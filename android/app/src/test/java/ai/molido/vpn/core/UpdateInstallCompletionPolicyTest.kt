package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallCompletionPolicyTest {
    @Test fun duplicateAndConcurrentUpdateAttemptsAreRejected() {
        assertEquals(UpdateInstallAdmission.ALREADY_PENDING,
            UpdateInstallAdmissionPolicy.evaluate(true, 9, 8, 8))
        assertEquals(UpdateInstallAdmission.NOT_NEWER,
            UpdateInstallAdmissionPolicy.evaluate(false, 8, 8, 7))
        assertEquals(UpdateInstallAdmission.NOT_NEWER,
            UpdateInstallAdmissionPolicy.evaluate(false, 7, 8, 8))
        assertEquals(UpdateInstallAdmission.NOT_NEWER,
            UpdateInstallAdmissionPolicy.evaluate(false, 0, 0, 0))
        assertEquals(UpdateInstallAdmission.ACCEPT,
            UpdateInstallAdmissionPolicy.evaluate(false, 9, 8, 8))
    }

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
