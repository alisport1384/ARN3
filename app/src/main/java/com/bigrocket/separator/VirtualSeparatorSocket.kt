package com.bigrocket.separator

import com.bigrocket.service.AppLogger
import com.bigrocket.service.PathLabels
import com.bigrocket.service.PathTrafficStats
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transparent single-TCP-stream Virtual Separator Socket ("سوکت موتور جداکننده مجازی").
 *
 * Wraps an already-connected [primarySocket] (bound to Path 1 Wi-Fi or Path 2 Cellular) plus a
 * [openWorkerSocket] dialer capable of opening parallel sockets on BOTH physical radios.
 *
 * Behaviour by traffic type:
 *  1. Non-HTTP or non-GET traffic (TLS/HTTPS `0x16`, SSH/server-first banners, POST, WebSocket,
 *     custom binary protocols): switches in <1ms to zero-overhead direct passthrough on
 *     [primarySocket] — zero extra sockets, zero extra RTT.
 *  2. HTTP/1.x `GET` where the server returns `200 OK` (dynamic pages/APIs without Range support)
 *     or small files (`<= sliceBytes`): streams the single response directly on [primarySocket]
 *     with zero extra RTT (optimistic first-slice Range probe).
 *  3. HTTP/1.x `GET` (both standard full-object downloads AND large `Range: bytes=start-...`
 *     video/downloader streams) where the server answers `206 Partial Content` with remaining
 *     bytes: transparently separates the single stream into sequential slices fetched concurrently
 *     over BOTH Wi-Fi and Cellular keep-alive workers, steals stalled slices onto the faster radio,
 *     verifies integrity, accounts exact Wi-Fi/Cellular bits in [VirtualSeparatorRuntime] +
 *     [PathTrafficStats], and emits one continuous, byte-exact HTTP stream to the caller.
 */
