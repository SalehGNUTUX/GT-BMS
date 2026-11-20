package com.vanaccess.bms.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versionOrdering() {
        assertTrue(Updater.isNewer("v1.0.1", "1.0.0"))
        assertTrue(Updater.isNewer("v1.1.0", "1.0.9"))
        assertTrue(Updater.isNewer("v1.0.0", "1.0.0-beta.2"))
        assertTrue(Updater.isNewer("v1.0.0-beta.10", "1.0.0-beta.9"))
        assertFalse(Updater.isNewer("v1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("v0.9.9", "1.0.0"))
    }
}
