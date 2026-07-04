// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.camera.camera2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class CameraOpenCoordinator<T>(private val close: (T) -> Unit) {
    private val lock = Any()
    private val opened = CountDownLatch(1)
    private var state = State.WAITING
    private var device: T? = null

    fun onOpened(device: T) {
        var closeLate = false
        synchronized(lock) {
            if (state == State.WAITING) {
                this.device = device
                state = State.OPENED
                opened.countDown()
            } else {
                closeLate = true
            }
        }
        if (closeLate) close(device)
    }

    fun onFailed(device: T? = null) {
        var closeDevice = false
        synchronized(lock) {
            if (state == State.WAITING) {
                state = State.FAILED
                opened.countDown()
                closeDevice = device != null
            } else if (device != null) {
                closeDevice = true
            }
        }
        if (closeDevice && device != null) close(device)
    }

    fun awaitOpen(timeoutMs: Long): T? {
        if (!opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            synchronized(lock) {
                if (state == State.WAITING) {
                    state = State.TIMED_OUT
                }
            }
            return null
        }
        return synchronized(lock) {
            if (state == State.OPENED) device else null
        }
    }

    private enum class State {
        WAITING,
        OPENED,
        FAILED,
        TIMED_OUT,
    }
}
