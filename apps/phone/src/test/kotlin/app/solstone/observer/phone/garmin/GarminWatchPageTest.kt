// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.garmin

import app.solstone.core.watch.courier.GarminSlv3Protocol
import app.solstone.observer.phone.garmin.GarminWatchPage.DeadEnd
import app.solstone.observer.phone.wear.PhoneWatchContactStore
import app.solstone.observer.phone.wear.WatchPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The watch page names one Garmin dead end at a time, the one the owner must clear first. */
class GarminWatchPageTest {
    private fun contact(version: Int? = GarminSlv3Protocol.PROTO_VERSION, storageFull: Boolean = false) =
        PhoneWatchContactStore.Contact(at = 1L, version = version, sealedWaiting = 0, platform = WatchPlatform.GARMIN, storageFull = storageFull)

    private fun status(
        connect: GarminEnvironmentProbe.ConnectAppStatus = GarminEnvironmentProbe.ConnectAppStatus.READY,
        device: GarminDeviceStatus = GarminDeviceStatus.CONNECTED,
        app: GarminWatchAppStatus = GarminWatchAppStatus.Installed(1),
    ) = GarminStatusSnapshot(connectAppStatus = connect, deviceStatus = device, deviceName = null, watchAppStatus = app)

    @Test
    fun aHealthyFreshLinkNamesNothing() {
        assertNull(GarminWatchPage.deadEnd(status(), contact(), stale = false))
    }

    @Test
    fun garminConnectComesBeforeEverythingElse() {
        val missing = status(GarminEnvironmentProbe.ConnectAppStatus.NOT_INSTALLED, GarminDeviceStatus.NOT_PAIRED, GarminWatchAppStatus.NotInstalled)
        assertEquals(DeadEnd.CONNECT_MISSING, GarminWatchPage.deadEnd(missing, contact(storageFull = true), stale = true))
        val old = status(GarminEnvironmentProbe.ConnectAppStatus.UPGRADE_NEEDED, GarminDeviceStatus.NOT_PAIRED)
        assertEquals(DeadEnd.CONNECT_OLD, GarminWatchPage.deadEnd(old, contact(), stale = true))
    }

    @Test
    fun thenPairingThenTheWatchApp() {
        assertEquals(DeadEnd.NOT_PAIRED, GarminWatchPage.deadEnd(status(device = GarminDeviceStatus.NOT_PAIRED, app = GarminWatchAppStatus.NotInstalled), contact(), stale = true))
        assertEquals(DeadEnd.APP_MISSING, GarminWatchPage.deadEnd(status(app = GarminWatchAppStatus.NotInstalled), contact(storageFull = true), stale = true))
    }

    @Test
    fun thenVersionsThenAFullWatchThenSilence() {
        assertEquals(DeadEnd.WATCH_TOO_OLD, GarminWatchPage.deadEnd(status(), contact(version = GarminSlv3Protocol.PROTO_VERSION - 1, storageFull = true), stale = true))
        assertEquals(DeadEnd.PHONE_TOO_OLD, GarminWatchPage.deadEnd(status(), contact(version = GarminSlv3Protocol.PROTO_VERSION + 1), stale = true))
        assertEquals(DeadEnd.WATCH_FULL, GarminWatchPage.deadEnd(status(), contact(storageFull = true), stale = true))
        assertEquals(DeadEnd.QUIET, GarminWatchPage.deadEnd(status(), contact(), stale = true))
    }

    @Test
    fun anUnknownStatusNeverInventsADeadEnd() {
        val unknown = status(device = GarminDeviceStatus.UNKNOWN, app = GarminWatchAppStatus.Unknown)
        assertNull(GarminWatchPage.deadEnd(unknown, contact(), stale = false))
        assertNull(GarminWatchPage.deadEnd(null, contact(), stale = false))
    }
}
