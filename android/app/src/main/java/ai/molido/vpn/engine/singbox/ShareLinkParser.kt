package ai.molido.vpn.engine.singbox

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

enum class ProxyProtocol { VLESS, VMESS, TROJAN, SHADOWSOCKS, HYSTERIA2, TUIC }

/**
 * One server parsed from a share link. [credential] is the UUID or password; [secondary]
 * holds the Shadowsocks method or the TUIC password. Contents are sensitive: never log them.
 */
data class ProxyNode(
    val protocol: ProxyProtocol,
    val name: String,
    val host: String,
    val port: Int,
    val credential: String,
    val secondary: String? = null,
    val params: Map<String, String> = emptyMap()
) {
    override fun toString(): String = "ProxyNode($protocol, <redacted>)"
}

/** Parses vless://, vmess://, trojan://, ss://, hysteria2:// (hy2://) and tuic:// links. */
object ShareLinkParser {
    const val MAX_LINK_LENGTH = 8 * 1024
    const val MAX_SUBSCRIPTION_NODES = 500

    fun parse(link: String): ProxyNode? {
        val trimmed = link.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LINK_LENGTH) return null
        val sep = trimmed.indexOf("://")
        if (sep <= 0) return null
        val scheme = trimmed.substring(0, sep).lowercase()
        val rest = trimmed.substring(sep + 3)
        return runCatching {
            when (scheme) {
                "vless" -> simple(ProxyProtocol.VLESS, rest)
                "trojan" -> simple(ProxyProtocol.TROJAN, rest)
                "hysteria2", "hy2" -> simple(ProxyProtocol.HYSTERIA2, rest)
                "tuic" -> parseTuic(rest)
                "ss" -> parseShadowsocks(rest)
                "vmess" -> parseVmess(rest)
                else -> null
            }
        }.getOrNull()
    }

    /** Accepts a plain or base64-encoded subscription body, one link per line. */
    fun parseSubscription(body: String): List<ProxyNode> {
        val text = body.trim().let { raw ->
            if (raw.contains("://")) raw else base64(raw) ?: raw
        }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parse(it) }
            .take(MAX_SUBSCRIPTION_NODES)
            .toList()
    }

    private class Parts(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val name: String
    )

    private fun simple(protocol: ProxyProtocol, rest: String): ProxyNode? {
        val p = split(rest) ?: return null
        if (p.userInfo.isBlank()) return null
        return ProxyNode(protocol, displayName(p), p.host, p.port, p.userInfo, params = p.query)
    }

    private fun parseTuic(rest: String): ProxyNode? {
        val p = split(rest) ?: return null
        val uuid = p.userInfo.substringBefore(':')
        val password = p.userInfo.substringAfter(':', "")
        if (uuid.isBlank() || password.isBlank()) return null
        return ProxyNode(ProxyProtocol.TUIC, displayName(p), p.host, p.port, uuid, password, p.query)
    }

    private fun parseShadowsocks(rest: String): ProxyNode? {
        val body = rest.substringBefore('#')
        if (!body.contains('@')) {
            // Legacy form: ss://BASE64(method:password@host:port)#name
            val decoded = base64(body.substringBefore('?')) ?: return null
            if (!decoded.contains('@')) return null
            val fragment = if (rest.contains('#')) "#" + rest.substringAfter('#') else ""
            return parseShadowsocks(decoded + fragment)
        }
        val p = split(rest) ?: return null
        val creds = if (p.userInfo.contains(':')) p.userInfo else base64(p.userInfo) ?: return null
        val method = creds.substringBefore(':')
        val password = creds.substringAfter(':', "")
        if (method.isBlank() || password.isEmpty()) return null
        return ProxyNode(ProxyProtocol.SHADOWSOCKS, displayName(p), p.host, p.port, password, method, p.query)
    }

    private val VMESS_FIELD = Regex(""""([A-Za-z_\-]+)"\s*:\s*(?:"((?:\\.|[^"\\])*)"|(-?\d+))""")

    private fun parseVmess(rest: String): ProxyNode? {
        val json = base64(rest.substringBefore('#')) ?: return null
        val fields = VMESS_FIELD.findAll(json).associate { m ->
            m.groupValues[1] to (m.groups[2]?.value ?: m.groupValues[3])
        }
        val host = fields["add"]?.takeIf { it.isNotBlank() } ?: return null
        val port = fields["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val id = fields["id"]?.takeIf { it.isNotBlank() } ?: return null
        val name = fields["ps"].orEmpty().ifBlank { "$host:$port" }
        return ProxyNode(ProxyProtocol.VMESS, name, host, port, id, params = fields - setOf("add", "port", "id", "ps"))
    }

    private fun split(rest: String): Parts? {
        val hash = rest.indexOf('#')
        val name = if (hash >= 0) decode(rest.substring(hash + 1)) else ""
        val noFragment = if (hash >= 0) rest.substring(0, hash) else rest
        val q = noFragment.indexOf('?')
        val query = if (q >= 0) parseQuery(noFragment.substring(q + 1)) else emptyMap()
        val main = (if (q >= 0) noFragment.substring(0, q) else noFragment).trimEnd('/')
        val at = main.lastIndexOf('@')
        if (at <= 0) return null
        val (host, port) = hostPort(main.substring(at + 1)) ?: return null
        return Parts(decode(main.substring(0, at)), host, port, query, name)
    }

    private fun hostPort(value: String): Pair<String, Int>? {
        val host: String
        val portText: String
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end < 0 || end + 1 >= value.length || value[end + 1] != ':') return null
            host = value.substring(1, end)
            portText = value.substring(end + 2)
        } else {
            val colon = value.lastIndexOf(':')
            if (colon <= 0) return null
            host = value.substring(0, colon)
            portText = value.substring(colon + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        return host to port
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
        }

    // '+' is preserved: passwords and base64 user-info legitimately contain it.
    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun displayName(p: Parts) = p.name.ifBlank { "${p.host}:${p.port}" }

    internal fun base64(value: String): String? {
        val clean = value.trim().filterNot { it == '\n' || it == '\r' || it == ' ' }
            .replace('-', '+').replace('_', '/')
        if (clean.isEmpty()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8) }.getOrNull()
    }
}
