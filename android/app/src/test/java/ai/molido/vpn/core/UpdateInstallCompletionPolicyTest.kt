package ai.molido.vpn.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallCompletionPolicyTest {
    @Test fun onlyMatchingSuccessfulInstallAdvancesCounter() {
        assertTrue(UpdateInstallCompletionPolicy.shouldRecordInstalledVersion(
            InstallCallbackPhase.SUCCEEDED, 8, 8, 7
        ))
    }

    @Test fun pendingOrFailedInstallNeverAdvancesCounter() {
        assertFalse(UpdateInstallCompletionPolicy.shouldRecordInstalledVersion(
            InstallCallbackPhase.PENDING_USER_ACTION, 8, 8, 7
        ))
        assertFalse(UpdateInstallCompletionPolicy.shouldRecordInstalledVersion(
            InstallCallbackPhase.FAILED, 8, 8, 7
        ))
    }

    @Test fun mismatchedOrAlreadyTrustedVersionNeverAdvancesCounter() {
        assertFalse(UpdateInstallCompletionPolicy.shouldRecordInstalledVersion(
            InstallCallbackPhase.SUCCEEDED, 8, 7, 6
        ))
        assertFalse(UpdateInstallCompletionPolicy.shouldRecordInstalledVersion(
            InstallCallbackPhase.SUCCEEDED, 8, 8, 8
        ))
        assertFalse(UpdateInstallCompletionPolicy.shouldRecordInstalledVersion(
            InstallCallbackPhase.SUCCEEDED, 8, 8, 9
        ))
    }
}