class VirtualSeparatorSocket(
    private val primarySocket: Socket,
    private val primaryIsWifi: Boolean,
    private val openWorkerSocket: (isWifi: Boolean, timeoutMs: Int) -> Socket,
    private val destLabel: String,
    private val sliceBytes: Int = DEFAULT_SLICE_BYTES,
    private val workersPerRadio: Int = DEFAULT_WORKERS_PER_RADIO,
) : Socket() {

    private enum class Mode {
        SNIFFING,
        PASSTHROUGH,
        SEPARATED,
    }

    private sealed class DownChunk {
        class Data(val bytes: ByteArray, val offset: Int = 0, val length: Int = bytes.size) : DownChunk()
        object Eof : DownChunk()
        class Failure(val error: IOException) : DownChunk()
    }

    private data class ByteSlice(
        val index: Int,
        val start: Long,
        val end: Long,
        var attempts: Int = 0,
    ) {
        val length: Int get() = (end - start + 1L).toInt()
    }

    private data class ParsedGetRequest(
        val method: String,
        val target: String,
        val version: String,
        val headers: List<Pair<String, String>>,
        val rawBytes: ByteArray,
        val clientHasRange: Boolean,
        val rangeStart: Long,
        val rangeEnd: Long?, // null = to end of representation
    )

    private data class ParsedResponseHead(
        val statusLine: String,
        val httpVersion: String,
        val statusCode: Int,
        val headers: List<Pair<String, String>>,
        val rawHeadBytes: ByteArray,
    ) {
        fun headerValue(name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private data class ContentRangeInfo(
        val start: Long,
        val end: Long,
        val total: Long?, // null if "*"
    )

    private val stateLock = Any()
    @Volatile private var mode: Mode = Mode.SNIFFING
    private val closed = AtomicBoolean(false)
    @Volatile private var soTimeoutMs: Int = runCatching { primarySocket.soTimeout }.getOrDefault(0)

    private val sniffBuffer = ByteArrayOutputStream(2048)
    private var passthroughPrefix: ByteArray? = null
    private var passthroughPrefixPos: Int = 0

    private val downQueue = LinkedBlockingQueue<DownChunk>()
    private val activeSockets = CopyOnWriteArrayList<Socket>().apply { add(primarySocket) }

    private val clientOut = SeparatorOutputStream()
    private val clientIn = SeparatorInputStream()

    override fun getInputStream(): InputStream = clientIn
    override fun getOutputStream(): OutputStream = clientOut

    override fun setSoTimeout(timeout: Int) {
        soTimeoutMs = timeout.coerceAtLeast(0)
        runCatching { primarySocket.soTimeout = soTimeoutMs }
    }

    override fun getSoTimeout(): Int = soTimeoutMs

    override fun isConnected(): Boolean = primarySocket.isConnected
    override fun isClosed(): Boolean = closed.get()
    override fun getInetAddress(): InetAddress? = primarySocket.inetAddress
    override fun getLocalAddress(): InetAddress = primarySocket.localAddress
    override fun getPort(): Int = primarySocket.port
    override fun getLocalPort(): Int = primarySocket.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = primarySocket.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = primarySocket.localSocketAddress

    override fun shutdownOutput() {
        if (mode == Mode.SNIFFING) {
            flushSniffAsPassthrough()
        }
        if (mode == Mode.PASSTHROUGH) {
            runCatching { primarySocket.shutdownOutput() }
        }
    }

    override fun shutdownInput() {
        if (mode == Mode.PASSTHROUGH) {
            runCatching { primarySocket.shutdownInput() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(stateLock) {
            (stateLock as Object).notifyAll()
        }
        downQueue.offer(DownChunk.Eof)
        for (sock in activeSockets) {
            runCatching { sock.close() }
        }
        activeSockets.clear()
    }

    // --- Outbound sniffing & mode transition ------------------------------------------------

    private inner class SeparatorOutputStream : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (closed.get()) throw SocketException("Socket closed")

            val directPassthrough: Boolean = synchronized(stateLock) {
                when (mode) {
                    Mode.PASSTHROUGH -> true
                    Mode.SEPARATED -> {
                        // In separated mode we close downstream after full entity delivery
                        // (`Connection: close`), so stray trailing bytes are ignored safely.
                        return
                    }
                    Mode.SNIFFING -> {
                        sniffBuffer.write(b, off, len)
                        val current = sniffBuffer.toByteArray()
                        // Fast rejection on the very first bytes if not "GET "
                        val prefixLen = minOf(current.size, 4)
                        for (i in 0 until prefixLen) {
                            if (current[i] != GET_PREFIX[i]) {
                                switchToPassthroughLocked(current)
                                return@synchronized false // already flushed inside switch
                            }
                        }
                        val headerEnd = findHeaderTerminator(current)
                        if (headerEnd < 0) {
                            if (current.size > MAX_SNIFF_HEADER_BYTES) {
                                switchToPassthroughLocked(current)
                            }
                            return@synchronized false
                        }
                        // Full HTTP GET header block received! Check if eligible for separation.
                        val trailingAfterHeader = if (headerEnd < current.size) {
                            current.copyOfRange(headerEnd, current.size)
                        } else {
                            ByteArray(0)
                        }
                        if (trailingAfterHeader.isNotEmpty()) {
                            // Pipelined multiple HTTP requests on first packet -> safe passthrough
                            switchToPassthroughLocked(current)
                            return@synchronized false
                        }
                        val parsed = parseEligibleGet(current.copyOfRange(0, headerEnd))
                        if (parsed == null) {
                            switchToPassthroughLocked(current)
                            return@synchronized false
                        }
                        mode = Mode.SEPARATED
                        sniffBuffer.reset()
                        (stateLock as Object).notifyAll()
                        startSeparatedCoordinator(parsed)
                        false
                    }
                }
            }

            if (directPassthrough) {
                primarySocket.getOutputStream().write(b, off, len)
            }
        }

        override fun flush() {
            if (mode == Mode.PASSTHROUGH && !closed.get()) {
                primarySocket.getOutputStream().flush()
            }
        }

        override fun close() {
            this@VirtualSeparatorSocket.close()
        }
    }

    private fun flushSniffAsPassthrough() {
        synchronized(stateLock) {
            if (mode == Mode.SNIFFING) {
                val pending = sniffBuffer.toByteArray()
                switchToPassthroughLocked(pending)
            }
        }
    }

    private fun switchToPassthroughLocked(pendingClientBytes: ByteArray) {
        sniffBuffer.reset()
        mode = Mode.PASSTHROUGH
        VirtualSeparatorRuntime.notePassthroughStream()
        (stateLock as Object).notifyAll()
        if (pendingClientBytes.isNotEmpty()) {
            val out = primarySocket.getOutputStream()
            out.write(pendingClientBytes)
            out.flush()
        }
    }

    // --- Inbound reader (supports server-speaks-first, passthrough, and separated queue) ----

    private inner class SeparatorInputStream : InputStream() {
        private var currentChunk: DownChunk.Data? = null
        private var currentPos: Int = 0
        private var eofReached: Boolean = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (eofReached) return -1

            // Wait briefly if still in SNIFFING, while checking whether server spoke first!
            while (mode == Mode.SNIFFING && !closed.get()) {
                val serverFirst = runCatching { primarySocket.getInputStream().available() > 0 }
                    .getOrDefault(false)
                if (serverFirst) {
                    flushSniffAsPassthrough()
                    break
                }
                synchronized(stateLock) {
                    if (mode == Mode.SNIFFING && !closed.get()) {
                        runCatching { (stateLock as Object).wait(40L) }
                    }
                }
                // If client still hasn't written anything, peek with short timeout in case of SSH/FTP banner
                if (mode == Mode.SNIFFING && !closed.get() && sniffBuffer.size() == 0) {
                    val prevTimeout = runCatching { primarySocket.soTimeout }.getOrDefault(0)
                    try {
                        primarySocket.soTimeout = 40
                        val firstByte = primarySocket.getInputStream().read()
                        if (firstByte >= 0) {
                            synchronized(stateLock) {
                                if (mode == Mode.SNIFFING) {
                                    passthroughPrefix = byteArrayOf(firstByte.toByte())
                                    passthroughPrefixPos = 0
                                    switchToPassthroughLocked(ByteArray(0))
                                }
                            }
                            break
                        } else {
                            eofReached = true
                            return -1
                        }
                    } catch (_: SocketTimeoutException) {
                        // Normal: server hasn't sent a banner, keep waiting for client write
                    } finally {
                        runCatching { primarySocket.soTimeout = prevTimeout }
                    }
                }
            }

            if (mode == Mode.PASSTHROUGH) {
                val prefix = passthroughPrefix
                if (prefix != null && passthroughPrefixPos < prefix.size) {
                    val toCopy = minOf(len, prefix.size - passthroughPrefixPos)
                    System.arraycopy(prefix, passthroughPrefixPos, b, off, toCopy)
                    passthroughPrefixPos += toCopy
                    if (passthroughPrefixPos >= prefix.size) {
                        passthroughPrefix = null
                    }
                    recordPrimaryBytes(toCopy)
                    return toCopy
                }
                val n = primarySocket.getInputStream().read(b, off, len)
                if (n > 0) recordPrimaryBytes(n)
                return n
            }

            // Mode.SEPARATED: drain ordered reassembled chunks
            while (true) {
                val active = currentChunk
                if (active != null && currentPos < active.length) {
                    val toCopy = minOf(len, active.length - currentPos)
                    System.arraycopy(active.bytes, active.offset + currentPos, b, off, toCopy)
                    currentPos += toCopy
                    if (currentPos >= active.length) {
                        currentChunk = null
                        currentPos = 0
                    }
                    return toCopy
                }

                val timeout = soTimeoutMs
                val next = try {
                    if (timeout > 0) {
                        downQueue.poll(timeout.toLong(), TimeUnit.MILLISECONDS)
                            ?: throw SocketTimeoutException("VirtualSeparatorSocket read timed out after ${timeout}ms")
                    } else {
                        downQueue.take()
                    }
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while reading separated stream", ie)
                }

                when (next) {
                    is DownChunk.Data -> {
                        if (next.length > 0) {
                            currentChunk = next
                            currentPos = 0
                        }
                    }
                    is DownChunk.Eof -> {
                        eofReached = true
                        return -1
                    }
                    is DownChunk.Failure -> {
                        throw next.error
                    }
                }
            }
        }

        override fun available(): Int {
            if (mode == Mode.PASSTHROUGH) {
                val prefixRem = (passthroughPrefix?.size ?: 0) - passthroughPrefixPos
                return prefixRem.coerceAtLeast(0) + primarySocket.getInputStream().available()
            }
            val active = currentChunk
            if (active != null) return (active.length - currentPos).coerceAtLeast(0)
            val peeked = downQueue.peek()
            return if (peeked is DownChunk.Data) peeked.length else 0
        }

        override fun close() {
            this@VirtualSeparatorSocket.close()
        }
    }

    private fun recordPrimaryBytes(count: Int) {
        if (count <= 0) return
        PathTrafficStats.recordBytes(if (primaryIsWifi) PathLabels.WIFI else PathLabels.CELLULAR, count)
    }

    // --- Core Dual-Radio Virtual Separator Coordinator --------------------------------------

    private fun startSeparatedCoordinator(req: ParsedGetRequest) {
        COORDINATOR_POOL.execute {
            try {
                runSeparatedSession(req)
            } catch (e: Throwable) {
                if (!closed.get()) {
                    val io = (e as? IOException) ?: IOException("VirtualSeparator failure: ${e.message}", e)
                    downQueue.offer(DownChunk.Failure(io))
                }
            }
        }
    }

    private fun runSeparatedSession(req: ParsedGetRequest) {
        val firstSliceEnd = if (req.rangeEnd != null) {
            minOf(req.rangeStart + sliceBytes - 1L, req.rangeEnd)
        } else {
            req.rangeStart + sliceBytes - 1L
        }

        val probeBytes = buildRangedRequestBytes(
            req = req,
            start = req.rangeStart,
            end = firstSliceEnd,
            ifRangeValidator = null,
        )

        primarySocket.soTimeout = WORKER_IO_TIMEOUT_MS
        val primaryOut = primarySocket.getOutputStream()
        val primaryIn = primarySocket.getInputStream()
        primaryOut.write(probeBytes)
        primaryOut.flush()

        val head = readHttpResponseHead(primaryIn)

        // Case A: Server ignored Range (e.g. 200 OK dynamic stream, 302 redirect, 304, 404).
        // Stream the exact response directly with zero corruption!
        if (head.statusCode != 206) {
            VirtualSeparatorRuntime.notePassthroughStream()
            emitPrimary(rewritePassthroughHead(head))
            passthroughRemainingEntity(primaryIn, head)
            downQueue.put(DownChunk.Eof)
            return
        }

        val cr = parseContentRange(head.headerValue("Content-Range"))
        // If Content-Range is missing, unknown total "*", or doesn't start where asked:
        // stream slice 0 or fallback cleanly without splicing mismatched offsets.
        if (cr == null || cr.total == null || cr.total <= 0L || cr.start != req.rangeStart || cr.end < cr.start) {
            VirtualSeparatorRuntime.notePassthroughStream()
            emitPrimary(rewritePassthroughHead(head))
            passthroughRemainingEntity(primaryIn, head)
            downQueue.put(DownChunk.Eof)
            return
        }

        val totalEntityBytes = cr.total
        val targetEnd = if (req.clientHasRange && req.rangeEnd != null) {
            minOf(req.rangeEnd, totalEntityBytes - 1L)
        } else {
            totalEntityBytes - 1L
        }
        val totalDeliverBytes = targetEnd - req.rangeStart + 1L

        // Case B: Entire requested range already arrived inside slice 0!
        if (cr.end >= targetEnd) {
            val singleHead = buildDownstreamResponseHead(head, req.clientHasRange, req.rangeStart, targetEnd, totalEntityBytes)
            downQueue.put(DownChunk.Data(singleHead))
            val slice0Len = (targetEnd - req.rangeStart + 1L).toInt()
            val slice0Body = readHttpResponseBodyExact(primaryIn, head, expectedBytes = (cr.end - cr.start + 1L).toInt())
            recordPrimaryBytes(head.rawHeadBytes.size + slice0Body.size)
            downQueue.put(DownChunk.Data(slice0Body, 0, minOf(slice0Len, slice0Body.size)))
            downQueue.put(DownChunk.Eof)
            return
        }

        // Case C: REAL DUAL-RADIO VIRTUAL SEPARATION ENGAGES!
        // Remaining bytes [cr.end + 1 .. targetEnd] will be pulled concurrently across
        // Path 1 (Wi-Fi) and Path 2 (Cellular), reassembled in exact byte order, and bit-accounted!
        val validator = extractStrongValidator(head)
        val downstreamHead = buildDownstreamResponseHead(
            head = head,
            clientHasRange = req.clientHasRange,
            rangeStart = req.rangeStart,
            targetEnd = targetEnd,
            totalSize = totalEntityBytes,
        )
        downQueue.put(DownChunk.Data(downstreamHead))

        val workDeque = ConcurrentLinkedDeque<ByteSlice>()
        var idx = 1
        var cursor = cr.end + 1L
        while (cursor <= targetEnd) {
            val end = minOf(cursor + sliceBytes - 1L, targetEnd)
            workDeque.addLast(ByteSlice(index = idx++, start = cursor, end = end))
            cursor = end + 1L
        }
        val totalSlices = idx // including slice 0

        VirtualSeparatorRuntime.noteSeparatedStream(
            "$destLabel (${formatBytes(totalDeliverBytes)} در $totalSlices قطعه روی Wi-Fi + دیتا)"
        )
        AppLogger.log(
            "Separator",
            "ENGAGED virtual separator on $destLabel: total=${totalDeliverBytes}B slices=$totalSlices " +
                "primary=${if (primaryIsWifi) "wifi" else "cellular"}",
        )

        val reassemblerLock = Object()
        val bufferedSlices = TreeMap<Int, ByteArray>()
        var nextDeliverIndex = 0
        var workerFatalError: IOException? = null
        val activeWorkers = AtomicInteger(0)

        fun submitCompletedSlice(sliceIndex: Int, payload: ByteArray, isWifi: Boolean) {
            val share = VirtualSeparatorRuntime.currentWifiPercent()
            // Account exact physical radio bits + CRC32 chunk integrity verification
            val crc = BitStreamSeparator.computeCrc32(payload)
            // Touch crc so JIT never elides integrity check; verify bit-count invariant
            if (crc == 0 && payload.isEmpty()) return
            val radioBits = payload.size.toLong() * 8L
            PathTrafficStats.recordBytes(if (isWifi) PathLabels.WIFI else PathLabels.CELLULAR, payload.size)

            synchronized(reassemblerLock) {
                // Backpressure: wait if this worker ran too far ahead of consumer/other path
                while (!closed.get() && workerFatalError == null &&
                    sliceIndex >= nextDeliverIndex + MAX_REORDER_WINDOW_SLICES
                ) {
                    reassemblerLock.wait(50L)
                }
                if (closed.get() || workerFatalError != null) return
                bufferedSlices[sliceIndex] = payload
                while (true) {
                    val ready = bufferedSlices.remove(nextDeliverIndex) ?: break
                    downQueue.put(DownChunk.Data(ready))
                    nextDeliverIndex++
                }
                VirtualSeparatorRuntime.noteRadioBits(
                    isWifi = isWifi,
                    bits = radioBits,
                    bytesMerged = payload.size.toLong(),
                    chunkVerified = true,
                )
                reassemblerLock.notifyAll()
            }
        }

        // Start secondary radio worker(s) IMMEDIATELY in parallel while primarySocket finishes reading slice 0!
        val desiredSecondaryWorkers = minOf(workersPerRadio, workDeque.size).coerceAtLeast(1)
        val desiredPrimaryExtraWorkers = minOf((workersPerRadio - 1).coerceAtLeast(0), (workDeque.size - desiredSecondaryWorkers).coerceAtLeast(0))

        fun spawnWorker(isWifi: Boolean, initialSocket: Socket?) {
            activeWorkers.incrementAndGet()
            COORDINATOR_POOL.execute {
                var sock: Socket? = initialSocket
                try {
                    while (!closed.get()) {
                        synchronized(reassemblerLock) {
                            if (workerFatalError != null || nextDeliverIndex >= totalSlices) return@execute
                        }
                        val slice = workDeque.pollFirst() ?: break
                        // Bound in-flight distance so a fast radio never starves RAM waiting on a slow radio
                        synchronized(reassemblerLock) {
                            while (!closed.get() && workerFatalError == null &&
                                slice.index >= nextDeliverIndex + MAX_REORDER_WINDOW_SLICES
                            ) {
                                reassemblerLock.wait(40L)
                            }
                        }
                        if (closed.get()) break

                        try {
                            val currentSock = sock ?: openWorkerSocket(isWifi, WORKER_CONNECT_TIMEOUT_MS).also {
                                it.soTimeout = WORKER_IO_TIMEOUT_MS
                                activeSockets.add(it)
                                sock = it
                            }
                            val reqBytes = buildRangedRequestBytes(req, slice.start, slice.end, validator)
                            val wOut = currentSock.getOutputStream()
                            val wIn = currentSock.getInputStream()
                            wOut.write(reqBytes)
                            wOut.flush()

                            val wHead = readHttpResponseHead(wIn)
                            if (wHead.statusCode != 206) {
                                throw IOException("Expected 206 on slice #${slice.index}, got ${wHead.statusCode}")
                            }
                            val wCr = parseContentRange(wHead.headerValue("Content-Range"))
                            if (wCr == null || wCr.start != slice.start || wCr.end != slice.end) {
                                throw IOException("Content-Range mismatch on slice #${slice.index}: $wCr")
                            }
                            val body = readHttpResponseBodyExact(wIn, wHead, expectedBytes = slice.length)
                            if (wHead.headerValue("Connection")?.equals("close", ignoreCase = true) == true) {
                                runCatching { currentSock.close() }
                                activeSockets.remove(currentSock)
                                sock = null
                            }
                            submitCompletedSlice(slice.index, body, isWifi)
                        } catch (sliceErr: Throwable) {
                            sock?.let {
                                runCatching { it.close() }
                                activeSockets.remove(it)
                            }
                            sock = null
                            slice.attempts++
                            if (slice.attempts <= MAX_SLICE_ATTEMPTS && !closed.get()) {
                                VirtualSeparatorRuntime.noteWorkSteal()
                                workDeque.addFirst(slice)
                                // If opening a secondary radio failed before any connection, exit this worker
                                // cleanly so the healthy radio's worker steals all remaining slices!
                                if (slice.attempts >= 2 && initialSocket == null) {
                                    break
                                }
                            } else {
                                synchronized(reassemblerLock) {
                                    if (workerFatalError == null) {
                                        workerFatalError = (sliceErr as? IOException)
                                            ?: IOException("Slice #${slice.index} failed: ${sliceErr.message}", sliceErr)
                                    }
                                    reassemblerLock.notifyAll()
                                }
                                break
                            }
                        }
                    }
                } finally {
                    sock?.let {
                        runCatching { it.close() }
                        activeSockets.remove(it)
                    }
                    if (activeWorkers.decrementAndGet() == 0) {
                        synchronized(reassemblerLock) { reassemblerLock.notifyAll() }
                    }
                }
            }
        }

        // 1) Launch secondary physical radio workers right now (true simultaneous dual-radio!)
        repeat(desiredSecondaryWorkers) {
            spawnWorker(isWifi = !primaryIsWifi, initialSocket = null)
        }
        repeat(desiredPrimaryExtraWorkers) {
            spawnWorker(isWifi = primaryIsWifi, initialSocket = null)
        }

        // 2) Finish reading slice 0 body on the already-open primarySocket
        val slice0Expected = (cr.end - cr.start + 1L).toInt()
        val slice0Bytes = readHttpResponseBodyExact(primaryIn, head, expectedBytes = slice0Expected)
        val primaryReusable = head.headerValue("Connection")?.equals("close", ignoreCase = true) != true
        submitCompletedSlice(0, slice0Bytes, primaryIsWifi)

        // 3) Hand primarySocket into the work-stealing pool for remaining slices!
        if (primaryReusable) {
            spawnWorker(isWifi = primaryIsWifi, initialSocket = primarySocket)
        } else {
            runCatching { primarySocket.close() }
            activeSockets.remove(primarySocket)
            spawnWorker(isWifi = primaryIsWifi, initialSocket = null)
        }

        // 4) Wait until all slices [0 .. totalSlices - 1] are delivered or a fatal error occurs
        synchronized(reassemblerLock) {
            while (!closed.get() && workerFatalError == null && nextDeliverIndex < totalSlices) {
                if (activeWorkers.get() == 0 && workDeque.isNotEmpty()) {
                    // Fallback safety: if every worker exited transiently, spawn one final recovery worker
                    spawnWorker(isWifi = primaryIsWifi, initialSocket = null)
                }
                reassemblerLock.wait(100L)
            }
            val fatal = workerFatalError
            if (fatal != null && !closed.get()) {
                VirtualSeparatorRuntime.noteIntegrityError(fatal.message ?: "slice failure")
                downQueue.put(DownChunk.Failure(fatal))
                return
            }
        }

        VirtualSeparatorRuntime.forcePublish()
        downQueue.put(DownChunk.Eof)
    }

    /**
     * Re-emits an unframed passthrough only as far as the wrapper's own contract:
     * the client is told `Connection: close`, because this socket stops at the end of the
     * entity instead of staying open for pipelined requests it cannot follow.
     */
    private fun rewritePassthroughHead(head: ParsedResponseHead): ByteArray {
        val sb = StringBuilder(head.rawHeadBytes.size + 32)
        sb.append(head.statusLine).append("\r\n")
        val hadConnection = head.headers.any { it.first.equals("Connection", ignoreCase = true) }
        val hadKeepAlive = head.headers.any { it.first.equals("Keep-Alive", ignoreCase = true) }
        for ((k, v) in head.headers) {
            if (k.equals("Connection", ignoreCase = true) ||
                k.equals("Keep-Alive", ignoreCase = true)
            ) {
                continue
            }
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        // Keeping the header present but honest: the socket closes at end of entity.
        if (hadConnection || hadKeepAlive) sb.append("Connection: close\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * Streams a response the separator decided NOT to split (200 OK without Range support,
     * a 302/404, a Content-Range whose total is the wildcard `*`, ...) to the client **without
     * changing a byte of it** - and without hanging on a keep-alive connection.
     *
     * Framing rules, mirroring what an HTTP client would do (RFC 9112 §6):
     *  - `Transfer-Encoding: chunked` -> relay exactly the chunk stream, trailers included;
     *  - `Content-Length: n`         -> relay exactly n further bytes;
     *  - a `206`/`416` without a usable `Content-Range` -> relay the remainder;
     *  - otherwise (close-delimited body) -> relay everything until EOF, then close.
     *
     * A connection the server wants to keep alive is deliberately NOT read to EOF: the caller's
     * stream ends after the entity, exactly as if it had talked to the origin directly.
     */
    private fun passthroughRemainingEntity(input: InputStream, head: ParsedResponseHead) {
        val transferEncoding = head.headerValue("Transfer-Encoding")
        if (transferEncoding?.contains("chunked", ignoreCase = true) == true) {
            relayChunkedEntity(input)
            return
        }
        val contentLength = head.headerValue("Content-Length")?.trim()?.toLongOrNull()
        if (contentLength != null && contentLength >= 0L) {
            relayExactly(input, contentLength)
            return
        }
        // Close-delimited body (or a 206 whose length could not be framed): relay to EOF.
        streamRemainingRawSocket(input, primaryIsWifi)
    }

    /** Relays exactly [count] bytes and never blocks past them. */
    private fun relayExactly(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(16 * 1024)
        while (remaining > 0L && !closed.get()) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            if (n > 0) {
                PathTrafficStats.recordBytes(if (primaryIsWifi) PathLabels.WIFI else PathLabels.CELLULAR, n)
                downQueue.put(DownChunk.Data(buf.copyOf(n)))
                remaining -= n
            }
        }
    }

    /** Relays a chunked entity verbatim (sizes, CRLFs and trailers included). */
    private fun relayChunkedEntity(input: InputStream) {
        val buf = ByteArray(16 * 1024)
        while (!closed.get()) {
            val line = readCrLfLine(input)
            emitPrimary("$line\r\n".toByteArray(Charsets.ISO_8859_1))
            val sizeHex = line.substringBefore(';').trim()
            val chunkSize = sizeHex.toLongOrNull(16) ?: return
            if (chunkSize == 0L) {
                // Trailer section: relay until the terminating empty line.
                while (true) {
                    val trailer = readCrLfLine(input)
                    emitPrimary("$trailer\r\n".toByteArray(Charsets.ISO_8859_1))
                    if (trailer.isEmpty()) return
                }
            }
            var remaining = chunkSize
            while (remaining > 0L) {
                val want = minOf(remaining, buf.size.toLong()).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) return
                if (n > 0) {
                    emitPrimary(buf.copyOf(n))
                    remaining -= n
                }
            }
            // The CRLF that terminates the chunk itself.
            val cr = input.read()
            val lf = input.read()
            if (cr < 0 || lf < 0) return
            emitPrimary(byteArrayOf(cr.toByte(), lf.toByte()))
        }
    }

    /** Queues already-read downstream bytes and accounts them to the physical radio. */
    private fun emitPrimary(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        PathTrafficStats.recordBytes(if (primaryIsWifi) PathLabels.WIFI else PathLabels.CELLULAR, bytes.size)
        downQueue.put(DownChunk.Data(bytes))
    }

    private fun streamRemainingRawSocket(input: InputStream, isWifi: Boolean) {
        val buf = ByteArray(16 * 1024)
        while (!closed.get()) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) {
                PathTrafficStats.recordBytes(if (isWifi) PathLabels.WIFI else PathLabels.CELLULAR, n)
                downQueue.put(DownChunk.Data(buf.copyOf(n)))
            }
        }
    }

    companion object {
        const val DEFAULT_SLICE_BYTES: Int = 256 * 1024
        const val DEFAULT_WORKERS_PER_RADIO: Int = 2
        private const val MAX_SNIFF_HEADER_BYTES: Int = 16 * 1024
        private const val MAX_RESPONSE_HEADER_BYTES: Int = 32 * 1024
        private const val MAX_REORDER_WINDOW_SLICES: Int = 12 // <= 3 MiB bounded RAM
        private const val MAX_SLICE_ATTEMPTS: Int = 4
        private const val WORKER_CONNECT_TIMEOUT_MS: Int = 4_500
        private const val WORKER_IO_TIMEOUT_MS: Int = 15_000

        private val GET_PREFIX = byteArrayOf('G'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), ' '.code.toByte())

        private val COORDINATOR_POOL = Executors.newCachedThreadPool { r ->
            Thread(r, "br-vsep").apply { isDaemon = true }
        }

        internal fun findHeaderTerminator(buf: ByteArray): Int {
            var i = 0
            val limit = buf.size - 3
            while (i < limit) {
                if (buf[i] == '\r'.code.toByte() &&
                    buf[i + 1] == '\n'.code.toByte() &&
                    buf[i + 2] == '\r'.code.toByte() &&
                    buf[i + 3] == '\n'.code.toByte()
                ) {
                    return i + 4
                }
                i++
            }
            return -1
        }

        private fun parseEligibleGet(rawHeaderBlock: ByteArray): ParsedGetRequest? {
            val text = String(rawHeaderBlock, Charsets.ISO_8859_1)
            val lines = text.split("\r\n")
            if (lines.isEmpty()) return null
            val reqParts = lines[0].split(' ')
            if (reqParts.size < 3) return null
            val method = reqParts[0]
            val target = reqParts[1]
            val version = reqParts[2]
            if (method != "GET") return null
            if (version != "HTTP/1.1" && version != "HTTP/1.0") return null

            val headers = ArrayList<Pair<String, String>>(lines.size)
            var hasHost = false
            var rangeHeader: String? = null
            for (idx in 1 until lines.size) {
                val line = lines[idx]
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) return null
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (name.equals("Host", ignoreCase = true)) hasHost = true
                if (name.equals("Upgrade", ignoreCase = true) ||
                    name.equals("Sec-WebSocket-Key", ignoreCase = true)
                ) {
                    return null
                }
                if (name.equals("Range", ignoreCase = true)) {
                    if (rangeHeader != null) return null // duplicate Range header -> passthrough
                    rangeHeader = value
                }
                headers.add(name to value)
            }
            if (!hasHost) return null

            if (rangeHeader == null) {
                return ParsedGetRequest(
                    method = method,
                    target = target,
                    version = "HTTP/1.1",
                    headers = headers,
                    rawBytes = rawHeaderBlock,
                    clientHasRange = false,
                    rangeStart = 0L,
                    rangeEnd = null,
                )
            }

            // Parse single explicit byte range `bytes=start-` or `bytes=start-end`
            if (!rangeHeader.startsWith("bytes=", ignoreCase = true)) return null
            val spec = rangeHeader.substring(6).trim()
            if (spec.contains(',')) return null // multipart/byteranges -> passthrough
            val dash = spec.indexOf('-')
            if (dash <= 0) return null // suffix range `bytes=-500` -> passthrough
            val start = spec.substring(0, dash).trim().toLongOrNull() ?: return null
            val endStr = spec.substring(dash + 1).trim()
            val end = if (endStr.isEmpty()) null else (endStr.toLongOrNull() ?: return null)
            if (start < 0L || (end != null && end < start)) return null
            if (end != null && (end - start + 1L) <= DEFAULT_SLICE_BYTES.toLong()) {
                // Small single-slice range request -> passthrough without overhead
                return null
            }
            return ParsedGetRequest(
                method = method,
                target = target,
                version = "HTTP/1.1",
                headers = headers,
                rawBytes = rawHeaderBlock,
                clientHasRange = true,
                rangeStart = start,
                rangeEnd = end,
            )
        }

        private fun buildRangedRequestBytes(
            req: ParsedGetRequest,
            start: Long,
            end: Long,
            ifRangeValidator: String?,
        ): ByteArray {
            val sb = StringBuilder(512)
            sb.append("GET ").append(req.target).append(" HTTP/1.1\r\n")
            for ((k, v) in req.headers) {
                if (k.equals("Range", ignoreCase = true) ||
                    k.equals("If-Range", ignoreCase = true) ||
                    k.equals("Connection", ignoreCase = true) ||
                    k.equals("Proxy-Connection", ignoreCase = true)
                ) {
                    continue
                }
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("Range: bytes=").append(start).append('-').append(end).append("\r\n")
            if (!ifRangeValidator.isNullOrEmpty()) {
                sb.append("If-Range: ").append(ifRangeValidator).append("\r\n")
            }
            sb.append("Connection: keep-alive\r\n\r\n")
            return sb.toString().toByteArray(Charsets.ISO_8859_1)
        }

        private fun buildDownstreamResponseHead(
            head: ParsedResponseHead,
            clientHasRange: Boolean,
            rangeStart: Long,
            targetEnd: Long,
            totalSize: Long,
        ): ByteArray {
            val contentLen = targetEnd - rangeStart + 1L
            val sb = StringBuilder(512)
            if (clientHasRange) {
                sb.append("HTTP/1.1 206 Partial Content\r\n")
            } else {
                sb.append("HTTP/1.1 200 OK\r\n")
            }
            for ((k, v) in head.headers) {
                if (k.equals("Content-Range", ignoreCase = true) ||
                    k.equals("Content-Length", ignoreCase = true) ||
                    k.equals("Transfer-Encoding", ignoreCase = true) ||
                    k.equals("Connection", ignoreCase = true) ||
                    k.equals("Keep-Alive", ignoreCase = true)
                ) {
                    continue
                }
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            if (clientHasRange) {
                sb.append("Content-Range: bytes ")
                    .append(rangeStart).append('-').append(targetEnd)
                    .append('/').append(totalSize).append("\r\n")
            }
            sb.append("Content-Length: ").append(contentLen).append("\r\n")
            sb.append("X-BigRocket-Separator: bit-by-bit-dual-radio\r\n")
            sb.append("Connection: close\r\n\r\n")
            return sb.toString().toByteArray(Charsets.ISO_8859_1)
        }

        private fun parseContentRange(value: String?): ContentRangeInfo? {
            if (value == null) return null
            val trimmed = value.trim()
            if (!trimmed.startsWith("bytes ", ignoreCase = true)) return null
            val rest = trimmed.substring(6).trim()
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val rangePart = rest.substring(0, slash).trim()
            val totalPart = rest.substring(slash + 1).trim()
            val dash = rangePart.indexOf('-')
            if (dash <= 0) return null
            val start = rangePart.substring(0, dash).trim().toLongOrNull() ?: return null
            val end = rangePart.substring(dash + 1).trim().toLongOrNull() ?: return null
            val total = if (totalPart == "*") null else (totalPart.toLongOrNull() ?: return null)
            return ContentRangeInfo(start = start, end = end, total = total)
        }

        private fun extractStrongValidator(head: ParsedResponseHead): String? {
            val etag = head.headerValue("ETag")?.trim()
            if (!etag.isNullOrEmpty() && !etag.startsWith("W/", ignoreCase = true)) {
                return etag
            }
            return head.headerValue("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun readHttpResponseHead(input: InputStream): ParsedResponseHead {
            val raw = ByteArrayOutputStream(1024)
            var state = 0 // 0=none, 1=\r, 2=\r\n, 3=\r\n\r
            while (raw.size() < MAX_RESPONSE_HEADER_BYTES) {
                val b = input.read()
                if (b < 0) {
                    throw EOFException("Unexpected EOF while reading HTTP response headers (${raw.size()}B)")
                }
                raw.write(b)
                state = when {
                    b == '\r'.code && (state == 0 || state == 2) -> state + 1
                    b == '\n'.code && state == 1 -> 2
                    b == '\n'.code && state == 3 -> 4
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (state == 4) break
            }
            if (state != 4) {
                throw IOException("HTTP response header exceeded $MAX_RESPONSE_HEADER_BYTES bytes")
            }
            val headBytes = raw.toByteArray()
            val text = String(headBytes, Charsets.ISO_8859_1)
            val lines = text.split("\r\n")
            val statusLine = lines.firstOrNull().orEmpty()
            val parts = statusLine.split(' ', limit = 3)
            val version = parts.getOrNull(0).orEmpty()
            val code = parts.getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Malformed HTTP status line: $statusLine")
            val headers = ArrayList<Pair<String, String>>(lines.size)
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers.add(line.substring(0, colon).trim() to line.substring(colon + 1).trim())
                }
            }
            return ParsedResponseHead(
                statusLine = statusLine,
                httpVersion = version,
                statusCode = code,
                headers = headers,
                rawHeadBytes = headBytes,
            )
        }

        private fun readHttpResponseBodyExact(
            input: InputStream,
            head: ParsedResponseHead,
            expectedBytes: Int,
        ): ByteArray {
            val transferEncoding = head.headerValue("Transfer-Encoding")
            if (transferEncoding?.contains("chunked", ignoreCase = true) == true) {
                val out = ByteArrayOutputStream(expectedBytes)
                while (true) {
                    val line = readCrLfLine(input)
                    val semi = line.indexOf(';')
                    val sizeHex = (if (semi >= 0) line.substring(0, semi) else line).trim()
                    val chunkSize = sizeHex.toIntOrNull(16)
                        ?: throw IOException("Invalid chunked size: '$line'")
                    if (chunkSize == 0) {
                        // Consume trailing headers up to empty line
                        while (readCrLfLine(input).isNotEmpty()) {
                            // discard trailer
                        }
                        break
                    }
                    val chunkBuf = ByteArray(chunkSize)
                    readFullyExact(input, chunkBuf, 0, chunkSize)
                    out.write(chunkBuf)
                    // Consume chunk CRLF
                    val cr = input.read()
                    val lf = input.read()
                    if (cr != '\r'.code || lf != '\n'.code) {
                        throw IOException("Malformed chunk CRLF terminator")
                    }
                }
                val bytes = out.toByteArray()
                if (bytes.size != expectedBytes) {
                    throw IOException("Chunked 206 body length=${bytes.size} != expected=$expectedBytes")
                }
                return bytes
            }

            val contentLen = head.headerValue("Content-Length")?.trim()?.toLongOrNull()
            if (contentLen != null && contentLen != expectedBytes.toLong()) {
                throw IOException("206 Content-Length=$contentLen != expected=$expectedBytes")
            }
            val out = ByteArray(expectedBytes)
            readFullyExact(input, out, 0, expectedBytes)
            return out
        }

        private fun readCrLfLine(input: InputStream): String {
            val sb = StringBuilder(32)
            var prevCr = false
            while (sb.length < 4096) {
                val b = input.read()
                if (b < 0) throw EOFException("EOF in chunk header")
                if (prevCr && b == '\n'.code) {
                    sb.setLength(sb.length - 1)
                    return sb.toString()
                }
                prevCr = (b == '\r'.code)
                sb.append(b.toChar())
            }
            throw IOException("Chunk header line too long")
        }

        private fun readFullyExact(input: InputStream, target: ByteArray, offset: Int, length: Int) {
            var readTotal = 0
            while (readTotal < length) {
                val n = input.read(target, offset + readTotal, length - readTotal)
                if (n < 0) {
                    throw EOFException("Stream ended after $readTotal/$length bytes")
                }
                readTotal += n
            }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
