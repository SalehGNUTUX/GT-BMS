// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.Collections
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import com.vanaccess.bms.R
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.ble.drivers.BmsDriver
import com.vanaccess.bms.ble.drivers.BmsDrivers
import com.vanaccess.bms.alarm.BatteryAlarmMonitor
import com.vanaccess.bms.ui.MainActivity
import java.util.UUID

class BleService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "gt_bms_ble"
        const val NOTIFICATION_ID = 1001

        // Intent actions for bound clients
        const val ACTION_GATT_CONNECTED = "ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED = "ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_DATA_AVAILABLE = "ACTION_DATA_AVAILABLE"

        // Singleton live data so any component can observe
        val connectionState = MutableLiveData<ConnectionState>(ConnectionState.DISCONNECTED)
        val basicInfo = MutableLiveData<BmsBasicInfo?>()
        val cellVoltages = MutableLiveData<List<Double>?>()
        val hardwareInfo = MutableLiveData<BmsHardwareInfo?>()
        val scanResults = MutableLiveData<List<ScannedDevice>>(emptyList())
        val lastError = MutableLiveData<String?>()

        // Victron advertisement LiveData
        val orionData         = MutableLiveData<OrionData?>()
        val smartSolarData    = MutableLiveData<SmartSolarData?>()
        val smartSolarKeyError = MutableLiveData<String?>()  // non-null = key problem message

        // Switch panel LiveData
        val switchPanelData        = MutableLiveData<SwitchPanelData?>()
        val switchPanelConnState   = MutableLiveData<ConnectionState>(ConnectionState.DISCONNECTED)

        // 123SmartBMS LiveData
        val batteryType = MutableLiveData<String?>(null) // "powerqueen" | "123bms" | null
        val smartBmsState = MutableLiveData<SmartBmsState?>()

        // Debug / reverse-engineering LiveData
        val gattDump = MutableLiveData<String>("")
        val rawPacketLog = MutableLiveData<List<String>>(emptyList())
        val activeServiceUuid = MutableLiveData<String?>()
        val activeWriteUuid = MutableLiveData<String?>()
        val activeNotifyUuid = MutableLiveData<String?>()
        val activeDriverName = MutableLiveData<String?>()
        /** Device identity (model, serials, clock…) keyed by DetailKey. */
        val deviceDetails = MutableLiveData<Map<String, String>>(emptyMap())
        val bmsParams = MutableLiveData<List<com.vanaccess.bms.ble.drivers.ParamItem>?>(null)
        val bmsHistory = MutableLiveData<List<com.vanaccess.bms.ble.drivers.HistoryRecord>?>(null)
        val historyLoading = MutableLiveData(false)
        val activeFeatures = MutableLiveData<Set<com.vanaccess.bms.ble.drivers.Feature>>(emptySet())

        /** How long a protocol candidate may stay silent before the next one is tried. */
        const val PROBE_TIMEOUT_MS = 4500L

        const val ACTION_STOP_ALARM = "com.gnutux.gtbms.STOP_ALARM"
        const val ACTION_TEST_ALARM = "com.gnutux.gtbms.TEST_ALARM"
    }

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

    private val binder = LocalBinder()
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    private val receiveBuffer = Collections.synchronizedList(mutableListOf<Byte>())
    private var pollRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private lateinit var prefs: AppPreferences
    private lateinit var alarmMonitor: BatteryAlarmMonitor

    // Pluggable BMS protocol state
    private var activeDriver: BmsDriver? = null
    private var driverLocked = false
    private val driverCandidates = mutableListOf<BmsDriver>()
    private var probeRunnable: Runnable? = null
    private var lastConnectedAddress: String? = null

    // 123SmartBMS state
    private var is123Bms = false
    private val smartBmsParser = SmartBmsAsciiParser()
    private val smartBmsLineBuffer = StringBuilder()
    private var smartBmsModuleAuthed = false
    private var smartBmsBmsAuthed = false
    private var smartBmsDataReceived = false
    private var smartBmsRefreshScheduled = false
    private val smartBmsWriteQueue = mutableListOf<Pair<String, ByteArray>>()
    private var smartBmsWriteInProgress = false

    // Scanning state
    private val scannedDevices = mutableListOf<ScannedDevice>()
    private var isScanning = false

    // Victron advertisement scan state
    private var victronScanRunnable: Runnable? = null
    private var isVictronScanning = false

    // Switch panel (Ai-Tanker) GATT state
    private var switchPanelGatt: BluetoothGatt? = null
    private var switchPanelWriteChar: BluetoothGattCharacteristic? = null
    private var switchPanelLastToken: Byte = 0x00
    private var switchPanelReconnectRunnable: Runnable? = null

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        alarmMonitor = BatteryAlarmMonitor(this, prefs)
        // Periodic background update check while the service runs (UpdateChecker throttles to 12 h)
        handler.post(object : Runnable {
            override fun run() {
                com.vanaccess.bms.update.UpdateChecker.checkIfDue(this@BleService)
                handler.postDelayed(this, 60 * 60 * 1000L)
            }
        })
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.app_name), getString(R.string.notif_initializing)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_ALARM -> { alarmMonitor.stopAlarm(); return START_STICKY }
            ACTION_TEST_ALARM -> { alarmMonitor.testAlarm(); return START_STICKY }
        }
        if (bluetoothGatt != null) return START_STICKY  // already running
        // Restore battery type from prefs
        val savedType = prefs.batteryType
        is123Bms = savedType == "123bms"
        batteryType.postValue(savedType)
        val savedAddress = prefs.selectedDeviceAddress
        if (!savedAddress.isNullOrEmpty()) {
            connectToDevice(savedAddress)
        }
        startVictronScan()
        val spAddress = prefs.switchPanelDeviceAddress
        if (!spAddress.isNullOrEmpty()) {
            connectSwitchPanel(spAddress)
        }
        return START_STICKY
    }

    // ── Scanning ─────────────────────────────────────────────────────────────

    fun startScan() {
        if (isScanning) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        scannedDevices.clear()
        scanResults.postValue(emptyList())
        connectionState.postValue(ConnectionState.SCANNING)
        isScanning = true

        val scanner = adapter.bluetoothLeScanner ?: return
        scanner.startScan(scanCallback)

        // Stop scan after 10s
        handler.postDelayed({
            stopScan()
        }, 10_000)
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        if (connectionState.value == ConnectionState.SCANNING) {
            connectionState.postValue(ConnectionState.DISCONNECTED)
        }
    }

    // ── Victron Advertisement Scan ────────────────────────────────────────────

    /**
     * Start (or restart) a periodic Victron advertisement scan.
     * Runs a 10-second BLE scan every 30 seconds, passive — no connection needed.
     * Call whenever Victron device config changes.
     */
    fun startVictronScan() {
        stopVictronScan()
        val hasOrion = !prefs.orionDeviceAddress.isNullOrEmpty()
        val hasSolar = !prefs.smartSolarDeviceAddress.isNullOrEmpty()
        if (!hasOrion && !hasSolar) return

        victronScanRunnable = object : Runnable {
            override fun run() {
                doVictronScanBurst()
                handler.postDelayed(this, 30_000)
            }
        }
        handler.post(victronScanRunnable!!)
    }

    fun stopVictronScan() {
        victronScanRunnable?.let { handler.removeCallbacks(it) }
        victronScanRunnable = null
        if (isVictronScanning) {
            BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(victronCallback)
            isVictronScanning = false
        }
    }

    private fun doVictronScanBurst() {
        if (isVictronScanning) return
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner
        if (scanner == null) {
            android.util.Log.w("VictronScan", "doVictronScanBurst: scanner is null (BT off?)")
            return
        }
        android.util.Log.d("VictronScan", "doVictronScanBurst: starting 10s burst " +
            "(orion=${prefs.orionDeviceAddress}, solar=${prefs.smartSolarDeviceAddress})")
        isVictronScanning = true
        scanner.startScan(victronCallback)
        handler.postDelayed({
            scanner.stopScan(victronCallback)
            isVictronScanning = false
            android.util.Log.d("VictronScan", "doVictronScanBurst: burst ended")
        }, 10_000)
    }

    private val victronCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            android.util.Log.e("VictronScan", "onScanFailed: errorCode=$errorCode")
            isVictronScanning = false
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mfrData = result.scanRecord
                ?.getManufacturerSpecificData(VictronProtocol.COMPANY_ID) ?: return
            val address = result.device.address

            val recordType = VictronProtocol.getRecordType(mfrData)
            val rawHex = mfrData.joinToString(" ") { "%02X".format(it) }

            when (address) {
                prefs.orionDeviceAddress -> {
                    android.util.Log.d("VictronScan",
                        "Orion raw[${mfrData.size}] type=0x%02X: $rawHex".format(recordType))
                    if (recordType != VictronProtocol.TYPE_DC_DC_CONVERTER &&
                        recordType != VictronProtocol.TYPE_ORION_XS) {
                        android.util.Log.w("VictronScan",
                            "Orion: unexpected record type 0x%02X (expected 0x04 or 0x0F)".format(
                                recordType))
                        return
                    }
                    val key = prefs.orionAdvKey?.takeIf { it.replace(" ","").length == 32 } ?: return
                    val dec = VictronProtocol.decrypt(key, mfrData) ?: return
                    android.util.Log.d("VictronScan",
                        "Orion dec[${dec.size}]: ${dec.joinToString(" ") { "%02X".format(it) }}")
                    val parsed = if (recordType == VictronProtocol.TYPE_ORION_XS)
                        VictronProtocol.parseOrionXS(dec)
                    else
                        VictronProtocol.parseOrion(dec)
                    parsed?.let { orionData.postValue(it) }
                }
                prefs.smartSolarDeviceAddress -> {
                    android.util.Log.d("VictronScan",
                        "SmartSolar raw[${mfrData.size}] type=0x%02X: $rawHex".format(recordType))
                    if (recordType != VictronProtocol.TYPE_SOLAR_CHARGER) {
                        android.util.Log.w("VictronScan",
                            "SmartSolar: unexpected record type 0x%02X (expected 0x%02X)".format(
                                recordType, VictronProtocol.TYPE_SOLAR_CHARGER))
                        return
                    }
                    val key = prefs.smartSolarAdvKey?.takeIf { it.replace(" ","").length == 32 }
                    val expectedByte = VictronProtocol.expectedKeyFirstByte(mfrData)
                    if (key == null) {
                        android.util.Log.w("VictronScan",
                            "SmartSolar: no key set (key must start with $expectedByte)")
                        smartSolarKeyError.postValue("No advertisement key set")
                        return
                    }
                    val dec = VictronProtocol.decrypt(key, mfrData)
                    if (dec == null) {
                        android.util.Log.w("VictronScan",
                            "SmartSolar: key[0] mismatch — key must start with $expectedByte")
                        smartSolarKeyError.postValue("Wrong key (must start with $expectedByte)")
                        return
                    }
                    android.util.Log.d("VictronScan",
                        "SmartSolar dec[${dec.size}]: ${dec.joinToString(" ") { "%02X".format(it) }}")
                    val parsed = VictronProtocol.parseSolarCharger(dec)
                    if (parsed == null) {
                        // key[0] matched by coincidence but content is garbage
                        android.util.Log.w("VictronScan",
                            "SmartSolar: decrypted content invalid (charge_state=${dec[0].toInt() and 0xFF})" +
                            " — key is wrong. Re-enter from VictronConnect → Product info. Key must start with $expectedByte")
                        smartSolarKeyError.postValue("Wrong key (must start with $expectedByte)")
                        return
                    }
                    smartSolarKeyError.postValue(null)   // key is good
                    smartSolarData.postValue(parsed)
                }
            }
        }
    }

    // ── Switch Panel (Ai-Tanker) GATT ─────────────────────────────────────────

    fun connectSwitchPanel(address: String) {
        switchPanelGatt?.close()
        switchPanelGatt = null
        switchPanelWriteChar = null
        switchPanelConnState.postValue(ConnectionState.CONNECTING)
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val device = adapter.getRemoteDevice(address)
        switchPanelGatt = device.connectGatt(this, false, switchPanelGattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnectSwitchPanel() {
        switchPanelReconnectRunnable?.let { handler.removeCallbacks(it) }
        switchPanelReconnectRunnable = null
        switchPanelGatt?.disconnect()
        switchPanelGatt?.close()
        switchPanelGatt = null
        switchPanelWriteChar = null
        switchPanelConnState.postValue(ConnectionState.DISCONNECTED)
    }

    /** Set channel states. channelMask bit0=CH1 … bit7=CH8. */
    fun setSwitchPanelChannels(channelMask: Int) {
        val gatt = switchPanelGatt ?: return
        val char = switchPanelWriteChar ?: return
        val cmd = SwitchPanelProtocol.buildSetChannels(channelMask, switchPanelLastToken)
        writeSwitchPanelChar(gatt, char, cmd)
    }

    /** Power off all channels (puts device in standby). */
    fun switchPanelPowerOff() {
        val gatt = switchPanelGatt ?: return
        val char = switchPanelWriteChar ?: return
        val cmd = SwitchPanelProtocol.buildPowerOff(switchPanelLastToken)
        writeSwitchPanelChar(gatt, char, cmd)
    }

    @Suppress("DEPRECATION")
    private fun writeSwitchPanelChar(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        data: ByteArray
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(char)
        }
    }

    private val switchPanelGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    switchPanelConnState.postValue(ConnectionState.CONNECTED)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    switchPanelWriteChar = null
                    switchPanelConnState.postValue(ConnectionState.DISCONNECTED)
                    // Auto-reconnect if address still configured
                    val addr = prefs.switchPanelDeviceAddress
                    if (!addr.isNullOrEmpty()) {
                        switchPanelConnState.postValue(ConnectionState.RECONNECTING)
                        switchPanelReconnectRunnable = Runnable { connectSwitchPanel(addr) }
                        handler.postDelayed(switchPanelReconnectRunnable!!, 5_000)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svcUuid  = java.util.UUID.fromString(SwitchPanelProtocol.SERVICE_UUID)
            val writeUuid  = java.util.UUID.fromString(SwitchPanelProtocol.WRITE_CHAR_UUID)
            val notifyUuid = java.util.UUID.fromString(SwitchPanelProtocol.NOTIFY_CHAR_UUID)
            val ccdUuid    = java.util.UUID.fromString(SwitchPanelProtocol.CCCD_UUID)

            val service = gatt.getService(svcUuid) ?: return
            switchPanelWriteChar = service.getCharacteristic(writeUuid) ?: return
            val notifyChar = service.getCharacteristic(notifyUuid) ?: return

            // Enable notifications
            gatt.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(ccdUuid) ?: return
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleSwitchPanelData(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleSwitchPanelData(characteristic.value ?: return)
        }
    }

    private fun handleSwitchPanelData(raw: ByteArray) {
        val data = SwitchPanelProtocol.parseNotification(raw) ?: return
        switchPanelLastToken = data.echoToken
        switchPanelData.postValue(data)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown"
            val existing = scannedDevices.find { it.address == device.address }
            if (existing == null) {
                scannedDevices.add(ScannedDevice(name, device.address, result.rssi))
            } else {
                existing.rssi = result.rssi
            }
            scanResults.postValue(scannedDevices.toList())
        }

        override fun onScanFailed(errorCode: Int) {
            lastError.postValue("BLE scan failed: error $errorCode")
            connectionState.postValue(ConnectionState.DISCONNECTED)
            isScanning = false
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun connectToDevice(address: String) {
        stopScan()
        cancelReconnect()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        if (address != lastConnectedAddress) {
            clearDeviceData()
            basicInfo.postValue(null)
            cellVoltages.postValue(null)
            lastConnectedAddress = address
        }
        connectionState.postValue(ConnectionState.CONNECTING)
        updateNotification(getString(R.string.st_connecting), address)

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            lastError.postValue("Bluetooth not available")
            connectionState.postValue(ConnectionState.DISCONNECTED)
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            lastError.postValue("Invalid device address: $address")
            connectionState.postValue(ConnectionState.DISCONNECTED)
            return
        }

        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        cancelReconnect()
        cancelProbe()
        driverLocked = false
        stopPolling()
        // Clean up 123BMS state
        smartBmsWriteQueue.clear()
        smartBmsWriteInProgress = false
        smartBmsRefreshScheduled = false
        is123Bms = false
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectionState.postValue(ConnectionState.DISCONNECTED)
        updateNotification("Disconnected", "")
    }

    private fun scheduleReconnect(address: String) {
        cancelReconnect()
        reconnectRunnable = Runnable {
            if (connectionState.value != ConnectionState.CONNECTED) {
                connectionState.postValue(ConnectionState.RECONNECTING)
                updateNotification("Reconnecting...", address)
                connectToDevice(address)
            }
        }.also { handler.postDelayed(it, 2000) }
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // ── GATT Callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionState.postValue(ConnectionState.CONNECTED)
                    updateNotification("Connected", gatt.device.name ?: gatt.device.address)
                    // Request a larger MTU; onMtuChanged fires then we discover services
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    stopPolling()
                    cancelProbe()
                    driverLocked = false
                    writeCharacteristic = null
                    notifyCharacteristic = null
                    connectionState.postValue(ConnectionState.DISCONNECTED)
                    updateNotification("Disconnected", "Auto-reconnecting...")
                    // Auto reconnect if we have a saved address
                    val savedAddress = prefs.selectedDeviceAddress
                    if (!savedAddress.isNullOrEmpty()) {
                        scheduleReconnect(savedAddress)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            android.util.Log.d("BleService", "onMtuChanged: mtu=$mtu status=$status")
            // Wipe the GATT cache only for unknown devices; known ones reuse the cached
            // service table, which makes reconnects much faster.
            val known = (prefs.batteryType != null && prefs.batteryTypeAddress == gatt.device.address) ||
                prefs.savedProtocolFor(gatt.device.address) != null
            if (!known) try {
                val refresh = gatt.javaClass.getMethod("refresh")
                val refreshed = refresh.invoke(gatt) as? Boolean ?: false
                android.util.Log.d("BleService", "gatt.refresh() = $refreshed")
            } catch (e: Exception) {
                android.util.Log.d("BleService", "gatt.refresh() not available: ${e.message}")
            }
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError.postValue("Service discovery failed")
                return
            }
            setupCharacteristics(gatt)
        }

        // Android ≤ 12: value is in characteristic.value
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            android.util.Log.d("BleService", "RX notify char=${characteristic.uuid} " +
                "${data.size}B: ${data.joinToString(" ") { "%02X".format(it) }}")
            handleIncomingData(data)
        }

        // Android 13+ (API 33): value is passed directly — overriding both versions is the
        // correct pattern; the platform calls whichever signature it dispatches to.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            android.util.Log.d("BleService", "RX notify(33) char=${characteristic.uuid} " +
                "${value.size}B: ${value.joinToString(" ") { "%02X".format(it) }}")
            handleIncomingData(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleIncomingData(characteristic.value ?: return)
            }
        }

        // Android 13+ version
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleIncomingData(value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            android.util.Log.d("BleService", "onCharacteristicWrite: status=$status char=${characteristic.uuid}")
            if (is123Bms && status == BluetoothGatt.GATT_SUCCESS) {
                handle123BmsWriteComplete()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            android.util.Log.d("BleService", "onDescriptorWrite: status=$status char=${descriptor.characteristic.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                advanceCccdChain(gatt, descriptor.characteristic.uuid.toString())
            } else {
                lastError.postValue(
                    "Failed to enable notifications on ${descriptor.characteristic.uuid} " +
                            "(GATT status $status). No data will be received."
                )
                android.util.Log.e("BleService", "onDescriptorWrite failed: status=$status char=${descriptor.characteristic.uuid}")
            }
        }
    }

    private fun setupCharacteristics(gatt: BluetoothGatt) {
        // Build detailed GATT dump for debugging / reverse engineering
        val dump = buildString {
            appendLine("Device: ${gatt.device.address}  name=${gatt.device.name ?: "(none)"}")
            appendLine("Services: ${gatt.services.size}")
            gatt.services.forEachIndexed { si, svc ->
                appendLine("")
                appendLine("[${si + 1}] Service: ${svc.uuid}")
                svc.characteristics.forEachIndexed { ci, c ->
                    val propList = buildList {
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("SIGNED")
                    }.joinToString("|")
                    appendLine("  [${ci + 1}] Char: ${c.uuid}")
                    appendLine("        Props(0x${c.properties.toString(16).uppercase()}): $propList")
                    c.descriptors.forEach { d -> appendLine("        Desc: ${d.uuid}") }
                }
            }
        }
        android.util.Log.i("BleService", "GATT Dump:\n$dump")
        gattDump.postValue(dump)

        // Detect battery type from GATT services
        val serviceUuids = gatt.services.map { it.uuid.toString() }
        val forced = prefs.bmsProtocol
        val detected123Bms = when (forced) {
            BmsDrivers.AUTO -> SmartBmsProtocol.is123SmartBms(serviceUuids)
            "123bms" -> true
            else -> false
        }
        is123Bms = detected123Bms
        if (detected123Bms) {
            prefs.batteryType = "123bms"
            batteryType.postValue("123bms")
            activeDriverName.postValue("123SmartBMS")
            android.util.Log.i("BleService", "Battery type detected: 123bms")
            val svc = gatt.getService(UUID.fromString(SmartBmsProtocol.NUS_SERVICE_UUID))
            val w = svc?.getCharacteristic(UUID.fromString(SmartBmsProtocol.NUS_RX_UUID))
            val n = svc?.getCharacteristic(UUID.fromString(SmartBmsProtocol.NUS_TX_UUID))
            if (w != null && n != null) {
                writeCharacteristic = w
                notifyCharacteristic = n
                activeServiceUuid.postValue(svc.uuid.toString())
                activeWriteUuid.postValue(w.uuid.toString())
                activeNotifyUuid.postValue(n.uuid.toString())
                enableNotifications(gatt)
                return
            }
        }

        // Build the ordered list of protocol candidates and probe them one by one.
        driverCandidates.clear()
        driverCandidates.addAll(
            BmsDrivers.candidates(
                forcedId = forced,
                rememberedId = prefs.batteryType?.takeIf { prefs.batteryTypeAddress == gatt.device.address }
                    ?: prefs.savedProtocolFor(gatt.device.address),
                deviceName = gatt.device.name ?: prefs.selectedDeviceName,
                serviceUuids = serviceUuids
            )
        )
        android.util.Log.i("BleService", "Driver candidates: ${driverCandidates.joinToString { it.id }}")
        if (tryNextDriver(gatt)) return

        // Generic fallback: find any service with a writable + notify/indicate characteristic pair
        for (service in gatt.services) {
            val writeChar = service.characteristics.firstOrNull { c ->
                (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
            }
            val notifyChar = service.characteristics.firstOrNull { c ->
                (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
            }
            if (writeChar != null && notifyChar != null) {
                activeDriver = BmsDrivers.byId(forced) ?: BmsDrivers.byId("powerqueen")
                activeDriver?.reset()
                driverLocked = false
                writeCharacteristic  = writeChar
                notifyCharacteristic = notifyChar
                activeServiceUuid.postValue("${service.uuid} (fallback)")
                activeWriteUuid.postValue(writeChar.uuid.toString())
                activeNotifyUuid.postValue(notifyChar.uuid.toString())
                activeDriverName.postValue(activeDriver?.displayName)
                enableNotifications(gatt)
                return
            }
        }

        activeServiceUuid.postValue("NOT FOUND")
        activeWriteUuid.postValue(null)
        activeNotifyUuid.postValue(null)
        val serviceList = gatt.services.joinToString { it.uuid.toString() }
        lastError.postValue(
            getString(R.string.err_bms_service_not_found) + "\n" + serviceList
        )
    }

    /**
     * Select the next driver candidate whose GATT profile exists on the device, enable its
     * notifications and start polling. Returns false when no candidate is left.
     */
    private fun tryNextDriver(gatt: BluetoothGatt): Boolean {
        cancelProbe()
        val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        while (driverCandidates.isNotEmpty()) {
            val driver = driverCandidates.removeAt(0)
            for (p in driver.profiles) {
                val service = gatt.getService(UUID.fromString(p.service)) ?: continue
                val write   = service.getCharacteristic(UUID.fromString(p.write))  ?: continue
                val notify  = service.getCharacteristic(UUID.fromString(p.notify)) ?: continue
                if ((write.properties and writeMask) == 0) continue
                stopPolling()
                activeDriver = driver
                driverLocked = false
                driver.reset()
                synchronized(receiveBuffer) { receiveBuffer.clear() }
                writeCharacteristic  = write
                notifyCharacteristic = notify
                activeServiceUuid.postValue(p.service)
                activeWriteUuid.postValue(p.write)
                activeNotifyUuid.postValue(p.notify)
                activeDriverName.postValue("${driver.displayName} …")
                android.util.Log.i("BleService", "Probing driver ${driver.id} on ${p.service}")
                enableNotifications(gatt)
                // If no valid frame arrives in time, move on to the next candidate.
                if (driverCandidates.isNotEmpty()) {
                    probeRunnable = Runnable {
                        if (!driverLocked && bluetoothGatt === gatt) {
                            android.util.Log.i("BleService", "Driver ${driver.id} gave no data, trying next")
                            if (!tryNextDriver(gatt)) {
                                // Nothing else matched: keep the first-choice driver running
                                lastError.postValue(getString(R.string.err_no_protocol_answer))
                            }
                        }
                    }.also { handler.postDelayed(it, PROBE_TIMEOUT_MS) }
                }
                return true
            }
        }
        return false
    }

    private fun cancelProbe() {
        probeRunnable?.let { handler.removeCallbacks(it) }
        probeRunnable = null
    }

    /**
     * Enable notifications on the primary notify characteristic (usually ffe1),
     * then chain to ffe3 if it exists (some JBD BMS variants respond on ffe3).
     * Polling starts only after all CCCD writes complete (or time out).
     */
    private fun enableNotifications(gatt: BluetoothGatt) {
        val notify = notifyCharacteristic ?: return
        writeCccd(gatt, notify, isFirst = true)
    }

    private fun writeCccd(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, isFirst: Boolean) {
        gatt.setCharacteristicNotification(char, true)
        val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val descriptor = char.getDescriptor(cccdUuid)
        if (descriptor == null) {
            android.util.Log.w("BleService", "No CCCD on ${char.uuid} — skipping, starting poll")
            startPolling()
            return
        }
        val enableValue = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val writeResult = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, enableValue)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = enableValue
            @Suppress("DEPRECATION")
            if (gatt.writeDescriptor(descriptor)) 0 else -1
        }
        android.util.Log.d("BleService", "writeCccd result=$writeResult char=${char.uuid}")
        // Fallback: if onDescriptorWrite never fires, advance anyway after 2 s
        handler.postDelayed({
            if (pollRunnable == null) {
                android.util.Log.w("BleService", "CCCD timeout for ${char.uuid}, forcing next step")
                advanceCccdChain(gatt, char.uuid.toString())
            }
        }, 2000)
    }

    /** Called from onDescriptorWrite (or fallback timer) to proceed to next step. */
    private fun advanceCccdChain(gatt: BluetoothGatt, completedCharUuid: String) {
        // After ffe1 CCCD, try to also enable ffe3 (some JBD BMS respond on ffe3)
        if (completedCharUuid.startsWith("0000ffe1")) {
            val ffe3Uuid = UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb")
            val ffe0 = gatt.getService(UUID.fromString(BmsProtocol.FFE0_SERVICE_UUID))
            val ffe3 = ffe0?.getCharacteristic(ffe3Uuid)
            if (ffe3 != null && (ffe3.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                android.util.Log.d("BleService", "Enabling ffe3 CCCD after ffe1")
                writeCccd(gatt, ffe3, isFirst = false)
                return
            }
        }
        // All CCCD writes done — start polling
        startPolling()
    }


    // ── Data Handling ─────────────────────────────────────────────────────────

    private fun handleIncomingData(data: ByteArray) {
        // Raw packet log for debugging
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val hex = data.joinToString(" ") { b -> "%02X".format(b) }
        val ascii = String(data.map { b -> if (b.toInt() in 32..126) b else '.'.code.toByte() }.toByteArray())
        val entry = "[$ts] RX ${data.size}B\n  HEX: $hex\n  TXT: $ascii"
        val log = rawPacketLog.value?.toMutableList() ?: mutableListOf()
        log.add(0, entry)
        if (log.size > 200) log.subList(200, log.size).clear()
        rawPacketLog.postValue(log)

        // 123SmartBMS: route to ASCII handler
        if (is123Bms) {
            handle123BmsNotification(data)
            return
        }

        val driver = activeDriver ?: return
        val out = try { driver.onNotification(data) } catch (e: Exception) {
            android.util.Log.w("BleService", "Driver ${driver.id} parse error: ${e.message}")
            null
        } ?: return
        if (out.isEmpty) return
        if (!driverLocked) {
            driverLocked = true
            cancelProbe()
            driverCandidates.clear()
            prefs.batteryType = driver.id
            prefs.batteryTypeAddress = bluetoothGatt?.device?.address
            batteryType.postValue(driver.id)
            activeDriverName.postValue(driver.displayName)
            activeFeatures.postValue(driver.features)
            prefs.upsertSavedBattery(bluetoothGatt?.device?.address, bluetoothGatt?.device?.name ?: prefs.selectedDeviceName, driver.id)
            android.util.Log.i("BleService", "Protocol locked: ${driver.id}")
        }
        out.basic?.let {
            basicInfo.postValue(it)
            alarmMonitor.onReading(it.soc, it.packVoltage, it.current)
        }
        out.cells?.let { cellVoltages.postValue(it) }
        out.hardware?.let { hardwareInfo.postValue(it) }
        out.details?.let { d -> synchronized(detailsLock) { details.putAll(d); deviceDetails.postValue(details.toMap()) } }
        out.params?.let { bmsParams.postValue(it) }
        out.history?.let { h -> synchronized(detailsLock) { historyBuf.addAll(h); bmsHistory.postValue(historyBuf.toList()) } }
        if (out.historyDone) historyLoading.postValue(false)
    }

    private val detailsLock = Any()
    private val details = mutableMapOf<String, String>()
    private val historyBuf = mutableListOf<com.vanaccess.bms.ble.drivers.HistoryRecord>()

    /** Start downloading the BMS event history (drivers with Feature.HISTORY). */
    fun requestHistory() {
        val d = activeDriver ?: return
        if (com.vanaccess.bms.ble.drivers.Feature.HISTORY !in d.features) return
        synchronized(detailsLock) { historyBuf.clear() }
        bmsHistory.postValue(emptyList())
        historyLoading.postValue(true)
        d.historyCommands()
        handler.postDelayed({ historyLoading.postValue(false) }, 180_000)
    }

    private fun clearDeviceData() {
        synchronized(detailsLock) { details.clear(); historyBuf.clear() }
        deviceDetails.postValue(emptyMap())
        bmsParams.postValue(null)
        bmsHistory.postValue(null)
        historyLoading.postValue(false)
        activeFeatures.postValue(emptySet())
    }

    private fun handleGattDead(gatt: BluetoothGatt) {
        stopPolling()
        writeCharacteristic = null
        notifyCharacteristic = null
        bluetoothGatt = null
        try { gatt.close() } catch (_: Exception) {}
        connectionState.postValue(ConnectionState.DISCONNECTED)
        lastError.postValue("Connection lost — reconnecting…")
        val savedAddress = prefs.selectedDeviceAddress
        if (!savedAddress.isNullOrEmpty()) scheduleReconnect(savedAddress)
    }

    /** Send arbitrary hex bytes to the write characteristic — for reverse engineering. */
    fun sendRawBytes(hexInput: String): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = writeCharacteristic ?: return false
        if (connectionState.value != ConnectionState.CONNECTED) return false
        return try {
            val bytes = hexInput.trim().split("\\s+".toRegex())
                .filter { it.isNotEmpty() }
                .map { it.toInt(16).toByte() }
                .toByteArray()
            char.value = bytes
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(char)
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            val hex = bytes.joinToString(" ") { b -> "%02X".format(b) }
            val entry = "[$ts] TX ${bytes.size}B\n  HEX: $hex"
            val log = rawPacketLog.value?.toMutableList() ?: mutableListOf()
            log.add(0, entry)
            rawPacketLog.postValue(log)
            true
        } catch (e: Exception) {
            lastError.postValue("Send failed: ${e.message}")
            false
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        stopPolling()
        if (is123Bms) {
            // 123BMS: start two-layer auth sequence instead of PQ polling
            start123BmsAuth()
            return
        }
        val driver = activeDriver ?: BmsDrivers.byId("powerqueen")!!.also { activeDriver = it }
        var first = true
        pollRunnable = object : Runnable {
            override fun run() {
                if (connectionState.value != ConnectionState.CONNECTED) return
                val cmds = (if (first) driver.initCommands() else emptyList()) + driver.pollCommands()
                first = false
                cmds.forEachIndexed { i, cmd ->
                    handler.postDelayed({
                        if (pollRunnable === this) writeDriverCommand(cmd)
                    }, i * driver.interCommandDelayMs)
                }
                handler.postDelayed(this, maxOf(driver.pollIntervalMs, cmds.size * driver.interCommandDelayMs + 100))
            }
        }
        handler.post(pollRunnable!!)
    }

    private fun writeDriverCommand(cmd: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        if (connectionState.value != ConnectionState.CONNECTED) return
        logTx(cmd)
        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            val writeResult = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, cmd, writeType)
            } else {
                @Suppress("DEPRECATION")
                char.value = cmd
                char.writeType = writeType
                @Suppress("DEPRECATION")
                if (gatt.writeCharacteristic(char)) 0 else -1
            }
            if (writeResult != 0) android.util.Log.w("BleService", "driver write failed: result=$writeResult")
        } catch (e: android.os.DeadObjectException) {
            handleGattDead(gatt)
        } catch (e: Exception) {
            android.util.Log.w("BleService", "driver write failed: ${e.message}")
            handleGattDead(gatt)
        }
    }

    private fun logTx(cmd: ByteArray) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val txEntry = "[$ts] TX ${cmd.size}B\n  HEX: ${cmd.joinToString(" ") { "%02X".format(it) }}"
        val log = rawPacketLog.value?.toMutableList() ?: mutableListOf()
        log.add(0, txEntry)
        if (log.size > 200) log.subList(200, log.size).clear()
        rawPacketLog.postValue(log)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    // ── 123SmartBMS Connection ────────────────────────────────────────────────

    private fun start123BmsAuth() {
        val pin = prefs.bmsPin
        if (pin.isNullOrBlank()) {
            lastError.postValue("123SmartBMS detected but no PIN configured.\nSet PIN in Settings.")
            return
        }
        smartBmsModuleAuthed = false
        smartBmsBmsAuthed = false
        smartBmsDataReceived = false
        smartBmsRefreshScheduled = false
        smartBmsLineBuffer.clear()
        smartBmsWriteQueue.clear()
        smartBmsWriteInProgress = false

        // Layer 1: send BCD PIN
        val bcdPin = SmartBmsProtocol.pinToBcd(pin)
        android.util.Log.d("BleService", "123BMS: sending BCD PIN ${bcdPin.joinToString(" ") { "%02X".format(it) }}")
        write123BmsChar(bcdPin)

        // Layer 2: send ASCII commands after 300ms
        handler.postDelayed({
            start123BmsAsciiAuth(pin)
        }, 300)
    }

    private fun start123BmsAsciiAuth(pin: String) {
        val authCmd = SmartBmsProtocol.pinToAuthCommand(pin)
        smartBmsWriteQueue.clear()
        smartBmsWriteQueue.add("D!" to SmartBmsProtocol.commandToBytes(SmartBmsProtocol.CMD_STOP_DATA))
        smartBmsWriteQueue.add("PW" to SmartBmsProtocol.commandToBytes(authCmd))
        smartBmsWriteQueue.add("E!" to SmartBmsProtocol.commandToBytes(SmartBmsProtocol.CMD_START_DATA))
        smartBmsWriteInProgress = false
        process123BmsWriteQueue()

        // Retry if no data after 5s
        handler.postDelayed({
            if (!smartBmsDataReceived && connectionState.value == ConnectionState.CONNECTED && is123Bms) {
                android.util.Log.d("BleService", "123BMS: no data after 5s, retrying auth")
                start123BmsAuth()
            }
        }, 5000)
    }

    private fun process123BmsWriteQueue() {
        if (smartBmsWriteInProgress || smartBmsWriteQueue.isEmpty()) return
        smartBmsWriteInProgress = true
        val (label, data) = smartBmsWriteQueue.removeAt(0)
        android.util.Log.d("BleService", "123BMS TX '$label': ${data.joinToString(" ") { "%02X".format(it) }}")
        write123BmsChar(data)
    }

    @Suppress("DEPRECATION")
    private fun write123BmsChar(data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        if (connectionState.value != ConnectionState.CONNECTED) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                char.value = data
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            android.util.Log.w("BleService", "123BMS write failed: ${e.message}")
        }
    }

    private fun handle123BmsWriteComplete() {
        smartBmsWriteInProgress = false
        handler.postDelayed({ process123BmsWriteQueue() }, 100)
    }

    private fun schedule123BmsRefresh() {
        if (smartBmsRefreshScheduled) return
        smartBmsRefreshScheduled = true
        handler.postDelayed({
            smartBmsRefreshScheduled = false
            if (bluetoothGatt != null && connectionState.value == ConnectionState.CONNECTED && is123Bms) {
                start123BmsAuth()
            }
        }, 2000)
    }

    private fun handle123BmsNotification(data: ByteArray) {
        // Module auth response: 0x0D = bridge opened
        if (data.size == 1 && data[0] == SmartBmsProtocol.CR) {
            if (!smartBmsModuleAuthed) {
                smartBmsModuleAuthed = true
                android.util.Log.d("BleService", "123BMS: module auth OK (0x0D)")
            }
            return
        }
        // Single-byte responses
        if (data.size == 1) {
            when (data[0]) {
                SmartBmsProtocol.ACK -> {
                    if (!smartBmsBmsAuthed) smartBmsBmsAuthed = true
                    return
                }
                SmartBmsProtocol.NACK -> {
                    lastError.postValue("123BMS: authentication failed (wrong PIN?)")
                    return
                }
                SmartBmsProtocol.BELL -> return
            }
        }
        // Buffer incoming ASCII and process complete lines
        val text = String(data, Charsets.US_ASCII)
        for (ch in text) {
            if (ch == '\r') {
                val line = smartBmsLineBuffer.toString()
                smartBmsLineBuffer.clear()
                if (line.isNotEmpty()) process123BmsLine(line)
            } else {
                smartBmsLineBuffer.append(ch)
            }
        }
    }

    private fun process123BmsLine(line: String) {
        // Command echoes and responses
        when {
            line.startsWith("D!") || line.startsWith("PW") || line.startsWith("E!") -> return
            line == "OK" -> {
                if (!smartBmsBmsAuthed) smartBmsBmsAuthed = true
                return
            }
            line == "NA" -> return
        }
        // Data lines: single letter + underscore
        if (line.length >= 2 && line[1] == '_' && line[0].isLetter()) {
            smartBmsDataReceived = true
            smartBmsParser.parseLine(line)
            smartBmsParser.state.updateCount++
            smartBmsState.postValue(smartBmsParser.state.copy())
            smartBmsParser.state.let { st ->
                if (st.soc >= 0 && !st.packVoltage.isNaN())
                    alarmMonitor.onReading(st.soc, st.packVoltage, if (st.netCurrent.isNaN()) 0.0 else st.netCurrent)
            }
            schedule123BmsRefresh()
            return
        }
        android.util.Log.d("BleService", "123BMS unknown line: '$line'")
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_conn),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_conn_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    override fun onDestroy() {
        alarmMonitor.release()
        stopScan()
        stopVictronScan()
        disconnect()
        disconnectSwitchPanel()
        super.onDestroy()
    }
}

data class ScannedDevice(
    val name: String,
    val address: String,
    var rssi: Int
)
