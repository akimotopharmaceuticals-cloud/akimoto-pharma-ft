package com.akimoto.fieldtrack

import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/** Doctor check-in + daily call report. The GPS fix is taken at the clinic. */
class VisitActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private lateinit var tvGps: TextView
    private lateinit var tvPin: TextView
    private lateinit var etDoctor: AutoCompleteTextView
    private lateinit var btnSave: Button
    private var location: Location? = null
    private var attempts = 0
    private val cancel = CancellationTokenSource()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_visit)
        prefs = Prefs(this)
        db = Db.get(this)
        tvGps = findViewById(R.id.tvGps)
        tvPin = findViewById(R.id.tvPin)
        etDoctor = findViewById(R.id.etDoctor)
        btnSave = findViewById(R.id.btnSaveVisit)
        btnSave.isEnabled = false

        etDoctor.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, db.knownDoctors()))
        etDoctor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePin()
        })
        findViewById<Button>(R.id.btnRetryGps).setOnClickListener { attempts = 0; fetchLocation() }
        btnSave.setOnClickListener { save() }

        if (!prefs.dayActive) {
            Toast.makeText(this, "Start your day first", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        fetchLocation()
    }

    override fun onDestroy() {
        cancel.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        if (!TrackingService.hasLocationPermission(this)) {
            tvGps.text = "Location permission is off. Allow it in phone settings."
            return
        }
        attempts++
        tvGps.text = "Getting your location… (try $attempts)"
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    tvGps.text = "No GPS fix yet. Step outside or near a window and tap Retry."
                    return@addOnSuccessListener
                }
                val better = location == null || loc.accuracy < (location?.accuracy ?: Float.MAX_VALUE)
                if (better) location = loc
                val l = location ?: return@addOnSuccessListener
                val mock = Geo.isMock(l)
                tvGps.text = buildString {
                    append("📍 %.5f, %.5f  ±%d m".format(l.latitude, l.longitude, l.accuracy.toInt()))
                    if (mock) append("\n⚠ Fake-GPS app detected. This call will be flagged.")
                    else if (l.accuracy > Geo.MAX_ACCURACY_M) append("\nWeak accuracy. Retrying for a better fix…")
                }
                btnSave.isEnabled = true
                updatePin()
                if (l.accuracy > Geo.MAX_ACCURACY_M && attempts < 3) fetchLocation()
            }
            .addOnFailureListener { tvGps.text = "Could not read GPS: ${it.message}. Tap Retry." }
    }

    private fun updatePin() {
        val name = etDoctor.text.toString().trim()
        val loc = location
        if (name.isEmpty()) {
            tvPin.text = ""
            return
        }
        val pin = db.pinnedLocation(name)
        tvPin.text = when {
            pin == null -> "New doctor: this visit will pin the clinic location."
            loc == null -> "Clinic location is pinned. Waiting for your GPS…"
            else -> {
                val d = Geo.distM(pin.first, pin.second, loc.latitude, loc.longitude).toInt()
                if (d <= Geo.GEOFENCE_M) "✓ You are $d m from this clinic. Visit will be verified."
                else "⚠ You are $d m from this clinic's pinned location. Visit will be flagged."
            }
        }
    }

    private fun text(id: Int): String = findViewById<EditText>(id).text.toString().trim()

    private fun save() {
        val loc = location ?: return
        val doctor = etDoctor.text.toString().trim()
        if (doctor.isEmpty()) {
            etDoctor.error = "Enter the doctor's name"
            return
        }
        val pin = db.pinnedLocation(doctor)
        val dist = if (pin == null) -1.0 else Geo.distM(pin.first, pin.second, loc.latitude, loc.longitude)
        val mock = Geo.isMock(loc)
        db.insertVisit(
            dayId = prefs.dayId,
            ts = System.currentTimeMillis(),
            lat = loc.latitude,
            lng = loc.longitude,
            acc = loc.accuracy.toDouble(),
            distM = dist,
            doctor = doctor,
            speciality = text(R.id.etSpeciality),
            area = text(R.id.etArea),
            products = text(R.id.etProducts),
            samples = text(R.id.etSamples),
            rx = text(R.id.etRx).toIntOrNull() ?: 0,
            feedback = text(R.id.etFeedback),
            mock = mock
        )
        val msg = when {
            mock -> "Call saved · flagged (fake GPS)"
            pin == null -> "Call saved · clinic location pinned"
            dist <= Geo.GEOFENCE_M && loc.accuracy <= Geo.MAX_ACCURACY_M -> "Call saved · verified"
            else -> "Call saved · flagged (${dist.toInt()} m from clinic)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        Sync.maybeSync(this, force = true)
        finish()
    }
}
