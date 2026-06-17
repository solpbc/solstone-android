// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room

fun openSolstonePersistenceDatabase(
    context: Context,
    name: String = "solstone-persistence.db",
): SolstonePersistenceDatabase =
    Room.databaseBuilder(context, SolstonePersistenceDatabase::class.java, name).build()
