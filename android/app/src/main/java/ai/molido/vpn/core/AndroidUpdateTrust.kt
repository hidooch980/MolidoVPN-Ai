package ai.molido.vpn.core

import android.content.Context
import android.os.Build

/**
 * Couples signed update verification to Android Keystore trust and the actual
 * installed package version. The encrypted counter is an additional rollback floor.
 */
class AndroidUpdateTrust(
    context: Context,
    private val trustStore: AndroidSecureTrustStore
) {
    private val appContext = context.applicationContext

    fun verifyCandidate(
        manifest: SignedUpdateManifest,
        artifact: ByteArray,
        nowEpochSeconds: Long
    ): UpdateVerificationResult {
        val installedFloor = maxOf(trustStore.currentInstalledVersionCode(), installedPackageVersionCode())
        return UpdateIntegrityCore(trustStore.loadUpdatePublicKey()).verify(
            manifest = manifest,
            artifact = artifact,
            installedVersionCode = installedFloor,
            nowEpochSeconds = nowEpochSeconds
        )
    }

    /**
     * Called only after PackageInstaller reports STATUS_SUCCESS. The package manager
     * is authoritative; duplicate success callbacks are idempotent.
     */
    @Synchronized
    internal fun recordSuccessfulInstall(expectedVersionCode: Long) {
        require(expectedVersionCode > 0)
        val installedVersion = installedPackageVersionCode()
        check(installedVersion == expectedVersionCode) {
            "Installed package version does not match the verified update"
        }
        val current = trustStore.currentInstalledVersionCode()
        check(installedVersion >= current) { "Installed package is below the trusted version floor" }
        if (installedVersion > current) trustStore.advanceInstalledVersionCode(installedVersion)
    }

    @Suppress("DEPRECATION")
    private fun installedPackageVersionCode(): Long {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode
        else packageInfo.versionCode.toLong()
    }
}
