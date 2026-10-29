package com.akimoto.fieldtrack

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Monthly tour plan (beat plan): one area or activity per day, sent to the manager for approval. */
class TourPlanActivity : AppCompatActivity() {

    private lateinit var db: Db
    private lateinit var container: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnSubmit: Button
    private lateinit var btnThis: Button
    private lateinit var btnNext: Button
    private lateinit var thisMonth: String
    private lateinit var nextMonth: String
    private lateinit var month: String

    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)
    private val labelFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE d", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tour)
        db = Db.get(this)
        container = findViewById(R.id.daysContainer)
        tvStatus = findViewById(R.id.tvTourStatus)
        btnSubmit = findViewById(R.id.btnSubmitTour)
        btnThis = findViewById(R.id.btnThisMonth)
        btnNext = findViewById(R.id.btnNextMonth)

        val cal = Calendar.getInstance()
        thisMonth = monthFmt.format(cal.time)
        cal.add(Calendar.MONTH, 1)
        nextMonth = monthFmt.format(cal.time)
        month = if (Calendar.getInstance().get(Calendar.DAY_OF_MONTH) >= 15) nextMonth else thisMonth

        btnThis.text = label(thisMonth)
        btnNext.text = label(nextMonth)
        btnThis.setOnClickListener { month = thisMonth; render() }
        btnNext.setOnClickListener { month = nextMonth; render() }
        btnSubmit.setOnClickListener { submit() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun label(m: String): String = labelFmt.format(monthFmt.parse(m)!!)

    private fun status(): String = db.tourPlan(month)?.status ?: "Draft"

    private fun editable(): Boolean = status() == "Draft" || status() == "Returned"

    private fun calendarFor(day: Int): Calendar = Calendar.getInstance().apply {
        time = monthFmt.parse(month)!!
        set(Calendar.DAY_OF_MONTH, day)
    }

    private fun daysInMonth(): Int = calendarFor(1).getActualMaximum(Calendar.DAY_OF_MONTH)

    /** A new month starts with Sundays filled in as off days. */
    private fun planDays(): MutableMap<Int, String> {
        val days = db.tourDays(month)
        if (days.isEmpty()) {
            for (d in 1..daysInMonth()) {
                if (calendarFor(d).get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                    db.setTourDay(month, d, "Sunday off")
                    days[d] = "Sunday off"
                }
            }
        }
        return days
    }

    private fun render() {
        val plan = db.tourPlan(month)
        val days = planDays()
        val brand = ContextCompat.getColor(this, R.color.accent_text)
        val muted = ContextCompat.getColor(this, R.color.muted)
        val ink = ContextCompat.getColor(this, R.color.ink)

        btnThis.isSelected = month == thisMonth
        btnNext.isSelected = month == nextMonth
        btnThis.setTypeface(null, if (month == thisMonth) Typeface.BOLD else Typeface.NORMAL)
        btnNext.setTypeface(null, if (month == nextMonth) Typeface.BOLD else Typeface.NORMAL)

        tvStatus.text = when (plan?.status) {
            null, "Draft" -> "${label(month)} · not sent yet. Tap a day to set the area."
            "Pending" -> "${label(month)} · sent, waiting for your manager's approval."
            "Approved" -> "✓ ${label(month)} · approved by your manager."
            "Returned" -> "${label(month)} · returned by your manager" +
                (if (!plan?.note.isNullOrBlank()) ": \"${plan?.note}\"" else "") + ". Change it and send again."
            else -> "${label(month)} · ${plan?.status}"
        }

        container.removeAllViews()
        val canEdit = editable()
        val pad = (12 * resources.displayMetrics.density).toInt()
        for (d in 1..daysInMonth()) {
            val area = days[d]
            val isOff = area == "Sunday off" || area == "Holiday" || area == "Leave"
            val row = TextView(this).apply {
                text = "${dayFmt.format(calendarFor(d).time)}    ${area ?: "— tap to plan"}"
                textSize = 15f
                setPadding(pad, pad, pad, pad)
                setTextColor(if (area == null) brand else if (isOff) muted else ink)
                if (area == null) setTypeface(null, Typeface.BOLD)
                if (canEdit) {
                    isClickable = true
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { pickArea(d) }
                }
            }
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        btnSubmit.isEnabled = canEdit
        btnSubmit.text = when (plan?.status) {
            "Pending" -> "Waiting for approval"
            "Approved" -> "Approved"
            "Returned" -> "Send again for approval"
            else -> "Send for approval"
        }
    }

    private fun pickArea(day: Int) {
        val fixed = listOf("Meeting at HQ", "Leave", "Holiday", "Sunday off")
        val areas = db.knownAreas().filter { it !in fixed }
        val items = (listOf("Type an area…") + areas + fixed).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(dayFmt.format(calendarFor(day).time))
            .setItems(items) { _, which ->
                if (which == 0) typeArea(day) else {
                    db.setTourDay(month, day, items[which])
                    render()
                }
            }
            .show()
    }

    private fun typeArea(day: Int) {
        val input = AutoCompleteTextView(this).apply {
            hint = "e.g. Hazratganj"
            setSingleLine()
            threshold = 1
            setAdapter(ArrayAdapter(this@TourPlanActivity, android.R.layout.simple_dropdown_item_1line, db.knownAreas()))
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Area for ${dayFmt.format(calendarFor(day).time)}")
            .setView(frame)
            .setPositiveButton("Save") { _, _ ->
                val area = input.text.toString().trim()
                if (area.isNotEmpty()) {
                    db.setTourDay(month, day, area)
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submit() {
        val days = planDays()
        val missing = (1..daysInMonth()).filter { days[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            Toast.makeText(this, "Plan every day first. Missing: ${missing.take(6).joinToString(", ")}" +
                if (missing.size > 6) "…" else "", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Send ${label(month)} plan?")
            .setMessage("Your manager will approve it or send it back with a note. You cannot change it while it is waiting.")
            .setPositiveButton("Send") { _, _ ->
                db.submitTour(month)
                render()
                Sync.maybeSync(this, force = true) { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
