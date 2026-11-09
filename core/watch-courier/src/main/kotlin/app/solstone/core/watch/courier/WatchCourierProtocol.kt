// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

object WatchCourierProtocol {
    const val CHANNEL_PATH_BUNDLE = "/solstone/courier/bundle"
    const val MESSAGE_PATH_RECEIPT = "/solstone/courier/receipt"
    const val MESSAGE_PATH_HEARTBEAT = "/solstone/courier/heartbeat"
    const val MESSAGE_PATH_STATUS = "/solstone/courier/status"
    const val CAPABILITY_COURIER = "solstone_courier"

    const val WINDOW_MS = 300_000L
    const val PROTOCOL_VERSION = 1
    const val SOURCE_ID_WATCH_AUDIO = "watch-audio"

    const val FILE_NAME_AUDIO = "audio.m4a"
    const val FILE_NAME_LOCATION = "location.jsonl"
    const val MEDIA_TYPE_AUDIO = "audio/mp4"
    const val MEDIA_TYPE_LOCATION = "application/x-jsonlines"

    const val STORAGE_FLOOR_BYTES = 50L * 1024L * 1024L // 50 MiB
    const val MAX_BUNDLE_BYTES = 32L * 1024L * 1024L // 32 MiB
    const val DEFAULT_BUNDLE_BATCH_CAP = 8

    const val KIND_BUNDLE_CONTROL = 1
    const val KIND_RECEIPT = 2
    const val KIND_HEARTBEAT = 3
    const val KIND_STATUS = 4
}
