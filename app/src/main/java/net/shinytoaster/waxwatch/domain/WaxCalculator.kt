package net.shinytoaster.waxwatch.domain

object WaxCalculator {
    const val BASELINE_WEIGHT_KG = 75.0
    const val WEIGHT_FACTOR = 0.015 // 1.5%
    const val METERS_PER_KM = 1000.0
    const val METERS_PER_MILE = 1609.344

    fun calculateMaxLifeMeters(riderWeightKg: Double, waxType: net.shinytoaster.waxwatch.data.WaxType = net.shinytoaster.waxwatch.data.WaxType.GENERIC_PARAFFIN): Double {
        val weightDiff = BASELINE_WEIGHT_KG - riderWeightKg
        val percentageAdjustment = weightDiff * WEIGHT_FACTOR
        val calculated = waxType.baselineMeters * (1.0 + percentageAdjustment)
        // Ensure we always have at least 1km of life, even for extreme rider weights
        return maxOf(1000.0, calculated)
    }

    fun metersToKm(meters: Double): Double = meters / METERS_PER_KM
    fun kmToMeters(km: Double): Double = km * METERS_PER_KM
    fun metersToMiles(meters: Double): Double = meters / METERS_PER_MILE
    fun milesToMeters(miles: Double): Double = miles * METERS_PER_MILE
}
