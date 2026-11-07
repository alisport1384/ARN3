package com.bigrocket.egress

/**
 * The "static IP proxy" the user can pin above the Connect button.
 *
 * Why this exists (see docs/StaticProxyEgress.md):
 *
 * BigRocket already merges Path 1 (Wi-Fi) and Path 2 (Cellular) at a single logical
 * boundary - [com.bigrocket.service.BondingSocksServer] for the chained modes and
 * [com.bigrocket.service.TcpRelayEngine]/UdpRelayEngine for Direct mode. That boundary is
 * the app-level equivalent of the "virtual VPS": it is where both pipes come together.
 * What it previously lacked was a single, stable public address to leave the phone from -
 * every flow left from whichever physical path happened to win the pick.
 *
 * [StaticProxyConfig] is that address. When it is set, the virtual VPS's egress is the
 * proxy: both paths are merged first (bonding), then the merged stream is carried inside
 * the proxy protocol to the user's proxy, and the proxy - not the phone - is what the
 * internet sees. So the observed public IP is the static proxy IP, in every mode
 * (Direct / Direct+Aether / Direct+Xray / the two chains).
 *
 * Supported input forms (all of these parse to the same type):
 *
 *  1.2.3.4                        -> SOCKS5, port 1080 (an IP with no port is allowed)
 *  1.2.3.4:1080                   -> SOCKS5
 *  host.example.com:8080          -> SOCKS5 (a hostname is allowed, resolves on the chosen path)
 *  socks5://1.2.3.4:1080          -> SOCKS5, explicit
 *  socks5://user:pass@1.2.3.4:1080
 *  socks5h://user:pass@host:1080  -> same as socks5 (remote DNS is always used)
 *  http://1.2.3.4:8080            -> HTTP CONNECT proxy
 *  https://user:pass@1.2.3.4:443  -> HTTP CONNECT proxy over TLS
 *  user:pass@1.2.3.4:1080         -> SOCKS5 with credentials, no scheme
 *  1.2.3.4:1080:user:pass         -> legacy colon form (proxy-list style)
 *  [2001:db8::1]:1080             -> IPv6 literal
 */
enum class StaticProxyKind {
    SOCKS5,
    HTTP;

    /** Only SOCKS5 can carry UDP (ASSOCIATE); HTTP proxies are TCP-only (CONNECT). */
    val supportsUdp: Boolean get() = this == SOCKS5

    fun defaultPort(): Int = when (this) {
        SOCKS5 -> 1080
        HTTP -> 8080
    }

    fun displayName(): String = when (this) {
        SOCKS5 -> "SOCKS5"
        HTTP -> "HTTP"
    }
}

data class StaticProxyConfig(
    val kind: StaticProxyKind,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    /** true only for `https://` proxies: the connection to the proxy itself is TLS-wrapped. */
    val proxyOverTls: Boolean = false,
    /**
     * True when the user gave an IP/host with NO port. BigRocket then detects the port itself
     * (see ProxyPortDetector) instead of guessing: a bare IP is a legitimate input, and a wrong
     * guess is indistinguishable from "the proxy is down", which is exactly the confusing
     * failure this flag removes. [port] then holds the default, used only as a hint.
     */
    val autoPort: Boolean = false,
    /** The exact text the user typed, kept so the field can be restored verbatim. */
    val source: String = "",
) {
    val hasCredentials: Boolean get() = !username.isNullOrEmpty()

    /** True when a UDP flow cannot be carried by this proxy and has to go out directly. */
    val udpBypassesProxy: Boolean get() = !kind.supportsUdp

    /** `host:port` as it appears in logs (never includes the password). */
    fun authority(): String = "$host:$port"

    /** What to show the user: a concrete port, or "auto". */
    fun portLabel(): String = if (autoPort) "پورت خودکار" else port.toString()

    /** Human-readable, credential-masked description for the UI and logs. */
    fun describe(): String = buildString {
        append(kind.displayName())
        append("://")
        if (username != null) {
            append(username)
            append(":***@")
        }
        if (autoPort) append("$host (port auto)") else append(authority())
        if (proxyOverTls) append(" (TLS)")
    }

    /** Canonical form used for persistence - round-trips through [StaticProxyParser]. */
    fun canonical(): String = buildString {
        if (proxyOverTls) append("https") else append(kind.name.lowercase())
        append("://")
        if (username != null) {
            append(StaticProxyParser.percentEncode(username))
            if (!password.isNullOrEmpty()) {
                append(':')
                append(StaticProxyParser.percentEncode(password))
            }
            append('@')
        }
        if (host.contains(':') && !host.startsWith("[")) append("[")
        append(host)
        if (host.contains(':') && !host.startsWith("[")) append("]")
        // No port -> keep it portless, so the saved value round-trips as autoPort again.
        if (!autoPort) {
            append(':')
            append(port)
        }
    }
}

