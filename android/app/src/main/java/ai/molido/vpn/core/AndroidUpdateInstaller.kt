package ai.molido.vpn.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import ai.molido.vpn.UpdateInstallStatusReceiver
import java.io.File
import java.io.FileOutputStream

/**
 * Stages a previously signed-and-digest-verified APK through Android PackageInstaller.
 * Android itself enforces package signing continuity against the installed application.
 * User confirmation is forwarded when PackageInstaller returns STATUS_PENDING_USER_ACTION.
 */
class AndroidUpdateInstaller(
    context: Context,
    private val updateTrust: AndroidUpdateTrust
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val packageInstaller: PackageInstaller
        get() = appContext.packageManager.packageInstaller

    @Synchronized
    fun installVerifiedUpdate(
        manifest: SignedUpdateManifest,
        artifact: ByteArray,
        nowEpochSeconds: Long
    ): Int {
        require(artifact.isNotEmpty() && artifact.size <= MAX_APK_BYTES) { "APK size is invalid" }
        val verification = updateTrust.verifyCandidate(manifest, artifact, nowEpochSeconds)
        require(verification.status == UpdateVerificationStatus.VERIFIED) {
            "Update rejected: ${verification.status}"
        }
        verifyApkIdentityAndVersion(manifest.versionCode, artifact)
        val admission = UpdateInstallAdmissionPolicy.evaluate(
            hasPendingInstall = preferences.getInt(KEY_PENDING_SESSION, NO_SESSION) != NO_SESSION,
            candidateVersionCode = manifest.versionCode,
            installedVersionCode = updateTrust.installedVersionCode(),
            trustedVersionCode = updateTrust.trustedVersionCode()
        )
        require(admission == UpdateInstallAdmission.ACCEPT) {
            when (admission) {
                UpdateInstallAdmission.ALREADY_PENDING -> "Another update installation is already pending"
                UpdateInstallAdmission.NOT_NEWER -> "Update is duplicate or older than the installed/trusted version"
                UpdateInstallAdmission.ACCEPT -> "Update accepted"
            }
        }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(artifact.size.toLong())
        }
        val sessionId = packageInstaller.createSession(params)
        var session: PackageInstaller.Session? = null
        try {
            val openedSession = packageInstaller.openSession(sessionId)
            session = openedSession
            openedSession.openWrite("base.apk", 0, artifact.size.toLong()).use { output ->
                output.write(artifact)
                openedSession.fsync(output)
            }
            check(
                preferences.edit()
                    .putInt(KEY_PENDING_SESSION, sessionId)
                    .putLong(KEY_PENDING_VERSION, manifest.versionCode)
                    .putString(KEY_LAST_RESULT, "INSTALL_PENDING")
                    .commit()
            ) { "Unable to persist pending update state" }

            val callback = Intent(appContext, UpdateInstallStatusReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
                data = Uri.parse("molido-update://package-installer/$sessionId")
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }
            val statusPendingIntent = PendingIntent.getBroadcast(
                appContext,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(statusPendingIntent.intentSender)
            return sessionId
        } catch (failure: Exception) {
            clearPending(sessionId, "INSTALL_SUBMISSION_FAILED")
            // createSession() allocates the platform session before openSession().
            // If opening failed, session is null but the allocated session still exists.
            // Fall back to the ID-based API if the opened-session abandon path fails.
            val abandoned = try {
                if (session != null) {
                    session.abandon()
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
            if (!abandoned) {
                try { packageInstaller.abandonSession(sessionId) } catch (_: Exception) { }
            }
            throw failure
        } finally {
            try { session?.close() } catch (_: Exception) { }
        }
    }

    /** Invoked only by the non-exported PackageInstaller status receiver. */
    @Synchronized
    internal fun handleInstallStatus(intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION)
        if (sessionId == NO_SESSION || preferences.getInt(KEY_PENDING_SESSION, NO_SESSION) != sessionId) return

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = parcelableIntent(intent, Intent.EXTRA_INTENT) ?: run {
                    clearPending(sessionId, "USER_CONFIRMATION_INTENT_MISSING")
                    return
                }
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(confirmation)
                preferences.edit().putString(KEY_LAST_RESULT, "WAITING_FOR_USER_CONFIRMATION").commit()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val expected = preferences.getLong(KEY_PENDING_VERSION, 0L)
                try {
                    updateTrust.recordSuccessfulInstall(expected)
                    clearPending(sessionId, "INSTALL_SUCCEEDED")
                } catch (_: Exception) {
                    // The APK is already installed, so its PackageManager version remains
                    // a rollback floor even if the encrypted counter cannot be advanced.
                    clearPending(sessionId, "INSTALL_SUCCESS_TRUST_UPDATE_FAILED")
                }
            }
            else -> clearPending(sessionId, "INSTALL_FAILED")
        }
    }

    fun lastInstallResult(): String = preferences.getString(KEY_LAST_RESULT, "NO_UPDATE") ?: "NO_UPDATE"

    private fun verifyApkIdentityAndVersion(expectedVersionCode: Long, artifact: ByteArray) {
        val temporaryApk = File.createTempFile("molido-update-", ".apk", appContext.cacheDir)
        try {
            FileOutputStream(temporaryApk).use { it.write(artifact) }
            @Suppress("DEPRECATION")
            val info = appContext.packageManager.getPackageArchiveInfo(temporaryApk.absolutePath, 0)
                ?: throw IllegalArgumentException("Downloaded artifact is not a readable APK")
            require(info.packageName == appContext.packageName) { "APK package identity mismatch" }
            @Suppress("DEPRECATION")
            val actualVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
            require(actualVersion == expectedVersionCode) { "APK version does not match signed update manifest" }
        } finally {
            temporaryApk.delete()
        }
    }

    private fun clearPending(sessionId: Int, result: String) {
        if (preferences.getInt(KEY_PENDING_SESSION, NO_SESSION) != sessionId) return
        preferences.edit()
            .remove(KEY_PENDING_SESSION)
            .remove(KEY_PENDING_VERSION)
            .putString(KEY_LAST_RESULT, result)
            .commit()
    }

    @Suppress("DEPRECATION")
    private fun parcelableIntent(source: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.getParcelableExtra(key, Intent::class.java)
        else source.getParcelableExtra(key)

    private companion object {
        const val PREFERENCES = "molido_update_install_state"
        const val KEY_PENDING_SESSION = "pending_package_installer_session"
        const val KEY_PENDING_VERSION = "pending_update_version"
        const val KEY_LAST_RESULT = "last_install_result"
        const val ACTION_INSTALL_STATUS = "ai.molido.vpn.action.PACKAGE_INSTALL_STATUS"
        const val MAX_APK_BYTES = 256 * 1024 * 1024
        const val NO_SESSION = -1
    }
}
