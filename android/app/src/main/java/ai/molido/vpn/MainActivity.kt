package ai.molido.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import ai.molido.vpn.core.AdaptivePathPolicyCore
import ai.molido.vpn.core.ConnectionMode
import ai.molido.vpn.core.CountryBucket
import ai.molido.vpn.core.CountryIndex
import ai.molido.vpn.core.EngineSlot
import ai.molido.vpn.core.NetworkAssessmentStatus
import ai.molido.vpn.core.NetworkPolicy
import ai.molido.vpn.core.PathDecision
import ai.molido.vpn.core.SessionState
import ai.molido.vpn.core.UpdateCheckOutcome
import ai.molido.vpn.core.WarpRegistration
import ai.molido.vpn.core.WireGuardBundle
import ai.molido.vpn.engine.singbox.ShareLinkParser
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** One clean screen: status + one main button, then server, profiles and settings. */
class MainActivity : ComponentActivity() {
    private val ui by lazy { UiKit(this) }
    private val molidoApp get() = application as MolidoVpnApplication
    private val prefs by lazy { getSharedPreferences("molido_ui", MODE_PRIVATE) }

    private lateinit var orb: PlasmaCoreView
    private lateinit var statusPill: TextView
    private lateinit var titleView: TextView
    private lateinit var noticeView: TextView
    private lateinit var profileView: TextView
    private lateinit var networkView: TextView
    private lateinit var mainButton: Button
    private lateinit var countrySpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var policySpinner: Spinner
    private lateinit var profileActions: LinearLayout

    private var profileText: String? = null
    private var countryBuckets: List<CountryBucket> = emptyList()
    private var countedProfileHash: Int? = null
    private var busy = false
    private var handshakeConfirmed = false
    private var notice: String? = null
    private var connectAfterConsent = false

