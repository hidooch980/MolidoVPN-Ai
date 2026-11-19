package ai.molido.vpn.core

import android.content.Context
import android.os.Build

/**
 * Couples update verification to the Android Keystore trust store.
 * The anti-rollback floor is the greater of the installed package version and
 * the encrypted monotonic counter. A caller cannot supply an arbitrary version.
 *
 * A PackageInstaller callback integration is still required before this is a
 * complete update workflow.
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
        val installedFloor = maxOf(
            trustStore.currentInstalledVersionCode(),
            installedPackageVersionCode()
        )
        return UpdateIntegrityCore(trustStore.loadUpdatePublicKey()).verify(
            manifest = manifest,
            artifact = artifact,
            installedVersionCode = installedFloor,
            nowEpochSeconds = nowEpochSeconds
        )
    }

    /**
     * Internal post-install hook. Reads the version actually installed by Android;
     * callers cannot claim success by passing an arbitrary future version number.
     */
    internal fun recordSuccessfulInstall() {
        val installedVersion = installedPackageVersionCode()
        val current = trustStore.currentInstalledVersionCode()
        check(installedVersion > current) {
            "Installed package version must advance the trusted version counter"
        }
        trustStore.advanceInstalledVersionCode(installedVersion)
    }

    @Suppress("DEPRECATION")
    private fun installedPackageVersionCode(): Long {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }
}
