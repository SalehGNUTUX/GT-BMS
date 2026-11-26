package com.vanaccess.bms.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmAndProjectionTest {
    @Test fun routeFallsInsideTheOsmAndPicture() {
        // ~1.3 km in Rabat, picture of 1500×1800 px at density 6
        val track = listOf(33.9700 to -6.8600, 33.9760 to -6.8550, 33.9800 to -6.8500)
        val p = com.vanaccess.bms.map.OsmAndProjection.of(track, 1500, 1800, 6f)!!
        track.forEach { (lat, lon) -> assertTrue(p.x(lon) in 0f..1500f && p.y(lat) in 0f..1800f) }
        assertTrue(p.x(-6.85) > p.x(-6.86)); assertTrue(p.y(33.98) < p.y(33.97))            // east right, north up
        assertEquals(750f, (p.x(-6.86) + p.x(-6.85)) / 2, 1f)
        assertNull(com.vanaccess.bms.map.OsmAndProjection.of(emptyList(), 10, 10, 1f))
    }
}
