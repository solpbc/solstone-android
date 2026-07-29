// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

enum class G3ProgressState(
    val sequence: Int,
    val wireName: String,
) {
    PARTIAL_RESPONSE_CONSUMED(1, "partial_response_consumed"),
    INTERRUPTED_REQUEST_FAILED(2, "interrupted_request_failed"),
    DEGRADED_STATUS_RECORDED(3, "degraded_status_recorded"),
    NETWORK_RESTORE_OBSERVED(4, "network_restore_observed"),
}

data class G3ProgressRecord(
    val sequence: Int,
    val state: String,
    val partialBodyBytes: Int,
)

class G3ProgressOrder(private val expectedBodyBytes: Int) {
    private var nextSequence = 1

    init {
        require(expectedBodyBytes > 0)
    }

    fun advance(state: G3ProgressState, partialBodyBytes: Int): G3ProgressRecord {
        require(state.sequence == nextSequence) { "g3 progress out of order" }
        require(partialBodyBytes > 0 && partialBodyBytes < expectedBodyBytes) {
            "g3 progress requires a partial response"
        }
        nextSequence += 1
        return G3ProgressRecord(state.sequence, state.wireName, partialBodyBytes)
    }

    fun isComplete(): Boolean = nextSequence == 5
}