class StaticProxyParseException(message: String) : Exception(message)

/**
 * Pure (Android-free) parser, deliberately unit-tested on the JVM - see
 * `app/src/test/java/com/bigrocket/egress/StaticProxyParserTest.kt`.
 */
object StaticProxyParser {

    /**
     * @return null for an empty/blank input, meaning "no static proxy - leave egress direct"
     *         (that is how the user turns the feature off without deleting the text).
     * @throws StaticProxyParseException for input that is non-empty but unusable, with a
     *         message meant to be shown to the user as-is.
     */
    fun parse(input: String?): StaticProxyConfig? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // People paste from chat apps: kill line breaks/tabs/zero-width junk, keep spaces out.
        // Persian/Arabic-Indic digits are also folded to ASCII: an Iranian keyboard very
        // commonly produces "۹۵.۸۵.۲۵۲.۱۱۵", which is a perfectly valid thing to type but not a
        // valid IP/host as far as DNS is concerned - it used to fail only later, at connect
        // time, with a misleading "DNS resolution failed".
        val cleaned = raw.replace("\u200f", "").replace("\u200e", "").replace("\uFEFF", "")
            .replace(Regex("[\\s\\t\\r\\n]+"), "")
            .let(::foldDigits)
        if (cleaned.isEmpty()) return null

        var rest = cleaned
        var kind = StaticProxyKind.SOCKS5
        var tls = false
        val schemeMatch = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://").find(cleaned)
        if (schemeMatch != null) {
            val scheme = schemeMatch.groupValues[1].lowercase()
            rest = cleaned.substring(schemeMatch.value.length)
            when (scheme) {
                "socks", "socks5", "socks5h", "socks5+tls" -> {
                    kind = StaticProxyKind.SOCKS5
                    tls = scheme == "socks5+tls"
                }
                "http" -> kind = StaticProxyKind.HTTP
                "https" -> {
                    kind = StaticProxyKind.HTTP
                    tls = true
                }
                else -> throw StaticProxyParseException(
                    "پروتکل «$scheme» پشتیبانی نمی‌شود؛ از socks5:// یا http:// استفاده کنید"
                )
            }
            // A path/trailing slash after the authority is meaningless for a proxy.
            rest = rest.substringBefore('/')
        }

        // Strip an optional query/fragment some paste sources append.
        rest = rest.substringBefore('?').substringBefore('#')
        if (rest.isEmpty()) throw StaticProxyParseException("آدرس پروکسی خالی است")

        var username: String? = null
        var password: String? = null

        // user:pass@authority
        val atIndex = rest.lastIndexOf('@')
        if (atIndex >= 0) {
            val userInfo = rest.substring(0, atIndex)
            rest = rest.substring(atIndex + 1)
            val sep = userInfo.indexOf(':')
            if (sep >= 0) {
                username = percentDecode(userInfo.substring(0, sep))
                password = percentDecode(userInfo.substring(sep + 1))
            } else {
                username = percentDecode(userInfo)
                password = ""
            }
        }

