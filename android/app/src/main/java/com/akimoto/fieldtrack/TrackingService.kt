package com.akimoto.fieldtrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that records the MR's location while the work day is open.
 * It runs only between Start day and End day (or the automatic day-end hour)
 * and always shows a notification.
 */
class TrackingService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private var updating = false
    private var receiverOn = false
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) onLocation(loc)
        }
    }

    /** Fires when the MR switches phone location on or off. */
    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkLocationSwitch()
    }

    /** Safety net that runs even when no location arrives (location off, indoors, phone still). */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!checkAutoEnd()) {
                checkLocationSwitch()
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        val prefs = Prefs(this)
        if (!prefs.dayActive || !hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(this, "Tracking on · waiting for GPS"), type)
        } catch (e: RuntimeException) {
            // Android may refuse to start location tracking while the app is in the background
            // (e.g. the system restarted this service). Ask the MR to open the app instead of crashing.
            Notifier.resumeReminder(this)
            stopSelf()
            return START_NOT_STICKY
        }
        Notifier.cancelResume(this)
        if (checkAutoEnd()) return START_NOT_STICKY
        startUpdates(prefs.intervalMin)
        if (!receiverOn) {
            ContextCompat.registerReceiver(
                this, providerReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverOn = true
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        checkLocationSwitch()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates(intervalMin: Int) {
        if (updating) return
        val ms = intervalMin.coerceIn(1, 60) * 60_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms)
            .setMinUpdateIntervalMillis(ms / 2)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            updating = true
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun onLocation(loc: Location) {
        val prefs = Prefs(this)
        if (!prefs.dayActive) return
        if (checkAutoEnd()) return
        val db = Db.get(this)
        val mock = Geo.isMock(loc)
        db.insertPing(
            dayId = prefs.dayId,
            ts = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            lat = loc.latitude,
            lng = loc.longitude,
            acc = loc.accuracy.toDouble(),
            speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
            battery = batteryPercent(this),
            mock = mock
        )
        updateNotification(if (mock) " · FAKE GPS DETECTED" else "")
        Sync.maybeSync(this, force = false)
    }

    private fun updateNotification(suffix: String) {
        val prefs = Prefs(this)
        val pings = Db.get(this).pingsForDay(prefs.dayId)
        val km = Geo.km(pings)
        val time = pings.lastOrNull()?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.ts)) } ?: "—"
        val text = if (!DayManager.isLocationOn(this)) "Location is OFF · turn it on to keep recording"
        else "Tracking on · %.1f km · last point %s%s".format(km, time, suffix)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(this, text))
    }

    /** Shows or clears the "location is off" alert and remembers when it went off. */
    private fun checkLocationSwitch() {
        val prefs = Prefs(this)
        if (!prefs.dayActive) return
        if (DayManager.isLocationOn(this)) {
            if (prefs.locationOffSince != 0L) {
                prefs.locationOffSince = 0L
                Notifier.cancelLocationOff(this)
                updateNotification("")
            }
        } else if (prefs.locationOffSince == 0L) {
            prefs.locationOffSince = System.currentTimeMillis()
            Notifier.locationOff(this)
            updateNotification("")
        }
    }

    /** Closes a forgotten day. Returns true if the day was closed and the service is stopping. */
    private fun checkAutoEnd(): Boolean {
        val prefs = Prefs(this)
        if (!DayManager.shouldAutoEnd(prefs)) return false
        val km = DayManager.endDay(this, auto = true)
        Notifier.autoEnded(this, km, prefs.dayEndHour)
        Sync.maybeSync(this, force = true)
        stopTracking()
        return true
    }

    private fun stopTracking() {
        handler.removeCallbacks(watchdog)
        if (updating) fused.removeLocationUpdates(callback)
        updating = false
        if (receiverOn) {
            try { unregisterReceiver(providerReceiver) } catch (e: IllegalArgumentException) { }
            receiverOn = false
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        if (updating) fused.removeLocationUpdates(callback)
        updating = false
        if (receiverOn) {
            try { unregisterReceiver(providerReceiver) } catch (e: IllegalArgumentException) { }
            receiverOn = false
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.akimoto.fieldtrack.STOP"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 42
        private const val WATCHDOG_MS = 5 * 60_000L

        fun start(ctx: Context) {
            // A foreground service must call startForeground() quickly, which needs these, so check first.
            if (!Prefs(ctx).dayActive || !hasLocationPermission(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
            } catch (e: RuntimeException) {
                Notifier.resumeReminder(ctx)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, TrackingService::class.java).setAction(ACTION_STOP))
            } catch (e: RuntimeException) {
                // Service not running and app in background: nothing to stop.
            }
        }

        fun hasLocationPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        fun batteryPercent(ctx: Context): Int =
            (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        private fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Work-day tracking", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while your work day is open and location is being recorded"
                    }
                )
            }
        }

        private fun buildNotification(ctx: Context, text: String): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_leaf)
                .setColor(ContextCompat.getColor(ctx, R.color.accent))
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
