package ai.molido.vpn.core

enum class InstallCallbackPhase { PENDING_USER_ACTION, SUCCEEDED, FAILED }

enum class UpdateInstallAdmission { ACCEPT, ALREADY_PENDING, NOT_NEWER }

/** Rejects concurrent sessions and duplicate/downgrade candidates before allocating a PackageInstaller session. */
object UpdateInstallAdmissionPolicy {
    fun evaluate(
        hasPendingInstall: Boolean,
        candidateVersionCode: Long,
        installedVersionCode: Long,
        trustedVersionCode: Long
    ): UpdateInstallAdmission {
        if (hasPendingInstall) return UpdateInstallAdmission.ALREADY_PENDING
        if (candidateVersionCode <= 0) return UpdateInstallAdmission.NOT_NEWER
        if (candidateVersionCode <= maxOf(installedVersionCode, trustedVersionCode)) {
            return UpdateInstallAdmission.NOT_NEWER
        }
        return UpdateInstallAdmission.ACCEPT
    }
}

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
