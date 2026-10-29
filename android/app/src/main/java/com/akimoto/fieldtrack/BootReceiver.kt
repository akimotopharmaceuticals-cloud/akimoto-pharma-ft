package com.akimoto.fieldtrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a phone restart or an app update the tracking service is not running.
 * Android does not let an app restart location tracking on its own from here,
 * so if a day is open we either close it (if it is too late) or remind the MR to open the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(ctx)
        if (!prefs.dayActive) return
        if (DayManager.shouldAutoEnd(prefs)) {
            val km = DayManager.endDay(ctx, auto = true)
            Notifier.autoEnded(ctx, km, prefs.dayEndHour)
        } else {
            Notifier.resumeReminder(ctx)
        }
    }
}
