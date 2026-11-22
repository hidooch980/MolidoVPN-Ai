package ai.molido.vpn

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import ai.molido.vpn.core.EngineSlot
import ai.molido.vpn.core.NetworkAssessmentStatus
import ai.molido.vpn.core.AdaptivePathPolicyCore
import ai.molido.vpn.core.PathDecision
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
    private lateinit var profileImportButton: Button
    private lateinit var wireGuardStateView: TextView
    private lateinit var providerSpinner: Spinner
    private lateinit var countrySpinner: Spinner
    private lateinit var countryAdapter: ArrayAdapter<String>
    private lateinit var providerStatusView: TextView
    private var controlsBusy = false
    private val policyButtons = mutableMapOf<NetworkPolicy, Button>()
    private var profileText: String? = null
    private var connectAfterConsent = false
    private val molidoApp get() = application as MolidoVpnApplication

    private val profilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (molidoApp.vpnSessions.snapshot().state != SessionState.IDLE) {
                Toast.makeText(this, "هنگام فعال‌بودن یا پاک‌سازی نشست، تغییر پروفایل مجاز نیست.", Toast.LENGTH_LONG).show()
                renderState()
                return@registerForActivityResult
            }
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
                offerSecureProfileStorage()
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
        engineCard.addView(label("وضعیت هر موتور از ثبت آداپتور و مراحل واقعی آزمون استخراج می‌شود.", 12f, MUTED, false))
        molidoApp.engineCapabilities.forEach { capability ->
            val status = when (capability.readiness) {
                ai.molido.vpn.core.EngineReadiness.NOT_IMPLEMENTED -> "هنوز پیاده‌سازی نشده"
                ai.molido.vpn.core.EngineReadiness.SOURCE_IMPLEMENTED -> "کد موجود • تست اتصال روی دستگاه لازم است"
                ai.molido.vpn.core.EngineReadiness.CI_BUILT -> "ساخت CI موفق"
                ai.molido.vpn.core.EngineReadiness.CI_TESTED -> "تست CI موفق"
                ai.molido.vpn.core.EngineReadiness.ANDROID_DEVICE_TESTED -> "روی دستگاه Android آزمایش شده"
                ai.molido.vpn.core.EngineReadiness.SERVER_DEPLOYMENT_REQUIRED -> "نیازمند استقرار سرور"
                ai.molido.vpn.core.EngineReadiness.PRODUCTION_VERIFIED -> "تأیید تولید"
                ai.molido.vpn.core.EngineReadiness.BLOCKED -> "مسدود / نیازمند رفع مانع"
            }
            engineCard.addView(engineRow(
                capability.displayName,
                status,
                capability.selectable,
                wireGuard = capability.slot == EngineSlot.WIREGUARD
            ))
        }
        engineCard.addView(glassButton("به‌روزرسانی آمار WireGuard", false).apply {
            setOnClickListener { refreshWireGuardStatistics() }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        page.addView(engineCard, sectionParams())

        val routingCard = card()
        routingCard.addView(label("انتخاب مسیر اتصال", 17f, AQUA, true))
        routingCard.addView(label("سرویس‌دهنده و کشور مقصد را از فهرست‌ها انتخاب کنید. انتخاب، اتصال خودکار ایجاد نمی‌کند.", 12f, MUTED, false))
        routingCard.addView(label("سرویس‌دهنده", 13f, WHITE, true).apply { setPadding(0, dp(12), 0, dp(5)) })
        val selectableCapabilities = molidoApp.engineCapabilities.filter { it.selectable }
        providerSpinner = dropdown(selectableCapabilities.map { it.displayName })
        routingCard.addView(providerSpinner, LinearLayout.LayoutParams(-1, dp(50)))
        routingCard.addView(label("کشور مقصد", 13f, WHITE, true).apply { setPadding(0, dp(12), 0, dp(5)) })
        countryAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            mutableListOf("کشور در پروفایل / سرویس تعیین می‌شود")).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        countrySpinner = Spinner(this).apply {
            adapter = countryAdapter
            background = rounded(Color.rgb(18, 54, 67), STROKE, 14f)
            setPadding(dp(10), 0, dp(10), 0)
        }
        routingCard.addView(countrySpinner, LinearLayout.LayoutParams(-1, dp(50)))
        countrySpinner.isEnabled = false
        providerStatusView = label(
            "فقط موتورهای دارای آداپتور ثبت‌شده در فهرست انتخاب هستند. کشور مقصد تا دریافت موجودی معتبر از سرویس‌دهنده قابل انتخاب نیست.",
            12f, GOLD, false
        ).apply { setPadding(0, dp(10), 0, 0) }
        routingCard.addView(providerStatusView)
        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val capability = selectableCapabilities.getOrNull(position)
                providerStatusView.text = capability?.statusDetail ?: "هیچ موتور قابل انتخابی ثبت نشده است."
                countrySpinner.isEnabled = capability?.countrySelectionSupported == true &&
                    capability.availableCountries.isNotEmpty()
                val countryOptions = capability?.availableCountries.orEmpty()
                val options = listOf("کشور در پروفایل / سرویس تعیین می‌شود") + countryOptions
                countryAdapter.clear()
                countryAdapter.addAll(options)
                countryAdapter.notifyDataSetChanged()
                countrySpinner.setSelection(0)
            }
        }
        page.addView(routingCard, sectionParams())

        val profileCard = card()
        profileCard.addView(label("پروفایل و تنظیمات اتصال", 17f, AQUA, true))
        profileImportButton = glassButton("＋  وارد کردن پروفایل WireGuard", false)
        profileImportButton.setOnClickListener {
            if (molidoApp.vpnSessions.snapshot().state != SessionState.IDLE) {
                Toast.makeText(this, "ابتدا نشست VPN را به‌طور کامل قطع کنید.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream", "application/x-wireguard-config"))
            }
            profilePicker.launch(intent)
        }
        profileCard.addView(profileImportButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
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
        recoveryCard.addView(label("مالکیت TUN یکتا • کلیدهای اعتماد در Android Keystore • بازیابی پروفایل فقط با اقدام دستی", 13f, MUTED, false))
        val restoreProfileButton = glassButton("بازیابی دستی پروفایل رمزگذاری‌شده", false).apply {
            isEnabled = true
            setOnClickListener { restoreSecureProfile() }
        }
        recoveryCard.addView(restoreProfileButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        recoveryButton = glassButton("پاک‌سازی امن نشست ناموفق", false).apply {
            visibility = View.GONE
            setOnClickListener { prepareRecovery() }
        }
        recoveryCard.addView(recoveryButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        val revokeProfileButton = glassButton("لغو رضایت و حذف پروفایل ذخیره‌شده", false).apply {
            setOnClickListener { revokeSecureProfileStorage() }
        }
        recoveryCard.addView(revokeProfileButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        page.addView(recoveryCard, sectionParams())

        val footer = label("خانه     •     سرورها     •     تنظیمات     •     پشتیبانی", 13f, MUTED, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(12))
        }
        page.addView(footer)
        setContentView(scroll)
    }

    private fun refreshWireGuardStatistics() {
        try {
            val stats = molidoApp.wireGuardAdapter.runtimeStatistics()
            if (stats == null) {
                providerStatusView.text = "WireGuard فعال نیست؛ ابتدا یک پروفایل معتبر وارد و اتصال را آغاز کنید."
                return
            }
            val handshake = if (stats.latestHandshakeEpochMillis > 0L) {
                val ageSeconds = ((System.currentTimeMillis() - stats.latestHandshakeEpochMillis).coerceAtLeast(0L)) / 1000L
                "آخرین Handshake: $ageSeconds ثانیه قبل"
            } else {
                "هنوز Handshake ثبت نشده است"
            }
            providerStatusView.text = "$handshake\nدریافت: ${stats.receivedBytes} بایت • ارسال: ${stats.transmittedBytes} بایت\nاین آمار به‌تنهایی دسترسی اینترنت یا سلامت کامل Data Plane را اثبات نمی‌کند."
        } catch (_: Exception) {
            providerStatusView.text = "خواندن آمار WireGuard ناموفق بود؛ وضعیت اتصال را موفق فرض نکنید."
        }
    }

    private fun dropdown(items: List<String>): Spinner {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        return Spinner(this).apply {
            this.adapter = adapter
            background = rounded(Color.rgb(18, 54, 67), STROKE, 14f)
            setPadding(dp(10), 0, dp(10), 0)
        }
    }

    private fun engineRow(name: String, state: String, available: Boolean, wireGuard: Boolean = false): View {
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(if (available) Color.rgb(18, 66, 69) else Color.rgb(13, 34, 44), if (available) AQUA else STROKE, 16f)
        }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(label(name, 14f, if (available) WHITE else MUTED, true))
        val stateLabel = label(state, 11f, if (available) AQUA else MUTED, false)
        if (wireGuard) wireGuardStateView = stateLabel
        titleColumn.addView(stateLabel)
        line.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(label(if (available) "●" else "○", 18f, if (available) AQUA else MUTED, true))
        val params = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        return line.apply { isEnabled = available; alpha = if (available) 1f else .78f }.also { it.layoutParams = params }
    }

    private fun smallPolicy(text: String, policy: NetworkPolicy): Button =
        glassButton(text, molidoAppPolicy() == policy).apply {
            textSize = 11f
            policyButtons[policy] = this
            setOnClickListener {
                if (molidoApp.setNetworkPolicy(policy)) {
                    renderState()
                } else {
                    Toast.makeText(this@MainActivity, "ذخیره سیاست شبکه ناموفق بود؛ تنظیم قبلی حفظ شد.", Toast.LENGTH_SHORT).show()
                    renderState()
                }
            }
        }

    private fun refreshPolicyButtonStyles(selected: NetworkPolicy) {
        policyButtons.forEach { (policy, button) ->
            val active = policy == selected
            button.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (active) intArrayOf(Color.rgb(86, 230, 208), Color.rgb(27, 169, 177), Color.rgb(20, 112, 129))
                else intArrayOf(Color.rgb(28, 75, 88), Color.rgb(12, 43, 57))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), if (active) AQUA else STROKE)
            }
            button.setTextColor(if (active) Color.rgb(3, 31, 39) else WHITE)
            button.isSelected = active
            button.contentDescription = button.text.toString() + ", " + (if (active) "انتخاب‌شده" else "انتخاب‌نشده")
        }
    }

    // The application exposes the active policy through this UI-owned selection.
    private fun molidoAppPolicy() = molidoApp.currentNetworkPolicy()

    private fun offerSecureProfileStorage() {
        if (molidoApp.secureProfileStore.hasUserConsent()) {
            AlertDialog.Builder(this)
                .setTitle("ذخیره امن پروفایل")
                .setMessage("رضایت قبلی شما فعال است. آیا نسخه رمزگذاری‌شده این پروفایل جایگزین شود؟")
                .setNegativeButton("فعلاً نه", null)
                .setPositiveButton("ذخیره رمزگذاری‌شده") { _, _ -> persistCurrentProfile() }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("رضایت برای ذخیره امن پروفایل")
            .setMessage("در صورت موافقت، پروفایل فقط روی همین دستگاه و با کلید غیرقابل‌استخراج Android Keystore رمزگذاری می‌شود. این کار اتصال خودکار را فعال نمی‌کند. آیا اجازه ذخیره می‌دهید؟")
            .setNegativeButton("خیر؛ فقط در حافظه") { _, _ ->
                Toast.makeText(this, "پروفایل فقط تا پایان عمر فرایند در حافظه می‌ماند.", Toast.LENGTH_LONG).show()
            }
            .setPositiveButton("موافقت و ذخیره") { _, _ ->
                try {
                    persistCurrentProfile(userConfirmed = true)
                } catch (_: Exception) {
                    Toast.makeText(this, "ذخیره رضایت یا پروفایل امن ناموفق بود.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun persistCurrentProfile(userConfirmed: Boolean = false) {
        if (molidoApp.vpnSessions.snapshot().state != SessionState.IDLE) {
            Toast.makeText(this, "ذخیره پروفایل فقط پس از توقف کامل نشست مجاز است.", Toast.LENGTH_LONG).show()
            return
        }
        val bytes = profileText?.toByteArray(StandardCharsets.UTF_8) ?: return
        try {
            if (userConfirmed) {
                molidoApp.secureProfileStore.saveProfileWithConsent(bytes, userConfirmed = true)
            } else {
                molidoApp.secureProfileStore.saveProfile(bytes)
            }
            Toast.makeText(this, "پروفایل با AES-GCM ذخیره شد؛ اتصال خودکار همچنان غیرفعال است.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "ذخیره امن پروفایل ناموفق بود.", Toast.LENGTH_LONG).show()
        } finally {
            bytes.fill(0)
        }
    }

    private fun revokeSecureProfileStorage() {
        if (molidoApp.vpnSessions.snapshot().state != SessionState.IDLE) {
            Toast.makeText(this, "برای حذف پروفایل ذخیره‌شده ابتدا VPN را کاملاً قطع کنید.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حذف پروفایل ذخیره‌شده")
            .setMessage("رضایت لغو می‌شود، داده رمزگذاری‌شده حذف و کلید اختصاصی این مخزن از Android Keystore پاک می‌شود.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("لغو رضایت و حذف") { _, _ ->
                try {
                    molidoApp.secureProfileStore.revokeConsentAndDelete()
                    profileText = null
                    profileView.text = "پروفایل ذخیره‌شده حذف شد"
                    connectButton.isEnabled = false
                    Toast.makeText(this, "رضایت لغو شد؛ داده رمزگذاری‌شده، کلید اختصاصی و نسخه فعال در حافظه حذف شدند.", Toast.LENGTH_LONG).show()
                    renderState()
                } catch (_: Exception) {
                    Toast.makeText(this, "حذف کامل انجام نشد؛ وضعیت ذخیره‌سازی را بررسی کنید.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun restoreSecureProfile() {
        if (molidoApp.vpnSessions.snapshot().state != SessionState.IDLE) {
            Toast.makeText(this, "بازیابی پروفایل فقط پس از توقف کامل نشست مجاز است.", Toast.LENGTH_LONG).show()
            return
        }
        if (!molidoApp.secureProfileStore.hasUserConsent()) {
            Toast.makeText(this, "برای بازیابی، رضایت ذخیره‌سازی امن لازم است.", Toast.LENGTH_LONG).show()
            return
        }
        var bytes: ByteArray? = null
        try {
            bytes = molidoApp.secureProfileStore.loadProfile()
                ?: error("No encrypted profile is available")
            require(bytes.isNotEmpty() && bytes.size <= MAX_PROFILE_BYTES) { "Stored profile size is invalid" }
            profileText = String(bytes, StandardCharsets.UTF_8)
            require(!profileText.isNullOrBlank()) { "Stored profile is empty" }
            profileView.text = "پروفایل رمزگذاری‌شده بازیابی شد • اتصال خودکار غیرفعال"
            statusView.text = "پروفایل در حافظه آماده است؛ اتصال فقط با فرمان دستی شما آغاز می‌شود."
            renderState()
        } catch (_: Exception) {
            profileText = null
            profileView.text = "بازیابی پروفایل ذخیره‌شده ناموفق بود"
            connectButton.isEnabled = false
            Toast.makeText(this, "کلید یا داده رمزگذاری‌شده در دسترس نیست؛ اتصال آغاز نشد.", Toast.LENGTH_LONG).show()
        } finally {
            bytes?.fill(0)
        }
    }

    private fun requestVpnConsentAndConnect() {
        if (profileText.isNullOrBlank()) {
            Toast.makeText(this, "ابتدا پروفایل WireGuard را وارد کنید.", Toast.LENGTH_SHORT).show()
            return
        }
        val decision = AdaptivePathPolicyCore.evaluate(molidoApp.latestNetworkAssessment(), molidoApp.currentNetworkPolicy())
        if (decision.decision != PathDecision.ALLOW) {
            Toast.makeText(this, decision.reason, Toast.LENGTH_LONG).show()
            renderState()
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
        if (!molidoApp.vpnSessions.resetRecoveryAfterExplicitUserAction()) {
            Toast.makeText(this, "بازنشانی ایمن وضعیت بازیابی ناموفق بود؛ اتصال آغاز نشد.", Toast.LENGTH_LONG).show()
            return
        }
        molidoApp.setRecoveryConfiguration(configuration)
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
        molidoApp.clearRecoveryConfiguration()
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
        val activePolicy = molidoApp.currentNetworkPolicy()
        val pathDecision = AdaptivePathPolicyCore.evaluate(network, activePolicy)
        statusView.text = when (snapshot.state) {
            SessionState.IDLE -> "●  قطع • آماده اتصال"
            SessionState.RESERVED -> "◌  در حال راه‌اندازی"
            SessionState.RUNNING -> "●  موتور فعال • هندشیک تأیید نشده"
            SessionState.STOPPING -> "◌  در حال پاک‌سازی نشست"
            SessionState.FAILED -> "!  خطا • پاک‌سازی لازم است"
        }
        wireGuardStateView.text = when (snapshot.state) {
            SessionState.IDLE -> "آداپتور موجود • آماده، اتصال تأیید نشده"
            SessionState.RESERVED -> "در حال ایجاد نشست و تخصیص TUN"
            SessionState.RUNNING -> "موتور فعال • هندشیک و ترافیک تأیید نشده"
            SessionState.STOPPING -> "در حال توقف • مالکیت TUN حفظ شده"
            SessionState.FAILED -> "خطا • مالکیت تا پاک‌سازی حفظ شده"
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
        connectButton.isEnabled = !controlsBusy && !profileText.isNullOrBlank() && snapshot.state == SessionState.IDLE &&
            pathDecision.decision == PathDecision.ALLOW
        disconnectButton.isEnabled = !controlsBusy && snapshot.state != SessionState.IDLE
        profileImportButton.isEnabled = !controlsBusy && snapshot.state == SessionState.IDLE
        policyButtons.values.forEach { it.isEnabled = !controlsBusy && snapshot.state == SessionState.IDLE }
        policyView.text = "سیاست شبکه: " + when (activePolicy) {
            NetworkPolicy.ANY_VALIDATED -> "هر شبکه معتبر"
            NetworkPolicy.UNMETERED_ONLY -> "فقط شبکه بدون هزینه"
            NetworkPolicy.WIFI_ONLY -> "فقط Wi‑Fi"
        }
        refreshPolicyButtonStyles(activePolicy)
        message?.let { statusView.text = it }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        controlsBusy = !enabled
        renderState()
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
