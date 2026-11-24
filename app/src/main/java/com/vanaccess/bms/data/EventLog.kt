// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Small local diary of what the background service did (connection, sleep, trips), so a trip
 * that was missed can be explained. Shared only inside the device report the user sends.
 */
object EventLog {
    private const val FILE = "events.log"
    private const val MAX_BYTES = 120_000L
    private val io = Executors.newSingleThreadExecutor()

    fun add(ctx: Context, text: String) {
        val app = ctx.applicationContext; val now = Date()
        io.execute {
            runCatching {
                val f = File(app.filesDir, FILE)
                if (f.length() > MAX_BYTES) f.writeText(f.readLines().takeLast(800).joinToString("\n", postfix = "\n"))
                f.appendText(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(now) + " " + text + "\n")
            }
        }
    }

    /** Written at once: the process is about to end. */
    fun crash(ctx: Context, thread: String, e: Throwable) {
        val trace = generateSequence(e) { it.cause }.take(3).joinToString("\n  caused by ") { c ->
            c.toString().take(300) + c.stackTrace.take(12).joinToString("") { "\n    at $it" } }
        File(ctx.filesDir, FILE).appendText(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + " CRASH [$thread] $trace\n")
    }

    fun tail(ctx: Context, lines: Int): List<String> =
        runCatching { File(ctx.filesDir, FILE).readLines().takeLast(lines) }.getOrDefault(emptyList())
}
