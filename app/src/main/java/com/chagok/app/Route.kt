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

/** Lat/lon to screen pixels. Implemented by the drawn map (RouteModel.Projection) and the base map (OsmMap.kt). */
interface ScreenProjection {
    fun x(lon: Double): Float
    fun y(lat: Double): Float
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

    /** Flat metres around the middle of the trip (x east, y north): fixed per trip so views can move. */
    class Ground(marks: List<RouteMark>) {
        val lat0 = ((marks.minOfOrNull { it.lat } ?: 0.0) + (marks.maxOfOrNull { it.lat } ?: 0.0)) / 2
        val lon0 = ((marks.minOfOrNull { it.lon } ?: 0.0) + (marks.maxOfOrNull { it.lon } ?: 0.0)) / 2
        val k = cos(Math.toRadians(lat0))
        fun x(lon: Double) = (lon - lon0) * M_PER_DEG * k
        fun y(lat: Double) = (lat - lat0) * M_PER_DEG
        fun lonOf(x: Double) = lon0 + x / (M_PER_DEG * k)
        fun latOf(y: Double) = lat0 + y / M_PER_DEG
        val minX = marks.minOfOrNull { x(it.lon) } ?: 0.0
        val maxX = marks.maxOfOrNull { x(it.lon) } ?: 0.0
        val minY = marks.minOfOrNull { y(it.lat) } ?: 0.0
        val maxY = marks.maxOfOrNull { y(it.lat) } ?: 0.0
        /** The road line (track points in time order) in Ground metres. */
        val line: List<Pair<Double, Double>> = marks.filter { it.kind == RouteMark.Kind.POINT || it.kind == RouteMark.Kind.MINUTE }
            .sortedBy { it.timeMs }.map { x(it.lon) to y(it.lat) }
        /** Typical metres per minute while moving (median, >= 3 km/h), from the 1-minute dots. Null if not moving. */
        val perMinM: Double? = marks.filter { it.kind == RouteMark.Kind.MINUTE }.zipWithNext().mapNotNull { (a, b) ->
            val min = (b.timeMs - a.timeMs) / 60_000.0
            if (min <= 0) null else (kotlin.math.hypot(x(b.lon) - x(a.lon), y(b.lat) - y(a.lat)) / min).takeIf { it >= 50 }
        }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    }

    /** Centre in Ground metres, metres per pixel; [partial] = only the start of a long route is shown. */
    data class View(val cx: Double, val cy: Double, val mpp: Double, val partial: Boolean) {
        /** Keeps the ground under (px, py) still while changing the scale by [factor]. */
        fun zoomAt(px: Float, py: Float, w: Float, h: Float, factor: Double, minMpp: Double, maxMpp: Double): View {
            val m = (mpp * factor).coerceIn(minMpp, maxMpp)
            val gx = cx + (px - w / 2) * mpp; val gy = cy - (py - h / 2) * mpp
            return copy(mpp = m, cx = gx - (px - w / 2) * m, cy = gy + (py - h / 2) * m)
        }
        fun pan(dx: Float, dy: Float) = copy(cx = cx - dx * mpp, cy = cy + dy * mpp)

        /**
         * Never lose the road: if no part of [line] (Ground metres, in order) is within [keepPx] of the edges
         * of a [w] x [h] map, slide just enough that the nearest part of it is back inside. Same rule as keepRoute
         * in docs/report.html.
         */
        fun keepRoute(line: List<Pair<Double, Double>>, w: Float, h: Float, keepPx: Float = 60f): View {
            if (line.isEmpty()) return this
            var best = line[0]; var bestD = Double.MAX_VALUE
            for (i in line.indices) {
                val p = if (i == 0) line[0] else {
                    val (ax, ay) = line[i - 1]; val (bx, by) = line[i]
                    val dx = bx - ax; val dy = by - ay; val l = dx * dx + dy * dy
                    val u = if (l > 0) (((cx - ax) * dx + (cy - ay) * dy) / l).coerceIn(0.0, 1.0) else 0.0
                    (ax + u * dx) to (ay + u * dy)
                }
                val d = kotlin.math.hypot(p.first - cx, p.second - cy)
                if (d < bestD) { bestD = d; best = p }
            }
            val hx = maxOf(0f, w / 2 - keepPx) * mpp; val hy = maxOf(0f, h / 2 - keepPx) * mpp
            return copy(cx = cx.coerceIn(best.first - hx, best.first + hx), cy = cy.coerceIn(best.second - hy, best.second + hy))
        }
    }

    /** About this many 1-minute dots across the screen on a straight road (same rule as the phone page). */
    const val DOTS_ACROSS = 6.5
    /** Never closer than about this many metres across (one spot, traffic jam). */
    const val MIN_SPAN_M = 300.0
    private const val M_PER_DEG = 111_320.0

