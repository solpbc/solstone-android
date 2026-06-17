// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

sealed interface LoadState<out T> {
    object Loading : LoadState<Nothing>
    data class Loaded<T>(val value: T) : LoadState<T>
    data class Failed(val error: Throwable) : LoadState<Nothing>
}

fun interface BackgroundRunner {
    fun submit(task: () -> Unit)
}

fun interface MainPoster {
    fun post(task: () -> Unit)
}

class AsyncLoad(
    private val background: BackgroundRunner,
    private val main: MainPoster,
) {
    fun <T> load(supplier: () -> T, onState: (LoadState<T>) -> Unit) {
        onState(LoadState.Loading)
        try {
            background.submit {
                val result = runCatching { supplier() }
                main.post {
                    onState(
                        result.fold(
                            onSuccess = { LoadState.Loaded(it) },
                            onFailure = { LoadState.Failed(it) },
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            // Background submission itself failed, so surface it as UI state instead of crashing main.
            main.post { onState(LoadState.Failed(t)) }
        }
    }
}
