package com.ollee.companion.feature

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise/sunset computation (NOAA / "sunrise equation"), pure Kotlin.
 *
 * Runs entirely on the phone from a GPS fix, so this feature is fully working
 * without any watch protocol. Pushing the computed times to the watch face is
 * a separate write whose bytes still need a capture (see OlleeRepository).
 */
object SunCalculator {

    /** @param dateMillis any instant on the target local day. */
    fun compute(latitude: Double, longitude: Double, dateMillis: Long): SunTimes {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = dateMillis
        val n = dayOfYear(cal)

        val sunrise = solarEvent(latitude, longitude, n, isSunrise = true, dateMillis)
        val sunset = solarEvent(latitude, longitude, n, isSunrise = false, dateMillis)
        return SunTimes(sunrise, sunset)
    }

    private fun dayOfYear(cal: Calendar): Int = cal[Calendar.DAY_OF_YEAR]

    private fun solarEvent(
        lat: Double, lng: Double, n: Int, isSunrise: Boolean, dateMillis: Long,
    ): Long? {
        val zenith = 90.833 // official sunrise/sunset, accounts for refraction
        val d2r = PI / 180.0
        val r2d = 180.0 / PI

        val lngHour = lng / 15.0
        val t = if (isSunrise) n + ((6 - lngHour) / 24.0) else n + ((18 - lngHour) / 24.0)

        val m = (0.9856 * t) - 3.289
        var l = m + (1.916 * sin(m * d2r)) + (0.020 * sin(2 * m * d2r)) + 282.634
        l = (l + 360) % 360

        var ra = r2d * atan(0.91764 * tan(l * d2r))
        ra = (ra + 360) % 360
        val lQuadrant = floor(l / 90.0) * 90
        val raQuadrant = floor(ra / 90.0) * 90
        ra = (ra + (lQuadrant - raQuadrant)) / 15.0

        val sinDec = 0.39782 * sin(l * d2r)
        val cosDec = cos(asin(sinDec))

        val cosH = (cos(zenith * d2r) - (sinDec * sin(lat * d2r))) / (cosDec * cos(lat * d2r))
        if ((cosH > 1) || (cosH < -1)) return null // sun never rises/sets that day

        var h = if (isSunrise) 360 - (r2d * acos(cosH)) else r2d * acos(cosH)
        h /= 15.0

        val localT = (h + ra) - (0.06571 * t) - 6.622
        val utc = (((localT - lngHour) % 24) + 24) % 24

        val day = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        day.timeInMillis = dateMillis
        day[Calendar.HOUR_OF_DAY] = 0
        day[Calendar.MINUTE] = 0
        day[Calendar.SECOND] = 0
        day[Calendar.MILLISECOND] = 0
        return day.timeInMillis + (utc * 3600_000L).toLong()
    }
}
