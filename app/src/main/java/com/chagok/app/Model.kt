package com.chagok.app

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** Raw vehicle values. null = not received yet. */
@Immutable
data class VehicleSnapshot(
    val timeMs: Long = System.currentTimeMillis(),
    val speedKmh: Float? = null,
    val gear: Gear? = null,
    val odometerKm: Float? = null,
    val tripMeterKm: Float? = null, // vehicle's own trip meter for the current driving session
    val batteryPercent: Int? = null,
    val brakePressed: Boolean? = null,
    val steeringDeg: Float? = null,
    val brakeFluidLow: Boolean? = null,
    val washerFluidLow: Boolean? = null,
    val powerDownWarning: Boolean? = null,
    val airbagDeployed: Boolean? = null,
    val telltales: Set<String>? = null, // warning lamps now on (Telltale codes); null = not received
)

/**
 * Warning lamps on the instrument cluster (Vehicle SDK Telltales, PRIVATE_CAR_SAFETY).
 * Only lamps that point to a fault or needed service are kept; status lamps such as turn
 * signals, headlamps, drive modes or an open door are ignored.
 */
object Telltale {
    val LABELS: Map<String, String> = linkedMapOf(
        "MASTER_WARNING" to "주 경고등",
        "ENGINE" to "엔진 경고등",
        "ENGINE_OIL_PRESSURE" to "엔진 오일 압력 경고등",
        "ENGINE_TEMPERATURE" to "엔진 과열 경고등",
        "EV_SYSTEM" to "전기차 시스템 경고등",
        "HEV_SYSTEM" to "하이브리드 시스템 경고등",
        "FCEV_SYSTEM" to "수소전기차 시스템 경고등",
        "EREV_SYSTEM" to "EREV 시스템 경고등",
        "RBS" to "회생 제동 시스템 경고등",
        "LOW_VOLT_BATTERY" to "12V 배터리 충전 경고등",
        "ABS" to "ABS 경고등",
        "ESC" to "차체자세제어(ESC) 경고등",
        "ELECTRONIC_PARKING_BRAKE" to "전자식 주차 브레이크 경고등",
        "MDPS" to "전동식 조향(MDPS) 경고등",
        "TPMS" to "타이어 공기압 경고등",
        "AIRBAG" to "에어백 경고등",
        "LED_HEADLAMP" to "LED 헤드램프 경고등",
        "AFS" to "AFS 헤드램프 경고등",
        "PARTICLE_FILTER" to "매연 필터(DPF) 경고등",
        "SCR" to "요소수(SCR) 경고등",
        "WATER_SEPARATOR" to "연료 필터 수분 경고등",
        "LPI" to "LPI 시스템 경고등",
        "H2_SENSOR" to "수소 센서 경고등",
        "H2_LEAK" to "수소 누출 경고등",
    )

    /** ESC blinks while it is working (normal); only a steady ESC lamp means a fault. */
    private val BLINK_IS_NORMAL = setOf("ESC")

    fun accept(code: String, blinking: Boolean) = code in LABELS && !(blinking && code in BLINK_IS_NORMAL)

    fun label(code: String) = LABELS[code] ?: code
}

/** Registration number as sent by the car (CarInfo CRN). */
object CarPlate {
    // 12가3456, 123가4567, 서울12가3456 (spaces allowed)
    private val PATTERN = Regex("""^([가-힣]{2}\s?)?\d{2,3}\s?[가-힣]\s?\d{4}$""")

    /** Usable plate, or null for blanks and placeholders such as "invalid" (the emulator sends that). */
    fun normalize(raw: String?): String? {
        val v = raw?.trim()?.trim('"', '\'', '“', '”')?.trim() ?: return null
        return v.takeIf { PATTERN.matches(it) }
    }
}

/** One time a warning came on, and how long it stayed (endMs null = still on). */
@Immutable
data class WarningEpisode(val key: String, val label: String, val startMs: Long, val odometerKm: Float?, val endMs: Long?)

