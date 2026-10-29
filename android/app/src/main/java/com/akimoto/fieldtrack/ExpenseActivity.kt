package com.akimoto.fieldtrack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Daily TA/DA claim built from the GPS distance of a finished work day. */
class ExpenseActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private lateinit var spDay: Spinner
    private lateinit var tvKm: TextView
    private lateinit var rgDa: RadioGroup
    private lateinit var etOther: EditText
    private lateinit var etOtherNote: EditText
    private lateinit var tvBill: TextView
    private lateinit var tvTotal: TextView
    private lateinit var form: View
    private lateinit var tvNoDays: TextView
    private lateinit var claimsContainer: LinearLayout
    private lateinit var tvMonth: TextView

    private var days: List<Day> = emptyList()
    private var billPath = ""
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val nice = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private val pickBill = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveBill(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expense)
        prefs = Prefs(this)
        db = Db.get(this)
        spDay = findViewById(R.id.spDay)
        tvKm = findViewById(R.id.tvKm)
        rgDa = findViewById(R.id.rgDa)
        etOther = findViewById(R.id.etOther)
        etOtherNote = findViewById(R.id.etOtherNote)
        tvBill = findViewById(R.id.tvBill)
        tvTotal = findViewById(R.id.tvTotal)
        form = findViewById(R.id.claimForm)
        tvNoDays = findViewById(R.id.tvNoDays)
        claimsContainer = findViewById(R.id.claimsContainer)
        tvMonth = findViewById(R.id.tvMonthTotal)

        findViewById<RadioButton>(R.id.rbHq).text = "HQ  ${rupees(prefs.daHq.toDouble())}"
        findViewById<RadioButton>(R.id.rbExHq).text = "Ex-HQ  ${rupees(prefs.daExHq.toDouble())}"
        findViewById<RadioButton>(R.id.rbOut).text = "Outstation  ${rupees(prefs.daOut.toDouble())}"
        rgDa.setOnCheckedChangeListener { _, _ -> recalc() }
        etOther.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = recalc()
        })
        spDay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = recalc()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        findViewById<Button>(R.id.btnBill).setOnClickListener { pickBill.launch("image/*") }
        findViewById<Button>(R.id.btnSubmitClaim).setOnClickListener { submit() }
        render()
    }

    private fun rupees(v: Double): String = "₹" + String.format(Locale("en", "IN"), "%,.0f", v)

    private fun dateOf(day: Day): String = iso.format(Date(day.startTs))

    private fun kmFor(day: Day): Double = day.km ?: Geo.km(db.pingsForDay(day.id))

    private fun selectedDay(): Day? = days.getOrNull(spDay.selectedItemPosition)

    private fun daType(): Pair<String, Double> = when (rgDa.checkedRadioButtonId) {
        R.id.rbExHq -> "Ex-HQ" to prefs.daExHq.toDouble()
        R.id.rbOut -> "Outstation" to prefs.daOut.toDouble()
        else -> "HQ" to prefs.daHq.toDouble()
    }

    private fun render() {
        // Only finished work days that are not yet claimed.
        val claimed = db.claimedDates()
        days = db.recentDays(31).filter { it.endTs != null && dateOf(it) !in claimed }
        if (days.isEmpty()) {
            form.visibility = View.GONE
            tvNoDays.visibility = View.VISIBLE
        } else {
            form.visibility = View.VISIBLE
            tvNoDays.visibility = View.GONE
            val labels = days.map { "${nice.format(Date(it.startTs))} · %.1f km by GPS".format(kmFor(it)) }
            spDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        }
        billPath = ""
        tvBill.text = "No bill attached"
        etOther.setText("")
        etOtherNote.setText("")
        recalc()
        renderClaims()
    }

    private fun recalc() {
        val day = selectedDay() ?: return
        val km = kmFor(day)
        val ta = km * prefs.taPerKm
        val (type, da) = daType()
        val other = etOther.text.toString().toDoubleOrNull() ?: 0.0
        tvKm.text = "Distance from GPS: %.1f km × ₹%.2f = %s".format(km, prefs.taPerKm, rupees(ta))
        tvTotal.text = "Total: ${rupees(da + ta + other)}  (DA $type ${rupees(da)} + TA ${rupees(ta)} + other ${rupees(other)})"
    }

    private fun submit() {
        val day = selectedDay() ?: return
        val other = etOther.text.toString().toDoubleOrNull() ?: 0.0
        val note = etOtherNote.text.toString().trim()
        if (other > 0 && note.isEmpty()) {
            etOtherNote.error = "Say what the other amount is for"
            return
        }
        val km = kmFor(day)
        val rate = prefs.taPerKm.toDouble()
        val ta = km * rate
        val (type, da) = daType()
        val total = da + ta + other
        AlertDialog.Builder(this)
            .setTitle("Submit claim for ${nice.format(Date(day.startTs))}?")
            .setMessage("Total ${rupees(total)}. Your manager will approve it or send it back with a note.")
            .setPositiveButton("Submit") { _, _ ->
                db.insertExpense(dateOf(day), day.id, type, da, km, rate, ta, other, note, billPath, total)
                Toast.makeText(this, "Claim submitted", Toast.LENGTH_SHORT).show()
                render()
                Sync.maybeSync(this, force = true) { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderClaims() {
        val claims = db.expenses()
        val monthKey = iso.format(Date()).substring(0, 7)
        val thisMonth = claims.filter { it.date.startsWith(monthKey) }
        val approved = thisMonth.filter { it.status == "Approved" }.sumOf { it.total }
        val pending = thisMonth.filter { it.status == "Pending" }.sumOf { it.total }
        tvMonth.text = "This month: ${rupees(approved)} approved · ${rupees(pending)} waiting"

        claimsContainer.removeAllViews()
        val pad = (12 * resources.displayMetrics.density).toInt()
        if (claims.isEmpty()) {
            claimsContainer.addView(TextView(this).apply {
                text = "No claims yet."
                setPadding(pad, pad, pad, pad)
            })
            return
        }
        for (c in claims) {
            val color = ContextCompat.getColor(this, when (c.status) {
                "Approved" -> R.color.ok
                "Returned" -> R.color.bad
                else -> R.color.muted
            })
            val dateLabel = try { nice.format(iso.parse(c.date)!!) } catch (e: Exception) { c.date }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
            row.addView(TextView(this).apply {
                text = "$dateLabel · ${rupees(c.total)}"
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@ExpenseActivity, R.color.ink))
            })
            row.addView(TextView(this).apply {
                text = "DA ${c.daType} ${rupees(c.da)} · TA %.1f km ${rupees(c.ta)}".format(c.km) +
                    (if (c.other > 0) " · other ${rupees(c.other)} (${c.otherNote})" else "") +
                    (if (c.billPath.isNotEmpty()) " · bill attached" else "")
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@ExpenseActivity, R.color.muted))
            })
            row.addView(TextView(this).apply {
                text = c.status + (if (c.note.isNotBlank()) ": \"${c.note}\"" else "") +
                    (if (c.status == "Returned") " · tap to remove and claim again" else "")
                textSize = 13f
                setTextColor(color)
            })
            if (c.status == "Returned") {
                row.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Remove this returned claim?")
                        .setMessage("The day will be available to claim again with corrected details.")
                        .setPositiveButton("Remove") { _, _ ->
                            db.deleteExpense(c.id)
                            render()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            claimsContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Saves a reduced copy of the bill photo (about 1280 px, JPEG) to keep sync light. */
    private fun saveBill(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw IllegalStateException("could not read image")
            val dir = File(filesDir, "bills").apply { mkdirs() }
            val file = File(dir, "bill_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            billPath = file.absolutePath
            tvBill.text = "Bill photo attached ✓ (${file.length() / 1024} KB)"
        } catch (e: Exception) {
            Toast.makeText(this, "Could not attach the photo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
