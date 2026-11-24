// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.map

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.content.FileProvider
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.gpx.CreateGpxBitmapParams
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.search.SearchResult
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Asks OsmAnd to draw the map behind a trip, from its own offline maps (its .obf format can
 * only be drawn by OsmAnd itself). Same method as our GT-SPEEDOMETER:
 *  - the connection only lives for one picture (binding starts the whole OsmAnd process);
 *  - binding is not permission: OsmAnd registers a new app *disabled* until the user enables
 *    it in "Connected apps", and registerForOsmandInitListener returning false proves that;
 *  - the answer may never come (no map of the region), so everything has a timeout;
 *  - the GPX is lent through a read permission that is withdrawn right after.
 * Nothing here can bring the app down: no OsmAnd, an old one, or one killed meanwhile = no picture.
 */
object OsmAndBridge {
    enum class State { MISSING, UNREACHABLE, DENIED, NO_MAP, READY }

    /** @param width @param height @param density as requested (needed to project positions on it) */
    class Shot(val bitmap: Bitmap, val detail: Int, val width: Int, val height: Int, val density: Float)
    class Answer(val state: State, val shot: Shot? = null)

    val PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
    private const val ACTION = "net.osmand.aidl.OsmandAidlServiceV2"
    private const val MAX_BYTES = 48L * 1024 * 1024
    private val io = Executors.newSingleThreadExecutor()
    private var cacheKey = ""
    private var cache: Shot? = null

    fun installed(ctx: Context): String? = PACKAGES.firstOrNull {
        runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess
    }

    /** Draw the pieces of [track] for a frame of this size; [done] runs on a background thread. */
    fun render(ctx: Context, track: List<List<Pair<Double, Double>>>, frameW: Int, frameH: Int, color: Int, done: (Answer) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val a = try { draw(app, track, frameW, frameH, color) } catch (_: Throwable) { Answer(State.UNREACHABLE) }
            done(a)
        }
    }

    private fun draw(app: Context, track: List<List<Pair<Double, Double>>>, frameW: Int, frameH: Int, color: Int): Answer {
        val pkg = installed(app) ?: return Answer(State.MISSING)
        if (track.sumOf { it.size } < 2 || frameW <= 0 || frameH <= 0) return Answer(State.NO_MAP)
        // Larger than the frame, with the density raised alike: same content, more detail to zoom into
        var detail = 3
        while (detail > 1 && frameW.toLong() * detail * frameH * detail * 4 > MAX_BYTES) detail--
        val w = frameW * detail; val h = frameH * detail
        val density = app.resources.displayMetrics.density * detail
        val key = "${track.hashCode()}|${track.size}|$w|$h"
        cache?.let { if (cacheKey == key) return Answer(State.READY, it) }

        val file = File(File(app.cacheDir, "exports").apply { mkdirs() }, "osmand-track.gpx")
        file.writeText(gpx(track, color))
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)

        val bound = CountDownLatch(1)
        val service = AtomicReference<IOsmAndAidlInterface?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
                service.set(runCatching { IOsmAndAidlInterface.Stub.asInterface(b) }.getOrNull()); bound.countDown()
            }
            override fun onServiceDisconnected(n: ComponentName?) = Unit
            override fun onNullBinding(n: ComponentName?) = bound.countDown()
            override fun onBindingDied(n: ComponentName?) = bound.countDown()
        }
        try {
            if (!app.bindService(Intent(ACTION).setPackage(pkg), conn, Context.BIND_AUTO_CREATE)) return Answer(State.UNREACHABLE)
            bound.await(4, TimeUnit.SECONDS)
            val api = service.get() ?: return Answer(State.UNREACHABLE)

            val ready = CountDownLatch(1)
            val allowed = api.registerForOsmandInitListener(object : Callback() {
                override fun onAppInitialized() = ready.countDown()
            })
            if (!allowed) return Answer(State.DENIED)
            ready.await(10, TimeUnit.SECONDS)                 // a cold start of OsmAnd takes a while

            val drawn = CountDownLatch(1)
            val picture = AtomicReference<Bitmap?>()
            app.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                val accepted = api.getBitmapForGpx(CreateGpxBitmapParams(uri, density, w, h, color), object : Callback() {
                    override fun onGpxBitmapCreated(b: AGpxBitmap?) {
                        picture.set(runCatching { b?.bitmap }.getOrNull()); drawn.countDown()
                    }
                })
                if (!accepted) return Answer(State.DENIED)
                drawn.await(12, TimeUnit.SECONDS)
            } finally {
                runCatching { app.revokeUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            val bmp = picture.get() ?: return Answer(State.NO_MAP)
            val shot = Shot(bmp, detail, w, h, density)
            cacheKey = key; cache = shot
            return Answer(State.READY, shot)
        } finally {
            runCatching { app.unbindService(conn) }
        }
    }

    private fun gpx(track: List<List<Pair<Double, Double>>>, color: Int) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"GT-BMS\"")
        append(" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:osmand=\"https://osmand.net\">\n  <trk>\n")
        append("    <extensions><osmand:color>").append(String.format(Locale.US, "#%08X", color)).append("</osmand:color></extensions>\n")
        track.forEach { seg ->
            append("    <trkseg>\n")
            seg.forEach { (lat, lon) -> append(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\" />\n", lat, lon)) }
            append("    </trkseg>\n")
        }
        append("  </trk>\n</gpx>\n")
    }

    private open class Callback : IOsmAndAidlCallback.Stub() {
        override fun onSearchComplete(r: MutableList<SearchResult>?) = Unit
        override fun onUpdate() = Unit
        override fun onAppInitialized() = Unit
        override fun onGpxBitmapCreated(b: AGpxBitmap?) = Unit
        override fun updateNavigationInfo(d: ADirectionInfo?) = Unit
        override fun onContextMenuButtonClicked(b: Int, p: String?, l: String?) = Unit
        override fun onVoiceRouterNotify(p: OnVoiceNavigationParams?) = Unit
        override fun onKeyEvent(p: KeyEvent?) = Unit
        override fun onLogcatMessage(p: OnLogcatMessageParams?) = Unit
    }
}
