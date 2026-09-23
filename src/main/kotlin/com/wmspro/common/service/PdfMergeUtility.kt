package com.wmspro.common.service

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Concatenates PDFs that may have different page sizes.
 *
 * Label printing needs this because a single print run can legitimately span several label
 * formats - a receipt containing some SKUs configured for the large branded label and others
 * for the small sticker, or a mix of item, box and pallet labels. Each format has its own page
 * geometry, so they must be rendered separately and then joined; iText preserves each page's
 * own size when copying, which is exactly the behaviour required here.
 */
@Service
class PdfMergeUtility {
    private val logger = LoggerFactory.getLogger(PdfMergeUtility::class.java)

    /**
     * Concatenates the given PDFs in order. Empty inputs are skipped.
     *
     * @throws IllegalArgumentException if there is nothing to merge
     */
    fun merge(documents: List<ByteArray>): ByteArray {
        val usable = documents.filter { it.isNotEmpty() }
        require(usable.isNotEmpty()) { "Cannot merge an empty set of PDFs" }

        // Avoid a pointless decode/re-encode cycle for the common single-format case.
        if (usable.size == 1) return usable.first()

        val output = ByteArrayOutputStream()
        val target = PdfDocument(PdfWriter(output))

        try {
            usable.forEach { bytes ->
                val source = PdfDocument(PdfReader(ByteArrayInputStream(bytes)))
                try {
                    source.copyPagesTo(1, source.numberOfPages, target)
                } finally {
                    source.close()
                }
            }
        } finally {
            target.close()
        }

        val merged = output.toByteArray()
        logger.debug("Merged {} PDFs into one document", usable.size)
        return merged
    }

    /**
     * Merge helper for the Base64-in-JSON convention used by the existing barcode endpoints.
     */
    fun mergeBase64(documentsBase64: List<String>): String {
        val decoder = Base64.getDecoder()
        val decoded = documentsBase64
            .filter { it.isNotBlank() }
            .map { decoder.decode(it) }
        return Base64.getEncoder().encodeToString(merge(decoded))
    }
}
