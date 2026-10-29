package com.akimoto.fieldtrack

import android.content.Context
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import java.util.Calendar

/** Rules for opening and closing the work day, shared by the screens, the service and the boot receiver. */
object DayManager {

    /**
     * True when an open day should be closed automatically:
     * the calendar date has changed, or it is past the day-end hour
     * (unless the day itself was started after that hour, e.g. a late meeting).
     */
    fun shouldAutoEnd(prefs: Prefs, now: Long = System.currentTimeMillis()): Boolean =
        shouldAutoEnd(prefs.dayActive, prefs.dayStart, prefs.dayEndHour, now)

    /** Pure version of the rule above, so it can be unit-tested without a phone. */
    fun shouldAutoEnd(dayActive: Boolean, dayStart: Long, endHour: Int, now: Long): Boolean {
        if (!dayActive) return false
        val start = Calendar.getInstance().apply { timeInMillis = dayStart }
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val sameDate = start.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
            start.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
        if (!sameDate) return true
        return cal.get(Calendar.HOUR_OF_DAY) >= endHour && start.get(Calendar.HOUR_OF_DAY) < endHour
    }

    /** The time an automatically closed day is recorded as ending (the day-end hour, or midnight). */
    private fun autoEndTime(prefs: Prefs, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = prefs.dayStart
            val startHour = get(Calendar.HOUR_OF_DAY)
            if (startHour < prefs.dayEndHour) {
                set(Calendar.HOUR_OF_DAY, prefs.dayEndHour)
            } else {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
            }
            if (startHour < prefs.dayEndHour) set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return minOf(now, cal.timeInMillis)
    }

    /**
     * Closes the open day and returns the GPS kilometres. Does not stop the tracking service;
     * the caller does that (the service stops itself, screens call [TrackingService.stop]).
     */
    fun endDay(ctx: Context, auto: Boolean): Double {
        val prefs = Prefs(ctx)
        if (!prefs.dayActive) return 0.0
        val db = Db.get(ctx)
        val now = System.currentTimeMillis()
        val endTs = if (auto) autoEndTime(prefs, now) else now
        val km = Geo.km(db.pingsForDay(prefs.dayId).filter { it.ts <= endTs })
        db.endDay(prefs.dayId, endTs, km, auto)
        prefs.dayActive = false
        prefs.locationOffSince = 0L
        Notifier.cancelLocationOff(ctx)
        return km
    }

    fun isLocationOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }
}
