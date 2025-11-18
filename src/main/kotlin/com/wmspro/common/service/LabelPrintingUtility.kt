package com.wmspro.common.service

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.properties.HorizontalAlignment
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.VerticalAlignment
import com.itextpdf.layout.properties.UnitValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.*

/**
 * LabelPrintingUtility - Generates professional PDF labels for BOX and PALLET items
 *
 * This utility creates information-rich labels without barcodes, designed for
 * thermal label printers. Labels display comprehensive metadata about boxes and pallets.
 *
 * Label Dimensions (Width × Height):
 * - Boxes (BOX): 100mm × 100mm (square)
 * - Pallets (PALLET): 101.6mm × 127mm (portrait)
 *
 * Key Features:
 * - No barcode (purely informational labels)
 * - Comprehensive metadata display (GRN, description, account info, dates)
 * - Professional section-based layout with dividers
 * - Box/Pallet tracking with indices
 *
 * @return BASE64 encoded PDF string
 */
@Service
class LabelPrintingUtility {
    private val logger = LoggerFactory.getLogger(LabelPrintingUtility::class.java)

    // Millimeters to points conversion (1mm = 2.83465 points)
    private val MM_TO_POINTS = 2.83465f

    /**
     * Generates a PDF with information labels for all provided label data
     *
     * @param itemType The type of item (BOX, PALLET) - determines label dimensions
     * @param labels List of label information to generate
     * @return BASE64 encoded PDF string
     */
    fun generateLabelPDF(itemType: String, labels: List<LabelInfo>): String {
        logger.info("Generating label PDF for itemType: $itemType with ${labels.size} labels")

        // Validate item type
        val normalizedType = itemType.uppercase()
        if (normalizedType != "BOX" && normalizedType != "PALLET") {
            throw IllegalArgumentException("LabelPrintingUtility only supports BOX and PALLET types. Received: $itemType")
        }

        try {
            // Determine dimensions based on item type
            val dimensions = getLabelDimensions(normalizedType)

            // Create PDF in memory
            val outputStream = ByteArrayOutputStream()
            val pdfWriter = PdfWriter(outputStream)
            val pdfDocument = PdfDocument(pdfWriter)

            // Set page size for the document
            val pageWidth = dimensions.widthMm * MM_TO_POINTS
            val pageHeight = dimensions.heightMm * MM_TO_POINTS
            val pageSize = PageSize(pageWidth, pageHeight)
            val document = Document(pdfDocument, pageSize)

            // Set minimal margins
            document.setMargins(5f, 5f, 5f, 5f)

            // Generate a page for each label
            labels.forEachIndexed { index, labelInfo ->
                // Add page break before each label (except the first one)
                if (index > 0) {
                    document.add(com.itextpdf.layout.element.AreaBreak(pageSize))
                }
                addLabelContent(document, labelInfo, dimensions, pageWidth, pageHeight, normalizedType)
            }

            document.close()

            // Convert to BASE64
            val pdfBytes = outputStream.toByteArray()
            val base64PDF = Base64.getEncoder().encodeToString(pdfBytes)

            logger.info("Successfully generated label PDF with ${labels.size} pages")
            return base64PDF

        } catch (e: Exception) {
            logger.error("Error generating label PDF for itemType: $itemType", e)
            throw RuntimeException("Failed to generate label PDF: ${e.message}", e)
        }
    }

