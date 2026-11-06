package com.mashwar.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared between the activity (which owns picture-in-picture) and the player screen. */
object PipState {
    /** True while the player screen is showing, so leaving the app shrinks it into a PiP window. */
    @Volatile var playerActive = false
    var inPip by mutableStateOf(false)
    /** Bumped when the user closes the PiP window, so the player can stop. */
    var dismissed by mutableIntStateOf(0)
}
