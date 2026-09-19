// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MuxCartesianOracleHostTest {

    data class Coordinate(
        val streamId: Int,
        val flags: Int,
        val payloadLength: Int,
        val activeStreamId: Int,
        val nextStreamId: Int,
        val isPreOpen: Boolean,
    )

    private fun computeExpectedOutcome(c: Coordinate): MuxClassification {
        val s = c.streamId
        val f = c.flags
        val len = c.payloadLength
        val active = c.activeStreamId
        val next = c.nextStreamId
        val pre = c.isPreOpen

        if ((f and 0x80) != 0) return MuxClassification.SESSION_TERMINAL_RESERVED
        if (len < 0 || len > 65536) return MuxClassification.SESSION_TERMINAL_OVERSIZED
        if (s < 0) return MuxClassification.SESSION_TERMINAL_NEGATIVE_ID

        if (s == 0) {
            return when {
                f == 0x20 && len == 8 -> MuxClassification.CONTROL_PING
                f == 0x40 && len == 8 -> MuxClassification.CONTROL_PONG
                else -> MuxClassification.SESSION_TERMINAL_MALFORMED_CONTROL
            }
        }

        if (f == 0 && len > 0) return MuxClassification.SESSION_TERMINAL_ZERO_MASK_NONZERO_PAYLOAD
        if (f == 0x10 && len != 4) return MuxClassification.SESSION_TERMINAL_MALFORMED_SHAPE
        if (f == 0x08 && len != 1) return MuxClassification.SESSION_TERMINAL_MALFORMED_SHAPE
        if ((f == 0x20 || f == 0x40) && len != 8) return MuxClassification.SESSION_TERMINAL_MALFORMED_CONTROL
        if ((f == 0x01 || f == 0x04) && len != 0) return MuxClassification.SESSION_TERMINAL_MALFORMED_SHAPE

        if ((f and 0x60) != 0) {
            return when {
                s == active -> MuxClassification.ACTIVE_INVALID_FLAGS
                s % 2 == 0 -> MuxClassification.FOREIGN_EVEN_RESET
                s < next -> MuxClassification.PAST_LOCAL_DROP
                else -> MuxClassification.SESSION_TERMINAL_FUTURE_LOCAL
            }
        }

        if (s == active) {
            if (pre) {
                if (f == 0x10 && len == 4) return MuxClassification.ACTIVE_WINDOW
                if (f == 0x08 && len == 1) return MuxClassification.ACTIVE_RESET
                if (f == 0x01 && len == 0) return MuxClassification.ACTIVE_DATA
                if (f == 0x03) return MuxClassification.ACTIVE_DATA
                if (f == 0x05 && len == 0) return MuxClassification.ACTIVE_CLOSE
                if (f == 0x07) return MuxClassification.ACTIVE_DATA
                if (f == 0x02 || f == 0x06) return MuxClassification.ACTIVE_DATA
                if (f == 0x04 && len == 0) return MuxClassification.ACTIVE_CLOSE
                return MuxClassification.ACTIVE_INVALID_FLAGS
            } else {
                if ((f and 0x08) != 0 && (f and 0x02) != 0) return MuxClassification.ACTIVE_INVALID_FLAGS
                if ((f and 0x01) != 0) return MuxClassification.ACTIVE_INVALID_FLAGS
                if (f == 0x08) return MuxClassification.ACTIVE_RESET
                if (f == 0x02 || f == 0x06) return MuxClassification.ACTIVE_DATA
                if (f == 0x10) return MuxClassification.ACTIVE_WINDOW
                if (f == 0x04) return MuxClassification.ACTIVE_CLOSE
                return MuxClassification.ACTIVE_INVALID_FLAGS
            }
        }

        if (s % 2 == 0) {
            return if (f == 0x08) MuxClassification.FOREIGN_EVEN_IGNORE else MuxClassification.FOREIGN_EVEN_RESET
        }

        return if (s < next) {
            MuxClassification.PAST_LOCAL_DROP
        } else {
            MuxClassification.SESSION_TERMINAL_FUTURE_LOCAL
        }
    }

    @Test
    fun cartesianOracleCoordinateSetEquality() {
        val streamIds = listOf(-1, 0, 1, 2, 3, 5, 6, 7)
        val payloadLengths = listOf(0, 1, 4, 8, 1024, 65536, 65537)
        val activeStreamId = 1
        val nextStreamId = 5

        val expectedMap = mutableMapOf<Coordinate, MuxClassification>()
        val actualMap = mutableMapOf<Coordinate, MuxClassification>()

        for (flags in 0..255) {
            for (streamId in streamIds) {
                for (length in payloadLengths) {
                    for (isPreOpen in listOf(true, false)) {
                        val coord = Coordinate(streamId, flags, length, activeStreamId, nextStreamId, isPreOpen)
                        val expected = computeExpectedOutcome(coord)
                        val actual = MuxClassifier.classify(
                            streamId = streamId,
                            flags = flags,
                            payloadLength = length,
                            activeStreamId = activeStreamId,
                            nextStreamId = nextStreamId,
                            isPreOpen = isPreOpen,
                        )
                        expectedMap[coord] = expected
                        actualMap[coord] = actual
                    }
                }
            }
        }

        assertEquals(expectedMap.size, actualMap.size)
        assertEquals(expectedMap, actualMap)
    }

    @Test
    fun omittingOneCoordinateFailsEquality() {
        val streamIds = listOf(0, 1, 2)
        val payloadLengths = listOf(0, 8)
        val expectedMap = mutableMapOf<Coordinate, MuxClassification>()
        val actualMap = mutableMapOf<Coordinate, MuxClassification>()

        for (flags in 0..10) {
            for (streamId in streamIds) {
                for (length in payloadLengths) {
                    val coord = Coordinate(streamId, flags, length, 1, 3, false)
                    expectedMap[coord] = computeExpectedOutcome(coord)
                    actualMap[coord] = MuxClassifier.classify(streamId, flags, length, 1, 3, false)
                }
            }
        }

        val keyToRemove = expectedMap.keys.first()
        val truncatedExpected = expectedMap.toMutableMap()
        truncatedExpected.remove(keyToRemove)

        assertNotEquals(truncatedExpected, actualMap)
    }

    @Test
    fun invertingOneOutcomeFailsEquality() {
        val coord = Coordinate(1, 0x02, 10, 1, 3, false)
        val expected = mutableMapOf(coord to MuxClassification.ACTIVE_DATA)
        val actual = mutableMapOf(coord to MuxClassification.ACTIVE_INVALID_FLAGS)
        assertNotEquals(expected, actual)
    }

    @Test
    fun reservedFlagsTerminalBeforeAllocation() {
        for (flags in 0x80..0xff) {
            val res = MuxClassifier.classify(
                streamId = 1,
                flags = flags,
                payloadLength = 10,
                activeStreamId = 1,
                nextStreamId = 3,
                isPreOpen = false,
            )
            assertEquals(MuxClassification.SESSION_TERMINAL_RESERVED, res)
        }
    }
}
