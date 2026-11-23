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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import ai.molido.vpn.core.UpdateCheckOutcome
import ai.molido.vpn.core.ConnectionMode
import ai.molido.vpn.core.CountryBucket
import ai.molido.vpn.core.CountryIndex
import ai.molido.vpn.core.WireGuardBundle
import android.net.Uri
import android.provider.OpenableColumns
import ai.molido.vpn.engine.singbox.ShareLinkParser
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
    private lateinit var plasmaCoreView: PlasmaCoreView
    private lateinit var statusChip: TextView
    private lateinit var netStatusValue: TextView
    private lateinit var netTransportValue: TextView
    private lateinit var netMeteredValue: TextView
    private lateinit var netBandwidthValue: TextView
    private lateinit var policySpinner: Spinner
    private lateinit var countrySpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private var countryBuckets: List<CountryBucket> = emptyList()
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
                profileText = if (files.size == 1) files[0].second
                else WireGuardBundle.join(files.filter { it.second.contains("[Interface]", ignoreCase = true) })
                require(!profileText.isNullOrBlank()) { "Empty profile" }
                val count = WireGuardBundle.split(profileText.orEmpty()).size
                profileView.text = if (count > 1) "$count پروفایل WireGuard بارگذاری شد • فقط در حافظه"
                else "پروفایل WireGuard بارگذاری شد • فقط در حافظه"
                renderState()
                statusView.text = "پروفایل آماده است؛ برای شروع، مجوز VPN اندروید لازم است."
                offerSecureProfileStorage()
            } catch (_: Exception) {
                profileText = null
                profileView.text = "هنوز پروفایلی انتخاب نشده"
                renderState()
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
        window.statusBarColor = BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.navigationBarColor = BG_END
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
        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG, BG_MID, BG_END))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // Top bar: brand + live status chip
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("MolidoVPN", 22f, WHITE, true))
        brand.addView(label("اتصال خصوصی، بدون ردیابی", 12f, MUTED, false))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        statusChip = pill("●  قطع", MUTED)
        header.addView(statusChip)
        page.addView(header)

        // Hero: orb, status, one primary action
        val hero = card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(22))
        }
        plasmaCoreView = PlasmaCoreView(this).apply { contentDescription = "نمایش وضعیت اتصال" }
        hero.addView(plasmaCoreView, LinearLayout.LayoutParams(dp(210), dp(210)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        statusView = label("در حال بررسی وضعیت…", 18f, WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        }
        hero.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        profileView = label("هنوز پروفایلی انتخاب نشده", 12f, MUTED, false).apply { gravity = Gravity.CENTER }
        hero.addView(profileView, LinearLayout.LayoutParams(-1, -2))
        connectButton = glassButton("اتصال امن", true).apply {
            textSize = 16f
            setOnClickListener { requestVpnConsentAndConnect() }
        }
        hero.addView(connectButton, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(20) })
        disconnectButton = glassButton("قطع اتصال", false).apply {
            textSize = 16f
            visibility = View.GONE
            setOnClickListener { disconnectTunnel() }
        }
        hero.addView(disconnectButton, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(20) })
        page.addView(hero, sectionParams())

        // Network: 2x2 stat grid
        page.addView(sectionTitle("وضعیت شبکه"), sectionTitleParams())
        val gridTop = row()
        val gridBottom = row()
        netStatusValue = statTile(gridTop, "اینترنت", first = true)
        netTransportValue = statTile(gridTop, "نوع اتصال", first = false)
        netMeteredValue = statTile(gridBottom, "هزینه داده", first = true)
        netBandwidthValue = statTile(gridBottom, "پهنای باند سیستم", first = false)
        page.addView(gridTop, LinearLayout.LayoutParams(-1, -2))
        page.addView(gridBottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        networkView = label("", 12f, MUTED, false).apply { visibility = View.GONE }
        page.addView(networkView)
        page.addView(label("IP عمومی، کشور سرور، پینگ و سرعت تا اتصال به منبع معتبر نمایش داده نمیشوند.", 11f, MUTED, false).apply {
            setPadding(dp(4), dp(10), dp(4), 0)
        })

        // Engine
        page.addView(sectionTitle("موتور اتصال"), sectionTitleParams())
        val engineCard = card()
        engineCard.addView(engineRow("WireGuard", "آماده", true, wireGuard = true))
        engineCard.addView(label("بهزودی: AmneziaWG • V2Ray/Xray • Psiphon • Tor • MASQUE/WARP", 12f, MUTED, false).apply {
            setPadding(dp(4), dp(12), dp(4), 0)
        })
        page.addView(engineCard, LinearLayout.LayoutParams(-1, -2))

        // Profile + network policy segmented control
        page.addView(sectionTitle("پروفایل و سیاست شبکه"), sectionTitleParams())
        val profileCard = card()
        profileImportButton = glassButton("＋  وارد کردن پروفایل WireGuard", false)
        profileImportButton.setOnClickListener {
            if (molidoApp.vpnSessions.snapshot().state != SessionState.IDLE) {
                Toast.makeText(this, "ابتدا نشست VPN را بهطور کامل قطع کنید.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream", "application/x-wireguard-config"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            profilePicker.launch(intent)
        }
        profileCard.addView(profileImportButton, LinearLayout.LayoutParams(-1, dp(50)))
        policyView = label("سیاست شبکه: هر شبکه معتبر", 13f, WHITE, true).apply { setPadding(dp(2), dp(16), dp(2), dp(10)) }
        profileCard.addView(policyView)
        // Network mode dropdown
        val policyOrder = listOf(NetworkPolicy.ANY_VALIDATED, NetworkPolicy.UNMETERED_ONLY, NetworkPolicy.WIFI_ONLY)
        policySpinner = dropdown(
            listOf("همه شبکهها", "فقط شبکه بدون هزینه", "فقط Wi‑Fi"),
            policyOrder.indexOf(molidoAppPolicy()).coerceAtLeast(0)
        ) { index ->
            val policy = policyOrder[index]
            if (policy != molidoAppPolicy() && !molidoApp.setNetworkPolicy(policy)) {
                Toast.makeText(this, "ذخیره سیاست شبکه ناموفق بود؛ تنظیم قبلی حفظ شد.", Toast.LENGTH_SHORT).show()
            }
            renderState()
        }
        profileCard.addView(policySpinner, LinearLayout.LayoutParams(-1, dp(48)))

        val prefs = getSharedPreferences("molido_ui", MODE_PRIVATE)

        // Connection mode dropdown
        profileCard.addView(label("حالت اتصال", 13f, WHITE, true).apply { setPadding(dp(2), dp(16), dp(2), dp(10)) })
        val modes = ConnectionMode.entries
        val savedMode = ConnectionMode.fromId(prefs.getString("connection_mode", null))
        modeSpinner = dropdown(modes.map { it.label }, modes.indexOf(savedMode)) { index ->
            val mode = modes[index]
            prefs.edit().putString("connection_mode", mode.id).apply()
            if (!mode.available) {
                Toast.makeText(this, "«${mode.title}» هنوز در این نسخه فعال نیست؛ اتصال با حالت خودکار انجام میشود.", Toast.LENGTH_SHORT).show()
            }
        }
        profileCard.addView(modeSpinner, LinearLayout.LayoutParams(-1, dp(48)))

        // Exit location dropdown with config count per country
        profileCard.addView(label("مکان خروجی", 13f, WHITE, true).apply { setPadding(dp(2), dp(16), dp(2), dp(10)) })
        countrySpinner = dropdown(listOf("🌐 خودکار"), 0) { index ->
            val bucket = countryBuckets.getOrNull(index - 1)
            if (bucket != null) prefs.edit().putString("country_code", bucket.code).apply()
            else prefs.edit().remove("country_code").apply()
        }
        profileCard.addView(countrySpinner, LinearLayout.LayoutParams(-1, dp(48)))
        refreshCountries()
        profileCard.addView(label("با پروفایل WireGuard، کشور را خود پروفایل تعیین میکند. این انتخاب برای موتورهای دارای فهرست سرور اعمال میشود.", 11f, MUTED, false).apply {
            setPadding(dp(2), dp(8), dp(2), 0)
        })
        page.addView(profileCard, LinearLayout.LayoutParams(-1, -2))

        // Security & recovery
        page.addView(sectionTitle("امنیت و بازیابی"), sectionTitleParams())
        val recoveryCard = card()
        recoveryCard.addView(label("کلیدها در Android Keystore • مالکیت یکتای TUN • بازیابی فقط با اقدام دستی شما", 12f, MUTED, false))
        val restoreProfileButton = glassButton("بازیابی پروفایل رمزگذاریشده", false).apply {
            setOnClickListener { restoreSecureProfile() }
        }
        recoveryCard.addView(restoreProfileButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
        val updateButton = glassButton("بررسی و نصب نسخه جدید", false).apply {
            setOnClickListener { checkForUpdate() }
        }
        recoveryCard.addView(updateButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        recoveryButton = glassButton("پاکسازی امن نشست ناموفق", false).apply {
            setTextColor(AMBER)
            visibility = View.GONE
            setOnClickListener { prepareRecovery() }
        }
        recoveryCard.addView(recoveryButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val revokeProfileButton = glassButton("حذف پروفایل ذخیرهشده", false).apply {
            setTextColor(DANGER)
            setOnClickListener { revokeSecureProfileStorage() }
        }
        recoveryCard.addView(revokeProfileButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        page.addView(recoveryCard, LinearLayout.LayoutParams(-1, -2))

        val appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        page.addView(label("MolidoVPN $appVersion • همه دادهها فقط روی همین دستگاه", 11f, MUTED, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun checkForUpdate() {
        if (molidoApp.vpnSessions.snapshot().state != SessionState.IDLE) {
            Toast.makeText(this, "برای نصب آپدیت ابتدا VPN را کاملاً قطع کنید.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "در حال بررسی نسخه جدید…", Toast.LENGTH_SHORT).show()
        molidoApp.vpnExecutor.execute {
            val outcome = molidoApp.updateChecker.checkAndInstall()
            runOnUiThread {
                val message = when (outcome) {
                    UpdateCheckOutcome.NOT_CONFIGURED -> "آپدیت خودکار در این نسخه فعال نیست."
                    UpdateCheckOutcome.UP_TO_DATE -> "شما آخرین نسخه را دارید."
                    UpdateCheckOutcome.INSTALL_STARTED -> "نسخه جدید تأیید شد؛ نصب را تأیید کنید."
                    UpdateCheckOutcome.NO_MANIFEST -> "نسخه امضاشدهای برای آپدیت پیدا نشد."
                    UpdateCheckOutcome.REJECTED -> "آپدیت رد شد: امضا یا فایل معتبر نبود."
                    UpdateCheckOutcome.NETWORK_ERROR -> "اتصال به سرور آپدیت ناموفق بود."
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sectionTitle(value: String) = label(value, 15f, WHITE, true).apply { setPadding(dp(4), 0, dp(4), 0) }
    private fun sectionTitleParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(10) }

    private fun statTile(parent: LinearLayout, caption: String, first: Boolean): TextView {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(SURFACE, STROKE, 18f)
        }
        tile.addView(label(caption, 11f, MUTED, false))
        val value = label("نامشخص", 15f, WHITE, true).apply { setPadding(0, dp(6), 0, 0) }
        tile.addView(value)
        parent.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!first) marginStart = dp(10) })
        return value
    }

    private fun engineRow(name: String, state: String, available: Boolean, wireGuard: Boolean = false): View {
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(if (available) 0xFFEEF2FF.toInt() else SURFACE, if (available) AQUA else STROKE, 16f)
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

    private var countedProfileHash: Int? = null

    /** Rebuilds the exit-location list from the loaded profile; skips work when the profile is unchanged. */
    private fun refreshCountries() {
        if (!::countrySpinner.isInitialized) return
        val text = profileText.orEmpty()
        val hash = text.hashCode()
        if (hash == countedProfileHash) return
        countedProfileHash = hash
        val names = when {
            text.isBlank() -> emptyList()
            text.contains("://") || !text.contains("[Interface]") ->
                ShareLinkParser.parseSubscription(text).map { it.name }
            // WireGuard: one entry per [Interface]; country comes from its leading comment or file name.
            else -> WireGuardBundle.split(text).map { it.name }
        }
        countryBuckets = CountryIndex.group(names)
        @Suppress("UNCHECKED_CAST")
        val adapter = countrySpinner.adapter as ArrayAdapter<String>
        adapter.clear()
        adapter.add("🌐 خودکار (${names.size} کانفیگ)")
        adapter.addAll(countryBuckets.map { it.label })
        adapter.notifyDataSetChanged()
        val savedCode = getSharedPreferences("molido_ui", MODE_PRIVATE).getString("country_code", null)
        val index = countryBuckets.indexOfFirst { it.code == savedCode }
        countrySpinner.setSelection(if (index >= 0) index + 1 else 0, false)
    }

    private fun dropdown(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            private fun style(v: View): View = v.apply {
                (this as? TextView)?.apply {
                    setTextColor(WHITE)
                    textSize = 14f
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    textDirection = View.TEXT_DIRECTION_RTL
                }
            }
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                style(super.getView(position, convertView, parent))
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                style(super.getDropDownView(position, convertView, parent)).apply { setBackgroundColor(SURFACE_2) }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        return Spinner(this, Spinner.MODE_DROPDOWN).apply {
            this.adapter = adapter
            background = rounded(SURFACE_2, STROKE, 14f)
            setPopupBackgroundDrawable(rounded(SURFACE_2, STROKE, 14f))
            setSelection(selected, false)
            var current = selected
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (position != current) { current = position; onSelect(position) }
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
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
                if (active) intArrayOf(AQUA, AQUA)
                else intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT)
            ).apply {
                cornerRadius = dp(12).toFloat()
            }
            button.elevation = 0f
            button.setTextColor(if (active) Color.WHITE else WHITE)
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
                    renderState()
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
            renderState()
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
        val loaded = profileText ?: return
        val countryCode = getSharedPreferences("molido_ui", MODE_PRIVATE).getString("country_code", null)
        val candidates = WireGuardBundle.candidates(loaded, countryCode).take(MAX_FAILOVER_ATTEMPTS)
        setControlsEnabled(false)
        molidoApp.vpnExecutor.execute {
            val sessions = molidoApp.vpnSessions
            for ((index, configuration) in candidates.withIndex()) {
                val isLast = index == candidates.lastIndex
                if (index > 0) runOnUiThread { statusView.text = "تلاش با کانفیگ ${index + 1} از ${candidates.size}…" }
                val started: Boolean? = try {
                    sessions.connect(EngineSlot.WIREGUARD, configuration)
                } catch (_: Throwable) {
                    null
                }
                if (started == true) {
                    val probe = sessions.activeHandshakeProbe()
                    if (probe != null) runOnUiThread { statusView.text = "در انتظار handshake سرور (${index + 1} از ${candidates.size})…" }
                    val answered = probe?.awaitHandshake(HANDSHAKE_TIMEOUT_MS) ?: true
                    if (answered || isLast) {
                        runOnUiThread {
                            setControlsEnabled(true)
                            renderState()
                            if (!answered) statusView.text = "تونل بالا است ولی هیچ سروری handshake را پاسخ نداد."
                        }
                        return@execute
                    }
                    // Tunnel is up but the peer never answered: tear it down and try the next config.
                }
                // false + IDLE = blocked by network policy: another config would be blocked too.
                val blocked = started == false && sessions.snapshot().state == SessionState.IDLE
                if (!blocked && !isLast) {
                    // Release any TUN ownership the failed attempt kept before trying the next config.
                    val cleaned = try {
                        sessions.snapshot().state == SessionState.IDLE || sessions.disconnect()
                    } catch (_: Throwable) { false }
                    if (cleaned) continue
                }
                if (started == false) runOnUiThread {
                    setControlsEnabled(true)
                    statusView.text = sessions.latestPathDecision()?.reason ?:"شروع نشست ممکن نشد."
                    renderState()
                } else runOnUiThread { setControlsEnabled(true); renderState("شروع WireGuard ناموفق بود؛ وضعیت پاک‌سازی را بررسی کنید.") }
                return@execute
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
        refreshCountries()
        if (::modeSpinner.isInitialized) modeSpinner.isEnabled = !controlsBusy && snapshot.state == SessionState.IDLE
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
        // Stay tappable while idle: requestVpnConsentAndConnect() explains a missing profile or a blocked network.
        connectButton.isEnabled = !controlsBusy && snapshot.state == SessionState.IDLE
        connectButton.alpha = if (!profileText.isNullOrBlank() && pathDecision.decision == PathDecision.ALLOW) 1f else 0.6f
        disconnectButton.isEnabled = !controlsBusy && snapshot.state != SessionState.IDLE
        profileImportButton.isEnabled = !controlsBusy && snapshot.state == SessionState.IDLE
        policyButtons.values.forEach { it.isEnabled = !controlsBusy && snapshot.state == SessionState.IDLE }
        if (::policySpinner.isInitialized) policySpinner.isEnabled = !controlsBusy && snapshot.state == SessionState.IDLE
        if (::countrySpinner.isInitialized) countrySpinner.isEnabled = !controlsBusy && snapshot.state == SessionState.IDLE
        policyView.text = "سیاست شبکه: " + when (activePolicy) {
            NetworkPolicy.ANY_VALIDATED -> "هر شبکه معتبر"
            NetworkPolicy.UNMETERED_ONLY -> "فقط شبکه بدون هزینه"
            NetworkPolicy.WIFI_ONLY -> "فقط Wi‑Fi"
        }
        refreshPolicyButtonStyles(activePolicy)
        connectButton.visibility = if (snapshot.state == SessionState.IDLE) View.VISIBLE else View.GONE
        if (::netStatusValue.isInitialized) {
            netStatusValue.text = when (network.status) {
                NetworkAssessmentStatus.VALIDATED -> "تأیید شده"
                NetworkAssessmentStatus.NOT_VALIDATED -> "تأیید نشده"
                NetworkAssessmentStatus.STALE -> "قدیمی"
                NetworkAssessmentStatus.UNKNOWN -> "نامشخص"
            }
            netStatusValue.setTextColor(if (network.status == NetworkAssessmentStatus.VALIDATED) EMERALD else AMBER)
            netTransportValue.text = network.transport?.name ?: "نامشخص"
            netMeteredValue.text = when (network.metered) { true -> "دارای هزینه"; false -> "بدون هزینه"; null -> "نامشخص" }
            netBandwidthValue.text = network.downstreamKbps?.let { "↓ ${it / 1000} Mbps" } ?: "نامشخص"
        }
        if (::statusChip.isInitialized) {
            val (chipText, chipColor) = when (snapshot.state) {
                SessionState.IDLE -> "●  قطع" to MUTED
                SessionState.RESERVED -> "◌  اتصال…" to AQUA
                SessionState.RUNNING -> "●  فعال" to EMERALD
                SessionState.STOPPING -> "◌  توقف…" to AMBER
                SessionState.FAILED -> "!  خطا" to DANGER
            }
            statusChip.text = chipText
            statusChip.setTextColor(chipColor)
            statusChip.background = rounded(SURFACE, chipColor, 30f)
        }
        if (::plasmaCoreView.isInitialized) {
            plasmaCoreView.setSessionState(snapshot.state)
        }
        message?.let { statusView.text = it }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        controlsBusy = !enabled
        renderState()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(SURFACE, STROKE, 24f)
        elevation = 0f
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    private fun pill(value: String, color: Int) = TextView(this).apply {
        text = value; textSize = 11f; setTextColor(color); gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8)); background = rounded(SURFACE_2, STROKE, 30f)
    }
    private fun glassButton(value: String, primary: Boolean) = Button(this).apply {
        text = value; textSize = 14f; isAllCaps = false; setTextColor(if (primary) Color.WHITE else WHITE)
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (primary) intArrayOf(PINK, AQUA, CYAN)
            else intArrayOf(SURFACE_2, SURFACE)
        ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), STROKE) }
        elevation = if (primary) dp(6).toFloat() else 0f
        stateListAnimator = null
    }
    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); setStroke(dp(1), stroke)
    }
    private fun sectionParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        // Must match AndroidSecureProfileStore.MAX_PROFILE_BYTES so a loaded bundle can be saved.
        const val MAX_PROFILE_BYTES = 64 * 1024
        const val MAX_FAILOVER_ATTEMPTS = 5
        const val HANDSHAKE_TIMEOUT_MS = 8_000L
        // Liquid Glass palette (light). WHITE = primary text colour, name kept for minimal churn.
        const val AQUA = 0xFF6366F1.toInt()
        const val PINK = 0xFFF472B6.toInt()
        const val CYAN = 0xFF22D3EE.toInt()
        const val STROKE = 0xFFFFFFFF.toInt()
        const val GOLD = 0xFFF59E0B.toInt()
        const val WHITE = 0xFF1E1B4B.toInt()
        const val MUTED = 0xFF6B7280.toInt()
        const val BG = 0xFFFDF2F8.toInt()
        const val BG_MID = 0xFFE0E7FF.toInt()
        const val BG_END = 0xFFCFFAFE.toInt()
        const val SURFACE = 0x99FFFFFF.toInt()
        const val SURFACE_2 = 0xCCFFFFFF.toInt()
        const val EMERALD = 0xFF059669.toInt()
        const val AMBER = 0xFFD97706.toInt()
        const val DANGER = 0xFFDC2626.toInt()
    }
}
