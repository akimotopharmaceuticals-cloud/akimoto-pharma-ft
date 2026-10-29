package com.akimoto.fieldtrack

import android.location.Location
import android.os.Build
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Fixes less accurate than this are ignored for distance and verification. */
    const val MAX_ACCURACY_M = 50.0

    /** A check-in within this distance of the clinic's pinned location counts as verified. */
    const val GEOFENCE_M = 150.0

    fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /** Kilometres travelled: skips poor fixes, jitter under 20 m and jumps faster than 150 km/h. */
    fun km(points: List<Ping>): Double {
        var total = 0.0
        var last: Ping? = null
        for (p in points.sortedBy { it.ts }) {
            if (p.acc > MAX_ACCURACY_M || p.mock) continue
            val l = last
            if (l == null) {
                last = p
                continue
            }
            val d = distM(l.lat, l.lng, p.lat, p.lng)
            if (d < 20) continue
            val hrs = (p.ts - l.ts) / 3_600_000.0
            if (hrs > 0 && d / 1000.0 / hrs > 150) continue
            total += d
            last = p
        }
        return total / 1000.0
    }

    @Suppress("DEPRECATION")
    fun isMock(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider
}
