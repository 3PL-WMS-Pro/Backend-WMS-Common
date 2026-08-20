package com.wmspro.common.utils

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

/**
 * Covers the shared document writer used for GRN and GIN Excel downloads.
 *
 * Every assertion reads the workbook back with POI rather than trusting the bytes, so a file that
 * Excel would refuse to open fails here first.
 */
class DocumentExcelWriterTest {

    private val columns = listOf(
        DocumentExcelWriter.Column("Item Code", 18),
        DocumentExcelWriter.Column("Description", 30),
        DocumentExcelWriter.Column("Received", 12, DocumentExcelWriter.ColumnType.INTEGER),
        DocumentExcelWriter.Column("CBM", 12, DocumentExcelWriter.ColumnType.DECIMAL)
    )

    private fun sample(
        rows: List<List<DocumentExcelWriter.Value>> = listOf(
            listOf(
                DocumentExcelWriter.Value.Text("SKU-1201"),
                DocumentExcelWriter.Value.Text("Blue Widget"),
                DocumentExcelWriter.Value.Integer(40),
                DocumentExcelWriter.Value.Decimal(0.96)
            ),
            listOf(
                DocumentExcelWriter.Value.Text("SKU-1202"),
                DocumentExcelWriter.Value.Text("Red Widget"),
                DocumentExcelWriter.Value.Integer(12),
                DocumentExcelWriter.Value.Decimal(0.29)
            )
        ),
        totals: Map<Int, DocumentExcelWriter.Value> = mapOf(
            2 to DocumentExcelWriter.Value.Integer(52),
            3 to DocumentExcelWriter.Value.Decimal(1.25)
        )
    ) = DocumentExcelWriter.write(
        documentTitle = "GOODS RECEIVED NOTE",
        sheetName = "GRN-2026-00042",
        headerFields = listOf(
            DocumentExcelWriter.HeaderField("GRN Number", "GRN-2026-00042"),
            DocumentExcelWriter.HeaderField("Customer", "Digital Stout"),
            DocumentExcelWriter.HeaderField("Warehouse", "Infinity Logistics"),
            DocumentExcelWriter.HeaderField("Empty Field", null)
        ),
        columns = columns,
        rows = rows,
        totals = totals,
        footNote = "Generated 20 Aug 2026"
    )

    private fun open(bytes: ByteArray) = WorkbookFactory.create(ByteArrayInputStream(bytes))

