package com.akimoto.fieldtrack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DayManagerTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun open_day_before_end_hour_stays_open() {
        assertFalse(DayManager.shouldAutoEnd(true, at(3, 9, 15), 21, at(3, 18, 40)))
    }

    @Test
    fun open_day_closes_at_end_hour() {
        assertTrue(DayManager.shouldAutoEnd(true, at(3, 9, 15), 21, at(3, 21, 0)))
    }

    @Test
    fun day_left_open_overnight_closes_next_morning() {
        assertTrue(DayManager.shouldAutoEnd(true, at(3, 9, 15), 21, at(4, 7, 30)))
    }

    @Test
    fun day_started_late_in_the_evening_runs_until_midnight() {
        assertFalse(DayManager.shouldAutoEnd(true, at(3, 21, 30), 21, at(3, 23, 10)))
        assertTrue(DayManager.shouldAutoEnd(true, at(3, 21, 30), 21, at(4, 0, 5)))
    }

    @Test
    fun closed_day_never_auto_ends() {
        assertFalse(DayManager.shouldAutoEnd(false, at(3, 9, 15), 21, at(4, 9, 0)))
    }
}
