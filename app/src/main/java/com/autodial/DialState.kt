package com.autodial

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-process state shared between [DialService] and the UI. Resets if the process dies. */
object DialState {
    data class Ui(
        val running: Boolean = false,
        val status: String = "Idle",
        /** Last session result or error; shown until the next start. */
        val lastResult: String? = null,
        val isError: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    fun update(transform: (Ui) -> Ui) = _ui.update(transform)
}
