package ai.molido.vpn.core

/**
 * Connection modes shown in the mode picker. [available] is true only when an engine in this
 * build can actually run the mode; the UI must never claim a mode works before its engine ships.
 */
enum class ConnectionMode(val id: String, val title: String, val hint: String, val available: Boolean) {
    AUTO("auto", "خودکار", "بهترین مسیر موجود را خودش انتخاب میکند", true),
    WIREGUARD("wireguard", "وایرگارد", "پروفایل WireGuard واردشده", true),
    GAMING_PLUS("gaming_plus", "گیمینگ پلاس", "کمترین تأخیر، اولویت UDP", false),
    ANTI_FILTER_HARD("anti_filter_hard", "ضد اختلال شدید", "برای شبکههای با اختلال سنگین", false),
    DOUBLE_CHAIN("double_chain", "زنجیره دوگانه", "عبور از دو سرور پشت سر هم", false),
    DNS("dns", "DNS", "فقط تغییر و رمزگذاری DNS", false),
    AMNEZIA("amnezia", "امنزیا", "WireGuard مبهمسازیشده (AmneziaWG)", false),
    PSIPHON("psiphon", "سایفون", "شبکه Psiphon", false),
    TOR("tor", "تور", "شبکه Tor با پل", false),
    SHAD("shad", "شاد", "مسیر سازگار با شبکه داخلی", false),
    SHARE_LINK("share_link", "کانفیگ V2Ray/sing-box", "vless، vmess، trojan، ss، hy2، tuic", false);

    /** Label for the dropdown; unavailable modes are marked rather than hidden. */
    val label: String get() = if (available) title else "$title (بهزودی)"

    companion object {
        fun fromId(id: String?): ConnectionMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}
