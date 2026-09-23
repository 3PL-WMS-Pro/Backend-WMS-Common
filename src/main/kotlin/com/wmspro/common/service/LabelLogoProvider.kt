package com.wmspro.common.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * LabelLogoProvider - Supplies a print-safe company logo for warehouse labels.
 *
 * Labels are printed on monochrome thermal printers (203 DPI, 1-bit output). A colour or
 * gradient logo dithers into noise on that hardware, and a white-on-transparent logo - which
 * is what the tenant's web/UI logo asset is - prints as nothing at all on white stock.
 *
 * This provider therefore hard-thresholds the source image to pure opaque black on a fully
 * transparent background. Transparency (rather than white) is deliberate so the logo can be
 * placed over section rules without painting a white box over them.
 *
 * The result is rendered once and cached for the lifetime of the application: label printing
 * happens in batches of hundreds on the warehouse floor and must not re-decode an image per label.
 *
 * Failure is always soft. If the asset is missing or undecodable the label still prints, just
 * without branding - an unbranded label is recoverable, a failed print run is not.
 *
 * NOTE: the logo is currently a bundled classpath asset. Per-tenant branding already exists for
 * GRN/GIN documents via DocumentTemplate.commonConfig.logoUrl, and resolving the label logo from
 * there is the natural follow-up; it is deliberately not done here because it would put a
 * cross-service HTTP fetch in the path of every label batch.
 */
@Service
class LabelLogoProvider(
    @Value("\${wms.label.logo-resource:labels/infinity-logo.png}")
    private val logoResourcePath: String,

    /**
     * Luminance at or below which a source pixel is treated as ink. The default of 190 is chosen
     * to capture both the blue logo mark (luminance ~64) and black wordmark while excluding the
     * white background (255), and to render antialiased edge pixels as ink rather than dropping
     * them, which keeps thin strokes intact at thermal resolution.
     */
    @Value("\${wms.label.logo-ink-threshold:190}")
    private val inkThreshold: Int,

    /**
     * Alpha at or below which a source pixel is treated as fully transparent and never inked.
     */
    @Value("\${wms.label.logo-alpha-threshold:32}")
    private val alphaThreshold: Int
) {
    private val logger = LoggerFactory.getLogger(LabelLogoProvider::class.java)

    /**
     * A print-ready logo: PNG bytes already thresholded and trimmed, plus its true dimensions
     * so layout code can preserve the aspect ratio without decoding the image again.
     */
    data class LabelLogo(
        val pngBytes: ByteArray,
        val widthPx: Int,
        val heightPx: Int
    ) {
        /** Width divided by height. Layout code sets one dimension and derives the other. */
        val aspectRatio: Float get() = widthPx.toFloat() / heightPx.toFloat()

        // Data class holding a ByteArray needs these by hand; the generated versions compare
        // array identity rather than contents.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LabelLogo) return false
            return widthPx == other.widthPx &&
                heightPx == other.heightPx &&
                pngBytes.contentEquals(other.pngBytes)
        }

        override fun hashCode(): Int =
            (pngBytes.contentHashCode() * 31 + widthPx) * 31 + heightPx
    }

    /**
     * Cached print-ready logo, or null when no usable logo is available.
     * Computed on first access; the enclosing Lazy makes initialisation thread-safe.
     */
    private val monoLogo: LabelLogo? by lazy { loadAndConvert() }

    /**
     * Returns the print-safe logo, or null if none is available.
     * Callers must treat null as "render without branding", not as an error.
     */
    fun getLogo(): LabelLogo? = monoLogo

    /**
     * True when a usable logo is available, letting layout code reserve header space only when
     * something will actually be drawn there.
     */
    fun hasLogo(): Boolean = monoLogo != null

    private fun loadAndConvert(): LabelLogo? {
        return try {
            val stream = javaClass.classLoader.getResourceAsStream(logoResourcePath)
            if (stream == null) {
                logger.warn(
                    "Label logo resource '{}' not found on classpath - labels will print without branding",
                    logoResourcePath
                )
                return null
            }

            val source = stream.use { ImageIO.read(it) }
            if (source == null) {
                logger.warn(
                    "Label logo resource '{}' could not be decoded as an image - labels will print without branding",
                    logoResourcePath
                )
                return null
            }

            val mono = toMonochrome(source)

            // Brand assets are usually exported with generous transparent padding - the supplied
            // file carries its mark in only 197x38 of a 312x132 canvas. Left in place, that
            // padding is counted as part of the image when layout code sets a width, so the mark
            // renders far smaller and higher than intended. Trimming makes placement predictable:
            // the caller sizes the mark itself.
            val trimmed = trimTransparentBorder(mono)
            if (trimmed == null) {
                logger.warn(
                    "Label logo '{}' contains no ink after thresholding - labels will print without branding",
                    logoResourcePath
                )
                return null
            }

            val out = ByteArrayOutputStream()
            ImageIO.write(trimmed, "PNG", out)

            logger.info(
                "Loaded label logo '{}' ({}x{}), converted to monochrome and trimmed to {}x{} for thermal printing",
                logoResourcePath, source.width, source.height, trimmed.width, trimmed.height
            )
            LabelLogo(out.toByteArray(), trimmed.width, trimmed.height)
        } catch (e: Exception) {
            // Deliberately broad: no logo failure should ever abort a print run.
            logger.warn("Failed to prepare label logo '{}' - labels will print without branding", logoResourcePath, e)
            null
        }
    }

    /**
     * Hard-thresholds the source to opaque black ink on a transparent background.
     *
     * A hard threshold is used rather than a greyscale conversion because thermal printers are
     * effectively 1-bit; handing them grey values produces dithering artefacts that read as dirt
     * on a small label.
     */
    private fun toMonochrome(source: BufferedImage): BufferedImage {
        val result = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)

        val opaqueBlack = 0xFF000000.toInt()
        val transparent = 0x00000000

        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val argb = source.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xFF

                if (alpha <= alphaThreshold) {
                    result.setRGB(x, y, transparent)
                    continue
                }

                val red = (argb ushr 16) and 0xFF
                val green = (argb ushr 8) and 0xFF
                val blue = argb and 0xFF

                // ITU-R BT.601 luma - matches how the eye weights the channels, so a saturated
                // blue is correctly seen as dark ink rather than a mid tone.
                val luminance = (0.299 * red + 0.587 * green + 0.114 * blue).toInt()

                result.setRGB(x, y, if (luminance <= inkThreshold) opaqueBlack else transparent)
            }
        }

        return result
    }

    /**
     * Crops away fully transparent rows and columns around the ink, returning null if the image
     * contains no ink at all (which would mean the threshold rejected everything - a white-on-
     * transparent logo being the likely cause).
     */
    private fun trimTransparentBorder(image: BufferedImage): BufferedImage? {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alpha = (image.getRGB(x, y) ushr 24) and 0xFF
                if (alpha == 0) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        if (maxX < minX || maxY < minY) return null

        return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}