    private val profilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        if (!isIdle()) { say("ابتدا اتصال را قطع کنید."); return@registerForActivityResult }
        val data = result.data ?: return@registerForActivityResult
        val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
            ?: listOfNotNull(data.data)
        if (uris.isEmpty()) return@registerForActivityResult
        try {
            var total = 0
            val files = uris.map { uri ->
                val text = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_PROFILE_BYTES) { "Profile exceeds size limit" }
                        output.write(buffer, 0, count)
                    }
                    output.toString(StandardCharsets.UTF_8.name())
                } ?: error("Unable to read profile")
                displayName(uri) to text
            }
            val loaded = if (files.size == 1) files[0].second
            else WireGuardBundle.join(files.filter { it.second.contains("[Interface]", ignoreCase = true) })
            require(loaded.isNotBlank()) { "Empty profile" }
            profileText = loaded
            say("پروفایل آماده است.")
            offerSecureProfileStorage()
        } catch (_: Exception) {
            say("خواندن فایل ناموفق بود. یک فایل متنی معتبر تا ۶۴ کیلوبایت انتخاب کنید.")
        }
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val wanted = connectAfterConsent
        connectAfterConsent = false
        if (wanted && result.resultCode == Activity.RESULT_OK) connectUsingLoadedProfile()
        else { busy = false; say("مجوز VPN داده نشد.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        setContentView(buildScreen())
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

    // ---------- Layout ----------

    private fun buildScreen(): View {
        val page = ui.column().apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(28))
        }

        val header = ui.row()
        header.addView(ui.text("MolidoVPN", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        statusPill = ui.pill("قطع", Palette.MUTED)
        header.addView(statusPill)
        page.addView(header)

        val hero = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(18), ui.dp(22), ui.dp(18), ui.dp(18)) }
        orb = PlasmaCoreView(this).apply { contentDescription = "وضعیت اتصال" }
        hero.addView(orb, LinearLayout.LayoutParams(ui.dp(180), ui.dp(180)))
        titleView = ui.text("", 20f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(14), 0, 0) }
        hero.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        noticeView = ui.text("", 13f, Palette.WARN).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
        hero.addView(noticeView, LinearLayout.LayoutParams(-1, -2))
        profileView = ui.text("", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
        hero.addView(profileView, LinearLayout.LayoutParams(-1, -2))
        mainButton = ui.primaryButton("اتصال").apply { setOnClickListener { onMainButton() } }
        hero.addView(mainButton, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { topMargin = ui.dp(18) })
        networkView = ui.text("", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, 0) }
        hero.addView(networkView, LinearLayout.LayoutParams(-1, -2))
        page.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })

        page.addView(ui.sectionLabel("سرور"))
        val server = ui.card()
        server.addView(ui.text("مکان خروجی", 12f, Palette.MUTED).apply { setPadding(ui.dp(2), 0, 0, ui.dp(6)) })
        countrySpinner = ui.dropdown(listOf("🌐 خودکار"), 0) { index ->
            val bucket = countryBuckets.getOrNull(index - 1)
            if (bucket != null) prefs.edit().putString("country_code", bucket.code).apply()
            else prefs.edit().remove("country_code").apply()
        }
        server.addView(countrySpinner, LinearLayout.LayoutParams(-1, ui.dp(48)))
        server.addView(ui.text("حالت اتصال", 12f, Palette.MUTED).apply { setPadding(ui.dp(2), ui.dp(14), 0, ui.dp(6)) })
        val modes = ConnectionMode.entries
        val savedMode = ConnectionMode.fromId(prefs.getString("connection_mode", null))
        modeSpinner = ui.dropdown(modes.map { it.label }, modes.indexOf(savedMode)) { index ->
            val mode = modes[index]
            prefs.edit().putString("connection_mode", mode.id).apply()
            if (!mode.available) say("«${mode.title}» هنوز فعال نیست؛ از حالت خودکار استفاده میشود.")
        }
        server.addView(modeSpinner, LinearLayout.LayoutParams(-1, ui.dp(48)))
        page.addView(server, LinearLayout.LayoutParams(-1, -2))

        page.addView(ui.sectionLabel("پروفایلها"))
        profileActions = ui.card().apply { setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4)) }
        addRows(profileActions, listOf(
            ui.actionRow("وارد کردن فایل WireGuard") { pickProfiles() },
            ui.actionRow("دریافت کانفیگ رایگان WARP") { registerWarp() },
            ui.actionRow("بازیابی پروفایل ذخیرهشده") { restoreSecureProfile() }
        ))
        page.addView(profileActions, LinearLayout.LayoutParams(-1, -2))

        page.addView(ui.sectionLabel("تنظیمات"))
        val settings = ui.card()
        settings.addView(ui.text("شبکه مجاز", 12f, Palette.MUTED).apply { setPadding(ui.dp(2), 0, 0, ui.dp(6)) })
        policySpinner = ui.dropdown(POLICY_LABELS, POLICY_ORDER.indexOf(molidoApp.currentNetworkPolicy())) { index ->
            if (!molidoApp.setNetworkPolicy(POLICY_ORDER[index])) say("ذخیره تنظیم شبکه ناموفق بود.")
            renderState()
        }
        settings.addView(policySpinner, LinearLayout.LayoutParams(-1, ui.dp(48)))
        val settingRows = ui.column().apply { setPadding(0, ui.dp(6), 0, 0) }
        addRows(settingRows, listOf(
            ui.actionRow("بررسی نسخه جدید") { checkForUpdate() },
            ui.actionRow("حذف پروفایل ذخیرهشده", Palette.DANGER) { revokeSecureProfileStorage() }
        ))
        settings.addView(settingRows)
        page.addView(settings, LinearLayout.LayoutParams(-1, -2))

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        page.addView(ui.text("نسخه $version • دادهها فقط روی همین دستگاه", 11f, Palette.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.dp(22), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        return ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        }
    }

    private fun addRows(parent: LinearLayout, rows: List<View>) {
        rows.forEachIndexed { i, row ->
            if (i > 0) parent.addView(ui.divider(), LinearLayout.LayoutParams(-1, 1))
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    // ---------- State ----------

    private fun isIdle() = molidoApp.vpnSessions.snapshot().state == SessionState.IDLE

    /** Shows a message under the status title until the next action replaces it. */
    private fun say(message: String?) { notice = message; renderState() }

    private fun renderState() {
        val state = molidoApp.vpnSessions.snapshot().state
        if (state != SessionState.RUNNING) handshakeConfirmed = false
        refreshCountries()
        val idle = state == SessionState.IDLE

        val (title, pillText, color) = when (state) {
            SessionState.IDLE -> Triple("قطع", "قطع", Palette.MUTED)
            SessionState.RESERVED -> Triple("در حال اتصال…", "اتصال…", Palette.ACCENT_2)
            SessionState.RUNNING -> if (handshakeConfirmed) Triple("متصل", "متصل", Palette.SUCCESS)
                else Triple("متصل • در انتظار پاسخ سرور", "اتصال…", Palette.ACCENT_2)
            SessionState.STOPPING -> Triple("در حال قطع…", "قطع…", Palette.WARN)
            SessionState.FAILED -> Triple("اتصال با خطا متوقف شد", "خطا", Palette.DANGER)
        }
        titleView.text = title
        statusPill.text = pillText
        statusPill.setTextColor(color)
        statusPill.background = ui.rounded(Palette.SURFACE_2, 30, color)
        orb.setSessionState(state)

        noticeView.text = notice.orEmpty()
        noticeView.visibility = if (notice.isNullOrBlank()) View.GONE else View.VISIBLE
        profileView.text = profileSummary()

        val network = molidoApp.latestNetworkAssessment()
        val netState = when (network.status) {
            NetworkAssessmentStatus.VALIDATED -> "اینترنت در دسترس"
            NetworkAssessmentStatus.NOT_VALIDATED -> "اینترنت تأیید نشده"
            NetworkAssessmentStatus.STALE, NetworkAssessmentStatus.UNKNOWN -> "وضعیت شبکه نامشخص"
        }
        networkView.text = listOfNotNull(network.transport?.name, netState).joinToString(" • ")

        when (state) {
            SessionState.IDLE -> styleMain("اتصال", danger = false)
            SessionState.RESERVED, SessionState.RUNNING -> styleMain("قطع اتصال", danger = true)
            SessionState.STOPPING -> styleMain("در حال قطع…", danger = true)
            SessionState.FAILED -> styleMain("پاکسازی و آمادهسازی دوباره", danger = true)
        }
        mainButton.isEnabled = !busy && state != SessionState.STOPPING
        mainButton.alpha = if (mainButton.isEnabled) 1f else 0.5f

        val editable = !busy && idle
        listOf(countrySpinner, modeSpinner, policySpinner).forEach { it.isEnabled = editable; it.alpha = if (editable) 1f else 0.5f }
        for (i in 0 until profileActions.childCount) profileActions.getChildAt(i).apply { isEnabled = editable; alpha = if (editable) 1f else 0.5f }
    }

    private fun styleMain(label: String, danger: Boolean) {
        mainButton.text = label
        mainButton.background = if (danger) ui.rounded(0x33EF4444, 18, Palette.DANGER)
        else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.ACCENT, Palette.ACCENT_2))
            .apply { cornerRadius = ui.dp(18).toFloat() }
    }

    private fun profileSummary(): String {
        val text = profileText ?: return "هنوز پروفایلی وارد نشده"
        val count = runCatching { WireGuardBundle.split(text).size }.getOrDefault(1).coerceAtLeast(1)
        return if (count > 1) "$count پروفایل آماده" else "۱ پروفایل آماده"
    }

    private fun refreshCountries() {
        val text = profileText.orEmpty()
        val hash = text.hashCode()
        if (hash == countedProfileHash) return
        countedProfileHash = hash
        val names = runCatching {
            when {
                text.isBlank() -> emptyList()
                text.contains("://") || !text.contains("[Interface]") -> ShareLinkParser.parseSubscription(text).map { it.name }
                else -> WireGuardBundle.split(text).map { it.name }
            }
        }.getOrDefault(emptyList())
        countryBuckets = CountryIndex.group(names)
        @Suppress("UNCHECKED_CAST")
        val adapter = countrySpinner.adapter as ArrayAdapter<String>
        adapter.clear()
        adapter.add(if (names.isEmpty()) "🌐 خودکار" else "🌐 خودکار (${names.size})")
        adapter.addAll(countryBuckets.map { it.label })
        adapter.notifyDataSetChanged()
        val index = countryBuckets.indexOfFirst { it.code == prefs.getString("country_code", null) }
        countrySpinner.setSelection(if (index >= 0) index + 1 else 0, false)
    }

    // ---------- Actions ----------

    private fun onMainButton() {
        when (molidoApp.vpnSessions.snapshot().state) {
            SessionState.IDLE -> requestVpnConsentAndConnect()
            SessionState.RESERVED, SessionState.RUNNING -> disconnectTunnel()
            SessionState.FAILED -> prepareRecovery()
            SessionState.STOPPING -> Unit
        }
    }

    private fun pickProfiles() {
        if (!isIdle()) { say("ابتدا اتصال را قطع کنید."); return }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream", "application/x-wireguard-config"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { profilePicker.launch(intent) }.onFailure { say("انتخابگر فایل باز نشد.") }
    }

    private fun requestVpnConsentAndConnect() {
        if (profileText.isNullOrBlank()) { say("ابتدا یک پروفایل وارد کنید یا WARP بگیرید."); return }
        val decision = AdaptivePathPolicyCore.evaluate(molidoApp.latestNetworkAssessment(), molidoApp.currentNetworkPolicy())
        if (decision.decision != PathDecision.ALLOW) { say(decision.reason); return }
        val consent = VpnService.prepare(this)
        if (consent == null) connectUsingLoadedProfile()
        else {
            connectAfterConsent = true
            busy = true
            say("مجوز VPN اندروید را تأیید کنید.")
            vpnConsent.launch(consent)
        }
    }

    private fun connectUsingLoadedProfile() {
        val loaded = profileText ?: return
        val candidates = WireGuardBundle.candidates(loaded, prefs.getString("country_code", null)).take(MAX_FAILOVER_ATTEMPTS)
        if (candidates.isEmpty()) { busy = false; say("پروفایل معتبری پیدا نشد."); return }
        busy = true
        say(null)
        molidoApp.vpnExecutor.execute {
            val sessions = molidoApp.vpnSessions
            for ((index, configuration) in candidates.withIndex()) {
                val isLast = index == candidates.lastIndex
                val step = if (candidates.size > 1) " (${index + 1} از ${candidates.size})" else ""
                runOnUiThread { say("در حال امتحان سرور$step…") }
                val started: Boolean? = try { sessions.connect(EngineSlot.WIREGUARD, configuration) } catch (_: Throwable) { null }
                if (started == true) {
                    val answered = sessions.activeHandshakeProbe()?.awaitHandshake(HANDSHAKE_TIMEOUT_MS) ?: true
                    if (answered || isLast) {
                        runOnUiThread {
                            busy = false
                            handshakeConfirmed = answered
                            say(if (answered) null else "هیچ سروری پاسخ نداد؛ اتصال را قطع کنید یا پروفایل دیگری امتحان کنید.")
                        }
                        return@execute
                    }
                }
                // false + IDLE = blocked by network policy: another config would be blocked too.
                val blocked = started == false && sessions.snapshot().state == SessionState.IDLE
                if (!blocked && !isLast) {
                    val cleaned = try { sessions.snapshot().state == SessionState.IDLE || sessions.disconnect() } catch (_: Throwable) { false }
                    if (cleaned) continue
                }
                val reason = if (started == false) sessions.latestPathDecision()?.reason ?: "شروع اتصال ممکن نشد."
                else "شروع WireGuard ناموفق بود."
                runOnUiThread { busy = false; say(reason) }
                return@execute
            }
        }
    }

    private fun disconnectTunnel() {
        busy = true
        say(null)
        molidoApp.vpnExecutor.execute {
            val ok = try { molidoApp.vpnSessions.disconnect(); true } catch (_: Throwable) { false }
            runOnUiThread { busy = false; say(if (ok) null else "قطع اتصال ناموفق بود؛ دکمه پاکسازی را بزنید.") }
        }
    }

    private fun prepareRecovery() {
        busy = true
        renderState()
        molidoApp.vpnExecutor.execute {
            val plan = molidoApp.vpnSessions.prepareRecovery(attempt = 0)
            runOnUiThread { busy = false; say(plan.reason) }
        }
    }

    private fun registerWarp() {
        if (!isIdle()) { say("ابتدا اتصال را قطع کنید."); return }
        busy = true
        say("در حال دریافت کانفیگ WARP…")
        Thread {
            val config = runCatching { WarpRegistration.withEndpoints(WarpRegistration.register(), WARP_ENDPOINT_VARIANTS) }.getOrNull()
            runOnUiThread {
                busy = false
                if (config == null) { say("دریافت WARP ناموفق بود؛ شاید در شبکه شما مسدود باشد."); return@runOnUiThread }
                val existing = profileText?.takeIf { it.contains("[Interface]", ignoreCase = true) }
                val merged = if (existing == null) config else existing.trim() + "\n\n" + config
                profileText = if (merged.toByteArray(StandardCharsets.UTF_8).size <= MAX_PROFILE_BYTES) merged else config
                say("کانفیگ WARP اضافه شد.")
                offerSecureProfileStorage()
            }
        }.start()
    }

    private fun checkForUpdate() {
        if (!isIdle()) { say("برای آپدیت ابتدا اتصال را قطع کنید."); return }
        Toast.makeText(this, "در حال بررسی نسخه جدید…", Toast.LENGTH_SHORT).show()
        molidoApp.vpnExecutor.execute {
            val outcome = molidoApp.updateChecker.checkAndInstall()
            runOnUiThread {
                val message = when (outcome) {
                    UpdateCheckOutcome.NOT_CONFIGURED -> "آپدیت خودکار در این نسخه فعال نیست."
                    UpdateCheckOutcome.UP_TO_DATE -> "آخرین نسخه را دارید."
                    UpdateCheckOutcome.INSTALL_STARTED -> "نسخه جدید آماده است؛ نصب را تأیید کنید."
                    UpdateCheckOutcome.NO_MANIFEST -> "نسخه امضاشدهای پیدا نشد."
                    UpdateCheckOutcome.REJECTED -> "آپدیت رد شد: امضا یا فایل معتبر نبود."
                    UpdateCheckOutcome.NETWORK_ERROR -> "اتصال به سرور آپدیت ناموفق بود."
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- Secure storage ----------

    private fun offerSecureProfileStorage() {
        val store = molidoApp.secureProfileStore
        val hasConsent = store.hasUserConsent()
        AlertDialog.Builder(this)
            .setTitle("ذخیره پروفایل")
            .setMessage(if (hasConsent) "پروفایل ذخیرهشده با این یکی جایگزین شود؟"
                else "پروفایل رمزگذاریشده فقط روی همین دستگاه ذخیره شود تا دفعه بعد لازم نباشد دوباره واردش کنید؟")
            .setNegativeButton("نه", null)
            .setPositiveButton("ذخیره") { _, _ -> persistCurrentProfile(userConfirmed = !hasConsent) }
            .show()
    }

    private fun persistCurrentProfile(userConfirmed: Boolean) {
        if (!isIdle()) return
        val bytes = profileText?.toByteArray(StandardCharsets.UTF_8) ?: return
        try {
            if (userConfirmed) molidoApp.secureProfileStore.saveProfileWithConsent(bytes, userConfirmed = true)
            else molidoApp.secureProfileStore.saveProfile(bytes)
            Toast.makeText(this, "پروفایل رمزگذاری و ذخیره شد.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "ذخیره پروفایل ناموفق بود.", Toast.LENGTH_LONG).show()
        } finally {
            bytes.fill(0)
        }
    }

    private fun restoreSecureProfile() {
        if (!isIdle()) { say("ابتدا اتصال را قطع کنید."); return }
        if (!molidoApp.secureProfileStore.hasUserConsent()) { say("پروفایل ذخیرهشدهای وجود ندارد."); return }
        var bytes: ByteArray? = null
        try {
            bytes = molidoApp.secureProfileStore.loadProfile() ?: error("No encrypted profile")
            require(bytes.isNotEmpty() && bytes.size <= MAX_PROFILE_BYTES)
            val text = String(bytes, StandardCharsets.UTF_8)
            require(text.isNotBlank())
            profileText = text
            say("پروفایل ذخیرهشده بازیابی شد.")
        } catch (_: Exception) {
            say("بازیابی پروفایل ذخیرهشده ناموفق بود.")
        } finally {
            bytes?.fill(0)
        }
    }

    private fun revokeSecureProfileStorage() {
        if (!isIdle()) { say("ابتدا اتصال را قطع کنید."); return }
        AlertDialog.Builder(this)
            .setTitle("حذف پروفایل ذخیرهشده")
            .setMessage("پروفایل رمزگذاریشده و کلید آن از این دستگاه حذف میشود.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("حذف") { _, _ ->
                try {
                    molidoApp.secureProfileStore.revokeConsentAndDelete()
                    profileText = null
                    say("پروفایل ذخیرهشده حذف شد.")
                } catch (_: Exception) {
                    say("حذف کامل انجام نشد.")
                }
            }
            .show()
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

    private companion object {
        // Must match AndroidSecureProfileStore.MAX_PROFILE_BYTES so a loaded bundle can be saved.
        const val MAX_PROFILE_BYTES = 64 * 1024
        const val MAX_FAILOVER_ATTEMPTS = 8
        const val WARP_ENDPOINT_VARIANTS = 6
        const val HANDSHAKE_TIMEOUT_MS = 8_000L
        val POLICY_ORDER = listOf(NetworkPolicy.ANY_VALIDATED, NetworkPolicy.UNMETERED_ONLY, NetworkPolicy.WIFI_ONLY)
        val POLICY_LABELS = listOf("همه شبکهها", "فقط شبکه بدون هزینه", "فقط Wi‑Fi")
    }
}
