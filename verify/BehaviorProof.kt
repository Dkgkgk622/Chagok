package com.chagok.app

import java.util.Calendar

/** In-memory storage so the recorder runs on a plain JVM. */
class MemoryStorage : LogbookStorage {
    var saved = Logbook(); var saves = 0
    override fun load() = saved
    override fun save(logbook: Logbook) { saved = logbook; saves++ }
}

var passed = 0; var failed = 0
val failures = mutableListOf<String>()
fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) { passed++; println("  ✔ $name") } else { failed++; failures += name; println("  ✘ $name  ${detail()}") }
}

class Drive(val r: TripRecorder, val t0: Long) {
    var odo: Float? = 1000f; var meter: Float? = 0f; var soc: Int? = 80
    var gear = Gear.P; var speed = 0f
    fun at(sec: Double, gear: Gear = this.gear, speed: Float = this.speed,
           odo: Float? = this.odo, meter: Float? = this.meter,
           brakeFluidLow: Boolean? = null, telltales: Set<String>? = null) {
        this.gear = gear; this.speed = speed; this.odo = odo; this.meter = meter
        r.onSnapshot(VehicleSnapshot(
            timeMs = t0 + (sec * 1000).toLong(), speedKmh = speed, gear = gear,
            odometerKm = odo, tripMeterKm = meter, batteryPercent = soc, brakeFluidLow = brakeFluidLow,
            telltales = telltales,
        ))
    }
}

fun newDrive(): Pair<Drive, MemoryStorage> {
    val store = MemoryStorage()
    val r = TripRecorder(store)
    val d = Drive(r, System.currentTimeMillis() - 600_000)
    d.at(0.0, Gear.P, 0f)
    return d to store
}

