package com.ollee.companion.feature

/** Result of the on-phone sunrise/sunset computation. */
data class SunTimes(
    val sunriseEpoch: Long?,
    val sunsetEpoch: Long?,
)
