package com.aurorafotos.capture

enum class SessionState { IDLE, COUNTDOWN, CAPTURING, PROCESSING, DONE, ERROR }

data class SessionProgress(
    val state: SessionState = SessionState.IDLE,
    val presetName: String = "",
    val sessionName: String = "",
    /** 1-based shot currently being captured. */
    val shot: Int = 0,
    /** 0 = open-ended. */
    val totalShots: Int = 0,
    /** 1-based frame within the current shot. */
    val frame: Int = 0,
    val framesPerShot: Int = 0,
    val countdown: Int = 0,
    val elapsedMs: Long = 0,
    val message: String = "",
    val error: String? = null,
    /** Exposure actually applied (after clamping), for display. */
    val exposureNs: Long = 0,
    val iso: Int = 0,
) {
    val isActive: Boolean
        get() = state == SessionState.COUNTDOWN || state == SessionState.CAPTURING || state == SessionState.PROCESSING
}
