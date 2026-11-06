package com.chagok.app

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Starts data collection when the process starts (app launch or boot),
 * independent of whether any screen is visible.
 */
class ChagokApp : Application() {

    lateinit var dataSource: VehicleDataSource
        private set
    lateinit var recorder: TripRecorder
        private set
    lateinit var navi: NaviSource
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Detailed test logs only in debug builds: a release build does no extra logging work.
        verbose = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        dataSource = VehicleDataSource(this)
        recorder = TripRecorder(TripStore(this))
        recorder.onDrivingChanged = { driving -> setRecorderService(driving) }

        dataSource.startPleos()
        dataSource.startCarApi(hasSpeedPermission())

        // Navigation: road speed limit + location, used for overspeed records.
        navi = NaviSource(this)
        recorder.requestLocation = { navi.requestLocation() }
        navi.onLocation = { loc ->
            recorder.onLocation(loc)
            // Test log: every answer (the flow below only logs changes).
            if (verbose) Log.i(TAG, "위치 수신 $loc → 경로 점 ${recorder.logbook.value.openTrip?.track?.size ?: "(운행 중 아님)"}개")
        }
        navi.start()

        scope.launch {
            dataSource.snapshot.collect { recorder.onSnapshot(it) }
        }
        scope.launch {
            dataSource.plate.collect { plate -> if (plate != null) recorder.fillPlateFromCar(plate) }
        }
        scope.launch {
            navi.speedLimitKmh.collect {
                if (verbose) Log.i(TAG, "내비 제한속도 ${it ?: "없음"} km/h")
                recorder.speedLimitKmh = it
            }
        }
        scope.launch {
            navi.location.collect { loc ->
                if (loc != null) {
                    if (verbose) Log.i(TAG, "내비 위치 $loc")
                    recorder.lastLocation = loc
                }
            }
        }
        scope.launch {
            navi.status.collect {
                if (verbose) Log.i(TAG, "내비 상태 ${it.status} ${it.value} ${it.detail}")
                dataSource.reportExternal(Field.SPEED_LIMIT, it)
            }
        }

        if (verbose) startTestLog()

        // Process was restarted in the middle of a trip: show the recording state.
        if (recorder.logbook.value.openTrip != null) setRecorderService(true)
    }

    /**
     * Recording log for testing. While driving the system blocks this app's screen,
     * so Logcat (filter: tag:Chagok) is the way to watch recording live.
     */
    private fun startTestLog() {
        scope.launch {
            var lastGear: Gear? = null
            var lastSpeedLogMs = 0L
            var lastSpeedLogged: Float? = null
            dataSource.snapshot.collect { s ->
                if (s.gear != lastGear) {
                    Log.i(TAG, "기어 ${lastGear?.label ?: "-"} → ${s.gear?.label ?: "값 없음"}")
                    lastGear = s.gear
                }
                // Speed arrives about once a second even when unchanged: log changes, plus every 10 s.
                val speed = s.speedKmh
                if (speed != null && s.timeMs - lastSpeedLogMs >= 1_000 &&
                    (lastSpeedLogged == null || kotlin.math.abs(speed - lastSpeedLogged!!) >= 1f ||
                        s.timeMs - lastSpeedLogMs >= 10_000)
                ) {
                    Log.i(TAG, "차속 %.1f km/h, 계기판 %s km".format(speed, s.odometerKm ?: "-"))
                    lastSpeedLogMs = s.timeMs
                    lastSpeedLogged = speed
                }
            }
        }
        scope.launch {
            var prevOpen: Trip? = recorder.logbook.value.openTrip
            var prevClosed = recorder.logbook.value.closedTrips.size
            var prevSafety = recorder.logbook.value.safetyEvents.toSet()
            recorder.logbook.collect { book ->
                book.safetyEvents.filterNot { it in prevSafety }.forEach { e ->
                    Log.i(TAG, "경고 기록: ${e.label} ${if (e.active) "켜짐" else "꺼짐"} (" +
                        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(e.timeMs)) +
                        ", 계기판 ${e.odometerKm ?: "-"} km)")
                }
                prevSafety = book.safetyEvents.toSet()
                val open = book.openTrip
                if (open != null && prevOpen?.id != open.id) Log.i(TAG, "트립 시작 #${open.id}")
                if (open != null && prevOpen?.id == open.id) {
                    open.events.drop(prevOpen!!.events.size).forEach { e ->
                        Log.i(TAG, "이벤트 ${e.type.label} " + listOfNotNull(
                            e.speedKmh?.let { "%.0fkm/h".format(it) },
                            e.limitKmh?.let { "제한 ${it}km/h" },
                        ).joinToString(" "))
                    }
                }
                if (book.closedTrips.size > prevClosed) {
                    val t = book.closedTrips.last()
                    Log.i(TAG, "트립 종료 #${t.id}: %d초, %.2fkm(%s)%s, 최고 %.0fkm/h, 정지 %d회, 정차 대기 %d초, 이벤트 %s".format(
                        t.durationMs / 1000, t.distanceKm, t.distanceSource?.label ?: "측정 없음",
                        if (t.distanceMismatch) " 출처 간 차이" else "",
                        t.maxSpeedKmh, t.stopCount, t.idleSeconds,
                        t.events.groupingBy { it.type.label }.eachCount().ifEmpty { "없음" },
                    ))
                }
                if (open == null && prevOpen != null && book.closedTrips.size == prevClosed) {
                    Log.i(TAG, "트립 #${prevOpen!!.id} 폐기 (10초 미만이고 0.05km 미만)")
                }
                prevOpen = open
                prevClosed = book.closedTrips.size
            }
        }
    }

    fun hasSpeedPermission() =
        ContextCompat.checkSelfPermission(this, CAR_SPEED) == PackageManager.PERMISSION_GRANTED

    /**
     * Start the always-on recorder service. Works from a boot/update broadcast or while the app
     * is on screen; refused by Android 12+ from the background, which is logged.
     */
    fun startRecorderService(reason: String) {
        if (RecorderService.running) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, RecorderService::class.java))
            Log.i(TAG, "기록 서비스 시작 ($reason)")
        } catch (e: Exception) {
            Log.w(TAG, "기록 서비스 시작 실패 ($reason): ${e.javaClass.simpleName}")
        }
    }

    private fun setRecorderService(driving: Boolean) {
        RecorderService.setDriving(this, driving)
        // Last chance if the service is not running yet (e.g. never opened since update).
        if (driving && !RecorderService.running) startRecorderService("운행 시작")
    }

    companion object {
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
        const val TAG = "Chagok"

        /** True in debug builds: test logs on (see startTestLog). */
        @Volatile var verbose = false
            private set

        /**
         * Address of the phone report page (docs/report.html published with GitHub Pages).
         * The repository must be named exactly "Chagok" (the path is part of the address).
         */
        const val REPORT_BASE_URL = "https://dkgkgk622.github.io/Chagok/report.html"
    }
}
