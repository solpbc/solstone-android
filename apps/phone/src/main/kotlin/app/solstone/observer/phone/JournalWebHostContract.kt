// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.pl.parseJson
import java.math.BigDecimal

internal sealed class JournalWebHostContractParse {
    data class Product(val value: String) : JournalWebHostContractParse()
    data object Refused : JournalWebHostContractParse()
}

internal fun parseJournalWebHostContract(bytes: ByteArray): JournalWebHostContractParse {
    val root = runCatching { parseJson(bytes.toString(Charsets.UTF_8)) }.getOrNull() as? Map<*, *>
        ?: return JournalWebHostContractParse.Refused
    val version = root["version"] as? BigDecimal ?: return JournalWebHostContractParse.Refused
    if (version.compareTo(BigDecimal.ONE) != 0 || version.scale() > 0) {
        return JournalWebHostContractParse.Refused
    }
    val product = root["user_agent_product"] as? String ?: return JournalWebHostContractParse.Refused
    if (product.isEmpty() || product.any { it <= ' ' || it.code == 0x7f }) {
        return JournalWebHostContractParse.Refused
    }
    return JournalWebHostContractParse.Product(product)
}

internal fun journalUserAgent(defaultAgent: String, product: String?): String =
    if (product == null) defaultAgent else "$defaultAgent $product"
