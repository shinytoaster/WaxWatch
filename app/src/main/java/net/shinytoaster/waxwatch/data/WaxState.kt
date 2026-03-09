package net.shinytoaster.waxwatch.data

data class WaxState(
    val profileId: String,
    var remainingDistanceMeters: Double,
    var maxLifeMeters: Double,
    var surfaceType: SurfaceType = SurfaceType.PAVEMENT,
    var alertTriggered: Boolean = false
) {
    val remainingPercentage: Double
        get() {
            // Highly defensive calculation to ensure 100% is returned when current == max
            val safeMax = if (maxLifeMeters > 0.1) maxLifeMeters else 1.0
            val safeRemaining = if (remainingDistanceMeters >= 0.0) remainingDistanceMeters else 0.0
            val rawPct = (safeRemaining / safeMax) * 100.0
            return rawPct.coerceIn(0.0, 100.0)
        }
}
