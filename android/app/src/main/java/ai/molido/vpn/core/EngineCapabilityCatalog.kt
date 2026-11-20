package ai.molido.vpn.core

/**
 * Runtime capability catalog. UI must derive selectable engines and destinations
 * from this catalog rather than presenting planned integrations as working.
 *
 * Registration means source adapter exists; it does NOT mean a tunnel is connected
 * or production verified. Country options are supplied only by a trusted provider
 * inventory and must never be guessed from a generic country list.
 */
enum class EngineReadiness {
    NOT_IMPLEMENTED,
    SOURCE_IMPLEMENTED,
    DEVICE_TESTED,
    PRODUCTION_VERIFIED
}

data class EngineCapability(
    val slot: EngineSlot,
    val displayName: String,
    val readiness: EngineReadiness,
    val adapterRegistered: Boolean,
    val countrySelectionSupported: Boolean,
    val availableCountries: List<String>,
    val statusDetail: String
) {
    /** A profile/configuration is still required before a connection attempt. */
    val selectable: Boolean get() = adapterRegistered

    init {
        require(availableCountries.distinct().size == availableCountries.size) {
            "Country inventory must not contain duplicates"
        }
        require(availableCountries.all { it.isNotBlank() }) {
            "Country inventory cannot contain blank entries"
        }
        require(countrySelectionSupported || availableCountries.isEmpty()) {
            "Countries cannot be exposed when provider country selection is unsupported"
        }
    }
}

object EngineCapabilityCatalog {
    private val names = mapOf(
        EngineSlot.WIREGUARD to "WireGuard",
        EngineSlot.AMNEZIA_WG to "AmneziaWG",
        EngineSlot.V2RAY_XRAY to "V2Ray / Xray",
        EngineSlot.PSIPHON to "Psiphon",
        EngineSlot.TOR to "Tor",
        EngineSlot.MASQUE_WARP to "MASQUE / WARP"
    )

    /**
     * @param adapters adapters actually wired into VpnSessionController.
     * @param verifiedSlots slots with independently evidenced physical-device verification.
     * @param countryInventories provider-supplied country identifiers; omit unless verified.
     * A slot is never promoted to DEVICE_TESTED merely because an adapter is registered.
     */
    fun build(
        adapters: Collection<EngineAdapter>,
        verifiedSlots: Set<EngineSlot> = emptySet(),
        countryInventories: Map<EngineSlot, List<String>> = emptyMap()
    ): List<EngineCapability> {
        val registered = adapters.map { it.slot }.toSet()
        require(registered.size == adapters.size) { "Duplicate engine adapter slot" }
        require(verifiedSlots.all { it in registered }) {
            "A device-verified engine must have a registered adapter"
        }

        return EngineSlot.entries.map { slot ->
            val hasAdapter = slot in registered
            val verified = slot in verifiedSlots
            val countries = if (verified) countryInventories[slot].orEmpty().distinct() else emptyList()
            EngineCapability(
                slot = slot,
                displayName = names.getValue(slot),
                readiness = when {
                    !hasAdapter -> EngineReadiness.NOT_IMPLEMENTED
                    verified -> EngineReadiness.DEVICE_TESTED
                    else -> EngineReadiness.SOURCE_IMPLEMENTED
                },
                adapterRegistered = hasAdapter,
                countrySelectionSupported = countries.isNotEmpty(),
                availableCountries = countries,
                statusDetail = when {
                    !hasAdapter -> "Adapter not implemented"
                    verified -> "Device-tested; production verification is separate"
                    else -> "Source adapter registered; device connectivity not verified"
                }
            )
        }
    }
}
