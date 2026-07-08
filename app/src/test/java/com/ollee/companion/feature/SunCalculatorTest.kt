package com.ollee.companion.feature

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SunCalculatorTest {

    @Test
    fun testCompute() {
        // San Francisco
        val lat = 37.7749
        val lng = -122.4194
        
        // 2024-06-20 (Summer Solstice)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2024, Calendar.JUNE, 20, 12, 0)
        
        val result = SunCalculator.compute(lat, lng, cal.timeInMillis)
        
        assertNotNull(result.sunriseEpoch)
        assertNotNull(result.sunsetEpoch)
    }
}
