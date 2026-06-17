// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertIs

class HarnessRevokedTest {
    @Test
    fun neverPairedIsDistinctFromStoredButUnusablePairing() {
        assertIs<HarnessPlStatus.NotPaired>(
            RealPlStatusProbe(FakeEndpointStore(), FakeCredentialStore(), FakeIdentityStore()).probe(),
        )
        assertIs<HarnessPlStatus.PairedButUnreachable>(
            RealPlStatusProbe(FakeEndpointStore(), FakeCredentialStore(credential()), FakeIdentityStore()).probe(),
        )
    }
}
