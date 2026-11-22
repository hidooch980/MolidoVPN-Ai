package ai.molido.vpn.core

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class ControlPlaneFetchResult(
    val verification: ConfigurationVerification,
    val acceptedRevision: Long? = null
)

/** HTTPS-only, bounded-response client for the self-hosted configuration API. */
class ControlPlaneClient(
    private val trustStore: AndroidSecureTrustStore,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000
) {
    init {
        ControlPlaneRequestPolicy.validateTimeouts(connectTimeoutMillis, readTimeoutMillis)
    }

    fun fetchAndVerify(baseUrl: String, deviceToken: String, nowEpochSeconds: Long): ControlPlaneFetchResult {
        ControlPlaneRequestPolicy.validateDeviceToken(deviceToken)
        require(nowEpochSeconds >= 0)
        val uri = URI(baseUrl)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        val endpoint = URI("https", null, uri.host, uri.port, (uri.path ?: "").trimEnd('/') + "/v1/configuration", null, null).toURL()
        val connection = endpoint.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $deviceToken")
            val status = connection.responseCode
            if (status != HttpsURLConnection.HTTP_OK) {
                throw IllegalStateException("Control plane returned HTTP $status")
            }
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_RESPONSE_BYTES) { "Control-plane response exceeds size limit" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            val json = JSONObject(body)
            val revision = json.getLong("revision")
            val expires = json.getLong("expires_at")
            val payload = Base64.decode(json.getString("payload_base64"), Base64.DEFAULT)
            val signature = Base64.decode(json.getString("signature_base64"), Base64.DEFAULT)
            val currentRevision = trustStore.currentConfigurationRevision()
            val config = SignedControlConfiguration(revision, expires, payload, signature)
            val verification = ControlPlaneConfigurationCore(trustStore.loadControlPlanePublicKey())
                .verify(config, currentRevision, nowEpochSeconds)
            if (verification.status == ConfigurationStatus.ACCEPTED) {
                trustStore.advanceConfigurationRevision(revision)
                return ControlPlaneFetchResult(verification, revision)
            }
            return ControlPlaneFetchResult(verification)
        } finally {
            connection.disconnect()
        }
    }

    private companion object { const val MAX_RESPONSE_BYTES = 128 * 1024 }
}
