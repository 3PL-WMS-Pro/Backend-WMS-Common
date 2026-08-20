package com.wmspro.common.utils

import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Writes a warehouse document — a GRN or a GIN — as a single-sheet XLSX.
 *
 * ## Why this lives in Common
 *
 * The Inbound and Order services each produce one of these documents, and a customer comparing a
 * GRN against a GIN should not find two different-looking spreadsheets. Putting the writer here
 * means one layout, one set of styles, and Apache POI declared once instead of in two poms.
 *
 * ## What it deliberately is not
 *
 * Not a report generator. The Inventory Service's `ExcelReportGenerator` builds analytical
 * workbooks with pivots and matrices; this builds a *document* — a header block, an items table
 * and a totals line, laid out to be recognisable as the same thing the PDF shows.
 *
 * The PDF is rendered from a tenant-configurable HTML template. A spreadsheet cannot use one, so
 * this layout is fixed for every tenant; the tenant and customer names are written into the header
 * so a downloaded file still says who issued it and who it belongs to.
 *
 * ## Numbers are numbers
 *
 * Quantities, dimensions, CBM and weights are written as numeric cells with formats — not as
 * pre-formatted strings. That is the entire reason someone asks for Excel rather than PDF: they
 * intend to sum, sort and pivot. A workbook of text that merely looks like numbers would be a
 * worse PDF.
 *
 * Dates are the exception: they are written as text, because every date on these documents lives
 * in the header block rather than the items table, where nobody sorts or subtracts them, and a
 * real date cell would render according to the reader's locale instead of the one the document
 * was issued in. Use [formatDate] and [formatDateTime] so both documents spell them the same way.
 *
 * ## Columns and their totals must agree
 *
 * If a column is totalled, the values written into it must be the ones that sum to that total.
 * Writing a per-unit figure under a quantity-weighted total produces a sheet that contradicts
 * itself the moment somebody selects the column — the one thing a PDF could never do wrong.
 */
object DocumentExcelWriter {

    /** A label/value pair in the header block. Null or blank values are skipped entirely. */
    data class HeaderField(val label: String, val value: String?)

    /** One column of the items table. */
    data class Column(
        val header: String,
        val width: Int = 16,
        val type: ColumnType = ColumnType.TEXT
    )

    /**
     * How a column's numbers should be displayed. The value written still decides the cell's own
     * type; this only picks the number format.
     *
     * [PRECISE_DECIMAL] exists for CBM. A single e-commerce item is around 0.0001 m³, which the
     * ordinary three-decimal format renders as `0.000` — a document that reads as though the
     * shipment had no volume at all. Six decimals covers a piece; three is right for a pallet.
     */
    enum class ColumnType { TEXT, INTEGER, DECIMAL, PRECISE_DECIMAL }

    /**
     * One cell value. Kept as a typed wrapper so the writer can put a real number in the cell
     * rather than its rendering, without every caller having to know POI.
     */
    sealed class Value {
        data class Text(val value: String?) : Value()
        data class Integer(val value: Int?) : Value()
        data class Decimal(val value: Double?) : Value()
    }

