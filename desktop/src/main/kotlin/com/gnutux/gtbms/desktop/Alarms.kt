// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import com.vanaccess.bms.alarm.AlarmCheck
import com.vanaccess.bms.alarm.AlarmSnooze
import com.vanaccess.bms.alarm.LowSteps
import com.vanaccess.bms.ble.BmsBasicInfo
import com.vanaccess.bms.reports.ChargeTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/**
 * The phone's alarms on the desktop: full charge, early cell (before the protection cuts), low
 * battery by steps. Shown over the whole window with a sound and a desktop notification; only
 * its buttons stop it. Charging alarms offer "I unplugged it" / "remind me in N min".
 */
class Alarms(private val settings: Settings, private val log: DesktopLog? = null) {
    enum class Kind { HIGH, CELL, LOW }
    enum class Level(val key: String, val color: Long) {
        NORMAL("lvl_normal", 0xFF43D17A), ACCEPTABLE("lvl_acceptable", 0xFF4DA3FF), ATTENTION("lvl_attention", 0xFFF5B83D), DANGER("lvl_danger", 0xFFF0544F)
    }
    class Ring(val kind: Kind, val title: String, val big: String, val text: String, val level: Level, val note: String? = null, val snoozeMin: Int? = null)

    private class State { var armed = true; var snoozeUntil = 0L; var stoppedAt = 0L }
    private val states = Kind.values().associateWith { State() }
    private val _ring = MutableStateFlow<Ring?>(null)
    val ring: StateFlow<Ring?> = _ring
    private var lastSoc = 0
    private var now = 0L
    @Volatile private var sounding = false
    /** No sound and no system notification (pictures taken for checks). */
    var quiet = false

    /** Every reading; [clock] is the time of the reading (the simulator runs faster). */
    fun evaluate(clock: Long, b: BmsBasicInfo, cells: List<Double>, tracker: ChargeTracker) {
        now = clock; lastSoc = b.soc
        val charging = clock - tracker.lastChargingMs < 90_000
        val lim = settings.limits
        val ov = lim.cellOvMv ?: 0
        // Full charge: percentage, or the voltage advised for this battery
        val hv = AlarmCheck.effectiveHighVoltage(0.0, cells.size, ov).takeIf { it > 0 }
            ?: com.vanaccess.bms.reports.ChargeAdvice.from(cells.size, ov)?.alarmPackV ?: 0.0
        val hSoc = settings.highSoc
        check(Kind.HIGH, settings.highEnabled && charging && ((hSoc > 0 && b.soc >= hSoc) || (hv > 0 && b.packVoltage >= hv)),
            (hSoc <= 0 || b.soc < hSoc - 2) && (hv <= 0 || b.packVoltage < hv - 0.5)) {
            Ring(Kind.HIGH, Res.s("alarm_high_title"), "${b.soc}%", Res.s("alarm_high_text", b.soc, b.packVoltage), Level.NORMAL,
                snoozeMin = AlarmSnooze.minutes(b.soc, b.fullCapacity, tracker.live?.watch?.avgCurrent() ?: b.current))
        }
        // Early cell: only for a battery whose protection limit is known
        if (ov > 0 && settings.cellAlarm && cells.size > 1) {
            val limit = tracker.learn.alarmCellV(ov / 1000.0)
            val mx = cells.max()
            check(Kind.CELL, clock - tracker.lastChargingMs < 15_000 && b.current > 0.1 && mx >= limit, mx < limit - 0.05) {
                Ring(Kind.CELL, Res.s("alarm_cell_title"), String.format(Locale.US, "%.2f V", mx),
                    Res.s("alarm_cell_text", cells.indexOf(mx) + 1, mx), Level.ACCEPTABLE, snoozeMin = 3)
            }
        }
        // Low battery: the owner's value, then 20/15/10/5
        val lSoc = settings.lowSoc
        val announced = LowSteps.settle(settings.lowStep, b.soc, lSoc)
        if (announced != settings.lowStep) settings.lowStep = announced
        if (settings.lowEnabled && b.current <= 0.1 && _ring.value == null && LowSteps.due(announced, b.soc, lSoc)) {
            settings.lowStep = LowSteps.at(b.soc, lSoc)
            start(Ring(Kind.LOW, Res.s("alarm_low_title"), "${b.soc}%",
                Res.s(when { b.soc <= 10 -> "alarm_low_text_danger"; b.soc <= 20 -> "alarm_low_text_soon"; else -> "alarm_low_text" }, b.soc, b.packVoltage),
                when { b.soc <= 10 -> Level.DANGER; b.soc <= 20 -> Level.ATTENTION; else -> Level.ACCEPTABLE },
                LowSteps.next(b.soc, lSoc)?.let { Res.s("alarm_low_next", it) }))
        }
    }

