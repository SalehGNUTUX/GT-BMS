// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

/**
 * Money side of the energy. The battery counts Wh *into the battery*; the wall meter sees
 * more because the charger loses some as heat, so wall energy = battery energy ÷ efficiency
 * (≈ 85 % for typical e-bike chargers).
 */
object EnergyCost {
    const val CHARGER_EFFICIENCY = 0.85

    /** Cost of [wh] battery-side energy at [pricePerKwh]. */
    fun cost(wh: Double, pricePerKwh: Double): Double = wh / CHARGER_EFFICIENCY / 1000.0 * pricePerKwh

    /** Electric cost per km from consumption Wh/km. */
    fun perKm(whPerKm: Double, pricePerKwh: Double): Double = cost(whPerKm, pricePerKwh)

    /** What [km] would have cost with a fuel vehicle using [litresPer100] at [fuelPrice]. */
    fun fuel(km: Double, litresPer100: Double, fuelPrice: Double): Double = km * litresPer100 / 100.0 * fuelPrice
}
