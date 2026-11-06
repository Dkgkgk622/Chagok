package com.chagok.app

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A made-up 9-minute trip around Pangyo (where the emulator's location is), so the map,
 * reports and QR can be tried without driving. Always marked demo = true.
 */
object DemoTrip {

    // Rough path from the emulator's default location; points are joined with straight lines.
    private val path = listOf(
        37.41272 to 127.09468, 37.40990 to 127.09790, 37.40610 to 127.10060, 37.40160 to 127.10370,
        37.39790 to 127.10790, 37.39460 to 127.11160, 37.39390 to 127.11690, 37.39650 to 127.12160,
        37.40060 to 127.12520,
    )

    // Speed every 15 s (km/h): quick start, overspeed in a 60 zone, sudden stop, 105 s wait, harsh accel.
    private val speeds = listOf(
        0, 30, 45, 50, 55, 58, 60, 62, 85, 88, 70, 55, 48, 20, 0, 0, 0, 0, 0, 0, 0,
        25, 45, 50, 50, 50, 50, 50, 50, 50, 50, 52, 30, 30, 20, 10, 0,
    )
    private const val STEP_MS = 15_000L

    fun build(nowMs: Long, startOdometerKm: Float): Trip {
        val start = nowMs - speeds.size * STEP_MS
        val stepKm = speeds.map { it * STEP_MS / 3_600_000.0 }
        val totalKm = stepKm.sum()

        // Place each sample along the path in proportion to the distance driven so far.
        val legs = path.zipWithNext { a, b -> haversineKm(a, b) }
        val pathKm = legs.sum()
        var driven = 0.0
        val track = speeds.mapIndexed { i, v ->
            if (i > 0) driven += stepKm[i - 1]
            val (lat, lon) = pointAt(driven / totalKm * pathKm, legs)
            TrackPoint(start + i * STEP_MS, round5(lat), round5(lon), v.toFloat(), if (i <= 12) 60 else 50)
        }
        fun loc(i: Int) = "%.5f,%.5f".format(java.util.Locale.US, track[i].lat, track[i].lon)
        fun t(i: Int) = track[i].timeMs

        val events = listOf(
            DriveEvent(t(1), DriveEvent.Type.QUICK_START, null, 30f, 60, loc(1), peakRate = 13.2f, endMs = t(1) + 3_000),
            DriveEvent(t(8), DriveEvent.Type.OVERSPEED, null, 88f, 60, loc(9), endMs = t(10)), // shown where the top speed was
            DriveEvent(t(13), DriveEvent.Type.SUDDEN_STOP, null, 48f, 50, loc(14), peakRate = 11.5f, endMs = t(14)),
            DriveEvent(t(22), DriveEvent.Type.HARSH_ACCEL, null, 45f, 50, loc(22), peakRate = 12.1f, endMs = t(22) + 2_000),
            DriveEvent(t(32), DriveEvent.Type.HARSH_BRAKE, null, 52f, 50, loc(32), peakRate = 8.4f, endMs = t(32) + 2_000),
        )
        val km = totalKm.toFloat()
        return Trip(
            id = start,
            startMs = start,
            endMs = t(speeds.lastIndex) + 5_000,
            startOdometerKm = startOdometerKm,
            endOdometerKm = startOdometerKm + km,
            startBattery = 80,
            endBattery = 79,
            maxSpeedKmh = speeds.max().toFloat(),
            speedDistanceKm = km,
            idleSeconds = 105,
            stopCount = 2,
            events = events,
            track = track,
            dwells = listOf(Dwell(t(14), t(21), loc(14))),
            demo = true,
        )
    }

    private fun pointAt(km: Double, legs: List<Double>): Pair<Double, Double> {
        var left = km
        legs.forEachIndexed { i, len ->
            if (left <= len || i == legs.lastIndex) {
                val f = if (len == 0.0) 0.0 else (left / len).coerceIn(0.0, 1.0)
                val a = path[i]; val b = path[i + 1]
                return (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
            }
            left -= len
        }
        return path.last()
    }

    private fun round5(v: Double) = Math.round(v * 1e5) / 1e5

    private fun haversineKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLon / 2).let { it * it }
        return 2 * 6371.0 * asin(sqrt(h))
    }
}
