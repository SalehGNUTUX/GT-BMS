// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms

import android.app.Application
import com.vanaccess.bms.data.EventLog

/** Writes an unexpected stop to the local event log (device report), then lets Android handle it. */
class GtBmsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val system = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { EventLog.crash(this, t.name, e) }
            system?.uncaughtException(t, e)
        }
    }
}
