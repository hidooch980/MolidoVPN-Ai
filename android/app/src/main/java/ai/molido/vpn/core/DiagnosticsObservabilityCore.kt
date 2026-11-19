package ai.molido.vpn.core

/**
 * Bounded, process-local diagnostics. Disabled by default; no disk, network,
 * endpoint, profile, or user-content persistence.
 */
data class DiagnosticEvent(
    val code: String,
    val occurredAtEpochMillis: Long,
    val detail: String
)

class DiagnosticsObservabilityCore(
    private val capacity: Int = 100,
    private val enabled: Boolean = false
) {
    private val events = ArrayDeque<DiagnosticEvent>()

    init { require(capacity in 1..1_000) }

    @Synchronized
    fun record(code: String, detail: String, nowEpochMillis: Long) {
        if (!enabled) return
        require(code.matches(Regex("[A-Z0-9_]{1,48}"))) { "Diagnostic code must be a stable non-sensitive identifier" }
        require(nowEpochMillis >= 0)
        val safeDetail = PrivacyRedactor.redact(detail).take(MAX_DETAIL_LENGTH)
        while (events.size >= capacity) events.removeFirst()
        events.addLast(DiagnosticEvent(code, nowEpochMillis, safeDetail))
    }

    @Synchronized
    fun snapshot(): List<DiagnosticEvent> = events.toList()

    @Synchronized
    fun clear() = events.clear()

    private companion object { const val MAX_DETAIL_LENGTH = 256 }
}
