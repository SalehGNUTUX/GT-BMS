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

    // Notifications and alarm texts in the language chosen in Settings (Android < 13)
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.vanaccess.bms.data.AppLanguage.wrap(base))
    }

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

        /** The running service, if any. */
        @Volatile var instance: BleService? = null
            private set

        /**
         * After a crash / force-stop / reboot: show the last saved reading (dimmed, with its age)
         * instead of an empty screen, until the battery sends fresh data.
         */
        fun restoreLastReading(ctx: android.content.Context) {
            if (basicInfo.value != null) return
            val addr = com.vanaccess.bms.data.AppPreferences(ctx).selectedDeviceAddress
            val snap = com.vanaccess.bms.data.LastReading.load(ctx, addr) ?: return
            basicInfo.value = snap.basic
            if (cellVoltages.value == null) cellVoltages.value = snap.cells
            if (lastDataMs < snap.timeMs) lastDataMs = snap.timeMs
        }
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
        /** Wall-clock time of the last BMS reading (0 = none yet), for "last update: N min ago". */
        @Volatile var lastDataMs: Long = 0L
        /** Guided capacity test (Reports → Levels). */
        val capacityTest = MutableLiveData<com.vanaccess.bms.reports.CapacityTest.State>()
        /** Cells that sit lowest over time (CellTracker), for the Cells tab. */
        val cellStats = MutableLiveData(com.vanaccess.bms.reports.CellTracker.Report())
        private var lastDataPersistMs = 0L
        /** Device identity (model, serials, clock…) keyed by DetailKey. */
        val deviceDetails = MutableLiveData<Map<String, String>>(emptyMap())
        val bmsParams = MutableLiveData<List<com.vanaccess.bms.ble.drivers.ParamItem>?>(null)
        val bmsHistory = MutableLiveData<List<com.vanaccess.bms.ble.drivers.HistoryRecord>?>(null)
        val historyLoading = MutableLiveData(false)
        /** Bumped when the history cache file was rewritten. */
        val historyCacheSaved = MutableLiveData(0L)
        val activeFeatures = MutableLiveData<Set<com.vanaccess.bms.ble.drivers.Feature>>(emptySet())

        /** How long a protocol candidate may stay silent before the next one is tried. */
        const val PROBE_TIMEOUT_MS = 4500L

        const val ACTION_STOP_ALARM = "com.gnutux.gtbms.STOP_ALARM"
        const val ACTION_TEST_ALARM = "com.gnutux.gtbms.TEST_ALARM"
        const val ACTION_ALARM_UNPLUGGED = "com.gnutux.gtbms.ALARM_UNPLUGGED"
        const val ACTION_ALARM_SNOOZE = "com.gnutux.gtbms.ALARM_SNOOZE"
        const val ACTION_ALARM_RESHOW = "com.gnutux.gtbms.ALARM_RESHOW"
        const val ACTION_RESUME_LINK = "com.gnutux.gtbms.RESUME_LINK"
        private const val YIELD_NOTIF = 4015
        /** The sync with the computer while it is open (code, where to reach the phone, until when, who came). */
        data class SyncState(val code: String, val address: String, val untilMs: Long, val peer: String?)
        val syncState = MutableLiveData<SyncState?>(null)
        /** The battery's link is left to the computer until then; 0 = not. */
        val linkYield = MutableLiveData(0L)
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
    private lateinit var recorder: com.vanaccess.bms.reports.SessionRecorder

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
        instance = this
        prefs.monitoring = true
        log("service created")
        if (lastDataMs == 0L) lastDataMs = prefs.lastDataMs
        restoreLastReading(this)
        capacityTest.postValue(com.vanaccess.bms.reports.CapacityTest.State.fromJson(prefs.capacityTestJson))
        cellStats.postValue(cellReport(com.vanaccess.bms.reports.CellTracker.fromJson(prefs.cellStatsJson)))
        recorder = com.vanaccess.bms.reports.SessionRecorder(this, prefs)
        if (prefs.knownOvMv > 0) recorder.cellOvLimitV = prefs.knownOvMv / 1000.0
        recorder.onSaved = {
            log("saved ${it.type} ${(it.endMs - it.startMs) / 1000}s ${it.i("soc_start")}-${it.i("soc_end")}%" +
                if (it.b("energy_partial")) " partial" else "")
            com.vanaccess.bms.ui.AdviceText.notifySession(this, it)
            if (it.type == com.vanaccess.bms.reports.Session.TYPE_CHARGE) loadChargeLearn()
        }
        recorder.onChargeStop = {
            // A cut seen on a BMS that doesn't report its limit teaches it
            if (it.reason == com.vanaccess.bms.reports.ChargeWatch.Stop.PROTECTION && it.maxCell > 1 && prefs.bmsCellOvMv == 0 &&
                (it.alarm?.contains("ell overvoltage") == true)) {
                prefs.cellOvSeenMv = Math.round(it.maxCell * 1000).toInt()
                recorder.cellOvLimitV = it.maxCell
            }
            com.vanaccess.bms.ui.AdviceText.notifyChargeStop(this, it)
        }
        recorder.log = ::log
        recorder.onChargePlan = { info, p ->
            ChargeTimer.update(this, com.vanaccess.bms.reports.ChargePlan.make(prefs.selectedDeviceAddress ?: "", System.currentTimeMillis(),
                info.soc, info.remainCapacity, info.fullCapacity, com.vanaccess.bms.reports.SessionRecorder.chargeWatch?.avgCurrent() ?: info.current,
                prefs.alarmHighSoc, p.hours, p.kind == com.vanaccess.bms.reports.ChargePredictor.Kind.FULL))
        }
        recorder.onChargeBegin = { prefs.chargePlanDone = false }
        recorder.onChargeEnd = { ChargeTimer.cancel(this) }
        recorder.onGpsProblem = { com.vanaccess.bms.ui.GpsNotice.show(this, it) }
        recorder.onGpsOk = { com.vanaccess.bms.ui.GpsNotice.clear(this) }
        recorder.restore()
        backfillRegen()
        loadChargeLearn()
        keepAlive = KeepAlive(this, { connectionState.value == ConnectionState.CONNECTED }) { why ->
            log("wake: $why, ${connectionState.value}")
            wake(brief = true)
        }
        keepAlive.start()
        androidx.core.content.ContextCompat.registerReceiver(this, btReceiver,
            android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.post(object : Runnable {
            override fun run() { recorder.onTick(); checkLink(); keepAlive.tick(); com.vanaccess.bms.ui.AdviceText.maybeDaily(this@BleService, recorder.tripActive); handler.postDelayed(this, 10_000L) }
        })
        // Periodic background update check while the service runs (UpdateChecker throttles to 12 h)
        handler.post(object : Runnable {
            override fun run() {
                com.vanaccess.bms.update.UpdateChecker.checkIfDue(this@BleService)
                basicInfo.value?.let { b ->
                    com.vanaccess.bms.ui.AdviceText.maybeWeekly(this@BleService, b.fullCapacity, b.cycleCount)
                }
                com.vanaccess.bms.ui.AdviceText.maybeBalanceReminder(this@BleService)
                handler.postDelayed(this, 60 * 60 * 1000L)
            }
        })
        createNotificationChannel()
        startForegroundTyped(buildNotification(getString(R.string.app_name), getString(R.string.notif_initializing)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_ALARM -> { alarmMonitor.stopAlarm(); return START_STICKY }
            ACTION_ALARM_UNPLUGGED -> { alarmMonitor.unplugged(); return START_STICKY }
            ACTION_ALARM_SNOOZE -> { alarmMonitor.snooze(); return START_STICKY }
            ACTION_ALARM_RESHOW -> { alarmMonitor.reshow(); return START_STICKY }
            ACTION_RESUME_LINK -> { resumeLink(); return START_STICKY }
            ChargeTimer.ACTION_RING -> {
                // (a service just started for this goes on to look for the battery too)
                alarmMonitor.ringAway(intent.getStringExtra("text") ?: "")
                if (bluetoothGatt != null) return START_STICKY
            }
            ACTION_TEST_ALARM -> {
                val kind = runCatching { BatteryAlarmMonitor.Kind.valueOf(intent.getStringExtra("kind") ?: "HIGH") }
                    .getOrDefault(BatteryAlarmMonitor.Kind.HIGH)
                alarmMonitor.testAlarm(kind); return START_STICKY
            }
        }
        if (bluetoothGatt != null) return START_STICKY  // already running
        // Restore battery type from prefs
        val savedType = prefs.batteryType
        is123Bms = savedType == "123bms"
        batteryType.postValue(savedType)
        val savedAddress = prefs.selectedDeviceAddress
        val yielded = prefs.linkYieldUntil
        if (yielded > System.currentTimeMillis()) {
            // still left to the computer: come back at the time agreed
            userDisconnected = true
            linkYield.postValue(yielded)
            handler.postDelayed(resumeRunnable, yielded - System.currentTimeMillis())
        } else if (!savedAddress.isNullOrEmpty()) {
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

    private fun log(text: String) = com.vanaccess.bms.data.EventLog.add(this, text)

    private lateinit var keepAlive: KeepAlive
    /** Failed attempts in a row; after a few, Android waits for the battery by itself. */
    private var attempts = 0
    private var passiveLink = false
    private var userDisconnected = false

    /** Bluetooth was switched back on: pending connections are lost with it. */
    private val btReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON)
                handler.postDelayed({ wake() }, 1500)
        }
    }

    fun stopRinging() = alarmMonitor.stopAlarm()
    fun alarmUnplugged() = alarmMonitor.unplugged()
    fun alarmSnooze() = alarmMonitor.snooze()

    fun onKeepAliveAlarm() = keepAlive.onAlarm()

    // ── The computer: sync on the home network, and leaving it the battery's link ──

    private var sync: com.vanaccess.bms.sync.SyncServer? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private val closeSyncRunnable = Runnable { closeSyncNow() }
    private val resumeRunnable = Runnable { resumeLink() }

    /** Open to the computer for [com.vanaccess.bms.sync.SyncProtocol.OPEN_MS], with a fresh code. */
    fun openSync() {
        handler.post {
            closeSyncNow()
            val s = com.vanaccess.bms.sync.SyncServer(PhoneSync(applicationContext, this), android.os.Build.MODEL ?: "phone")
            runCatching { s.start() }.onFailure { log("sync: $it"); return@post }
            sync = s
            // Some phones drop broadcast datagrams while the screen is off unless asked
            multicastLock = runCatching {
                (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                    .createMulticastLock("gtbms-sync").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            val until = System.currentTimeMillis() + com.vanaccess.bms.sync.SyncProtocol.OPEN_MS
            syncState.value = SyncState(s.code, (PhoneSync.localAddress() ?: "?") + ":" + s.port, until, null)
            handler.postDelayed(closeSyncRunnable, com.vanaccess.bms.sync.SyncProtocol.OPEN_MS)
            log("sync open")
        }
    }

    fun closeSync() { handler.post { closeSyncNow() } }

    private fun closeSyncNow() {
        handler.removeCallbacks(closeSyncRunnable)
        sync?.stop(); sync = null
        runCatching { multicastLock?.release() }; multicastLock = null
        if (syncState.value != null) { syncState.value = null; log("sync closed") }
    }

    /** A computer identified itself during the sync. */
    fun syncPeer(name: String) { syncState.value?.let { if (it.peer != name) syncState.postValue(it.copy(peer = name)) } }

    /**
     * Leave the battery's Bluetooth link to the computer (most BMSs take one connection): close
     * what runs, disconnect, stay away for [minutes], then come back by itself.
     */
    fun yieldLink(minutes: Int): Long {
        val until = System.currentTimeMillis() + minutes * 60_000L
        handler.post {
            prefs.linkYieldUntil = until
            linkYield.value = until
            recorder.closeSessions()
            disconnect()
            handler.removeCallbacks(resumeRunnable)
            handler.postDelayed(resumeRunnable, until - System.currentTimeMillis())
            showYieldNotice(until)
            log("link left to the computer for $minutes min")
        }
        return until
    }

    fun resumeLink() {
        handler.post {
            handler.removeCallbacks(resumeRunnable)
            val was = prefs.linkYieldUntil != 0L
            prefs.linkYieldUntil = 0L
            linkYield.value = 0L
            getSystemService(NotificationManager::class.java).cancel(YIELD_NOTIF)
            if (was) log("link taken back from the computer")
            prefs.selectedDeviceAddress?.takeIf { it.isNotEmpty() }?.let { connectToDevice(it) }
        }
    }

    private fun showYieldNotice(until: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("gt_bms_computer", getString(R.string.pc_channel), NotificationManager.IMPORTANCE_LOW))
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(until))
        val text = getString(R.string.pc_yielded_until, hm)
        val resume = android.app.PendingIntent.getService(this, 90, Intent(this, BleService::class.java).setAction(ACTION_RESUME_LINK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(this, "gt_bms_computer").setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(getString(R.string.pc_yield_title)).setContentText(text).setOngoing(true)
            .addAction(0, getString(R.string.pc_resume), resume).build()
        runCatching { nm.notify(YIELD_NOTIF, n) }
        com.vanaccess.bms.data.NotifLog.add(this, getString(R.string.pc_yield_title), text)
    }

    /**
     * The phone woke up or the app came to the screen: look for the battery. [brief] = it will
     * sleep again in a moment, so leave Android waiting for the battery rather than start
     * attempts that could not finish.
     */
    fun wake(brief: Boolean = false) {
        handler.post {
            val address = prefs.selectedDeviceAddress
            if (address.isNullOrEmpty() || userDisconnected || isScanning) return@post
            if (connectionState.value == ConnectionState.CONNECTED) return@post      // checkLink watches it
            val idle = android.os.SystemClock.uptimeMillis() - linkStateAt > 15_000L
            if (brief) {
                if (!passiveLink) { attempts = 3; openLink(address) }
            } else if (passiveLink || idle) {
                attempts = 0
                openLink(address)
            }
        }
    }

    /** What past charges teach (early cell alarm, time left). */
    private fun loadChargeLearn() {
        val ctx = applicationContext
        Thread {
            runCatching {
                val store = com.vanaccess.bms.reports.ReportStore.get(ctx)
                val charges = store.list(com.vanaccess.bms.reports.Session.TYPE_CHARGE, 30, prefs.selectedDeviceAddress)
                com.vanaccess.bms.reports.SessionRecorder.chargeLearn =
                    com.vanaccess.bms.reports.ChargeLearn.from(charges, prefs.bmsCellCount) { store.points(it.id) }
            }
        }.start()
    }

    /** Asked for: always a direct attempt. */
    fun connectToDevice(address: String) {
        attempts = 0
        openLink(address)
    }

    private fun openLink(address: String) {
        stopScan()
        userDisconnected = false
        cancelReconnect()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        if (address != lastConnectedAddress) {
            // (right after a start the values are this battery's own last reading: keep them)
            if (lastConnectedAddress != null) {
                clearDeviceData()
                basicInfo.postValue(null)
                cellVoltages.postValue(null)
                recorder.switchBattery()
            }
            lastConnectedAddress = address
            cellTracker = null; capTest = null; learnedCache = null; learnedAtMs = 0L; seenMax = 0.0
            handler.post {
                if (prefs.knownOvMv > 0) recorder.cellOvLimitV = prefs.knownOvMv / 1000.0
                capacityTest.value = com.vanaccess.bms.reports.CapacityTest.State.fromJson(prefs.capacityTestJson)
                cellStats.value = cellReport(com.vanaccess.bms.reports.CellTracker.fromJson(prefs.cellStatsJson))
                loadChargeLearn()
            }
        }
        connectionState.postValue(ConnectionState.CONNECTING)
        linkStateAt = android.os.SystemClock.uptimeMillis()
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

        // A direct attempt gives up after 30 s and needs the phone awake. Once a few failed, let
        // Android wait for the battery (autoConnect): it also works while the phone sleeps. During
        // a trip keep trying directly: the phone is awake and the battery is right there.
        passiveLink = attempts >= 3 && !recorder.tripActive
        if (attempts <= 3) log("connect #${attempts + 1}" + if (passiveLink) " (passive)" else "")
        attempts++
        bluetoothGatt = device.connectGatt(this, passiveLink, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        userDisconnected = true
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
        updateNotification(getString(R.string.st_disconnected), "")
    }

    private fun scheduleReconnect(address: String) {
        cancelReconnect()
        reconnectRunnable = Runnable {
            if (connectionState.value != ConnectionState.CONNECTED) {
                connectionState.postValue(ConnectionState.RECONNECTING)
                updateNotification(getString(R.string.st_reconnecting), address)
                openLink(address)
            }
        }.also { handler.postDelayed(it, 2000) }
    }

    @Volatile private var lastRxAt = 0L            // uptimeMillis of the last notification
    @Volatile private var linkStateAt = 0L         // …and of the last connect attempt / connection

    /** Last signal strength, to tell a weak signal from interference when the link is lost. */
    @Volatile private var rssi = 0
    @Volatile private var rssiAt = 0L

    /**
     * Android can leave a link that is "connected" but mute, or an attempt that never returns.
     * Nothing would ever reconnect then, so restart the connection.
     */
    private fun checkLink() {
        if (connectionState.value == ConnectionState.CONNECTED && driverLocked) runCatching { bluetoothGatt?.readRemoteRssi() }
        val address = prefs.selectedDeviceAddress
        if (address.isNullOrEmpty() || linkStateAt == 0L) return
        val now = android.os.SystemClock.uptimeMillis()       // awake time: a sleep is not a mute link
        val dead = when (connectionState.value) {
            ConnectionState.CONNECTED -> driverLocked && !is123Bms && now - maxOf(lastRxAt, linkStateAt) > 20_000L
            // (a passive connection may wait for hours; that is its job)
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING ->
                now - linkStateAt > 40_000L && (!passiveLink || recorder.tripActive)
            else -> false
        }
        if (!dead) return
        log("link stalled (${connectionState.value}), restarting it")
        stopPolling()
        cancelProbe()
        driverLocked = false
        openLink(address)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // ── GATT Callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // Late news from a connection that was already replaced
            bluetoothGatt?.let { if (it !== gatt) { runCatching { gatt.close() }; return } }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linkStateAt = android.os.SystemClock.uptimeMillis()
                    attempts = 0; passiveLink = false
                    keepAlive.hold(60_000L)            // time for services, protocol and the first readings
                    log("connected")
                    connectionState.postValue(ConnectionState.CONNECTED)
                    updateNotification(getString(R.string.st_connected), gatt.device.name ?: gatt.device.address)
                    // Request a larger MTU; onMtuChanged fires then we discover services
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectionState.value == ConnectionState.CONNECTED) log("disconnected, status $status" +
                        if (rssi != 0) ", signal $rssi dBm ${(android.os.SystemClock.uptimeMillis() - rssiAt) / 1000}s before" else "")
                    rssi = 0
                    // Stay awake for the direct attempts and for handing over to the passive one
                    keepAlive.hold()
                    stopPolling()
                    cancelProbe()
                    driverLocked = false
                    writeCharacteristic = null
                    notifyCharacteristic = null
                    connectionState.postValue(ConnectionState.DISCONNECTED)
                    updateNotification(getString(R.string.st_disconnected), getString(R.string.notif_auto_reconnecting))
                    // Auto reconnect if we have a saved address
                    val savedAddress = prefs.selectedDeviceAddress
                    if (!savedAddress.isNullOrEmpty()) {
                        scheduleReconnect(savedAddress)
                    }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) { rssi = value; rssiAt = android.os.SystemClock.uptimeMillis() }
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
            // Kept for the whole connection: the more exchanges per second, the more chances to get
            // through before the link times out. "Balanced" (1.7.0-beta.2) went from 3 to 59 losses
            // per hour of riding.
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
        lastRxAt = android.os.SystemClock.uptimeMillis()
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
            recorder.onBattery(bluetoothGatt?.device?.address)
            android.util.Log.i("BleService", "Protocol locked: ${driver.id}")
        }
        publishReading(out.basic, out.cells)
        out.hardware?.let { hardwareInfo.postValue(it) }
        out.details?.let { d -> synchronized(detailsLock) { details.putAll(d); deviceDetails.postValue(details.toMap()) } }
        out.params?.let { p ->
            bmsParams.postValue(p)
            // Cell over-voltage protection limit drives charge reports ("ended by protection")
            p.firstOrNull { it.label == "Cell overvoltage protection (level 1)" }?.value
                ?.substringBefore(' ')?.toIntOrNull()?.let { mv ->
                    recorder.cellOvLimitV = mv / 1000.0
                    prefs.bmsCellOvMv = mv
                }
            p.firstOrNull { it.label == "Cell count" }?.value?.toIntOrNull()?.let { prefs.bmsCellCount = it }
            prefs.bmsLimitsJson = com.vanaccess.bms.reports.BmsLimits.fromParams(p).toJson()
        }
        out.history?.let { h -> synchronized(detailsLock) { historyBuf.addAll(h); bmsHistory.postValue(historyBuf.toList()) } }
        if (out.historyDone) {
            historyLoading.postValue(false)
            saveHistoryCache()
        }
    }

    private val detailsLock = Any()
    private val details = mutableMapOf<String, String>()
    private val historyBuf = mutableListOf<com.vanaccess.bms.ble.drivers.HistoryRecord>()

    /** Keep the downloaded history on the phone (per battery) so the tab shows it next time. */
    private fun saveHistoryCache() {
        val list = synchronized(detailsLock) { historyBuf.toList() }
        com.vanaccess.bms.reports.HistoryCache.save(this, bluetoothGatt?.device?.address ?: prefs.selectedDeviceAddress, list)
        historyCacheSaved.postValue(System.currentTimeMillis())
    }

    /** Start downloading the BMS event history (drivers with Feature.HISTORY). */
    fun requestHistory() {
        val d = activeDriver ?: return
        if (com.vanaccess.bms.ble.drivers.Feature.HISTORY !in d.features) return
        synchronized(detailsLock) { historyBuf.clear() }
        bmsHistory.postValue(emptyList())
        historyLoading.postValue(true)
        d.historyCommands()
        handler.postDelayed({
            if (historyLoading.value == true) { historyLoading.postValue(false); saveHistoryCache() }
        }, 180_000)
    }

    /**
     * One place for every live reading (all drivers and the 123SmartBMS bridge): screen,
     * widget/notification, alarms, reports, cell tracking.
     */
    private var lastSnapshotMs = 0L

    private fun publishReading(basic: BmsBasicInfo?, cells: List<Double>?) {
        basic?.let {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastDataPersistMs >= 30_000L) { prefs.lastDataMs = nowMs; lastDataPersistMs = nowMs }
            lastDataMs = nowMs
            basicInfo.postValue(it)
            // Local copy for after a crash / force-stop (every 10 s, atomic)
            if (nowMs - lastSnapshotMs >= 10_000L) {
                lastSnapshotMs = nowMs
                prefs.selectedDeviceAddress?.let { addr ->
                    val snap = com.vanaccess.bms.data.LastReading.Snapshot(addr, nowMs, it, cells ?: cellVoltages.value)
                    Thread { com.vanaccess.bms.data.LastReading.save(applicationContext, snap) }.start()
                }
            }
            publishLive(it, nowMs)
            cells?.let { c -> recorder.onCells(c) }     // before the reading they belong to
            alarmMonitor.onReading(it.soc, it.packVoltage, it.current)
            recorder.onReading(it)
            stepCapacityTest(it, cells ?: cellVoltages.value)
        }
        cells?.let {
            cellVoltages.postValue(it)
            if (basic == null) recorder.onCells(it)
            val b = basic ?: basicInfo.value
            b?.let { bi -> learnModel(bi, it); trackCells(it, bi.current, bi.soc); alarmMonitor.onCells(it, bi.current, bi.soc) }
        }
    }

    private var seenMax = 0.0
    private var lastModelMs = 0L

    /** Recognise the battery: cell count and chemistry, for BMSs that don't report them. */
    private fun learnModel(info: BmsBasicInfo, cells: List<Double>) {
        val now = System.currentTimeMillis()
        if (now - lastModelMs < 10_000L || cells.size < 2) return
        lastModelMs = now
        if (prefs.bmsCellCount != cells.size) prefs.bmsCellCount = cells.size
        if (prefs.bmsCellOvMv > 0) return
        val avg = cells.average()
        if (seenMax == 0.0) seenMax = prefs.cellSeenMax.toDouble()
        if (avg > seenMax + 0.005) { seenMax = avg; prefs.cellSeenMax = avg.toFloat() }
        com.vanaccess.bms.reports.BatteryModel.chemistry(seenMax, avg, info.soc, kotlin.math.abs(info.current) < 1.0)
            ?.let { if (it != prefs.seenChemistry) { prefs.seenChemistry = it; log("chemistry: $it") } }
    }

    /** Once: recovered energy of the trips saved before it was measured, from their points. */
    private fun backfillRegen() {
        if (prefs.regenBackfilledV >= com.vanaccess.bms.reports.RegenMeter.VERSION) return
        Thread {
            runCatching {
                val store = com.vanaccess.bms.reports.ReportStore.get(this)
                store.list(com.vanaccess.bms.reports.Session.TYPE_TRIP, 5000).filter { it.data.optInt("regen_v") < com.vanaccess.bms.reports.RegenMeter.VERSION }.forEach { s ->
                    com.vanaccess.bms.reports.RegenMeter.fromPoints(store.points(s.id)).write(s.data)
                    store.updateData(s.id, s.data)
                }
                prefs.regenBackfilledV = com.vanaccess.bms.reports.RegenMeter.VERSION
            }.onFailure { log("regen backfill: $it") }
        }.start()
    }

    // ── Guided capacity test ─────────────────────────────────────────────────

    private var capTest: com.vanaccess.bms.reports.CapacityTest.State? = null
    private var capSavedMs = 0L

    fun startCapacityTest() {
        capTest = com.vanaccess.bms.reports.CapacityTest.start(System.currentTimeMillis()).also { saveCapTest(it) }
    }

    fun cancelCapacityTest() {
        capTest = com.vanaccess.bms.reports.CapacityTest.State().also { saveCapTest(it) }
    }

    private fun saveCapTest(st: com.vanaccess.bms.reports.CapacityTest.State) {
        prefs.capacityTestJson = st.toJson(); capSavedMs = System.currentTimeMillis()
        capacityTest.postValue(st)
    }

    private fun stepCapacityTest(info: BmsBasicInfo, cells: List<Double>?) {
        val st = capTest ?: com.vanaccess.bms.reports.CapacityTest.State.fromJson(prefs.capacityTestJson).also { capTest = it }
        if (st.phase !in setOf(com.vanaccess.bms.reports.CapacityTest.Phase.DISCHARGE, com.vanaccess.bms.reports.CapacityTest.Phase.READY, com.vanaccess.bms.reports.CapacityTest.Phase.CHARGING)) return
        val lim = prefs.limits
        val lowV = (lim.socLowCalMv ?: if (lim.lion) 3300 else if (lim.lfp) 3050 else lim.cellUvMv ?: 2500) / 1000.0
        val ovV = (lim.cellOvMv ?: 4500) / 1000.0
        val now = System.currentTimeMillis()
        // The counting begins with a charger, not with a moment of braking
        val amps = if (st.phase == com.vanaccess.bms.reports.CapacityTest.Phase.READY && info.current > 0 &&
            now - com.vanaccess.bms.reports.SessionRecorder.lastChargingMs > 5_000L) 0.0 else info.current
        val next = com.vanaccess.bms.reports.CapacityTest.update(st, now, info.soc, amps, cells?.minOrNull() ?: 0.0, cells?.maxOrNull() ?: 0.0,
            info.chargeFetOn, lowV, ovV)
        capTest = next
        if (next.phase != st.phase) {
            saveCapTest(next)
            when (next.phase) {
                com.vanaccess.bms.reports.CapacityTest.Phase.READY -> com.vanaccess.bms.ui.AdviceText.notifyPlain(this, 5,
                    getString(R.string.captest_title), getString(R.string.captest_ready_notify))
                com.vanaccess.bms.reports.CapacityTest.Phase.DONE -> com.vanaccess.bms.ui.AdviceText.notifyPlain(this, 5,
                    getString(R.string.captest_title), getString(R.string.captest_done_notify, next.resultAh ?: 0.0))
                com.vanaccess.bms.reports.CapacityTest.Phase.FAILED -> com.vanaccess.bms.ui.AdviceText.notifyPlain(this, 5,
                    getString(R.string.captest_title), getString(R.string.captest_failed))
                else -> {}
            }
        } else if (now - capSavedMs >= 30_000L) saveCapTest(next)
    }

    /** Stop the history download; keep (and cache) what has been read so far. */
    fun cancelHistory() {
        activeDriver?.cancelHistory()
        if (historyLoading.value == true) {
            historyLoading.postValue(false)
            saveHistoryCache()
        }
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
            // 123SmartBMS joins the common pipeline (new battery screen, reports, alarms, widget)
            smartBmsParser.state.toBasicInfo()?.let { info ->
                publishReading(info, smartBmsParser.state.cellVoltagesList().takeIf { it.size >= 2 })
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

    // ── Long-term cell tracking (weak-cell detection) ─────────────────────────

    private var cellTracker: com.vanaccess.bms.reports.CellTracker? = null
    private var lastCellSampleMs = 0L
    private var lastCellSaveMs = 0L

    private fun cellReport(t: com.vanaccess.bms.reports.CellTracker) = t.report(prefs.alarmDeltaRestMv)

    private fun trackCells(cells: List<Double>, current: Double, soc: Int) {
        val now = System.currentTimeMillis()
        if (now - lastCellSampleMs < 30_000L) return
        lastCellSampleMs = now
        val addr = bluetoothGatt?.device?.address ?: return
        val t = cellTracker ?: run {
            prefs.cellStatsAddress = addr              // (kept per battery)
            com.vanaccess.bms.reports.CellTracker.fromJson(prefs.cellStatsJson)
        }.also { cellTracker = it }
        t.update(cells, current, prefs.vehicle.loadCurrentA, soc)
        if (now - lastCellSaveMs >= 5 * 60_000L) { lastCellSaveMs = now; prefs.cellStatsJson = t.toJson() }
        cellStats.postValue(cellReport(t))
    }

    // ── Live summary: persistent notification + home-screen widget ─────────────

    private var lastNotifyMs = 0L
    private var lastWidgetMs = 0L
    @Volatile private var learnedCache: Pair<Double, Double>? = null
    private var learnedAtMs = 0L

    /** Range for vehicle profiles (same estimator as the Live tab). */
    private fun rangeKm(info: BmsBasicInfo): Double? {
        val v = prefs.vehicle
        if (!v.mobile) return null
        val now = System.currentTimeMillis()
        if (now - learnedAtMs > 15 * 60_000L) {
            learnedAtMs = now
            Thread { learnedCache = try { com.vanaccess.bms.reports.ReportStore.get(this).learnedConsumption(prefs.selectedDeviceAddress) } catch (_: Exception) { null } }.start()
        }
        return com.vanaccess.bms.reports.RangeEstimator.estimate(info.remainCapacity, info.fullCapacity, info.packVoltage,
            prefs.bmsCellCount, com.vanaccess.bms.reports.BatteryModel.nominalCell(prefs.chemistry), prefs.consumptionWhKm.toDouble(), learnedCache,
            v.defaultWhPerKm, info.temperatures.minOrNull())?.km
    }

    /** Live values in the ongoing notification (every 10 s) and the widget (every 30 s). */
    private fun publishLive(info: BmsBasicInfo, now: Long) {
        val range = rangeKm(info)
        if (now - lastNotifyMs >= 10_000L) {
            lastNotifyMs = now
            val state = getString(when {
                com.vanaccess.bms.reports.SessionRecorder.regen(info.current) -> R.string.st_regen
                info.current > 0.1 -> R.string.st_charging
                info.current < -0.1 -> R.string.st_discharging
                else -> R.string.st_idle
            })
            val title = String.format(java.util.Locale.US, "🔋 %d%%  •  %.2f V", info.soc, info.packVoltage)
            val text = String.format(java.util.Locale.US, "%s  •  %+.1f A  •  %.0f W", state, info.current,
                kotlin.math.abs(info.powerWatts)) + (range?.let { String.format(java.util.Locale.US, "  •  ≈ %.0f km", it) } ?: "")
            updateNotification(title, text)
        }
        if (now - lastWidgetMs >= 30_000L) {
            lastWidgetMs = now
            prefs.liveSummary = AppPreferences.LiveSummary(info.soc, info.packVoltage, info.current, range, now)
            com.vanaccess.bms.widget.BatteryWidget.refresh(this)
        }
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    /**
     * Foreground with "connectedDevice", plus "location" when the permission is granted,
     * so trip GPS keeps working with the screen off. Android may refuse the location type
     * when started from the background; then BLE monitoring alone continues.
     */
    private fun startForegroundTyped(n: android.app.Notification) {
        val hasLoc = androidx.core.content.ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val base = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            androidx.core.app.ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
                if (hasLoc) base or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base)
        } catch (e: Exception) {
            androidx.core.app.ServiceCompat.startForeground(this, NOTIFICATION_ID, n, base)
        }
    }

    /** Manual "End trip" from the battery screen. */
    fun endTripNow() = recorder.endTripNow()

    /** Called by the UI after the location permission is granted. */
    fun refreshForegroundType() {
        lastNotifyMs = 0L          // the next reading puts the live text back at once
        startForegroundTyped(buildNotification(getString(R.string.app_name),
            BleService.activeDriverName.value ?: getString(R.string.notif_initializing)))
    }

    override fun onDestroy() {
        closeSyncNow()
        log("service destroyed")
        prefs.monitoring = false
        instance = null
        keepAlive.stop()
        runCatching { unregisterReceiver(btReceiver) }
        cellTracker?.let { prefs.cellStatsJson = it.toJson() }
        recorder.release()
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
