package net.shinytoaster.waxwatch.data

enum class SurfaceType(val multiplier: Double, val displayName: String) {
    PAVEMENT(1.0, "Road"),
    MIXED(1.2, "Mixed"),
    GRAVEL(1.5, "Gravel")
}
