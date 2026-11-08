package com.chagok.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.MotionEvent
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import java.io.File

/**
 * Car-screen route map on an OpenStreetMap base map (osmdroid), same rules as the drawn map and the
 * phone page: first view with ~6.5 minute dots across, thinned dots, only dots/events/stops tappable.
 *
 * OSM tile policy (operations.osmfoundation.org/policies/tiles): a distinct User-Agent naming the app,
 * visible attribution (CopyrightOverlay), normal on-screen loading only (no prefetch / offline download),
 * honour cache headers (osmdroid's tile cache does). Without internet the screen uses the drawn map instead.
 */
object OsmMap {
    private var configured = false

    /** Once per process, before the first MapView. Cache lives in the app's own cache dir (no storage permission). */
    fun configure(context: Context) {
        if (configured) return
        val conf = Configuration.getInstance()
        conf.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        conf.userAgentValue = "Chagok/${BuildConfigInfo.versionName(context)} (Pleos Connect contest app; +https://github.com/Dkgkgk622/Chagok)"
        val base = File(context.cacheDir, "osmdroid")
        conf.osmdroidBasePath = base
        conf.osmdroidTileCache = File(base, "tiles")
        configured = true
    }

    /** True when the system says there is a network with internet (tiles can load). */
    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Box (north, east, south, west) of a RouteModel.View on a [w] x [h] px map. */
    fun box(g: RouteModel.Ground, v: RouteModel.View, w: Float, h: Float): BoundingBox {
        val hw = v.mpp * w / 2; val hh = v.mpp * h / 2
        return BoundingBox(g.latOf(v.cy + hh), g.lonOf(v.cx + hw), g.latOf(v.cy - hh), g.lonOf(v.cx - hw))
    }

    /** New MapView: OSM tiles drawn grey-ish, multi-touch, no built-in zoom buttons (the screen has its own). */
    fun newMapView(context: Context): MapView {
        configure(context)
        return MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true // street names readable on the car screen
            tilesScaleFactor = 1.5f    // and 1.5x more on top (tried on the emulator: names were too small)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            setMaxZoomLevel(19.0)
            overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.2f) }))
            overlays.add(CopyrightOverlay(context))
            // Inside the app's vertical scroll: keep drags and pinches on the map.
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }
}

/** osmdroid projection as a ScreenProjection (no map rotation, so x depends only on lon and y only on lat). */
private class OsmProjection(private val pj: Projection, private val lat0: Double, private val lon0: Double) : ScreenProjection {
    private val p = Point()
    override fun x(lon: Double): Float = pj.toPixels(GeoPoint(lat0, lon), p).x.toFloat()
    override fun y(lat: Double): Float = pj.toPixels(GeoPoint(lat, lon0), p).y.toFloat()
}

/**
 * Road, minute dots, events, stops and the selection triangle, drawn in screen pixels on top of the tiles.
 * [marks] = what the layer toggles currently show. Tap → [onTap] with the picked mark (or null).
 */
class RouteOverlay(
    private val ground: RouteModel.Ground,
    private val colorOf: (RouteMark) -> Int,
    private val roadColor: Int,
    private val dwellColor: Int,
    private val selColor: Int,
    private val onTap: (RouteMark?) -> Unit,
) : Overlay() {
    var marks: List<RouteMark> = emptyList()
    var selected: RouteMark? = null

    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun proj(mapView: MapView) = OsmProjection(mapView.projection, ground.lat0, ground.lon0)

    override fun draw(c: Canvas, pj: Projection) {
        val p = OsmProjection(pj, ground.lat0, ground.lon0)
        val line = marks.filter { it.kind == RouteMark.Kind.POINT || it.kind == RouteMark.Kind.MINUTE }
        if (line.size > 1) {
            val path = Path()
            line.forEachIndexed { i, m -> if (i == 0) path.moveTo(p.x(m.lon), p.y(m.lat)) else path.lineTo(p.x(m.lon), p.y(m.lat)) }
            // See-through, so the street name printed on the tile under the route stays readable.
            road.color = android.graphics.Color.WHITE; road.alpha = 140; road.strokeWidth = 16f; c.drawPath(path, road) // casing
            road.color = roadColor; road.alpha = 150; road.strokeWidth = 9f; c.drawPath(path, road)
        }
        fun circle(m: RouteMark, r: Float, color: Int, width: Float? = null) {
            val paint = if (width == null) fill else stroke.apply { strokeWidth = width }
            paint.color = color
            c.drawCircle(p.x(m.lon), p.y(m.lat), r, paint)
        }
        line.firstOrNull()?.let { circle(it, 14f, selColor, 5f) }  // start: hollow
        line.lastOrNull()?.let { circle(it, 14f, selColor) }       // end: filled
        RouteModel.drawnMinutes(marks, p).forEach { circle(it, 7f, android.graphics.Color.WHITE); circle(it, 7f, roadColor, 3f) }
        marks.filter { it.kind == RouteMark.Kind.DWELL }.forEach {
            circle(it, RouteModel.DWELL_RING_PX, android.graphics.Color.WHITE, 10f); circle(it, RouteModel.DWELL_RING_PX, dwellColor, 6f)
        }
        marks.filter { it.kind == RouteMark.Kind.EVENT }.forEach {
            if (it.event!!.type == DriveEvent.Type.OVERSPEED) {
                circle(it, 13f, colorOf(it)); circle(it, 13f, android.graphics.Color.WHITE, 4f)
            } else {
                circle(it, RouteModel.RING_PX, android.graphics.Color.WHITE, 10f); circle(it, RouteModel.RING_PX, colorOf(it), 6f)
            }
        }
        selected?.let { m ->
            val x = p.x(m.lon); val y = p.y(m.lat)
            val tri = Path().apply { moveTo(x, y); lineTo(x - 18f, y - 34f); lineTo(x + 18f, y - 34f); close() }
            stroke.color = android.graphics.Color.WHITE; stroke.strokeWidth = 6f; c.drawPath(tri, stroke)
            fill.color = selColor; c.drawPath(tri, fill)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val p = proj(mapView)
        val hit = RouteModel.nearest(RouteModel.tappable(marks, p), p, e.x, e.y, 64f)
        onTap(hit)
        return hit != null
    }
}

/** versionName without generating BuildConfig (buildFeatures.buildConfig is off). */
private object BuildConfigInfo {
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }
}
