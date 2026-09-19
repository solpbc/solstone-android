// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

enum class MuxClassification {
    SESSION_TERMINAL_RESERVED,
    SESSION_TERMINAL_OVERSIZED,
    SESSION_TERMINAL_NEGATIVE_ID,
    SESSION_TERMINAL_MALFORMED_CONTROL,
    SESSION_TERMINAL_MALFORMED_SHAPE,
    SESSION_TERMINAL_ZERO_MASK_NONZERO_PAYLOAD,
    SESSION_TERMINAL_FUTURE_LOCAL,
    CONTROL_PING,
    CONTROL_PONG,
    ACTIVE_DATA,
    ACTIVE_WINDOW,
    ACTIVE_CLOSE,
    ACTIVE_RESET,
    ACTIVE_INVALID_FLAGS,
    PAST_LOCAL_DROP,
    FOREIGN_EVEN_RESET,
    FOREIGN_EVEN_IGNORE,
}

object MuxClassifier {
    fun classify(
        streamId: Int,
        flags: Int,
        payloadLength: Int,
        activeStreamId: Int,
        nextStreamId: Int = activeStreamId + 2,
        isPreOpen: Boolean = false,
    ): MuxClassification {
        // 1. Session-terminal pre-checks (before payload allocation)
        if ((flags and FLAG_RESERVED) != 0) {
            return MuxClassification.SESSION_TERMINAL_RESERVED
        }
        if (payloadLength > MAX_DATA_CHUNK_BYTES || payloadLength < 0) {
            return MuxClassification.SESSION_TERMINAL_OVERSIZED
        }
        if (streamId < 0) {
            return MuxClassification.SESSION_TERMINAL_NEGATIVE_ID
        }

        // 2. Control Stream (Stream ID 0)
        if (streamId == 0) {
            return when (flags) {
                FLAG_PING -> {
                    if (payloadLength == 8) MuxClassification.CONTROL_PING
                    else MuxClassification.SESSION_TERMINAL_MALFORMED_CONTROL
                }
                FLAG_PONG -> {
                    if (payloadLength == 8) MuxClassification.CONTROL_PONG
                    else MuxClassification.SESSION_TERMINAL_MALFORMED_CONTROL
                }
                else -> MuxClassification.SESSION_TERMINAL_MALFORMED_CONTROL
            }
        }

        // 3. One-hot shape & payload checks
        if (flags == 0 && payloadLength > 0) {
            return MuxClassification.SESSION_TERMINAL_ZERO_MASK_NONZERO_PAYLOAD
        }
        if (flags == FLAG_WINDOW && payloadLength != 4) {
            return MuxClassification.SESSION_TERMINAL_MALFORMED_SHAPE
        }
        if (flags == FLAG_RESET && payloadLength != 1) {
            return MuxClassification.SESSION_TERMINAL_MALFORMED_SHAPE
        }
        if ((flags == FLAG_PING || flags == FLAG_PONG) && payloadLength != 8) {
            return MuxClassification.SESSION_TERMINAL_MALFORMED_CONTROL
        }
        if ((flags == FLAG_OPEN || flags == FLAG_CLOSE) && payloadLength != 0) {
            return MuxClassification.SESSION_TERMINAL_MALFORMED_SHAPE
        }

        // Control flags on non-zero stream with valid length (8)
        if ((flags and (FLAG_PING or FLAG_PONG)) != 0) {
            if (streamId == activeStreamId) {
                return MuxClassification.ACTIVE_INVALID_FLAGS
            }
            if (streamId % 2 == 0) {
                return MuxClassification.FOREIGN_EVEN_RESET
            }
            return if (streamId < nextStreamId) {
                MuxClassification.PAST_LOCAL_DROP
            } else {
                MuxClassification.SESSION_TERMINAL_FUTURE_LOCAL
            }
        }

        // 4. Active Local Stream
        if (streamId == activeStreamId) {
            if (isPreOpen) {
                if (flags == FLAG_WINDOW && payloadLength == 4) {
                    return MuxClassification.ACTIVE_WINDOW
                }
                if (flags == FLAG_RESET && payloadLength == 1) {
                    return MuxClassification.ACTIVE_RESET
                }
                if (flags == FLAG_OPEN && payloadLength == 0) {
                    return MuxClassification.ACTIVE_DATA
                }
                if (flags == (FLAG_OPEN or FLAG_DATA)) {
                    return MuxClassification.ACTIVE_DATA
                }
                if (flags == (FLAG_OPEN or FLAG_CLOSE) && payloadLength == 0) {
                    return MuxClassification.ACTIVE_CLOSE
                }
                if (flags == (FLAG_OPEN or FLAG_DATA or FLAG_CLOSE)) {
                    return MuxClassification.ACTIVE_DATA
                }
                if (flags == FLAG_DATA || flags == (FLAG_DATA or FLAG_CLOSE)) {
                    return MuxClassification.ACTIVE_DATA
                }
                if (flags == FLAG_CLOSE && payloadLength == 0) {
                    return MuxClassification.ACTIVE_CLOSE
                }
                return MuxClassification.ACTIVE_INVALID_FLAGS
            } else {
                if ((flags and FLAG_RESET) != 0 && (flags and FLAG_DATA) != 0) {
                    return MuxClassification.ACTIVE_INVALID_FLAGS
                }
                if ((flags and FLAG_OPEN) != 0) {
                    // Duplicate OPEN on active stream
                    return MuxClassification.ACTIVE_INVALID_FLAGS
                }
                if (flags == FLAG_RESET) {
                    return MuxClassification.ACTIVE_RESET
                }
                if (flags == FLAG_DATA || flags == (FLAG_DATA or FLAG_CLOSE)) {
                    return MuxClassification.ACTIVE_DATA
                }
                if (flags == FLAG_WINDOW) {
                    return MuxClassification.ACTIVE_WINDOW
                }
                if (flags == FLAG_CLOSE) {
                    return MuxClassification.ACTIVE_CLOSE
                }
                return MuxClassification.ACTIVE_INVALID_FLAGS
            }
        }

        // 5. Foreign Positive-Even Peer Streams
        if (streamId % 2 == 0) {
            return if (flags == FLAG_RESET) {
                MuxClassification.FOREIGN_EVEN_IGNORE
            } else {
                MuxClassification.FOREIGN_EVEN_RESET
            }
        }

        // 6. Positive Odd Non-Active Local Streams
        return if (streamId < nextStreamId) {
            // Past local -> drop, no RESET
            MuxClassification.PAST_LOCAL_DROP
        } else {
            // Future local -> session-terminal
            MuxClassification.SESSION_TERMINAL_FUTURE_LOCAL
        }
    }
}
