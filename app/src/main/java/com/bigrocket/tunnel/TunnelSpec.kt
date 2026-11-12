package com.bigrocket.tunnel

/**
 * WHAT THE USER MAY TYPE - and what each form means.
 *
 * The requirement was literally "mount a normal IP or even a normal DNS in place of the static
 * IP". So the parser accepts, and the app treats identically, all of these:
 *
 * ```
 *   203.0.113.9                     plain IP            -> UDP carrier on port 5300
 *   203.0.113.9:5300                IP + port
 *   udp://203.0.113.9:5300          explicit UDP
 *   tcp://example.org:8443          TCP carrier (one ordered stream per radio)
 *   bridge.example.org              a NAME: resolved, and if the DNS answer carries the
 *                                   [port|cookie] scheme it is used as the endpoint directly
 *   bridge.example.org:5300         a NAME + explicit port
 *   x1.t.example.org                a name answered by the far side itself (see DnsRecords)
 *   https://dns.example.org/dns-query   DoH carrier (RFC 8484), behind your TLS terminator
 *   1.2.3.4:1080                    ^ these are still accepted as PROXIES (old behaviour):
 *   socks5://1.2.3.4:1080           the static-proxy egress, unchanged
 * ```
 *
 * The distinction between "an address" and "a proxy" is made here, once, and the result is
 * either a [TunnelSpec] (this file's engine 2) or a proxy (handed to `StaticProxyParser`).
 * That is what keeps old saved proxies working: this change is additive.
 *
 * Pure Kotlin, no Android - the unit tests drive exactly this code.
 */
data class TunnelSpec(
    /** The exact text the user typed, kept for round-tripping into the input box. */
    val source: String,
    val transport: Transport,
    val host: String,
    val port: Int,
    /** Path part, DoH only (`/dns-query`). */
    val path: String = "",
    /** True when [host] is a literal IP, so no lookup is needed before dialling. */
    val literalHost: Boolean = false,
    /** True when the user typed a bare name with no port: the DNS answer decides the port. */
    val portFromDnsAnswer: Boolean = false,
) {

    enum class Transport { UDP, TCP, DOH }

    /** Session tag: derived from what the user typed, so app and far side agree with no setup. */
    val cookie: Int get() = Brt.cookieFor(source)

    /** SIP002-ish canonical form stored in the app's encrypted prefs. */
    fun canonical(): String = when (transport) {
        Transport.UDP -> if (port == DEFAULT_UDP_PORT) host else "$host:$port"
        Transport.TCP -> "tcp://$host:$port"
        Transport.DOH -> "https://$host$path"
    }

    fun describe(): String = when (transport) {
        Transport.UDP -> "تونل آدرسی UDP → $host:$port (cookie $cookie)"
        Transport.TCP -> "تونل آدرسی TCP → $host:$port (cookie $cookie)"
        Transport.DOH -> "تونل آدرسی DoH → https://$host$path (cookie $cookie)"
    }

    /** Shown in the card: short and unambiguous about WHERE the frames go. */
    fun endpointLabel(): String = when (transport) {
        Transport.UDP -> "udp://$host:$port"
        Transport.TCP -> "tcp://$host:$port"
        Transport.DOH -> "https://$host$path"
    }

    companion object {
        const val DEFAULT_UDP_PORT = 5300
        const val DEFAULT_TCP_PORT = 5301
        const val DEFAULT_DOH_PATH = "/dns-query"
    }
}

/**
 * Thrown when the text is neither a usable address nor a proxy. Named `...ParseException` to
 * match the app's existing [com.bigrocket.egress.StaticProxyParseException], so the UI handles
 * both cards the same way.
 */
class TunnelSpecParseException(message: String) : Exception(message)

/**
 * The parser. Deliberately strict about *nonsense* (so the UI can say why) and permissive about
 * *forms* (so a user who knows only the IP can still just type it).
 */
object TunnelSpecParser {

