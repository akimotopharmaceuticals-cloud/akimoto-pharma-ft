package com.akimoto.fieldtrack

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Alerts shown to the MR, separate from the quiet "tracking on" notification. */
object Notifier {
    private const val CHANNEL = "alerts"
    private const val ID_LOCATION_OFF = 44
    private const val ID_RESUME = 45
    private const val ID_AUTO_END = 46

    private fun manager(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Work-day alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Location switched off, tracking stopped, day closed automatically"
                }
            )
        }
        return nm
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun base(ctx: Context, title: String, text: String) =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_leaf)
            .setColor(ContextCompat.getColor(ctx, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)

    private fun post(ctx: Context, id: Int, builder: NotificationCompat.Builder) {
        try {
            manager(ctx).notify(id, builder.build())
        } catch (e: SecurityException) {
            // Notification permission not granted; nothing else to do.
        }
    }

    fun locationOff(ctx: Context) {
        val settings = PendingIntent.getActivity(
            ctx, 2, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        post(
            ctx, ID_LOCATION_OFF,
            base(ctx, "Location is OFF", "Your work day is open but phone location is switched off. Turn it on so your calls and travel are recorded.")
                .setContentIntent(settings)
                .addAction(0, "Turn on location", settings)
                .setOngoing(true)
        )
    }

    fun cancelLocationOff(ctx: Context) = manager(ctx).cancel(ID_LOCATION_OFF)

    fun resumeReminder(ctx: Context) {
        post(
            ctx, ID_RESUME,
            base(ctx, "Tap to resume tracking", "Your phone restarted or the app was updated while your work day was open. Open the app to continue recording.")
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
        )
    }

    fun cancelResume(ctx: Context) = manager(ctx).cancel(ID_RESUME)

    fun autoEnded(ctx: Context, km: Double, endHour: Int) {
        post(
            ctx, ID_AUTO_END,
            base(ctx, "Work day closed automatically", "Your day was still open after %d:00, so it was closed and tracking stopped. Distance: %.1f km.".format(endHour, km))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
        )
    }
}
