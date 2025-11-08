package com.wmspro.common.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.HorizontalAlignment
import com.itextpdf.layout.properties.TextAlignment
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

/**
 * BarcodePrintingUtility - Generates PDF documents with scannable Code128 barcodes
 *
 * This utility is used across multiple WMS services for printing barcodes on thermal printers.
 * Each barcode is rendered on a separate page with dimensions based on item type.
 * All barcodes are oriented horizontally (landscape) for better space utilization.
 *
 * Barcode Dimensions (Width × Height - Landscape):
 * - Normal Item (ITEM): 37.29mm × 25.93mm
 * - Boxes (BOX): 105mm × 74mm (A7 landscape)
 * - Pallets (PALLET): 210mm × 148mm (A5 landscape)
 *
 * @return BASE64 encoded PDF string
 */
@Service
class BarcodePrintingUtility {
    private val logger = LoggerFactory.getLogger(BarcodePrintingUtility::class.java)

    // DPI for thermal printers (203 DPI is standard for most thermal label printers)
    private val PRINTER_DPI = 203

    // Millimeters to points conversion (1mm = 2.83465 points)
    private val MM_TO_POINTS = 2.83465f

    /**
     * Generates a PDF with scannable barcodes for all provided barcode strings
     *
     * @param itemType The type of item (ITEM, BOX, PALLET) - determines barcode dimensions
     * @param barcodes List of barcode strings to generate
     * @return BASE64 encoded PDF string
     */
    fun generateBarcodePDF(itemType: String, barcodes: List<String>): String {
        // Convert to BarcodeInfo objects without SKU names (backward compatibility)
        val barcodeInfoList = barcodes.map { BarcodeInfo(barcodeText = it, skuName = null) }
        return generateBarcodePDFWithInfo(itemType, barcodeInfoList)
    }

    /**
     * Generates a PDF with scannable barcodes with additional SKU information
     *
     * @param itemType The type of item (ITEM, BOX, PALLET) - determines barcode dimensions
     * @param barcodeInfoList List of barcode information including optional SKU names
     * @return BASE64 encoded PDF string
     */
    fun generateBarcodePDFWithInfo(itemType: String, barcodeInfoList: List<BarcodeInfo>): String {
        logger.info("Generating barcode PDF for itemType: $itemType with ${barcodeInfoList.size} barcodes")

        try {
            // Determine dimensions based on item type
            val dimensions = getBarcodeDimensions(itemType)

            // Create PDF in memory
            val outputStream = ByteArrayOutputStream()
            val pdfWriter = PdfWriter(outputStream)
            val pdfDocument = PdfDocument(pdfWriter)

            // Set page size for the document (all pages will have same size based on itemType)
            val pageWidth = dimensions.widthMm * MM_TO_POINTS
            val pageHeight = dimensions.heightMm * MM_TO_POINTS
            val pageSize = PageSize(pageWidth, pageHeight)
            val document = Document(pdfDocument, pageSize)

            // Set very minimal margins for maximum space utilization
            document.setMargins(2f, 2f, 2f, 2f)

            // Generate a page for each barcode
            barcodeInfoList.forEachIndexed { index, barcodeInfo ->
                // Add page break before each barcode (except the first one)
                if (index > 0) {
                    document.add(com.itextpdf.layout.element.AreaBreak(pageSize))
                }
                addBarcodeContent(document, barcodeInfo, dimensions, pageWidth, pageHeight, itemType)
            }

            document.close()

            // Convert to BASE64
            val pdfBytes = outputStream.toByteArray()
            val base64PDF = Base64.getEncoder().encodeToString(pdfBytes)

            logger.info("Successfully generated barcode PDF with ${barcodeInfoList.size} pages")
            return base64PDF

        } catch (e: Exception) {
            logger.error("Error generating barcode PDF for itemType: $itemType", e)
            throw RuntimeException("Failed to generate barcode PDF: ${e.message}", e)
        }
    }

