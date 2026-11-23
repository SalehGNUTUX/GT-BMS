// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.data

import android.content.Context
import android.net.Uri
import com.vanaccess.bms.reports.ReportStore
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup to move GT-BMS to another phone: one ZIP with the reports database (charges,
 * trips, tracks, levels) and every setting. Restoring replaces both.
 */
object Backup {
    private const val DB = "gt_bms_reports.db"
    private const val PREFS = "gt_bms_prefs"

    fun export(ctx: Context, out: Uri) {
        ReportStore.get(ctx).close()                       // flush the database to its file
        val dbFile = ctx.getDatabasePath(DB)
        val prefsFile = File(ctx.applicationInfo.dataDir, "shared_prefs/$PREFS.xml")
        ctx.contentResolver.openOutputStream(out)?.use { os ->
            ZipOutputStream(os).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(JSONObject().put("app", "GT-BMS").put("format", 1)
                    .put("created", System.currentTimeMillis()).toString(2).toByteArray())
                zip.closeEntry()
                if (dbFile.exists()) { zip.putNextEntry(ZipEntry(DB)); dbFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                if (prefsFile.exists()) { zip.putNextEntry(ZipEntry("$PREFS.xml")); prefsFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        } ?: error("cannot write")
    }

    /** Restore; the app must be restarted afterwards (see [restart]). */
    fun import(ctx: Context, input: Uri) {
        var seenManifest = false
        var db: ByteArray? = null
        var prefsXml: ByteArray? = null
        ctx.contentResolver.openInputStream(input)?.use { ins ->
            ZipInputStream(ins).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when (e.name) {
                        "manifest.json" -> seenManifest = JSONObject(zip.readBytes().decodeToString()).optString("app") == "GT-BMS"
                        DB -> db = zip.readBytes()
                        "$PREFS.xml" -> prefsXml = zip.readBytes()
                    }
                }
            }
        } ?: error("cannot read")
        require(seenManifest) { "not a GT-BMS backup" }
        db?.let { bytes ->
            ReportStore.get(ctx).close()
            val f = ctx.getDatabasePath(DB)
            f.parentFile?.mkdirs()
            listOf("-wal", "-shm", "-journal").forEach { File(f.path + it).delete() }
            f.writeBytes(bytes)
        }
        prefsXml?.let { applyPrefs(ctx, it) }
    }

    /** Write the backed-up preferences through the editor (the live SharedPreferences is cached). */
    private fun applyPrefs(ctx: Context, xml: ByteArray) {
        val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        val p = android.util.Xml.newPullParser()
        p.setInput(xml.inputStream(), "UTF-8")
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            val name = p.getAttributeValue(null, "name") ?: continue
            val value = p.getAttributeValue(null, "value")
            when (p.name) {
                "string" -> ed.putString(name, p.nextText())
                "int" -> value?.toIntOrNull()?.let { ed.putInt(name, it) }
                "long" -> value?.toLongOrNull()?.let { ed.putLong(name, it) }
                "float" -> value?.toFloatOrNull()?.let { ed.putFloat(name, it) }
                "boolean" -> value?.let { ed.putBoolean(name, it.toBoolean()) }
            }
        }
        ed.commit()
    }

    /** Relaunch so every component reloads the restored data. */
    fun restart(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        ctx.startActivity(i)
        Runtime.getRuntime().exit(0)
    }
}
