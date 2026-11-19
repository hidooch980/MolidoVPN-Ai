package ai.molido.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import ai.molido.vpn.core.EngineSlot
import ai.molido.vpn.core.NetworkAssessmentStatus
import ai.molido.vpn.core.NetworkPolicy
import ai.molido.vpn.core.RecoveryAction
import ai.molido.vpn.core.SessionState
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Glass-and-layered dashboard. All displayed connection facts come from local app/OS state. */
class MainActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var networkView: TextView
    private lateinit var profileView: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var recoveryButton: Button
    private lateinit var policyView: TextView
    private var profileText: String? = null
    private var connectAfterConsent = false
    private val molidoApp get() = application as MolidoVpnApplication

    private val profilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                profileText = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_PROFILE_BYTES) { "Profile exceeds size limit" }
                        output.write(buffer, 0, count)
                    }
                    output.toString(StandardCharsets.UTF_8.name())
                } ?: error("Unable to read profile")
                require(!profileText.isNullOrBlank()) { "Empty profile" }
                profileView.text = "پروفایل WireGuard بارگذاری شد • فقط در حافظه"
                connectButton.isEnabled = true
                statusView.text = "پروفایل آماده است؛ برای شروع، مجوز VPN اندروید لازم است."
            } catch (_: Exception) {
                profileText = null
                profileView.text = "هنوز پروفایلی انتخاب نشده"
                connectButton.isEnabled = false
                statusView.text = "خواندن پروفایل ناموفق بود. فایل متنی معتبر تا ۶۴ کیلوبایت انتخاب کنید."
            }
        }
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (connectAfterConsent && result.resultCode == Activity.RESULT_OK) {
            connectAfterConsent = false
            connectUsingLoadedProfile()
        } else {
            connectAfterConsent = false
            statusView.text = "مجوز VPN داده نشد؛ تونلی شروع نشد."
            setControlsEnabled(true)
            renderState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 20, 29)
        window.navigationBarColor = Color.rgb(5, 20, 29)
        window.decorView.systemUiVisibility = 0
        buildDashboard()
        renderState()
    }

    override fun onStart() {
        super.onStart()
        molidoApp.onNetworkAssessmentChanged = { runOnUiThread { renderState() } }
        molidoApp.vpnSessions.onSessionStateChanged = { runOnUiThread { renderState() } }
        renderState()
    }

    override fun onStop() {
        molidoApp.onNetworkAssessmentChanged = null
        molidoApp.vpnSessions.onSessionStateChanged = null
        super.onStop()
    }

    private fun buildDashboard() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 20, 29), Color.rgb(7, 31, 42), Color.rgb(5, 20, 29))
            )
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }

        val header = row()
        header.addView(label("◆  MolidoVPN", 23f, AQUA, true), LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(pill("AI • SECURE", GOLD))
        page.addView(header)

        val hero = card()
        hero.addView(label("وضعیت اتصال", 14f, MUTED, true))
        statusView = label("در حال بررسی وضعیت…", 19f, WHITE, true).apply { setPadding(0, dp(8), 0, dp(8)) }
        hero.addView(statusView)
        val actionRow = row()
        connectButton = glassButton("اتصال امن  ⏻", true).apply {
            isEnabled = false
            setOnClickListener { requestVpnConsentAndConnect() }
        }
        disconnectButton = glassButton("قطع اتصال", false).apply {
            visibility = View.GONE
            setOnClickListener { disconnectTunnel() }
        }
        actionRow.addView(connectButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(8) })
        actionRow.addView(disconnectButton, LinearLayout.LayoutParams(0, dp(54), 1f))
        hero.addView(actionRow)
        profileView = label("هنوز پروفایلی انتخاب نشده", 12f, MUTED, false).apply { setPadding(0, dp(12), 0, 0) }
        hero.addView(profileView)
        page.addView(hero, sectionParams())

        val networkCard = card()
        networkCard.addView(label("هوشمندی شبکه", 17f, AQUA, true))
        networkView = label("در انتظار اطلاعات شبکه سیستم‌عامل…", 14f, WHITE, false).apply { setPadding(0, dp(10), 0, 0) }
        networkCard.addView(networkView)
        networkCard.addView(label("IP عمومی، کشور سرور، پینگ و سرعت واقعی تا زمان اتصال به منبع معتبر نمایش داده نمی‌شوند.", 12f, MUTED, false))
        page.addView(networkCard, sectionParams())

        val engineCard = card()
        engineCard.addView(label("موتورهای VPN", 17f, AQUA, true))
        engineCard.addView(label("فقط موتورهای پیاده‌سازی و آزمایش‌شده قابل انتخاب هستند.", 12f, MUTED, false))
        engineCard.addView(engineRow("WireGuard", "پیاده‌سازی شده • نیازمند تست دستگاه", true))
        engineCard.addView(engineRow("AmneziaWG", "هنوز پیاده‌سازی نشده", false))
        engineCard.addView(engineRow("V2Ray / Xray", "هنوز پیاده‌سازی نشده", false))
        engineCard.addView(engineRow("Psiphon", "هنوز پیاده‌سازی نشده", false))
        engineCard.addView(engineRow("Tor", "هنوز پیاده‌سازی نشده", false))
        engineCard.addView(engineRow("MASQUE / WARP", "هنوز پیاده‌سازی نشده", false))
        page.addView(engineCard, sectionParams())

        val profileCard = card()
        profileCard.addView(label("پروفایل و تنظیمات اتصال", 17f, AQUA, true))
        val import = glassButton("＋  وارد کردن پروفایل WireGuard", false)
        import.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream", "application/x-wireguard-config"))
            }
            profilePicker.launch(intent)
        }
        profileCard.addView(import, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        policyView = label("سیاست شبکه: هر شبکه معتبر", 13f, WHITE, true).apply { setPadding(0, dp(14), 0, dp(8)) }
        profileCard.addView(policyView)
        val policies = row()
        policies.addView(smallPolicy("همه", NetworkPolicy.ANY_VALIDATED), LinearLayout.LayoutParams(0, dp(42), 1f))
        policies.addView(smallPolicy("بدون‌هزینه", NetworkPolicy.UNMETERED_ONLY), LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
        policies.addView(smallPolicy("فقط Wi‑Fi", NetworkPolicy.WIFI_ONLY), LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
        profileCard.addView(policies)
        page.addView(profileCard, sectionParams())

        val recoveryCard = card()
        recoveryCard.addView(label("بازیابی و امنیت", 17f, AQUA, true))
        recoveryCard.addView(label("مالکیت TUN یکتا • کلیدهای اعتماد در Android Keystore • پروفایل فقط در حافظه", 13f, MUTED, false))
        recoveryButton = glassButton("پاک‌سازی امن نشست ناموفق", false).apply {
            visibility = View.GONE
            setOnClickListener { prepareRecovery() }
        }
        recoveryCard.addView(recoveryButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        page.addView(recoveryCard, sectionParams())

        val footer = label("خانه     •     سرورها     •     تنظیمات     •     پشتیبانی", 13f, MUTED, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(12))
        }
        page.addView(footer)
        setContentView(scroll)
    }

    private fun engineRow(name: String, state: String, available: Boolean): View {
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(if (available) Color.rgb(18, 66, 69) else Color.rgb(13, 34, 44), if (available) AQUA else STROKE, 16f)
        }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(label(name, 14f, if (available) WHITE else MUTED, true))
        titleColumn.addView(label(state, 11f, if (available) AQUA else MUTED, false))
        line.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(label(if (available) "●" else "○", 18f, if (available) AQUA else MUTED, true))
        val params = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        return line.apply { isEnabled = available; alpha = if (available) 1f else .78f }.also { it.layoutParams = params }
    }

    private fun smallPolicy(text: String, policy: NetworkPolicy): Button =
        glassButton(text, molidoAppPolicy() == policy).apply {
            textSize = 11f
            setOnClickListener {
                molidoApp.setNetworkPolicy(policy)
                policyView.text = "سیاست شبکه: " + when (policy) {
                    NetworkPolicy.ANY_VALIDATED -> "هر شبکه معتبر"
                    NetworkPolicy.UNMETERED_ONLY -> "فقط شبکه بدون هزینه"
                    NetworkPolicy.WIFI_ONLY -> "فقط Wi‑Fi"
                }
                renderState()
            }
        }

    // The application exposes the active policy through this UI-owned selection.
    private fun molidoAppPolicy() = molidoApp.currentNetworkPolicy()

    private fun requestVpnConsentAndConnect() {
        if (profileText.isNullOrBlank()) {
            Toast.makeText(this, "ابتدا پروفایل WireGuard را وارد کنید.", Toast.LENGTH_SHORT).show()
            return
        }
        val consent = VpnService.prepare(this)
        if (consent == null) connectUsingLoadedProfile() else {
            connectAfterConsent = true
            setControlsEnabled(false)
            statusView.text = "در انتظار مجوز VPN اندروید…"
            vpnConsent.launch(consent)
        }
    }

    private fun connectUsingLoadedProfile() {
        val configuration = profileText ?: return
        setControlsEnabled(false)
        molidoApp.vpnExecutor.execute {
            try {
                val started = molidoApp.vpnSessions.connect(EngineSlot.WIREGUARD, configuration)
                runOnUiThread {
                    setControlsEnabled(true)
                    if (!started) statusView.text = molidoApp.vpnSessions.latestPathDecision()?.reason ?: "شروع نشست ممکن نشد."
                    renderState()
                }
            } catch (_: Throwable) {
                runOnUiThread { setControlsEnabled(true); renderState("شروع WireGuard ناموفق بود؛ وضعیت پاک‌سازی را بررسی کنید.") }
            }
        }
    }

    private fun disconnectTunnel() {
        setControlsEnabled(false)
        molidoApp.vpnExecutor.execute {
            try {
                molidoApp.vpnSessions.disconnect()
                runOnUiThread { setControlsEnabled(true); renderState() }
            } catch (_: Throwable) {
                runOnUiThread { setControlsEnabled(true); renderState("قطع اتصال ناموفق بود؛ مالکیت TUN برای پاک‌سازی امن حفظ شده است.") }
            }
        }
    }

    private fun prepareRecovery() {
        recoveryButton.isEnabled = false
        molidoApp.vpnExecutor.execute {
            val plan = molidoApp.vpnSessions.prepareRecovery(attempt = 0)
            runOnUiThread {
                recoveryButton.isEnabled = true
                Toast.makeText(this, plan.reason, Toast.LENGTH_LONG).show()
                renderState()
            }
        }
    }

    private fun renderState(message: String? = null) {
        val snapshot = molidoApp.vpnSessions.snapshot()
        val network = molidoApp.latestNetworkAssessment()
        statusView.text = when (snapshot.state) {
            SessionState.IDLE -> "●  قطع • آماده اتصال"
            SessionState.RESERVED -> "◌  در حال راه‌اندازی"
            SessionState.RUNNING -> "●  موتور فعال • هندشیک تأیید نشده"
            SessionState.STOPPING -> "◌  در حال پاک‌سازی نشست"
            SessionState.FAILED -> "!  خطا • پاک‌سازی لازم است"
        }
        networkView.text = buildString {
            append("وضعیت: ").append(when (network.status) {
                NetworkAssessmentStatus.VALIDATED -> "اینترنت توسط Android تأیید شده"
                NetworkAssessmentStatus.NOT_VALIDATED -> "شبکه به اینترنت تأیید نشده"
                NetworkAssessmentStatus.STALE -> "داده شبکه قدیمی است"
                NetworkAssessmentStatus.UNKNOWN -> "اطلاعات شبکه در دسترس نیست"
            })
            append("\nنوع اتصال: ").append(network.transport?.name ?: "نامشخص")
            append("\nمصرف شبکه: ").append(when (network.metered) { true -> "دارای هزینه"; false -> "بدون هزینه"; null -> "نامشخص" })
            network.downstreamKbps?.let { append("\nپهنای‌باند اعلامی سیستم: دریافت ").append(it).append(" kbps") }
            network.upstreamKbps?.let { append(" • ارسال ").append(it).append(" kbps") }
        }
        disconnectButton.visibility = if (snapshot.state == SessionState.IDLE) View.GONE else View.VISIBLE
        recoveryButton.visibility = if (snapshot.state == SessionState.FAILED) View.VISIBLE else View.GONE
        connectButton.isEnabled = !profileText.isNullOrBlank() && snapshot.state == SessionState.IDLE &&
            network.status == NetworkAssessmentStatus.VALIDATED
        disconnectButton.isEnabled = snapshot.state != SessionState.IDLE
        policyView.text = "سیاست شبکه: " + when (molidoApp.currentNetworkPolicy()) {
            NetworkPolicy.ANY_VALIDATED -> "هر شبکه معتبر"
            NetworkPolicy.UNMETERED_ONLY -> "فقط شبکه بدون هزینه"
            NetworkPolicy.WIFI_ONLY -> "فقط Wi‑Fi"
        }
        message?.let { statusView.text = it }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        connectButton.isEnabled = enabled && !profileText.isNullOrBlank()
        disconnectButton.isEnabled = enabled
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(Color.rgb(13, 39, 51), STROKE, 24f)
        elevation = dp(8).toFloat()
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    private fun pill(value: String, color: Int) = TextView(this).apply {
        text = value; textSize = 11f; setTextColor(color); gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8)); background = rounded(Color.rgb(17, 54, 63), color, 30f)
    }
    private fun glassButton(value: String, primary: Boolean) = Button(this).apply {
        text = value; textSize = 14f; isAllCaps = false; setTextColor(if (primary) Color.rgb(3, 31, 39) else WHITE)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (primary) intArrayOf(Color.rgb(86, 230, 208), Color.rgb(27, 169, 177), Color.rgb(20, 112, 129))
            else intArrayOf(Color.rgb(28, 75, 88), Color.rgb(12, 43, 57))
        ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), if (primary) AQUA else STROKE) }
        elevation = dp(5).toFloat()
        stateListAnimator = null
    }
    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); setStroke(dp(1), stroke)
    }
    private fun sectionParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_PROFILE_BYTES = 64 * 1024
        const val AQUA = 0xFF39C7B7.toInt()
        const val STROKE = 0xFF246E70.toInt()
        const val GOLD = 0xFFE2C56C.toInt()
        const val WHITE = 0xFFEAF5F7.toInt()
        const val MUTED = 0xFF8DA9B2.toInt()
    }
}