fun main() {
    println("\n[1] 트립 시작·종료")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f); d.at(5.0, speed = 30f); d.at(61.0, speed = 0f); d.at(62.0, Gear.P, 0f)
        val trips = s.saved.closedTrips
        check("D에서 시작, P에서 종료 → 트립 1건", trips.size == 1) { "trips=${trips.size}" }
        check("운행 시간 61초", trips.firstOrNull()?.durationMs == 61_000L) { "${trips.firstOrNull()?.durationMs}" }
        check("열린 트립이 남지 않음", s.saved.openTrip == null)
    }
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.R, 0f); d.at(4.0, Gear.P, 0f)
        check("3초 R→P 같은 짧은 조작은 트립으로 남기지 않음", s.saved.trips.isEmpty()) { "${s.saved.trips.size}" }
    }

    println("\n[2] 급출발·급가속 (초당 11km/h 기준)")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f)
        d.at(2.0, speed = 6f); d.at(2.5, speed = 12f); d.at(3.0, speed = 18f)   // 12 km/h/s from standstill
        d.at(20.0, speed = 50f); d.at(30.0, speed = 50.5f)
        d.at(31.0, speed = 56f); d.at(31.5, speed = 62f); d.at(32.0, speed = 68f) // 12 km/h/s while moving
        d.at(60.0, speed = 0f); d.at(61.0, Gear.P, 0f)
        val t = s.saved.closedTrips.single()
        check("정지 상태에서 12km/h/s → 급출발 1건", t.count(DriveEvent.Type.QUICK_START) == 1) { "${t.events}" }
        check("주행 중 12km/h/s → 급가속 1건", t.count(DriveEvent.Type.HARSH_ACCEL) == 1) { "${t.events}" }
    }
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f)
        for (i in 1..10) d.at(1.0 + i, speed = 5f * i)   // 5 km/h/s: gentle
        d.at(30.0, speed = 0f); d.at(31.0, Gear.P, 0f)
        val t = s.saved.closedTrips.single()
        check("완만한 가속(5km/h/s)은 이벤트 없음", t.count(DriveEvent.Type.QUICK_START) + t.count(DriveEvent.Type.HARSH_ACCEL) == 0) { "${t.events}" }
    }

    println("\n[3] 급정지·급감속 (초당 7.5km/h 기준)")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f); d.at(10.0, speed = 30f)
        d.at(20.0, speed = 25f); d.at(20.5, speed = 18f); d.at(21.0, speed = 10f); d.at(21.5, speed = 3f); d.at(22.0, speed = 0f)
        d.at(40.0, speed = 60f)
        d.at(50.0, speed = 55f); d.at(50.5, speed = 50f); d.at(51.0, speed = 45f); d.at(51.5, speed = 40f)  // 10 km/h/s, keeps moving
        d.at(70.0, speed = 40.5f)
        d.at(90.0, speed = 0f); d.at(91.0, Gear.P, 0f)
        val t = s.saved.closedTrips.single()
        check("감속하며 3초 안에 정지 → 급정지 1건", t.count(DriveEvent.Type.SUDDEN_STOP) == 1) { "${t.events}" }
        check("감속 후 계속 주행 → 급감속 1건", t.count(DriveEvent.Type.HARSH_BRAKE) == 1) { "${t.events}" }
    }

    println("\n[4] 과속 (제한속도 +20km/h, 구간당 1건)")
    run {
        val (d, s) = newDrive()
        d.r.speedLimitKmh = 60
        d.at(1.0, Gear.D, 0f); d.at(10.0, speed = 70f)
        d.at(20.0, speed = 85f)
        d.r.onLocation("37.50000,127.00000")
        d.at(25.0, speed = 95f); d.at(30.0, speed = 78f); d.at(35.0, speed = 76f)
        d.at(40.0, speed = 90f)
        d.at(60.0, speed = 0f); d.at(61.0, Gear.P, 0f)
        val evs = s.saved.closedTrips.single().events.filter { it.type == DriveEvent.Type.OVERSPEED }
        check("과속 구간 2개 → 기록 2건", evs.size == 2) { "${evs.size}" }
        check("1구간 최고 속도 95km/h로 갱신", evs.getOrNull(0)?.speedKmh == 95f) { "${evs.getOrNull(0)}" }
        check("제한속도 60 저장", evs.all { it.limitKmh == 60 })
        check("요청한 위치가 과속 기록에 붙음", evs.getOrNull(0)?.location == "37.50000,127.00000") { "${evs.getOrNull(0)?.location}" }
        check("제한속도 +20 미만(70km/h)은 과속 아님", evs.none { it.timeMs == d.t0 + 10_000 })
    }

    println("\n[5] 주행거리 출처 우선순위와 차이 표시")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 1000f, meter = 2f); d.at(2.0, speed = 60f)
        d.at(302.0, speed = 0f, odo = 1005f, meter = 7.1f); d.at(303.0, Gear.P, 0f)   // 5 km in 5 min at 60 km/h
        val t = s.saved.closedTrips.single()
        check("계기판이 움직이면 계기판 기준", t.distanceSource == DistanceSource.ODOMETER) { "${t.distanceSource}" }
        check("거리 5.0km", kotlin.math.abs(t.distanceKm - 5f) < 0.01f) { "${t.distanceKm}" }
        check("트립미터 5.1km·차속 5.0km와 차이 없음", !t.distanceMismatch) { "${t.distanceCandidates}" }
    }
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 1000f, meter = 0f); d.at(2.0, speed = 36f)
        d.at(302.0, speed = 0f, odo = 1000f, meter = 3f); d.at(303.0, Gear.P, 0f)
        val t = s.saved.closedTrips.single()
        check("계기판 그대로면 차량 트립 미터 기준", t.distanceSource == DistanceSource.TRIP_METER && t.distanceKm == 3f) { "${t.distanceSource} ${t.distanceKm}" }
    }
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 1000f, meter = 0f); d.at(2.0, speed = 60f)
        d.at(62.0, speed = 0f); d.at(63.0, Gear.P, 0f)
        val t = s.saved.closedTrips.single()
        check("계기판·트립미터 모두 그대로면 차속 계산(60km/h×60초=1km)", t.distanceSource == DistanceSource.SPEED && kotlin.math.abs(t.distanceKm - 1f) < 0.01f) { "${t.distanceSource} ${t.distanceKm}" }
    }
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 1000f, meter = 0f); d.at(2.0, speed = 60f)
        d.at(62.0, speed = 0f, odo = 1002f); d.at(63.0, Gear.P, 0f)   // odometer 2 km vs speed 1 km (possible at 60km/h+tolerance)
        val t = s.saved.closedTrips.single()
        check("계기판 2km vs 차속 1km → 출처 간 차이 표시", t.distanceMismatch && t.distanceSource == DistanceSource.ODOMETER) { "${t.distanceCandidates}" }
    }

    println("\n[6] 정지 횟수·정차 대기 시간")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f); d.at(5.0, speed = 30f); d.at(20.0, speed = 0f)
        d.at(40.0, speed = 20f); d.at(60.0, speed = 0f); d.at(62.0, Gear.P, 0f)
        val t = s.saved.closedTrips.single()
        check("주행→정지 2번 → 정지 2회", t.stopCount == 2) { "${t.stopCount}" }
        check("출발 전 4초는 5초 미만이라 제외, 20초 정차만 집계", t.idleSeconds == 20L) { "${t.idleSeconds}" }
    }

    println("\n[7] 정비 신호 기록 (중복 없이 상태 변화만)")
    run {
        val (d, s) = newDrive()
        d.at(1.0, brakeFluidLow = false); d.at(2.0, brakeFluidLow = true); d.at(3.0, brakeFluidLow = true); d.at(4.0, brakeFluidLow = false)
        val evs = s.saved.safetyEvents
        check("정상→부족→부족→정상 → 기록 2건(발생, 해소)", evs.size == 2 && evs[0].active && !evs[1].active) { "$evs" }
    }

    println("\n[8] 세무용 운행기록부 (국세청 고시 항목, 계기판 값만)")
    run {
        val now = System.currentTimeMillis()
        fun trip(id: Long, so: Float?, eo: Float?, p: TripPurpose, sd: Float = 0f) =
            Trip(id = id, startMs = now - id * 3_600_000, endMs = now - id * 3_600_000 + 600_000,
                 startOdometerKm = so, endOdometerKm = eo, purpose = p, speedDistanceKm = sd)
        val book = Logbook(
            trips = listOf(
                trip(5, 100f, 110f, TripPurpose.COMMUTE),
                trip(4, 110f, 130f, TripPurpose.BUSINESS),
                trip(3, 130f, 135f, TripPurpose.PERSONAL),
                trip(2, null, null, TripPurpose.BUSINESS, sd = 8f),
                trip(1, 135f, 135f, TripPurpose.BUSINESS, sd = 4f),
            ),
            profile = VehicleProfile(companyName = "(주)차곡", userDepartment = "영업팀", userName = "홍길동"),
        )
        val r = ReportBuilder.build(ReportType.TAX, book, VehicleSnapshot())
        val rows = r.taxRows
        check("운행 5건 → 행 5개", rows.size == 5) { "${rows.size}" }
        check("열 8개 (③~⑩)", rows.all { it.cells().size == 8 })
        check("사용일자에 요일 표시", rows.all { it.date.contains("(") && it.date.endsWith(")") }) { rows.first().date }
        check("출퇴근 10km는 ⑧칸, 업무 20km는 ⑨칸", rows[0].commute == "10.0" && rows[0].business == "" && rows[1].business == "20.0") { "${rows[0]} ${rows[1]}" }
        check("계기판 없는 운행은 거리 빈칸 + '계기판 값 없음' (차속 8km 사용 안 함)", rows[3].distance == "" && rows[3].note.startsWith("계기판 값 없음")) { "${rows[3]}" }
        check("계기판 그대로·다른 측정값 있음 → 비고 표시", rows[4].note.startsWith("계기판 변화 없음")) { "${rows[4]}" }
        val ratioRow = r.sections[1].rows.first { it.first.startsWith("⑬") }.second
        check("업무사용비율 = (10+20)/35 = 85.7%", ratioRow == "85.7 %") { ratioRow }
        check("사용자에 부서·성명", rows.all { it.user == "영업팀 홍길동" })
        val cal = Calendar.getInstance()
        check("기간이 사업연도(1월~)", r.period.contains("${cal.get(Calendar.YEAR)}.01.01")) { r.period }
    }

    println("\n[9] 주간 보고서 구성")
    run {
        val now = System.currentTimeMillis()
        val t = Trip(id = 1, startMs = now - 3_600_000, endMs = now - 3_000_000, startOdometerKm = 0f, endOdometerKm = 12f,
            events = listOf(DriveEvent(now - 3_300_000, DriveEvent.Type.OVERSPEED, 5f, 101f, 80, "37.1,127.1")))
        val r = ReportBuilder.build(ReportType.WEEKLY, Logbook(trips = listOf(t)), VehicleSnapshot())
        val titles = r.sections.map { it.title }
        check("주행거리 측정 근거 섹션 포함", titles.any { it.startsWith("주행거리 측정 근거") }) { "$titles" }
        val os = r.sections.first { it.title.startsWith("과속 기록") }.rows.first()
        check("과속 기록에 일시·최고/제한속도 (좌표 숫자는 넣지 않음)", !os.first.contains("37.1") && os.second == "최고 101 / 제한 80 km/h") { "$os" }
    }

    println("\n[10] 계기판 값 급변 (에뮬레이터 실측: 주행 중 0 → 121545km로 설정)")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 0f, meter = null)
        d.at(10.0, speed = 3f, odo = 121545f); d.at(11.0, speed = 21f); d.at(12.0, speed = 43f)
        d.at(13.0, speed = 64f); d.at(14.0, speed = 75f); d.at(21.0, speed = 68f); d.at(22.0, speed = 38f)
        d.at(23.0, speed = 0f); d.at(70.0, Gear.P, 0f)
        val t = s.saved.closedTrips.single()
        check("1분에 121545km는 불가능 → 계기판 제외", t.odometerImplausible && t.odometerDistanceKm == null) { "${t.odometerRawDistanceKm}" }
        check("거리는 다음 출처(차속 계산)로", t.distanceSource == DistanceSource.SPEED && t.distanceKm < 1f) { "${t.distanceSource} ${t.distanceKm}" }
        val row = ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot()).taxRows.single()
        check("세무용: 거리 빈칸 + 비고 '계기판 값 급변'", row.distance == "" && row.note.startsWith("계기판 값 급변")) { "$row" }
    }
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 1000f); d.at(2.0, speed = 30f)
        d.at(62.0, speed = 0f, odo = 1001f); d.at(63.0, Gear.P, 0f)   // 1 min at 30 km/h = 0.5 km, odometer counts whole km
        val t = s.saved.closedTrips.single()
        check("정수 km 계기판의 1km 증가는 허용", !t.odometerImplausible && t.distanceSource == DistanceSource.ODOMETER) { "${t.odometerRawDistanceKm}" }
    }
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 1000f); d.at(2.0, speed = 30f)
        d.at(62.0, speed = 0f, odo = 990f); d.at(63.0, Gear.P, 0f)
        check("계기판이 줄어들면 제외", s.saved.closedTrips.single().odometerImplausible)
    }
    run {
        check("시간 표시 71초 → '1분 11초'", ReportBuilder.duration(71_000) == "1분 11초") { ReportBuilder.duration(71_000) }
    }

    println("\n[11] 급가속·급감속은 구간당 1건 (에뮬레이터 실측 2026-10-30 재현)")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f)
        val samples = listOf(1.855 to 0f, 3.334 to 1f, 4.444 to 21f, 5.553 to 40f, 6.56 to 58f, 7.667 to 78f,
            8.704 to 99f, 9.721 to 116f, 10.763 to 133f, 11.765 to 151f, 12.808 to 168f, 13.912 to 189f,
            14.952 to 202f, 16.035 to 155f, 17.169 to 108f, 18.186 to 62f, 19.265 to 32f, 20.354 to 0f)
        samples.forEach { (t, v) -> d.at(t, speed = v) }
        d.at(21.997, Gear.P, 0f)
        val t = s.saved.closedTrips.single()
        val byType = t.events.groupingBy { it.type }.eachCount()
        check("0→202km/h 한 번의 가속 → 급출발 1건, 급가속 0건", byType[DriveEvent.Type.QUICK_START] == 1 && byType[DriveEvent.Type.HARSH_ACCEL] == null) { "$byType" }
        check("202→0km/h 한 번의 감속 → 급정지 1건, 급감속 0건", byType[DriveEvent.Type.SUDDEN_STOP] == 1 && byType[DriveEvent.Type.HARSH_BRAKE] == null) { "$byType" }
        val start = t.events.first { it.type == DriveEvent.Type.QUICK_START }
        check("가속 구간 최고 가속도 기록(18km/h/s 이상)", (start.peakRate ?: 0f) >= 18f) { "${start.peakRate}" }
        check("가속 구간 길이 기록(10초 이상)", start.endMs != null && start.endMs!! - start.timeMs >= 10_000) { "${start.endMs?.minus(start.timeMs)}" }
    }
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f)
        listOf(5f, 10f, 15f, 20f, 25f, 30f).forEachIndexed { i, v -> d.at(2.0 + i, speed = v) }  // gentle start, 5 km/h/s
        d.at(8.0, speed = 45f)                                                    // hard accel #1 (15 km/h/s)
        (9..18).forEach { d.at(it.toDouble(), speed = 45f) }                      // cruise, reported every second
        d.at(19.0, speed = 60f)                                                   // hard accel #2
        (20..28).forEach { d.at(it.toDouble(), speed = 60f) }
        d.at(80.0, speed = 0f); d.at(81.0, Gear.P, 0f)
        val n = s.saved.closedTrips.single().events.count { it.type == DriveEvent.Type.HARSH_ACCEL }
        check("사이에 정속 구간이 있으면 별도 2건", n == 2) { "$n" }
    }

    println("\n[12] 운행 폐기 규칙 (D로 서 있기만 한 운행)")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f); (2..90).forEach { d.at(it.toDouble(), speed = 0f) }; d.at(91.0, Gear.P, 0f)
        check("D로 90초 서 있기만 함 → 운행 남기지 않음", s.saved.closedTrips.isEmpty()) { "${s.saved.closedTrips.size}" }
    }
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f); d.at(2.0, speed = 20f); d.at(3.0, speed = 40f); d.at(60.0, speed = 0f)
        (61..140).forEach { d.at(it.toDouble(), speed = 0f) }          // 80 s stop during the trip
        d.at(141.0, speed = 30f); d.at(200.0, speed = 0f); d.at(201.0, Gear.P, 0f)
        val t = s.saved.closedTrips.singleOrNull()
        check("운행 중 80초 정차 → 운행 유지 + 1분 이상 정차 1곳", t != null && t.dwells.size == 1 && t.dwells[0].seconds in 79..81) { "${t?.dwells}" }
    }

    println("\n[13] 경로 기록 (위치·이벤트 위치·정차 위치)")
    run {
        val store = MemoryStorage()
        val r = TripRecorder(store)
        var asked = 0
        r.requestLocation = { asked++ }
        r.speedLimitKmh = 50
        val d = Drive(r, System.currentTimeMillis() - 600_000)
        d.at(0.0, Gear.P, 0f)
        d.at(1.0, Gear.D, 0f); r.onLocation("37.40000,127.10000")
        d.at(2.0, speed = 5f); d.at(3.0, speed = 20f)                    // quick start
        r.onLocation("37.40010,127.10010")
        (4..20).forEach { d.at(it.toDouble(), speed = 40f) }
        r.onLocation("37.40100,127.10100")
        d.at(21.0, speed = 30f); d.at(22.0, speed = 10f); d.at(23.0, speed = 0f)   // sudden stop
        r.onLocation("37.40200,127.10200")
        (24..100).forEach { d.at(it.toDouble(), speed = 0f) }           // 77 s stop
        d.at(101.0, speed = 20f); r.onLocation("37.40300,127.10300")
        d.at(160.0, speed = 0f); d.at(161.0, Gear.P, 0f)
        val t = store.saved.closedTrips.single()
        check("위치 5개 → 경로 점 5개, 속도·제한속도 함께", t.track.size == 5 && t.track[2].speedKmh == 40f && t.track.all { it.limitKmh == 50 }) { "${t.track}" }
        val quick = t.events.first { it.type == DriveEvent.Type.QUICK_START }
        check("급출발에 위치·속도·제한속도", quick.location == "37.40010,127.10010" && quick.speedKmh == 20f && quick.limitKmh == 50) { "$quick" }
        val stop = t.events.first { it.type == DriveEvent.Type.SUDDEN_STOP }
        check("급정지에 위치", stop.location == "37.40200,127.10200") { "$stop" }
        check("1분 이상 정차 위치", t.dwells.singleOrNull()?.location == "37.40200,127.10200") { "${t.dwells}" }
        check("출발·이벤트·정차 때 위치를 요청함", asked >= 4) { "$asked" }
        val marks = RouteModel.marks(t)
        // events: quick start, sudden stop, and the second quick start after the stop (0→20 km/h in 1 s)
        check("지도 표시: 이벤트 3 + 정차 1 + 경로 점 5", marks.count { it.kind == RouteMark.Kind.EVENT } == 3 &&
            marks.count { it.kind == RouteMark.Kind.DWELL } == 1 &&
            marks.count { it.kind == RouteMark.Kind.POINT || it.kind == RouteMark.Kind.MINUTE } == 5) { "$marks" }
        val proj = RouteModel.Projection(marks, 1000f, 600f, 40f)
        val ev = marks.first { it.kind == RouteMark.Kind.EVENT && it.event?.type == DriveEvent.Type.QUICK_START }
        val tapped = RouteModel.nearest(marks, proj, proj.x(ev.lon) + 5f, proj.y(ev.lat) - 5f, 64f)
        check("이벤트 근처를 누르면 그 이벤트 선택 (경로 점보다 우선)", tapped?.event?.type == DriveEvent.Type.QUICK_START) { "$tapped" }
        check("투영 결과가 화면 안", marks.all { proj.x(it.lon) in 0f..1000f && proj.y(it.lat) in 0f..600f })
        val sampled = RouteCodec.sample(t.track, 60)
        check("1분 점 추출: 첫·끝 포함", sampled.first() == t.track.first() && sampled.last() == t.track.last()) { "$sampled" }
    }

    println("\n[14] 놓친 운행 추정 (보고서에는 안내만)")
    run {
        val (d, s) = newDrive()   // odometer 1000
        d.at(1.0, Gear.D, 0f, odo = 1000f); d.at(2.0, speed = 30f); d.at(62.0, speed = 0f, odo = 1000.5f); d.at(63.0, Gear.P, 0f)
        check("운행 끝 계기판 저장", s.saved.lastOdometerKm == 1000.5f) { "${s.saved.lastOdometerKm}" }
        d.at(3600.0, Gear.P, 0f, odo = 1012.5f)    // parked later, odometer moved 12 km
        val g = s.saved.gaps.singleOrNull()
        check("주차 중 계기판 12km 증가 → 추정 공백 1건", g != null && kotlin.math.abs(g.km - 12f) < 0.01f) { "${s.saved.gaps}" }
        d.at(3601.0, Gear.P, 0f, odo = 1012.5f)
        check("같은 값이면 중복 없음", s.saved.gaps.size == 1)
        d.at(3700.0, Gear.P, 0f, odo = 1012.55f)
        check("0.1km 미만 차이는 무시", s.saved.gaps.size == 1)
        val tax = ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot())
        check("세무용: 추정 거리는 표에 없음 (운행 1행만)", tax.taxRows.size == 1) { "${tax.taxRows.size}" }
        check("세무용: '직접 기재' 안내", tax.notes.any { it.contains("기록되지 않은 운행") && it.contains("직접 기재") }) { "${tax.notes}" }
        check("세무용: 안내에 추정 km 숫자 없음", tax.notes.none { it.contains("12.0") || it.contains("12 km") })
        val weekly = ReportBuilder.build(ReportType.WEEKLY, s.saved, VehicleSnapshot())
        check("주간: 총 주행거리에 추정 거리 미포함", weekly.sections.first().rows.first { it.first == "총 주행거리" }.second.startsWith("0.5")) { "${weekly.sections.first().rows}" }
    }
    run {
        val now = System.currentTimeMillis()
        val book = Logbook(trips = listOf(Trip(id = 1, startMs = now - 3_600_000, endMs = now - 3_000_000, startOdometerKm = null, endOdometerKm = null, speedDistanceKm = 5f, maxSpeedKmh = 50f)))
        val tax = ReportBuilder.build(ReportType.TAX, book, VehicleSnapshot())
        check("계기판 없는 운행 → 직접 기재 안내 (합계 제외 명시)", tax.notes.any { it.startsWith("직접 기재 필요") && it.contains("빈 운행 1건") && it.contains("합계에 들어가 있지 않습니다") }) { "${tax.notes}" }
    }

    println("\n[15] 경로 보고서 '운행' 묶음에 운행 시각")
    run {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 30, 8, 0, 0) }
        val s0 = cal.timeInMillis
        val same = ReportBuilder.route(Trip(id = 1, startMs = s0, endMs = s0 + 30 * 60_000))
        val rows = same.sections.first().rows
        check("운행 시각 → 운행 목적 → 거리 → 시간 순서", rows.take(4).map { it.first } == listOf("운행 시각", "운행 목적", "거리", "시간")) { "$rows" }
        check("같은 날: '2026.10.30(금) 08:00 ~ 08:30'", rows[0].second == "2026.10.30(금) 08:00 ~ 08:30") { rows[0].second }
        cal.set(2026, Calendar.OCTOBER, 30, 23, 50, 0)
        val night = ReportBuilder.route(Trip(id = 2, startMs = cal.timeInMillis, endMs = cal.timeInMillis + 30 * 60_000))
        check("자정 넘김: 두 날짜 모두 표시", night.sections.first().rows[0].second == "2026.10.30(금) 23:50 ~ 2026.10.31(토) 00:20") { night.sections.first().rows[0].second }
    }

    println("\n[16] 업무 운행 방문 목적")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 1000f); d.at(2.0, speed = 30f); d.at(62.0, speed = 0f, odo = 1000.5f); d.at(63.0, Gear.P, 0f)
        val id = s.saved.closedTrips.single().id
        d.r.setPurpose(id, TripPurpose.BUSINESS)
        check("업무인데 방문 목적 없음 → 표시 대상", s.saved.closedTrips.single().needsMemo)
        var row = ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot()).taxRows.single()
        check("세무용 비고 '방문 목적 미기재'", row.note.contains("방문 목적 미기재")) { row.note }
        d.r.setMemo(id, "한빛상사 납품 미팅")
        row = ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot()).taxRows.single()
        check("방문 목적 → 세무용 ⑩비고", row.note == "한빛상사 납품 미팅" && row.business == "0.5") { "$row" }
        check("적으면 표시 대상에서 빠짐", !s.saved.closedTrips.single().needsMemo)
        val tax = ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot())
        check("세무용 집계 '방문 목적 미기재 업무 운행 0건'", tax.sections[1].rows.any { it.first == "방문 목적 미기재 업무 운행" && it.second == "0건" })
        d.r.setMemo(id, "가".repeat(100))
        check("방문 목적 60자 제한", s.saved.closedTrips.single().memo.length == TripRecorder.MEMO_MAX)
    }

    println("\n[17] 시연용 운행 (지도 테스트용)")
    run {
        val store = MemoryStorage(); val r = TripRecorder(store)
        r.addDemoTrip(System.currentTimeMillis())
        val t = store.saved.closedTrips.single()
        check("시연용 표시", t.demo)
        check("경로 점 37개(15초 간격, 9분)", t.track.size == 37 && t.track.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs == 15_000L })
        check("판교 일대 좌표", t.track.all { it.lat in 37.38..37.42 && it.lon in 127.09..127.13 })
        check("이벤트 5건 모두 위치 있음", t.events.size == 5 && t.events.all { it.location != null })
        check("1분 이상 정차 1곳(105초)", t.dwells.singleOrNull()?.seconds == 105L)
        check("거리: 계기판 기준, 차속 계산과 일치", t.distanceSource == DistanceSource.ODOMETER && !t.distanceMismatch && t.distanceKm in 3f..6f) { "${t.distanceKm} ${t.distanceCandidates}" }
        check("과속 이벤트: 88km/h, 제한 60", t.events.any { it.type == DriveEvent.Type.OVERSPEED && it.speedKmh == 88f && it.limitKmh == 60 })
        val marks = RouteModel.marks(t)
        check("지도 표시 대상 43개(점 37+정차 1+이벤트 5)", marks.size == 43) { "${marks.size}" }
        val rep = ReportBuilder.route(t)
        check("운행 보고서 안내에 '시연용'", rep.notes.first().startsWith("시연용"))
        val weekly = ReportBuilder.build(ReportType.WEEKLY, store.saved, VehicleSnapshot())
        check("주간 보고서에 시연용 포함 안내", weekly.notes.any { it.startsWith("시연용(가상) 운행 1건") }) { "${weekly.notes}" }
        val taxRow = ReportBuilder.build(ReportType.TAX, store.saved, VehicleSnapshot()).taxRows.single()
        check("세무용 비고에 '시연용 데이터'", taxRow.note.contains("시연용 데이터")) { taxRow.note }
        check("운행 중에는 만들지 않음", run {
            val s2 = MemoryStorage(); val r2 = TripRecorder(s2)
            r2.onSnapshot(VehicleSnapshot(timeMs = 1000, gear = Gear.D, speedKmh = 0f))
            r2.addDemoTrip(System.currentTimeMillis()); s2.saved.trips.size == 1 })
    }

    println("\n[18] 같은 자리의 정차(고리)와 이벤트(점) 고르기")
    run {
        val t = DemoTrip.build(System.currentTimeMillis(), 1000f)
        val marks = RouteModel.marks(t)
        val proj = RouteModel.Projection(marks, 1000f, 600f, 40f)
        val dw = marks.first { it.kind == RouteMark.Kind.DWELL }
        val cx = proj.x(dw.lon); val cy = proj.y(dw.lat)
        val center = RouteModel.nearest(marks, proj, cx, cy, 64f)
        check("가운데를 누르면 같은 자리의 급정지", center?.event?.type == DriveEvent.Type.SUDDEN_STOP) { "$center" }
        val ring = RouteModel.nearest(marks, proj, cx + RouteModel.DWELL_RING_PX, cy, 64f)
        check("고리를 누르면 1분 이상 정차", ring?.kind == RouteMark.Kind.DWELL) { "$ring" }
    }

    println("\n[19] 내부 버그 점검 (11/3)")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f); d.at(2.0, speed = 30f)
        d.r.onLocation("0.00000,0.00000"); d.r.onLocation("위치 없음"); d.r.onLocation("37.40000,127.10000")
        d.at(62.0, speed = 0f); d.at(63.0, Gear.P, 0f)
        val t = s.saved.closedTrips.single()
        check("(0,0)·읽을 수 없는 위치는 경로에 안 들어감", t.track.size == 1 && t.track[0].lat == 37.4) { "${t.track}" }
    }
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 1000f); d.at(2.0, speed = 30f); d.at(62.0, speed = 0f, odo = 1000.5f); d.at(63.0, Gear.P, 0f)
        // gear signal lost: the car drives while we still see P, odometer creeping up
        var odo = 1000.5f
        (1..40).forEach { i -> odo += 0.1f; d.at(100.0 + i * 15, Gear.P, 0f, odo = odo) }
        val g = s.saved.gaps
        check("기어 신호를 놓친 채 4km 주행 → 추정 공백 1건으로 합침", g.size == 1 && kotlin.math.abs(g[0].km - 4f) < 0.05f) { "${g.size} ${g.map { it.km }}" }
        d.at(100.0 + 41 * 15 + 3_600, Gear.P, 0f, odo = odo + 2f)  // much later, another unrecorded drive
        check("30분 넘게 떨어진 공백은 따로", s.saved.gaps.size == 2) { "${s.saved.gaps.size}" }
        d.at(20_000.0, Gear.P, 0f, odo = 9_999_999f)
        check("터무니없는 계기판 점프는 공백이 아니라 초기화로", s.saved.gaps.size == 2 && s.saved.lastOdometerKm == 9_999_999f)
    }
    run {
        // process restart in the middle of a trip: same trip continues, stops still counted
        val store = MemoryStorage()
        val t0 = System.currentTimeMillis() - 600_000
        val r1 = TripRecorder(store)
        r1.onSnapshot(VehicleSnapshot(t0, speedKmh = 0f, gear = Gear.P, odometerKm = 1000f))
        r1.onSnapshot(VehicleSnapshot(t0 + 1_000, speedKmh = 0f, gear = Gear.D, odometerKm = 1000f))
        r1.onSnapshot(VehicleSnapshot(t0 + 2_000, speedKmh = 40f, gear = Gear.D, odometerKm = 1000f))
        val r2 = TripRecorder(store)   // process killed and started again
        r2.onSnapshot(VehicleSnapshot(t0 + 30_000, speedKmh = 40f, gear = Gear.D, odometerKm = 1000.3f))
        r2.onSnapshot(VehicleSnapshot(t0 + 60_000, speedKmh = 0f, gear = Gear.D, odometerKm = 1000.6f))
        r2.onSnapshot(VehicleSnapshot(t0 + 61_000, speedKmh = 0f, gear = Gear.P, odometerKm = 1000.6f))
        val trips = store.saved.closedTrips
        check("재시작 후에도 운행 1건으로 이어짐", trips.size == 1 && trips[0].startMs == t0 + 1_000) { "${trips.size}" }
        check("재시작 후 정지도 셈", trips.single().stopCount == 1) { "${trips.single().stopCount}" }
        check("거리는 계기판 0.6km", kotlin.math.abs(trips.single().distanceKm - 0.6f) < 0.01f) { "${trips.single().distanceKm}" }
    }

    println("\n[20] 운행기록부 직접 기재·운행별 사용자·정비 중 주행 (11/4)")
    run {
        // emulator case: odometer never moves, speed says 0.5 km
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 0f); d.at(2.0, speed = 30f); d.at(62.0, speed = 0f, odo = 0f); d.at(63.0, Gear.P, 0f)
        val id = s.saved.closedTrips.single().id
        d.r.setPurpose(id, TripPurpose.BUSINESS)
        val row = ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot()).taxRows.single()
        check("계기판 그대로·차속 0.5km → ⑦⑨ 빈칸(0.0 아님) + 직접 기재 표시", row.needsManual && row.distance == "" && row.business == "" && row.note.startsWith("계기판 변화 없음 → 직접 기재")) { "$row" }
        val tax = ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot())
        check("세무용 집계에 '직접 기재가 필요한 운행 1건'", tax.sections[1].rows.any { it.first.startsWith("직접 기재가 필요한 운행") && it.second == "1건" })
        d.r.updateProfile(VehicleProfile(userDepartment = "영업팀", userName = "홍길동"))
        d.r.setDriver(id, "  기술팀 김철수 ")
        val row2 = ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot()).taxRows.single()
        check("운행별 사용자 → ④칸 (앞뒤 공백 제거)", row2.user == "기술팀 김철수" && row2.tripId == id) { row2.user }
        d.r.setDriver(id, "")
        check("비우면 기본 사용자로", ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot()).taxRows.single().user == "영업팀 홍길동")
    }
    run {
        // service: drop off, drive 0.5 km by speed (odometer frozen), pick up
        val store = MemoryStorage(); val r = TripRecorder(store)
        val now = System.currentTimeMillis()
        r.onSnapshot(VehicleSnapshot(now, speedKmh = 0f, gear = Gear.P, odometerKm = 0f))
        r.dropOffForService(VehicleSnapshot(now, odometerKm = 0f))
        val t0 = System.currentTimeMillis() + 1_000
        r.onSnapshot(VehicleSnapshot(t0, speedKmh = 0f, gear = Gear.D, odometerKm = 0f))
        r.onSnapshot(VehicleSnapshot(t0 + 1_000, speedKmh = 30f, gear = Gear.D, odometerKm = 0f))
        r.onSnapshot(VehicleSnapshot(t0 + 61_000, speedKmh = 0f, gear = Gear.D, odometerKm = 0f))
        r.onSnapshot(VehicleSnapshot(t0 + 62_000, speedKmh = 0f, gear = Gear.P, odometerKm = 0f))
        Thread.sleep(5)
        r.pickUpFromService(VehicleSnapshot(t0 + 70_000, odometerKm = 0f))
        val v = store.saved.visits.single()
        val drive = store.saved.serviceDrive(v)
        check("계기판이 그대로여도 정비 중 주행 0.5km(운행 1건, 차속 계산)", drive.trips == 1 && kotlin.math.abs(drive.km - 0.5f) < 0.01f && drive.source == "차속 계산") { drive.text }
        check("정비 중 운행 표시", store.saved.closedTrips.single().duringServiceVisit)
    }

    println("\n[21] 수정 이력 (사람이 입력한 값의 시각)")
    run {
        val (d, s) = newDrive()
        d.at(1.0, Gear.D, 0f, odo = 1000f); d.at(2.0, speed = 30f); d.at(62.0, speed = 0f, odo = 1000.5f); d.at(63.0, Gear.P, 0f)
        val t = s.saved.closedTrips.single(); val end = t.endMs!!
        val DAY = 24 * 3_600_000L
        var now = end + 60_000
        d.r.clock = { now }
        d.r.setPurpose(t.id, TripPurpose.BUSINESS)
        d.r.setPurpose(t.id, TripPurpose.BUSINESS)          // same value: not logged
        d.r.setMemo(t.id, "거래처 방문")
        var tt = s.saved.closedTrips.single()
        check("같은 값 다시 누르면 이력 안 남음 (목적 1 + 방문 목적 1)", tt.edits.size == 2) { "${tt.edits}" }
        check("운행 직후 입력·수정 없음 → 비고 표시 없음", tt.auditNote() == "") { tt.auditNote() }
        now = end + 3 * DAY
        d.r.setPurpose(t.id, TripPurpose.PERSONAL)
        d.r.setPurpose(t.id, TripPurpose.BUSINESS)
        tt = s.saved.closedTrips.single()
        check("목적을 사흘 뒤 두 번 수정 → '운행 목적 수정 2회'", tt.auditNote() == "운행 목적 수정 2회") { tt.auditNote() }
        now = end + 5 * DAY
        d.r.setDriver(t.id, "기술팀 김철수")
        tt = s.saved.closedTrips.single()
        check("사용자를 닷새 뒤 처음 입력 → '사용자 운행 5일 뒤 입력'", tt.auditNote().endsWith("사용자 운행 5일 뒤 입력")) { tt.auditNote() }
        val row = ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot()).taxRows.single()
        check("세무용 ⑩비고에 이력 표시", row.note.contains("운행 목적 수정 2회") && row.note.contains("사용자 운행 5일 뒤 입력")) { row.note }
        val rep = ReportBuilder.route(tt)
        val hist = rep.sections.first { it.title.startsWith("입력 이력") }.rows
        check("운행 보고서 '입력 이력'에 5건 + 표시 요약", hist.size == 6 && hist.last().first == "운행기록부 표시") { "$hist" }
    }

    println("\n[22] 기본 사용자 변경·차량에서 가져온 등록번호")
    run {
        val (d, s) = newDrive()
        d.r.updateProfile(VehicleProfile(userDepartment = "영업팀", userName = "홍길동"))
        d.at(1.0, Gear.D, 0f, odo = 1000f); d.at(2.0, speed = 30f); d.at(62.0, speed = 0f, odo = 1000.5f); d.at(63.0, Gear.P, 0f)
        val first = s.saved.closedTrips.single().id
        d.r.updateProfile(s.saved.profile.copy(userName = "김영희"))
        d.at(100.0, Gear.D, 0f, odo = 1000.5f); d.at(101.0, speed = 30f); d.at(161.0, speed = 0f, odo = 1001f); d.at(162.0, Gear.P, 0f)
        val rows = ReportBuilder.build(ReportType.TAX, s.saved, VehicleSnapshot()).taxRows
        check("기본 사용자 변경 → 이전 운행은 '영업팀 홍길동' 유지", rows.first { it.tripId == first }.user == "영업팀 홍길동") { "${rows.map { it.user }}" }
        check("이후 운행은 새 기본 사용자 '영업팀 김영희'", rows.first { it.tripId != first }.user == "영업팀 김영희") { "${rows.map { it.user }}" }
        d.r.fillPlateFromCar("12가3456")
        check("등록번호가 비어 있으면 차량 값으로 채움", s.saved.profile.plateNumber == "12가3456")
        d.r.fillPlateFromCar("99나9999")
        check("사용자가 입력한(이미 있는) 등록번호는 덮어쓰지 않음", s.saved.profile.plateNumber == "12가3456")
    }

    println("\n[23] 휴대폰 지도: 출발·도착 주변 가리기")
    run {
        val t0 = 1_793_300_000_000L
        val start = 37.40000 to 127.10000
        // Straight north, about 33 m per point, 2 km in all.
        val track = (0..60).map { i -> TrackPoint(t0 + i * 15_000L, 37.40000 + i * 0.0003, 127.10000, 40f, 50) }
        val end = track.last().let { it.lat to it.lon }
        val ev = listOf(
            DriveEvent(t0 + 45_000, DriveEvent.Type.QUICK_START, 1f, 20f, null, "37.40090,127.10000"),   // ~100 m from start
            DriveEvent(t0 + 450_000, DriveEvent.Type.HARSH_BRAKE, 1f, 40f, null, "37.40900,127.10000"),  // middle
            DriveEvent(t0 + 880_000, DriveEvent.Type.OVERSPEED, 1f, 90f, 50, "37.41750,127.10000"),     // ~50 m from end
        )
        val dw = listOf(Dwell(t0 + 300_000, t0 + 400_000, "37.40600,127.10000"))
        val trip = Trip(id = 1, startMs = t0, endMs = t0 + 900_000, track = track, events = ev, dwells = dw)
        val r = RouteCodec.PHONE_HIDE_RADIUS_M
        val ph = RouteCodec.phoneRoute(trip, 60, r)
        val all = ph.points.map { it.lat to it.lon } + ph.events.map { it.second } + ph.dwells.map { it.second }
        check("남은 점·표시가 모두 출발·도착에서 ${r}m 밖",
            all.all { RouteCodec.distanceM(it, start) >= r && RouteCodec.distanceM(it, end) >= r }) {
            "${all.map { "%.0f/%.0f".format(RouteCodec.distanceM(it, start), RouteCodec.distanceM(it, end)) }}" }
        check("가운데 경로는 남음", ph.points.size >= 5) { "${ph.points.size}" }
        check("출발 근처 급출발·도착 근처 과속은 빠지고 가운데 급감속은 남음",
            ph.events.map { it.first.type } == listOf(DriveEvent.Type.HARSH_BRAKE)) { "${ph.events.map { it.first.type }}" }
        check("가운데 1분 정차는 남음", ph.dwells.size == 1)
        check("기준점(b)도 가린 범위 밖", ph.base!!.let { RouteCodec.distanceM(it, start) >= r })
        check("가린 개수 = 경로 점 18 + 이벤트 2", ph.hiddenCount == 20) { "${ph.hiddenCount}" }
        val full = RouteCodec.phoneRoute(trip, 60, 0)
        check("가리기 0이면 기존과 같음(기준점 = 출발점)", full.base == start && full.events.size == 3 && full.hiddenCount == 0)
        check("차 화면 지도는 전체 표시", RouteModel.marks(trip).size == track.size + dw.size + ev.size)

        val short = Trip(id = 2, startMs = t0, endMs = t0 + 60_000,
            track = (0..4).map { i -> TrackPoint(t0 + i * 15_000L, 37.40000 + i * 0.0003, 127.10000, 20f, null) })
        val js = RouteCodec.encode(short, 60, r).toString()
        check("짧은 운행: 전부 가려지면 기준점 없이 반경만 보냄", js.contains("\"h\":$r") && !js.contains("\"b\"")) { js }
        val plain = ReportBuilder.route(trip)
        check("기본(꺼짐): 휴대폰 QR에 전체 경로, 반경 없음",
            !plain.toJson().toString().contains("\"h\"") && plain.notes.none { it.contains("반경") })
        val rep = ReportBuilder.route(trip, hideEnds = true)
        check("켜면: 운행 보고서 QR 데이터에 반경 포함", rep.toJson().toString().contains("\"h\":$r"))
        check("켜면: 안내문에 가리기 설명", rep.notes.any { it.contains("반경 ${r}m") })
    }

    println("\n[24] 경고등(Telltales) 기록")
    run {
        check("고장·정비 경고등만 기록 대상 (ABS O, 방향지시등·문 열림 X)",
            Telltale.accept("ABS", false) && !Telltale.accept("TURN_SIGNAL_LEFT", false) && !Telltale.accept("DOOR_OPEN", false))
        check("ESC 깜빡임은 정상 작동이라 제외, 계속 켜짐만 기록", !Telltale.accept("ESC", true) && Telltale.accept("ESC", false))

        val (d, s) = newDrive()
        // 시동 때 경고등 점검: 여러 개가 2초 켜졌다 꺼짐 → 기록 없음
        d.at(1.0, telltales = setOf("ABS", "AIRBAG", "ENGINE")); d.at(3.0, telltales = emptySet())
        check("시동 때 2초 점등(경고등 점검)은 기록하지 않음", s.saved.safetyEvents.isEmpty()) { "${s.saved.safetyEvents}" }

        d.at(10.0, odo = 1200f, telltales = setOf("ABS"))
        d.at(12.0, telltales = setOf("ABS"))
        check("켜진 지 2초: 아직 기록 안 함", s.saved.safetyEvents.isEmpty())
        d.at(21.0, odo = 1201f, telltales = setOf("ABS"))
        var evs = s.saved.safetyEvents
        check("10초 넘게 켜져 있으면 처음 켜진 시각·주행거리로 1건 기록",
            evs.size == 1 && evs[0].code == "ABS" && evs[0].active && evs[0].timeMs == d.t0 + 10_000 && evs[0].odometerKm == 1200f) { "$evs" }
        check("이름 표시", evs[0].label == "ABS 경고등")

        d.at(22.0, telltales = setOf("ABS", "TPMS"))
        d.at(22.5) // 경고등 값이 없는 스냅샷(null)은 '꺼짐'으로 보지 않음
        d.at(40.0, telltales = setOf("ABS", "TPMS"))
        evs = s.saved.safetyEvents
        check("두 번째 경고등도 켜진 시각(22초)으로 기록, ABS 중복 없음",
            evs.size == 2 && evs[1].code == "TPMS" && evs[1].timeMs == d.t0 + 22_000) { "$evs" }
        check("지금 켜진 경고에 경고등 포함", d.r.activeWarnings() == listOf("TT_ABS", "TT_TPMS")) { "${d.r.activeWarnings()}" }

        // 앱이 다시 시작돼도 같은 경고등을 두 번 기록하지 않고, 목록에서 빠진 것은 꺼짐
        val r2 = TripRecorder(s)
        fun snap(sec: Double, set: Set<String>?) = r2.onSnapshot(VehicleSnapshot(d.t0 + (sec * 1000).toLong(), gear = Gear.P, speedKmh = 0f, odometerKm = 1201f, telltales = set))
        snap(50.0, setOf("ABS"))
        evs = s.saved.safetyEvents
        check("재시작 후: ABS 중복 없음, TPMS 꺼짐 1건", evs.size == 3 && evs.last().code == "TPMS" && !evs.last().active) { "$evs" }

        // 켜진 뒤 다른 신호가 없다가 35초 뒤 꺼짐만 들어와도 켜짐+꺼짐 둘 다 남김
        snap(60.0, setOf("ABS", "ESC")); snap(95.0, setOf("ABS"))
        evs = s.saved.safetyEvents.filter { it.code == "ESC" }
        check("중간 신호 없이 35초 켜졌다 꺼짐 → 켜짐(60초)·꺼짐(95초)", evs.size == 2 && evs[0].active && evs[0].timeMs == d.t0 + 60_000 && !evs[1].active) { "$evs" }
        snap(100.0, setOf("ABS", "MDPS")); snap(105.0, setOf("ABS"))
        check("5초만 켜졌다 꺼진 경고등은 기록 안 함", s.saved.safetyEvents.none { it.code == "MDPS" })
        check("기록은 시간 순서", s.saved.safetyEvents.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
        check("예전 형식 경고 이름도 그대로 표시", Warnings.label("BRAKE_FLUID_LOW") == "브레이크액 부족" && Warnings.label("TT_ABS") == "ABS 경고등")

        r2.clearAll()
        snap(200.0, setOf("ABS")); snap(215.0, setOf("ABS"))
        evs = s.saved.safetyEvents
        check("기록 전체 삭제 뒤에도 켜져 있는 경고등은 새로 기록", evs.size == 1 && evs[0].code == "ABS" && evs[0].timeMs == d.t0 + 200_000) { "$evs" }
    }

    println("\n[25] 정비 전후 경고등 비교 (다시 발생 / 처음 발생)")
    run {
        val (d, s) = newDrive()
        d.at(10.0, odo = 5000f, telltales = setOf("ABS")); d.at(25.0, telltales = setOf("ABS"))   // 맡기기 전부터 ABS
        d.r.dropOffForService(VehicleSnapshot(odometerKm = 5000f))
        check("맡길 때 경고에 ABS 경고등", Warnings.labels(s.saved.visits.single().dropOff.activeWarnings) == "ABS 경고등") {
            "${s.saved.visits.single().dropOff.activeWarnings}" }
        d.at(30.0, odo = 5001f, telltales = emptySet())                                           // 수리로 꺼짐
        d.r.pickUpFromService(VehicleSnapshot(odometerKm = 5001f))
        d.at(1_000.0, odo = 5121f, telltales = setOf("ABS")); d.at(1_015.0, telltales = setOf("ABS"))  // 120 km 뒤 다시
        d.at(1_100.0, odo = 5130f, telltales = setOf("ABS", "TPMS")); d.at(1_115.0, telltales = setOf("ABS", "TPMS"))
        d.at(1_200.0, telltales = setOf("TPMS"))                                                  // ABS 꺼졌다가
        d.at(1_300.0, odo = 5300f, telltales = setOf("ABS", "TPMS")); d.at(1_315.0, telltales = setOf("ABS", "TPMS")) // 또 켜짐
        val rep = ReportBuilder.build(ReportType.SERVICE, s.saved, VehicleSnapshot(telltales = setOf("ABS", "TPMS")))
        val last = rep.sections.first { it.title.startsWith("직전 정비") }.rows
        check("ABS: 수리 후 120 km 만에 다시 발생", last.any { it.first == "ABS 경고등" && it.second.contains("120") && it.second.endsWith("다시 발생") }) { "$last" }
        check("같은 경고가 또 켜져도 수리 후 첫 발생 1줄만", last.count { it.first == "ABS 경고등" } == 1) { "$last" }
        check("TPMS: 수리 전엔 없던 경고 → 처음 발생", last.any { it.first == "타이어 공기압 경고등" && it.second.endsWith("처음 발생") }) { "$last" }
        check("찾을 때 경고는 없음", last.any { it.first == "찾을 때 경고" && it.second == "없음" }) { "$last" }
        val now = rep.sections.first { it.title == "현재 차량 상태" }.rows.first { it.first == "켜진 경고등" }.second
        check("현재 상태에 켜진 경고등 이름", now == "ABS 경고등, 타이어 공기압 경고등") { now }
        val unknown = ReportBuilder.build(ReportType.SERVICE, s.saved, VehicleSnapshot()).sections.first().rows.first { it.first == "켜진 경고등" }.second
        check("경고등 값을 못 받으면 '확인 불가'", unknown == "확인 불가") { unknown }
        val buyer = ReportBuilder.build(ReportType.BUYER, s.saved, VehicleSnapshot()).sections.flatMap { it.rows }.first { it.first == "켜진 적 있는 경고등" }.second
        check("구매자용: 켜진 적 있는 경고등과 횟수", buyer == "ABS 경고등 3회, 타이어 공기압 경고등 1회") { buyer }
    }

    println("\n[26] 자동차등록번호 (차량 값 검사)")
    run {
        check("에뮬레이터 값 \"invalid\"는 버림", CarPlate.normalize("\"invalid\"") == null && CarPlate.normalize("invalid") == null)
        check("빈 값·null은 버림", CarPlate.normalize("  ") == null && CarPlate.normalize(null) == null)
        check("따옴표·공백을 걷어낸 번호판은 사용", CarPlate.normalize(" \"12가3456\" ") == "12가3456" &&
            CarPlate.normalize("123가 4567") == "123가 4567" && CarPlate.normalize("서울12가3456") == "서울12가3456")
        val (d, s) = newDrive()
        d.r.fillPlateFromCar("\"invalid\"")
        check("잘못된 값은 기본정보에 채우지 않음", s.saved.profile.plateNumber.isEmpty()) { s.saved.profile.plateNumber }
        d.r.fillPlateFromCar("\"12가3456\"")
        check("정상 번호는 따옴표 없이 채움", s.saved.profile.plateNumber == "12가3456") { s.saved.profile.plateNumber }
        val bad = MemoryStorage().apply { saved = Logbook(profile = VehicleProfile(plateNumber = "\"invalid\"")) }
        TripRecorder(bad)
        check("예전 버전이 채운 \"invalid\"는 앱 시작 때 지움", bad.saved.profile.plateNumber.isEmpty()) { bad.saved.profile.plateNumber }
        val mine = MemoryStorage().apply { saved = Logbook(profile = VehicleProfile(plateNumber = "34나5678")) }
        TripRecorder(mine)
        check("사용자가 입력한 번호는 그대로", mine.saved.profile.plateNumber == "34나5678")
    }

    println("\n[27] 경고 기록 전체 목록 (에뮬레이터 실측 로그 재현)")
    run {
        val (d, s) = newDrive()
        // 2026-11-05 에뮬레이터 Logcat 순서 그대로 (초 = 07:26:00 기준)
        fun lamp(sec: Double, set: Set<String>?) = d.at(sec, telltales = set)
        lamp(59.4, emptySet()); lamp(61.3, null)
        lamp(103.0, setOf("AIRBAG")); lamp(109.2, setOf("TPMS", "AIRBAG")); lamp(111.5, null)
        lamp(111.7, setOf("H2_SENSOR", "TPMS", "AIRBAG")); lamp(113.6, setOf("H2_SENSOR", "TPMS", "AIRBAG"))
        lamp(117.6, setOf("H2_SENSOR", "TPMS", "AIRBAG")); lamp(121.6, null)
        lamp(122.4, setOf("ENGINE_OIL_PRESSURE", "H2_SENSOR", "TPMS", "AIRBAG")); lamp(131.7, null); lamp(141.7, null)
        lamp(145.7, setOf("H2_SENSOR", "TPMS", "AIRBAG")); lamp(147.3, setOf("ABS", "H2_SENSOR", "TPMS", "AIRBAG"))
        lamp(149.7, setOf("TPMS", "ABS", "H2_SENSOR", "AIRBAG")); lamp(151.7, null); lamp(161.8, null)
        lamp(165.9, setOf("ABS", "H2_SENSOR", "AIRBAG")); lamp(170.2, setOf("H2_SENSOR", "AIRBAG")); lamp(171.9, null)
        lamp(174.2, setOf("H2_SENSOR")); lamp(181.9, null); lamp(182.6, emptySet())
        val eps = warningEpisodes(s.saved.safetyEvents)
        check("실측 순서: 경고등 5종 모두 기록 (에어백·타이어·수소 센서·엔진 오일 압력·ABS)",
            eps.map { it.key.removePrefix("TT_") }.toSet() == setOf("AIRBAG", "TPMS", "H2_SENSOR", "ENGINE_OIL_PRESSURE", "ABS")) { "$eps" }
        val airbag = eps.single { it.key == "TT_AIRBAG" }
        check("에어백 경고등: 103초에 켜져 174초에 꺼짐 (71초)", airbag.startMs == d.t0 + 103_000 && airbag.endMs == d.t0 + 174_200) { "$airbag" }
        check("최신 순서", eps.zipWithNext().all { (a, b) -> a.startMs >= b.startMs })

        val rep = ReportBuilder.build(ReportType.SERVICE, s.saved, VehicleSnapshot(telltales = emptySet()))
        val sec = rep.sections.first { it.title.startsWith("경고 기록") }
        check("정비소용: '경고 기록 (전체 5건)'", sec.title == "경고 기록 (전체 5건)" && sec.rows.size == 5) { "${sec.title} ${sec.rows}" }
        check("정비소용: 에어백 경고등 1분 11초 동안", sec.rows.any { it.first.endsWith("에어백 경고등") && it.second.endsWith("1분 11초 동안") }) { "${sec.rows}" }

        lamp(200.0, setOf("ABS")); lamp(215.0, setOf("ABS"))
        val still = warningEpisodes(s.saved.safetyEvents).first()
        check("아직 켜진 경고는 '지금도 켜짐'", still.endMs == null &&
            ReportBuilder.build(ReportType.SERVICE, s.saved, VehicleSnapshot()).sections.first { it.title.startsWith("경고 기록") }
                .rows.first().second.endsWith("지금도 켜짐"))

        // 휴대폰 QR: 긴 목록은 최신 30건 + '이전 n건'
        val many = (0 until 35).flatMap { i ->
            listOf(SafetyEvent(d.t0 + i * 60_000L, SafetyEvent.Type.TELLTALE, true, 1f, "ABS"),
                SafetyEvent(d.t0 + i * 60_000L + 20_000, SafetyEvent.Type.TELLTALE, false, 1f, "ABS"))
        }
        val big = ReportBuilder.build(ReportType.SERVICE, Logbook(safetyEvents = many), VehicleSnapshot())
        val carRows = big.sections.first { it.title.startsWith("경고 기록") }.rows
        val phoneRows = big.phoneCopy().sections.first { it.title.startsWith("경고 기록") }.rows
        check("차 화면은 35건 전부", carRows.size == 35)
        check("휴대폰은 최신 30건 + '이전 5건'", phoneRows.size == 31 && phoneRows.last().first == "이전 5건" &&
            phoneRows.first() == carRows.first()) { "${phoneRows.size} ${phoneRows.last()}" }
    }

    println("\n[28] 지도 값 정확성: 과속 위치·오래된 위치")
    run {
        // 과속은 최고 속도가 난 곳에 표시: 최고 속도 뒤 첫 위치를 쓰고, 그 뒤 위치로 덮어쓰지 않음
        val (d, _) = newDrive()
        d.r.speedLimitKmh = 60
        d.at(1.0, Gear.D, 0f)
        for (i in 1..16) d.at(1.0 + i, speed = i * 5f)           // 초당 5 km/h씩 80까지 (급가속 아님)
        d.r.onLocation("37.40000,127.10000")                      // 과속 시작 직후 위치
        d.at(18.0, speed = 85f); d.at(19.0, speed = 90f)          // 최고 90 (2초 지나 위치 다시 요청)
        d.r.onLocation("37.40500,127.10500")                      // 최고 속도 지점
        d.at(21.0, speed = 88f)
        d.r.onLocation("37.41000,127.11000")                      // 그 뒤 위치 (덮어쓰면 안 됨)
        d.at(23.0, speed = 80f); d.at(25.0, speed = 76f); d.at(27.0, speed = 70f)  // 제한+20-3 아래로 → 과속 끝
        val o = d.r.logbook.value.openTrip!!.events.single { it.type == DriveEvent.Type.OVERSPEED }
        check("과속 표시 위치 = 최고 속도 지점", o.location == "37.40500,127.10500") { "${o.location}" }
        check("최고 속도 90, 그때 제한 60", o.speedKmh == 90f && o.limitKmh == 60) { "$o" }
        check("과속 시작 시각과 끝 시각(76km/h < 60+20-3 에서 끝)", o.timeMs == d.t0 + 17_000 && o.endMs == d.t0 + 25_000) { "${o.timeMs - d.t0} ${o.endMs?.minus(d.t0)}" }
    }
    run {
        // 운행 시작 때 위치만 있고 16초 뒤 과속 시작 → 16초 전 위치를 쓰지 않음
        val (d, _) = newDrive()
        d.r.speedLimitKmh = 60
        d.at(1.0, Gear.D, 0f); d.r.onLocation("37.30000,127.00000")
        for (i in 1..16) d.at(1.0 + i, speed = i * 5f)
        var o = d.r.logbook.value.openTrip!!.events.single { it.type == DriveEvent.Type.OVERSPEED }
        check("오래된 위치(16초 전)는 과속 위치로 쓰지 않음", o.location == null) { "${o.location}" }
        d.r.onLocation("37.30400,127.00600")
        o = d.r.logbook.value.openTrip!!.events.single { it.type == DriveEvent.Type.OVERSPEED }
        check("새 위치가 오면 그 위치", o.location == "37.30400,127.00600") { "${o.location}" }
    }
    run {
        // 1분 이상 정차: 멈춘 뒤 위치 응답이 없으면, 1분 전 위치 대신 '위치 없음'
        fun stopWith(lastLocAt: Double): String? {
            val (d, _) = newDrive()
            d.at(1.0, Gear.D, 0f)
            for (i in 1..6) d.at(1.0 + i, speed = i * 5f)
            var t = 7.0
            while (t < lastLocAt) { t += 1.0; d.at(t, speed = 30f) }
            d.r.onLocation("37.35000,127.05000")
            for (i in 1..10) d.at(t + i, speed = 30f - i * 3f)     // 10초에 걸쳐 정지
            d.at(t + 80.0, speed = 0f); d.at(t + 81.0, speed = 10f)  // 70초 정차 후 출발
            return d.r.logbook.value.openTrip!!.dwells.singleOrNull()?.location
        }
        check("멈추기 70초 전 위치는 정차 위치로 쓰지 않음", run {
            // 마지막 위치 뒤 60초 더 달린 다음 멈춤
            val (d, _) = newDrive()
            d.at(1.0, Gear.D, 0f); for (i in 1..6) d.at(1.0 + i, speed = i * 5f)
            d.r.onLocation("37.35000,127.05000")
            var t = 7.0; repeat(60) { t += 1.0; d.at(t, speed = 30f) }
            for (i in 1..10) d.at(t + i, speed = 30f - i * 3f)
            d.at(t + 80.0, speed = 0f); d.at(t + 81.0, speed = 10f)
            d.r.logbook.value.openTrip!!.dwells.single().location == null
        })
        check("멈추기 10초 전 위치(감속 중)는 정차 위치로 사용", stopWith(10.0) == "37.35000,127.05000")
    }

    println("\n[29] 운행기록부 숫자 일관성 (무작위 300건)")
    run {
        fun t10(v: String): Long? = v.takeIf { it.isNotBlank() }?.let { Math.round(it.toDouble() * 10) }
        val rnd = java.util.Random(7)
        val now = System.currentTimeMillis()
        var odo = 12_345.67f
        val purposes = listOf(TripPurpose.BUSINESS, TripPurpose.COMMUTE, TripPurpose.PERSONAL, TripPurpose.UNSET)
        val trips = (0 until 300).map { i ->
            val start = odo
            val km = 0.3f + rnd.nextFloat() * 40f            // 소수점 아래가 제각각인 계기판 값
            if (i % 37 == 0) odo += 3.3f                       // 가끔 기록 안 된 운행
            odo = start + km
            val st = now - (300 - i) * 3_600_000L
            Trip(id = st, startMs = st, endMs = st + 1_800_000, startOdometerKm = start + (if (i % 37 == 0) 0f else 0f),
                endOdometerKm = odo, maxSpeedKmh = 90f, speedDistanceKm = km, purpose = purposes[i % 4], memo = if (i % 4 == 0) "거래처 방문" else "")
        }.let { list -> // 기록 안 된 운행: 다음 운행의 출발 값을 3.3 km 뒤로
            var extra = 0f
            list.mapIndexed { i, t -> if (i % 37 == 1 && i > 1) { extra = 3.3f }; t.copy(startOdometerKm = t.startOdometerKm!! + extra, endOdometerKm = t.endOdometerKm!! + extra) }
        }
        val prof = VehicleProfile(fiscalStartMonth = 1)
        val book = Logbook(trips = trips, profile = prof)
        val tax = ReportBuilder.build(ReportType.TAX, book, VehicleSnapshot(), now)
        val rows = tax.taxRows
        check("모든 행: ⑤ + ⑦ = ⑥ (따로 반올림해서 0.1 어긋나는 행 없음)",
            rows.filter { !it.needsManual }.all { t10(it.startOdo)!! + t10(it.distance)!! == t10(it.endOdo)!! }) {
            rows.first { !it.needsManual && t10(it.startOdo)!! + t10(it.distance)!! != t10(it.endOdo)!! }.toString() }
        check("⑧·⑨는 ⑦과 같은 값 (목적에 따라 한쪽만)", rows.all { r ->
            (r.commute.isEmpty() || r.commute == r.distance) && (r.business.isEmpty() || r.business == r.distance) && !(r.commute.isNotEmpty() && r.business.isNotEmpty()) })
        val sums = tax.sections.first { it.title.startsWith("업무용 사용비율") }.rows.toMap()
        val colTotal = rows.sumOf { t10(it.distance) ?: 0L }
        val colWork = rows.sumOf { (t10(it.commute) ?: 0L) + (t10(it.business) ?: 0L) }
        check("⑦ 열을 더하면 ⑪과 정확히 같음", t10(sums.getValue("⑪ 사업연도 총주행 거리").substringBefore(" km")) == colTotal) { "${sums["⑪ 사업연도 총주행 거리"]} vs $colTotal" }
        check("⑧+⑨ 열을 더하면 ⑫와 정확히 같음", t10(sums.getValue("⑫ 사업연도 업무용 사용거리").substringBefore(" km")) == colWork) { "${sums["⑫ 사업연도 업무용 사용거리"]} vs $colWork" }
        val ratio = sums.getValue("⑬ 업무사용비율 (⑫/⑪)").substringBefore(" %").toDouble()
        check("⑬ = ⑫/⑪ (표에 보이는 값으로 다시 계산해도 같음)", kotlin.math.abs(ratio - colWork * 100.0 / colTotal) < 0.05) { "$ratio" }
        val jumps = rows.filter { it.note.contains("앞 운행 뒤 계기판 +3.3 km") }
        check("앞 운행 끝과 다음 출발 사이 3.3 km 차이 → ⑩에 표시", jumps.isNotEmpty() && rows.count { it.note.contains("앞 운행 뒤") } == jumps.size) { "${rows.count { it.note.contains("앞 운행 뒤") }} ${jumps.size}" }

        val old = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)  // 소수점이 쉼표인 언어 설정
        val de = ReportBuilder.build(ReportType.TAX, book, VehicleSnapshot(), now)
        java.util.Locale.setDefault(old)
        check("기기 언어가 달라도 숫자 형식 같음 (1234,5 같은 쉼표 없음)", de.taxRows == tax.taxRows &&
            de.sections.flatMap { it.rows }.none { it.second.matches(Regex(".*\\d,\\d.*")) }) { "${de.sections}" }
    }

    println("\n[30] 운행기록부: 믿을 수 있는 값만 칸에 적기")
    run {
        val now = System.currentTimeMillis()
        fun trip(i: Int, so: Float?, eo: Float?, moved: Float = 10f, late: Boolean = false) = Trip(id = i.toLong(), startMs = now - (10 - i) * 3_600_000L,
            endMs = now - (10 - i) * 3_600_000L + 1_800_000, startOdometerKm = so, endOdometerKm = eo, speedDistanceKm = moved, maxSpeedKmh = 80f,
            purpose = TripPurpose.BUSINESS, memo = "방문", startOdometerLate = late)
        val book = Logbook(trips = listOf(
            trip(1, 1000.0f, 1010.0f),
            trip(2, 1010.0f, 1010.0f),            // 계기판이 안 움직임 (다른 출처는 10 km)
            trip(3, 1020.0f, 9020.0f),            // 30분에 8000 km: 불가능
            trip(4, 1030.0f, 1040.0f, late = true), // 출발 계기판을 늦게 받음
            trip(5, 1040.0f, null),               // 도착 계기판 없음
            trip(6, 1050.0f, 1060.0f),
        ))
        val r = ReportBuilder.build(ReportType.TAX, book, VehicleSnapshot(), now).taxRows
        check("정상 행: ⑤⑥⑦ 모두", r[0].startOdo == "1000.0" && r[0].endOdo == "1010.0" && r[0].distance == "10.0" && !r[0].needsManual)
        check("계기판 변화 없음: ⑤만, ⑥⑦ 빈칸", r[1].startOdo == "1010.0" && r[1].endOdo == "" && r[1].distance == "" && r[1].needsManual) { "${r[1]}" }
        check("값 급변: ⑤⑥⑦ 모두 빈칸 (어느 값이 틀렸는지 모름)", r[2].startOdo == "" && r[2].endOdo == "" && r[2].distance == "" && r[2].note.startsWith("계기판 값 급변")) { "${r[2]}" }
        check("출발 값 늦게 받음: ⑤ 빈칸, ⑥ 표시", r[3].startOdo == "" && r[3].endOdo == "1040.0" && r[3].distance == "" && r[3].note.startsWith("출발 계기판 값 확인 못함")) { "${r[3]}" }
        check("도착 값 없음: ⑥ 빈칸 (출발 값을 복사해 넣지 않음)", r[4].endOdo == "" && r[4].distance == "" && r[4].needsManual) { "${r[4]}" }
        check("직접 기재 행은 합계에서 빼고 그렇다고 표시", ReportBuilder.build(ReportType.TAX, book, VehicleSnapshot(), now).sections
            .first { it.title.startsWith("업무용 사용비율") }.rows.first().second == "20.0 km (직접 기재 4건 제외)")
    }

    println("\n[31] 출발 계기판을 늦게 받을 때 (시동 직후 계기판 값이 몇 초 늦게 옴)")
    run {
        // 앞 운행: 60 km/h로 10분 → 계기판 990.0 → 1000.0
        fun firstTrip(d: Drive) {
            d.odo = 990f
            d.at(1.0, Gear.D, 0f); for (i in 1..12) d.at(1.0 + i, speed = i * 5f)
            for (i in 14..613) d.at(i.toDouble(), speed = 60f)
            for (i in 1..12) d.at(613.0 + i, speed = 60f - i * 5f)
            d.odo = 1000f; d.at(630.0, speed = 0f); d.at(631.0, Gear.P)
        }
        val (d, s) = newDrive()
        firstTrip(d)
        check("(준비) 앞 운행 끝 계기판 1000.0", s.saved.lastOdometerKm == 1000f) { "${s.saved.lastOdometerKm}" }
        val sd = s.saved.closedTrips.first().speedDistanceKm
        check("정속 구간 중간 저장이 있어도 차속 거리를 두 번 더하지 않음 (60km/h 10분 + 가감속 ≈ 10.1km)", sd in 10.0f..10.3f) { "$sd" }
        // 다음 운행: 계기판 값 없이 출발, 30 km/h로 40초(약 0.33 km) 간 뒤 계기판 1000.3 도착
        d.odo = null
        d.at(700.0, Gear.D, 0f); for (i in 1..6) d.at(700.0 + i, speed = i * 5f); for (i in 7..46) d.at(700.0 + i, speed = 30f)
        d.at(747.0, odo = 1000.3f)
        val t2 = s.saved.openTrip!!
        check("늦게 온 계기판이 이 운행 거리만큼만 늘었으면 앞 운행 끝(1000.0)을 출발 값으로", t2.startOdometerKm == 1000f && !t2.startOdometerLate) { "${t2.startOdometerKm} ${t2.startOdometerLate}" }
        check("이 운행 거리를 '기록 안 된 운행'으로 착각하지 않음", s.saved.gaps.isEmpty()) { "${s.saved.gaps}" }

        val (d2, s2) = newDrive()
        firstTrip(d2)
        d2.odo = null
        d2.at(700.0, Gear.D, 0f); for (i in 1..6) d2.at(700.0 + i, speed = i * 5f); for (i in 7..46) d2.at(700.0 + i, speed = 30f)
        d2.at(747.0, odo = 1005.0f)   // 5 km 늘어 있음: 그 사이 기록 안 된 운행이 있었는지 알 수 없음
        val t3 = s2.saved.openTrip!!
        check("설명 안 되는 차이면 출발 값 '확인 못함'으로 표시 → 운행기록부 직접 기재", t3.startOdometerLate) { "${t3.startOdometerKm} ${t3.startOdometerLate}" }

        // 운행 중 앱이 다시 시작되고 도착 때 계기판 값을 못 받으면: 도착 값을 출발 값으로 채우지 않음
        val (d3, s3) = newDrive()
        d3.odo = 2000f
        d3.at(1.0, Gear.D, 0f); for (i in 1..12) d3.at(1.0 + i, speed = i * 5f); for (i in 14..120) d3.at(i.toDouble(), speed = 60f)
        val r3 = TripRecorder(s3)
        r3.onSnapshot(VehicleSnapshot(d3.t0 + 125_000, gear = Gear.P, speedKmh = 0f, odometerKm = null))
        val ended = s3.saved.closedTrips.lastOrNull()
        check("도착 계기판을 못 받으면 비워 둠 (출발 값 복사 안 함)", ended != null && ended.endOdometerKm == null && ended.startOdometerKm == 2000f) { "$ended" }
    }

    println("\n[32] 휴대폰 QR 크기·확인 코드")
    run {
        val now = System.currentTimeMillis()
        var odo = 30_000f
        val trips = (0 until 300).map { i ->
            val st = now - (300 - i) * 3_600_000L; val s0 = odo; odo += 12.3f
            Trip(id = st, startMs = st, endMs = st + 1_800_000, startOdometerKm = s0, endOdometerKm = odo, maxSpeedKmh = 80f, speedDistanceKm = 12.3f,
                purpose = TripPurpose.BUSINESS, memo = "판교 거래처 납품 및 견적 협의 ${i}", driver = "영업팀 홍길동")
        }
        val tax = ReportBuilder.build(ReportType.TAX, Logbook(trips = trips, profile = VehicleProfile(fiscalStartMonth = 1)), VehicleSnapshot(), now)
        val prefix = (ChagokApp.REPORT_BASE_URL + "#").length
        val fit = tax.fitForQr(prefix)
        val len = prefix + fit.encoded().length
        check("QR 글자 수 한도(${Report.MAX_QR_CHARS}) 안", len <= Report.MAX_QR_CHARS) { "$len" }
        val n = fit.taxRows.size
        val more = tax.phoneCopy(taxRowsMax = n + 1)
        check("한도 안에서 가장 많이 담음 (1건 더 넣으면 넘침)", n == tax.taxRows.size || prefix + more.encoded().length > Report.MAX_QR_CHARS) { "$n" }
        check("최신 운행부터 담음", fit.taxRows.last() == tax.taxRows.last())
        check("휴대폰 안내에 담은 건수와 전체 건수", fit.notes.any { it.contains("최근 ${n}건") && it.contains("전체 300건") }) { "${fit.notes}" }
        check("확인 코드: 휴대폰 = 차 화면 (일부만 담아도 같음)", fit.toJson().toString().contains("\"check\":\"${tax.verificationCode()}\"")) { "" }
        println("    (참고) 업무 운행 300건 → 휴대폰에 ${n}건, URL ${len}자")
    }

    println("\n[33] 차 이력: 에어백 '없음'은 신호를 받을 때만")
    run {
        val b = Logbook()
        fun airbag(cur: VehicleSnapshot) = ReportBuilder.build(ReportType.BUYER, b, cur).sections.flatMap { it.rows }.first { it.first == "에어백 전개 기록" }.second
        check("신호를 못 받는 차: '확인 불가'", airbag(VehicleSnapshot()).startsWith("확인 불가"))
        check("신호를 받는 차: '없음 (기록 기간 중)'", airbag(VehicleSnapshot(airbagDeployed = false)) == "없음 (기록 기간 중)")
    }

    println("\n결과: 통과 $passed / 실패 $failed")
    if (failed > 0) { println("실패 목록: $failures"); kotlin.system.exitProcess(1) }
}