    private inline fun check(kind: Kind, hit: Boolean, clear: Boolean, make: () -> Ring) {
        val st = states.getValue(kind)
        if (clear) { st.armed = true; st.snoozeUntil = 0L }
        if (!hit || _ring.value != null) return
        val again = if (st.snoozeUntil > 0) now >= st.snoozeUntil else st.stoppedAt != 0L && now - st.stoppedAt >= 10 * 60_000L
        if (st.armed || again) { st.armed = false; st.snoozeUntil = 0L; start(make()) }
    }

    private fun start(r: Ring) {
        _ring.value = r
        log?.add(r.title, r.text)
        if (quiet) return
        if (settings.systemNotify) notifyDesktop(r)
        if (settings.sound) beep()
    }

    /** A short demo from the settings. */
    fun test(kind: Kind) = start(Ring(kind, Res.s(when (kind) { Kind.HIGH -> "alarm_high_title"; Kind.CELL -> "alarm_cell_title"; Kind.LOW -> "alarm_low_title" }),
        "🔔", Res.s("alarm_test_text"), Level.NORMAL, snoozeMin = if (kind == Kind.LOW) null else 5))

    /** «فصلتُ الشاحن»: no reminder, unless it is still charging two minutes later. */
    fun unplugged() = stop { it.snoozeUntil = now + 2 * 60_000L }

    /** «ذكّرني بعد N د». */
    fun snooze() { val m = _ring.value?.snoozeMin ?: 5; stop { it.snoozeUntil = now + m * 60_000L } }

    /** Low battery: silent until the next step. */
    fun dismiss() = stop { it.stoppedAt = now }

    private fun stop(st: (State) -> Unit) {
        val r = _ring.value ?: return
        st(states.getValue(r.kind))
        sounding = false
        _ring.value = null
        closeNotification()
    }

    /** A short repeating tone, generated (no sound file), until stopped. */
    private fun beep() {
        if (sounding) return
        sounding = true
        Thread {
            runCatching {
                val rate = 44100f
                val line = AudioSystem.getSourceDataLine(AudioFormat(rate, 16, 1, true, false))
                line.open(); line.start()
                val tone = ByteArray((rate * 0.35).toInt() * 2).also { buf ->
                    for (i in 0 until buf.size / 2) {
                        val v = (sin(2 * PI * 880 * i / rate) * 9000 * minOf(1.0, i / 400.0)).toInt()
                        buf[2 * i] = (v and 0xFF).toByte(); buf[2 * i + 1] = (v shr 8).toByte()
                    }
                }
                val silence = ByteArray(tone.size)
                while (sounding) { line.write(tone, 0, tone.size); line.write(silence, 0, silence.size) }
                line.drain(); line.close()
            }
        }.apply { isDaemon = true; start() }
    }

    @Volatile private var notifId: String? = null

    /**
     * The system notification, with the same buttons as the alarm window (notify-send -A): the
     * button chosen there stops or puts off the alarm here. Closed when the alarm stops elsewhere.
     */
    private fun notifyDesktop(r: Ring) = runCatching {
        val acts = if (r.snoozeMin != null) listOf("unplugged=" + Res.s("away_done"), "snooze=" + Res.s("alarm_snooze", r.snoozeMin))
            else listOf("stop=" + Res.s("alarm_stop"))
        val cmd = listOf("notify-send", "-u", "critical", "-a", "GT-BMS", "-i", "battery-caution", "-p") +
            acts.flatMap { listOf("-A", it) } + listOf(r.title, r.text + (r.note?.let { "\n" + it } ?: ""))
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    val t = line.trim()
                    when {
                        t.all { it.isDigit() } && t.isNotEmpty() -> notifId = t
                        _ring.value !== r -> {}
                        t == "unplugged" -> unplugged()
                        t == "snooze" -> snooze()
                        t == "stop" -> dismiss()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun closeNotification() {
        val id = notifId ?: return
        notifId = null
        runCatching {
            ProcessBuilder("gdbus", "call", "--session", "--dest", "org.freedesktop.Notifications", "--object-path",
                "/org/freedesktop/Notifications", "--method", "org.freedesktop.Notifications.CloseNotification", id).start()
        }
    }
}
