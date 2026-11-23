package ai.molido.vpn.core

import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

/**
 * Registers a free Cloudflare WARP device for this user and builds its WireGuard config.
 * The private key is generated on the device and never leaves it; only the public key is sent.
 * Uses the same unofficial client API as wgcf, so Cloudflare may change or block it.
 * The returned text is sensitive: never log it.
 */
object WarpRegistration {
    private const val ENDPOINT = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val TIMEOUT_MS = 15_000
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    fun register(): String {
        val keys = KeyPair()
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val body = JSONObject()
            .put("key", keys.publicKey.toBase64())
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", tos)
            .put("type", "Android")
            .put("locale", "en_US")
            .toString().toByteArray(StandardCharsets.UTF_8)

        val connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
        val response = try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("User-Agent", "okhttp/3.12.1")
            connection.setRequestProperty("CF-Client-Version", "a-6.30-3596")
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            check(status in 200..299) { "WARP registration failed with HTTP $status" }
            connection.inputStream.use { input ->
                val bytes = input.readBytes()
                require(bytes.size <= MAX_RESPONSE_BYTES) { "WARP response too large" }
                String(bytes, StandardCharsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
        return buildConfig(keys.privateKey.toBase64(), JSONObject(response))
    }

    /** Builds the WireGuard text from a registration response. */
    fun buildConfig(privateKey: String, response: JSONObject): String {
        val config = response.getJSONObject("config")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val addresses = config.getJSONObject("interface").getJSONObject("addresses")
        val v4 = addresses.optString("v4").takeIf { it.isNotBlank() }?.let { "$it/32" }
        val v6 = addresses.optString("v6").takeIf { it.isNotBlank() }?.let { "$it/128" }
        val address = listOfNotNull(v4, v6).joinToString(", ")
        require(address.isNotEmpty()) { "WARP response has no address" }
        val endpoint = peer.getJSONObject("endpoint").optString("host").ifBlank { "engage.cloudflareclient.com:2408" }
        return listOf(
            "# Cloudflare WARP",
            "[Interface]",
            "PrivateKey = $privateKey",
            "Address = $address",
            "DNS = 1.1.1.1, 1.0.0.1",
            "MTU = 1280",
            "",
            "[Peer]",
            "PublicKey = ${peer.getString("public_key")}",
            "AllowedIPs = 0.0.0.0/0, ::/0",
            "Endpoint = $endpoint"
        ).joinToString("\n")
    }

    /** Published Cloudflare WARP anycast /24 ranges; the 8.x ranges are often reachable when 162.159.x is not. */
    val ENDPOINT_RANGES = listOf(
        "8.6.112", "8.34.70", "8.34.146", "8.35.211", "8.39.125", "8.39.204", "8.39.214", "8.47.69",
        "162.159.192", "162.159.193", "162.159.195", "188.114.96", "188.114.97", "188.114.98", "188.114.99"
    )

    /** UDP ports the WARP edge accepts besides the default 2408. */
    val ENDPOINT_PORTS = listOf(
        2408, 500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942, 943, 945, 946,
        955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1701, 1843, 2371, 2506,
        3138, 3476, 3581, 3854, 4177, 4198, 4233, 4500, 5279, 5956, 7103, 7152, 7156, 7281, 7559,
        8319, 8742, 8854, 8886
    )

    /**
     * The same WARP account repeated with [count] different endpoints, so connect failover (with its
     * handshake check) walks through them. The first keeps the original endpoint; about half of the
     * rest come from the 8.x ranges. All copies share one key, and only one is ever active at a time.
     */
    fun withEndpoints(config: String, count: Int, random: Random = Random.Default): String {
        val lines = config.lines()
        val original = lines.firstOrNull { it.trim().startsWith("Endpoint", ignoreCase = true) }
            ?.substringAfter('=')?.trim() ?: return config
        val eightRanges = ENDPOINT_RANGES.filter { it.startsWith("8.") }
        val endpoints = linkedSetOf(original)
        var guard = 0
        while (endpoints.size < count && guard++ < count * 20) {
            val ranges = if (endpoints.size % 2 == 1) eightRanges else ENDPOINT_RANGES
            val ip = "${ranges[random.nextInt(ranges.size)]}.${random.nextInt(1, 255)}"
            endpoints += "$ip:${ENDPOINT_PORTS[random.nextInt(ENDPOINT_PORTS.size)]}"
        }
        return endpoints.joinToString("\n\n") { endpoint ->
            lines.joinToString("\n") { line ->
                val t = line.trim()
                when {
                    t.startsWith("#") && t.contains("WARP", ignoreCase = true) -> "# Cloudflare WARP $endpoint"
                    t.startsWith("Endpoint", ignoreCase = true) -> "Endpoint = $endpoint"
                    else -> line
                }
            }
        }
    }
}