    /**
     * Build the workbook.
     *
     * @param documentTitle e.g. "GOODS RECEIVED NOTE"
     * @param sheetName the tab name; truncated to Excel's 31-character limit
     * @param headerFields label/value pairs, rendered two per line
     * @param columns the items table columns
     * @param rows one list of values per item, each the same length as [columns]
     * @param totals label/value pairs written under the table, aligned to their columns by index
     * @param footNote optional line under everything, e.g. generation time
     */
    fun write(
        documentTitle: String,
        sheetName: String,
        headerFields: List<HeaderField>,
        columns: List<Column>,
        rows: List<List<Value>>,
        totals: Map<Int, Value> = emptyMap(),
        footNote: String? = null
    ): ByteArray {
        // Caught here rather than left to produce a quietly wrong sheet: an index past the last
        // column is dropped on the floor by writeTotals, which still emits a grey, bold, entirely
        // blank TOTAL row. Column 0 carries the row's "TOTAL" label, so totalling it produces a
        // row of figures with nothing saying what they are.
        require(totals.keys.all { it in columns.indices }) {
            "Totals refer to columns that do not exist: " +
                "${totals.keys.filterNot { it in columns.indices }} (there are ${columns.size} columns)"
        }
        require(0 !in totals.keys) {
            "Column 0 carries the TOTAL label and cannot itself be totalled"
        }

        // Typed as the interface, not XSSFWorkbook: the concrete type makes createRow/createCell
        // return XSSF-specific classes whose cellStyle setter then refuses a plain CellStyle.
        XSSFWorkbook().use { workbook: Workbook ->
            val sheet = workbook.createSheet(safeSheetName(sheetName))
            val styles = createStyles(workbook)

            var rowNum = 0
            rowNum = writeTitle(sheet, styles, documentTitle, columns.size, rowNum)
            rowNum = writeHeaderFields(sheet, styles, headerFields, rowNum)
            rowNum++ // blank line between the header block and the table

            // Captured rather than reconstructed by subtracting the rows back off at the end: that
            // arithmetic silently depended on a footNote being present to supply a missing +1, so
            // the first caller to omit one would have frozen the wrong row.
            val tableHeaderRow = rowNum

            rowNum = writeTable(sheet, styles, columns, rows, rowNum)
            rowNum = writeTotals(sheet, styles, columns, totals, rowNum)

            footNote?.let {
                rowNum++
                val note = sheet.createRow(rowNum)
                note.createCell(0).apply {
                    setCellValue(it)
                    cellStyle = styles.getValue("note")
                }
            }

            columns.forEachIndexed { index, column -> sheet.setColumnWidth(index, column.width * 256) }
            // Freeze everything down to and including the table header, so the column names stay
            // visible when scrolling a long receipt.
            if (rows.isNotEmpty()) sheet.createFreezePane(0, tableHeaderRow + 1)

            return workbook.toBytes()
        }
    }

    /**
     * Excel rejects a sheet name over 31 characters, one containing `: \ / ? * [ ]`, and — less
     * famously — one that begins or ends with an apostrophe. POI enforces all three by throwing,
     * so anything not handled here surfaces to the customer as a failed download.
     */
    private fun safeSheetName(name: String): String =
        name.replace(Regex("[:\\\\/?*\\[\\]]"), "-")
            .take(31)
            .trim('\'')
            .ifBlank { "Document" }

    // ── sections ────────────────────────────────────────────────────────────────

    private fun writeTitle(
        sheet: Sheet,
        styles: Map<String, CellStyle>,
        title: String,
        columnCount: Int,
        startRow: Int
    ): Int {
        val row = sheet.createRow(startRow)
        row.heightInPoints = 24f
        row.createCell(0).apply {
            setCellValue(title)
            cellStyle = styles.getValue("title")
        }
        if (columnCount > 1) {
            sheet.addMergedRegion(CellRangeAddress(startRow, startRow, 0, columnCount - 1))
        }
        return startRow + 2 // title, then a blank line
    }

    /** Header fields go two per row — label, value, label, value — to keep the block compact. */
    private fun writeHeaderFields(
        sheet: Sheet,
        styles: Map<String, CellStyle>,
        fields: List<HeaderField>,
        startRow: Int
    ): Int {
        val present = fields.filter { !it.value.isNullOrBlank() }
        var rowNum = startRow

        present.chunked(2).forEach { pair ->
            val row = sheet.createRow(rowNum++)
            pair.forEachIndexed { index, field ->
                val col = index * 2
                row.createCell(col).apply {
                    setCellValue(field.label)
                    cellStyle = styles.getValue("fieldLabel")
                }
                row.createCell(col + 1).apply {
                    setCellValue(field.value)
                    cellStyle = styles.getValue("fieldValue")
                }
            }
        }
        return rowNum
    }

