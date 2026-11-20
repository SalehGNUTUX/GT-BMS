// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

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
        prefs = AppPreferences(this)
        updateNavTabs()
        prefs.registerListener(prefsListener)

        // Self-update: silent check on launch (every 12 h at most) + notification deep-link
        com.vanaccess.bms.update.UpdateChecker.checkIfDue(this)
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
    }

    private lateinit var prefs: AppPreferences

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

    // Keep a strong reference: SharedPreferences holds listeners weakly.
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in NAV_KEYS) runOnUiThread { updateNavTabs() }
    }

    /** Show only the tabs that match devices the user configured (battery + settings always). */
    private fun updateNavTabs() {
        val menu = binding.bottomNavigation.menu
        menu.findItem(R.id.smartSolarFragment)?.isVisible = !prefs.smartSolarDeviceAddress.isNullOrBlank()
        menu.findItem(R.id.orionFragment)?.isVisible = !prefs.orionDeviceAddress.isNullOrBlank()
        menu.findItem(R.id.switchPanelFragment)?.isVisible = !prefs.switchPanelDeviceAddress.isNullOrBlank()
    }

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

    /** Android 13+: alarms need the POST_NOTIFICATIONS runtime permission. */
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
