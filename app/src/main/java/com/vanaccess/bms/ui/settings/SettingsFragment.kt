// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.settings

import com.vanaccess.bms.ui.AppDialog as AD
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.vanaccess.bms.BuildConfig
import com.vanaccess.bms.R
import com.vanaccess.bms.ble.BleService
import com.vanaccess.bms.ble.ScannedDevice
import com.vanaccess.bms.ble.drivers.BmsDrivers
import com.vanaccess.bms.update.UpdateChecker
import com.vanaccess.bms.update.UpdateDownload
import com.vanaccess.bms.update.UpdatePhase
import com.vanaccess.bms.update.UpdateService
import com.vanaccess.bms.update.Updater
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.data.Share
import com.vanaccess.bms.databinding.FragmentSettingsBinding
import com.vanaccess.bms.ui.MainActivity
import org.json.JSONObject

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: AppPreferences
    private lateinit var deviceAdapter: DeviceListAdapter

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeExport(uri) }

    /** Which alarm kind the system tone picker is choosing for. */
    private var pickingTone: com.vanaccess.bms.alarm.BatteryAlarmMonitor.Kind? = null

    private val toneLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val kind = pickingTone ?: return@registerForActivityResult
        pickingTone = null
        if (res.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        @Suppress("DEPRECATION")
        val uri = res.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        prefs.setAlarmTone(com.vanaccess.bms.alarm.BatteryAlarmMonitor.Tones.toneKey(kind), uri?.toString())
        renderTones()
    }

    private val fullBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) runBackup { com.vanaccess.bms.data.Backup.export(requireContext().applicationContext, uri) } }

    private val fullRestoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        AD.show(requireContext(), getString(R.string.backup_restore_q), message = getString(R.string.backup_restore_msg),
            buttons = listOf(
                AD.Btn(getString(R.string.btn_cancel), AD.GREEN),
                AD.Btn(getString(R.string.backup_full_import), AD.RED) {
                    val app = requireContext().applicationContext
                    runBackup(restartAfter = true) { com.vanaccess.bms.data.Backup.import(app, uri) }
                },
            ))
    }

    /** Run a backup/restore off the main thread and report the result. */
    private fun runBackup(restartAfter: Boolean = false, work: () -> Unit) {
        val app = requireContext().applicationContext
        kotlin.concurrent.thread {
            val err = try { work(); null } catch (e: Exception) { e.message ?: e.toString() }
            activity?.runOnUiThread {
                if (err != null) {
                    com.vanaccess.bms.ui.AppDialog.info(requireContext(), getString(R.string.backup_failed), err)
                } else if (restartAfter) {
                    Toast.makeText(app, R.string.backup_restored, Toast.LENGTH_LONG).show()
                    view?.postDelayed({ com.vanaccess.bms.data.Backup.restart(app) }, 1200)
                } else Toast.makeText(app, R.string.backup_done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) readImport(uri) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        setupDeviceList()
        setupDeviceRows()
        setupButtons()
        setupKeysSection()
        setupImportExport()
        setupDebugButton()
        setupProtocolSelector()
        setupAlarms()
        setupUpdates()
        setupShare()
        setupVehicle()
        setupSections()
        setupInfoIcons()
        observeData()

        binding.tvAboutVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        fun open(url: String) = runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
        }
        binding.btnAboutSite.setOnClickListener { open(com.vanaccess.bms.data.Share.SITE) }
        binding.btnAboutGnutux.setOnClickListener { open("https://salehgnutux.github.io/gnutux/") }
        binding.btnAboutSpeedometer.setOnClickListener { open("https://salehgnutux.github.io/GT-SPEEDOMETER/") }
    }

    // ── Device scan list ────────────────────────────────────────────────────

    private fun setupDeviceList() {
        deviceAdapter = DeviceListAdapter { device ->
            showRoleDialog(device)
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = deviceAdapter
        }
    }

    // ── BMS protocol selector ───────────────────────────────────────────────

    private fun setupProtocolSelector() {
        refreshProtocolButton()
        binding.btnBmsProtocol.setOnClickListener {
            val entries = BmsDrivers.selectorEntries(getString(R.string.protocol_auto))
            val current = entries.indexOfFirst { it.first == prefs.bmsProtocol }.coerceAtLeast(0)
            com.vanaccess.bms.ui.AppDialog.show(requireContext(), getString(R.string.protocol_choose),
                items = entries.map { it.second }, checked = current, onItem = { which ->
                    val id = entries[which].first
                    if (id != prefs.bmsProtocol) {
                        prefs.bmsProtocol = id
                        prefs.batteryType = if (id == BmsDrivers.AUTO) null else id
                        prefs.batteryTypeAddress = null
                        refreshProtocolButton()
                        refreshDeviceRows()
                        val addr = prefs.selectedDeviceAddress
                        val service = (activity as? MainActivity)?.getBleService()
                        if (!addr.isNullOrBlank() && service != null) service.connectToDevice(addr)
                    }
                },
                buttons = listOf(com.vanaccess.bms.ui.AppDialog.Btn(getString(R.string.btn_cancel), com.vanaccess.bms.ui.AppDialog.GREY)))
        }
    }

    private fun refreshProtocolButton() {
        if (_binding == null) return
        val sel = prefs.bmsProtocol
        val label = when (sel) {
            BmsDrivers.AUTO -> {
                val detected = BleService.activeDriverName.value
                if (detected.isNullOrBlank()) getString(R.string.protocol_auto)
                else getString(R.string.protocol_auto) + " • " + detected
            }
            "123bms" -> "123SmartBMS"
            else -> BmsDrivers.byId(sel)?.displayName ?: sel
        }
        binding.btnBmsProtocol.text = getString(R.string.protocol_value, label)
    }

    // ── Section tiles ───────────────────────────────────────────────────────

    private val SECTION_COLORS = mapOf("devices" to "#2ECC71", "vehicle" to "#2196F3", "alarms" to "#F5A623", "charger" to "#FF7043", "prefs" to "#9C8CFF", "updates" to "#26C6DA", "share" to "#EC407A", "backup" to "#4FC3F7", "about" to "#90A4AE")

    private data class Section(val key: String, val icon: Int, val title: Int, val sub: Int, val cards: List<View>)

    private var openSection: String? = null
    private lateinit var sections: List<Section>

    /** Settings start as a grid of tiles; a tile opens one section with a back arrow. */
    private fun setupSections() {
        sections = listOf(
            Section("devices", R.drawable.ic_sec_devices, R.string.sec_devices, R.string.sec_devices_sub, listOf(binding.cardDevices, binding.cardScan)),
            Section("vehicle", R.drawable.ic_sec_vehicle, R.string.sec_vehicle, R.string.sec_vehicle_sub, listOf(binding.cardVehicle)),
            Section("alarms", R.drawable.ic_sec_alarms, R.string.sec_alarms, R.string.sec_alarms_sub, listOf(binding.cardAlarms)),
            Section("charger", R.drawable.ic_sec_charger, R.string.charger_section, R.string.charger_section_sub, listOf(binding.cardCharger)),
            Section("prefs", R.drawable.ic_sec_prefs, R.string.sec_prefs, R.string.sec_prefs_sub, listOf(binding.cardPrefs)),
            // F-Droid builds update through F-Droid: no Updates section there
            *(if (com.vanaccess.bms.BuildConfig.SELF_UPDATE)
                arrayOf(Section("updates", R.drawable.ic_sec_updates, R.string.sec_updates, R.string.sec_updates_sub, listOf(binding.cardUpdates)))
              else emptyArray()),
            Section("share", R.drawable.ic_sec_share, R.string.sec_share, R.string.sec_share_sub, listOf(binding.cardShare)),
            Section("backup", R.drawable.ic_sec_backup, R.string.sec_backup, R.string.sec_backup_sub, listOf(binding.cardBackup)),
            Section("about", R.drawable.ic_sec_about, R.string.sec_about, R.string.sec_about_sub, listOf(binding.cardAbout, binding.cardDev)),
        )
        if (!com.vanaccess.bms.BuildConfig.SELF_UPDATE) binding.cardUpdates.visibility = View.GONE
        val dp = resources.displayMetrics.density
        binding.gridSections.removeAllViews()
        sections.forEach { sec ->
            val tile = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                setCardBackgroundColor(Color.parseColor("#1E2130"))
                radius = 18 * dp
                cardElevation = 0f
                strokeWidth = 0
                isClickable = true
                isFocusable = true
                setOnClickListener { showSection(sec.key) }
            }
            val col = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
                minimumHeight = (104 * dp).toInt()
            }
            // Icon in a soft tile of its own colour (one colour per section)
            val tint = Color.parseColor(SECTION_COLORS[sec.key] ?: "#4DA3FF")
            col.addView(android.widget.ImageView(requireContext()).apply {
                setImageResource(sec.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(tint)
                val pad = (9 * dp).toInt(); setPadding(pad, pad, pad, pad)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 13 * dp
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 0x2E))
                    setStroke((1 * dp).toInt(), androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 0x55))
                }
            }, android.widget.LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { bottomMargin = (4 * dp).toInt() })
            col.addView(android.widget.TextView(requireContext()).apply {
                text = getString(sec.title); textSize = 15f; setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, (6 * dp).toInt(), 0, 0)
            })
            col.addView(android.widget.TextView(requireContext()).apply {
                text = getString(sec.sub); textSize = 12f; setTextColor(Color.parseColor("#9BA3B8"))
            })
            tile.addView(col)
            binding.gridSections.addView(tile, android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; val m = (5 * dp).toInt(); setMargins(m, m, m, m) })
        }
        binding.btnSectionBack.setOnClickListener { showSection(null) }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (openSection != null) showSection(null) else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            })
        val main = activity as? MainActivity
        showSection(if (main?.scrollToUpdates == true) "updates".also { main.scrollToUpdates = false } else openSection)
    }

    private fun showSection(key: String?) {
        if (_binding == null) return
        if (key == "charger") renderCharger()
        openSection = key
        val sec = sections.firstOrNull { it.key == key }
        binding.gridSections.visibility = if (sec == null) View.VISIBLE else View.GONE
        binding.btnSectionBack.visibility = if (sec == null) View.GONE else View.VISIBLE
        binding.tvSettingsTitle.text = getString(sec?.title ?: R.string.lbl_settings)
        sections.flatMap { it.cards }.forEach { it.visibility = View.GONE }
        sec?.cards?.forEach { it.visibility = View.VISIBLE }
        binding.settingsScroll.scrollTo(0, 0)
    }

    /** ⓘ on fields whose meaning isn't obvious: tap for an explanation. */
    private fun setupInfoIcons() {
        listOf(
            binding.tilConsumption to (R.string.info_consumption_title to R.string.info_consumption),
            binding.tilRatedCycles to (R.string.info_cycles_title to R.string.info_cycles),
            binding.tilDeltaRest to (R.string.info_delta_rest_title to R.string.info_delta_rest),
            binding.tilDeltaLoad to (R.string.info_delta_load_title to R.string.info_delta_load),
            binding.tilAlarmCell to (R.string.alarm_cell_label to R.string.info_alarm_cell),
            binding.tilPriceKwh to (R.string.cost_price_hint to R.string.info_price_kwh),
            binding.tilCurrency to (R.string.cost_currency_hint to R.string.info_currency),
            binding.tilFuelPrice to (R.string.cost_fuel_price_title to R.string.info_fuel_price),
            binding.tilFuelL100 to (R.string.cost_fuel_l100_title to R.string.info_fuel_l100),
        ).forEach { (til, txt) ->
            til.setEndIconOnClickListener {
                com.vanaccess.bms.ui.AppDialog.info(requireContext(), getString(txt.first), getString(txt.second))
            }
        }
    }

    // ── Charger & charging ──────────────────────────────────────────────────

    /** Detected charger (from the last charges' curves) and charging tips. */
    private fun renderCharger() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun line(t: String, color: Int, bold: Boolean = false) = android.widget.TextView(ctx).apply {
            text = t; textSize = 13f; setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
        }
        binding.boxTips.removeAllViews()
        resources.getStringArray(R.array.charging_tips).forEach { binding.boxTips.addView(line("• $it", Color.parseColor("#C9CFDD"))) }
        binding.boxCharger.removeAllViews()
        kotlin.concurrent.thread {
            val store = com.vanaccess.bms.reports.ReportStore.get(ctx)
            val charges = store.list(com.vanaccess.bms.reports.Session.TYPE_CHARGE, 5)
            val results = charges.mapNotNull { com.vanaccess.bms.ui.AdviceText.chargerFor(ctx, it) }
            val profile = com.vanaccess.bms.reports.ChargerAnalyzer.profile(results)
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (results.isEmpty()) {
                    binding.boxCharger.addView(line(getString(R.string.charger_none), Color.parseColor("#9BA3B8")))
                    return@runOnUiThread
                }
                binding.boxCharger.addView(line(getString(R.string.charger_detected, results.size), Color.parseColor("#9BA3B8")))
                // Verdicts of the most recent charge, with the typical current/voltage across charges
                val latest = results.first()
                val recs = latest.recs.map { r ->
                    if ((r.code == "charger_seen" || r.code == "charger_seen_nocv") && profile != null)
                        r.copy(args = listOf(profile.first, profile.second ?: latest.maxVoltage)) else r
                }
                recs.forEach { r ->
                    binding.boxCharger.addView(line(com.vanaccess.bms.ui.AdviceText.icon(r.level) + " " +
                        com.vanaccess.bms.ui.AdviceText.text(ctx, r), com.vanaccess.bms.ui.AdviceText.color(r.level),
                        r.level == com.vanaccess.bms.reports.Advisor.Level.CRITICAL))
                }
            }
        }
    }

    // ── Vehicle & trips ─────────────────────────────────────────────────────

    private fun setupVehicle() {
        val group = binding.radioVehicle
        group.removeAllViews()
        com.vanaccess.bms.data.VehicleProfile.all.forEachIndexed { i, v ->
            group.addView(android.widget.RadioButton(requireContext()).apply {
                id = 1000 + i
                text = "${v.icon}  ${getString(v.labelRes)}"
                setTextColor(Color.WHITE)
                isChecked = v.id == prefs.vehicleType
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val v = com.vanaccess.bms.data.VehicleProfile.all.getOrNull(checkedId - 1000) ?: return@setOnCheckedChangeListener
            prefs.vehicleType = v.id
            refreshVehicleBox()
            if (v.mobile) (activity as? MainActivity)?.ensureLocationPermission()
        }
        binding.switchTrips.isChecked = prefs.tripsEnabled
        binding.switchTrips.setOnCheckedChangeListener { _, c ->
            prefs.tripsEnabled = c
            if (c) (activity as? MainActivity)?.ensureLocationPermission()
        }
        binding.btnBackground.setOnClickListener { com.vanaccess.bms.ui.BackgroundAccess.ask(requireActivity()) }
        binding.etConsumption.setText(if (prefs.consumptionWhKm > 0) fmtV(prefs.consumptionWhKm) else "0")
        binding.etConsumption.setOnFocusChangeListener { _, f -> if (!f) saveConsumption() }
        binding.etRatedCycles.setText(prefs.ratedCycles.takeIf { it > 0 }?.toString() ?: "")
        binding.etPriceKwh.setText(prefs.pricePerKwh.takeIf { it > 0 }?.toString() ?: "")
        binding.etCurrency.setText(prefs.currency)
        binding.etFuelPrice.setText(prefs.fuelPrice.takeIf { it > 0 }?.toString() ?: "")
        binding.etFuelL100.setText(prefs.fuelL100.takeIf { it > 0 }?.toString() ?: "")
        listOf(binding.etPriceKwh, binding.etCurrency, binding.etFuelPrice, binding.etFuelL100).forEach { et ->
            et.setOnFocusChangeListener { _, f -> if (!f) saveConsumption() }
        }
        binding.etRatedCycles.setOnFocusChangeListener { _, f -> if (!f) saveConsumption() }
        refreshVehicleBox()
    }

    private fun saveConsumption() {
        if (_binding == null) return
        parseNum(binding.etConsumption.text)?.let { prefs.consumptionWhKm = it.toFloat().coerceIn(0f, 1000f) }
        fun num(t: CharSequence?) = parseNum(t)?.toFloat()?.coerceAtLeast(0f) ?: 0f
        prefs.pricePerKwh = num(binding.etPriceKwh.text)
        prefs.currency = binding.etCurrency.text?.toString()?.trim()?.ifEmpty { null } ?: prefs.currency
        prefs.fuelPrice = num(binding.etFuelPrice.text)
        prefs.fuelL100 = num(binding.etFuelL100.text)
        val rc = binding.etRatedCycles.text?.toString()?.trim().orEmpty()
        prefs.ratedCycles = if (rc.isEmpty()) 0 else (parseNum(rc)?.toInt()?.coerceIn(0, 20000) ?: prefs.ratedCycles)
    }

    private fun refreshVehicleBox() {
        if (_binding == null) return
        val v = prefs.vehicle
        binding.boxTripSettings.visibility = if (v.mobile) View.VISIBLE else View.GONE
        val learned = com.vanaccess.bms.reports.ReportStore.get(requireContext()).learnedConsumption(prefs.selectedDeviceAddress)
        binding.tvConsumptionLearned.text = if (learned != null)
            getString(R.string.consumption_learned, learned.first, learned.second)
        else getString(R.string.consumption_none, v.defaultWhPerKm)
    }

    // ── Share the app ───────────────────────────────────────────────────────

    private fun setupShare() {
        binding.btnShareText.setOnClickListener { Share.shareText(requireContext()) }
        binding.btnShareApk.setOnClickListener { btn ->
            btn.isEnabled = false
            Share.shareApk(requireContext()) { ok ->
                if (_binding != null) binding.btnShareApk.isEnabled = true
                if (!ok) context?.let { Toast.makeText(it, R.string.share_apk_failed, Toast.LENGTH_SHORT).show() }
            }
        }
        binding.btnOpenSite.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(Share.SITE)))
        }
        binding.btnOpenRepo.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(Share.REPO)))
        }
    }

    // ── Self-update ─────────────────────────────────────────────────────────

    private fun setupUpdates() {
        binding.switchAutoUpdate.isChecked = prefs.autoCheckUpdates
        fun renderInterval() {
            binding.tvUpdateInterval.visibility = if (prefs.autoCheckUpdates) View.VISIBLE else View.GONE
            binding.tvUpdateInterval.text = getString(R.string.update_interval,
                com.vanaccess.bms.ui.UpdateUi.intervalLabel(requireContext(), prefs.updateIntervalH)) + "  ▾"
        }
        renderInterval()
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, c ->
            prefs.autoCheckUpdates = c; renderInterval(); UpdateChecker.schedule(requireContext())
        }
        binding.tvUpdateInterval.setOnClickListener {
            val all = com.vanaccess.bms.ui.UpdateUi.INTERVALS
            AD.show(requireContext(), getString(R.string.update_auto_check),
                items = all.map { com.vanaccess.bms.ui.UpdateUi.intervalLabel(requireContext(), it) },
                checked = all.indexOf(prefs.updateIntervalH), onItem = { i ->
                prefs.updateIntervalH = all[i]; renderInterval(); UpdateChecker.schedule(requireContext())
            })
        }
        binding.switchPrerelease.isChecked = prefs.allowPrerelease
        binding.switchPrerelease.setOnCheckedChangeListener { _, c -> prefs.allowPrerelease = c }
        binding.btnUpdateCheck.setOnClickListener { UpdateChecker.check(requireContext(), userInitiated = true) }
        UpdateChecker.phase.observe(viewLifecycleOwner) { renderUpdate() }
        UpdateService.state.observe(viewLifecycleOwner) { renderUpdate() }
        renderUpdate()

    }

    private fun renderUpdate() {
        if (_binding == null) return
        val ctx = requireContext()
        val current = Updater.currentVersion(ctx)
        val dl = UpdateService.state.value ?: UpdateDownload.Idle
        val phase = UpdateChecker.phase.value ?: UpdatePhase.Idle
        val btn = binding.btnUpdateAction
        binding.progressUpdate.visibility = View.GONE
        btn.visibility = View.GONE
        binding.btnUpdateCheck.isEnabled = phase !is UpdatePhase.Checking && dl !is UpdateDownload.Running

        when (dl) {
            is UpdateDownload.Running -> {
                val pct = if (dl.total > 0) (dl.received * 100 / dl.total).toInt() else 0
                binding.tvUpdateStatus.text = getString(R.string.update_downloading_pct, pct)
                binding.progressUpdate.visibility = View.VISIBLE
                binding.progressUpdate.isIndeterminate = dl.total <= 0
                binding.progressUpdate.progress = pct
                btn.visibility = View.VISIBLE
                btn.text = getString(R.string.btn_cancel)
                btn.setOnClickListener { UpdateService.stop(ctx) }
                return
            }
            is UpdateDownload.Done -> {
                binding.tvUpdateStatus.text = getString(R.string.update_ready)
                btn.visibility = View.VISIBLE
                btn.text = getString(R.string.update_install)
                btn.setOnClickListener { installUpdate(dl.file) }
                return
            }
            is UpdateDownload.Failed -> {
                binding.tvUpdateStatus.text = getString(R.string.update_failed) + "\n" + dl.message
            }
            else -> {}
        }
        if (dl !is UpdateDownload.Failed) binding.tvUpdateStatus.text = when (phase) {
            UpdatePhase.Checking -> getString(R.string.update_checking)
            UpdatePhase.UpToDate -> getString(R.string.update_up_to_date, current)
            is UpdatePhase.Error -> getString(R.string.update_error,
                if (phase.message == "rate-limited") getString(R.string.update_rate_limited) else phase.message)
            is UpdatePhase.Available -> getString(R.string.update_available_status, phase.info.version, current)
            else -> getString(R.string.update_current, current)
        }
        val act = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        binding.btnUpdateNotes.visibility = View.GONE
        if (phase is UpdatePhase.Available) {
            val info = phase.info
            binding.btnUpdateNotes.visibility = View.VISIBLE
            binding.btnUpdateNotes.setOnClickListener { com.vanaccess.bms.ui.UpdateUi.showNotes(act, info) }
            btn.visibility = View.VISIBLE
            btn.text = getString(R.string.update_download_install)
            btn.setOnClickListener { com.vanaccess.bms.ui.UpdateUi.download(act, info) }
        }
    }

    private fun installUpdate(file: java.io.File) {
        (activity as? androidx.appcompat.app.AppCompatActivity)?.let { com.vanaccess.bms.ui.UpdateUi.install(it, file) }
    }

    // ── Battery alarms ──────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        _binding?.btnBackground?.visibility =
            if (com.vanaccess.bms.ui.BackgroundAccess.granted(requireContext())) View.GONE else View.VISIBLE
        _binding?.boxFullScreen?.visibility =
            if (com.vanaccess.bms.ui.FullScreenAccess.granted(requireContext())) View.GONE else View.VISIBLE
    }

    private fun setupAlarms() {
        binding.switchAlarmHigh.isChecked = prefs.alarmHighEnabled
        binding.switchAlarmLow.isChecked = prefs.alarmLowEnabled
        binding.switchAlarmVibrate.isChecked = prefs.alarmVibrate
        binding.etAlarmHighSoc.setText(prefs.alarmHighSoc.toString())
        binding.etAlarmHighV.setText(fmtV(prefs.alarmHighVoltage))
        binding.etAlarmLowSoc.setText(prefs.alarmLowSoc.toString())
        binding.etAlarmLowV.setText(fmtV(prefs.alarmLowVoltage))
        binding.etAlarmCooldown.setText(prefs.alarmCooldownMinutes.toString())
        // Charge-limit presets (80 / 90 / 100 %) for the full-charge alarm
        binding.chipsSocLimit.removeAllViews()
        listOf(80, 90, 100).forEach { pct ->
            binding.chipsSocLimit.addView(com.google.android.material.chip.Chip(requireContext()).apply {
                text = getString(R.string.limit_preset, pct)
                isCheckable = true
                isChecked = prefs.alarmHighSoc == pct
                setOnClickListener {
                    prefs.alarmHighSoc = pct
                    prefs.alarmHighEnabled = true
                    binding.etAlarmHighSoc.setText(pct.toString())
                    binding.switchAlarmHigh.isChecked = true
                }
            })
        }
        binding.switchDaily.isChecked = prefs.notifyDaily
        binding.switchDaily.setOnCheckedChangeListener { _, c -> prefs.notifyDaily = c }
        binding.switchSumNotify.isChecked = prefs.notifySessionSummary
        binding.switchSumNotify.setOnCheckedChangeListener { _, c -> prefs.notifySessionSummary = c }
        binding.switchBalanceReminder.isChecked = prefs.balanceReminder
        binding.switchBalanceReminder.setOnCheckedChangeListener { _, c -> prefs.balanceReminder = c }
        binding.switchSumWeekly.isChecked = prefs.notifyWeekly
        binding.switchSumWeekly.setOnCheckedChangeListener { _, c -> prefs.notifyWeekly = c }
        binding.switchAlarmAway.isChecked = prefs.alarmAway
        binding.switchAlarmAway.setOnCheckedChangeListener { _, c ->
            prefs.alarmAway = c
            if (!c) com.vanaccess.bms.ble.ChargeTimer.cancel(requireContext())
        }
        binding.switchAlarmCell.isChecked = prefs.alarmCellEnabled
        binding.switchAlarmCell.setOnCheckedChangeListener { _, c -> prefs.alarmCellEnabled = c }
        binding.etAlarmCell.setText(prefs.alarmCellVoltage.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "")
        binding.etAlarmCell.setOnFocusChangeListener { _, f -> if (!f) saveAlarmFields(false) }
        binding.tvAlarmCellDesc.text = if (prefs.effectiveOvMv > 0) getString(R.string.alarm_cell_desc,
            com.vanaccess.bms.reports.SessionRecorder.chargeLearn.alarmCellV(prefs.effectiveOvMv / 1000.0)) else getString(R.string.alarm_cell_desc_unknown)
        binding.switchAlarmDelta.isChecked = prefs.alarmDeltaEnabled
        binding.switchAlarmDelta.setOnCheckedChangeListener { _, c -> prefs.alarmDeltaEnabled = c }
        binding.etAlarmDeltaRest.setText(prefs.alarmDeltaRestMv.toString())
        binding.etAlarmDeltaLoad.setText(prefs.alarmDeltaLoadMv.toString())
        listOf(binding.etAlarmDeltaRest, binding.etAlarmDeltaLoad).forEach { et ->
            et.setOnFocusChangeListener { _, f -> if (!f) saveAlarmFields(false) }
        }

        binding.switchAlarmHigh.setOnCheckedChangeListener { _, c ->
            prefs.alarmHighEnabled = c
            if (c) (activity as? MainActivity)?.ensureNotificationPermission()
        }
        binding.switchAlarmLow.setOnCheckedChangeListener { _, c ->
            prefs.alarmLowEnabled = c
            if (c) (activity as? MainActivity)?.ensureNotificationPermission()
        }
        binding.switchAlarmVibrate.setOnCheckedChangeListener { _, c -> prefs.alarmVibrate = c }

        listOf(binding.etAlarmHighSoc, binding.etAlarmHighV, binding.etAlarmLowSoc,
            binding.etAlarmLowV, binding.etAlarmCooldown).forEach { et ->
            et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveAlarmFields(false) }
        }

        binding.btnFullScreen.setOnClickListener { com.vanaccess.bms.ui.FullScreenAccess.ask(requireActivity()) }
        binding.btnAlarmTest.setOnClickListener {
            saveAlarmFields(false)
            (activity as? MainActivity)?.ensureNotificationPermission()
            sendServiceAction(BleService.ACTION_TEST_ALARM)
        }
        binding.btnAlarmStop.setOnClickListener { sendServiceAction(BleService.ACTION_STOP_ALARM) }
        renderTones()
        BleService.bmsParams.observe(viewLifecycleOwner) { renderAdvice() }
        BleService.cellVoltages.observe(viewLifecycleOwner) { renderAdvice() }
        renderAdvice()
    }

    /** One row per alarm kind: tone name (tap → system picker) and ▶ to test that alarm. */
    private fun renderTones() {
        if (_binding == null) return
        val T = com.vanaccess.bms.alarm.BatteryAlarmMonitor.Tones
        val box = binding.boxTones
        box.removeAllViews()
        listOf(
            com.vanaccess.bms.alarm.BatteryAlarmMonitor.Kind.HIGH to R.string.tone_high,
            com.vanaccess.bms.alarm.BatteryAlarmMonitor.Kind.LOW to R.string.tone_low,
            com.vanaccess.bms.alarm.BatteryAlarmMonitor.Kind.DELTA_LOAD to R.string.tone_delta,
            com.vanaccess.bms.alarm.BatteryAlarmMonitor.Kind.CELL to R.string.tone_cell,
        ).forEach { (kind, label) ->
            val saved = prefs.alarmTone(T.toneKey(kind))
            val uri = if (saved.isNotBlank()) Uri.parse(saved) else T.defaultTone(requireContext(), kind)
            val name = try {
                android.media.RingtoneManager.getRingtone(requireContext(), uri)?.getTitle(requireContext())
            } catch (_: Exception) { null } ?: "—"
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val btn = com.google.android.material.button.MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(label) + ": " + name + if (saved.isBlank()) " " + getString(R.string.tone_none) else ""
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    pickingTone = kind
                    val i = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALL)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.tone_pick) + " — " + getString(label))
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, uri)
                    }
                    try { toneLauncher.launch(i) } catch (_: Exception) { pickingTone = null }
                }
            }
            row.addView(btn, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val play = com.google.android.material.button.MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "▶"; minWidth = 0; minimumWidth = 0
                setOnClickListener {
                    val ctx = requireContext()
                    ctx.startService(android.content.Intent(ctx, BleService::class.java)
                        .setAction(BleService.ACTION_TEST_ALARM).putExtra("kind", kind.name))
                }
            }
            row.addView(play)
            box.addView(row)
            box.addView(com.google.android.material.switchmaterial.SwitchMaterial(requireContext()).apply {
                text = getString(R.string.alarm_short)
                textSize = 12f
                setTextColor(Color.parseColor("#9BA3B8"))
                isChecked = prefs.alarmShort(T.toneKey(kind))
                setOnCheckedChangeListener { _, c -> prefs.setAlarmShort(T.toneKey(kind), c) }
            })
        }
    }

    /** Charger + alarm voltage advice from the BMS protection limit (see ChargeAdvice). */
    private fun renderAdvice() {
        if (_binding == null) return
        val cells = prefs.bmsCellCount.takeIf { it > 0 } ?: BleService.cellVoltages.value?.size ?: 0
        val advice = com.vanaccess.bms.reports.ChargeAdvice.from(cells, prefs.effectiveOvMv)
        if (advice == null) {
            binding.tvAdvice.text = getString(R.string.advice_unknown)
            binding.tvAdviceWarning.visibility = View.GONE
            binding.btnAdviceApply.visibility = View.GONE
            return
        }
        binding.tvAdvice.text = getString(R.string.advice_body,
            advice.cellOvV, advice.cells, advice.protectionPackV,
            advice.chargerPackV, advice.chargeCellV,
            advice.alarmPackV, advice.alarmCellV)
        val current = prefs.alarmHighVoltage.toDouble()
        val unreachable = current > 0 && current >= advice.protectionPackV - 0.05
        binding.tvAdviceWarning.visibility = if (unreachable) View.VISIBLE else View.GONE
        binding.tvAdviceWarning.text = getString(R.string.advice_current_unreachable, current)
        val already = kotlin.math.abs(current - advice.alarmPackV) < 0.05
        binding.btnAdviceApply.visibility = if (already) View.GONE else View.VISIBLE
        binding.btnAdviceApply.text = getString(R.string.advice_apply, advice.alarmPackV)
        binding.btnAdviceApply.setOnClickListener {
            prefs.alarmHighVoltage = advice.alarmPackV.toFloat()
            binding.etAlarmHighV.setText(fmtV(prefs.alarmHighVoltage))
            Toast.makeText(requireContext(), getString(R.string.advice_applied, advice.alarmPackV), Toast.LENGTH_SHORT).show()
            renderAdvice()
        }
    }

    private fun fmtV(v: Float): String = if (v == 0f) "0" else String.format(java.util.Locale.US, "%.1f", v)

    private fun parseNum(text: CharSequence?): Double? =
        text?.toString()?.trim()?.replace(',', '.')?.replace('٫', '.')
            ?.map { ch -> if (ch in '٠'..'٩') ('0' + (ch - '٠')) else ch }?.joinToString("")
            ?.toDoubleOrNull()

    private fun saveAlarmFields(showToast: Boolean) {
        if (_binding == null) return
        val chk = com.vanaccess.bms.alarm.AlarmCheck
        val cells = prefs.bmsCellCount; val ov = prefs.effectiveOvMv
        // A value that is impossible for this battery is marked and not saved; the previous one stays
        fun field(et: android.widget.EditText, problem: String?, save: () -> Unit) {
            (et.parent.parent as? com.google.android.material.textfield.TextInputLayout)?.apply {
                error = problem; isErrorEnabled = problem != null
            }
            if (problem == null) save()
        }
        fun range(r: Pair<Double, Double>?, digits: Int) = r?.let {
            getString(R.string.alarm_bad_value, String.format(java.util.Locale.US, "%.${digits}f – %.${digits}f V", it.first, it.second)) }
        parseNum(binding.etAlarmHighSoc.text)?.toInt()?.let { v ->
            field(binding.etAlarmHighSoc, if (chk.highSoc(v)) null else getString(R.string.alarm_bad_value, "10 – 100 %")) { prefs.alarmHighSoc = v } }
        parseNum(binding.etAlarmHighV.text)?.let { v ->
            field(binding.etAlarmHighV, range(chk.highVoltage(v, cells, ov), 1)) { prefs.alarmHighVoltage = v.toFloat().coerceIn(0f, 1000f) } }
        renderAdvice()
        parseNum(binding.etAlarmLowSoc.text)?.toInt()?.let { v ->
            field(binding.etAlarmLowSoc, if (chk.lowSoc(v)) null else getString(R.string.alarm_bad_value, "5 – 80 %")) { prefs.alarmLowSoc = v } }
        parseNum(binding.etAlarmLowV.text)?.let { v ->
            val uv = com.vanaccess.bms.ui.AdviceText.limits(requireContext()).cellUvMv
            field(binding.etAlarmLowV, range(chk.lowVoltage(v, cells, ov, uv), 1)) { prefs.alarmLowVoltage = v.toFloat().coerceIn(0f, 1000f) } }
        (parseNum(binding.etAlarmCell.text) ?: 0.0).let { v ->
            field(binding.etAlarmCell, range(chk.cellVoltage(v, ov), 2)) { prefs.alarmCellVoltage = v.toFloat() } }
        parseNum(binding.etAlarmCooldown.text)?.let { prefs.alarmCooldownMinutes = it.toInt().coerceIn(1, 1440) }
        parseNum(binding.etAlarmDeltaRest.text)?.let { prefs.alarmDeltaRestMv = it.toInt().coerceIn(5, 1000) }
        parseNum(binding.etAlarmDeltaLoad.text)?.let { prefs.alarmDeltaLoadMv = it.toInt().coerceIn(10, 2000) }
        if (showToast) Toast.makeText(requireContext(), R.string.alarm_saved, Toast.LENGTH_SHORT).show()
    }

    private fun sendServiceAction(action: String) {
        val ctx = requireContext()
        val intent = android.content.Intent(ctx, BleService::class.java).setAction(action)
        try { ctx.startService(intent) } catch (e: Exception) {
            Toast.makeText(ctx, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Device rows in unified card ─────────────────────────────────────────

    private fun setupDeviceRows() {
        refreshDeviceRows()

        // Switch Panel connect/disconnect
        binding.btnDevSp.setOnClickListener {
            val service = (activity as? MainActivity)?.getBleService() ?: return@setOnClickListener
            val addr = prefs.switchPanelDeviceAddress
            if (BleService.switchPanelConnState.value == BleService.ConnectionState.CONNECTED) {
                service.disconnectSwitchPanel()
            } else if (!addr.isNullOrBlank()) {
                service.connectSwitchPanel(addr)
            }
        }
        // Switch Panel clear
        binding.btnClearSp.setOnClickListener {
            confirmClear(getString(R.string.lbl_switch_panel), prefs.switchPanelDeviceName ?: prefs.switchPanelDeviceAddress) {
                (activity as? MainActivity)?.getBleService()?.disconnectSwitchPanel()
                prefs.switchPanelDeviceAddress = null
                prefs.switchPanelDeviceName = null
                BleService.switchPanelData.postValue(null)
                refreshDeviceRows()
            }
        }

        // Battery connect/disconnect
        binding.btnDevBat.setOnClickListener {
            val service = (activity as? MainActivity)?.getBleService() ?: return@setOnClickListener
            val addr = prefs.selectedDeviceAddress
            if (BleService.connectionState.value == BleService.ConnectionState.CONNECTED) {
                service.disconnect()
            } else if (!addr.isNullOrBlank()) {
                service.connectToDevice(addr)
            }
        }
        // Battery clear
        binding.btnClearBat.setOnClickListener {
            confirmClear(getString(R.string.lbl_battery), prefs.selectedDeviceName ?: prefs.selectedDeviceAddress) {
                (activity as? MainActivity)?.getBleService()?.disconnect()
                prefs.selectedDeviceAddress = null
                prefs.selectedDeviceName = null
                prefs.batteryType = null
                prefs.bmsPin = null
                BleService.basicInfo.postValue(null)
                BleService.cellVoltages.postValue(null)
                BleService.smartBmsState.postValue(null)
                BleService.batteryType.postValue(null)
                binding.etBmsPin.setText("")
                refreshDeviceRows()
            }
        }

        // Solar clear
        binding.btnDevSolar.setOnClickListener {
            confirmClear(getString(R.string.lbl_solar), prefs.smartSolarDeviceName ?: prefs.smartSolarDeviceAddress) {
                prefs.smartSolarDeviceAddress = null
                prefs.smartSolarDeviceName = null
                prefs.smartSolarAdvKey = null
                binding.etSolarKey.setText("")
                BleService.smartSolarData.postValue(null)
                refreshDeviceRows()
            }
        }

        // DC/DC clear
        binding.btnDevOrion.setOnClickListener {
            confirmClear(getString(R.string.lbl_dcdc), prefs.orionDeviceName ?: prefs.orionDeviceAddress) {
                prefs.orionDeviceAddress = null
                prefs.orionDeviceName = null
                prefs.orionAdvKey = null
                binding.etOrionKey.setText("")
                BleService.orionData.postValue(null)
                refreshDeviceRows()
            }
        }
    }

    private fun refreshDeviceRows() {
        // Switch Panel
        val spAddr = prefs.switchPanelDeviceAddress
        val spConfigured = !spAddr.isNullOrBlank()
        if (!spConfigured) {
            binding.tvDevSpInfo.text = getString(R.string.lbl_not_configured)
            binding.btnDevSp.visibility = View.GONE
            binding.btnClearSp.visibility = View.GONE
        } else {
            val name = prefs.switchPanelDeviceName ?: spAddr
            binding.tvDevSpInfo.text = "$name\n$spAddr"
            binding.btnDevSp.visibility = View.VISIBLE
            binding.btnClearSp.visibility = View.VISIBLE
        }

        // Battery
        val batAddr = prefs.selectedDeviceAddress
        val batConfigured = !batAddr.isNullOrBlank()
        if (!batConfigured) {
            binding.tvDevBatInfo.text = getString(R.string.lbl_not_configured)
            binding.btnDevBat.visibility = View.GONE
            binding.btnClearBat.visibility = View.GONE
            binding.tilBmsPin.visibility = View.GONE
        } else {
            val name = prefs.selectedDeviceName ?: batAddr
            val typeLabel = when (val t = prefs.batteryType) {
                null -> null
                "123bms" -> "123SmartBMS"
                else -> BmsDrivers.byId(t)?.displayName
            }
            binding.tvDevBatInfo.text = if (typeLabel != null) "$name\n$typeLabel" else "$name\n$batAddr"
            binding.btnDevBat.visibility = View.VISIBLE
            binding.btnClearBat.visibility = View.VISIBLE
            // Show PIN field only for 123BMS
            binding.tilBmsPin.visibility = if (prefs.is123Bms) View.VISIBLE else View.GONE
        }

        refreshSavedBatteries()

        // Solar
        val solarAddr = prefs.smartSolarDeviceAddress
        if (solarAddr.isNullOrBlank()) {
            binding.tvDevSolarInfo.text = getString(R.string.lbl_not_configured)
            binding.btnDevSolar.visibility = View.GONE
            binding.tilSolarKey.visibility = View.GONE
        } else {
            val name = prefs.smartSolarDeviceName ?: solarAddr
            binding.tvDevSolarInfo.text = "$name\n$solarAddr"
            binding.btnDevSolar.visibility = View.VISIBLE
            binding.tilSolarKey.visibility = View.VISIBLE
        }

        // Orion DC/DC
        val orionAddr = prefs.orionDeviceAddress
        if (orionAddr.isNullOrBlank()) {
            binding.tvDevOrionInfo.text = getString(R.string.lbl_not_configured)
            binding.btnDevOrion.visibility = View.GONE
            binding.tilOrionKey.visibility = View.GONE
        } else {
            val name = prefs.orionDeviceName ?: orionAddr
            binding.tvDevOrionInfo.text = "$name\n$orionAddr"
            binding.btnDevOrion.visibility = View.VISIBLE
            binding.tilOrionKey.visibility = View.VISIBLE
        }
    }

    /** Other saved batteries: tap to switch to it, ✕ to forget it. */
    private fun refreshSavedBatteries() {
        if (_binding == null) return
        val box = binding.boxSaved
        box.removeAllViews()
        val others = prefs.savedBatteries.filter { it.address != prefs.selectedDeviceAddress }
        binding.tvSavedTitle.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        val dp = resources.displayMetrics.density
        others.forEach { sb ->
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            val proto = BmsDrivers.byId(sb.protocol)?.displayName ?: sb.protocol
            val tv = android.widget.TextView(requireContext()).apply {
                text = "🔋 ${sb.name}\n$proto"
                setTextColor(Color.parseColor("#9BA3B8"))
                textSize = 12f
            }
            row.addView(tv, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val connect = com.google.android.material.button.MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.saved_connect); textSize = 12f
                setOnClickListener {
                    prefs.selectedDeviceAddress = sb.address
                    prefs.selectedDeviceName = sb.name
                    prefs.batteryType = if (prefs.bmsProtocol == BmsDrivers.AUTO) sb.protocol else prefs.bmsProtocol
                    prefs.batteryTypeAddress = sb.address
                    refreshDeviceRows()
                    (activity as? MainActivity)?.getBleService()?.connectToDevice(sb.address)
                }
            }
            row.addView(connect)
            val del = com.google.android.material.button.MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "✕"; minWidth = 0; minimumWidth = 0
                setOnClickListener {
                    confirmClear(getString(R.string.saved_batteries), sb.name) {
                        prefs.removeSavedBattery(sb.address); refreshSavedBatteries()
                    }
                }
            }
            row.addView(del)
            box.addView(row)
        }
    }

    private fun updateBatteryButton(state: BleService.ConnectionState) {
        if (prefs.selectedDeviceAddress.isNullOrBlank()) {
            binding.btnDevBat.visibility = View.GONE
            return
        }
        binding.btnDevBat.visibility = View.VISIBLE
        when (state) {
            BleService.ConnectionState.CONNECTED -> {
                binding.btnDevBat.text = getString(R.string.btn_disconnect)
                binding.tvDevBatInfo.setTextColor(Color.parseColor("#4CAF50"))
            }
            BleService.ConnectionState.CONNECTING,
            BleService.ConnectionState.RECONNECTING -> {
                binding.btnDevBat.text = getString(R.string.st_connecting)
                binding.tvDevBatInfo.setTextColor(Color.parseColor("#FF9800"))
            }
            BleService.ConnectionState.SCANNING -> {
                binding.btnDevBat.text = getString(R.string.btn_connect)
                binding.tvDevBatInfo.setTextColor(Color.parseColor("#FF9800"))
            }
            BleService.ConnectionState.DISCONNECTED -> {
                binding.btnDevBat.text = getString(R.string.btn_connect)
                binding.tvDevBatInfo.setTextColor(Color.parseColor("#9E9E9E"))
            }
        }
    }

    private fun updateSwitchPanelButton(state: BleService.ConnectionState) {
        if (prefs.switchPanelDeviceAddress.isNullOrBlank()) {
            binding.btnDevSp.visibility = View.GONE
            return
        }
        binding.btnDevSp.visibility = View.VISIBLE
        when (state) {
            BleService.ConnectionState.CONNECTED -> {
                binding.btnDevSp.text = getString(R.string.btn_disconnect)
                binding.tvDevSpInfo.setTextColor(Color.parseColor("#4CAF50"))
            }
            BleService.ConnectionState.CONNECTING,
            BleService.ConnectionState.RECONNECTING -> {
                binding.btnDevSp.text = getString(R.string.st_connecting)
                binding.tvDevSpInfo.setTextColor(Color.parseColor("#FF9800"))
            }
            else -> {
                binding.btnDevSp.text = getString(R.string.btn_connect)
                binding.tvDevSpInfo.setTextColor(Color.parseColor("#9E9E9E"))
            }
        }
    }

    // ── Scan / preferences buttons ──────────────────────────────────────────

    private fun setupButtons() {
        binding.btnScan.setOnClickListener {
            val service = (activity as? MainActivity)?.getBleService() ?: return@setOnClickListener
            deviceAdapter.clearDevices()
            service.startScan()
        }

        binding.switchAutoReconnect.isChecked = prefs.autoReconnect
        binding.switchAutoReconnect.setOnCheckedChangeListener { _, checked ->
            prefs.autoReconnect = checked
        }

        // App language (AndroidX per-app locales): empty list = follow the system
        val tag = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
        binding.radioGroupLang.check(when {
            tag.startsWith("ar") -> binding.radioLangAr.id
            tag.startsWith("en") -> binding.radioLangEn.id
            else -> binding.radioLangSystem.id
        })
        binding.radioGroupLang.setOnCheckedChangeListener { _, id ->
            val newTag = when (id) { binding.radioLangAr.id -> "ar"; binding.radioLangEn.id -> "en"; else -> "" }
            com.vanaccess.bms.data.AppLanguage.set(requireContext(), newTag)
            if (newTag != androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
                // Recreates the activity in the new language
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(newTag))
            }
        }

        binding.radioGroupTemp.check(
            if (prefs.temperatureUnit == "C") binding.radioCelsius.id
            else binding.radioFahrenheit.id
        )
        // Live tab design
        val designs = listOf("classic" to R.string.design_classic, "segments" to R.string.design_segments, "ring" to R.string.design_ring,
            "compact" to R.string.design_compact, "ride" to R.string.design_ride)
        binding.radioLiveDesign.removeAllViews()
        designs.forEachIndexed { i, (id, label) ->
            binding.radioLiveDesign.addView(android.widget.RadioButton(requireContext()).apply {
                this.id = 2000 + i
                text = getString(label)
                setTextColor(Color.WHITE)
                isChecked = prefs.liveDesign == id
            })
        }
        binding.radioLiveDesign.setOnCheckedChangeListener { _, checked ->
            designs.getOrNull(checked - 2000)?.let {
                prefs.liveDesign = it.first
                com.vanaccess.bms.widget.BatteryWidget.refresh(requireContext())   // widget follows the design
            }
        }

        binding.radioGroupTemp.setOnCheckedChangeListener { _, checkedId ->
            prefs.temperatureUnit = if (checkedId == binding.radioCelsius.id) "C" else "F"
        }
    }

    // ── Victron advertisement keys (inline under each device row) ──────────

    private fun setupKeysSection() {
        binding.etOrionKey.setText(prefs.orionAdvKey ?: "")
        binding.etSolarKey.setText(prefs.smartSolarAdvKey ?: "")

        // Auto-save orion key on focus loss
        binding.etOrionKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveOrionKey()
        }
        // Auto-save solar key on focus loss
        binding.etSolarKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveSolarKey()
        }

        // 123SmartBMS PIN
        binding.etBmsPin.setText(prefs.bmsPin ?: "")
        binding.etBmsPin.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveBmsPin()
        }
    }

    private fun saveBmsPin() {
        val pin = binding.etBmsPin.text?.toString()?.trim() ?: ""
        val prev = prefs.bmsPin
        prefs.bmsPin = pin.ifEmpty { null }
        if (prev != prefs.bmsPin && pin.isNotEmpty()) {
            Toast.makeText(requireContext(), "BMS PIN saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveOrionKey() {
        val key = binding.etOrionKey.text?.toString()?.trim() ?: ""
        if (key.isNotEmpty() && key.replace(" ", "").length != 32) {
            Toast.makeText(requireContext(), "DC/DC key must be 32 hex characters", Toast.LENGTH_SHORT).show()
            return
        }
        val prev = prefs.orionAdvKey
        prefs.orionAdvKey = key.ifEmpty { null }
        if (prev != prefs.orionAdvKey) {
            (activity as? MainActivity)?.getBleService()?.startVictronScan()
            Toast.makeText(requireContext(), "DC/DC key saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSolarKey() {
        val key = binding.etSolarKey.text?.toString()?.trim() ?: ""
        if (key.isNotEmpty() && key.replace(" ", "").length != 32) {
            Toast.makeText(requireContext(), "Solar key must be 32 hex characters", Toast.LENGTH_SHORT).show()
            return
        }
        val prev = prefs.smartSolarAdvKey
        prefs.smartSolarAdvKey = key.ifEmpty { null }
        if (prev != prefs.smartSolarAdvKey) {
            (activity as? MainActivity)?.getBleService()?.startVictronScan()
            Toast.makeText(requireContext(), "Solar key saved", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Observe live data ───────────────────────────────────────────────────

    private fun observeData() {
        BleService.scanResults.observe(viewLifecycleOwner) { devices ->
            deviceAdapter.updateDevices(devices)
            binding.tvScanHint.visibility =
                if (devices.isEmpty()) View.VISIBLE else View.GONE
        }

        BleService.connectionState.observe(viewLifecycleOwner) { state ->
            val scanning = state == BleService.ConnectionState.SCANNING
            binding.btnScan.isEnabled = !scanning
            binding.scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
            updateBatteryButton(state)
        }

        // When battery type is detected, refresh device rows to show type label + PIN field
        BleService.batteryType.observe(viewLifecycleOwner) { type ->
            refreshDeviceRows()
            refreshSavedBatteries()
            // If 123BMS detected and no PIN set, prompt user
            if (type == "123bms" && prefs.bmsPin.isNullOrBlank()) {
                showPinDialog()
            }
        }

        BleService.activeDriverName.observe(viewLifecycleOwner) { refreshProtocolButton() }

        BleService.switchPanelConnState.observe(viewLifecycleOwner) { state ->
            updateSwitchPanelButton(state)
        }

        // Victron data — update status color when data arrives
        BleService.smartSolarData.observe(viewLifecycleOwner) { data ->
            if (!prefs.smartSolarDeviceAddress.isNullOrBlank()) {
                binding.tvDevSolarInfo.setTextColor(
                    Color.parseColor(if (data != null) "#4CAF50" else "#FF9800")
                )
            }
        }

        BleService.orionData.observe(viewLifecycleOwner) { data ->
            if (!prefs.orionDeviceAddress.isNullOrBlank()) {
                binding.tvDevOrionInfo.setTextColor(
                    Color.parseColor(if (data != null) "#4CAF50" else "#FF9800")
                )
            }
        }
    }

    // ── Confirm clear dialog ────────────────────────────────────────────────

    private fun showPinDialog() {
        val ctx = requireContext()
        val editText = com.vanaccess.bms.ui.AppDialog.input(ctx, prefs.bmsPin ?: "", getString(R.string.pin_hint),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        com.vanaccess.bms.ui.AppDialog.show(ctx, getString(R.string.pin_title), message = getString(R.string.pin_msg), body = editText,
            buttons = listOf(
                com.vanaccess.bms.ui.AppDialog.Btn(getString(R.string.btn_save), com.vanaccess.bms.ui.AppDialog.GREEN) {
                    val pin = editText.text.toString().trim()
                    if (pin.isNotEmpty()) {
                        prefs.bmsPin = pin
                        binding.etBmsPin.setText(pin)
                        Toast.makeText(ctx, R.string.pin_saved, Toast.LENGTH_SHORT).show()
                        val service = (activity as? MainActivity)?.getBleService() ?: return@Btn
                        val addr = prefs.selectedDeviceAddress ?: return@Btn
                        service.connectToDevice(addr)
                    }
                },
                com.vanaccess.bms.ui.AppDialog.Btn(getString(R.string.btn_cancel), com.vanaccess.bms.ui.AppDialog.GREY),
            ))
    }

    private fun confirmClear(type: String, deviceName: String?, onConfirm: () -> Unit) {
        val name = deviceName ?: getString(R.string.this_device)
        com.vanaccess.bms.ui.AppDialog.show(requireContext(), getString(R.string.remove_title, type), message = getString(R.string.remove_msg, name),
            buttons = listOf(
                com.vanaccess.bms.ui.AppDialog.Btn(getString(R.string.btn_cancel), com.vanaccess.bms.ui.AppDialog.GREEN),
                com.vanaccess.bms.ui.AppDialog.Btn(getString(R.string.btn_remove), com.vanaccess.bms.ui.AppDialog.RED) { onConfirm() },
            ))
    }

    // ── Role assignment dialog ──────────────────────────────────────────────

    private fun showRoleDialog(device: ScannedDevice) {
        // One battery entry per vehicle type, then the non-battery roles
        val vehicles = com.vanaccess.bms.data.VehicleProfile.all
        val options = vehicles.map { "${it.icon}  " + getString(R.string.role_battery_for, getString(it.labelRes)) } +
            listOf(getString(R.string.role_orion), getString(R.string.role_solar), getString(R.string.role_switch))
        com.vanaccess.bms.ui.AppDialog.show(requireContext(), getString(R.string.role_assign, device.name), items = options, onItem = { which ->
            when {
                which < vehicles.size -> {
                    selectBattery(device)                      // first: the vehicle is saved per battery
                    prefs.vehicleType = vehicles[which].id
                    if (vehicles[which].mobile) (activity as? MainActivity)?.ensureLocationPermission()
                    setupVehicle()
                }
                which == vehicles.size -> selectOrion(device)
                which == vehicles.size + 1 -> selectSmartSolar(device)
                else -> selectSwitchPanel(device)
            }
        }, buttons = listOf(com.vanaccess.bms.ui.AppDialog.Btn(getString(R.string.btn_cancel), com.vanaccess.bms.ui.AppDialog.GREY)))
    }

    private fun selectBattery(device: ScannedDevice) {
        prefs.selectedDeviceAddress = device.address
        prefs.selectedDeviceName = device.name
        // Clear previous battery type — will be auto-detected on connect
        prefs.batteryType = if (prefs.bmsProtocol == BmsDrivers.AUTO) null else prefs.bmsProtocol
        prefs.batteryTypeAddress = null
        refreshDeviceRows()
        val service = (activity as? MainActivity)?.getBleService() ?: return
        service.connectToDevice(device.address)
    }

    private fun selectOrion(device: ScannedDevice) {
        prefs.orionDeviceAddress = device.address
        prefs.orionDeviceName = device.name
        refreshDeviceRows()
        (activity as? MainActivity)?.getBleService()?.startVictronScan()
        Toast.makeText(requireContext(), "Orion set to ${device.name}", Toast.LENGTH_SHORT).show()
    }

    private fun selectSmartSolar(device: ScannedDevice) {
        prefs.smartSolarDeviceAddress = device.address
        prefs.smartSolarDeviceName = device.name
        refreshDeviceRows()
        (activity as? MainActivity)?.getBleService()?.startVictronScan()
        Toast.makeText(requireContext(), "SmartSolar set to ${device.name}", Toast.LENGTH_SHORT).show()
    }

    private fun selectSwitchPanel(device: ScannedDevice) {
        prefs.switchPanelDeviceAddress = device.address
        prefs.switchPanelDeviceName = device.name
        refreshDeviceRows()
        val service = (activity as? MainActivity)?.getBleService()
        service?.connectSwitchPanel(device.address)
        Toast.makeText(requireContext(), "Switch Panel set to ${device.name}", Toast.LENGTH_SHORT).show()
    }

    // ── Debug ───────────────────────────────────────────────────────────────

    private fun setupDebugButton() {
        binding.btnOpenDebug.setOnClickListener {
            findNavController().navigate(R.id.debugFragment)
        }
    }

    // ── Import / Export ─────────────────────────────────────────────────────

    private fun setupImportExport() {
        binding.btnExport.setOnClickListener {
            exportLauncher.launch("gt_bms_settings.json")
        }
        binding.btnBackupFull.setOnClickListener {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            fullBackupLauncher.launch("GT-BMS-backup-$stamp.zip")
        }
        binding.btnRestoreFull.setOnClickListener { fullRestoreLauncher.launch(arrayOf("application/zip", "*/*")) }
        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }

    private fun writeExport(uri: Uri) {
        try {
            val json = JSONObject().apply {
                put("version", 1)
                put("battery", JSONObject().apply {
                    put("address", prefs.selectedDeviceAddress ?: JSONObject.NULL)
                    put("name",    prefs.selectedDeviceName    ?: JSONObject.NULL)
                    put("type",    prefs.batteryType           ?: JSONObject.NULL)
                    put("pin",     prefs.bmsPin                ?: JSONObject.NULL)
                })
                put("orion", JSONObject().apply {
                    put("address", prefs.orionDeviceAddress ?: JSONObject.NULL)
                    put("name",    prefs.orionDeviceName    ?: JSONObject.NULL)
                    put("advKey",  prefs.orionAdvKey        ?: JSONObject.NULL)
                })
                put("smartSolar", JSONObject().apply {
                    put("address", prefs.smartSolarDeviceAddress ?: JSONObject.NULL)
                    put("name",    prefs.smartSolarDeviceName    ?: JSONObject.NULL)
                    put("advKey",  prefs.smartSolarAdvKey        ?: JSONObject.NULL)
                })
                put("switchPanel", JSONObject().apply {
                    put("address", prefs.switchPanelDeviceAddress ?: JSONObject.NULL)
                    put("name",    prefs.switchPanelDeviceName    ?: JSONObject.NULL)
                    put("channels", JSONObject().apply {
                        for (i in 1..8) put("$i", prefs.getSwitchPanelChannelName(i))
                    })
                })
                put("preferences", JSONObject().apply {
                    put("autoReconnect",    prefs.autoReconnect)
                    put("temperatureUnit",  prefs.temperatureUnit)
                })
            }
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toString(2).toByteArray())
            }
            Toast.makeText(requireContext(), "Settings exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readImport(uri: Uri) {
        try {
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw Exception("Could not read file")
            val json = JSONObject(text)

            json.optJSONObject("battery")?.let { b ->
                prefs.selectedDeviceAddress = b.optString("address").ifBlank { null }
                prefs.selectedDeviceName    = b.optString("name").ifBlank { null }
                prefs.batteryType           = b.optString("type").ifBlank { null }
                prefs.bmsPin                = b.optString("pin").ifBlank { null }
            }
            json.optJSONObject("orion")?.let { o ->
                prefs.orionDeviceAddress = o.optString("address").ifBlank { null }
                prefs.orionDeviceName    = o.optString("name").ifBlank { null }
                prefs.orionAdvKey        = o.optString("advKey").ifBlank { null }
            }
            json.optJSONObject("smartSolar")?.let { s ->
                prefs.smartSolarDeviceAddress = s.optString("address").ifBlank { null }
                prefs.smartSolarDeviceName    = s.optString("name").ifBlank { null }
                prefs.smartSolarAdvKey        = s.optString("advKey").ifBlank { null }
            }
            json.optJSONObject("switchPanel")?.let { sp ->
                prefs.switchPanelDeviceAddress = sp.optString("address").ifBlank { null }
                prefs.switchPanelDeviceName    = sp.optString("name").ifBlank { null }
                sp.optJSONObject("channels")?.let { chs ->
                    for (i in 1..8) {
                        val name = chs.optString("$i")
                        if (name.isNotBlank()) prefs.setSwitchPanelChannelName(i, name)
                    }
                }
            }
            json.optJSONObject("preferences")?.let { p ->
                prefs.autoReconnect    = p.optBoolean("autoReconnect", true)
                prefs.temperatureUnit  = p.optString("temperatureUnit", "C").ifBlank { "C" }
            }

            // Refresh all UI fields
            refreshDeviceRows()
            binding.etOrionKey.setText(prefs.orionAdvKey ?: "")
            binding.etSolarKey.setText(prefs.smartSolarAdvKey ?: "")
            binding.etBmsPin.setText(prefs.bmsPin ?: "")
            binding.switchAutoReconnect.isChecked = prefs.autoReconnect
            binding.radioGroupTemp.check(
                if (prefs.temperatureUnit == "C") binding.radioCelsius.id
                else binding.radioFahrenheit.id
            )
            (activity as? MainActivity)?.getBleService()?.startVictronScan()

            Toast.makeText(requireContext(), "Settings imported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        // Save any pending key/PIN edits before view is destroyed
        saveOrionKey()
        saveSolarKey()
        saveBmsPin()
        saveAlarmFields(false)
        saveConsumption()
        super.onDestroyView()
        _binding = null
    }
}