        // Legacy `host:port:user:pass` (exactly four colon-separated fields, no scheme,
        // no '@', and not a bare IPv6 literal).
        if (username == null && schemeMatch == null && !rest.startsWith("[")) {
            val parts = rest.split(':')
            if (parts.size == 4 && parts[1].toIntOrNull() != null && parts[0].isNotEmpty()) {
                username = parts[2]
                password = parts[3]
                rest = "${parts[0]}:${parts[1]}"
            }
        }

        val (rawHost, portText) = splitHostPort(rest)
        // A trailing dot ("95.85.252.115.") is a common copy/paste artefact and never helps.
        val host = rawHost.trimEnd('.')
        if (host.isBlank()) throw StaticProxyParseException("هاست پروکسی خالی است")
        if (host.contains(' ') || host.any { it.isWhitespace() }) {
            throw StaticProxyParseException("هاست پروکسی نامعتبر است")
        }

        val autoPort = portText.isNullOrEmpty()
        val port = if (autoPort) {
            kind.defaultPort()
        } else {
            val value = portText!!.toIntOrNull()
                ?: throw StaticProxyParseException("پورت پروکسی «$portText» عدد نیست")
            if (value !in 1..65535) throw StaticProxyParseException("پورت پروکسی باید بین ۱ تا ۶۵۵۳۵ باشد")
            value
        }

        return StaticProxyConfig(
            kind = kind,
            host = host,
            port = port,
            username = username?.takeIf { it.isNotEmpty() },
            password = password,
            proxyOverTls = tls,
            autoPort = autoPort,
            source = cleaned,
        )
    }

    /** Splits `host`, `host:port`, `[v6]`, `[v6]:port`, `v6::literal`. */
    private fun splitHostPort(value: String): Pair<String, String?> {
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close < 0) throw StaticProxyParseException("آدرس IPv6 بسته نشده است (] ندارد)")
            val host = value.substring(1, close)
            val remainder = value.substring(close + 1)
            if (remainder.isEmpty()) return host to null
            if (!remainder.startsWith(":")) throw StaticProxyParseException("آدرس پروکسی نامعتبر است")
            return host to remainder.substring(1)
        }
        val firstColon = value.indexOf(':')
        val lastColon = value.lastIndexOf(':')
        if (firstColon < 0) return value to null
        // More than one colon and no brackets: either a bare IPv6 literal (no port attached)
        // or a typo. Only a string that really is an IPv6 literal is accepted - anything else
        // is rejected loudly instead of being silently treated as a hostname, which would send
        // the connection nowhere useful.
        if (firstColon != lastColon) {
            if (isIpv6Literal(value)) return value to null
            throw StaticProxyParseException("آدرس پروکسی نامعتبر است (اگر IPv6 است آن را در [] بگذارید)")
        }
        return value.substring(0, firstColon) to value.substring(firstColon + 1)
    }

    private fun isIpv6Literal(value: String): Boolean =
        value.contains(':') &&
            value.count { it == ':' } >= 2 &&
            value.all { it.isDigit() || it in "abcdefABCDEF:." } &&
            value.count { it == '.' } in 0..3

    /** Persian (۰-۹) and Arabic-Indic (٠-٩) digits -> ASCII, everything else untouched. */
    fun foldDigits(value: String): String {
        if (value.none { it in '\u06F0'..'\u06F9' || it in '\u0660'..'\u0669' }) return value
        val out = StringBuilder(value.length)
        for (ch in value) {
            out.append(
                when (ch) {
                    in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                    in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                    else -> ch
                }
            )
        }
        return out.toString()
    }

    /** RFC 3986 percent-decoding without the `+`-means-space surprise of URLDecoder. */
    fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun percentEncode(value: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toSet()
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = (byte.toInt() and 0xFF).toChar()
            if (ch in safe) {
                out.append(ch)
            } else {
                out.append('%').append(String.format("%02X", byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }
}
