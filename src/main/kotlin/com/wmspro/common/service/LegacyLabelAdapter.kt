package com.wmspro.common.service

import com.wmspro.common.service.BarcodePrintingUtility.BarcodeInfo
import com.wmspro.common.service.WarehouseLabelRenderer.LabelFormat
import com.wmspro.common.service.WarehouseLabelRenderer.LabelSpec
import org.springframework.stereotype.Service

/**
 * Bridges the long-standing [BarcodeInfo] request shape onto [WarehouseLabelRenderer].
 *
 * Seven call sites across Inventory, Inbound and Task build [BarcodeInfo] objects today, and
 * they cover live paths - ASN creation, receiving completion, mobile printing, box and pallet
 * creation. Rewriting all of them to a new request shape in one change would put every one of
 * those paths at risk simultaneously. This adapter reduces each migration to swapping which
 * renderer is called, so the paths can move one at a time and be reverted individually.
 *
 * It is intentionally a translation layer with a limited life. Once every call site has moved
 * and been verified, call sites should construct [LabelSpec] directly and both this adapter and
 * [BarcodePrintingUtility] can be retired.
 */
@Service
class LegacyLabelAdapter(
    private val warehouseLabelRenderer: WarehouseLabelRenderer
) {
    /**
     * Whether an item-type label should print on the large branded stock or the small sticker.
     * Mirrors the per-SKU setting held in Product Service so that callers which have already
     * loaded the SKU can pass the decision through without this module depending on that service.
     */
    enum class ItemLabelSize { NORMAL, BIG }

    /**
     * Resolves the label format for a legacy item type string.
     *
     * Returns null for the small item sticker, which this renderer does not produce - that
     * format is unchanged by the redesign and stays on [BarcodePrintingUtility]. A null return
     * is the signal to keep using the old path, not an error.
     */
    fun resolveFormat(itemType: String, itemLabelSize: ItemLabelSize = ItemLabelSize.NORMAL): LabelFormat? =
        when (itemType.uppercase()) {
            "BOX" -> LabelFormat.BOX
            "PALLET" -> LabelFormat.PALLET
            "ITEM", "SKU_ITEM" -> if (itemLabelSize == ItemLabelSize.BIG) LabelFormat.ITEM_BIG else null
            else -> null
        }

    /**
     * Translates a [BarcodeInfo] into a [LabelSpec].
     *
     * Box and pallet indices collapse into one sequence pair because the label prints a single
     * "n / total" and only one of the two is ever populated for a given label.
     */
    fun toLabelSpec(info: BarcodeInfo): LabelSpec =
        LabelSpec(
            barcodeValue = info.barcodeText,
            customerName = info.accountName,
            itemName = info.skuName,
            skuCode = info.skuCode,
            receivedDate = info.receivedDate,
            clientReference = info.clientReference,
            sequenceIndex = info.boxIndex ?: info.palletIndex,
            sequenceTotal = info.totalBoxesNum ?: info.totalPalletsNum
        )

    /**
     * Renders a batch that shares one item type, returning Base64 to match the existing
     * endpoints' response convention.
     *
     * Falls back to the legacy renderer when the format is one this renderer does not handle,
     * so a caller can switch to this method unconditionally and still get correct output for
     * small item stickers.
     */
    fun renderBase64(
        itemType: String,
        infos: List<BarcodeInfo>,
        itemLabelSize: ItemLabelSize = ItemLabelSize.NORMAL,
        legacyFallback: BarcodePrintingUtility
    ): String {
        val format = resolveFormat(itemType, itemLabelSize)
            ?: return legacyFallback.generateBarcodePDFWithInfo(itemType, infos)

        return warehouseLabelRenderer.renderLabelsBase64(format, infos.map(::toLabelSpec))
    }
}
