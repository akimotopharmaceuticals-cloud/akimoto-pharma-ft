package com.akimoto.fieldtrack

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Ping(
    val id: Long, val dayId: Long, val ts: Long, val lat: Double, val lng: Double,
    val acc: Double, val speed: Double, val battery: Int, val mock: Boolean
)

data class Visit(
    val id: Long, val dayId: Long, val ts: Long, val lat: Double, val lng: Double, val acc: Double,
    val distM: Double, val doctor: String, val speciality: String, val area: String,
    val products: String, val samples: String, val rx: Int, val feedback: String, val mock: Boolean
) {
    /** -1 means this was the first visit, so it pinned the clinic location. */
    val verified: Boolean get() = !mock && acc <= Geo.MAX_ACCURACY_M && distM >= 0 && distM <= Geo.GEOFENCE_M
}

data class TourPlan(val month: String, val status: String, val note: String, val submittedTs: Long)

data class Expense(
    val id: Long, val date: String, val dayId: Long, val daType: String, val da: Double, val km: Double,
    val rate: Double, val ta: Double, val other: Double, val otherNote: String, val billPath: String,
    val total: Double, val status: String, val note: String
)

data class Day(
    val id: Long, val startTs: Long, val startLat: Double?, val startLng: Double?,
    val endTs: Long?, val km: Double?, val selfiePath: String, val autoEnded: Boolean
)

