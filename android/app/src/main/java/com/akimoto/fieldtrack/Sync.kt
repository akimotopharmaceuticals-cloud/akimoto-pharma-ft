package com.akimoto.fieldtrack

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Sends unsynced pings, calls, days, tour plans and expense claims to the FieldTrack server,
 * and brings back the manager's decisions (approved / returned) and the company's expense rates.
 */
object Sync {
    private val running = AtomicBoolean(false)
    private const val MIN_GAP_MS = 10 * 60_000L

    fun maybeSync(ctx: Context, force: Boolean, done: ((String) -> Unit)? = null) {
        val prefs = Prefs(ctx)
        if (prefs.server.isBlank()) {
            done?.invoke("No server set. Data is saved on this phone; use Export to share it.")
            return
        }
        if (!force && System.currentTimeMillis() - prefs.lastSync < MIN_GAP_MS) return
        if (!running.compareAndSet(false, true)) {
            done?.invoke("Sync is already running")
            return
        }
        val app = ctx.applicationContext
        thread(name = "fieldtrack-sync") {
            val msg = try {
                runSync(app)
            } catch (e: Exception) {
                "Sync failed: ${e.message ?: e.javaClass.simpleName}. Data is kept on the phone."
            } finally {
                running.set(false)
            }
            if (done != null) Handler(Looper.getMainLooper()).post { done(msg) }
        }
    }

    private fun runSync(ctx: Context): String {
        val prefs = Prefs(ctx)
        val db = Db.get(ctx)
        var pingTotal = 0
        var visitTotal = 0
        var claimTotal = 0
        // Always send at least once so the manager's decisions come back even when nothing is pending.
        for (round in 0 until 20) {
            val pings = db.unsyncedPings(500)
            val visits = db.unsyncedVisits(200)
            val days = db.unsyncedDays()
            val tourMonths = db.unsyncedTourMonths()
            val expenses = db.unsyncedExpenses(5)
            val empty = pings.isEmpty() && visits.isEmpty() && days.isEmpty() && tourMonths.isEmpty() && expenses.isEmpty()
            if (empty && round > 0) break

            val body = JSONObject().apply {
                put("mr", JSONObject().put("code", prefs.code).put("name", prefs.name))
                put("device", JSONObject().put("id", prefs.deviceId)
                    .put("model", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.SDK_INT))
                put("pings", JSONArray().apply {
                    pings.forEach {
                        put(JSONObject().put("id", it.id).put("day_id", it.dayId).put("ts", it.ts)
                            .put("lat", it.lat).put("lng", it.lng).put("acc", it.acc).put("speed", it.speed)
                            .put("battery", it.battery).put("mock", it.mock))
                    }
                })
                put("visits", JSONArray().apply {
                    visits.forEach {
                        put(JSONObject().put("id", it.id).put("day_id", it.dayId).put("ts", it.ts)
                            .put("lat", it.lat).put("lng", it.lng).put("acc", it.acc).put("dist_m", it.distM)
                            .put("doctor", it.doctor).put("speciality", it.speciality).put("area", it.area)
                            .put("products", it.products).put("samples", it.samples).put("rx", it.rx)
                            .put("feedback", it.feedback).put("mock", it.mock).put("verified", it.verified))
                    }
                })
                put("days", JSONArray().apply {
                    days.forEach {
                        val o = JSONObject().put("id", it.id).put("start_ts", it.startTs)
                            .put("start_lat", it.startLat ?: JSONObject.NULL)
                            .put("start_lng", it.startLng ?: JSONObject.NULL)
                            .put("end_ts", it.endTs ?: JSONObject.NULL)
                            .put("km", it.km ?: JSONObject.NULL)
                            .put("auto_ended", it.autoEnded)
                        val selfie = File(it.selfiePath)
                        if (it.selfiePath.isNotEmpty() && selfie.exists() && selfie.length() < 1_000_000) {
                            o.put("selfie_jpg", Base64.encodeToString(selfie.readBytes(), Base64.NO_WRAP))
                        } else if (it.selfiePath.isEmpty()) {
                            o.put("selfie_skipped", true)
                        }
                        put(o)
                    }
                })
                put("tour_plans", JSONArray().apply {
                    tourMonths.forEach { month ->
                        val plan = db.tourPlan(month)
                        val daysObj = JSONObject()
                        db.tourDays(month).forEach { (d, area) -> daysObj.put(d.toString(), area) }
                        put(JSONObject().put("month", month).put("submitted_ts", plan?.submittedTs ?: 0L).put("days", daysObj))
                    }
                })
                put("expenses", JSONArray().apply {
                    expenses.forEach {
                        val o = JSONObject().put("id", it.id).put("date", it.date).put("day_id", it.dayId)
                            .put("da_type", it.daType).put("da", it.da).put("km", it.km).put("rate", it.rate)
                            .put("ta", it.ta).put("other", it.other).put("other_note", it.otherNote).put("total", it.total)
                        val bill = File(it.billPath)
                        if (it.billPath.isNotEmpty() && bill.exists() && bill.length() < 2_000_000) {
                            o.put("bill_jpg", Base64.encodeToString(bill.readBytes(), Base64.NO_WRAP))
                        }
                        put(o)
                    }
                })
            }
            val (code, response) = post(prefs.server.trimEnd('/') + "/api/sync", prefs.apiKey, body.toString())
            if (code !in 200..299) {
                return if (code == 401) "Server refused the access key. Check Settings." else "Server replied $code. Will retry."
            }
            db.markSynced("pings", pings.map { it.id })
            db.markSynced("visits", visits.map { it.id })
            db.markSynced("days", days.map { it.id })
            db.markSynced("expenses", expenses.map { it.id })
            tourMonths.forEach { db.markTourSynced(it) }
            applyResponse(prefs, db, response)
            pingTotal += pings.size
            visitTotal += visits.size
            claimTotal += expenses.size
            if (empty) break
        }
        prefs.lastSync = System.currentTimeMillis()
        return "Synced $pingTotal location points, $visitTotal calls, $claimTotal claims"
    }

    /** Manager decisions and company rates sent back by the server. */
    private fun applyResponse(prefs: Prefs, db: Db, response: String) {
        val json = try { JSONObject(response) } catch (e: Exception) { return }
        json.optJSONObject("rules")?.let { r ->
            if (r.has("da_hq")) prefs.daHq = r.getDouble("da_hq").toFloat()
            if (r.has("da_exhq")) prefs.daExHq = r.getDouble("da_exhq").toFloat()
            if (r.has("da_out")) prefs.daOut = r.getDouble("da_out").toFloat()
            if (r.has("ta_per_km")) prefs.taPerKm = r.getDouble("ta_per_km").toFloat()
            if (r.has("day_end_hour")) prefs.dayEndHour = r.getInt("day_end_hour")
        }
        json.optJSONObject("tour")?.let { t ->
            for (month in t.keys()) {
                val o = t.optJSONObject(month) ?: continue
                db.setTourStatus(month, o.optString("status", "Pending"), o.optString("note", ""))
            }
        }
        json.optJSONObject("expenses")?.let { e ->
            for (id in e.keys()) {
                val o = e.optJSONObject(id) ?: continue
                val localId = id.toLongOrNull() ?: continue
                db.setExpenseStatus(localId, o.optString("status", "Pending"), o.optString("note", ""))
            }
        }
    }

    private fun post(url: String, key: String, json: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Api-Key", key)
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }
}
