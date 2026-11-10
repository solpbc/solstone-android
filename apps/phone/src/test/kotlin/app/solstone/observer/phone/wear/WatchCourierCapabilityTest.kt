// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.phone.wear

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.wearable.WearableStatusCodes
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class WatchCourierCapabilityTest {
    @Test
    fun existingCapabilityAllowsTheCallerToContinue() {
        acceptExistingCourierCapability {
            throw ExecutionException(ApiException(Status(WearableStatusCodes.DUPLICATE_CAPABILITY)))
        }
    }

    @Test
    fun otherRegistrationFailuresArePreserved() {
        val failure = ExecutionException(ApiException(Status(WearableStatusCodes.TARGET_NODE_NOT_CONNECTED)))
        assertSame(failure, assertFailsWith<ExecutionException> {
            acceptExistingCourierCapability { throw failure }
        })
    }
}
