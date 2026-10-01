// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

enum class MarkConfirmationRoute { CONFIRM }

/**
 * A manual ask. The once-per-process flag gates only the automatic prompt, so it does not
 * change this result. The confirm route is returned whenever the home is awaiting.
 */
fun manualMarkConfirmationRoute(
    awaitingMarkConfirmation: Boolean,
    promptedConfirmationThisProcess: Boolean,
): MarkConfirmationRoute? =
    if (awaitingMarkConfirmation) MarkConfirmationRoute.CONFIRM else null