    private fun cellsOf(bytes: ByteArray): List<List<String>> {
        open(bytes).use { wb ->
            val sheet = wb.getSheetAt(0)
            return (0..sheet.lastRowNum).map { r ->
                val row = sheet.getRow(r) ?: return@map emptyList()
                (0 until row.lastCellNum.coerceAtLeast(0)).map { c ->
                    val cell = row.getCell(c) ?: return@map ""
                    when (cell.cellType) {
                        CellType.NUMERIC -> cell.numericCellValue.toString()
                        CellType.STRING -> cell.stringCellValue
                        else -> ""
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("produces a workbook Excel can actually open")
    fun producesReadableWorkbook() {
        open(sample()).use { wb ->
            assertEquals(1, wb.numberOfSheets)
            assertNotNull(wb.getSheetAt(0))
        }
    }

    @Test
    @DisplayName("the sheet is named after the document")
    fun sheetIsNamed() {
        open(sample()).use { wb -> assertEquals("GRN-2026-00042", wb.getSheetName(0)) }
    }

    @Test
    @DisplayName("the title and header fields are present, and empty fields are omitted")
    fun headerBlockIsWritten() {
        val flat = cellsOf(sample()).flatten()

        assertTrue(flat.contains("GOODS RECEIVED NOTE"))
        assertTrue(flat.contains("GRN Number"))
        assertTrue(flat.contains("GRN-2026-00042"))
        assertTrue(flat.contains("Digital Stout"))
        assertTrue(flat.contains("Infinity Logistics"))
        // A null value must not leave a stray label behind.
        assertTrue(flat.none { it == "Empty Field" }, "header fields with no value must be skipped")
    }

    @Test
    @DisplayName("one row per item, in the order supplied")
    fun rowsAppearInOrder() {
        val flat = cellsOf(sample())
        val first = flat.indexOfFirst { it.contains("SKU-1201") }
        val second = flat.indexOfFirst { it.contains("SKU-1202") }

        assertTrue(first > 0, "first item must be present")
        assertTrue(second > first, "items must keep the order they were supplied in")
    }

    /**
     * The point of an Excel export rather than a PDF: the recipient intends to sum and pivot.
     * A workbook of text that merely looks like numbers would be a worse PDF.
     */
    @Test
    @DisplayName("quantities and CBM are numeric cells, not text")
    fun numbersAreNumeric() {
        open(sample()).use { wb ->
            val sheet = wb.getSheetAt(0)
            val itemRow = (0..sheet.lastRowNum)
                .mapNotNull { sheet.getRow(it) }
                .first { it.getCell(0)?.toString() == "SKU-1201" }

            assertEquals(CellType.NUMERIC, itemRow.getCell(2).cellType, "quantity must be numeric")
            assertEquals(40.0, itemRow.getCell(2).numericCellValue)
            assertEquals(CellType.NUMERIC, itemRow.getCell(3).cellType, "CBM must be numeric")
            assertEquals(0.96, itemRow.getCell(3).numericCellValue)
        }
    }

    @Test
    @DisplayName("totals are numeric too, so they add up when re-summed")
    fun totalsAreNumeric() {
        open(sample()).use { wb ->
            val sheet = wb.getSheetAt(0)
            val totalRow = (0..sheet.lastRowNum)
                .mapNotNull { sheet.getRow(it) }
                .first { it.getCell(0)?.toString() == "TOTAL" }

            assertEquals(52.0, totalRow.getCell(2).numericCellValue)
            assertEquals(1.25, totalRow.getCell(3).numericCellValue)
        }
    }

    @Test
    @DisplayName("a document with no items says so instead of producing a bare header")
    fun emptyItemsIsSafe() {
        val bytes = sample(rows = emptyList(), totals = emptyMap())
        val flat = cellsOf(bytes).flatten()

        assertTrue(flat.any { it.contains("No items recorded") })
        assertTrue(flat.contains("GOODS RECEIVED NOTE"), "the header must still be written")
    }

    @Test
    @DisplayName("a null number is left blank rather than written as zero")
    fun nullNumbersAreBlankNotZero() {
        val bytes = sample(
            rows = listOf(
                listOf(
                    DocumentExcelWriter.Value.Text("SKU-9"),
                    DocumentExcelWriter.Value.Text("Unmeasured"),
                    DocumentExcelWriter.Value.Integer(5),
                    DocumentExcelWriter.Value.Decimal(null)
                )
            ),
            totals = emptyMap()
        )

        open(bytes).use { wb ->
            val sheet = wb.getSheetAt(0)
            val row = (0..sheet.lastRowNum)
                .mapNotNull { sheet.getRow(it) }
                .first { it.getCell(0)?.toString() == "SKU-9" }

            // Blank, not 0.0 — "not measured" and "zero volume" are different statements, and a 0
            // would be silently included in any sum the recipient runs.
            assertTrue(
                row.getCell(3) == null || row.getCell(3).cellType == CellType.BLANK,
                "an absent measurement must not become a zero"
            )
        }
    }

    @Test
    @DisplayName("a sheet name with characters Excel forbids is sanitised rather than throwing")
    fun sheetNameIsSanitised() {
        val bytes = DocumentExcelWriter.write(
            documentTitle = "GOODS ISSUE NOTE",
            sheetName = "GIN/2026:00042*[draft] with a very long trailing description",
            headerFields = listOf(DocumentExcelWriter.HeaderField("GIN Number", "GIN-1")),
            columns = columns,
            rows = emptyList()
        )

        open(bytes).use { wb ->
            // Every forbidden character replaced, then cut to Excel's 31-character limit. Asserted
            // as the exact resulting name rather than "short enough and contains nothing illegal",
            // which is just the implementation restated and would pass on a sanitiser that
            // returned the empty string.
            assertEquals("GIN-2026-00042--draft- with a v", wb.getSheetName(0))
        }
    }

    /**
     * Every one of these makes POI throw, and a throw here reaches the customer as a failed
     * download. The apostrophe rule is the obscure one — Excel writes `'Sheet Name'!A1` to quote a
     * name inside a formula, so a name that starts or ends with one is ambiguous and rejected.
     */
    @Test
    @DisplayName("sheet names Excel would reject are repaired, not passed through")
    fun sheetNamesAreRepaired() {
        fun nameFor(raw: String): String =
            open(
                DocumentExcelWriter.write(
                    documentTitle = "T", sheetName = raw,
                    headerFields = emptyList(), columns = columns, rows = emptyList()
                )
            ).use { it.getSheetName(0) }

        assertEquals("Document", nameFor("   "), "a blank name")
        assertEquals("Document", nameFor("\'\'\'"), "a name that is nothing but apostrophes")
        assertEquals("GRN-1", nameFor("\'GRN-1\'"), "leading and trailing apostrophes")
        assertEquals("Q1\'26 receipts", nameFor("Q1\'26 receipts"), "an interior apostrophe is legal")
        assertEquals("N-A", nameFor("N/A"), "the path separator")
        assertEquals("a".repeat(31), nameFor("a".repeat(40)), "over the 31-character limit")
    }

    /**
     * The header row must stay on screen when scrolling a long receipt. The pane used to be
     * derived by subtracting the rows back off the final row number, which quietly relied on a
     * foot note being present — so the no-footnote case froze one row short and hid the headers.
     */
    @Test
    @DisplayName("the freeze pane holds the table header, with or without a foot note")
    fun freezePaneHoldsTheHeader() {
        fun assertHeaderFrozen(bytes: ByteArray, case: String) {
            open(bytes).use { wb ->
                val sheet = wb.getSheetAt(0)
                val split = sheet.paneInformation?.horizontalSplitPosition?.toInt()
                assertNotNull(split, "$case: no freeze pane at all")

                // The last frozen row is split - 1, and it has to be the row carrying the headers.
                val headerRow = sheet.getRow(split!! - 1)
                assertEquals("Item Code", headerRow.getCell(0).stringCellValue, case)
            }
        }

        assertHeaderFrozen(sample(), "with a foot note")
        assertHeaderFrozen(
            DocumentExcelWriter.write(
                documentTitle = "GOODS RECEIVED NOTE", sheetName = "GRN-1",
                headerFields = listOf(DocumentExcelWriter.HeaderField("GRN Number", "GRN-1")),
                columns = columns,
                rows = listOf(
                    listOf(
                        DocumentExcelWriter.Value.Text("SKU-1"),
                        DocumentExcelWriter.Value.Text("Widget"),
                        DocumentExcelWriter.Value.Integer(1),
                        DocumentExcelWriter.Value.Decimal(0.5)
                    )
                ),
                totals = mapOf(2 to DocumentExcelWriter.Value.Integer(1))
            ),
            "without a foot note"
        )
    }

    /**
     * A single e-commerce piece is around 0.0001 m³. Under the ordinary three-decimal format the
     * whole CBM column renders `0.000` and the document reads as though the shipment had no
     * volume — the value is intact, but nobody reads the value, they read the sheet.
     */
    @Test
    @DisplayName("a precise-decimal column shows a piece-level CBM instead of 0.000")
    fun preciseDecimalColumnsAreReadable() {
        val preciseColumns = columns.dropLast(1) +
            DocumentExcelWriter.Column("CBM", 12, DocumentExcelWriter.ColumnType.PRECISE_DECIMAL)

        val bytes = DocumentExcelWriter.write(
            documentTitle = "T", sheetName = "S", headerFields = emptyList(),
            columns = preciseColumns,
            rows = listOf(
                listOf(
                    DocumentExcelWriter.Value.Text("SKU-1"),
                    DocumentExcelWriter.Value.Text("Widget"),
                    DocumentExcelWriter.Value.Integer(40),
                    DocumentExcelWriter.Value.Decimal(0.000093)
                )
            ),
            totals = mapOf(3 to DocumentExcelWriter.Value.Decimal(0.003720))
        )

        open(bytes).use { wb ->
            val sheet = wb.getSheetAt(0)
            val formatter = DataFormatter()
            val row = (0..sheet.lastRowNum).mapNotNull { sheet.getRow(it) }
                .first { it.getCell(0)?.toString() == "SKU-1" }

            assertEquals("0.000093", formatter.formatCellValue(row.getCell(3)))

            val total = (0..sheet.lastRowNum).mapNotNull { sheet.getRow(it) }
                .first { it.getCell(0)?.toString() == "TOTAL" }
            assertEquals("0.003720", formatter.formatCellValue(total.getCell(3)))
        }
    }

    /**
     * Both of these used to produce a workbook rather than an error: an out-of-range index gave a
     * grey, bold, entirely blank TOTAL row, and totalling column 0 gave a row of figures with
     * nothing saying what they were. Silent is the wrong failure mode for a document.
     */
    @Test
    @DisplayName("a totals map that cannot be laid out is refused outright")
    fun impossibleTotalsAreRefused() {
        val boom = assertThrows<IllegalArgumentException> {
            DocumentExcelWriter.write(
                documentTitle = "T", sheetName = "S", headerFields = emptyList(),
                columns = columns, rows = emptyList(),
                totals = mapOf(99 to DocumentExcelWriter.Value.Integer(1))
            )
        }
        assertTrue(boom.message!!.contains("do not exist"), boom.message)

        val labelClash = assertThrows<IllegalArgumentException> {
            DocumentExcelWriter.write(
                documentTitle = "T", sheetName = "S", headerFields = emptyList(),
                columns = columns, rows = emptyList(),
                totals = mapOf(0 to DocumentExcelWriter.Value.Integer(1))
            )
        }
        assertTrue(labelClash.message!!.contains("TOTAL label"), labelClash.message)
    }

    @Test
    @DisplayName("dates render as a readable day-month-year")
    fun dateFormatting() {
        assertEquals(
            "12 Aug 2026",
            DocumentExcelWriter.formatDate(java.time.LocalDateTime.of(2026, 8, 12, 14, 30))
        )
        assertEquals(
            "12 Aug 2026 14:30",
            DocumentExcelWriter.formatDateTime(java.time.LocalDateTime.of(2026, 8, 12, 14, 30))
        )
        assertEquals(null, DocumentExcelWriter.formatDate(null as java.time.LocalDateTime?))
    }

    // ── template markup ─────────────────────────────────────────────────────

    /**
     * The GRN's descriptions are built as HTML because the PDF is rendered from a template that
     * styles them. A cell has no renderer, so a customer's description column showed the tags —
     * literally `<div class="product-title">POLAR CABLE CHARGER</div>`.
     *
     * The inputs here are the exact shapes `GrnDataAggregationService` produces.
     */
    @Test
    @DisplayName("template markup is flattened to the text a cell can actually show")
    fun templateMarkupIsFlattened() {
        assertEquals(
            "Box",
            DocumentExcelWriter.plainText("<span class=\"container-badge\">Box</span>")
        )
        assertEquals(
            "Box Pallet of widgets",
            DocumentExcelWriter.plainText("<span class=\"container-badge\">Box</span> Pallet of widgets")
        )
        assertEquals(
            "No Description",
            DocumentExcelWriter.plainText("<span class=\"no-description\">No Description</span>")
        )
    }

    /**
     * Two adjacent blocks must not weld together. Stripping tags to nothing would turn a product
     * and its variant into "POLAR CABLE CHARGERBlack" — a value that is wrong in a way nobody
     * would notice until they tried to match on it.
     */
    @Test
    @DisplayName("adjacent blocks are separated, not welded together")
    fun adjacentBlocksAreSeparated() {
        assertEquals(
            "POLAR CABLE CHARGER Black",
            DocumentExcelWriter.plainText(
                "<div class=\"product-title\">POLAR CABLE CHARGER</div>" +
                    "<div class=\"variant-title\">Black</div>"
            )
        )
    }

    /**
     * "No variant name available" is a placeholder that holds space in the PDF layout. Repeated
     * down a column people filter and sort, it is noise dressed as data — so the block marked
     * `no-variant` is dropped rather than flattened.
     */
    @Test
    @DisplayName("the no-variant placeholder is dropped, not flattened into the description")
    fun noVariantPlaceholderIsDropped() {
        assertEquals(
            "POLAR CABLE CHARGER",
            DocumentExcelWriter.plainText(
                "<div class=\"product-title\">POLAR CABLE CHARGER</div>" +
                    "<div class=\"variant-title no-variant\">No variant name available</div>"
            )
        )
    }

    @Test
    @DisplayName("entities are decoded, and ordinary text is left alone")
    fun entitiesAreDecodedAndPlainTextUntouched() {
        assertEquals("Nuts & Bolts", DocumentExcelWriter.plainText("Nuts &amp; Bolts"))
        assertEquals("12\" cable", DocumentExcelWriter.plainText("12&quot; cable"))
        assertEquals("caf\u00e9", DocumentExcelWriter.plainText("caf&#233;"))
        assertEquals("a b", DocumentExcelWriter.plainText("a&nbsp;b"))

        // Decoded after tags are removed, so text that was genuinely escaped survives as text
        // instead of becoming a tag and disappearing.
        assertEquals("<b> is bold", DocumentExcelWriter.plainText("&lt;b&gt; is bold"))

        // The overwhelmingly common case must be untouched, including nulls.
        assertEquals("Blue Widget", DocumentExcelWriter.plainText("Blue Widget"))
        assertEquals(null, DocumentExcelWriter.plainText(null))
        assertEquals(null, DocumentExcelWriter.plainText("<span></span>"))
    }
}
