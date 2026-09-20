// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

/**
 * Removing this device's own record from the journal it is paired to.
 *
 * Unpairing used to be a purely local act: the phone dropped its credential and the journal kept a
 * live record of a device that was never coming back, with nothing on either side saying so. The
 * owner's word for it is `unpair`, and an unpair that only one side hears is not one.
 *
 * ⚠ The identifier is the device's own client-certificate digest, which is what the journal keys
 * its record by — the pairing handshake already refuses unless the two agree on it, so a device
 * asking to remove that identifier is asking to remove itself.
 */
sealed interface ClientRevokeResult {
    /** The journal removed the record. */
    data object Removed : ClientRevokeResult

    /** The journal has no such record — already gone, which is the state the caller wanted. */
    data object NotFound : ClientRevokeResult

    /** The journal could not be reached, or refused. The caller still owns the local half. */
    data class Failure(val status: Int?, val message: String?) : ClientRevokeResult
}

fun revokeClient(
    client: PlHttpClient,
    cid: String,
    maxResponseBytes: Int = 64 * 1024,
): ClientRevokeResult = try {
    val response = client.request(
        method = "DELETE",
        path = "/app/network/api/clients/${encodeCidPathSegment(cid)}",
        headers = mapOf("Accept" to "application/json", "Cache-Control" to "no-cache"),
        body = null,
        maxResponseBytes = maxResponseBytes,
    )
    when (response.status) {
        200, 204 -> ClientRevokeResult.Removed
        404 -> ClientRevokeResult.NotFound
        else -> ClientRevokeResult.Failure(response.status, response.bodyText())
    }
} catch (e: Exception) {
    ClientRevokeResult.Failure(null, e.message)
}

/**
 * A cid is `sha256:` plus 64 lowercase hex characters, and the colon is the only byte in it that
 * a path segment cannot carry literally. ⛔ Nothing else is escaped, because nothing else can
 * legally appear: a value that is not that shape is refused rather than encoded, so a malformed
 * identifier can never be smuggled into a path.
 */
internal fun encodeCidPathSegment(cid: String): String {
    val digest = cid.removePrefix("sha256:")
    require(cid.startsWith("sha256:")) { "cid must carry its digest algorithm" }
    require(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }) {
        "cid must be a lowercase sha-256 digest"
    }
    return "sha256%3A$digest"
}
