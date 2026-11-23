package ai.molido.vpn.core

/** One exit country and how many configs point at it. */
data class CountryBucket(val code: String, val count: Int) {
    val flag: String get() = CountryIndex.flag(code)
    val persianName: String get() = CountryIndex.NAMES[code] ?: code
    val label: String get() = "$flag $persianName ($count کانفیگ)".trim()
}

/**
 * Groups config display names by exit country. A name is matched by its flag emoji first,
 * then by an English country name, then by a standalone two-letter code. Unmatched names go
 * to [UNKNOWN] so the total always equals the number of configs.
 */
object CountryIndex {
    const val UNKNOWN = "??"

    val NAMES: Map<String, String> = linkedMapOf(
        "DE" to "آلمان", "NL" to "هلند", "FI" to "فنلاند", "FR" to "فرانسه", "GB" to "انگلستان",
        "SE" to "سوئد", "CH" to "سوئیس", "TR" to "ترکیه", "AE" to "امارات", "US" to "آمریکا",
        "CA" to "کانادا", "JP" to "ژاپن", "SG" to "سنگاپور", "AM" to "ارمنستان", "RU" to "روسیه",
        "PL" to "لهستان", "AT" to "اتریش", "IT" to "ایتالیا", "ES" to "اسپانیا", "HK" to "هنگکنگ",
        "IN" to "هند", "KR" to "کره جنوبی", "QA" to "قطر", "IR" to "ایران", UNKNOWN to "نامشخص"
    )

    private val ENGLISH = mapOf(
        "germany" to "DE", "netherlands" to "NL", "holland" to "NL", "finland" to "FI", "france" to "FR",
        "united kingdom" to "GB", "england" to "GB", "uk" to "GB", "sweden" to "SE", "switzerland" to "CH",
        "turkey" to "TR", "turkiye" to "TR", "emirates" to "AE", "uae" to "AE", "dubai" to "AE",
        "united states" to "US", "usa" to "US", "america" to "US", "canada" to "CA", "japan" to "JP",
        "singapore" to "SG", "armenia" to "AM", "russia" to "RU", "poland" to "PL", "austria" to "AT",
        "italy" to "IT", "spain" to "ES", "hong kong" to "HK", "india" to "IN", "korea" to "KR",
        "qatar" to "QA", "iran" to "IR"
    )

    private val CODE_TOKEN = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])")

    fun countryOf(name: String): String {
        flagCode(name)?.let { return it }
        val lower = name.lowercase()
        ENGLISH.entries.firstOrNull { (word, _) -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lower) }
            ?.let { return it.value }
        CODE_TOKEN.findAll(name).map { it.groupValues[1] }.firstOrNull { it in NAMES }?.let { return it }
        return UNKNOWN
    }

    /** Buckets sorted by count (largest first); unknown always last. */
    fun group(names: List<String>): List<CountryBucket> =
        names.groupingBy(::countryOf).eachCount()
            .map { (code, count) -> CountryBucket(code, count) }
            .sortedWith(compareBy<CountryBucket> { it.code == UNKNOWN }.thenByDescending { it.count }.thenBy { it.code })

    fun flag(code: String): String {
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return "🏳"
        return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    private fun flagCode(name: String): String? {
        val points = name.codePoints().toArray()
        for (i in 0 until points.size - 1) {
            val a = points[i] - 0x1F1E6
            val b = points[i + 1] - 0x1F1E6
            if (a in 0..25 && b in 0..25) return "${'A' + a}${'A' + b}"
        }
        return null
    }
}
