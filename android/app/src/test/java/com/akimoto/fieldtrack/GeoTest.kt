package com.akimoto.fieldtrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    private fun ping(id: Long, minute: Int, lat: Double, lng: Double, acc: Double = 10.0, mock: Boolean = false) =
        Ping(id, 1, minute * 60_000L, lat, lng, acc, 0.0, 90, mock)

    @Test
    fun distance_hazratganj_to_charbagh_is_about_3_point_7_km() {
        // Hazratganj (26.8500, 80.9462) to Charbagh station (26.8320, 80.9220)
        val d = Geo.distM(26.8500, 80.9462, 26.8320, 80.9220)
        assertTrue("got $d", d in 3_000.0..3_300.0)
    }

    @Test
    fun km_adds_up_a_straight_route() {
        val pts = (0..10).map { ping(it.toLong(), it * 5, 26.80 + it * 0.009, 80.95) } // ~1 km per step
        assertEquals(10.0, Geo.km(pts), 0.3)
    }

    @Test
    fun km_ignores_jitter_while_standing_still() {
        val pts = (0..20).map { ping(it.toLong(), it * 5, 26.85 + (it % 2) * 0.00005, 80.95) } // ~5 m wobble
        assertEquals(0.0, Geo.km(pts), 0.001)
    }

    @Test
    fun km_ignores_poor_accuracy_and_fake_gps_points() {
        val pts = listOf(
            ping(1, 0, 26.85, 80.95),
            ping(2, 5, 27.85, 80.95, acc = 500.0),      // 111 km away but inaccurate
            ping(3, 10, 27.85, 80.95, mock = true),     // 111 km away but fake
            ping(4, 15, 26.859, 80.95)                  // ~1 km real move
        )
        assertEquals(1.0, Geo.km(pts), 0.05)
    }

    @Test
    fun km_ignores_impossible_jumps() {
        val pts = listOf(
            ping(1, 0, 26.85, 80.95),
            ping(2, 1, 26.95, 80.95),   // 11 km in 1 minute = 660 km/h, rejected
            ping(3, 10, 26.859, 80.95)  // ~1 km from the first point in 10 minutes
        )
        assertEquals(1.0, Geo.km(pts), 0.05)
    }

    @Test
    fun km_of_nothing_is_zero() {
        assertEquals(0.0, Geo.km(emptyList()), 0.0)
    }
}
