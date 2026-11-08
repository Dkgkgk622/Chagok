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

    // Real roads from the emulator's default location (Pangyo), so the demo lies on the base map.
    // Route geometry: OSRM demo server on OpenStreetMap data (© OpenStreetMap contributors, ODbL), 5.67 km,
    // fetched once on 2026-11-08 and kept here as fixed data (the app never calls a routing service).
    private val path = listOf(
        37.41272 to 127.09468, 37.412717 to 127.094709, 37.412711 to 127.094752, 37.4127 to 127.094799,
        37.412679 to 127.094876, 37.412664 to 127.094911, 37.412645 to 127.094948, 37.412601 to 127.095024,
        37.41255 to 127.095103, 37.412472 to 127.095216, 37.41228 to 127.095478, 37.412122 to 127.095676,
        37.412183 to 127.095798, 37.412226 to 127.095896, 37.412262 to 127.095988, 37.412299 to 127.096092,
        37.412344 to 127.096238, 37.412381 to 127.096348, 37.412425 to 127.096479, 37.412466 to 127.096614,
        37.412514 to 127.096758, 37.41259 to 127.096985, 37.412656 to 127.097195, 37.412691 to 127.097325,
        37.412714 to 127.09742, 37.412726 to 127.097496, 37.412738 to 127.097599, 37.412743 to 127.097651,
        37.412747 to 127.097733, 37.412758 to 127.097818, 37.412762 to 127.097873, 37.412764 to 127.097954,
        37.412764 to 127.098076, 37.412768 to 127.098137, 37.412754 to 127.098268, 37.412724 to 127.098406,
        37.412679 to 127.098559, 37.412646 to 127.098669, 37.412632 to 127.098716, 37.412575 to 127.098795,
        37.412558 to 127.09884, 37.412536 to 127.098892, 37.412476 to 127.099023, 37.412402 to 127.099195,
        37.412294 to 127.099482, 37.412203 to 127.099745, 37.412048 to 127.099684, 37.411894 to 127.099629,
        37.411588 to 127.099534, 37.411423 to 127.099496, 37.411303 to 127.099471, 37.411211 to 127.099458,
        37.411122 to 127.09945, 37.410998 to 127.099446, 37.410694 to 127.099437, 37.410382 to 127.099451,
        37.410155 to 127.099473, 37.410013 to 127.099491, 37.409899 to 127.099512, 37.409738 to 127.099556,
        37.4096 to 127.099599, 37.409159 to 127.099756, 37.408679 to 127.09994, 37.408402 to 127.100057,
        37.408054 to 127.1002, 37.407724 to 127.100315, 37.407395 to 127.100434, 37.407039 to 127.100582,
        37.406683 to 127.100836, 37.406312 to 127.10123, 37.405709 to 127.101937, 37.405515 to 127.10226,
        37.405315 to 127.102617, 37.405211 to 127.102812, 37.40513 to 127.102957, 37.405053 to 127.103088,
        37.404935 to 127.103271, 37.404761 to 127.103509, 37.404612 to 127.103683, 37.404459 to 127.103852,
        37.404312 to 127.103993, 37.404205 to 127.104085, 37.404096 to 127.104173, 37.403975 to 127.104266,
        37.403838 to 127.104362, 37.403466 to 127.104608, 37.40341 to 127.104651, 37.403211 to 127.104826,
        37.402976 to 127.104948, 37.402645 to 127.105114, 37.402198 to 127.105303, 37.4013 to 127.105703,
        37.400661 to 127.105972, 37.400262 to 127.106172, 37.40003 to 127.106275, 37.399847 to 127.106369,
        37.399554 to 127.106493, 37.399414 to 127.106588, 37.399298 to 127.106636, 37.399156 to 127.106688,
        37.398806 to 127.106792, 37.398698 to 127.106827, 37.398602 to 127.106852, 37.398512 to 127.106873,
        37.39841 to 127.106897, 37.398304 to 127.106915, 37.398199 to 127.106927, 37.398061 to 127.106936,
        37.39781 to 127.106944, 37.397598 to 127.106947, 37.397375 to 127.10694, 37.397076 to 127.106944,
        37.396818 to 127.106952, 37.396543 to 127.106918, 37.396411 to 127.106892, 37.396232 to 127.10684,
        37.396193 to 127.107027, 37.396181 to 127.107112, 37.396181 to 127.107542, 37.396181 to 127.107607,
        37.396195 to 127.109442, 37.396197 to 127.109676, 37.396048 to 127.109675, 37.395277 to 127.109671,
        37.394945 to 127.109669, 37.394665 to 127.109668, 37.393746 to 127.109664, 37.393737 to 127.109913,
        37.393722 to 127.110347, 37.393716 to 127.110535, 37.393716 to 127.110817, 37.393712 to 127.111175,
        37.393708 to 127.111432, 37.393711 to 127.111623, 37.393718 to 127.112038, 37.393727 to 127.112691,
        37.393735 to 127.112948, 37.393601 to 127.112951, 37.392222 to 127.112983, 37.391931 to 127.112962,
        37.391784 to 127.112956, 37.391648 to 127.112947, 37.391648 to 127.113147, 37.391684 to 127.114064,
        37.391793 to 127.116585, 37.391806 to 127.116868, 37.391808 to 127.117066, 37.391809 to 127.117181,
        37.391849 to 127.117711, 37.391853 to 127.117912, 37.391857 to 127.118113, 37.391857 to 127.118314,
        37.391856 to 127.118356, 37.391853 to 127.118526, 37.391846 to 127.118597, 37.391835 to 127.118684,
        37.391808 to 127.118841, 37.391786 to 127.118955, 37.391759 to 127.11907, 37.391681 to 127.119336,
        37.391553 to 127.119684, 37.391537 to 127.119726, 37.391499 to 127.119825, 37.391366 to 127.120112,
        37.391332 to 127.120201, 37.391145 to 127.120509, 37.391082 to 127.120615, 37.391053 to 127.120671,
        37.390953 to 127.120869, 37.390284 to 127.122068, 37.390236 to 127.122149, 37.390086 to 127.122406,
        37.390172 to 127.122486, 37.390367 to 127.122683, 37.392318 to 127.124459, 37.392446 to 127.124571,
        37.392993 to 127.125047, 37.393169 to 127.125144, 37.393371 to 127.12523, 37.393502 to 127.125278,
        37.393666 to 127.125316, 37.393835 to 127.125348, 37.394004 to 127.125369, 37.394547 to 127.125397,
        37.394703 to 127.125405, 37.394873 to 127.125399, 37.395125 to 127.125385, 37.39524 to 127.125387,
        37.396131 to 127.125385, 37.396782 to 127.125391, 37.39841 to 127.125405, 37.398669 to 127.125399,
        37.399018 to 127.125405, 37.399176 to 127.125408, 37.399333 to 127.12541, 37.399661 to 127.125413,
        37.4006 to 127.125412,
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
        val samples = speeds.mapIndexed { i, v ->
            if (i > 0) driven += stepKm[i - 1]
            val (lat, lon) = pointAt(driven / totalKm * pathKm, legs)
            TrackPoint(start + i * STEP_MS, round5(lat), round5(lon), v.toFloat(), if (i <= 12) 60 else 50)
        }
        // Between two samples, add the road's own corners (time and speed interpolated), like a denser
        // navigation fix would: otherwise a 15 s chord cuts across the block at every turn.
        val vertexKm = legs.runningFold(0.0) { acc, l -> acc + l }
        val track = samples.flatMapIndexed { i, s ->
            if (i == 0) return@flatMapIndexed listOf(s)
            val a = samples[i - 1]
            val fromKm = (stepKm.take(i - 1).sum()) / totalKm * pathKm
            val toKm = (stepKm.take(i).sum()) / totalKm * pathKm
            val corners = path.indices.filter { vertexKm[it] > fromKm + 1e-6 && vertexKm[it] < toKm - 1e-6 }.map { k ->
                val u = (vertexKm[k] - fromKm) / (toKm - fromKm)
                TrackPoint(a.timeMs + (u * STEP_MS).toLong(), round5(path[k].first), round5(path[k].second),
                    (a.speedKmh!! + (s.speedKmh!! - a.speedKmh!!) * u).toFloat(), s.limitKmh)
            }
            corners + s
        }
        fun loc(i: Int) = "%.5f,%.5f".format(java.util.Locale.US, samples[i].lat, samples[i].lon)
        fun t(i: Int) = samples[i].timeMs

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
