// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

import android.content.Context
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.reports.ReportStore
import com.vanaccess.bms.reports.SessionRecorder
import com.vanaccess.bms.sync.SyncCodec
import com.vanaccess.bms.sync.SyncServer
import com.vanaccess.bms.sync.SyncedSession
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** What the phone gives the computer during a sync (see core SyncServer). */
class PhoneSync(private val ctx: Context, private val service: BleService) : SyncServer.Source {
    private val prefs = AppPreferences(ctx)

    override fun sessions(sinceMs: Long): List<SyncedSession> {
        val store = ReportStore.get(ctx)
        return store.list(null, 5000).filter { it.endMs > sinceMs }.map { SyncedSession(it, store.points(it.id)) }
    }

    override fun accept(list: List<SyncedSession>): Int {
        val store = ReportStore.get(ctx)
        val added = list.count { store.insertIfNew(it.session.type, it.session.startMs, it.session.endMs, it.session.battery, it.session.data, it.points) }
        if (added > 0) SessionRecorder.savedCount.postValue((SessionRecorder.savedCount.value ?: 0) + 1)
        return added
    }

    /** This battery's limits and alarm values, so the computer judges it the same way. */
    override fun battery(): JSONObject = JSONObject()
        .put("address", prefs.selectedDeviceAddress ?: "").put("name", prefs.selectedDeviceName ?: "")
        .put("bms_limits", prefs.limits.toJson()).put("bms_cell_ov_mv", prefs.knownOvMv).put("cells", prefs.bmsCellCount)
        .put("alarm_high_enabled", prefs.alarmHighEnabled).put("alarm_high_soc", prefs.alarmHighSoc)
        .put("alarm_low_enabled", prefs.alarmLowEnabled).put("alarm_low_soc", prefs.alarmLowSoc)
        // shown the same way on the computer: energy cost, fuel comparison, the Live design
        .put("price_kwh", prefs.pricePerKwh.toDouble()).put("currency", prefs.currency)
        .put("fuel_price", prefs.fuelPrice.toDouble()).put("fuel_l100", prefs.fuelL100.toDouble()).put("live_design", prefs.liveDesign)
        .put("price_book", prefs.priceBook ?: "")

    override fun yieldLink(minutes: Int): Long = service.yieldLink(minutes)
    override fun resumeLink() = service.resumeLink()
    override fun peer(name: String) = service.syncPeer(name)

    /** The reading on the phone's screen now, for the computer to watch the battery through the phone. */
    override fun live(): SyncCodec.Live? {
        val b = BleService.basicInfo.value ?: return null
        return SyncCodec.Live(BleService.lastDataMs, prefs.selectedDeviceName ?: "", prefs.selectedDeviceAddress ?: "",
            BleService.connectionState.value == BleService.ConnectionState.CONNECTED, b, BleService.cellVoltages.value ?: emptyList())
    }

    override fun notifs(sinceMs: Long): List<SyncCodec.Notif> =
        com.vanaccess.bms.data.NotifLog.all(ctx).filter { it.tMs > sinceMs }.map { SyncCodec.Notif(it.tMs, it.title, it.text) }

    companion object {
        /** The phone's address on the home network (Wi-Fi first). */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
                .firstOrNull()?.hostAddress
        }.getOrNull()
    }
}
