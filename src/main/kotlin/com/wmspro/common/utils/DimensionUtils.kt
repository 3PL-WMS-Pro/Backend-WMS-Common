package com.wmspro.common.utils

import kotlin.math.ceil

/**
 * Utility class for dimension-related calculations
 */
object DimensionUtils {

    /**
     * Calculate CBM (Cubic Meter) from dimensions in centimeters
     *
     * Formula: CBM = CEIL((Length × Width × Height) ÷ 1,000,000)
     *
     * The result is always rounded UP to the nearest whole number.
     * Examples:
     * - 4.123 CBM -> 5 CBM
     * - 1.001 CBM -> 2 CBM
     * - 3.0 CBM -> 3 CBM
     *
     * @param lengthCm Length in centimeters
     * @param widthCm Width in centimeters
     * @param heightCm Height in centimeters
     * @return CBM value rounded up to nearest whole number, or null if any dimension is null or non-positive
     */
    fun calculateCBM(lengthCm: Double?, widthCm: Double?, heightCm: Double?): Double? {
        // Return null if any dimension is missing or non-positive
        if (lengthCm == null || widthCm == null || heightCm == null) {
            return null
        }

        if (lengthCm <= 0 || widthCm <= 0 || heightCm <= 0) {
            return null
        }

        // CBM = (L × W × H) ÷ 1,000,000, rounded UP to nearest whole number
        val rawCBM = (lengthCm * widthCm * heightCm) / 1_000_000.0
        return ceil(rawCBM)
    }
}
