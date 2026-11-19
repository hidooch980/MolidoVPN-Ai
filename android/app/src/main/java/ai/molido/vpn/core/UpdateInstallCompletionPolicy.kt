package ai.molido.vpn.core

enum class InstallCallbackPhase { PENDING_USER_ACTION, SUCCEEDED, FAILED }

/** Pure gate: only a matching, successful PackageInstaller result may advance trust. */
object UpdateInstallCompletionPolicy {
    fun shouldRecordInstalledVersion(
        phase: InstallCallbackPhase,
        expectedVersionCode: Long,
        actualInstalledVersionCode: Long,
        trustedVersionCode: Long
    ): Boolean =
        phase == InstallCallbackPhase.SUCCEEDED &&
            expectedVersionCode > 0 &&
            actualInstalledVersionCode == expectedVersionCode &&
            actualInstalledVersionCode > trustedVersionCode
}
