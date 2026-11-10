// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.obsidian

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.solstone.core.model.WireKeys
import app.solstone.core.obsidian.ObsidianLedger
import app.solstone.core.obsidian.PreparedObsidianBatch

/** Source-owned custody; no pairing identity is stored alongside notes or pending bytes. */
class SqliteObsidianLedger(context: Context) : SQLiteOpenHelper(
    context.applicationContext, context.noBackupFilesDir.resolve("obsidian.sqlite").absolutePath, null, 1,
), ObsidianLedger {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(root TEXT NOT NULL,bucket TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(root,bucket,key))")
        db.execSQL("CREATE TABLE batches(root TEXT NOT NULL,vault TEXT NOT NULL,name TEXT NOT NULL,day TEXT NOT NULL,segment TEXT NOT NULL,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,zone TEXT NOT NULL,offset_s INTEGER NOT NULL,lines INTEGER NOT NULL,initial_notes INTEGER NOT NULL,spooled INTEGER NOT NULL,bytes BLOB,PRIMARY KEY(root,name))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unsupported Obsidian ledger version")
    override fun <T> transaction(action: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val result = action(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    // A valid note or frozen file can exceed Android's CursorWindow. Read bounded
    // slices rather than asking a cursor to materialize the whole value in one row.
    override fun get(root: String, bucket: String, key: String): String? {
        val args = arrayOf(root, bucket, key)
        val head = readableDatabase.rawQuery(
            "SELECT length(value),substr(value,1,131072) FROM records WHERE root=? AND bucket=? AND key=?", args,
        ).use { if (it.moveToFirst()) it.getInt(0) to it.getString(1) else null } ?: return null
        if (head.first <= 131072) return head.second
        return buildString {
            append(head.second)
            var position = 131073
            while (position <= head.first) {
                val chunk = readableDatabase.rawQuery(
                    "SELECT substr(value,?,131072) FROM records WHERE root=? AND bucket=? AND key=?",
                    arrayOf(position.toString(), root, bucket, key),
                ).use { check(it.moveToFirst()); it.getString(0) }
                append(chunk); position += 131072
            }
        }
    }
    override fun put(root: String, bucket: String, key: String, value: String) {
        writableDatabase.insertWithOnConflict("records", null, ContentValues().apply {
            put("root", root); put("bucket", bucket); put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Obsidian state was not saved" } }
    }
    override fun remove(root: String, bucket: String, key: String) {
        writableDatabase.delete("records", "root=? AND bucket=? AND key=?", arrayOf(root, bucket, key))
    }
    override fun keys(root: String, bucket: String): List<String> = readableDatabase.query(
        "records", arrayOf("key"), "root=? AND bucket=?", arrayOf(root, bucket), null, null, "key COLLATE BINARY",
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    override fun roots(): List<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT root FROM records WHERE root <> ? ORDER BY root", arrayOf(GLOBAL_ROOT),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    override fun prepare(batch: PreparedObsidianBatch, bytes: ByteArray) {
        val n = writableDatabase.insertWithOnConflict("batches", null, ContentValues().apply {
            put("root", batch.root); put("vault", batch.vault); put("name", batch.name)
            put("day", batch.keys.day); put("segment", batch.keys.segment)
            put("start_ms", batch.keys.startEpochMs); put("end_ms", batch.keys.endEpochMs)
            put("zone", batch.keys.zoneId); put("offset_s", batch.keys.utcOffsetSeconds)
            put("lines", batch.lines); put("initial_notes", batch.initialNotes); put("spooled", 0); put("bytes", bytes)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (n == -1L) check(batches().any { it.root == batch.root && it.name == batch.name }) { "Obsidian batch was not saved" }
    }
    override fun batches(): List<PreparedObsidianBatch> = readableDatabase.query(
        "batches", arrayOf("root", "vault", "name", "day", "segment", "start_ms", "end_ms", "zone", "offset_s", "lines", "initial_notes", "spooled"),
        null, null, null, null, "start_ms,name",
    ).use { c -> buildList { while (c.moveToNext()) add(PreparedObsidianBatch(c.getString(0), c.getString(1), c.getString(2),
        WireKeys(c.getString(3), c.getString(4), c.getLong(5), c.getLong(6), c.getString(7), c.getInt(8)), c.getInt(9), c.getInt(10), c.getInt(11) == 1)) } }
    override fun batchBytes(root: String, name: String): ByteArray? {
        val length = readableDatabase.rawQuery("SELECT length(bytes) FROM batches WHERE root=? AND name=?", arrayOf(root, name))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null } ?: return null
        val out = java.io.ByteArrayOutputStream(length)
        var position = 1
        while (position <= length) {
            val chunk = readableDatabase.rawQuery("SELECT substr(bytes,?,524288) FROM batches WHERE root=? AND name=?",
                arrayOf(position.toString(), root, name)).use { check(it.moveToFirst()); it.getBlob(0) }
            check(chunk.isNotEmpty()); out.write(chunk); position += chunk.size
        }
        return out.toByteArray()
    }
    override fun markSpooled(root: String, name: String) {
        writableDatabase.update("batches", ContentValues().apply { put("spooled", 1) }, "root=? AND name=?", arrayOf(root, name))
    }
    override fun retireBatch(root: String, name: String) {
        writableDatabase.delete("batches", "root=? AND name=?", arrayOf(root, name))
    }
    companion object { const val GLOBAL_ROOT = "@settings" }
}
