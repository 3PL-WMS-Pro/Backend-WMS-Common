package com.wmspro.common.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.oned.Code128Writer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Renders Code 128 barcode images sized for thermal label printing.
 *
 * Scannability is entirely a function of how wide the narrowest bar ends up on paper, so the
 * rule this class exists to enforce is that images are generated at the printer's native
 * resolution for the exact width they will occupy. When an image is generated at one size and
 * then scaled by the PDF layer, bar edges land between dots and the printer rounds them
 * inconsistently - a barcode that looks fine on screen and reads unreliably on a scanner.
 */
@Service
class BarcodeImageGenerator {
    private val logger = LoggerFactory.getLogger(BarcodeImageGenerator::class.java)

    companion object {
        /** Native resolution of the thermal printers in use. */
        const val PRINTER_DPI = 203

        const val MM_PER_INCH = 25.4

        /**
         * Quiet zone either side of the barcode, in modules.
         *
         * The Code 128 specification requires at least 10x the narrow-bar width. Squeezing this
         * buys a slightly wider barcode at the cost of scanners failing to find the symbol's
         * start at all, which is the least recoverable kind of label defect.
         */
        const val QUIET_ZONE_MODULES = 10

        /**
         * Below roughly two printer dots per module, adjacent bars merge at 203 DPI. Used to warn
         * rather than to fail: a marginal barcode still prints, and the human-readable value
         * underneath it remains a fallback.
         */
        const val MIN_DOTS_PER_MODULE = 2.0
    }

    /**
     * Generates a barcode image intended to be placed at exactly [widthMm] on the page.
     *
     * @param content the value to encode
     * @param widthMm the printed width the image will occupy
     * @param heightMm the printed height the image will occupy
     */
    fun generate(content: String, widthMm: Float, heightMm: Float): BufferedImage {
        require(content.isNotBlank()) { "Cannot generate a barcode for an empty value" }

        val widthPx = mmToDots(widthMm)
        val heightPx = mmToDots(heightMm)

        val matrix = Code128Writer().encode(
            content,
            BarcodeFormat.CODE_128,
            widthPx,
            heightPx,
            mapOf(EncodeHintType.MARGIN to QUIET_ZONE_MODULES)
        )

        warnIfModulesTooNarrow(content, matrix.width, widthPx)

        return MatrixToImageWriter.toBufferedImage(matrix)
    }

    /**
     * Convenience wrapper returning PNG bytes, for callers embedding into a PDF.
     */
    fun generatePng(content: String, widthMm: Float, heightMm: Float): ByteArray {
        val image = generate(content, widthMm, heightMm)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", out)
        return out.toByteArray()
    }

    /** Converts millimetres to whole printer dots at [PRINTER_DPI]. */
    fun mmToDots(mm: Float): Int = ((mm / MM_PER_INCH) * PRINTER_DPI).toInt()

    /**
     * ZXing widens the bitmap past the requested width rather than dropping modules when a value
     * is too long to fit. That is the right behaviour, but it means the caller asked for
     * something impossible, so it is worth saying so - long SKU codes on small labels are
     * exactly how a barcode quietly becomes unscannable.
     */
    private fun warnIfModulesTooNarrow(content: String, matrixWidth: Int, requestedWidthPx: Int) {
        if (matrixWidth <= 0) return

        if (matrixWidth > requestedWidthPx) {
            logger.warn(
                "Barcode '{}' needs {}px but only {}px was allocated; it will be scaled down and " +
                    "may not scan. Consider a wider label or a shorter code.",
                content, matrixWidth, requestedWidthPx
            )
            return
        }

        // matrixWidth is in dots, and each module is at least one dot wide; the ratio of the
        // allocated width to the module count approximates dots per module.
        val moduleCount = estimateModuleCount(content)
        if (moduleCount <= 0) return

        val dotsPerModule = requestedWidthPx.toDouble() / moduleCount
        if (dotsPerModule < MIN_DOTS_PER_MODULE) {
            logger.warn(
                "Barcode '{}' resolves to {} dots per module at {} DPI, below the {} needed for " +
                    "reliable scanning. The printed symbol may be unreadable.",
                content, "%.2f".format(dotsPerModule), PRINTER_DPI, MIN_DOTS_PER_MODULE
            )
        }
    }

    /**
     * Approximate module count for a Code 128 symbol: 11 modules per encoded character, plus the
     * start character, checksum and stop pattern. Good enough to spot a label that is badly
     * undersized; exact widths come from the encoder itself.
     */
    private fun estimateModuleCount(content: String): Int =
        (content.length * 11) + 35 + (QUIET_ZONE_MODULES * 2)
}