/** Pairs each "on" with the next "off" of the same warning; newest first. */
fun warningEpisodes(events: List<SafetyEvent>): List<WarningEpisode> {
    val open = mutableMapOf<String, SafetyEvent>()
    val out = mutableListOf<WarningEpisode>()
    events.sortedBy { it.timeMs }.forEach { e ->
        if (e.active) open.putIfAbsent(e.key, e)
        else open.remove(e.key)?.let { on -> out += WarningEpisode(on.key, on.label, on.timeMs, on.odometerKm, e.timeMs) }
    }
    open.values.forEach { on -> out += WarningEpisode(on.key, on.label, on.timeMs, on.odometerKm, null) }
    return out.sortedByDescending { it.startMs }
}

/** Keys for "warnings active now": a SafetyEvent.Type name, or "TT_" + a telltale code. */
object Warnings {
    const val TELLTALE_PREFIX = "TT_"
    fun label(key: String): String =
        if (key.startsWith(TELLTALE_PREFIX)) Telltale.label(key.removePrefix(TELLTALE_PREFIX))
        else runCatching { SafetyEvent.Type.valueOf(key).label }.getOrDefault(key)
    fun labels(keys: List<String>) = keys.joinToString { label(it) }.ifEmpty { "없음" }
}

enum class Gear(val label: String) { P("P"), R("R"), N("N"), D("D"), OTHER("?") }

enum class TripPurpose(val label: String) {
    UNSET("미지정"), BUSINESS("업무"), COMMUTE("출퇴근"), PERSONAL("개인");
}

