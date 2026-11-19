package ai.molido.vpn.core

/**
 * Couples update verification to the Android Keystore trust store.
 * The installed-version counter is advanced only after PackageInstaller reports
 * a successful install; verification alone never changes installed state.
 */
class AndroidUpdateTrust(private val trustStore: AndroidSecureTrustStore) {
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

    fun recordSuccessfulInstall(versionCode: Long) {
        trustStore.advanceInstalledVersionCode(versionCode)
    }
}
