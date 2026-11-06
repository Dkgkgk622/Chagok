package com.chagok.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Road speed limit and current location from the built-in navigation (Pleos NaviHelper SDK).
 * Used only for overspeed records. Other navigation events are ignored.
 */
class NaviSource(private val context: Context) : NaviHelperEventListener {

    private val _speedLimitKmh = MutableStateFlow<Int?>(null)
    val speedLimitKmh: StateFlow<Int?> = _speedLimitKmh

    private val _location = MutableStateFlow<String?>(null)
    /** Last known location as "lat,lon" (or the raw text if it cannot be parsed). */
    val location: StateFlow<String?> = _location

    private val _status = MutableStateFlow(Signal())
    val status: StateFlow<Signal> = _status

    /** Called with the fresh location after [requestLocation]. */
    var onLocation: ((String) -> Unit)? = null

    private var helper: NaviHelper? = null

    fun start() {
        if (helper != null) return
        try {
            val h = NaviHelper(context.applicationContext)
            h.initialize()
            h.addListener(this)
            helper = h
            _status.value = Signal("연결됨 (제한속도 대기)", Signal.Status.WAITING)
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper start failed", e)
            _status.value = Signal("오류", Signal.Status.ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    fun requestLocation() {
        try {
            helper?.getCurrentLocationInfo()
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentLocationInfo failed", e)
        }
    }

    fun stop() {
        try {
            helper?.removeListener(this)
            helper?.release()
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper stop failed", e)
        }
        helper = null
    }

    // ---------------- events we use ----------------

    override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
        val limit = speedLimit.takeIf { it > 0 }
        _speedLimitKmh.value = limit
        _status.value = Signal(limit?.let { "제한속도 $it km/h" } ?: "제한속도 정보 없음", Signal.Status.OK)
    }

    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
        val loc = formatLocation(currentLocationInfo.toString())
        _location.value = loc
        onLocation?.invoke(loc)
    }

    override fun onError(errorCode: NaviErrorCode) {
        Log.w(TAG, "navi error $errorCode")
        _status.value = Signal("오류", Signal.Status.ERROR, errorCode.toString())
    }

    // ---------------- events we do not use ----------------

    override fun onRouteStarted(routeStartInfo: RouteStartInfo) = Unit
    override fun onRouteEnded() = Unit
    override fun onRoutingCompleted() = Unit
    override fun onReRouteEnded() = Unit
    override fun onRouteOptionChanged(routeOption: RouteOption) = Unit
    override fun onRouteCancelled() = Unit
    override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = Unit
    override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = Unit
    override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = Unit
    override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = Unit
    override fun onDestinationInfo(destinationInfo: DestinationInfo) = Unit
    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = Unit
    override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = Unit
    override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = Unit
    override fun onCameraAlert(cameraAlertList: List<CameraAlert>) = Unit
    override fun onDrivingInfo(drivingInfo: DrivingInfo) = Unit
    override fun onBookMarkChanged() = Unit
    override fun onDestinationChanged() = Unit
    override fun onNavigationStatus(status: NavigationStatus) = Unit
    override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = Unit
    override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) = Unit
    override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) = Unit
    override fun onTripInfo(tripInfo: TripInfo) = Unit

    /**
     * The location class's field names are not documented, so latitude/longitude are read
     * from its text form. Falls back to the raw text (shortened) if the format differs.
     */
    private fun formatLocation(raw: String): String {
        val lat = Regex("""lat\w*\s*=\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
        val lon = Regex("""(?:lon|lng)\w*\s*=\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
        return if (lat != null && lon != null) {
            String.format(Locale.US, "%.5f,%.5f", lat.toDouble(), lon.toDouble())
        } else {
            raw.take(120)
        }
    }

    companion object {
        private const val TAG = "Chagok"
    }
}
