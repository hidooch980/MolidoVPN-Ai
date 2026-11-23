package ai.molido.vpn.core

import java.util.Base64

/** Release-side update manifest published as `update-manifest.json` next to the APK. */
data class PublishedUpdate(
    val manifest: SignedUpdateManifest,
    val apkAssetName: String
)

/**
 * Parses `{"versionCode":N,"expiresAt":E,"sha256":"..","apk":"..","signature":"<base64 DER>"}`.
 * The signed payload is rebuilt from the fields (never trusted from the wire), so
 * UpdateIntegrityCore verifies exactly what the release pipeline signed.
 */
object UpdateManifestParser {
    const val MAX_MANIFEST_BYTES = 16 * 1024
    private val APK_NAME = Regex("""MolidoVPN-v[0-9]+(\.[0-9]+){0,3}\.apk""")

    fun parse(json: String): PublishedUpdate? {
        if (json.length > MAX_MANIFEST_BYTES) return null
        val versionCode = number(json, "versionCode") ?: return null
        val expiresAt = number(json, "expiresAt") ?: return null
        val sha256 = text(json, "sha256")?.lowercase()?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
        val apk = text(json, "apk")?.takeIf { APK_NAME.matches(it) } ?: return null
        val signature = text(json, "signature")?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull()
        }?.takeIf { it.isNotEmpty() } ?: return null
        if (versionCode <= 0 || expiresAt <= 0) return null
        return PublishedUpdate(
            SignedUpdateManifest(
                versionCode = versionCode,
                expiresAtEpochSeconds = expiresAt,
                artifactSha256Hex = sha256,
                signedPayload = UpdateIntegrityCore.canonicalPayload(versionCode, expiresAt, sha256),
                signature = signature
            ),
            apk
        )
    }

    private fun number(json: String, key: String): Long? =
        Regex(""""${key}"\s*:\s*([0-9]{1,18})""").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun text(json: String, key: String): String? =
        Regex(""""${key}"\s*:\s*"([A-Za-z0-9+/=._\-]{1,1024})"""").find(json)?.groupValues?.get(1)
}
