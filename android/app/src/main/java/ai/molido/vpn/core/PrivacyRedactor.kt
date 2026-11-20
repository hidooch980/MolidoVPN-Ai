package ai.molido.vpn.core

/**
 * Conservative redaction for local diagnostic text. Diagnostics must never be
 * treated as a substitute for structured, typed error codes.
 */
object PrivacyRedactor {
    private val assignments = Regex(
        """(?i)\b(private[_ -]?key|preshared[_ -]?key|password|passwd|token|authorization|secret)\s*[:=]\s*(?:Bearer\s+)?([^\s,;]+)"""
    )
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

    fun redact(input: String): String =
        input
            .replace(assignments) { "${it.groupValues[1]}=<redacted>" }
            .replace(bearer, "Bearer <redacted>")
            .replace(ipv4, "<ip-redacted>")
}
