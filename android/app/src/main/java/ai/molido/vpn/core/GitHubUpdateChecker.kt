package ai.molido.vpn.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

enum class UpdateCheckOutcome { NOT_CONFIGURED, UP_TO_DATE, INSTALL_STARTED, NO_MANIFEST, REJECTED, NETWORK_ERROR }

/**
 * Fetches the signed manifest from the latest GitHub Release, downloads the APK it names,
 * and hands both to AndroidUpdateInstaller, which verifies the ECDSA signature, SHA-256,
 * package identity, version and rollback floor before PackageInstaller is invoked.
 * Must run off the main thread.
 */
class GitHubUpdateChecker(
    private val updateTrust: AndroidUpdateTrust,
    private val installer: AndroidUpdateInstaller,
    private val trustKeyProvisioned: () -> Boolean,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    fun checkAndInstall(): UpdateCheckOutcome {
        if (!trustKeyProvisioned()) return UpdateCheckOutcome.NOT_CONFIGURED
        val manifestText = try {
            String(download("$baseUrl/update-manifest.json", UpdateManifestParser.MAX_MANIFEST_BYTES), StandardCharsets.UTF_8)
        } catch (_: NotFound) {
            return UpdateCheckOutcome.NO_MANIFEST
        } catch (_: Exception) {
            return UpdateCheckOutcome.NETWORK_ERROR
        }
        val published = UpdateManifestParser.parse(manifestText) ?: return UpdateCheckOutcome.REJECTED
        val installed = maxOf(updateTrust.installedVersionCode(), updateTrust.trustedVersionCode())
        if (published.manifest.versionCode <= installed) return UpdateCheckOutcome.UP_TO_DATE

        val apk = try {
            download("$baseUrl/${published.apkAssetName}", MAX_APK_BYTES)
        } catch (_: Exception) {
            return UpdateCheckOutcome.NETWORK_ERROR
        }
        return try {
            installer.installVerifiedUpdate(published.manifest, apk, nowEpochSeconds())
            UpdateCheckOutcome.INSTALL_STARTED
        } catch (_: Exception) {
            UpdateCheckOutcome.REJECTED
        }
    }

    private class NotFound : Exception()

    private fun download(url: String, maxBytes: Int): ByteArray {
        require(url.startsWith("https://")) { "Only HTTPS update sources are allowed" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw NotFound()
            check(code == HttpURLConnection.HTTP_OK) { "Unexpected HTTP status" }
            check(connection.url.protocol == "https") { "Redirected away from HTTPS" }
            val declared = connection.contentLengthLong
            check(declared <= maxBytes) { "Download exceeds size limit" }
            return connection.inputStream.use { readBounded(it, maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            check(total <= maxBytes) { "Download exceeds size limit" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://github.com/hidooch980/MolidoVPN-Ai/releases/latest/download"
        const val MAX_APK_BYTES = 150 * 1024 * 1024
    }
}
