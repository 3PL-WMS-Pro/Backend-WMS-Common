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
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.properties.HorizontalAlignment
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.VerticalAlignment
import com.itextpdf.layout.properties.UnitValue
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
            // Top/Bottom: 3pt for spacing, Left/Right: 0.5pt for maximum barcode width
            document.setMargins(3f, 0.5f, 3f, 0.5f)

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
     * For BOX/PALLET items, includes account name and received date below the barcode number
     */
    private fun addBarcodeContent(
        document: Document,
        barcodeInfo: BarcodeInfo,
        dimensions: BarcodeDimensions,
        pageWidth: Float,
        pageHeight: Float,
        itemType: String
    ) {
        // For BOX/PALLET, use structured layout with section dividers
        if (itemType.uppercase() == "BOX" || itemType.uppercase() == "PALLET") {
            addStructuredBarcodeContent(document, barcodeInfo, dimensions, pageWidth, pageHeight, itemType)
            return
        }

        // For SKU_ITEM, use compact layout (original implementation)
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
        val fontSize = 5.5f  // ITEM - optimized for compact layout

        // Determine if we should show SKU name (only for SKU_ITEM/ITEM types)
        val showSkuName = (itemType.uppercase() == "SKU_ITEM" || itemType.uppercase() == "ITEM")
                          && !barcodeInfo.skuName.isNullOrBlank()

        // Calculate proper dimensions for maximum space utilization
        // Account for document margins (0.5pt left/right = 1pt total, 3pt top/bottom = 6pt total)
        val usableWidth = pageWidth - 1f  // Maximum barcode width
        val usableHeight = pageHeight - 6f

        // Text area dimensions - ultra compact layout
        val barcodeTextHeight = fontSize * 1.5f  // Barcode text area (minimal)
        val skuNameHeight = if (showSkuName) fontSize * 1.3f else 0f  // SKU name area (minimal)

        // Gap between components
        val gap = 2f  // Gap after barcode image
        val skuNameGap = 0f  // NO gap between barcode text and SKU name

        // Barcode dimensions - use remaining space
        val totalTextHeight = barcodeTextHeight + skuNameHeight + skuNameGap
        val barcodeHeight = usableHeight - totalTextHeight - gap
        val barcodeWidth = usableWidth

        // Calculate vertical layout - bottom-aligned text
        val totalContentHeight = barcodeHeight + gap + barcodeTextHeight + skuNameHeight + skuNameGap
        val remainingSpace = usableHeight - totalContentHeight

        // Use Table layout for precise vertical positioning
        val table = Table(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setBorder(null)
            .setPadding(0f)
            .setMargin(0f)
            .setHorizontalBorderSpacing(0f)  // Remove horizontal spacing
            .setVerticalBorderSpacing(0f)     // Remove vertical spacing between cells

        // Row 1: Spacer (pushes content down)
        if (remainingSpace > 0) {
            val spacerCell = Cell()
                .add(Paragraph(""))
                .setHeight(remainingSpace)
                .setBorder(null)
                .setPadding(0f)
            table.addCell(spacerCell)
        }

        // Row 2: Barcode image
        image.setWidth(barcodeWidth)
        image.setHeight(barcodeHeight)
        image.setAutoScale(false)
        image.setHorizontalAlignment(HorizontalAlignment.CENTER)

        val barcodeCell = Cell()
            .add(image)
            .setHeight(barcodeHeight)
            .setBorder(null)
            .setPadding(0f)
            .setVerticalAlignment(VerticalAlignment.BOTTOM)
            .setTextAlignment(TextAlignment.CENTER)
        table.addCell(barcodeCell)

        // Row 3: Barcode text
        val textParagraph = Paragraph(barcodeText)
            .setTextAlignment(TextAlignment.CENTER)
            .setFontSize(fontSize)
            .setMargin(0f)
            .setBold()

        val textCell = Cell()
            .add(textParagraph)
            .setHeight(barcodeTextHeight)
            .setBorder(null)
            .setPadding(0f)
            .setPaddingTop(gap)
            .setPaddingBottom(0f)
            .setVerticalAlignment(VerticalAlignment.BOTTOM)  // Align to bottom of cell
            .setTextAlignment(TextAlignment.CENTER)
        table.addCell(textCell)

        // Row 4: Tiny spacer between barcode text and SKU name (if needed)
        if (showSkuName && skuNameGap > 0) {
            val microSpacerCell = Cell()
                .add(Paragraph(""))
                .setHeight(skuNameGap)
                .setBorder(null)
                .setPadding(0f)
            table.addCell(microSpacerCell)
        }

        // Row 5: SKU name (if available for SKU_ITEM/ITEM types)
        if (showSkuName) {
            val skuNameParagraph = Paragraph(barcodeInfo.skuName)
                .setTextAlignment(TextAlignment.CENTER)
                .setFontSize(fontSize * 0.68f)
                .setMargin(0f)

            val skuNameCell = Cell()
                .add(skuNameParagraph)
                .setHeight(skuNameHeight)
                .setBorder(null)
                .setPadding(0f)
                .setVerticalAlignment(VerticalAlignment.TOP)  // Align to top of cell
                .setTextAlignment(TextAlignment.CENTER)
            table.addCell(skuNameCell)
        }

        document.add(table)

        logger.debug("Added barcode for: $barcodeText with SKU name: ${barcodeInfo.skuName ?: "N/A"} (barcode: ${barcodeWidth}x${barcodeHeight}pt, page: ${pageWidth}x${pageHeight}pt)")
    }

    /**
     * Adds structured barcode content for BOX/PALLET labels with section dividers
     * Creates a professional label layout similar to customer's sketch
     * Optimized for horizontal/landscape orientation with full-width composition
     */
    private fun addStructuredBarcodeContent(
        document: Document,
        barcodeInfo: BarcodeInfo,
        dimensions: BarcodeDimensions,
        pageWidth: Float,
        pageHeight: Float,
        itemType: String
    ) {
        val barcodeText = barcodeInfo.barcodeText

        // Font size based on label type (adjusted for landscape orientation)
        val fontSize = when {
            dimensions.widthMm > 180 -> 16f  // PALLET (landscape)
            else -> 12f   // BOX (landscape)
        }

        // Generate barcode image
        val barcodeImage = generateBarcodeImage(barcodeText, dimensions)
        val imageBytes = ByteArrayOutputStream()
        ImageIO.write(barcodeImage, "PNG", imageBytes)
        val imageData = ImageDataFactory.create(imageBytes.toByteArray())
        val image = Image(imageData)

        // Calculate usable dimensions based on document margins (already set: 3pt top/bottom, 0.5pt left/right)
        // Document margins are applied at document level, so we use full available space
        val documentTopMargin = 3f
        val documentBottomMargin = 3f
        val documentLeftMargin = 0.5f
        val documentRightMargin = 0.5f

        val usableWidth = pageWidth - documentLeftMargin - documentRightMargin
        val usableHeight = pageHeight - documentTopMargin - documentBottomMargin

        // Border style for section dividers
        val borderColor = com.itextpdf.kernel.colors.ColorConstants.BLACK
        val borderWidth = 1f

        // Create main table layout - NO margins (document margins already applied)
        // Set explicit height to prevent overflow to next page
        val mainTable = Table(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setHeight(usableHeight)  // Exact height to fit within page
            .setBorder(null)
            .setPadding(0f)
            .setMargin(0f)  // NO table margins - using document margins only

        // Section 1: Account/Company Name with Account ID (with bottom border, left-aligned)
        if (!barcodeInfo.accountName.isNullOrBlank()) {
            val accountText = buildString {
                append(barcodeInfo.accountName)
                if (!barcodeInfo.accountId.isNullOrBlank()) {
                    append("  (#${barcodeInfo.accountId})")
                }
            }

            val accountParagraph = Paragraph(accountText)
                .setTextAlignment(TextAlignment.LEFT)
                .setFontSize(fontSize * 1.0f)
                .setBold()
                .setMargin(0f)

            val accountCell = Cell()
                .add(accountParagraph)
                .setPadding(fontSize * 0.4f)
                .setPaddingLeft(fontSize * 0.5f)
                .setBorderTop(null)
                .setBorderLeft(null)
                .setBorderRight(null)
                .setBorderBottom(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
                .setTextAlignment(TextAlignment.LEFT)
            mainTable.addCell(accountCell)
        }

        // Section 2: Metadata row - Item Type, Received Date, and Client Reference (with bottom border, left-aligned)
        val metadataText = buildString {
            append("Type: ${itemType.uppercase()}")
            if (!barcodeInfo.receivedDate.isNullOrBlank()) {
                append("    |    Received: ${barcodeInfo.receivedDate}")
            }
            if (!barcodeInfo.clientReference.isNullOrBlank()) {
                append("    |    Client Ref: ${barcodeInfo.clientReference}")
            }
        }

        val metadataParagraph = Paragraph(metadataText)
            .setTextAlignment(TextAlignment.LEFT)
            .setFontSize(fontSize * 0.75f)
            .setMargin(0f)

        val metadataCell = Cell()
            .add(metadataParagraph)
            .setPadding(fontSize * 0.35f)
            .setPaddingLeft(fontSize * 0.5f)
            .setBorderTop(null)
            .setBorderLeft(null)
            .setBorderRight(null)
            .setBorderBottom(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
            .setTextAlignment(TextAlignment.LEFT)
        mainTable.addCell(metadataCell)

        // Section 3: Combined Barcode Image + Text (vertically centered in remaining space)
        // For landscape orientation, use available vertical space and center everything
        // Calculate header height including padding and borders:
        // Account section: fontSize * 1.0f + (fontSize * 0.4f * 2 padding) + borderWidth
        // Metadata section: fontSize * 0.75f + (fontSize * 0.35f * 2 padding) + borderWidth
        val accountSectionHeight = (fontSize * 1.0f) + (fontSize * 0.4f * 2) + borderWidth
        val metadataSectionHeight = (fontSize * 0.75f) + (fontSize * 0.35f * 2) + borderWidth
        val headerHeight = accountSectionHeight + metadataSectionHeight
        val remainingHeight = usableHeight - headerHeight

        val barcodeTextHeight = fontSize * 1.1f * 1.5f  // Text height
        val barcodeImageHeight = remainingHeight * 0.55f  // 55% for barcode image
        val totalBarcodeContentHeight = barcodeImageHeight + barcodeTextHeight
        val verticalSpacerHeight = (remainingHeight - totalBarcodeContentHeight) / 2  // Center vertically

        val barcodeWidth = usableWidth

        // Top spacer to push barcode content down (vertical centering)
        if (verticalSpacerHeight > 0) {
            val topSpacerCell = Cell()
                .add(Paragraph(""))
                .setHeight(verticalSpacerHeight)
                .setBorder(null)
                .setPadding(0f)
            mainTable.addCell(topSpacerCell)
        }

        // Barcode image
        image.setWidth(barcodeWidth)
        image.setHeight(barcodeImageHeight)
        image.setAutoScale(false)
        image.setHorizontalAlignment(HorizontalAlignment.CENTER)

        val barcodeCell = Cell()
            .add(image)
            .setPadding(0f)
            .setBorder(null)
            .setTextAlignment(TextAlignment.CENTER)
            .setVerticalAlignment(VerticalAlignment.MIDDLE)
        mainTable.addCell(barcodeCell)

        // Barcode Text (centered, no border, minimal top padding)
        val textParagraph = Paragraph(barcodeText)
            .setTextAlignment(TextAlignment.CENTER)
            .setFontSize(fontSize * 1.1f)
            .setBold()
            .setMargin(0f)

        val textCell = Cell()
            .add(textParagraph)
            .setPadding(0f)
            .setPaddingTop(fontSize * 0.3f)
            .setBorder(null)
            .setTextAlignment(TextAlignment.CENTER)
        mainTable.addCell(textCell)

        // Bottom spacer to balance vertical centering
        if (verticalSpacerHeight > 0) {
            val bottomSpacerCell = Cell()
                .add(Paragraph(""))
                .setHeight(verticalSpacerHeight)
                .setBorder(null)
                .setPadding(0f)
            mainTable.addCell(bottomSpacerCell)
        }

        document.add(mainTable)

        logger.debug("Added structured barcode for: $barcodeText, type: $itemType, account: ${barcodeInfo.accountName ?: "N/A"}, date: ${barcodeInfo.receivedDate ?: "N/A"} (page: ${pageWidth}x${pageHeight}pt)")
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
                // Medium items (BOX): Reduced from 35% to 28% to make room for account name text
                ((dimensions.heightMm / 25.4) * PRINTER_DPI * 0.28).toInt()
            }
            else -> {
                // Large items (PALLET): Reduced from 40% to 32% to make room for account name text
                ((dimensions.heightMm / 25.4) * PRINTER_DPI * 0.32).toInt()
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
     * Returns barcode dimensions based on item type
     * All types use landscape/horizontal orientation
     */
    private fun getBarcodeDimensions(itemType: String): BarcodeDimensions {
        return when (itemType.uppercase()) {
            "ITEM", "SKU_ITEM" -> BarcodeDimensions(widthMm = 37.29f, heightMm = 25.93f)  // Landscape
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
     * Data class to hold barcode information including optional metadata
     *
     * @param barcodeText The barcode string to encode (required)
     * @param skuName The SKU name to display (optional, only for SKU_ITEM/ITEM types)
     * @param accountName The company/account name to display (optional, only for BOX/PALLET types)
     * @param accountId The account ID/code to display (optional, only for BOX/PALLET types)
     * @param receivedDate The received date to display (optional, only for BOX/PALLET types)
     * @param clientReference The client reference to display (optional, only for BOX/PALLET types)
     */
    data class BarcodeInfo(
        val barcodeText: String,
        val skuName: String? = null,
        val accountName: String? = null,
        val accountId: String? = null,
        val receivedDate: String? = null,
        val clientReference: String? = null
    )
}
