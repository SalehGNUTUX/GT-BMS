// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

import android.content.Context
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.reports.ReportStore
import com.vanaccess.bms.reports.SessionRecorder
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

    override fun yieldLink(minutes: Int): Long = service.yieldLink(minutes)
    override fun resumeLink() = service.resumeLink()
    override fun peer(name: String) = service.syncPeer(name)

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
