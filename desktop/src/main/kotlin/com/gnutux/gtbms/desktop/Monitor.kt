// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import com.vanaccess.bms.ble.BmsBasicInfo
import com.vanaccess.bms.ble.BmsPoller
import com.vanaccess.bms.ble.ReplayLink
import com.vanaccess.bms.ble.ReplayLog
import com.vanaccess.bms.ble.drivers.BwbmDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The battery as the screens see it: last readings, and where they come from. */
class Monitor {
    data class State(val source: String = "", val basic: BmsBasicInfo? = null, val cells: List<Double> = emptyList(), val updatedMs: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var poller: BmsPoller? = null

    /** Plays back a GT-BMS device report (the bundled one when [text] is null). */
    fun startReplay(text: String? = null) {
        stop()
        val log = text ?: Monitor::class.java.getResource("/sample-bwbm-23s.txt")!!.readText()
        val link = ReplayLink(ReplayLog.parse(log))
        _state.value = State(source = Tr["sim"])
        poller = BmsPoller(BwbmDriver(), link) { out ->
            val s = _state.value
            _state.value = s.copy(basic = out.basic ?: s.basic, cells = out.cells ?: s.cells, updatedMs = System.currentTimeMillis())
        }.also { it.start() }
    }

    fun stop() { poller?.stop(); poller = null }
}
