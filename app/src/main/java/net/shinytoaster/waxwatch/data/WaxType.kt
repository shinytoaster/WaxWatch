package net.shinytoaster.waxwatch.data

enum class WaxType(val baselineMeters: Double, val displayName: String) {
    GENERIC_PARAFFIN(350_000.0, "Generic Paraffin"),
    PARAFFIN_TEFLON(450_000.0, "Paraffin + Teflon (PTFE)"),
    CERAMICSPEED_UFO(600_000.0, "CeramicSpeed UFO"),
    MOLTEN_SPEED_WAX(650_000.0, "Molten Speed Wax"),
    SILCA_SECRET(800_000.0, "Silca Secret Chain Blend"),
    ABSOLUTE_BLACK(550_000.0, "Absolute Black Graphenwax")
}
