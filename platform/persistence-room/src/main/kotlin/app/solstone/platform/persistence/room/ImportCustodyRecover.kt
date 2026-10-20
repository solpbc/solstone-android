// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import kotlin.concurrent.thread

fun startImportCustodyRecover(context: Context) {
    val appContext = context.applicationContext
    thread(name = "import-custody-recover", isDaemon = false) {
        var db: SolstonePersistenceDatabase? = null
        try {
            db = openSolstonePersistenceDatabase(appContext)
            val store = ImportCustodyStore(
                root = appContext.filesDir.toPath().resolve("import"),
                dao = db.importDao(),
            )
            store.recover()
        } catch (_: Exception) {
            // Background recovery failure must not crash the process
        } finally {
            try {
                db?.close()
            } catch (_: Exception) {
            }
        }
    }
}
