package ai.molido.vpn.core

import android.content.SharedPreferences

/** Synchronous commit is intentional: retry reservation must survive abrupt process death. */
class AndroidRecoveryAttemptStateStore(
    private val preferences: SharedPreferences
) : RecoveryAttemptStateStore {
    override fun read(): PersistedRecoveryAttemptState {
        val encoded = preferences.getString(KEY_STATE, null) ?: return PersistedRecoveryAttemptState()
        val parts = encoded.split(':')
        if (parts.size != 4) return PersistedRecoveryAttemptState(
            attemptsStarted = ResilienceRecoveryCore.DEFAULT_MAX_ATTEMPTS,
            interrupted = true
        )
        return runCatching {
            PersistedRecoveryAttemptState(
                attemptsStarted = parts[0].toInt(),
                generation = parts[1].toLong(),
                inFlight = parts[2].toBooleanStrict(),
                interrupted = parts[3].toBooleanStrict()
            )
        }.getOrElse {
            PersistedRecoveryAttemptState(
                attemptsStarted = ResilienceRecoveryCore.DEFAULT_MAX_ATTEMPTS,
                interrupted = true
            )
        }
    }

    override fun write(state: PersistedRecoveryAttemptState): Boolean =
        preferences.edit().putString(
            KEY_STATE,
            "${state.attemptsStarted}:${state.generation}:${state.inFlight}:${state.interrupted}"
        ).commit()

    private companion object {
        const val KEY_STATE = "bounded_recovery_state_v1"
    }
}
