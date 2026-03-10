package net.shinytoaster.waxwatch.data

enum class WaxType(val baselineMeters: Double, val displayName: String) {
    GENERIC_PARAFFIN(350_000.0, "Generic Paraffin"),
    WEND_WAX_ON(300_000.0, "Wend Wax-On"),
    PARAFFIN_TEFLON(450_000.0, "Paraffin + Teflon (PTFE)"),
    ABSOLUTE_BLACK(450_000.0, "Absolute Black Graphenwax"),
    CERAMICSPEED_UFO(600_000.0, "CeramicSpeed UFO Wax"),
    REX_BLACK_DIAMOND(650_000.0, "Rex Black Diamond"),
    MOLTEN_SPEED_WAX(650_000.0, "Molten Speed Wax"),
    SILCA_SECRET(800_000.0, "Silca Secret Chain Blend"),
    SQUIRT_HOT_WAX(800_000.0, "Squirt Hot Wax")
}
