package ai.pleos.playground.navi.helper.listener
import ai.pleos.playground.navi.constants.*
import ai.pleos.playground.navi.data.*
interface NaviHelperEventListener {
    fun onRouteStarted(routeStartInfo: RouteStartInfo); fun onRouteEnded(); fun onRoutingCompleted(); fun onReRouteEnded()
    fun onRouteOptionChanged(routeOption: RouteOption); fun onRouteCancelled()
    fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo); fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo)
    fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo); fun onError(errorCode: NaviErrorCode)
    fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo); fun onRouteStateInfo(routeStateInfo: RouteStateInfo)
    fun onDestinationInfo(destinationInfo: DestinationInfo); fun onWayPointInfo(waypointInfoList: List<WaypointInfo>)
    fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>); fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>)
    fun onCurrentRoadSpeedLimit(speedLimit: Int); fun onCameraAlert(cameraAlertList: List<CameraAlert>)
    fun onDrivingInfo(drivingInfo: DrivingInfo); fun onBookMarkChanged(); fun onDestinationChanged()
    fun onNavigationStatus(status: NavigationStatus); fun onTBTInfo(tbtInfoList: List<TBTInfo>)
    fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>); fun onCautionPointInfo(cautionPointList: List<CautionPoint>)
    fun onTripInfo(tripInfo: TripInfo)
}
