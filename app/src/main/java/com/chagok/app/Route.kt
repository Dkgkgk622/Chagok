package com.chagok.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Route data for the map views (car screen and phone page).
 * The phone gets a compact, delta-encoded copy inside the QR code.
 */
object RouteCodec {

    /** One point per [intervalSec] (plus the first and last), for dots and for the QR. */
    fun sample(track: List<TrackPoint>, intervalSec: Int): List<TrackPoint> {
        if (track.size <= 2) return track
        val out = mutableListOf(track.first())
        for (p in track.drop(1)) if (p.timeMs - out.last().timeMs >= intervalSec * 1000L) out += p
        if (out.last() !== track.last()) out += track.last()
        return out
    }

    fun code(type: DriveEvent.Type) = when (type) {
        DriveEvent.Type.QUICK_START -> "Q"
        DriveEvent.Type.HARSH_ACCEL -> "A"
        DriveEvent.Type.HARSH_BRAKE -> "B"
        DriveEvent.Type.SUDDEN_STOP -> "S"
        DriveEvent.Type.OVERSPEED -> "O"
        DriveEvent.Type.LONG_IDLE -> "I"
    }

    /** Phone copy: nothing within this distance of where the trip started or ended (home, office). */
    const val PHONE_HIDE_RADIUS_M = 300

    /** What the phone page will get after hiding the start/end areas. */
    data class PhoneRoute(
        val points: List<TrackPoint>,
        val events: List<Pair<DriveEvent, Pair<Double, Double>>>,
        val dwells: List<Pair<Dwell, Pair<Double, Double>>>,
        val hiddenCount: Int,
    ) {
        val base: Pair<Double, Double>? get() =
            points.firstOrNull()?.let { it.lat to it.lon } ?: events.firstOrNull()?.second ?: dwells.firstOrNull()?.second
    }

    /**
     * Leaves out every point, event and stop within [hideRadiusM] of the first and the last known
     * position of the trip, so a leaked QR does not show where the driver lives or works.
     * 0 keeps everything (the car screen shows the full route).
     */
    fun phoneRoute(trip: Trip, intervalSec: Int, hideRadiusM: Int): PhoneRoute {
        val eventLocs = trip.events.mapNotNull { e -> TrackPoint.parse(e.location)?.let { e to it } }
        val dwellLocs = trip.dwells.mapNotNull { d -> TrackPoint.parse(d.location)?.let { d to it } }
        val located = trip.track.map { it.timeMs to (it.lat to it.lon) } +
            eventLocs.map { it.first.timeMs to it.second } + dwellLocs.map { it.first.startMs to it.second }
        val zones = if (hideRadiusM <= 0 || located.isEmpty()) emptyList()
            else listOf(located.minBy { it.first }.second, located.maxBy { it.first }.second)
        fun hidden(p: Pair<Double, Double>) = zones.any { distanceM(it, p) < hideRadiusM }
        val track = trip.track.filterNot { hidden(it.lat to it.lon) }
        val events = eventLocs.filterNot { hidden(it.second) }
        val dwells = dwellLocs.filterNot { hidden(it.second) }
        val hiddenCount = (trip.track.size - track.size) + (eventLocs.size - events.size) + (dwellLocs.size - dwells.size)
        return PhoneRoute(sample(track, intervalSec), events, dwells, hiddenCount)
    }

    /** Ground distance in metres (flat approximation, fine within a few km). */
    fun distanceM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val k = cos(Math.toRadians((a.first + b.first) / 2))
        val dy = (a.first - b.first) * 111_320.0
        val dx = (a.second - b.second) * 111_320.0 * k
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * {"t0": start ms, "iv": point spacing s, "h": hidden radius m (if any), "b": [lat, lon] x1e5,
     *  "p": [[dt s, dLat, dLon, speed|-1, limit|-1], ...]   (deltas from the previous point)
     *  "e": [[t s, code, dLat, dLon, speed|-1, limit|-1, peak x10|-1, length s|-1], ...] (from base)
     *  "d": [[t s, length s, dLat, dLon], ...]}  (stops of 1 minute or more, from base)
     * No "b" means nothing is left to draw.
     */
    fun encode(trip: Trip, intervalSec: Int, hideRadiusM: Int = 0): JSONObject {
        val route = phoneRoute(trip, intervalSec, hideRadiusM)
        val pts = route.points
        val base = route.base
        val t0 = trip.startMs
        fun e5(v: Double) = (v * 1e5).roundToInt()
        fun sec(ms: Long) = ((ms - t0) / 1000.0).roundToInt()

        return JSONObject().apply {
            put("t0", t0); put("iv", intervalSec)
            if (hideRadiusM > 0) put("h", hideRadiusM)
            if (base == null) return@apply
            put("b", JSONArray(listOf(e5(base.first), e5(base.second))))
            var prevT = t0; var prevLa = e5(base.first); var prevLo = e5(base.second)
            put("p", JSONArray(pts.map { p ->
                val la = e5(p.lat); val lo = e5(p.lon)
                // Seconds from the time the phone will have rebuilt (not from the exact previous time),
                // so rounding does not add up along a long trip.
                val dt = ((p.timeMs - prevT) / 1000.0).roundToInt()
                JSONArray(listOf(dt, la - prevLa, lo - prevLo,
                    p.speedKmh?.roundToInt() ?: -1, p.limitKmh ?: -1)).also { prevT += dt * 1000L; prevLa = la; prevLo = lo }
            }))
            put("e", JSONArray(route.events.map { (e, loc) ->
                JSONArray(listOf(sec(e.timeMs), code(e.type), e5(loc.first) - e5(base.first), e5(loc.second) - e5(base.second),
                    e.speedKmh?.roundToInt() ?: -1, e.limitKmh ?: -1,
                    e.peakRate?.let { (it * 10).roundToInt() } ?: -1,
                    e.endMs?.let { ((it - e.timeMs) / 1000).toInt() } ?: -1))
            }))
            put("d", JSONArray(route.dwells.map { (d, loc) ->
                JSONArray(listOf(sec(d.startMs), d.seconds.toInt(), e5(loc.first) - e5(base.first), e5(loc.second) - e5(base.second)))
            }))
        }
    }
}

