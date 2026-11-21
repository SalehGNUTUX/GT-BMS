// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.settings

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
        observeData()

        binding.tvAboutVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
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
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.protocol_choose)
                .setSingleChoiceItems(entries.map { it.second }.toTypedArray(), current) { dialog, which ->
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
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
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
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, c -> prefs.autoCheckUpdates = c }
        binding.switchPrerelease.isChecked = prefs.allowPrerelease
        binding.switchPrerelease.setOnCheckedChangeListener { _, c -> prefs.allowPrerelease = c }
        binding.btnUpdateCheck.setOnClickListener { UpdateChecker.check(requireContext(), userInitiated = true) }
        UpdateChecker.phase.observe(viewLifecycleOwner) { renderUpdate() }
        UpdateService.state.observe(viewLifecycleOwner) { renderUpdate() }
        renderUpdate()

        val main = activity as? MainActivity
        if (main?.scrollToUpdates == true) {
            main.scrollToUpdates = false
            binding.settingsScroll.post { binding.settingsScroll.smoothScrollTo(0, binding.cardUpdates.top) }
        }
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
            is UpdatePhase.Error -> getString(R.string.update_error, phase.message)
            is UpdatePhase.Available -> getString(R.string.update_available_status, phase.info.version, current)
            else -> getString(R.string.update_current, current)
        }
        if (phase is UpdatePhase.Available) {
            val info = phase.info
            btn.visibility = View.VISIBLE
            btn.text = getString(R.string.update_download_install)
            btn.setOnClickListener {
                val asset = info.asset
                if (asset == null) {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                    return@setOnClickListener
                }
                AlertDialog.Builder(ctx)
                    .setTitle(getString(R.string.update_available_title, info.version))
                    .setMessage(info.notes.ifBlank { getString(R.string.update_available_text) })
                    .setPositiveButton(R.string.update_download_install) { _, _ ->
                        (activity as? MainActivity)?.ensureNotificationPermission()
                        UpdateService.start(ctx, asset)
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }
    }

    private fun installUpdate(file: java.io.File) {
        val ctx = requireContext()
        if (!Updater.canInstall(ctx)) {
            Toast.makeText(ctx, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")))
            return
        }
        Updater.install(ctx, file).onFailure {
            Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show()
        }
    }

    // ── Battery alarms ──────────────────────────────────────────────────────

    private fun setupAlarms() {
        binding.switchAlarmHigh.isChecked = prefs.alarmHighEnabled
        binding.switchAlarmLow.isChecked = prefs.alarmLowEnabled
        binding.switchAlarmVibrate.isChecked = prefs.alarmVibrate
        binding.etAlarmHighSoc.setText(prefs.alarmHighSoc.toString())
        binding.etAlarmHighV.setText(fmtV(prefs.alarmHighVoltage))
        binding.etAlarmLowSoc.setText(prefs.alarmLowSoc.toString())
        binding.etAlarmLowV.setText(fmtV(prefs.alarmLowVoltage))
        binding.etAlarmCooldown.setText(prefs.alarmCooldownMinutes.toString())

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

        binding.btnAlarmTest.setOnClickListener {
            saveAlarmFields(false)
            (activity as? MainActivity)?.ensureNotificationPermission()
            sendServiceAction(BleService.ACTION_TEST_ALARM)
        }
        binding.btnAlarmStop.setOnClickListener { sendServiceAction(BleService.ACTION_STOP_ALARM) }
    }

    private fun fmtV(v: Float): String = if (v == 0f) "0" else String.format(java.util.Locale.US, "%.1f", v)

    private fun parseNum(text: CharSequence?): Double? =
        text?.toString()?.trim()?.replace(',', '.')?.replace('٫', '.')
            ?.map { ch -> if (ch in '٠'..'٩') ('0' + (ch - '٠')) else ch }?.joinToString("")
            ?.toDoubleOrNull()

    private fun saveAlarmFields(showToast: Boolean) {
        if (_binding == null) return
        parseNum(binding.etAlarmHighSoc.text)?.let { prefs.alarmHighSoc = it.toInt().coerceIn(0, 100) }
        parseNum(binding.etAlarmHighV.text)?.let { prefs.alarmHighVoltage = it.toFloat().coerceIn(0f, 1000f) }
        parseNum(binding.etAlarmLowSoc.text)?.let { prefs.alarmLowSoc = it.toInt().coerceIn(0, 100) }
        parseNum(binding.etAlarmLowV.text)?.let { prefs.alarmLowVoltage = it.toFloat().coerceIn(0f, 1000f) }
        parseNum(binding.etAlarmCooldown.text)?.let { prefs.alarmCooldownMinutes = it.toInt().coerceIn(1, 1440) }
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
                setOnClickListener { prefs.removeSavedBattery(sb.address); refreshSavedBatteries() }
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

        binding.radioGroupTemp.check(
            if (prefs.temperatureUnit == "C") binding.radioCelsius.id
            else binding.radioFahrenheit.id
        )
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
        val editText = android.widget.EditText(requireContext()).apply {
            hint = "Enter PIN (e.g. 2410)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setText(prefs.bmsPin ?: "")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("123SmartBMS PIN")
            .setMessage("Enter the BMS PIN to authenticate.\nDefault is usually 0000 or the last 4 digits of the serial number.")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val pin = editText.text.toString().trim()
                if (pin.isNotEmpty()) {
                    prefs.bmsPin = pin
                    binding.etBmsPin.setText(pin)
                    Toast.makeText(requireContext(), "PIN saved", Toast.LENGTH_SHORT).show()
                    // Reconnect to apply PIN
                    val service = (activity as? MainActivity)?.getBleService() ?: return@setPositiveButton
                    val addr = prefs.selectedDeviceAddress ?: return@setPositiveButton
                    service.connectToDevice(addr)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClear(type: String, deviceName: String?, onConfirm: () -> Unit) {
        val name = deviceName ?: "this device"
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.remove_title, type))
            .setMessage(getString(R.string.remove_msg, name))
            .setPositiveButton(R.string.btn_remove) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ── Role assignment dialog ──────────────────────────────────────────────

    private fun showRoleDialog(device: ScannedDevice) {
        val options = arrayOf(
            getString(R.string.role_battery),
            getString(R.string.role_orion),
            getString(R.string.role_solar),
            getString(R.string.role_switch)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.role_assign, device.name))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> selectBattery(device)
                    1 -> selectOrion(device)
                    2 -> selectSmartSolar(device)
                    3 -> selectSwitchPanel(device)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
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
        super.onDestroyView()
        _binding = null
    }
}
