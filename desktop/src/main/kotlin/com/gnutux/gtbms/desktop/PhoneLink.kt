// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import com.vanaccess.bms.reports.Session
import com.vanaccess.bms.sync.SyncClient
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The computer's side of the sync with the phone (home network only): find the phone, then with
 * the code it shows, take its reports and battery values and give it the charges recorded here.
 */
class PhoneLink(private val monitor: Monitor) {
    data class State(val busy: Boolean = false, val found: List<SyncClient.Found> = emptyList(), val message: String = "", val ok: Boolean = true)
    val state = MutableStateFlow(State())

    private fun broadcastTargets(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.mapNotNull { a -> a.broadcast } }
    }.getOrDefault(emptyList()) + InetAddress.getByName("255.255.255.255")

    fun discover() = work {
        val found = SyncClient.discover(2000, broadcastTargets())
        state.value = state.value.copy(found = found)
        if (found.isEmpty()) Tr["syncNone"] to false else String.format(Tr["syncFound"], found.size) to true
    }

    /** Take the phone's reports and values, give it this computer's charges. */
    fun sync(host: String, port: Int, code: String) = work {
        val c = SyncClient(host, port, code.filter { it.isDigit() }, computerName())
        val s = monitor.settings
        s.takeFromPhoneBattery(c.battery())
        val got = c.sessions(s.lastPullMs(host)).count { monitor.store.addIfNew(it) }
        val mine = monitor.store.list(5000).filter { it.data.optString("source") == "desktop" && it.endMs > s.lastPushMs }
        val sent = if (mine.isEmpty()) 0 else c.send(mine.map { monitor.store.synced(it) })
        val now = System.currentTimeMillis()
        s.setLastPull(host, now); s.lastPushMs = now
        s.phoneHost = host; s.phonePort = port
        monitor.reportsVersion.value++
        monitor.onPhoneValues()
        val msg = String.format(Tr["syncDone"], got, sent)
        monitor.log.add(Tr["syncTitle"], msg)
        msg to true
    }

    /** Ask the phone to leave the battery to this computer for [minutes]. */
    fun askYield(host: String, port: Int, code: String, minutes: Int) = work {
        val until = SyncClient(host, port, code.filter { it.isDigit() }, computerName()).yieldLink(minutes)
        val msg = String.format(Tr["yieldDone"], java.text.SimpleDateFormat("HH:mm").format(java.util.Date(until)))
        monitor.log.add(Tr["syncTitle"], msg)
        msg to true
    }

    fun giveBack(host: String, port: Int, code: String) = work {
        SyncClient(host, port, code.filter { it.isDigit() }, computerName()).resumeLink()
        Tr["resumeDone"] to true
    }

    private fun work(job: () -> Pair<String, Boolean>) {
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, message = Tr["syncWorking"])
        Thread {
            val (msg, ok) = runCatching(job).getOrElse { e ->
                (if (e is SyncClient.Refused && e.status == 401) Tr["syncBadCode"] else if (e is SyncClient.Refused && e.status == 403) Tr["syncClosed"]
                else String.format(Tr["syncFail"], e.message ?: e.javaClass.simpleName)) to false
            }
            state.value = state.value.copy(busy = false, message = msg, ok = ok)
        }.apply { isDaemon = true; start() }
    }

    companion object {
        fun computerName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("computer")
    }
}
