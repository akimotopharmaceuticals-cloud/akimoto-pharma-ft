package com.akimoto.fieldtrack

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private lateinit var tvName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvWarning: TextView
    private lateinit var tvPlan: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvVisits: TextView
    private lateinit var tvSync: TextView
    private lateinit var tvApprovals: TextView
    private lateinit var btnDay: Button
    private lateinit var btnVisit: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 10_000)
        }
    }
    private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var syncMsgAt = 0L
    private var starting = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (TrackingService.hasLocationPermission(this)) {
                takeSelfie()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Location permission needed")
                    .setMessage("The app records your location only during your work day. Allow \"Precise location\" while using the app to start your day.")
                    .setPositiveButton("Open settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

    /** Start-of-day selfie, taken with the phone's camera app (no camera permission needed). */
    private val selfieLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
            if (bmp != null) {
                locateAndOpenDay(saveSelfie(bmp))
            } else {
                askStartWithoutSelfie()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = Db.get(this)
        if (prefs.consentAt == 0L || prefs.code.isBlank()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        tvName = findViewById(R.id.tvName)
        tvStatus = findViewById(R.id.tvStatus)
        tvWarning = findViewById(R.id.tvWarning)
        tvPlan = findViewById(R.id.tvPlan)
        tvStats = findViewById(R.id.tvStats)
        tvVisits = findViewById(R.id.tvVisits)
        tvSync = findViewById(R.id.tvSync)
        tvApprovals = findViewById(R.id.tvApprovals)
        btnDay = findViewById(R.id.btnDay)
        btnVisit = findViewById(R.id.btnVisit)

        btnDay.setOnClickListener { if (prefs.dayActive) confirmEndDay() else requestStartDay() }
        btnVisit.setOnClickListener { startActivity(Intent(this, VisitActivity::class.java)) }
        tvWarning.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                toast("Open phone Settings and turn on Location")
            }
        }
        findViewById<Button>(R.id.btnSync).setOnClickListener {
            showSyncMsg("Syncing…")
            Sync.maybeSync(this, force = true) { msg -> showSyncMsg(msg); refresh() }
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_EDIT, true))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askBatteryExemption(force = true) }
        findViewById<Button>(R.id.btnTour).setOnClickListener { startActivity(Intent(this, TourPlanActivity::class.java)) }
        findViewById<Button>(R.id.btnExpenses).setOnClickListener { startActivity(Intent(this, ExpenseActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        if (!::tvName.isInitialized) return
        // A day left open overnight is closed; otherwise make sure tracking is running
        // (it stops after a phone restart or an app update).
        if (DayManager.shouldAutoEnd(prefs)) {
            TrackingService.stop(this)
            val km = DayManager.endDay(this, auto = true)
            AlertDialog.Builder(this)
                .setTitle("Yesterday's day was closed")
                .setMessage("Your work day was still open, so it was closed automatically at %d:00. Distance recorded: %.1f km.".format(prefs.dayEndHour, km))
                .setPositiveButton("OK", null)
                .show()
            Sync.maybeSync(this, force = true)
        } else if (prefs.dayActive && TrackingService.hasLocationPermission(this)) {
            TrackingService.start(this)
        }
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun showSyncMsg(msg: String) {
        syncMsgAt = System.currentTimeMillis()
        tvSync.text = msg
    }

    private fun refresh() {
        tvName.text = "${prefs.name} · ${prefs.code}"
        tvPlan.text = todaysPlan()
        if (prefs.dayActive) {
            val pings = db.pingsForDay(prefs.dayId)
            val visits = db.visitsForDay(prefs.dayId)
            val km = Geo.km(pings)
            val last = pings.lastOrNull()
            tvStatus.text = "Day started ${hhmm.format(Date(prefs.dayStart))} · tracking ON"
            btnDay.text = "End day"
            btnVisit.isEnabled = true
            val mockCount = pings.count { it.mock }
            tvStats.text = buildString {
                append("Distance today: %.1f km\n".format(km))
                append("Location points: ${pings.size}")
                if (last != null) append(" · last at ${hhmm.format(Date(last.ts))} (±${last.acc.toInt()} m)")
                append("\nDoctor calls today: ${visits.size} (${visits.count { it.verified }} verified)")
                if (mockCount > 0) append("\n⚠ Fake-GPS app detected on $mockCount points")
                append("\nDay closes automatically at ${prefs.dayEndHour}:00 if you forget to end it.")
            }
            tvVisits.text = if (visits.isEmpty()) "No calls reported yet today." else
                visits.joinToString("\n\n") { v ->
                    val where = when {
                        v.mock -> "fake GPS detected"
                        v.distM < 0 -> "first visit · clinic location pinned"
                        v.verified -> "verified · ${v.distM.toInt()} m from clinic"
                        else -> "FLAGGED · ${v.distM.toInt()} m from clinic"
                    }
                    "${hhmm.format(Date(v.ts))}  ${v.doctor}\n${v.speciality.ifBlank { "—" }} · ${v.area.ifBlank { "—" }}\n$where"
                }
            val locationOn = DayManager.isLocationOn(this)
            tvWarning.visibility = if (locationOn) View.GONE else View.VISIBLE
        } else {
            tvStatus.text = "Day not started · tracking OFF"
            btnDay.text = "Start day"
            btnVisit.isEnabled = false
            tvStats.text = "Tap Start day when you begin field work. You will take a quick selfie, then your location is recorded until you tap End day."
            tvVisits.text = ""
            tvWarning.visibility = View.GONE
        }
        btnDay.isEnabled = !starting
        tvApprovals.text = approvalsSummary()
        val pending = db.unsyncedCount()
        val server = if (prefs.server.isBlank()) "No server set (data stays on phone)" else "Server: ${prefs.server}"
        val lastSync = if (prefs.lastSync > 0) "last sync ${hhmm.format(Date(prefs.lastSync))}" else "not synced yet"
        if (System.currentTimeMillis() - syncMsgAt > 60_000) {
            tvSync.text = "$server\n$pending items waiting · $lastSync"
        }
    }

    /** Today's area from the tour plan, if one has been made for this month. */
    private fun todaysPlan(): String {
        val cal = Calendar.getInstance()
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(cal.time)
        val area = db.tourDays(month)[cal.get(Calendar.DAY_OF_MONTH)]
        val today = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(cal.time)
        return if (area.isNullOrBlank()) "$today · no tour plan for today" else "$today · plan: $area"
    }

    private fun approvalsSummary(): String {
        val fmt = SimpleDateFormat("yyyy-MM", Locale.US)
        val cal = Calendar.getInstance()
        val thisMonth = fmt.format(cal.time)
        cal.add(Calendar.MONTH, 1)
        val nextMonth = fmt.format(cal.time)
        val label = SimpleDateFormat("MMM", Locale.getDefault())
        val tour = listOf(thisMonth, nextMonth).joinToString("\n") { m ->
            val plan = db.tourPlan(m)
            val st = plan?.status ?: "not sent"
            "Tour plan ${label.format(fmt.parse(m)!!)}: $st"
        }
        val claims = db.expenses()
        val waiting = claims.count { it.status == "Pending" }
        val returned = claims.count { it.status == "Returned" }
        return "$tour\nClaims waiting: $waiting" + if (returned > 0) " · returned: $returned (open Expenses)" else ""
    }

    // ---------- start day: permissions → selfie → location → open ----------

    private fun requestStartDay() {
        if (!DayManager.isLocationOn(this)) {
            AlertDialog.Builder(this)
                .setTitle("Turn on location")
                .setMessage("Phone location is switched off. Turn it on, then tap Start day again.")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        toast("Open phone Settings and turn on Location")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val notifOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (TrackingService.hasLocationPermission(this) && notifOk) {
            takeSelfie()
        } else {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun takeSelfie() {
        try {
            toast("Take a selfie to start your day")
            selfieLauncher.launch(null)
        } catch (e: ActivityNotFoundException) {
            askStartWithoutSelfie()
        }
    }

    private fun askStartWithoutSelfie() {
        AlertDialog.Builder(this)
            .setTitle("No selfie taken")
            .setMessage("A selfie confirms that you started the day yourself. If you start without one, your manager will see that it was skipped.")
            .setPositiveButton("Take selfie") { _, _ -> takeSelfie() }
            .setNegativeButton("Start without") { _, _ -> locateAndOpenDay("") }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun saveSelfie(bmp: Bitmap): String = try {
        val dir = File(filesDir, "selfies").apply { mkdirs() }
        val file = File(dir, "selfie_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    } catch (e: Exception) {
        ""
    }

    @SuppressLint("MissingPermission")
    private fun locateAndOpenDay(selfiePath: String) {
        starting = true
        btnDay.isEnabled = false
        tvStatus.text = "Getting your location…"
        val fused = LocationServices.getFusedLocationProviderClient(this)
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc -> openDay(loc, selfiePath) }
                .addOnFailureListener { openDay(null, selfiePath) }
        } catch (e: SecurityException) {
            openDay(null, selfiePath)
        }
    }

    private fun openDay(loc: Location?, selfiePath: String) {
        starting = false
        if (prefs.dayActive) {
            refresh()
            return
        }
        val now = System.currentTimeMillis()
        val id = db.startDay(now, loc?.latitude, loc?.longitude, selfiePath)
        prefs.dayId = id
        prefs.dayStart = now
        prefs.dayActive = true
        prefs.locationOffSince = 0L
        if (loc != null) {
            db.insertPing(id, now, loc.latitude, loc.longitude, loc.accuracy.toDouble(),
                if (loc.hasSpeed()) loc.speed.toDouble() else 0.0, TrackingService.batteryPercent(this), Geo.isMock(loc))
        }
        TrackingService.start(this)
        toast(if (loc != null) "Day started · location recorded" else "Day started · waiting for GPS")
        refresh()
        askBatteryExemption(force = false)
        Sync.maybeSync(this, force = true)
    }

    private fun confirmEndDay() {
        AlertDialog.Builder(this)
            .setTitle("End your work day?")
            .setMessage("Location tracking will stop until you start your next day.")
            .setPositiveButton("End day") { _, _ -> endDay() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun endDay() {
        TrackingService.stop(this)
        val km = DayManager.endDay(this, auto = false)
        toast("Day ended · %.1f km".format(km))
        refresh()
        Sync.maybeSync(this, force = true) { msg -> showSyncMsg(msg) }
    }

    private fun askBatteryExemption(force: Boolean) {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            if (force) toast("Battery saver already allows the app")
            return
        }
        if (!force && prefs.batteryAsked) return
        prefs.batteryAsked = true
        AlertDialog.Builder(this)
            .setTitle("Keep tracking reliable")
            .setMessage("Phones like Xiaomi, Oppo, Vivo and Realme stop apps in the background. Allow Akimoto Pharma FT to run without battery restrictions so your route is recorded correctly.\n\nOn Xiaomi also turn on Autostart for the app.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        toast("Open Settings › Battery and allow the app to run in the background")
                    }
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Shares the latest day's route and calls as a CSV file (e.g. on WhatsApp or email). */
    private fun exportCsv() {
        val dayId = prefs.dayId
        if (dayId < 0) {
            toast("No day recorded yet")
            return
        }
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val pings = db.pingsForDay(dayId)
        val visits = db.visitsForDay(dayId)
        val sb = StringBuilder()
        sb.append("MR,${csv(prefs.name)},${csv(prefs.code)}\n")
        sb.append("Distance km,%.2f\n\n".format(Geo.km(pings)))
        sb.append("CALLS\ntime,doctor,speciality,area,products,samples,rx_per_week,feedback,lat,lng,accuracy_m,distance_from_clinic_m,status\n")
        visits.forEach { v ->
            val status = when {
                v.mock -> "fake GPS"
                v.distM < 0 -> "first visit (pinned)"
                v.verified -> "verified"
                else -> "flagged"
            }
            sb.append(listOf(iso.format(Date(v.ts)), v.doctor, v.speciality, v.area, v.products, v.samples,
                v.rx.toString(), v.feedback, v.lat.toString(), v.lng.toString(), v.acc.toInt().toString(),
                if (v.distM < 0) "" else v.distM.toInt().toString(), status).joinToString(",") { csv(it) }).append('\n')
        }
        sb.append("\nROUTE\ntime,lat,lng,accuracy_m,speed_mps,battery_pct,fake_gps\n")
        pings.forEach { p ->
            sb.append("${iso.format(Date(p.ts))},${p.lat},${p.lng},${p.acc.toInt()},${"%.1f".format(Locale.US, p.speed)},${p.battery},${if (p.mock) "yes" else "no"}\n")
        }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(prefs.dayStart))
        val file = File(dir, "AkimotoFT_${prefs.code}_$date.csv")
        file.writeText(sb.toString())
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Akimoto Pharma FT report ${prefs.name} $date")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share day report"))
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
