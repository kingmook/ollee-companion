package com.ollee.companion.feature

/** Alarm with configurable days, chime and snooze (UI model; encoding TBD). */
data class AlarmConfig(
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    /** Sun=0 .. Sat=6 -> true if the alarm fires that day. */
    val days: List<Boolean> = List(7) { false },
    val chime: Boolean = true,
    val snoozeMinutes: Int = 5,
)

/** Result of the on-phone sunrise/sunset computation. */
data class SunTimes(
    val sunriseEpoch: Long?,
    val sunsetEpoch: Long?,
)
