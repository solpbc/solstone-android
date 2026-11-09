// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.watch.courier

import kotlin.test.*

class WatchCourierHelloTest {
    @Test fun recognizesVersionsWithoutTreatingArbitraryBytesAsContact() {
        val future = byteArrayOf(0,0,0,3, 0,0,0,9, 0,0,0,4)
        assertEquals(WatchCourierHello(3, 9, 4), WatchCourierHello.decode(future, 3))
        assertNull(WatchCourierHello.decode(future, 4))
        assertNull(WatchCourierHello.decode(future.copyOf(8), 3))
        assertNull(WatchCourierHello.decode(future + byteArrayOf(0), 3))
        assertNull(WatchCourierHello.decode(byteArrayOf(0,0,0,3, 0,0,0,0, 0,0,0,4), 3))
        assertNull(WatchCourierHello.decode(byteArrayOf(0,0,0,3, 0,0,0,1, -1,-1,-1,-2), 3))
    }
}
