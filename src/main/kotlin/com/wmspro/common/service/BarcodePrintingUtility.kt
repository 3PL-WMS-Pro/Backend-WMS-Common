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
 *
 * Barcode Dimensions:
 * - Normal Item (ITEM): 25mm × 15mm
 * - Boxes (BOX): 37mm × 26mm
 * - Pallets (PALLET): 100mm × 50mm
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
        logger.info("Generating barcode PDF for itemType: $itemType with ${barcodes.size} barcodes")

        try {
            // Determine dimensions based on item type
            val dimensions = getBarcodeDimensions(itemType)

            // Create PDF in memory
            val outputStream = ByteArrayOutputStream()
            val pdfWriter = PdfWriter(outputStream)
            val pdfDocument = PdfDocument(pdfWriter)
            val document = Document(pdfDocument)

            // Generate a page for each barcode
            barcodes.forEach { barcodeText ->
                addBarcodePage(document, pdfDocument, barcodeText, dimensions)
            }

            document.close()

            // Convert to BASE64
            val pdfBytes = outputStream.toByteArray()
            val base64PDF = Base64.getEncoder().encodeToString(pdfBytes)

            logger.info("Successfully generated barcode PDF with ${barcodes.size} pages")
            return base64PDF

        } catch (e: Exception) {
            logger.error("Error generating barcode PDF for itemType: $itemType", e)
            throw RuntimeException("Failed to generate barcode PDF: ${e.message}", e)
        }
    }

    /**
     * Adds a single barcode page to the PDF document
     */
    private fun addBarcodePage(
        document: Document,
        pdfDocument: PdfDocument,
        barcodeText: String,
        dimensions: BarcodeDimensions
    ) {
        // Set page size based on dimensions
        val pageWidth = dimensions.widthMm * MM_TO_POINTS
        val pageHeight = dimensions.heightMm * MM_TO_POINTS
        val pageSize = PageSize(pageWidth, pageHeight)

        pdfDocument.addNewPage(pageSize)

        // Generate barcode image
        val barcodeImage = generateBarcodeImage(barcodeText, dimensions)

        // Convert BufferedImage to byte array
        val imageBytes = ByteArrayOutputStream()
        ImageIO.write(barcodeImage, "PNG", imageBytes)

        // Add barcode image to PDF
        val imageData = ImageDataFactory.create(imageBytes.toByteArray())
        val image = Image(imageData)

        // Scale image to fit the page while maintaining aspect ratio
        val imageWidth = pageWidth * 0.8f  // 80% of page width
        image.setWidth(imageWidth)
        image.setAutoScale(true)
        image.setHorizontalAlignment(HorizontalAlignment.CENTER)

        document.add(image)

        // Add human-readable text below barcode
        val textParagraph = Paragraph(barcodeText)
            .setTextAlignment(TextAlignment.CENTER)
            .setFontSize(if (dimensions.widthMm > 50) 12f else 8f)
            .setMarginTop(2f)

        document.add(textParagraph)

        logger.debug("Added barcode page for: $barcodeText")
    }

    /**
     * Generates a Code128 barcode image
     */
    private fun generateBarcodeImage(barcodeText: String, dimensions: BarcodeDimensions): BufferedImage {
        val writer = Code128Writer()

        // Calculate pixel dimensions based on DPI
        // For thermal printers, we want high resolution
        val widthPixels = ((dimensions.widthMm / 25.4) * PRINTER_DPI).toInt()
        val heightPixels = ((dimensions.heightMm / 25.4) * PRINTER_DPI).toInt()

        val hints = mapOf(
            EncodeHintType.MARGIN to 1  // Minimal margin for compact printing
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
     * Returns barcode dimensions based on item type
     */
    private fun getBarcodeDimensions(itemType: String): BarcodeDimensions {
        return when (itemType.uppercase()) {
            "ITEM" -> BarcodeDimensions(widthMm = 25f, heightMm = 15f)
            "BOX" -> BarcodeDimensions(widthMm = 37f, heightMm = 26f)
            "PALLET", "PALETTE" -> BarcodeDimensions(widthMm = 100f, heightMm = 50f)
            else -> {
                logger.warn("Unknown item type: $itemType, using default ITEM dimensions")
                BarcodeDimensions(widthMm = 25f, heightMm = 15f)
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
}
