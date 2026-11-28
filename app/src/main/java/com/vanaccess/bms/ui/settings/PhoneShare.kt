// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.settings

import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import com.vanaccess.bms.R
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.data.NotifLog
import com.vanaccess.bms.reports.ReportStore
import com.vanaccess.bms.reports.SessionRecorder
import com.vanaccess.bms.sync.SyncClient
import com.vanaccess.bms.ui.AppDialog
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Taking another phone's reports, on the home network, one way: the other phone presses
 * «إرسال إلى هاتف» (it opens with a code, like for the computer), this one «استقبال من هاتف».
 * They keep their battery, so they form that battery's own record here and never mix with ours.
 */
object PhoneShare {

    private fun targets(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.mapNotNull { a -> a.broadcast } }
    }.getOrDefault(emptyList()) + InetAddress.getByName("255.255.255.255")

    fun receive(ctx: Context, onDone: () -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val status = android.widget.TextView(ctx).apply {
            text = ctx.getString(R.string.share_searching); setTextColor(android.graphics.Color.parseColor("#9BA3B8")); textSize = 13f
        }
        val addr = AppDialog.input(ctx, "", ctx.getString(R.string.share_addr_hint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val code = AppDialog.input(ctx, "", ctx.getString(R.string.pc_code), InputType.TYPE_CLASS_NUMBER)
        body.addView(status)
        body.addView(addr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() })
        body.addView(code, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        // Find the sending phone (it answers the computer's discovery the same way)
        Thread {
            val found = runCatching { SyncClient.discover(2500, targets()) }.getOrDefault(emptyList())
            main.post {
                if (found.isEmpty()) status.text = ctx.getString(R.string.share_none)
                else { status.text = ctx.getString(R.string.share_found, found.first().name); addr.setText(found.first().host + ":" + found.first().port) }
            }
        }.start()
        AppDialog.show(ctx, ctx.getString(R.string.share_receive), body = body, buttons = listOf(
            AppDialog.Btn(ctx.getString(R.string.btn_cancel), AppDialog.GREY),
            AppDialog.Btn(ctx.getString(R.string.share_take), AppDialog.GREEN) {
                val a = addr.text.toString().trim(); val c = code.text.toString().filter { it.isDigit() }
                val host = a.substringBefore(':'); val port = a.substringAfter(':', "").toIntOrNull()
                if (host.isEmpty() || port == null || c.length != 6) { AppDialog.info(ctx, ctx.getString(R.string.share_receive), ctx.getString(R.string.share_bad_input)); return@Btn }
                Thread {
                    val msg = runCatching { take(ctx, host, port, c) }.getOrElse { e ->
                        when { e is SyncClient.Refused && e.status == 401 -> ctx.getString(R.string.share_bad_code)
                            e is SyncClient.Refused && e.status == 403 -> ctx.getString(R.string.share_closed)
                            else -> ctx.getString(R.string.share_failed, e.message ?: e.javaClass.simpleName) }
                    }
                    main.post { AppDialog.info(ctx, ctx.getString(R.string.share_receive), msg); onDone() }
                }.start()
            }))
    }

    /** Pulls the other phone's reports; returns what to tell the owner. */
    private fun take(ctx: Context, host: String, port: Int, code: String): String {
        val client = SyncClient(host, port, code, "📱 " + (android.os.Build.MODEL ?: "phone"))
        val bat = client.battery()
        val address = bat.optString("address").ifEmpty { "phone:$host" }
        val name = bat.optString("name").ifEmpty { address }
        val store = ReportStore.get(ctx)
        val list = client.sessions(0)
        // reports of that phone that carry no battery are that phone's battery's
        val added = list.count { s -> store.insertIfNew(s.session.type, s.session.startMs, s.session.endMs,
            s.session.battery.ifEmpty { address }, s.session.data, s.points) }
        val prefs = AppPreferences(ctx)
        // its battery joins the list, to browse its record (our connection stays as it is)
        if (prefs.savedBatteries.none { it.address == address }) {
            val keepFirst = prefs.savedBatteries
            prefs.upsertSavedBattery(address, name, "")
            // keep ours first: the battery we use stays the one in front
            keepFirst.reversed().forEach { prefs.upsertSavedBattery(it.address, it.name, it.protocol) }
        }
        if (added > 0) SessionRecorder.savedCount.postValue((SessionRecorder.savedCount.value ?: 0) + 1)
        val msg = ctx.getString(R.string.share_done, added, list.size, name)
        NotifLog.add(ctx, ctx.getString(R.string.share_receive), msg)
        return msg
    }
}
