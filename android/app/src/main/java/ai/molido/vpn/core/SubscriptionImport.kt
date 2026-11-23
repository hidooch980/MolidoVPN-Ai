package ai.molido.vpn.core

import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/** Result of reading a subscription. [wireGuardText] is sensitive: never log it. */
class SubscriptionResult(val wireGuardText: String, val wireGuardCount: Int, val unsupportedCount: Int) {
    override fun toString() = "SubscriptionResult(wg=$wireGuardCount, unsupported=$unsupportedCount)"
}

/**
 * Fetches a subscription link the user entered and keeps the WireGuard configs in it, either as
 * `.conf` text or as `wireguard://` / `wg://` links. Other links (VLESS, VMess, Trojan, ...) are
 * counted but not imported, because this build only has a WireGuard engine.
 */
object SubscriptionImport {
    private const val TIMEOUT_MS = 15_000
    const val MAX_RESPONSE_BYTES = 512 * 1024
    private val OTHER_SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://", "ssr://", "hysteria2://", "hy2://", "tuic://", "socks://", "http://", "https://")

    fun fetch(url: String): SubscriptionResult {
        val uri = URI(url.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) { "Only https links are allowed" }
        val connection = URL(uri.toString()).openConnection() as HttpsURLConnection
        val body = try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MolidoVPN")
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= MAX_RESPONSE_BYTES) { "Subscription too large" }
                    out.write(buffer, 0, n)
                }
                out.toString(StandardCharsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
        return parse(body)
    }

    /** Parses a subscription body: plain text, or base64 of it. */
    fun parse(body: String): SubscriptionResult {
        val text = decodeIfBase64(body.trim())
        val configs = mutableListOf<String>()
        if (text.contains("[Interface]", ignoreCase = true)) {
            configs += WireGuardBundle.split(text).map { it.text }
        }
        var unsupported = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("wireguard://", true) || line.startsWith("wg://", true) ->
                    fromUri(line)?.let { configs += it } ?: unsupported++
                OTHER_SCHEMES.any { line.startsWith(it, ignoreCase = true) } -> unsupported++
            }
        }
        val distinct = configs.distinct().take(WireGuardBundle.MAX_ENTRIES)
        return SubscriptionResult(distinct.joinToString("\n\n"), distinct.size, unsupported)
    }

    /**
     * `wireguard://<private key>@host:port?publickey=..&address=..&mtu=..&presharedkey=..#name`
     * (the v2rayN / sing-box share format) converted to `.conf` text with its name as a comment.
     */
    fun fromUri(link: String): String? = runCatching {
        val rest = link.substringAfter("://")
        val name = rest.substringAfter('#', "").let { decode(it) }
        val main = rest.substringBefore('#')
        val query = main.substringAfter('?', "")
        val authority = main.substringBefore('?').trimEnd('/')
        val privateKey = decode(authority.substringBeforeLast('@'))
        val endpoint = authority.substringAfterLast('@')
        val params = query.split('&').filter { '=' in it }
            .associate { decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=')) }
        val publicKey = params["publickey"] ?: params["public_key"] ?: params["peer_public_key"]
        val address = params["address"] ?: params["ip"] ?: params["local_address"]
        if (privateKey.length != 44 || publicKey?.length != 44 || address.isNullOrBlank() || ':' !in endpoint) return null
        listOfNotNull(
            "# ${name.ifBlank { endpoint }}",
            "[Interface]",
            "PrivateKey = $privateKey",
            "Address = ${address.split(',').joinToString(", ") { it.trim() }}",
            "DNS = ${params["dns"] ?: "1.1.1.1, 1.0.0.1"}",
            "MTU = ${params["mtu"]?.toIntOrNull() ?: 1280}",
            "",
            "[Peer]",
            "PublicKey = $publicKey",
            params["presharedkey"]?.let { "PresharedKey = $it" },
            "AllowedIPs = 0.0.0.0/0, ::/0",
            "Endpoint = $endpoint"
        ).joinToString("\n")
    }.getOrNull()

    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    private fun decodeIfBase64(text: String): String {
        if (text.contains("://") || text.contains("[Interface]", true)) return text
        val compact = text.replace(Regex("\\s"), "")
        return runCatching {
            val normalized = compact.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
            String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8)
        }.getOrDefault(text)
    }
}
