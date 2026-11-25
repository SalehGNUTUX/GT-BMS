// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

import android.view.View
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.vanaccess.bms.R
import com.vanaccess.bms.ble.BleService
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private val NAV_KEYS = setOf("solar_address", "orion_address", "switchpanel_address")
        /** Bumped when the Live button is pressed; the battery screen jumps to its Live tab. */
        const val MODE_LIVE = "live"
        const val MODE_TABS = "tabs"
        /** Battery screen mode: Live (central button, the home screen) or the sub-tabs (Battery button). */
        val batteryMode = androidx.lifecycle.MutableLiveData(MODE_LIVE)
        /** Icon of the Battery button = the last Battery sub-tab used. */
        val batteryTabIcon = androidx.lifecycle.MutableLiveData<Int>()
    }

    private lateinit var binding: ActivityMainBinding
    private var bleService: BleService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.values.all { it }
        if (allGranted) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && !adapter.isEnabled) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                startBleService()
            }
        } else {
            Toast.makeText(this, R.string.perm_bt_required, Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkAndRequestPermissions()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNavigation.setupWithNavController(navController)
        setupBackHandling(navController)
        this.navController = navController
        // Central button → Live (its own screen). Battery button → the last Battery sub-tab.
        binding.btnLive.setOnClickListener {
            if (navController.currentDestination?.id != R.id.dashboardFragment)
                androidx.navigation.ui.NavigationUI.onNavDestinationSelected(
                    binding.bottomNavigation.menu.findItem(R.id.dashboardFragment), navController)
            batteryMode.value = MODE_LIVE
        }
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val ok = androidx.navigation.ui.NavigationUI.onNavDestinationSelected(item, navController)
            if (item.itemId == R.id.dashboardFragment) batteryMode.value = MODE_TABS
            ok
        }
        // Tapping the current tab again returns to that tab's root (e.g. from the debug console)
        binding.bottomNavigation.setOnItemReselectedListener { item ->
            if (navController.currentDestination?.id != item.itemId) navController.popBackStack(item.itemId, false)
            if (item.itemId == R.id.dashboardFragment) batteryMode.value = MODE_TABS
        }
        navController.addOnDestinationChangedListener { _, _, _ -> syncBarSelection() }
        batteryMode.observe(this) { syncBarSelection() }
        batteryTabIcon.observe(this) { icon -> binding.bottomNavigation.menu.findItem(R.id.dashboardFragment)?.setIcon(icon) }
        prefs = AppPreferences(this)
        updateNavTabs()
        prefs.registerListener(prefsListener)

        // Self-update: silent check on launch (once per chosen interval), background alarm,
        // in-app banner and notification deep-link
        com.vanaccess.bms.update.UpdateChecker.checkIfDue(this)
        com.vanaccess.bms.update.UpdateChecker.schedule(this)
        setupUpdateBanner()
        setupChargePulse()
        com.vanaccess.bms.ble.BleService.restoreLastReading(this)
        handleUpdateIntent(intent)

        // Check Bluetooth & permissions
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, R.string.no_bt_support, Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            // Request permissions first, then enable Bluetooth
            checkAndRequestPermissions()
        } else {
            checkAndRequestPermissions()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isEmpty()) {
            // Permissions already granted — now safe to enable Bluetooth
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && !adapter.isEnabled) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                startBleService()
            }
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startBleService() {
        val intent = Intent(this, BleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        BackgroundAccess.askOnce(this)
    }

    override fun onStart() {
        super.onStart()
        // On screen: look for the battery now, and GPS may be declared again for the service
        BleService.instance?.let { it.wake(); it.refreshForegroundType() }
    }

    private lateinit var prefs: AppPreferences
    private var lastBackPress = 0L

    /**
     * Root back behaviour (screens register their own callbacks first: a settings
     * section returns to the section grid, a battery tab returns to Live):
     *  - on another bottom tab → go to the Battery tab;
     *  - on the Battery tab → press twice within 2 s to leave. Monitoring and alarms
     *    keep running in the background service.
     */
    private fun setupBackHandling(nav: androidx.navigation.NavController) {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val dest = nav.currentDestination?.id
                val topLevel = binding.bottomNavigation.menu.findItem(dest ?: 0) != null
                // A screen opened from a tab (e.g. the debug console) goes back to that tab
                if (!topLevel && nav.previousBackStackEntry != null) {
                    nav.popBackStack()
                    return
                }
                if (dest != R.id.dashboardFragment) {
                    binding.bottomNavigation.selectedItemId = R.id.dashboardFragment
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2000) {
                    finish()
                } else {
                    lastBackPress = now
                    Toast.makeText(this@MainActivity, R.string.back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUpdateIntent(intent)
    }

    /** Opens Settings scrolled to the Updates card when launched from an update notification. */
    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(com.vanaccess.bms.update.UpdateChecker.EXTRA_OPEN_UPDATES, false) != true) return
        intent.removeExtra(com.vanaccess.bms.update.UpdateChecker.EXTRA_OPEN_UPDATES)
        scrollToUpdates = true
        binding.bottomNavigation.selectedItemId = R.id.settingsFragment
    }

    /** Read (and cleared) by SettingsFragment. */
    var scrollToUpdates = false

    // ── In-app update banner ────────────────────────────────────────────────
    /** Download/failed banner closed with ✕ (until the state changes). */
    private var bannerHiddenFor: Any? = null

    private fun setupUpdateBanner() {
        com.vanaccess.bms.update.UpdateChecker.phase.observe(this) { renderUpdateBanner() }
        com.vanaccess.bms.update.UpdateService.state.observe(this) { renderUpdateBanner() }
    }

    private fun renderUpdateBanner() {
        val phase = com.vanaccess.bms.update.UpdateChecker.phase.value
        val dl = com.vanaccess.bms.update.UpdateService.state.value
        val info = (phase as? com.vanaccess.bms.update.UpdatePhase.Available)?.info
        val b = binding
        fun btn(v: android.widget.TextView, text: Int, bg: Int, click: () -> Unit) {
            v.visibility = View.VISIBLE; v.setText(text); v.setBackgroundResource(bg); v.setOnClickListener { click() }
        }
        b.progressBanner.visibility = View.GONE
        b.btnBannerNotes.visibility = if (info != null) View.VISIBLE else View.GONE
        b.btnBannerNotes.setOnClickListener { info?.let { com.vanaccess.bms.ui.UpdateUi.showNotes(this, it) } }
        b.btnBannerClose.visibility = View.VISIBLE
        val show: Boolean = when (dl) {
            is com.vanaccess.bms.update.UpdateDownload.Running -> {
                val pct = if (dl.total > 0) (dl.received * 100 / dl.total).toInt() else 0
                b.tvBannerTitle.text = getString(R.string.update_downloading_pct, pct)
                b.tvBannerStatus.text = getString(R.string.update_mb, dl.received / 1048576.0,
                    if (dl.total > 0) dl.total / 1048576.0 else 0.0)
                b.progressBanner.visibility = View.VISIBLE
                b.progressBanner.isIndeterminate = dl.total <= 0
                b.progressBanner.setProgressCompat(pct, true)
                b.btnBannerNotes.visibility = View.GONE
                b.btnBannerClose.visibility = View.GONE
                btn(b.btnBannerAction, R.string.btn_cancel, R.drawable.banner_btn_red) {
                    com.vanaccess.bms.update.UpdateService.stop(this) }
                true
            }
            is com.vanaccess.bms.update.UpdateDownload.Done -> {
                b.tvBannerTitle.text = getString(R.string.update_ready)
                b.tvBannerStatus.text = info?.let { getString(R.string.update_available_title, it.version) } ?: ""
                btn(b.btnBannerAction, R.string.update_install, R.drawable.banner_btn_green) {
                    com.vanaccess.bms.ui.UpdateUi.install(this, dl.file) }
                bannerHiddenFor != dl
            }
            is com.vanaccess.bms.update.UpdateDownload.Failed -> {
                b.tvBannerTitle.text = getString(R.string.update_failed)
                b.tvBannerStatus.text = dl.message
                if (info != null) btn(b.btnBannerAction, R.string.update_retry, R.drawable.banner_btn_green) {
                    com.vanaccess.bms.ui.UpdateUi.download(this, info) }
                else b.btnBannerAction.visibility = View.GONE
                bannerHiddenFor != dl
            }
            else -> if (info != null && prefs.dismissedUpdate != info.version) {
                b.tvBannerTitle.text = "⬆️  " + getString(R.string.update_available_title, info.version)
                b.tvBannerStatus.text = getString(R.string.update_available_status, info.version,
                    com.vanaccess.bms.update.Updater.currentVersion(this))
                btn(b.btnBannerAction, R.string.update_download_install, R.drawable.banner_btn_green) {
                    com.vanaccess.bms.ui.UpdateUi.download(this, info) }
                true
            } else false
        }
        b.btnBannerClose.setOnClickListener {
            if (dl is com.vanaccess.bms.update.UpdateDownload.Done || dl is com.vanaccess.bms.update.UpdateDownload.Failed) bannerHiddenFor = dl
            else info?.let { prefs.dismissedUpdate = it.version }
            renderUpdateBanner()
        }
        b.updateBanner.visibility = if (show) View.VISIBLE else View.GONE
    }

    // Keep a strong reference: SharedPreferences holds listeners weakly.
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in NAV_KEYS) runOnUiThread { updateNavTabs() }
    }

    /** Show only the tabs that match devices the user configured (battery + settings always). */
    /**
     * Bottom bar built in code: Battery, the configured device tabs, Settings, and an empty
     * slot in the middle under the round Live button. The bar holds at most 5 items, so with
     * all three device tabs configured the slot is dropped and the button floats centred.
     */
    private fun updateNavTabs() {
        val nav = binding.bottomNavigation
        val menu = nav.menu
        val batteryIcon = batteryTabIcon.value ?: when (prefs.lastBatteryTab) {
            "REPORTS" -> R.drawable.ic_tab_reports; "INFO" -> R.drawable.ic_tab_info
            "HISTORY" -> R.drawable.ic_tab_history; else -> R.drawable.ic_tab_cells
        }
        val tabs = mutableListOf(Triple(R.id.dashboardFragment, R.string.nav_dashboard, batteryIcon))
        if (!prefs.smartSolarDeviceAddress.isNullOrBlank()) tabs += Triple(R.id.smartSolarFragment, R.string.nav_solar, R.drawable.ic_solar)
        if (!prefs.orionDeviceAddress.isNullOrBlank()) tabs += Triple(R.id.orionFragment, R.string.nav_orion, R.drawable.ic_dc)
        if (!prefs.switchPanelDeviceAddress.isNullOrBlank()) tabs += Triple(R.id.switchPanelFragment, R.string.nav_switches, R.drawable.ic_switch)
        tabs += Triple(R.id.settingsFragment, R.string.nav_settings, R.drawable.ic_settings)
        val withSlot = tabs.size < 5
        val slotAt = (tabs.size + 1) / 2
        val current = navController?.currentDestination?.id
        menu.clear()
        var order = 0
        tabs.forEachIndexed { i, (id, title, icon) ->
            if (withSlot && i == slotAt) {
                menu.add(android.view.Menu.NONE, R.id.nav_live_slot, order++, "").apply { isEnabled = false }
            }
            menu.add(android.view.Menu.NONE, id, order++, title).setIcon(icon)
        }
        current?.let { menu.findItem(it)?.isChecked = true }
        nav.post { positionLiveButton() }
        syncBarSelection()
    }

    /**
     * On Live the round button is the selected one and no bar item is highlighted (the empty
     * middle slot takes the "checked" state); on the Battery sub-tabs the Battery item is.
     */
    private fun syncBarSelection() {
        val onBattery = navController?.currentDestination?.id == R.id.dashboardFragment
        val live = onBattery && batteryMode.value == MODE_LIVE
        binding.btnLive.isSelected = live
        binding.bottomNavigation.post {
            val menu = binding.bottomNavigation.menu
            if (live) menu.findItem(R.id.nav_live_slot)?.let { it.isCheckable = true; it.isChecked = true }
            else if (onBattery) menu.findItem(R.id.dashboardFragment)?.isChecked = true
            // The slot under the round button keeps no highlight rectangle: the button itself shows Live
            binding.bottomNavigation.findViewById<View>(R.id.nav_live_slot)?.apply { background = null; foreground = null }
        }
    }

    // ── Central button blinks while charging ────────────────────────────────
    private var chargePulse: android.animation.ValueAnimator? = null

    private fun setupChargePulse() {
        com.vanaccess.bms.ble.BleService.basicInfo.observe(this) { info ->
            val charging = info != null && info.current > 0.3 && !com.vanaccess.bms.reports.SessionRecorder.regen(info.current) &&
                com.vanaccess.bms.ble.BleService.connectionState.value == com.vanaccess.bms.ble.BleService.ConnectionState.CONNECTED
            if (charging) startChargePulse() else stopChargePulse()
        }
        com.vanaccess.bms.ble.BleService.connectionState.observe(this) { st ->
            if (st != com.vanaccess.bms.ble.BleService.ConnectionState.CONNECTED) stopChargePulse()
        }
    }

    private fun startChargePulse() {
        if (chargePulse != null) return
        val btn = binding.btnLive
        val blue = android.graphics.Color.parseColor("#2196F3"); val green = android.graphics.Color.parseColor("#2ECC71")
        chargePulse = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                btn.imageTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.graphics.ColorUtils.blendARGB(blue, green, f))
                btn.alpha = 1f - 0.35f * (1f - f)
                btn.scaleX = 1f + 0.06f * f; btn.scaleY = btn.scaleX
            }
            start()
        }
    }

    private fun stopChargePulse() {
        chargePulse?.cancel(); chargePulse = null
        binding.btnLive.apply {
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2196F3"))
            alpha = 1f; scaleX = 1f; scaleY = 1f
        }
    }

    /** Centre the Live button over the empty slot (or the bar when there is none). */
    private fun positionLiveButton() {
        val btn = binding.btnLive
        val slot = binding.bottomNavigation.findViewById<android.view.View>(R.id.nav_live_slot)
        btn.translationX = if (slot != null && slot.width > 0) {
            val loc = IntArray(2); slot.getLocationOnScreen(loc)
            val slotCenter = loc[0] + slot.width / 2f
            val barLoc = IntArray(2); binding.bottomNavigation.getLocationOnScreen(barLoc)
            slotCenter - (barLoc[0] + binding.bottomNavigation.width / 2f)
        } else 0f
    }

    private var navController: androidx.navigation.NavController? = null



    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterListener(prefsListener)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    fun getBleService(): BleService? = bleService

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, R.string.notif_permission_needed, Toast.LENGTH_LONG).show()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            // Let the running service add the "location" foreground type for trips
            bleService?.refreshForegroundType()
        } else {
            Toast.makeText(this, R.string.location_needed, Toast.LENGTH_LONG).show()
        }
    }

    /** Trips need precise location (GPS speed and distance). */
    fun ensureLocationPermission() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    /** Android 13+: alarms need the POST_NOTIFICATIONS runtime permission. */
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