/** Something drawn on the route view that can be tapped. */
data class RouteMark(
    val kind: Kind,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float?,
    val limitKmh: Int?,
    val event: DriveEvent? = null,
    val dwell: Dwell? = null,
) {
    enum class Kind { POINT, MINUTE, EVENT, DWELL }
}

object RouteModel {
    /** Everything that can be shown and tapped, most important last (drawn on top, picked first). */
    fun marks(trip: Trip): List<RouteMark> {
        val minuteTimes = RouteCodec.sample(trip.track, 60).map { it.timeMs }.toSet()
        val points = trip.track.map {
            RouteMark(if (it.timeMs in minuteTimes) RouteMark.Kind.MINUTE else RouteMark.Kind.POINT, it.timeMs, it.lat, it.lon, it.speedKmh, it.limitKmh)
        }
        val dwells = trip.dwells.mapNotNull { d ->
            TrackPoint.parse(d.location)?.let { RouteMark(RouteMark.Kind.DWELL, d.startMs, it.first, it.second, 0f, null, dwell = d) }
        }
        val events = trip.events.mapNotNull { e ->
            TrackPoint.parse(e.location)?.let { RouteMark(RouteMark.Kind.EVENT, e.timeMs, it.first, it.second, e.speedKmh, e.limitKmh, event = e) }
        }
        return points + dwells + events
    }

    /** Flat projection that keeps shapes right at city scale (x east, y south), fitted to a box. */
    class Projection(marks: List<RouteMark>, private val width: Float, private val height: Float, private val pad: Float) {
        // Short trips: show at least ~400 m around the route so a few points do not fill the screen.
        private val rawMinLat = marks.minOfOrNull { it.lat } ?: 0.0
        private val rawMaxLat = marks.maxOfOrNull { it.lat } ?: 0.0
        private val rawMinLon = marks.minOfOrNull { it.lon } ?: 0.0
        private val rawMaxLon = marks.maxOfOrNull { it.lon } ?: 0.0
        private val k = cos(Math.toRadians((rawMinLat + rawMaxLat) / 2))
        private val spanY = (rawMaxLat - rawMinLat).coerceAtLeast(MIN_SPAN_DEG)
        private val spanX = ((rawMaxLon - rawMinLon) * k).coerceAtLeast(MIN_SPAN_DEG)
        private val minLat = (rawMinLat + rawMaxLat) / 2 - spanY / 2
        private val maxLat = minLat + spanY
        private val minLon = (rawMinLon + rawMaxLon) / 2 - spanX / k / 2
        private val scale = minOf((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
        private val offX = (width - spanX * scale) / 2
        private val offY = (height - spanY * scale) / 2

        fun x(lon: Double) = (offX + (lon - minLon) * k * scale).toFloat()
        fun y(lat: Double) = (offY + (maxLat - lat) * scale).toFloat()
    }

    /** Radius of the hollow rings for accel/brake on the car screen (px). */
    const val RING_PX = 13f

    /** 1-minute stops: bigger ring so an accel/brake ring at the same spot stays visible inside. */
    const val DWELL_RING_PX = 22f

    /** About 400 m of latitude. */
    const val MIN_SPAN_DEG = 0.0036

    fun nearest(marks: List<RouteMark>, proj: Projection, tx: Float, ty: Float, maxPx: Float): RouteMark? {
        // Events and stops win over plain points when they are close to the finger.
        val ranked = marks.map { m ->
            val dx = proj.x(m.lon) - tx; val dy = proj.y(m.lat) - ty
            val center = kotlin.math.sqrt(dx * dx + dy * dy)
            // A stop is a ring: touching the ring picks it, touching the middle picks what is inside.
            val d = if (m.kind == RouteMark.Kind.DWELL) kotlin.math.abs(center - DWELL_RING_PX) else center
            val bonus = when (m.kind) { RouteMark.Kind.EVENT -> 18f; RouteMark.Kind.DWELL -> 14f; RouteMark.Kind.MINUTE -> 6f; RouteMark.Kind.POINT -> 0f }
            m to d - bonus
        }.filter { it.second <= maxPx }
        return ranked.minByOrNull { it.second }?.first
    }
}
