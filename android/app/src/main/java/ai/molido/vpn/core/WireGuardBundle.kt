package ai.molido.vpn.core

/** One WireGuard config inside a bundle. [text] is sensitive: never log it. */
data class WireGuardEntry(val name: String, val text: String) {
    override fun toString(): String = "WireGuardEntry(<redacted>)"
}

/**
 * Several WireGuard configs stored as one text blob, so the existing single-profile secure
 * store keeps working. Each config starts at an `[Interface]` line; comment lines directly
 * above it belong to it, and the first comment is its display name (used for country lookup).
 */
object WireGuardBundle {
    const val MAX_ENTRIES = 200

    fun split(text: String): List<WireGuardEntry> {
        val sections = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        val pending = mutableListOf<String>()
        for (line in text.lines()) {
            val t = line.trim()
            when {
                t.equals("[Interface]", ignoreCase = true) -> {
                    current?.let { sections += it }
                    current = pending.toMutableList().also { it += line }
                    pending.clear()
                }
                t.startsWith("#") || (t.isEmpty() && pending.isNotEmpty()) -> pending += line
                current == null -> pending.clear() // stray text before the first config
                else -> {
                    current.addAll(pending)
                    pending.clear()
                    current += line
                }
            }
        }
        current?.let { sections += it + pending }
        return sections.take(MAX_ENTRIES).mapIndexed { i, lines ->
            val name = lines.firstOrNull { it.trim().startsWith("#") }
                ?.trim()?.trimStart('#')?.trim()
                ?.removePrefix("Name:")?.removePrefix("name:")?.trim()
                .orEmpty()
            WireGuardEntry(name.ifBlank { "WireGuard ${i + 1}" }, lines.joinToString("\n").trim())
        }
    }

    /** Joins (fileName, text) pairs; a file without a leading comment gets its file name as one. */
    fun join(files: List<Pair<String, String>>): String = files.joinToString("\n\n") { (fileName, text) ->
        val body = text.trim()
        if (body.startsWith("#")) body else "# ${fileName.substringBeforeLast('.')}\n$body"
    }

    /**
     * The config to connect with: the first one in [countryCode], or the first overall when no
     * country is chosen or none matches. Returns [text] unchanged when it holds no `[Interface]`.
     */
    fun select(text: String, countryCode: String?): String {
        val entries = split(text)
        if (entries.isEmpty()) return text
        val match = countryCode?.let { code -> entries.firstOrNull { CountryIndex.countryOf(it.name) == code } }
        return (match ?: entries.first()).text
    }
}