    /**
     * First view of a [w] x [h] px map. A long route is not squeezed onto one screen: it starts near the
     * beginning with about [DOTS_ACROSS] minute dots across. [whole] (or a route that already fits) shows everything.
     */
    fun firstView(marks: List<RouteMark>, g: Ground, w: Float, h: Float, pad: Float, whole: Boolean): View {
        val floor = MIN_SPAN_M / (minOf(w, h) - 2 * pad).coerceAtLeast(1f)
        val fitMpp = maxOf((g.maxX - g.minX) / (w - 2 * pad), (g.maxY - g.minY) / (h - 2 * pad), floor)
        val fit = View((g.minX + g.maxX) / 2, (g.minY + g.maxY) / 2, fitMpp, partial = false)
        val perMin = g.perMinM
        if (whole || perMin == null || marks.isEmpty()) return fit
        val capMpp = maxOf(DOTS_ACROSS * perMin / w, floor) // 6.5 steps across the whole visible width
        if (fitMpp <= capMpp) return fit
        // Put the start just inside the screen edge, on the side the road leaves from, so the road runs across
        // the whole screen: direction = start -> where the road is half a screen later. Same as docs/report.html.
        val line = g.line.ifEmpty { marks.sortedBy { it.timeMs }.map { g.x(it.lon) to g.y(it.lat) } }
        val s0 = line.first()
        val half = w / 2 * capMpp
        val a = line.firstOrNull { kotlin.math.hypot(it.first - s0.first, it.second - s0.second) >= half } ?: line.last()
        val len = kotlin.math.hypot(a.first - s0.first, a.second - s0.second)
        if (len == 0.0) return View(s0.first, s0.second, capMpp, partial = true)
        val ux = (a.first - s0.first) / len; val uy = (a.second - s0.second) / len
        val toEdge = minOf(if (ux != 0.0) w / 2 / kotlin.math.abs(ux) else Double.MAX_VALUE,
            if (uy != 0.0) h / 2 / kotlin.math.abs(uy) else Double.MAX_VALUE) // px, centre -> edge
        val reach = maxOf(0.0, toEdge - 1.5 * pad) * capMpp // start ~1.5 padding inside the edge
        return View(s0.first + ux * reach, s0.second + uy * reach, capMpp, partial = true)
    }

    /** Lat/lon to screen pixels (x right, y down) for one [view]. */
    class Projection(private val g: Ground, private val view: View, private val width: Float, private val height: Float) : ScreenProjection {
        /** Whole route fitted to the box (padding [pad]). */
        constructor(marks: List<RouteMark>, width: Float, height: Float, pad: Float) :
            this(Ground(marks), firstView(marks, Ground(marks), width, height, pad, whole = true), width, height)

        override fun x(lon: Double) = (width / 2 + (g.x(lon) - view.cx) / view.mpp).toFloat()
        override fun y(lat: Double) = (height / 2 - (g.y(lat) - view.cy) / view.mpp).toFloat()
    }

    /** Radius of the hollow rings for accel/brake on the car screen (px). */
    const val RING_PX = 13f

    /** 1-minute stops: bigger ring so an accel/brake ring at the same spot stays visible inside. */
    const val DWELL_RING_PX = 22f

    /** Zoomed out, minute dots closer than this would merge into a dotted line: later ones are not drawn. */
    const val DOT_MIN_GAP_PX = 24f

    /** The 1-minute dots actually drawn for [proj] (thinned when zoomed out; the last one always stays). */
    fun drawnMinutes(marks: List<RouteMark>, proj: ScreenProjection): List<RouteMark> {
        val minutes = marks.filter { it.kind == RouteMark.Kind.MINUTE }
        var lastX = Float.NaN; var lastY = Float.NaN
        return minutes.filterIndexed { i, m ->
            val x = proj.x(m.lon); val y = proj.y(m.lat)
            val keep = lastX.isNaN() || kotlin.math.hypot(x - lastX, y - lastY) >= DOT_MIN_GAP_PX || i == minutes.lastIndex
            if (keep) { lastX = x; lastY = y }
            keep
        }
    }

    /**
     * What a tap can pick: the drawn minute dots, events and stops. Not the 15-second points or the road
     * between dots: zoomed in, "some spots work and some don't" was worse than "only the dots work" (Nov 2026).
     */
    fun tappable(marks: List<RouteMark>, proj: ScreenProjection): List<RouteMark> =
        drawnMinutes(marks, proj) + marks.filter { it.kind == RouteMark.Kind.EVENT || it.kind == RouteMark.Kind.DWELL }

    fun nearest(marks: List<RouteMark>, proj: ScreenProjection, tx: Float, ty: Float, maxPx: Float): RouteMark? {
        // Events and stops win over minute dots when they are close to the finger. Plain points never.
        val ranked = marks.filter { it.kind != RouteMark.Kind.POINT }.map { m ->
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
