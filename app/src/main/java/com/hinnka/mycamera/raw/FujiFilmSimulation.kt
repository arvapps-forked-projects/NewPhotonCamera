package com.hinnka.mycamera.raw

/** Stable storage keys; mode/renderer IDs verified against the inspected GXUP0008 firmware. */
enum class FujiFilmSimulation(val persistedValue: String, val firmwareMode: Int, val renderer: Int) {
    Provia("provia", 0, 0),
    Velvia("velvia", 3, 4),
    Astia("astia", 1, 5),
    ClassicChrome("classic_chrome", 13, 8),
    ClassicNegative("classic_negative", 19, 10),
    NostalgicNeg("nostalgic_neg", 21, 12),
    RealaAce("reala_ace", 22, 13);

    companion object {
        fun fromPersistedValue(value: String?): FujiFilmSimulation =
            entries.firstOrNull { it.persistedValue.equals(value, ignoreCase = true) } ?: Provia
    }
}
