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
}