/** One detected driving event inside a trip. */
@Immutable
data class DriveEvent(
    val timeMs: Long,
    val type: Type,
    val odometerKm: Float?,
    val speedKmh: Float? = null,   // overspeed: highest speed in the episode; others: speed when it started
    val limitKmh: Int? = null,     // road speed limit from navigation at that moment
    val location: String? = null,  // overspeed: "lat,lon" from navigation
    val peakRate: Float? = null,   // harsh accel/brake: strongest change in the episode (km/h per second)
    val endMs: Long? = null,       // harsh accel/brake: when the episode ended (for graphs)
) {
    /**
     * Criteria follow the Korea Transportation Safety Authority's risky-driving definitions
     * (operation record analysis system), as cited in published research.
     */
    enum class Type(val label: String) {
        HARSH_ACCEL("급가속"),   // > 11 km/h per second while moving
        QUICK_START("급출발"),   // > 11 km/h per second from standstill
        HARSH_BRAKE("급감속"),   // > 7.5 km/h per second while still moving
        SUDDEN_STOP("급정지"),   // > 7.5 km/h per second ending at standstill
        OVERSPEED("과속"),       // > speed limit + 20 km/h
        LONG_IDLE("장시간 정차(이전 기록)"), // no longer produced; kept so old logbooks load
    }

    fun toJson() = JSONObject().apply {
        put("t", timeMs); put("type", type.name); putOpt("odo", odometerKm?.toDouble())
        putOpt("sp", speedKmh?.toDouble()); putOpt("lim", limitKmh); putOpt("loc", location)
        putOpt("pr", peakRate?.toDouble()); putOpt("te", endMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = DriveEvent(
            timeMs = o.getLong("t"),
            type = Type.valueOf(o.getString("type")),
            odometerKm = o.optDoubleOrNull("odo")?.toFloat(),
            speedKmh = o.optDoubleOrNull("sp")?.toFloat(),
            limitKmh = o.optIntOrNull("lim"),
            location = if (o.has("loc") && !o.isNull("loc")) o.getString("loc") else null,
            peakRate = o.optDoubleOrNull("pr")?.toFloat(),
            endMs = if (o.has("te") && !o.isNull("te")) o.getLong("te") else null,
        )
    }
}

/** A location sample on the route (from navigation, about every 15 s while moving). */
@Immutable
data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float?,
    val limitKmh: Int?,
) {
    fun toJson() = JSONObject().apply {
        put("t", timeMs); put("la", lat); put("lo", lon)
        putOpt("sp", speedKmh?.toDouble()); putOpt("lim", limitKmh)
    }

    companion object {
        fun fromJson(o: JSONObject) = TrackPoint(
            timeMs = o.getLong("t"),
            lat = o.getDouble("la"),
            lon = o.getDouble("lo"),
            speedKmh = o.optDoubleOrNull("sp")?.toFloat(),
            limitKmh = o.optIntOrNull("lim"),
        )

        /** "lat,lon" from navigation. */
        fun parse(text: String?): Pair<Double, Double>? {
            val parts = text?.split(",") ?: return null
            if (parts.size != 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lon = parts[1].trim().toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return lat to lon
        }
    }
}

/** A stop of at least a minute while not in P (traffic, waiting, pick-up...). */
@Immutable
data class Dwell(
    val startMs: Long,
    val endMs: Long,
    val location: String?,
) {
    val seconds get() = (endMs - startMs) / 1000

    fun toJson() = JSONObject().apply { put("s", startMs); put("e", endMs); putOpt("loc", location) }

    companion object {
        fun fromJson(o: JSONObject) = Dwell(
            startMs = o.getLong("s"),
            endMs = o.getLong("e"),
            location = if (o.has("loc") && !o.isNull("loc")) o.getString("loc") else null,
        )
    }
}

/**
 * The odometer moved while nothing was recorded (app not running, signal missed...).
 * Shown in the app as an estimate only; never added to reports.
 */
@Immutable
data class MissedGap(
    val fromMs: Long,
    val toMs: Long,
    val startOdometerKm: Float,
    val endOdometerKm: Float,
) {
    val km get() = endOdometerKm - startOdometerKm

    fun toJson() = JSONObject().apply {
        put("f", fromMs); put("to", toMs); put("so", startOdometerKm.toDouble()); put("eo", endOdometerKm.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = MissedGap(
            fromMs = o.getLong("f"),
            toMs = o.getLong("to"),
            startOdometerKm = o.getDouble("so").toFloat(),
            endOdometerKm = o.getDouble("eo").toFloat(),
        )
    }
}

/**
 * A value a person entered for a trip (purpose, visit purpose, driver) and when.
 * Times, distances and events are recorded by the car and cannot be edited; these are the
 * human parts, so their history is kept for the logbook's credibility.
 */
@Immutable
data class TripEdit(val timeMs: Long, val field: Field, val value: String) {
    enum class Field(val code: String, val label: String) {
        PURPOSE("p", "운행 목적"), MEMO("m", "방문 목적"), DRIVER("d", "사용자");
        companion object { fun of(code: String) = entries.firstOrNull { it.code == code } }
    }

    fun toJson() = JSONObject().apply { put("t", timeMs); put("f", field.code); put("v", value) }

    companion object {
        fun fromJson(o: JSONObject): TripEdit? {
            val f = Field.of(o.optString("f")) ?: return null
            return TripEdit(o.getLong("t"), f, o.optString("v"))
        }
    }
}

/** One trip: from leaving P to returning to P. */
@Immutable
data class Trip(
    val id: Long,
    val startMs: Long,
    val endMs: Long? = null,
    val startOdometerKm: Float? = null,
    val endOdometerKm: Float? = null,
    val startBattery: Int? = null,
    val endBattery: Int? = null,
    val maxSpeedKmh: Float = 0f,
    val tripMeterStartKm: Float? = null,
    val tripMeterEndKm: Float? = null,
    val locationDistanceKm: Float = 0f, // sum of distances between navigation locations
    val speedDistanceKm: Float = 0f, // distance integrated from vehicle speed
    val idleSeconds: Long = 0, // stopped while not in P ("정차 대기 시간")
    val stopCount: Int = 0,
    val events: List<DriveEvent> = emptyList(),
    val purpose: TripPurpose = TripPurpose.UNSET,
    val duringServiceVisit: Boolean = false,
    val track: List<TrackPoint> = emptyList(),
    val dwells: List<Dwell> = emptyList(),
    val memo: String = "",        // business trips: visit purpose (goes to the logbook's 비고)
    val driver: String = "",      // who drove, if not the profile's default user (logbook ④)
    val edits: List<TripEdit> = emptyList(), // when a person set/changed purpose, visit purpose, driver
    val demo: Boolean = false,    // generated for testing / the demo video, not driven
    /** The odometer arrived only after the car had moved and could not be tied to the last trip's end. */
    val startOdometerLate: Boolean = false,
) {
    val isOpen get() = endMs == null
    /** Trip meter difference (the meter can reset with a new driving session). */
    val tripMeterDistanceKm: Float?
        get() {
            val s = tripMeterStartKm ?: return null
            val e = tripMeterEndKm ?: return null
            return if (e >= s) e - s else e
        }

    /**
     * Every distance measurement available for this trip, in priority order:
     * odometer > vehicle trip meter > navigation locations > speed x time.
     * A source counts only if it actually moved.
     */
    val distanceCandidates: List<Pair<DistanceSource, Float>>
        get() = listOfNotNull(
            odometerDistanceKm?.let { DistanceSource.ODOMETER to it },
            tripMeterDistanceKm?.let { DistanceSource.TRIP_METER to it },
            DistanceSource.LOCATION to locationDistanceKm,
            DistanceSource.SPEED to speedDistanceKm,
        ).filter { it.second >= MIN_MEASURED_KM }

    /** The source used for this trip's distance. */
    val distanceSource: DistanceSource? get() = distanceCandidates.firstOrNull()?.first

    /** Distance from the highest-priority source that measured anything. */
    val distanceKm: Float get() = distanceCandidates.firstOrNull()?.second ?: 0f

    /** True when another source disagrees with the chosen one by more than the tolerance. */
    val distanceMismatch: Boolean
        get() {
            val chosen = distanceCandidates.firstOrNull()?.second ?: return false
            return distanceCandidates.drop(1).any { (_, v) ->
                val diff = kotlin.math.abs(v - chosen)
                diff > MISMATCH_MIN_KM && diff / chosen > MISMATCH_RATIO
            }
        }
    /** Raw odometer difference, unchecked (can be negative or impossible). */
    val odometerRawDistanceKm: Float?
        get() {
            val s = startOdometerKm ?: return null
            val e = endOdometerKm ?: return null
            return e - s
        }

    /**
     * Odometer moved backwards or further than the car could have driven in this time
     * (e.g. the value arrived late, was reset, or was changed in a simulator).
     * Upper bound: max speed (or 250 km/h if speed is unknown) x 1.2 x duration, plus 1 km
     * for odometers that count in whole kilometers.
     */
    val odometerImplausible: Boolean
        get() {
            val raw = odometerRawDistanceKm ?: return false
            if (raw < 0f) return true
            val hours = durationMs / 3_600_000f
            val topSpeed = if (maxSpeedKmh > 0f) maxOf(maxSpeedKmh * 1.2f, 30f) else 250f
            return raw > topSpeed * hours + ODOMETER_TOLERANCE_KM
        }

    /** Distance from the instrument-cluster odometer only (null when not available or implausible). */
    val odometerDistanceKm: Float?
        get() = odometerRawDistanceKm?.takeUnless { odometerImplausible }
    val durationMs get() = (endMs ?: System.currentTimeMillis()) - startMs
    val batteryUsed: Int?
        get() {
            val s = startBattery ?: return null
            val e = endBattery ?: return null
            return (s - e)
        }

    fun count(type: DriveEvent.Type) = events.count { it.type == type }

    /**
     * Short note for the logbook's ⑩ 비고 about how the human-entered parts were entered:
     * entered a day or more after the trip, or changed after first being set.
     */
    fun auditNote(): String {
        val end = endMs ?: return ""
        return TripEdit.Field.entries.mapNotNull { f ->
            val list = edits.filter { it.field == f }
            if (list.isEmpty()) return@mapNotNull null
            val days = (list.first().timeMs - end) / (24 * 3_600_000L)
            val parts = listOfNotNull(
                if (days >= 1) "운행 ${days}일 뒤 입력" else null,
                if (list.size > 1) "수정 ${list.size - 1}회" else null,
            )
            if (parts.isEmpty()) null else "${f.label} ${parts.joinToString("·")}"
        }.joinToString(", ")
    }

    /** A business trip whose visit purpose has not been written yet. */
    val needsMemo get() = purpose == TripPurpose.BUSINESS && memo.isBlank()

    fun toJson() = JSONObject().apply {
        put("id", id); put("start", startMs); putOpt("end", endMs)
        putOpt("so", startOdometerKm?.toDouble()); putOpt("eo", endOdometerKm?.toDouble())
        putOpt("sb", startBattery); putOpt("eb", endBattery)
        put("max", maxSpeedKmh.toDouble()); put("sd", speedDistanceKm.toDouble())
        putOpt("tms", tripMeterStartKm?.toDouble()); putOpt("tme", tripMeterEndKm?.toDouble())
        put("ld", locationDistanceKm.toDouble())
        put("idle", idleSeconds); put("stops", stopCount)
        put("events", JSONArray(events.map { it.toJson() }))
        put("purpose", purpose.name); put("svc", duringServiceVisit)
        if (track.isNotEmpty()) put("trk", JSONArray(track.map { it.toJson() }))
        if (dwells.isNotEmpty()) put("dw", JSONArray(dwells.map { it.toJson() }))
        if (memo.isNotEmpty()) put("memo", memo)
        if (driver.isNotEmpty()) put("drv", driver)
        if (edits.isNotEmpty()) put("ed", JSONArray(edits.map { it.toJson() }))
        if (demo) put("demo", true)
        if (startOdometerLate) put("sol", true)
    }

    companion object {
        const val MIN_MEASURED_KM = 0.05f
        const val ODOMETER_TOLERANCE_KM = 1.0f
        const val MISMATCH_MIN_KM = 0.2f
        const val MISMATCH_RATIO = 0.10f

        fun fromJson(o: JSONObject) = Trip(
            id = o.getLong("id"),
            startMs = o.getLong("start"),
            endMs = o.optLongOrNull("end"),
            startOdometerKm = o.optDoubleOrNull("so")?.toFloat(),
            endOdometerKm = o.optDoubleOrNull("eo")?.toFloat(),
            startBattery = o.optIntOrNull("sb"),
            endBattery = o.optIntOrNull("eb"),
            maxSpeedKmh = o.optDouble("max", 0.0).toFloat(),
            speedDistanceKm = o.optDouble("sd", 0.0).toFloat(),
            tripMeterStartKm = o.optDoubleOrNull("tms")?.toFloat(),
            tripMeterEndKm = o.optDoubleOrNull("tme")?.toFloat(),
            locationDistanceKm = o.optDouble("ld", 0.0).toFloat(),
            idleSeconds = o.optLong("idle", 0),
            stopCount = o.optInt("stops", 0),
            events = o.optJSONArray("events").objects().map(DriveEvent::fromJson),
            purpose = runCatching { TripPurpose.valueOf(o.optString("purpose")) }.getOrDefault(TripPurpose.UNSET),
            duringServiceVisit = o.optBoolean("svc", false),
            startOdometerLate = o.optBoolean("sol", false),
            track = o.optJSONArray("trk").objects().map(TrackPoint::fromJson),
            dwells = o.optJSONArray("dw").objects().map(Dwell::fromJson),
            memo = o.optString("memo", ""),
            driver = o.optString("drv", ""),
            edits = o.optJSONArray("ed").objects().mapNotNull(TripEdit::fromJson),
            demo = o.optBoolean("demo", false),
        )
    }
}

/** Where a trip's distance came from, most trusted first. */
enum class DistanceSource(val label: String) {
    ODOMETER("계기판"),
    TRIP_METER("차량 트립 미터"),
    LOCATION("위치 기반"),
    SPEED("차속 계산"),
}

/** A maintenance-related signal changed state (e.g. brake fluid became low). */
@Immutable
data class SafetyEvent(
    val timeMs: Long,
    val type: Type,
    val active: Boolean, // true = warning started, false = warning cleared
    val odometerKm: Float?,
    val code: String? = null, // TELLTALE: which lamp (Telltale code)
) {
    enum class Type(val label: String) {
        BRAKE_FLUID_LOW("브레이크액 부족"),
        WASHER_FLUID_LOW("워셔액 부족"),
        POWER_DOWN_WARNING("전원 차단 경고"),
        AIRBAG_DEPLOYED("에어백 전개"),
        TELLTALE("경고등"),
    }

    /** Same key for the same warning, so "on again after repair" can be matched. */
    val key: String get() = code?.let { Warnings.TELLTALE_PREFIX + it } ?: type.name
    val label: String get() = code?.let { Telltale.label(it) } ?: type.label

    fun toJson() = JSONObject().apply {
        put("t", timeMs); put("type", type.name); put("on", active); putOpt("odo", odometerKm?.toDouble())
        putOpt("code", code)
    }

    companion object {
        fun fromJson(o: JSONObject) = SafetyEvent(
            o.getLong("t"), Type.valueOf(o.getString("type")), o.getBoolean("on"),
            o.optDoubleOrNull("odo")?.toFloat(),
            if (o.has("code") && !o.isNull("code")) o.getString("code") else null,
        )
    }
}

/** Vehicle state captured when the car is handed to / picked up from a repair shop. */
@Immutable
data class StateCapture(
    val timeMs: Long,
    val odometerKm: Float?,
    val batteryPercent: Int?,
    val activeWarnings: List<String>, // Warnings keys
) {
    fun toJson() = JSONObject().apply {
        put("t", timeMs); putOpt("odo", odometerKm?.toDouble()); putOpt("bat", batteryPercent)
        put("warn", JSONArray(activeWarnings))
    }

    companion object {
        fun fromJson(o: JSONObject) = StateCapture(
            o.getLong("t"), o.optDoubleOrNull("odo")?.toFloat(), o.optIntOrNull("bat"),
            o.optJSONArray("warn").strings()
        )
    }
}

@Immutable
data class ServiceVisit(
    val id: Long,
    val dropOff: StateCapture,
    val pickUp: StateCapture? = null,
) {
    val isOpen get() = pickUp == null
    val odometerDiffKm: Float?
        get() {
            val a = dropOff.odometerKm ?: return null
            val b = pickUp?.odometerKm ?: return null
            return b - a
        }

    fun toJson() = JSONObject().apply {
        put("id", id); put("drop", dropOff.toJson()); putOpt("pick", pickUp?.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = ServiceVisit(
            o.getLong("id"),
            StateCapture.fromJson(o.getJSONObject("drop")),
            o.optJSONObject("pick")?.let(StateCapture::fromJson),
        )
    }
}

/** Header fields of the NTS business-car logbook form (entered once by the user). */
@Immutable
data class VehicleProfile(
    val companyName: String = "",
    val businessNumber: String = "",
    val carModel: String = "",
    val plateNumber: String = "",
    val userDepartment: String = "",
    val userName: String = "",
    val fiscalStartMonth: Int = 1,
) {
    /** ④ 사용자 for trips that do not name someone else: "부서 성명". */
    val defaultUser get() = listOf(userDepartment, userName).filter { it.isNotBlank() }.joinToString(" ")

    fun toJson() = JSONObject().apply {
        put("company", companyName); put("bizNo", businessNumber); put("model", carModel)
        put("plate", plateNumber); put("dept", userDepartment); put("user", userName)
        put("fiscal", fiscalStartMonth)
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) VehicleProfile() else VehicleProfile(
            companyName = o.optString("company"),
            businessNumber = o.optString("bizNo"),
            carModel = o.optString("model"),
            plateNumber = o.optString("plate"),
            userDepartment = o.optString("dept"),
            userName = o.optString("user"),
            fiscalStartMonth = o.optInt("fiscal", 1).coerceIn(1, 12),
        )
    }
}

data class ServiceDrive(val km: Float, val trips: Int, val source: String) {
    val text get() = "%.1f km · 운행 %d건 (%s)".format(km, trips, source)
}

/** Everything that is saved on the device. */
@Immutable
data class Logbook(
    val trips: List<Trip> = emptyList(),
    val safetyEvents: List<SafetyEvent> = emptyList(),
    val visits: List<ServiceVisit> = emptyList(),
    val profile: VehicleProfile = VehicleProfile(),
    val gaps: List<MissedGap> = emptyList(),
    val lastOdometerKm: Float? = null, // last odometer value accounted for (trip end or check)
    val lastOdometerMs: Long? = null,
) {
    val openTrip get() = trips.lastOrNull()?.takeIf { it.isOpen }
    val closedTrips get() = trips.filter { !it.isOpen }
    val openVisit get() = visits.lastOrNull()?.takeIf { it.isOpen }

    /**
     * Driving while the car was at the service shop: the odometer difference when it moved,
     * otherwise the recorded trips of that period (e.g. a car whose odometer signal is missing).
     */
    fun serviceDrive(v: ServiceVisit): ServiceDrive {
        // Trips are flagged when they start during a visit; the next visit's drop-off bounds this one
        // (robust even if the vehicle clock and the phone-style capture clock differ slightly).
        val next = visits.map { it.dropOff.timeMs }.filter { it > v.dropOff.timeMs }.minOrNull() ?: Long.MAX_VALUE
        val trips = closedTrips.filter { it.duringServiceVisit && it.startMs >= v.dropOff.timeMs && it.startMs < next }
        val odo = v.odometerDiffKm
        return if (odo != null && odo >= Trip.MIN_MEASURED_KM) ServiceDrive(odo, trips.size, "계기판")
        else ServiceDrive(trips.sumOf { it.distanceKm.toDouble() }.toFloat(), trips.size,
            trips.mapNotNull { it.distanceSource?.label }.distinct().joinToString(", ").ifEmpty { "측정 없음" })
    }

    fun toJson() = JSONObject().apply {
        put("version", 1)
        put("trips", JSONArray(trips.map { it.toJson() }))
        put("safety", JSONArray(safetyEvents.map { it.toJson() }))
        put("visits", JSONArray(visits.map { it.toJson() }))
        put("profile", profile.toJson())
        put("gaps", JSONArray(gaps.map { it.toJson() }))
        putOpt("lastOdo", lastOdometerKm?.toDouble()); putOpt("lastOdoT", lastOdometerMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = Logbook(
            trips = o.optJSONArray("trips").objects().map(Trip::fromJson),
            safetyEvents = o.optJSONArray("safety").objects().map(SafetyEvent::fromJson),
            visits = o.optJSONArray("visits").objects().map(ServiceVisit::fromJson),
            profile = VehicleProfile.fromJson(o.optJSONObject("profile")),
            gaps = o.optJSONArray("gaps").objects().map(MissedGap::fromJson),
            lastOdometerKm = o.optDoubleOrNull("lastOdo")?.toFloat(),
            lastOdometerMs = o.optLongOrNull("lastOdoT"),
        )
    }
}

// ---------- small org.json helpers ----------

internal fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
internal fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
internal fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }
