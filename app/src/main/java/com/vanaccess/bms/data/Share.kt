// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.data

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.vanaccess.bms.R
import java.io.File
import kotlin.concurrent.thread

/**
 * Sharing the app itself, as in GMD-PHONE / GT-SALAT:
 *  1. a description with version, website, repository and hashtags (for people online);
 *  2. the installed APK itself through messaging apps (for people without internet).
 * The text comes from string resources, so it follows the UI language.
 */
object Share {
    const val SITE = "https://salehgnutux.github.io/GT-BMS/"
    const val REPO = "https://github.com/SalehGNUTUX/GT-BMS"

    fun appText(context: Context): String = buildString {
        append(context.getString(R.string.share_pitch))
        append("\n\n").append(context.getString(R.string.share_version, version(context)))
        append("\n").append(context.getString(R.string.share_site_label)).append(": ").append(SITE)
        append("\n").append(context.getString(R.string.share_repo_label)).append(": ").append(REPO)
        append("\n\n").append(context.getString(R.string.share_hashtags))
    }

    /** Button 1: text + links. */
    fun shareText(context: Context) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject))
            putExtra(Intent.EXTRA_TEXT, appText(context))
        }
        open(context, intent, context.getString(R.string.share_app_text))
    }

    /**
     * Button 2: the installed APK, copied to cache under a versioned name (FileProvider
     * cannot serve the system path, and "base.apk" means nothing to the receiver).
     * [onResult] runs on the main thread with false when the file could not be prepared.
     */
    fun shareApk(context: Context, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        thread(name = "gt-bms-share-apk") {
            val uri = try {
                val source = File(app.applicationInfo.sourceDir)
                val dir = File(app.cacheDir, "shared_apk").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, "GT-BMS-${version(app)}.apk")
                source.copyTo(dest, overwrite = true)
                FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", dest)
            } catch (_: Exception) { null }
            main.post {
                if (uri == null) { onResult(false); return@post }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject))
                    putExtra(Intent.EXTRA_TEXT, appText(context))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onResult(open(context, intent, context.getString(R.string.share_app_apk)))
            }
        }
    }

    private fun version(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) { "" }

    private fun open(context: Context, intent: Intent, title: String): Boolean = try {
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }
}
