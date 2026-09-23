package com.wmspro.common.service

import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.HorizontalAlignment
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import com.itextpdf.layout.properties.VerticalAlignment
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Renders the warehouse label family: a branded, monochrome layout shared by large item labels,
 * box labels and pallet labels.
 *
 * Everything here is constrained by the output device. These labels are printed on 203 DPI
 * monochrome thermal printers, which means:
 *  - pure black on white only; grey fills dither into what reads as dirt on a small label
 *  - structure comes from rules and whitespace, never from tone or colour
 *  - fonts are pinned to a standard PDF font so glyph coverage is known rather than inherited
 *
 * That last point is not theoretical: the label renderer this replaces used emoji as section
 * markers without ever setting a font, so they fell back to a WinAnsi encoding with no glyphs
 * for them and printed as blanks.
 *
 * Layout follows the customer's design: branding at the top, then the customer, then the
 * barcode as the dominant element, then the identifying detail. The visual hierarchy matches
 * how the label is actually used - staff find the right pallet by eye from across an aisle,
 * then scan, then confirm the detail up close.
 */
@Service
class WarehouseLabelRenderer(
    private val barcodeImageGenerator: BarcodeImageGenerator,
    private val labelLogoProvider: LabelLogoProvider,
    private val pdfMergeUtility: PdfMergeUtility
) {
    private val logger = LoggerFactory.getLogger(WarehouseLabelRenderer::class.java)

    companion object {
        private const val MM_TO_POINTS = 2.83465f

        /** Fraction of the label width the logo occupies. */
        private const val LOGO_WIDTH_FRACTION = 0.42f
    }

    /**
     * The physical label formats. Sizes match the stock already in use in the warehouse, so a
     * redesign does not strand existing label rolls.
     *
     * [barcodeHeightFraction] is per-format rather than shared because the fixed rows - logo,
     * customer, detail lines - cost the same on every format, so a single fraction leaves a
     * taller label visibly empty at the bottom. The pallet's larger share is not just
     * space-filling: pallet labels are scanned from further away and at worse angles than a
     * label held in the hand, and a taller symbol tolerates both.
     */
    enum class LabelFormat(
        val widthMm: Float,
        val heightMm: Float,
        val baseFontSize: Float,
        val barcodeHeightFraction: Float
    ) {
        /** Large item label - the customer's new design. */
        ITEM_BIG(100f, 100f, 9f, 0.34f),
        BOX(100f, 100f, 9f, 0.34f),
        PALLET(101.6f, 127f, 11f, 0.50f)
    }

    /**
     * Everything printable on one label. Fields that do not apply to a given format are simply
     * left null; the layout omits their rows rather than printing empty labels.
     */
    data class LabelSpec(
        val barcodeValue: String,
        val customerName: String? = null,
        /** Human-readable SKU description, e.g. "HANGER". Item labels only. */
        val itemName: String? = null,
        /** SKU code, e.g. "001-096-2016". Item labels only. */
        val skuCode: String? = null,
        val receivedDate: String? = null,
        val clientReference: String? = null,
        /** Position within a batch, printed as "n / total". */
        val sequenceIndex: Int? = null,
        val sequenceTotal: Int? = null
    )

    /**
     * Renders one PDF containing a page per label, and returns it Base64-encoded to match the
     * convention every existing barcode endpoint already uses.
     */
    fun renderLabelsBase64(format: LabelFormat, labels: List<LabelSpec>): String =
        Base64.getEncoder().encodeToString(renderLabels(format, labels))

    /**
     * Renders a run spanning several label formats into one PDF.
     *
     * Labels are grouped by format rather than kept in the caller's order. That is deliberate:
     * each format is a different physical label size, and interleaving them would make the
     * operator swap label stock on the printer repeatedly through a single run. Grouping means
     * one stock change per format.
     *
     * The resulting PDF legitimately contains pages of differing sizes.
     */
    fun renderMixedLabels(labels: List<Pair<LabelFormat, LabelSpec>>): ByteArray {
        require(labels.isNotEmpty()) { "Cannot render a label PDF with no labels" }

        val grouped = labels.groupBy({ it.first }, { it.second })

        if (grouped.size == 1) {
            val (format, specs) = grouped.entries.first()
            return renderLabels(format, specs)
        }

        logger.info(
            "Rendering {} labels across {} formats: {}",
            labels.size, grouped.size, grouped.map { "${it.key.name}=${it.value.size}" }
        )

        // Iterate the enum rather than the map so the grouping order is stable across calls
        // instead of depending on the caller's input ordering.
        val perFormat = LabelFormat.entries.mapNotNull { format ->
            grouped[format]?.let { renderLabels(format, it) }
        }

        return pdfMergeUtility.merge(perFormat)
    }

    /** Base64 variant of [renderMixedLabels]. */
    fun renderMixedLabelsBase64(labels: List<Pair<LabelFormat, LabelSpec>>): String =
        Base64.getEncoder().encodeToString(renderMixedLabels(labels))

    /**
     * Renders one PDF containing a page per label.
     *
     * One label per page is deliberate rather than a limitation: these go to roll-fed thermal
     * printers where the page IS the label. A multi-up sheet layout would be wrong for that
     * hardware.
     */
    fun renderLabels(format: LabelFormat, labels: List<LabelSpec>): ByteArray {
        require(labels.isNotEmpty()) { "Cannot render a label PDF with no labels" }

        logger.info("Rendering {} {} label(s)", labels.size, format.name)

        val output = ByteArrayOutputStream()
        val pdfDocument = PdfDocument(PdfWriter(output))

        val pageWidth = format.widthMm * MM_TO_POINTS
        val pageHeight = format.heightMm * MM_TO_POINTS
        val pageSize = PageSize(pageWidth, pageHeight)

        val document = Document(pdfDocument, pageSize)

        // A thermal printer cannot print to the very edge, and label stock is rarely fed with
        // perfect registration. A small uniform margin absorbs that drift.
        val margin = 6f
        document.setMargins(margin, margin, margin, margin)

        val regular = PdfFontFactory.createFont(StandardFonts.HELVETICA)
        val bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD)

        labels.forEachIndexed { index, label ->
            if (index > 0) {
                document.add(AreaBreak(pageSize))
            }
            renderSingleLabel(document, format, label, pageWidth - (margin * 2), regular, bold)
        }

        document.close()
        return output.toByteArray()
    }

    private fun renderSingleLabel(
        document: Document,
        format: LabelFormat,
        label: LabelSpec,
        contentWidthPt: Float,
        regular: PdfFont,
        bold: PdfFont
    ) {
        val fontSize = format.baseFontSize
        val table = Table(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setBorder(null)
            .setMargin(0f)
            .setPadding(0f)

        addLogoRow(table, contentWidthPt)
        addCustomerRow(table, label, fontSize, bold)
        addBarcodeRow(table, format, label, contentWidthPt, fontSize, bold)
        addDetailRows(table, format, label, fontSize, regular, bold)

        document.add(table)
    }

    /**
     * Branding row. Rendered only when a logo is actually available - an empty reserved band
     * would read as a printing fault rather than as deliberate whitespace.
     */
    private fun addLogoRow(table: Table, contentWidthPt: Float) {
        val logo = labelLogoProvider.getLogo() ?: return

        val logoWidth = contentWidthPt * LOGO_WIDTH_FRACTION
        val image = Image(ImageDataFactory.create(logo.pngBytes))
            .setWidth(logoWidth)
            .setHeight(logoWidth / logo.aspectRatio)
            .setHorizontalAlignment(HorizontalAlignment.LEFT)

        table.addCell(
            Cell()
                .add(image)
                .setBorder(null)
                .setBorderBottom(SolidBorder(ColorConstants.BLACK, 1f))
                .setPadding(0f)
                .setPaddingBottom(4f)
                .setTextAlignment(TextAlignment.LEFT)
        )
    }

    private fun addCustomerRow(table: Table, label: LabelSpec, fontSize: Float, bold: PdfFont) {
        val customer = label.customerName?.takeIf { it.isNotBlank() } ?: return

        table.addCell(
            Cell()
                .add(
                    Paragraph("CUSTOMER : ${customer.uppercase()}")
                        .setFont(bold)
                        .setFontSize(fontSize)
                        .setMargin(0f)
                )
                .setBorder(null)
                .setBorderBottom(SolidBorder(ColorConstants.BLACK, 1f))
                .setPadding(0f)
                .setPaddingTop(5f)
                .setPaddingBottom(5f)
                .setTextAlignment(TextAlignment.LEFT)
        )
    }

    /**
     * The barcode and its human-readable value.
     *
     * The value is always printed beneath the bars. A smudged or torn barcode is common on a
     * warehouse floor, and without the value underneath there is no way to identify the item by
     * hand - the batch position ("1 / 20") narrows it to twenty candidates, which is not an
     * identification.
     */
    private fun addBarcodeRow(
        table: Table,
        format: LabelFormat,
        label: LabelSpec,
        contentWidthPt: Float,
        fontSize: Float,
        bold: PdfFont
    ) {
        // Generate the image at exactly the width it will occupy, so one image pixel maps to one
        // printer dot and the bars are never resampled.
        val barcodeWidthMm = format.widthMm * 0.86f
        val barcodeHeightMm = format.heightMm * format.barcodeHeightFraction

        val pngBytes = barcodeImageGenerator.generatePng(
            content = label.barcodeValue,
            widthMm = barcodeWidthMm,
            heightMm = barcodeHeightMm
        )

        val image = Image(ImageDataFactory.create(pngBytes))
            .setWidth(barcodeWidthMm * MM_TO_POINTS)
            .setHeight(barcodeHeightMm * MM_TO_POINTS)
            .setHorizontalAlignment(HorizontalAlignment.CENTER)

        table.addCell(
            Cell()
                .add(image)
                .setBorder(null)
                .setPadding(0f)
                .setPaddingTop(8f)
                .setTextAlignment(TextAlignment.CENTER)
                .setHorizontalAlignment(HorizontalAlignment.CENTER)
        )

        table.addCell(
            Cell()
                .add(
                    Paragraph(label.barcodeValue)
                        .setFont(bold)
                        .setFontSize(fontSize)
                        .setMargin(0f)
                )
                .setBorder(null)
                .setBorderBottom(SolidBorder(ColorConstants.BLACK, 1f))
                .setPadding(0f)
                .setPaddingTop(3f)
                .setPaddingBottom(6f)
                .setTextAlignment(TextAlignment.CENTER)
        )
    }

    /**
     * Identifying detail, plus the batch position pinned bottom-right.
     *
     * Which fields appear depends on the tier. An item label names its SKU because an item is by
     * definition one SKU; a box or pallet does not, because it can hold several and naming only
     * the first would be actively misleading.
     */
    private fun addDetailRows(
        table: Table,
        format: LabelFormat,
        label: LabelSpec,
        fontSize: Float,
        regular: PdfFont,
        bold: PdfFont
    ) {
        val detailFontSize = fontSize * 0.92f
        val lines = mutableListOf<Pair<String, String>>()

        when (format) {
            LabelFormat.ITEM_BIG -> {
                label.itemName?.takeIf { it.isNotBlank() }?.let { lines += "ITEM" to it }
                label.skuCode?.takeIf { it.isNotBlank() }?.let { lines += "SKU" to it }
            }

            LabelFormat.BOX, LabelFormat.PALLET -> {
                lines += "TYPE" to format.name
                label.clientReference?.takeIf { it.isNotBlank() }?.let { lines += "CLIENT REF" to it }
            }
        }

        label.receivedDate?.takeIf { it.isNotBlank() }?.let { lines += "RECEIVED DATE" to it }

        val sequenceText = if (label.sequenceIndex != null && label.sequenceTotal != null) {
            "${label.sequenceIndex} / ${label.sequenceTotal}"
        } else {
            null
        }

        if (lines.isEmpty() && sequenceText == null) return

        // Two columns so the batch position can sit hard right against the detail block without
        // depending on the detail text's length.
        val detailTable = Table(UnitValue.createPercentArray(floatArrayOf(72f, 28f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setBorder(null)
            .setMargin(0f)
            .setPadding(0f)

        val detailBlock = Cell()
            .setBorder(null)
            .setPadding(0f)
            .setTextAlignment(TextAlignment.LEFT)

        if (lines.isEmpty()) {
            detailBlock.add(Paragraph("").setMargin(0f))
        } else {
            lines.forEach { (labelText, value) ->
                detailBlock.add(
                    Paragraph()
                        .add(Paragraph("$labelText : ").setFont(bold).setFontSize(detailFontSize))
                        .add(Paragraph(value).setFont(regular).setFontSize(detailFontSize))
                        .setMargin(0f)
                        .setMultipliedLeading(1.15f)
                )
            }
        }
        detailTable.addCell(detailBlock)

        detailTable.addCell(
            Cell()
                .add(
                    Paragraph(sequenceText ?: "")
                        .setFont(bold)
                        .setFontSize(fontSize * 1.15f)
                        .setMargin(0f)
                )
                .setBorder(null)
                .setPadding(0f)
                .setTextAlignment(TextAlignment.RIGHT)
                .setVerticalAlignment(VerticalAlignment.BOTTOM)
        )

        table.addCell(
            Cell()
                .add(detailTable)
                .setBorder(null)
                .setPadding(0f)
                .setPaddingTop(6f)
        )
    }
}