    /**
     * Adds barcode content (image + text) to the current page
     * Properly centered and sized for thermal label printing
     * For SKU items, includes SKU name below the barcode number
     */
    private fun addBarcodeContent(
        document: Document,
        barcodeInfo: BarcodeInfo,
        dimensions: BarcodeDimensions,
        pageWidth: Float,
        pageHeight: Float,
        itemType: String
    ) {
        val barcodeText = barcodeInfo.barcodeText

        // Generate barcode image
        val barcodeImage = generateBarcodeImage(barcodeText, dimensions)

        // Convert BufferedImage to byte array
        val imageBytes = ByteArrayOutputStream()
        ImageIO.write(barcodeImage, "PNG", imageBytes)

        // Add barcode image to PDF
        val imageData = ImageDataFactory.create(imageBytes.toByteArray())
        val image = Image(imageData)

        // Font size based on label type
        val fontSize = when {
            dimensions.widthMm > 150 -> 16f  // PALLET
            dimensions.widthMm > 80 -> 12f   // BOX
            else -> 8f                       // ITEM - increased for readability
        }

        // Determine if we should show SKU name (only for SKU_ITEM/ITEM types)
        val showSkuName = (itemType.uppercase() == "SKU_ITEM" || itemType.uppercase() == "ITEM")
                          && !barcodeInfo.skuName.isNullOrBlank()

        // Calculate proper dimensions for maximum space utilization
        // Account for document margins (2pt on each side = 4pt total)
        val usableWidth = pageWidth - 4f
        val usableHeight = pageHeight - 4f

        // Text area dimensions
        val barcodeTextHeight = fontSize * 2.5f  // Barcode text area
        val skuNameHeight = if (showSkuName) fontSize * 2.0f else 0f  // SKU name area (slightly smaller)

        // Gap between components
        val gap = 2f

        // Barcode dimensions - use remaining space
        val totalTextHeight = barcodeTextHeight + skuNameHeight + (if (showSkuName) gap else 0f)
        val barcodeHeight = usableHeight - totalTextHeight - gap
        val barcodeWidth = usableWidth

        // Calculate vertical centering
        val totalContentHeight = if (showSkuName) {
            barcodeHeight + gap + barcodeTextHeight + gap + skuNameHeight
        } else {
            barcodeHeight + gap + barcodeTextHeight
        }
        val verticalPadding = (usableHeight - totalContentHeight) / 2f

        // Set image dimensions to fill available space
        // CRITICAL: Use setAutoScale(false) to force exact dimensions
        // The ZXing barcode image has padding, and auto-scale shrinks it
        image.setWidth(barcodeWidth)
        image.setHeight(barcodeHeight)
        image.setAutoScale(false)  // FORCE exact dimensions, ignore aspect ratio
        image.setHorizontalAlignment(HorizontalAlignment.CENTER)
        image.setMarginTop(Math.max(verticalPadding, 0f))  // Center vertically
        image.setMarginBottom(gap)

        // Add barcode image
        document.add(image)

        // Add human-readable barcode text below barcode - bold and centered
        val textParagraph = Paragraph(barcodeText)
            .setTextAlignment(TextAlignment.CENTER)
            .setFontSize(fontSize)
            .setMarginTop(0f)
            .setMarginBottom(if (showSkuName) gap else 0f)
            .setBold()

        document.add(textParagraph)

        // Add SKU name at the bottom (if available)
        if (showSkuName) {
            val skuNameParagraph = Paragraph(barcodeInfo.skuName)
                .setTextAlignment(TextAlignment.CENTER)
                .setFontSize(fontSize * 0.85f)  // Slightly smaller than barcode text
                .setMarginTop(0f)
                .setMarginBottom(0f)
                .setBold()

            document.add(skuNameParagraph)
        }

        logger.debug("Added barcode for: $barcodeText with SKU name: ${barcodeInfo.skuName ?: "N/A"} (barcode: ${barcodeWidth}x${barcodeHeight}pt, page: ${pageWidth}x${pageHeight}pt)")
    }

    /**
     * Generates a Code128 barcode image
     * Optimized for horizontal scanning with proper aspect ratio
     */
    private fun generateBarcodeImage(barcodeText: String, dimensions: BarcodeDimensions): BufferedImage {
        val writer = Code128Writer()

        // Calculate pixel dimensions based on DPI
        // For thermal printers, we want high resolution
        val widthPixels = ((dimensions.widthMm / 25.4) * PRINTER_DPI).toInt()

        // For CODE_128, height should be proportional to create a horizontal barcode
        // Small items need short barcodes (horizontal look but with good scannability), larger items can be taller
        val heightPixels = when {
            dimensions.widthMm < 50 -> {
                // Small items: Use absolute pixel height for horizontal barcode with good scannability
                // Increased from 10mm to 13mm for better space utilization
                ((13.0 / 25.4) * PRINTER_DPI).toInt()  // 13mm height = ~104 pixels
            }
            dimensions.widthMm < 120 -> {
                // Medium items: 35% of page height
                ((dimensions.heightMm / 25.4) * PRINTER_DPI * 0.35).toInt()
            }
            else -> {
                // Large items: 40% of page height
                ((dimensions.heightMm / 25.4) * PRINTER_DPI * 0.40).toInt()
            }
        }

        val hints = mapOf(
            EncodeHintType.MARGIN to 1  // Minimal margin for maximum barcode size
        )

        val bitMatrix: BitMatrix = writer.encode(
            barcodeText,
            BarcodeFormat.CODE_128,
            widthPixels,
            heightPixels,
            hints
        )

        return MatrixToImageWriter.toBufferedImage(bitMatrix)
    }

    /**
     * Returns barcode dimensions based on item type (all in landscape orientation)
     */
    private fun getBarcodeDimensions(itemType: String): BarcodeDimensions {
        return when (itemType.uppercase()) {
            "ITEM", "SKU_ITEM" -> BarcodeDimensions(widthMm = 37.29f, heightMm = 25.93f)
            "BOX" -> BarcodeDimensions(widthMm = 105f, heightMm = 74f)  // A7 landscape
            "PALLET" -> BarcodeDimensions(widthMm = 210f, heightMm = 148f)  // A5 landscape
            else -> {
                logger.warn("Unknown item type: $itemType, using default ITEM dimensions")
                BarcodeDimensions(widthMm = 37.29f, heightMm = 25.93f)
            }
        }
    }

    /**
     * Data class to hold barcode dimensions
     */
    private data class BarcodeDimensions(
        val widthMm: Float,
        val heightMm: Float
    )

    /**
     * Data class to hold barcode information including optional SKU name
     *
     * @param barcodeText The barcode string to encode (required)
     * @param skuName The SKU name to display above the barcode (optional, only for SKU_ITEM/ITEM types)
     */
    data class BarcodeInfo(
        val barcodeText: String,
        val skuName: String? = null
    )
}
