// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

/**
 * Whether the paired journal has refused this phone's current pairing.
 *
 * A refusal is recorded for one [PairingGeneration], so pairing again (a new generation) never
 * inherits it. It is set when the journal refuses the phone (a rejected certificate, or a 401/403
 * on the status probe or on a send) and cleared only by an authorized answer from the same
 * generation. A run that never reached the journal (offline, busy) leaves it as it was.
 */
interface PairingRefusalStore {
    fun refusedFor(pairing: PairingGeneration): Boolean
    fun markRefused(pairing: PairingGeneration)
    fun markAuthorized(pairing: PairingGeneration)
    fun addListener(listener: () -> Unit): () -> Unit
}