/** Everything is stored on the phone first, so the app works without network. */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "fieldtrack.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE pings(id INTEGER PRIMARY KEY AUTOINCREMENT, day_id INTEGER, ts INTEGER, " +
                "lat REAL, lng REAL, acc REAL, speed REAL, battery INTEGER, mock INTEGER, synced INTEGER DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE visits(id INTEGER PRIMARY KEY AUTOINCREMENT, day_id INTEGER, ts INTEGER, " +
                "lat REAL, lng REAL, acc REAL, dist_m REAL, doctor TEXT, speciality TEXT, area TEXT, " +
                "products TEXT, samples TEXT, rx INTEGER, feedback TEXT, mock INTEGER, synced INTEGER DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE days(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER, start_lat REAL, " +
                "start_lng REAL, end_ts INTEGER, km REAL, synced INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_pings_day ON pings(day_id, ts)")
        db.execSQL("CREATE INDEX idx_visits_doctor ON visits(doctor)")
        createV2(db)
        createV3(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) createV3(db)
    }

    /** Version 3: start-of-day selfie and days closed automatically. */
    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE days ADD COLUMN selfie_path TEXT DEFAULT ''")
        db.execSQL("ALTER TABLE days ADD COLUMN auto_ended INTEGER DEFAULT 0")
    }

    /** Version 2: tour plans and expense claims. */
    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tour_days(month TEXT, day INTEGER, area TEXT, PRIMARY KEY(month, day))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS tour_plans(month TEXT PRIMARY KEY, status TEXT, note TEXT, " +
                "submitted_ts INTEGER, synced INTEGER DEFAULT 1)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS expenses(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT UNIQUE, day_id INTEGER, " +
                "da_type TEXT, da REAL, km REAL, rate REAL, ta REAL, other REAL, other_note TEXT, bill_path TEXT, " +
                "total REAL, status TEXT, note TEXT, created_ts INTEGER, synced INTEGER DEFAULT 0)"
        )
    }

    // ---------- tour plans ----------
    fun tourDays(month: String): MutableMap<Int, String> =
        readableDatabase.rawQuery("SELECT day, area FROM tour_days WHERE month=?", arrayOf(month)).use { c ->
            val out = HashMap<Int, String>()
            while (c.moveToNext()) out[c.getInt(0)] = c.getString(1) ?: ""
            out
        }

    fun setTourDay(month: String, day: Int, area: String) {
        val cv = ContentValues().apply { put("month", month); put("day", day); put("area", area) }
        writableDatabase.insertWithOnConflict("tour_days", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun tourPlan(month: String): TourPlan? =
        readableDatabase.rawQuery("SELECT * FROM tour_plans WHERE month=?", arrayOf(month)).use { c ->
            if (c.moveToFirst()) TourPlan(
                month, c.getString(c.getColumnIndexOrThrow("status")) ?: "Draft",
                c.getString(c.getColumnIndexOrThrow("note")) ?: "",
                c.getLong(c.getColumnIndexOrThrow("submitted_ts"))
            ) else null
        }

    fun submitTour(month: String) {
        val cv = ContentValues().apply {
            put("month", month); put("status", "Pending"); put("note", ""); put("submitted_ts", System.currentTimeMillis()); put("synced", 0)
        }
        writableDatabase.insertWithOnConflict("tour_plans", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unsyncedTourMonths(): List<String> =
        readableDatabase.rawQuery("SELECT month FROM tour_plans WHERE synced=0", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    fun markTourSynced(month: String) {
        writableDatabase.execSQL("UPDATE tour_plans SET synced=1 WHERE month=?", arrayOf<Any>(month))
    }

    /** Status coming back from the manager. Ignored while a newer local submission is unsent. */
    fun setTourStatus(month: String, status: String, note: String) {
        writableDatabase.execSQL(
            "UPDATE tour_plans SET status=?, note=? WHERE month=? AND synced=1", arrayOf<Any>(status, note, month)
        )
    }

    fun knownAreas(): List<String> =
        readableDatabase.rawQuery(
            "SELECT area FROM (SELECT area FROM tour_days UNION SELECT area FROM visits) WHERE area IS NOT NULL AND area<>'' ORDER BY area", null
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    // ---------- expenses ----------
    fun insertExpense(
        date: String, dayId: Long, daType: String, da: Double, km: Double, rate: Double, ta: Double,
        other: Double, otherNote: String, billPath: String, total: Double
    ): Long {
        val cv = ContentValues().apply {
            put("date", date); put("day_id", dayId); put("da_type", daType); put("da", da); put("km", km)
            put("rate", rate); put("ta", ta); put("other", other); put("other_note", otherNote)
            put("bill_path", billPath); put("total", total); put("status", "Pending"); put("note", "")
            put("created_ts", System.currentTimeMillis()); put("synced", 0)
        }
        return writableDatabase.insert("expenses", null, cv)
    }

    fun expenses(): List<Expense> = queryExpenses("SELECT * FROM expenses ORDER BY date DESC LIMIT 120")

    fun unsyncedExpenses(limit: Int = 5): List<Expense> =
        queryExpenses("SELECT * FROM expenses WHERE synced=0 ORDER BY id LIMIT $limit")

    fun deleteExpense(id: Long) {
        writableDatabase.delete("expenses", "id=?", arrayOf(id.toString()))
    }

    fun setExpenseStatus(id: Long, status: String, note: String) {
        writableDatabase.execSQL("UPDATE expenses SET status=?, note=? WHERE id=?", arrayOf<Any>(status, note, id))
    }

    fun claimedDates(): Set<String> =
        readableDatabase.rawQuery("SELECT date FROM expenses", null).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    fun recentDays(limit: Int = 31): List<Day> =
        readableDatabase.rawQuery("SELECT * FROM days ORDER BY start_ts DESC LIMIT $limit", null).use { c ->
            val out = ArrayList<Day>()
            while (c.moveToNext()) out.add(day(c))
            out
        }

    private fun queryExpenses(sql: String): List<Expense> =
        readableDatabase.rawQuery(sql, null).use { c ->
            val out = ArrayList<Expense>()
            while (c.moveToNext()) {
                out.add(
                    Expense(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        date = c.getString(c.getColumnIndexOrThrow("date")) ?: "",
                        dayId = c.getLong(c.getColumnIndexOrThrow("day_id")),
                        daType = c.getString(c.getColumnIndexOrThrow("da_type")) ?: "",
                        da = c.getDouble(c.getColumnIndexOrThrow("da")),
                        km = c.getDouble(c.getColumnIndexOrThrow("km")),
                        rate = c.getDouble(c.getColumnIndexOrThrow("rate")),
                        ta = c.getDouble(c.getColumnIndexOrThrow("ta")),
                        other = c.getDouble(c.getColumnIndexOrThrow("other")),
                        otherNote = c.getString(c.getColumnIndexOrThrow("other_note")) ?: "",
                        billPath = c.getString(c.getColumnIndexOrThrow("bill_path")) ?: "",
                        total = c.getDouble(c.getColumnIndexOrThrow("total")),
                        status = c.getString(c.getColumnIndexOrThrow("status")) ?: "Pending",
                        note = c.getString(c.getColumnIndexOrThrow("note")) ?: ""
                    )
                )
            }
            out
        }

    // ---------- days ----------
    fun startDay(ts: Long, lat: Double?, lng: Double?, selfiePath: String): Long {
        val cv = ContentValues().apply {
            put("start_ts", ts)
            put("selfie_path", selfiePath)
            if (lat != null) put("start_lat", lat)
            if (lng != null) put("start_lng", lng)
        }
        return writableDatabase.insert("days", null, cv)
    }

    fun endDay(id: Long, ts: Long, km: Double, auto: Boolean) {
        val cv = ContentValues().apply {
            put("end_ts", ts); put("km", km); put("auto_ended", if (auto) 1 else 0); put("synced", 0)
        }
        writableDatabase.update("days", cv, "id=?", arrayOf(id.toString()))
    }

    fun unsyncedDays(): List<Day> =
        readableDatabase.rawQuery("SELECT * FROM days WHERE synced=0 ORDER BY id LIMIT 100", null).use { c ->
            val out = ArrayList<Day>()
            while (c.moveToNext()) out.add(day(c))
            out
        }

    private fun day(c: Cursor) = Day(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        startTs = c.getLong(c.getColumnIndexOrThrow("start_ts")),
        startLat = c.nullableDouble("start_lat"),
        startLng = c.nullableDouble("start_lng"),
        endTs = c.nullableLong("end_ts"),
        km = c.nullableDouble("km"),
        selfiePath = c.getString(c.getColumnIndexOrThrow("selfie_path")) ?: "",
        autoEnded = c.getInt(c.getColumnIndexOrThrow("auto_ended")) == 1
    )

    // ---------- pings ----------
    fun insertPing(dayId: Long, ts: Long, lat: Double, lng: Double, acc: Double, speed: Double, battery: Int, mock: Boolean): Long {
        val cv = ContentValues().apply {
            put("day_id", dayId); put("ts", ts); put("lat", lat); put("lng", lng)
            put("acc", acc); put("speed", speed); put("battery", battery); put("mock", if (mock) 1 else 0)
        }
        return writableDatabase.insert("pings", null, cv)
    }

    fun pingsForDay(dayId: Long): List<Ping> =
        queryPings("SELECT * FROM pings WHERE day_id=? ORDER BY ts", arrayOf(dayId.toString()))

    fun unsyncedPings(limit: Int = 500): List<Ping> =
        queryPings("SELECT * FROM pings WHERE synced=0 ORDER BY id LIMIT $limit", null)

    private fun queryPings(sql: String, args: Array<String>?): List<Ping> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Ping>()
            while (c.moveToNext()) {
                out.add(
                    Ping(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        dayId = c.getLong(c.getColumnIndexOrThrow("day_id")),
                        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                        lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
                        lng = c.getDouble(c.getColumnIndexOrThrow("lng")),
                        acc = c.getDouble(c.getColumnIndexOrThrow("acc")),
                        speed = c.getDouble(c.getColumnIndexOrThrow("speed")),
                        battery = c.getInt(c.getColumnIndexOrThrow("battery")),
                        mock = c.getInt(c.getColumnIndexOrThrow("mock")) == 1
                    )
                )
            }
            out
        }

    // ---------- visits ----------
    fun insertVisit(
        dayId: Long, ts: Long, lat: Double, lng: Double, acc: Double, distM: Double,
        doctor: String, speciality: String, area: String, products: String, samples: String,
        rx: Int, feedback: String, mock: Boolean
    ): Long {
        val cv = ContentValues().apply {
            put("day_id", dayId); put("ts", ts); put("lat", lat); put("lng", lng); put("acc", acc)
            put("dist_m", distM); put("doctor", doctor); put("speciality", speciality); put("area", area)
            put("products", products); put("samples", samples); put("rx", rx); put("feedback", feedback)
            put("mock", if (mock) 1 else 0)
        }
        return writableDatabase.insert("visits", null, cv)
    }

    fun visitsForDay(dayId: Long): List<Visit> =
        queryVisits("SELECT * FROM visits WHERE day_id=? ORDER BY ts", arrayOf(dayId.toString()))

    fun unsyncedVisits(limit: Int = 200): List<Visit> =
        queryVisits("SELECT * FROM visits WHERE synced=0 ORDER BY id LIMIT $limit", null)

    /** The clinic location = the first good-accuracy, non-mock visit to this doctor. */
    fun pinnedLocation(doctor: String): Pair<Double, Double>? =
        readableDatabase.rawQuery(
            "SELECT lat, lng FROM visits WHERE LOWER(TRIM(doctor))=LOWER(TRIM(?)) AND acc<=? AND mock=0 ORDER BY ts LIMIT 1",
            arrayOf(doctor, Geo.MAX_ACCURACY_M.toString())
        ).use { c -> if (c.moveToFirst()) Pair(c.getDouble(0), c.getDouble(1)) else null }

    fun knownDoctors(): List<String> =
        readableDatabase.rawQuery("SELECT DISTINCT doctor FROM visits ORDER BY doctor", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    private fun queryVisits(sql: String, args: Array<String>?): List<Visit> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Visit>()
            while (c.moveToNext()) {
                out.add(
                    Visit(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        dayId = c.getLong(c.getColumnIndexOrThrow("day_id")),
                        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                        lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
                        lng = c.getDouble(c.getColumnIndexOrThrow("lng")),
                        acc = c.getDouble(c.getColumnIndexOrThrow("acc")),
                        distM = c.getDouble(c.getColumnIndexOrThrow("dist_m")),
                        doctor = c.getString(c.getColumnIndexOrThrow("doctor")) ?: "",
                        speciality = c.getString(c.getColumnIndexOrThrow("speciality")) ?: "",
                        area = c.getString(c.getColumnIndexOrThrow("area")) ?: "",
                        products = c.getString(c.getColumnIndexOrThrow("products")) ?: "",
                        samples = c.getString(c.getColumnIndexOrThrow("samples")) ?: "",
                        rx = c.getInt(c.getColumnIndexOrThrow("rx")),
                        feedback = c.getString(c.getColumnIndexOrThrow("feedback")) ?: "",
                        mock = c.getInt(c.getColumnIndexOrThrow("mock")) == 1
                    )
                )
            }
            out
        }

    // ---------- sync bookkeeping ----------
    fun markSynced(table: String, ids: List<Long>) {
        if (ids.isEmpty()) return
        require(table in setOf("pings", "visits", "days", "expenses"))
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (chunk in ids.chunked(500)) {
                db.execSQL("UPDATE $table SET synced=1 WHERE id IN (${chunk.joinToString(",")})")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun unsyncedCount(): Int =
        readableDatabase.rawQuery(
            "SELECT (SELECT COUNT(*) FROM pings WHERE synced=0)+(SELECT COUNT(*) FROM visits WHERE synced=0)" +
                "+(SELECT COUNT(*) FROM expenses WHERE synced=0)+(SELECT COUNT(*) FROM tour_plans WHERE synced=0)", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    companion object {
        @Volatile private var instance: Db? = null
        fun get(ctx: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(ctx.applicationContext).also { instance = it }
            }
    }
}

private fun Cursor.nullableDouble(col: String): Double? {
    val i = getColumnIndexOrThrow(col)
    return if (isNull(i)) null else getDouble(i)
}

private fun Cursor.nullableLong(col: String): Long? {
    val i = getColumnIndexOrThrow(col)
    return if (isNull(i)) null else getLong(i)
}
