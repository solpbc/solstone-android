// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.persistence.room

import androidx.room.Dao
import androidx.room.Query

@Dao
interface WatchSourceDao {
    @Query("SELECT MIN(committed_at) FROM watch_bundle_receipt")
    fun firstReceiptEpochMs(): Long?

    @Query("SELECT COUNT(*) FROM segment s WHERE s.state IN ('SEALED','UPLOADING','FAILED') AND EXISTS (SELECT 1 FROM watch_bundle_receipt r WHERE r.segment_id = s.id)")
    fun heldOnPhone(): Int
}