    private fun writeTable(
        sheet: Sheet,
        styles: Map<String, CellStyle>,
        columns: List<Column>,
        rows: List<List<Value>>,
        startRow: Int
    ): Int {
        var rowNum = startRow

        val headerRow = sheet.createRow(rowNum++)
        headerRow.heightInPoints = 18f
        columns.forEachIndexed { index, column ->
            headerRow.createCell(index).apply {
                setCellValue(column.header)
                cellStyle = styles.getValue("tableHeader")
            }
        }

        if (rows.isEmpty()) {
            // Say so rather than leaving a bare header, which reads as a broken file.
            val empty = sheet.createRow(rowNum++)
            empty.createCell(0).apply {
                setCellValue("No items recorded on this document")
                cellStyle = styles.getValue("note")
            }
            if (columns.size > 1) {
                sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, columns.size - 1))
            }
            return rowNum
        }

        rows.forEach { values ->
            val row = sheet.createRow(rowNum++)
            columns.indices.forEach { index ->
                writeValue(row, index, values.getOrNull(index) ?: Value.Text(null), columns[index], styles, "cell")
            }
        }
        return rowNum
    }

    private fun writeTotals(
        sheet: Sheet,
        styles: Map<String, CellStyle>,
        columns: List<Column>,
        totals: Map<Int, Value>,
        startRow: Int
    ): Int {
        if (totals.isEmpty()) return startRow

        val row = sheet.createRow(startRow)
        row.heightInPoints = 18f

        columns.indices.forEach { index ->
            val value = totals[index]
            if (value == null) {
                // Column 0 always carries the label — write() refuses a totals map that claims it,
                // so the row can never come out anonymous.
                row.createCell(index).apply {
                    setCellValue(if (index == 0) "TOTAL" else "")
                    cellStyle = styles.getValue("totalLabel")
                }
            } else {
                writeValue(row, index, value, columns[index], styles, "total")
            }
        }
        return startRow + 1
    }

    private fun writeValue(
        row: Row,
        index: Int,
        value: Value,
        column: Column,
        styles: Map<String, CellStyle>,
        stylePrefix: String
    ) {
        val cell = row.createCell(index)
        when (value) {
            is Value.Text -> {
                cell.setCellValue(value.value ?: "")
                cell.cellStyle = styles.getValue(stylePrefix)
            }
            is Value.Integer -> {
                // A null number is left blank rather than written as 0 — "not recorded" and "zero"
                // are different statements, and in a spreadsheet a 0 gets summed.
                value.value?.let { cell.setCellValue(it.toDouble()) }
                cell.cellStyle = styles.getValue("${stylePrefix}Integer")
            }
            is Value.Decimal -> {
                value.value?.let { cell.setCellValue(it) }
                // The value decides the cell is numeric; the column decides how many decimals it
                // is shown to, which is the difference between a readable CBM and "0.000".
                val suffix = if (column.type == ColumnType.PRECISE_DECIMAL) "PreciseDecimal" else "Decimal"
                cell.cellStyle = styles.getValue("$stylePrefix$suffix")
            }
        }
    }

    // ── styles ──────────────────────────────────────────────────────────────────

    private fun createStyles(workbook: Workbook): Map<String, CellStyle> {
        val styles = mutableMapOf<String, CellStyle>()
        val format = workbook.createDataFormat()

        styles["title"] = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 16
                color = IndexedColors.DARK_BLUE.index
            })
            alignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.CENTER
        }

        styles["fieldLabel"] = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 10
                color = IndexedColors.GREY_50_PERCENT.index
            })
        }

        styles["fieldValue"] = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply { fontHeightInPoints = 11 })
        }

        styles["tableHeader"] = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 11
                color = IndexedColors.WHITE.index
            })
            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            bordered()
        }

        styles["note"] = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply {
                italic = true
                fontHeightInPoints = 9
                color = IndexedColors.GREY_50_PERCENT.index
            })
        }

        // Body cells
        styles["cell"] = workbook.createCellStyle().apply { bordered() }
        styles["cellInteger"] = workbook.createCellStyle().apply {
            bordered()
            alignment = HorizontalAlignment.RIGHT
            dataFormat = format.getFormat("#,##0")
        }
        styles["cellDecimal"] = workbook.createCellStyle().apply {
            bordered()
            alignment = HorizontalAlignment.RIGHT
            dataFormat = format.getFormat(DECIMAL_FORMAT)
        }
        styles["cellPreciseDecimal"] = workbook.createCellStyle().apply {
            bordered()
            alignment = HorizontalAlignment.RIGHT
            dataFormat = format.getFormat(PRECISE_DECIMAL_FORMAT)
        }

        // Totals row
        val totalFont = workbook.createFont().apply { bold = true; fontHeightInPoints = 11 }
        styles["totalLabel"] = workbook.createCellStyle().apply {
            setFont(totalFont); bordered()
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }
        styles["total"] = styles.getValue("totalLabel")
        styles["totalInteger"] = workbook.createCellStyle().apply {
            setFont(totalFont); bordered()
            alignment = HorizontalAlignment.RIGHT
            dataFormat = format.getFormat("#,##0")
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }
        styles["totalDecimal"] = workbook.createCellStyle().apply {
            setFont(totalFont); bordered()
            alignment = HorizontalAlignment.RIGHT
            dataFormat = format.getFormat(DECIMAL_FORMAT)
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }
        styles["totalPreciseDecimal"] = workbook.createCellStyle().apply {
            setFont(totalFont); bordered()
            alignment = HorizontalAlignment.RIGHT
            dataFormat = format.getFormat(PRECISE_DECIMAL_FORMAT)
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        return styles
    }

    private fun CellStyle.bordered() {
        borderTop = BorderStyle.THIN
        borderBottom = BorderStyle.THIN
        borderLeft = BorderStyle.THIN
        borderRight = BorderStyle.THIN
    }

    private fun Workbook.toBytes(): ByteArray =
        ByteArrayOutputStream().use { out ->
            write(out)
            out.toByteArray()
        }

    // ── turning template markup back into text ──────────────────────────────────

    /**
     * Flatten a value that was built for an HTML template into something a cell can show.
     *
     * The GRN's descriptions are assembled as markup — `<span class="container-badge">Box</span>`,
     * `<div class="product-title">…</div><div class="variant-title">…</div>` — because the PDF is
     * rendered from an HTML template that styles them. A spreadsheet has no such renderer, so those
     * cells displayed the tags themselves, and the customer's description column read as source
     * code.
     *
     * Fixed here rather than in the aggregation because both formats share one DTO and the PDF
     * genuinely wants the markup. Stripping at the point of writing a cell also means any tag a
     * template gains later is handled without this having to be revisited.
     *
     * Tags collapse to a single space rather than to nothing, so two adjacent blocks do not weld
     * into `POLAR CABLEBlack` — and entities are decoded *after* tags are removed, so a `&lt;b&gt;`
     * that was genuinely part of a product name cannot turn into a tag and then vanish.
     */
    fun plainText(value: String?): String? {
        if (value == null) return null
        // Overwhelmingly the common case: ordinary text, nothing to do.
        if ('<' !in value && '&' !in value) return value

        return value
            // Placeholders that exist only to hold space in the PDF layout. In a column people
            // filter and sort, "No variant name available" on every second row is noise, not data.
            .replace(PLACEHOLDER_BLOCK, " ")
            .replace(HTML_TAG, " ")
            .let(::decodeEntities)
            .replace(WHITESPACE, " ")
            .trim()
            .ifBlank { null }
    }

    private fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        var decoded = text
        NAMED_ENTITIES.forEach { (entity, replacement) -> decoded = decoded.replace(entity, replacement) }
        return NUMERIC_ENTITY.replace(decoded) { match ->
            val (hex, digits) = match.destructured
            val code = if (hex.isNotEmpty()) digits.toIntOrNull(16) else digits.toIntOrNull()
            code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: match.value
        }
    }

    private val PLACEHOLDER_BLOCK =
        Regex("""<([a-z]+)[^>]*\bclass\s*=\s*"[^"]*\bno-variant\b[^"]*"[^>]*>.*?</\1>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val HTML_TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
    private val NUMERIC_ENTITY = Regex("&#(x)?([0-9a-fA-F]+);", RegexOption.IGNORE_CASE)

    private val NAMED_ENTITIES = listOf(
        "&nbsp;" to " ",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&apos;" to "'",
        // Last, so an escaped ampersand cannot revive one of the entities above.
        "&amp;" to "&"
    )

    // ── formatting helpers, so callers do not each invent their own ─────────────

    fun formatDate(value: LocalDateTime?): String? = value?.toLocalDate()?.let { formatDate(it) }

    fun formatDate(value: LocalDate?): String? = value?.let {
        "%02d %s %d".format(it.dayOfMonth, MONTHS[it.monthValue - 1], it.year)
    }

    fun formatDateTime(value: LocalDateTime?): String? = value?.let {
        "%s %02d:%02d".format(formatDate(it.toLocalDate()), it.hour, it.minute)
    }

    /** Weights, dimensions and the like — three decimals is more than anyone reads. */
    private const val DECIMAL_FORMAT = "#,##0.000"

    /** CBM, where a single piece is around 0.0001 m3 and three decimals would show nothing. */
    private const val PRECISE_DECIMAL_FORMAT = "#,##0.000000"

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )
}