    /**
     * Adds label content to the current page with professional structured layout
     * Displays comprehensive metadata about the box/pallet without barcode image
     */
    private fun addLabelContent(
        document: Document,
        labelInfo: LabelInfo,
        dimensions: LabelDimensions,
        pageWidth: Float,
        pageHeight: Float,
        itemType: String
    ) {
        // Font sizes - PALLET has generous spacing, BOX slightly more compact
        val baseFontSize = when {
            dimensions.heightMm > 120 -> 13f  // PALLET (portrait) - has more vertical space
            dimensions.widthMm >= 100 -> 10f  // BOX (square) - slight reduction for multiline support
            else -> 8f
        }

        // Minimal margins
        val documentMargin = 3f

        // Border style - slightly thicker for better definition
        val borderColor = com.itextpdf.kernel.colors.ColorConstants.BLACK
        val borderWidth = 1f
        val thickBorderWidth = 2f

        // Create main container with thick outer border
        val outerTable = Table(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setBorder(com.itextpdf.layout.borders.SolidBorder(borderColor, thickBorderWidth))
            .setPadding(0f)
            .setMargin(0f)

        val mainTable = Table(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setBorder(null)
            .setPadding(0f)
            .setMargin(0f)

        // Section 1: Large, Bold Title with emoji
        val isBox = dimensions.widthMm >= 100 && dimensions.heightMm <= 100
        val titleEmoji = if (itemType == "PALLET") "📦" else "📦"
        val titleText = "$titleEmoji $itemType LABEL $titleEmoji"
        val titleFontSize = baseFontSize * 2.2f  // Same for both
        val titlePadding = if (isBox) baseFontSize * 0.65f else baseFontSize * 0.6f

        val titleParagraph = Paragraph(titleText)
            .setTextAlignment(TextAlignment.CENTER)
            .setFontSize(titleFontSize)
            .setBold()
            .setMargin(0f)
            .setFixedLeading(titleFontSize * 1.15f)

        val titleCell = Cell()
            .add(titleParagraph)
            .setPadding(titlePadding)
            .setBorderTop(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
            .setBorderLeft(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
            .setBorderRight(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
            .setBorderBottom(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth * 1.5f))  // Slightly thicker bottom border
            .setTextAlignment(TextAlignment.CENTER)
            .setBackgroundColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
            .setFontColor(com.itextpdf.kernel.colors.ColorConstants.BLACK)
        mainTable.addCell(titleCell)

        // Section 2: Large Index Display - most prominent
        val hasBoxInfo = labelInfo.boxIndex != null && labelInfo.totalBoxesNum != null
        val hasPalletInfo = labelInfo.palletIndex != null && labelInfo.totalPalletsNum != null

        if (hasBoxInfo || hasPalletInfo) {
            val indexText = when {
                hasBoxInfo -> "${labelInfo.boxIndex} of ${labelInfo.totalBoxesNum}"
                hasPalletInfo -> "${labelInfo.palletIndex} of ${labelInfo.totalPalletsNum}"
                else -> ""
            }

            // Slightly smaller index for BOX to fit multiline content
            val indexFontSize = if (isBox) baseFontSize * 2.8f else baseFontSize * 3.2f
            val indexPadding = if (isBox) baseFontSize * 0.75f else baseFontSize * 0.8f

            val indexParagraph = Paragraph(indexText)
                .setTextAlignment(TextAlignment.CENTER)
                .setFontSize(indexFontSize)
                .setBold()
                .setMargin(0f)
                .setFixedLeading(indexFontSize * 1.1f)

            val indexCell = Cell()
                .add(indexParagraph)
                .setPadding(indexPadding)
                .setBorder(null)
                .setBorderBottom(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
                .setTextAlignment(TextAlignment.CENTER)
            mainTable.addCell(indexCell)
        }

        // Section 3: Account Information with smart truncation to prevent overflow
        if (!labelInfo.accountName.isNullOrBlank()) {
            val accountPadding = if (isBox) baseFontSize * 0.5f else baseFontSize * 0.5f

            // Smart text handling: if name is too long, remove account code; if still long, truncate
            val maxLengthWithCode = if (isBox) 55 else 49  // Max chars with account code
            val maxLengthWithoutCode = if (isBox) 55 else 49  // Max chars without account code
            val truncateLength = if (isBox) 60 else 49  // Max before truncation

            val accountText = buildString {
                append("👤 ")
                val nameLength = labelInfo.accountName.length
                val codeLength = labelInfo.accountId?.length ?: 0

                when {
                    // Short name - include code if available
                    nameLength + codeLength + 5 <= maxLengthWithCode && !labelInfo.accountId.isNullOrBlank() -> {
                        append(labelInfo.accountName)
                        append(" (#${labelInfo.accountId})")
                    }
                    // Medium name - only name, no code
                    nameLength <= maxLengthWithoutCode -> {
                        append(labelInfo.accountName)
                    }
                    // Long name - truncate
                    else -> {
                        append(labelInfo.accountName.take(truncateLength - 3))
                        append("...")
                    }
                }
            }

            val accountParagraph = Paragraph(accountText)
                .setTextAlignment(TextAlignment.LEFT)
                .setFontSize(baseFontSize * 1.2f)
                .setBold()
                .setMargin(0f)
                .setFixedLeading(baseFontSize * 1.4f)

            val accountCell = Cell()
                .add(accountParagraph)
                .setPadding(accountPadding)
                .setPaddingLeft(baseFontSize * 0.6f)
                .setPaddingRight(baseFontSize * 0.6f)
                .setBorder(null)
                .setBorderBottom(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
                .setTextAlignment(TextAlignment.LEFT)
                .setBackgroundColor(com.itextpdf.kernel.colors.DeviceRgb(245f/255f, 245f/255f, 245f/255f))
            mainTable.addCell(accountCell)
        }

        // Section 4: GRN and ASN Numbers with space-between layout
        if (!labelInfo.grnNumber.isNullOrBlank() || !labelInfo.asnNumber.isNullOrBlank()) {
            val contentPadding = if (isBox) baseFontSize * 0.45f else baseFontSize * 0.45f
            val sidePadding = if (isBox) baseFontSize * 0.7f else baseFontSize * 0.7f

            // Create 2-column table for space-between effect
            val grnAsnTable = Table(UnitValue.createPercentArray(floatArrayOf(50f, 50f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setBorder(null)

            // Left: GRN
            val grnText = if (!labelInfo.grnNumber.isNullOrBlank()) "📋 ${labelInfo.grnNumber}" else "📋"
            val grnParagraph = Paragraph(grnText)
                .setTextAlignment(TextAlignment.LEFT)
                .setFontSize(baseFontSize * 1.05f)
                .setMargin(0f)
                .setFixedLeading(baseFontSize * 1.3f)

            val grnLeftCell = Cell()
                .add(grnParagraph)
                .setBorder(null)
                .setPadding(0f)
                .setTextAlignment(TextAlignment.LEFT)
            grnAsnTable.addCell(grnLeftCell)

            // Right: ASN
            val asnText = labelInfo.asnNumber ?: ""
            val asnParagraph = Paragraph(asnText)
                .setTextAlignment(TextAlignment.RIGHT)
                .setFontSize(baseFontSize * 1.05f)
                .setMargin(0f)
                .setFixedLeading(baseFontSize * 1.3f)

            val asnRightCell = Cell()
                .add(asnParagraph)
                .setBorder(null)
                .setPadding(0f)
                .setTextAlignment(TextAlignment.RIGHT)
            grnAsnTable.addCell(asnRightCell)

            // Wrapper cell
            val grnCell = Cell()
                .add(grnAsnTable)
                .setPadding(contentPadding)
                .setPaddingLeft(sidePadding)
                .setPaddingRight(sidePadding)
                .setBorder(null)
                .setBorderBottom(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
            mainTable.addCell(grnCell)
        }

        // Define padding for content sections
        val contentPadding = if (isBox) baseFontSize * 0.45f else baseFontSize * 0.45f
        val sidePadding = if (isBox) baseFontSize * 0.7f else baseFontSize * 0.7f

        // Section 5: Description with emoji and truncation to prevent overflow
        if (!labelInfo.description.isNullOrBlank()) {
            // Truncate description to fit in 2 lines max (already validated to 70/80 chars)
            // This ensures it won't overflow to next page
            val maxDescLength = if (isBox) 80 else 70  // Already validated in API, but double-check
            val truncatedDesc = if (labelInfo.description.length > maxDescLength) {
                labelInfo.description.take(maxDescLength - 3) + "..."
            } else {
                labelInfo.description
            }

            val descParagraph = Paragraph("📝 $truncatedDesc")
                .setTextAlignment(TextAlignment.LEFT)
                .setFontSize(baseFontSize * 1.05f)
                .setMargin(0f)
                .setFixedLeading(baseFontSize * 1.3f)

            val descCell = Cell()
                .add(descParagraph)
                .setPadding(contentPadding)
                .setPaddingLeft(sidePadding)
                .setPaddingRight(sidePadding)
                .setBorder(null)
                .setBorderBottom(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
                .setTextAlignment(TextAlignment.LEFT)
                .setBackgroundColor(com.itextpdf.kernel.colors.DeviceRgb(245f/255f, 245f/255f, 245f/255f))
            mainTable.addCell(descCell)
        }

        // Section 6: Received Date with emoji
        if (!labelInfo.receivedDate.isNullOrBlank()) {
            val dateParagraph = Paragraph("📅 Received: ${labelInfo.receivedDate}")
                .setTextAlignment(TextAlignment.LEFT)
                .setFontSize(baseFontSize * 1.0f)
                .setMargin(0f)
                .setFixedLeading(baseFontSize * 1.25f)

            val dateCell = Cell()
                .add(dateParagraph)
                .setPadding(contentPadding)
                .setPaddingLeft(sidePadding)
                .setPaddingRight(sidePadding)
                .setBorder(null)
                .setBorderBottom(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
                .setTextAlignment(TextAlignment.LEFT)
            mainTable.addCell(dateCell)
        }

        // Section 7: Client Reference with emoji (if available)
        if (!labelInfo.clientReference.isNullOrBlank()) {
            val clientRefParagraph = Paragraph("📌 Client Ref: ${labelInfo.clientReference}")
                .setTextAlignment(TextAlignment.LEFT)
                .setFontSize(baseFontSize * 1.0f)
                .setMargin(0f)
                .setFixedLeading(baseFontSize * 1.25f)

            val clientRefCell = Cell()
                .add(clientRefParagraph)
                .setPadding(contentPadding)
                .setPaddingLeft(sidePadding)
                .setPaddingRight(sidePadding)
                .setBorder(null)
                .setBorderBottom(com.itextpdf.layout.borders.SolidBorder(borderColor, borderWidth))
                .setTextAlignment(TextAlignment.LEFT)
                .setBackgroundColor(com.itextpdf.kernel.colors.DeviceRgb(245f/255f, 245f/255f, 245f/255f))
            mainTable.addCell(clientRefCell)
        }

        // Section 8: Dimensions
        if (!labelInfo.dimensions.isNullOrBlank()) {
            val dimParagraph = Paragraph("📏 Dimensions: ${labelInfo.dimensions}")
                .setTextAlignment(TextAlignment.LEFT)
                .setFontSize(baseFontSize * 1.0f)
                .setMargin(0f)
                .setFixedLeading(baseFontSize * 1.25f)

            val dimCell = Cell()
                .add(dimParagraph)
                .setPadding(contentPadding)
                .setPaddingLeft(sidePadding)
                .setPaddingRight(sidePadding)
                .setBorder(null)
                .setTextAlignment(TextAlignment.LEFT)
            mainTable.addCell(dimCell)
        }

        // Add mainTable to outerTable
        val outerCell = Cell()
            .add(mainTable)
            .setBorder(null)
            .setPadding(0f)
        outerTable.addCell(outerCell)

        document.add(outerTable)

        logger.debug("Added label for type: $itemType, GRN: ${labelInfo.grnNumber ?: "N/A"}, Account: ${labelInfo.accountName ?: "N/A"}")
    }

    /**
     * Returns label dimensions based on item type
     * BOX is square, PALLET is portrait
     */
    private fun getLabelDimensions(itemType: String): LabelDimensions {
        return when (itemType.uppercase()) {
            "BOX" -> LabelDimensions(widthMm = 100f, heightMm = 100f)  // Square
            "PALLET" -> LabelDimensions(widthMm = 101.6f, heightMm = 127f)  // Portrait
            else -> {
                logger.warn("Unknown item type: $itemType, using default BOX dimensions")
                LabelDimensions(widthMm = 100f, heightMm = 100f)
            }
        }
    }

    /**
     * Data class to hold label dimensions
     */
    private data class LabelDimensions(
        val widthMm: Float,
        val heightMm: Float
    )

    /**
     * Data class to hold comprehensive label information for BOX/PALLET items
     *
     * @param accountName The company/account name (optional)
     * @param accountId The account ID/code (optional)
     * @param grnNumber The Goods Receipt Note number (optional)
     * @param asnNumber The ASN number (optional)
     * @param clientReference The client reference/PO number (optional)
     * @param description Description of the box/pallet contents or purpose (optional)
     * @param receivedDate The date received (optional)
     * @param boxIndex The current box index (optional, only for BOX types)
     * @param totalBoxesNum The total number of boxes (optional, only for BOX types)
     * @param palletIndex The current pallet index (optional, only for PALLET types)
     * @param totalPalletsNum The total number of pallets (optional, only for PALLET types)
     * @param weight The weight of the box/pallet (optional, e.g., "25 kg")
     * @param dimensions The physical dimensions (optional, e.g., "100x80x60 cm")
     */
    data class LabelInfo(
        val accountName: String? = null,
        val accountId: String? = null,
        val grnNumber: String? = null,
        val asnNumber: String? = null,
        val clientReference: String? = null,
        val description: String? = null,
        val receivedDate: String? = null,
        val boxIndex: Int? = null,
        val totalBoxesNum: Int? = null,
        val palletIndex: Int? = null,
        val totalPalletsNum: Int? = null,
        val weight: String? = null,
        val dimensions: String? = null
    )
}
