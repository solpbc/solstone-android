// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE segment ADD COLUMN server_key TEXT")
        db.execSQL("ALTER TABLE segment ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE segment ADD COLUMN last_status_code INTEGER")
        db.execSQL("ALTER TABLE segment ADD COLUMN last_attempt_at INTEGER")
        db.execSQL("ALTER TABLE segment ADD COLUMN dedupe_checked_at INTEGER")
        db.execSQL("ALTER TABLE segment ADD COLUMN last_error TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE segment ADD COLUMN dir_segment TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE segment SET dir_segment = segment")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS segment_new (
                id TEXT NOT NULL,
                day TEXT NOT NULL,
                stream TEXT NOT NULL,
                segment TEXT NOT NULL,
                dir_segment TEXT NOT NULL DEFAULT '',
                state TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                sealed_at INTEGER NOT NULL,
                home_instance_id TEXT,
                observer_handle TEXT,
                attempt_count INTEGER NOT NULL,
                last_status_code INTEGER,
                last_attempt_at INTEGER,
                last_error TEXT,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO segment_new (
                id, day, stream, segment, dir_segment, state, byte_size, sealed_at,
                home_instance_id, observer_handle, attempt_count, last_status_code, last_attempt_at, last_error
            )
            SELECT
                id, day, stream, segment, dir_segment, state, byte_size, sealed_at,
                home_instance_id, observer_handle, attempt_count, last_status_code, last_attempt_at, last_error
            FROM segment
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE segment")
        db.execSQL("ALTER TABLE segment_new RENAME TO segment")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `import_item` (`id` TEXT NOT NULL, `payload_kind` TEXT NOT NULL, `display_name` TEXT NOT NULL, `mime` TEXT NOT NULL, `byte_size` INTEGER NOT NULL, `state` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `progress_path` TEXT, `progress_timestamp` TEXT, `progress_instance_id` TEXT, `backoff_instance_id` TEXT, `attempt_count` INTEGER NOT NULL, `next_attempt_at` INTEGER, `attention_error` TEXT, `attention_reason_code` TEXT, `attention_kind` TEXT, `attention_instance_id` TEXT, `attention_journal_version` TEXT, PRIMARY KEY(`id`))
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `import_delivery` (`id` INTEGER NOT NULL, `last_delivered_at` INTEGER NOT NULL, `instance_id` TEXT NOT NULL, PRIMARY KEY(`id`))
            """.trimIndent(),
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `calendar_preview` (`generation` INTEGER NOT NULL, `identity_key` TEXT NOT NULL, `calendar_id` INTEGER NOT NULL, `start_epoch_ms` INTEGER NOT NULL, `provider_event_ids` TEXT NOT NULL, `body` TEXT NOT NULL, PRIMARY KEY(`generation`, `identity_key`, `calendar_id`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_settings` (`id` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `count_generation` INTEGER NOT NULL, `consent_token` INTEGER NOT NULL, `confirmed_generation` INTEGER, `history_kind` TEXT NOT NULL, `confirmed_history_kind` TEXT, `lower_bound_epoch_day` INTEGER, `confirmed_lower_bound_epoch_day` INTEGER, `fallback_namespace` TEXT NOT NULL, `last_success_at` INTEGER, `import_slice_cursor` INTEGER, `import_done` INTEGER NOT NULL, `import_total` INTEGER, `import_completed` INTEGER NOT NULL, `last_read_failed` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_calendar` (`provider_id` INTEGER NOT NULL, `display_name` TEXT NOT NULL, `owner_account` TEXT, `account_type` TEXT, `selected` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL, `visible` INTEGER NOT NULL, PRIMARY KEY(`provider_id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_count` (`generation` INTEGER NOT NULL, `selection_fingerprint` TEXT NOT NULL, `span` TEXT NOT NULL, `count` INTEGER NOT NULL, `status` TEXT NOT NULL, `per_calendar` TEXT NOT NULL, `import_cursor` INTEGER, PRIMARY KEY(`generation`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_identity` (`event` TEXT NOT NULL, `occurrence` TEXT NOT NULL, `published_fingerprint` TEXT, `published_changed_at` TEXT, `published_changed_at_epoch_ms` INTEGER, `pending_fingerprint` TEXT, `pending_changed_at` TEXT, `pending_changed_at_epoch_ms` INTEGER, `pending_canonical` TEXT, `pending_intent_id` TEXT, `last_title` TEXT, `last_calendar` TEXT, `holders` TEXT NOT NULL, `winning_holder` INTEGER, `name_holder` INTEGER, `miss_count` INTEGER NOT NULL, `first_miss_at` INTEGER, `last_change` TEXT, `touched_at` INTEGER, `last_start_epoch_ms` INTEGER, `provider_event_ids` TEXT NOT NULL, PRIMARY KEY(`event`, `occurrence`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_intent` (`id` TEXT NOT NULL, `day` TEXT NOT NULL, `segment` TEXT NOT NULL, `body` TEXT NOT NULL, `sealed` INTEGER NOT NULL, `identity_keys` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_capability` (`instance_id` TEXT NOT NULL, `ca_fingerprint` TEXT NOT NULL, `calendar_notes` INTEGER NOT NULL, `observed_at` INTEGER NOT NULL, PRIMARY KEY(`instance_id`, `ca_fingerprint`))",
        )
    }
}

fun openSolstonePersistenceDatabase(
    context: Context,
    name: String = "solstone-persistence.db",
): SolstonePersistenceDatabase =
    Room.databaseBuilder(context, SolstonePersistenceDatabase::class.java, name)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
        .build()

