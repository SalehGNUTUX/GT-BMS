// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

import com.vanaccess.bms.ble.drivers.BmsDriver
import com.vanaccess.bms.ble.drivers.DriverOutput
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A connection to a battery, whatever carries it (BlueZ on the desktop, a replayed report…). */
interface BmsLink {
    /** Called for every notification the battery sends (any thread). */
    var onNotify: ((ByteArray) -> Unit)?
    fun write(bytes: ByteArray)
    fun close()
}

/**
 * Asks the battery for its readings the way the phone does: the driver's init commands once,
 * then its poll commands every [BmsDriver.pollIntervalMs], spaced by [BmsDriver.interCommandDelayMs].
 * Only the driver's own read commands are ever sent.
 */
class BmsPoller(private val driver: BmsDriver, private val link: BmsLink, private val onOutput: (DriverOutput) -> Unit) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bms-poll").apply { isDaemon = true } }
    private var task: ScheduledFuture<*>? = null

    fun start() {
        driver.reset()
        link.onNotify = { data -> driver.onNotification(data)?.let { if (!it.isEmpty) onOutput(it) } }
        exec.execute { driver.initCommands().forEach { send(it) } }
        task = exec.scheduleWithFixedDelay({ driver.pollCommands().forEach { send(it) } },
            200, driver.pollIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun send(cmd: ByteArray) {
        runCatching { link.write(cmd) }
        Thread.sleep(driver.interCommandDelayMs)
    }

    fun stop() {
        task?.cancel(false)
        exec.shutdownNow()
        link.onNotify = null
        link.close()
    }
}

/**
 * Plays back a device report as if the battery answered: each command written gets the
 * notifications that followed the same command in the recording, in turn.
 */
class ReplayLink(packets: List<ReplayLog.Packet>, private val gapMs: Long = 15) : BmsLink {
    override var onNotify: ((ByteArray) -> Unit)? = null
    private val answers = HashMap<String, MutableList<List<ByteArray>>>()
    private val next = HashMap<String, Int>()
    @Volatile private var closed = false

    init {
        var key: String? = null
        var group = mutableListOf<ByteArray>()
        fun flush() { key?.let { k -> if (group.isNotEmpty()) answers.getOrPut(k) { mutableListOf() } += group.toList() } }
        packets.forEach { p ->
            if (!p.received) { flush(); key = hex(p.bytes); group = mutableListOf() } else group += p.bytes
        }
        flush()
    }

    /** Commands this recording can answer. */
    val known: Set<String> get() = answers.keys

    override fun write(bytes: ByteArray) {
        val k = hex(bytes)
        val list = answers[k] ?: return
        val i = next.getOrDefault(k, 0)
        next[k] = (i + 1) % list.size
        list[i].forEach { if (!closed) { Thread.sleep(gapMs); onNotify?.invoke(it) } }
    }

    override fun close() { closed = true }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}
