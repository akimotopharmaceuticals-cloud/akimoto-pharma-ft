package com.akimoto.fieldtrack

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** First-run setup: MR details, server, and the location-tracking consent. */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        val prefs = Prefs(this)
        val editing = intent.getBooleanExtra(EXTRA_EDIT, false)

        val etName = findViewById<EditText>(R.id.etName)
        val etCode = findViewById<EditText>(R.id.etCode)
        val etServer = findViewById<EditText>(R.id.etServer)
        val etKey = findViewById<EditText>(R.id.etKey)
        val etInterval = findViewById<EditText>(R.id.etInterval)
        val cbConsent = findViewById<CheckBox>(R.id.cbConsent)
        val tvConsentInfo = findViewById<TextView>(R.id.tvConsentInfo)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        findViewById<TextView>(R.id.tvVersion).text = "Akimoto Pharma FT v$version · device ${prefs.deviceId}"
        if (editing) title = "Settings"

        etName.setText(prefs.name)
        etCode.setText(prefs.code)
        etServer.setText(prefs.server)
        etKey.setText(prefs.apiKey)
        etInterval.setText(prefs.intervalMin.toString())
        if (prefs.consentAt > 0) {
            cbConsent.isChecked = true
            val at = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(prefs.consentAt))
            tvConsentInfo.text = "Consent given on $at"
        }

        findViewById<Button>(R.id.btnSaveSetup).setOnClickListener {
            val name = etName.text.toString().trim()
            val code = etCode.text.toString().trim().uppercase(Locale.ROOT)
            val server = etServer.text.toString().trim()
            val interval = etInterval.text.toString().toIntOrNull()
            when {
                name.isEmpty() -> etName.error = "Enter your name"
                code.isEmpty() -> etCode.error = "Enter your employee code"
                server.isNotEmpty() && !server.startsWith("http") -> etServer.error = "Start with https://"
                interval == null || interval !in 1..60 -> etInterval.error = "1 to 60 minutes"
                !cbConsent.isChecked -> cbConsent.error = "Consent is needed to use FieldTrack"
                else -> {
                    prefs.name = name
                    prefs.code = code
                    prefs.server = server
                    prefs.apiKey = etKey.text.toString().trim()
                    prefs.intervalMin = interval ?: 5
                    if (prefs.consentAt == 0L) prefs.consentAt = System.currentTimeMillis()
                    if (!editing) startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_EDIT = "edit"
    }
}
