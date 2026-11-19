package ai.molido.vpn.core

/** Persists only the non-secret network-selection preference. */
class NetworkPolicySettingsStore(
    private val readString: (String) -> String?,
    private val writeString: (String, String) -> Boolean
) {
    fun load(): NetworkPolicy = parse(readString(POLICY_KEY))

    fun save(policy: NetworkPolicy): Boolean = writeString(POLICY_KEY, policy.name)

    companion object {
        const val PREFERENCES_NAME = "molido_network_settings"
        const val POLICY_KEY = "network_policy"

        fun parse(value: String?): NetworkPolicy =
            NetworkPolicy.values().firstOrNull { it.name == value } ?: NetworkPolicy.ANY_VALIDATED
    }
}
