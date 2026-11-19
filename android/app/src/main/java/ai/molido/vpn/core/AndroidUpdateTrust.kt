package ai.molido.vpn.core

import android.content.Context
import android.os.Build

/**
 * Couples update verification to the Android Keystore trust store.
 * The anti-rollback counter is advanced only from the installed package version,
 * never from an arbitrary version supplied by a caller.
 *
 * A PackageInstaller callback integration is still required before this is used
 * as a complete update workflow.
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
        val verifier = UpdateIntegrityCore(trustStore.loadUpdatePublicKey())
        return verifier.verify(
            manifest = manifest,
            artifact = artifact,
            installedVersionCode = trustStore.currentInstalledVersionCode(),
            nowEpochSeconds = nowEpochSeconds
        )
    }

    /**
     * Internal post-install hook. Reads the version actually installed by Android;
     * callers cannot claim success by passing an arbitrary future version number.
     */
    internal fun recordSuccessfulInstall() {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        val installedVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val current = trustStore.currentInstalledVersionCode()
        check(installedVersion > current) {
            "Installed package version must advance the trusted version counter"
        }
        trustStore.advanceInstalledVersionCode(installedVersion)
    }
}
