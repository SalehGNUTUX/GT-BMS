// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Where a position falls on the picture OsmAnd draws for a GPX. Its interface returns the
 * picture without centre or zoom, so its own computation (TrackBitmapDrawer.createTileBox /
 * RotatedTileBox) is repeated here step by step. From GT-SPEEDOMETER. Pure, unit-tested.
 */
class OsmAndProjection private constructor(
    val zoom: Int, private val cx: Double, private val cy: Double,
    private val pxPerTile: Double, private val ox: Int, private val oy: Int,
) {
    fun x(lon: Double): Float = ((tileX(zoom, lon) - cx) * pxPerTile + ox).toFloat()
    fun y(lat: Double): Float = ((tileY(zoom, lat) - cy) * pxPerTile + oy).toFloat()

    companion object {
        /** @param track (lat, lon); size and density exactly as requested from OsmAnd */
        fun of(track: List<Pair<Double, Double>>, width: Int, height: Int, density: Float): OsmAndProjection? {
            if (track.isEmpty() || width <= 0 || height <= 0) return null
            val minLat = track.minOf { it.first }; val maxLat = track.maxOf { it.first }
            val minLon = track.minOf { it.second }; val maxLon = track.maxOf { it.second }
            val cLat = (maxLat + minLat) / 2.0; val cLon = (minLon + maxLon) / 2.0
            val ppt = 256.0 * density
            val ox = (width * 0.5).toInt(); val oy = (height * 0.5).toInt()
            fun fits(z: Int): Boolean {
                val tx = tileX(z, cLon); val ty = tileY(z, cLat)
                val l = ((tileX(z, minLon) - tx) * ppt + ox).toFloat(); val t = ((tileY(z, maxLat) - ty) * ppt + oy).toFloat()
                val r = ((tileX(z, maxLon) - tx) * ppt + ox).toFloat(); val b = ((tileY(z, minLat) - ty) * ppt + oy).toFloat()
                return l >= 0f && l <= width && t >= 0f && t <= height && r >= 0f && r <= width && b >= 0f && b <= height
            }
            // Same two loops, same order: the first may overshoot by one, the second brings it back
            var z = 15
            while (z < 17 && fits(z)) z++
            while (z >= 7 && !fits(z)) z--
            return OsmAndProjection(z, tileX(z, cLon), tileY(z, cLat), ppt, ox, oy)
        }

        private fun tiles(z: Int) = (1L shl z).toDouble()
        private fun tileX(z: Int, lon: Double): Double {
            val v = (lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * tiles(z)
            return if (v >= tiles(z)) tiles(z) - 0.01 else v
        }
        private fun tileY(z: Int, lat: Double): Double {
            val r = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
            return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * tiles(z)
        }
    }
}