    /** Persian/Arabic digits are common input on an Iranian keyboard; normalise before parsing. */
    private val digitMap = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9',
    )

    fun normaliseDigits(raw: String): String = buildString {
        raw.forEach { ch -> append(digitMap[ch] ?: ch) }
    }

    /**
     * @param raw whatever is in the address box.
     * @param proxyParser the app's existing proxy parser, injected so this file stays
     *        Android-free and so "is this a proxy?" is decided by exactly one implementation.
     * @return null when the text is a PROXY (the caller installs the static-proxy egress instead).
     * @throws TunnelSpecParseException when the text is neither.
     */
    fun parse(raw: String, proxyParser: (String) -> Boolean): TunnelSpec? {
        val text = normaliseDigits(raw).trim().removeSuffix("/")
        if (text.isEmpty()) throw TunnelSpecParseException("آدرس خالی است")

        // DoH first: `https://host/dns-query` is unambiguously a carrier, not a proxy... unless
        // it is a plain `https://host:port` which the proxy parser owns (TLS proxy).
        val lower = text.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            val isDoh = lower.contains("/dns") || lower.contains("?dns=") || lower.endsWith("/dns-query")
            if (isDoh) {
                val schemeEnd = text.indexOf("://") + 3
                val rest = text.substring(schemeEnd)
                val slash = rest.indexOf('/')
                val authority = if (slash < 0) rest else rest.substring(0, slash)
                val path = if (slash < 0) TunnelSpec.DEFAULT_DOH_PATH else rest.substring(slash)
                val (host, port) = splitAuthority(authority, DEFAULT_DOH_PORT, text)
                return TunnelSpec(
                    source = text,
                    transport = TunnelSpec.Transport.DOH,
                    host = host,
                    port = port,
                    path = if (path.isEmpty()) TunnelSpec.DEFAULT_DOH_PATH else path,
                    literalHost = isIpLiteral(host),
                )
            }
            if (proxyParser(text)) return null
        }

        if (lower.startsWith("tcp://")) {
            val (host, port) = splitAuthority(text.substring(6), TunnelSpec.DEFAULT_TCP_PORT, text)
            return TunnelSpec(text, TunnelSpec.Transport.TCP, host, port, literalHost = isIpLiteral(host))
        }

        if (lower.startsWith("udp://")) {
            val (host, port) = splitAuthority(text.substring(6), TunnelSpec.DEFAULT_UDP_PORT, text)
            return TunnelSpec(text, TunnelSpec.Transport.UDP, host, port, literalHost = isIpLiteral(host))
        }

        // PROXY OR ADDRESS? This is the one genuinely ambiguous decision, and the rule is
        // written down because both behaviours have to keep working:
        //
        //   * a bare IP or name with no explicit port        -> ADDRESS (this card's engine 2).
        //     This is the user's headline case: "mount a normal IP in place of the static IP".
        //     Before this change a bare IP meant "proxy, find the port yourself", which is
        //     exactly the per-flow port scan this card exists to remove.
        //   * an explicit proxy scheme (`socks5://`, `http://`, `https://` + port, `socks5h://`),
        //     credentials (`user:pass@host`), or an explicit port that is a WELL-KNOWN proxy port
        //     (1080, 1081, 8080, 8888, 3128, 9050, 8118) -> PROXY (old behaviour, unchanged).
        //   * anything else with an explicit port            -> ADDRESS on that port.
        //
        // So an existing saved proxy keeps working, and a plain address is never mistaken for one.
        if (looksLikeProxy(text) && proxyParser(text)) return null

        // A bare hostname is case-insensitive on the wire; keep the typed text for the cookie
        // (so the same typed value always yields the same session tag) but dial the lower-case
        // form, which is what resolvers expect.
        val hostText = text.lowercase()

        // Plain IP or name, with or without a port.
        val hasExplicitPort = hasPort(hostText)
        val (host, port) = if (hasExplicitPort) {
            splitAuthority(hostText, TunnelSpec.DEFAULT_UDP_PORT, text)
        } else {
            hostText to TunnelSpec.DEFAULT_UDP_PORT
        }
        if (host.isEmpty()) throw TunnelSpecParseException("آدرس نامعتبر: $raw")
        if (!isIpLiteral(host) && !isHostname(host)) {
            throw TunnelSpecParseException("این آدرس نه آی‌پی است و نه نام دامنه: $raw")
        }
        if (looksLikeIpv4(host) && !isValidIpv4(host)) {
            throw TunnelSpecParseException("آی‌پی نامعتبر: $host")
        }
        return TunnelSpec(
            source = text,
            transport = TunnelSpec.Transport.UDP,
            host = host,
            port = port,
            literalHost = isIpLiteral(host),
            // A bare NAME with no port means: the DNS answer tells us both where and how.
            portFromDnsAnswer = !hasExplicitPort && !isIpLiteral(host),
        )
    }

    private const val DEFAULT_DOH_PORT = 443

    /** Ports that mean "this is a proxy" when typed explicitly - the app's historical forms. */
    private val PROXY_PORTS = setOf(1080, 1081, 1082, 8000, 8080, 8081, 8118, 8888, 9050, 9150, 10808, 10809)

    /**
     * True when the text carries an *unambiguous* proxy marker. A bare IP with no port is
     * deliberately NOT a proxy any more (see [parse]): that is the whole point of this card.
     */
    fun looksLikeProxy(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.contains("://")) {
            // `https://host/dns-query` was already routed to the DoH carrier above.
            return true
        }
        if (lower.contains('@')) return true // credentials
        if (hasPort(text)) {
            val port = text.substringAfterLast(':').toIntOrNull()
            if (port != null && port in PROXY_PORTS) return true
        }
        // Legacy `host:port:user:pass`
        val parts = text.split(':')
        return parts.size == 4 && parts[1].toIntOrNull() != null
    }

    private fun splitAuthority(authorityRaw: String, defaultPort: Int, original: String): Pair<String, Int> {
        val authority = authorityRaw.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) throw TunnelSpecParseException("آدرس نامعتبر: $original")
        // IPv6 literal in brackets.
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) throw TunnelSpecParseException("آی‌پی نسخه ۶ نامعتبر: $authority")
            val host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            val port = if (rest.startsWith(":")) {
                rest.substring(1).toIntOrNull() ?: throw TunnelSpecParseException("پورت نامعتبر: $rest")
            } else {
                defaultPort
            }
            return host to port
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to defaultPort
        val portText = authority.substring(colon + 1)
        if (portText.isEmpty()) return authority.substring(0, colon) to defaultPort
        val port = portText.toIntOrNull() ?: throw TunnelSpecParseException("پورت نامعتبر: $portText")
        if (port !in 1..65535) throw TunnelSpecParseException("پورت خارج از محدوده: $port")
        return authority.substring(0, colon) to port
    }

    /** `host:port` with a numeric port, allowing an IPv6 literal in brackets. */
    private fun hasPort(text: String): Boolean {
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            return close >= 0 && text.length > close + 1 && text[close + 1] == ':'
        }
        val colon = text.lastIndexOf(':')
        if (colon <= 0 || colon == text.length - 1) return false
        return text.substring(colon + 1).all { it.isDigit() }
    }

    /** Four dot-separated runs of digits: an intended IPv4, valid or not. */
    private fun looksLikeIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }
    }

    fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        return isValidIpv4(host)
    }

    private fun isValidIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.all { it.isDigit() } && (part.toIntOrNull() ?: 999) in 0..255
        }
    }

    private fun isHostname(host: String): Boolean {
        if (host.length > 253 || !host.contains('.')) return false
        val labels = host.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        return labels.all { label ->
            label.first().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
                !label.startsWith("-")
        }
    }
}
