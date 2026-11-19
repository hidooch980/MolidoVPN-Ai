package ai.molido.vpn.core

enum class UpdateInstallAdmission { ACCEPT, ALREADY_PENDING, NOT_NEWER }

/** Pure admission gate used before creating a PackageInstaller session. */
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
