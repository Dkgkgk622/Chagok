package com.chagok.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** The four report types. Same data, different reader. */
/** [tab] names what the report is for (the owner opens every one of them); [reader] who it is shown to. */
enum class ReportType(val title: String, val tab: String, val reader: String) {
    WEEKLY("주간 운행 요약", "주간 요약", "차주"),
    SERVICE("정비용 차량 기록", "정비용", "정비사"),
    BUYER("차 이력", "차 이력", "구매자"),
    TAX("업무용 차량 운행기록부", "운행기록부", "세무·회계"),
    ROUTE("운행 보고서", "운행 보고서", "차주"), // one trip, 나 자신용 only (locations are personal)
}

/** The reports chosen from the report tab (the route report is opened from a trip). */
val SHAREABLE_REPORTS = listOf(ReportType.WEEKLY, ReportType.SERVICE, ReportType.BUYER, ReportType.TAX)

data class ReportSection(val title: String, val rows: List<Pair<String, String>>)

/** One row of the NTS business-car logbook (국세청고시 제2025-8호 별지 서식, ③~⑩). */
data class TaxRow(
    val date: String,      // ③ 사용일자(요일)
    val user: String,      // ④ 사용자 (부서, 성명)
    val startOdo: String,  // ⑤ 주행 전 계기판의 거리
    val endOdo: String,    // ⑥ 주행 후 계기판의 거리
    val distance: String,  // ⑦ 주행거리
    val commute: String,   // ⑧ 업무용 사용거리: 출·퇴근용
    val business: String,  // ⑨ 업무용 사용거리: 일반 업무용
    val note: String,      // ⑩ 비고
    val tripId: Long = 0,  // not printed: lets the car screen edit this row's ④
    val needsManual: Boolean = false, // distance must be written by hand (odometer missing/unchanged/implausible)
    // The same values as numbers, for the compact phone format (see Report.compactTax):
    val day: Long = 0,          // local date as days since 1970-01-01
    val startT: Long? = null,   // ⑤ in tenths of a km (null = blank)
    val endT: Long? = null,     // ⑥
    val distT: Long? = null,    // ⑦
    val kind: Int = 0,          // 1 = ⑧ 출·퇴근, 2 = ⑨ 일반 업무, 0 = neither
) {
    fun cells() = listOf(date, user, startOdo, endOdo, distance, commute, business, note)
}

data class Report(
    val type: ReportType,
    val createdMs: Long,
    val period: String,
    val sections: List<ReportSection>,
    val taxRows: List<TaxRow> = emptyList(),
    val notes: List<String> = emptyList(),
    val routeTrip: Trip? = null,
    /** Phone map leaves out the start/end areas (the owner turns this on before showing someone else). */
    val hideEnds: Boolean = false,
    /** Phone copy: the full report's code, so it matches the car screen even when rows were cut. */
    val sourceCheck: String? = null,
) {
    /** Compact JSON for the phone web page. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("type", type.name)
        put("title", type.title)
        put("created", createdMs)
        put("period", period)
        put("sections", JSONArray(sections.map { sec ->
            JSONObject().apply {
                put("t", sec.title)
                put("r", JSONArray(sec.rows.map { JSONArray(listOf(it.first, it.second)) }))
            }
        }))
        if (taxRows.isNotEmpty()) put("tx", compactTax())
        put("notes", JSONArray(notes))
        put("check", sourceCheck ?: verificationCode())
        routeTrip?.let { put("route", RouteCodec.encode(it, routeIntervalSec, if (hideEnds) RouteCodec.PHONE_HIDE_RADIUS_M else 0)) }
    }

    /**
     * Logbook rows for the phone, about half the size of the plain cells so more rows fit in one QR.
     * {"u": most common ④, "r": [[days since previous row, ④ or 0 (= u), ⑤ or -1 (= previous ⑥),
     *  ⑥ or -1 (= ⑤ + ⑦), ⑦, 0|1|2 (⑧/⑨), ⑩], ...]} with km in tenths and null for a blank cell.
     * report.html rebuilds exactly the strings of [TaxRow.cells] (checked in verify/tax_roundtrip.py).
     */
    fun compactTax(): JSONObject {
        val common = taxRows.groupingBy { it.user }.eachCount().maxByOrNull { it.value }?.key ?: ""
        var prevDay = 0L
        var prevEnd: Long? = null
        val rows = taxRows.map { r ->
            val start: Long? = when { r.startT == null -> null; r.startT == prevEnd -> -1L; else -> r.startT }
            val end: Long? = when {
                r.endT == null -> null
                r.startT != null && r.distT != null && r.endT == r.startT + r.distT -> -1L
                else -> r.endT
            }
            JSONArray(listOf(r.day - prevDay, if (r.user == common) 0 else r.user, start, end, r.distT, r.kind, r.note))
                .also { prevDay = r.day; prevEnd = r.endT }
        }
        return JSONObject().apply { put("u", common); put("r", JSONArray(rows)) }
    }

    /** Spacing of route points sent to the phone (1 minute; widened if the QR would be too big). */
    private var routeIntervalSec: Int = 60

    /** Short code computed from the report content. Detects accidental edits, not forgery. */
    fun verificationCode(): String {
        val body = "$type|$createdMs|$period|" +
            sections.joinToString("|") { s -> s.title + s.rows.joinToString { "${it.first}=${it.second}" } } +
            taxRows.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
        return digest.take(6).joinToString("") { "%02X".format(it) }
    }

    /**
     * What goes into the QR: the newest [taxRowsMax] logbook rows and [sectionRowsMax] rows per
     * section (rows are kept in order; lists are newest first except the logbook, which is by date).
     */
    fun phoneCopy(taxRowsMax: Int = MAX_QR_TAX_ROWS, sectionRowsMax: Int = MAX_QR_SECTION_ROWS): Report {
        var forPhone = copy(sourceCheck = verificationCode())
        if (taxRows.size > taxRowsMax) {
            forPhone = forPhone.copy(
                taxRows = taxRows.takeLast(taxRowsMax),
                notes = notes + "휴대폰 화면에는 최근 ${taxRowsMax}건만 표시됩니다(전체 ${taxRows.size}건). " +
                    "⑪~⑬ 합계는 전체 기준입니다. 전체 내역은 차량 화면에서 확인하세요.",
            )
        }
        if (sections.any { it.rows.size > sectionRowsMax }) {
            forPhone = forPhone.copy(sections = sections.map { sec ->
                if (sec.rows.size <= sectionRowsMax) sec
                else sec.copy(rows = sec.rows.take(sectionRowsMax) +
                    ("이전 ${sec.rows.size - sectionRowsMax}건" to "차량 화면에서 확인"))
            })
        }
        return forPhone
    }

    /** The #fragment text: JSON, raw-deflated, base64url without padding. */
    fun encoded(): String {
        val raw = toJson().toString().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION, true)).use { it.write(raw) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /**
     * URL for the QR code. Data lives in the #fragment, which browsers never send to a server.
     * Kept under [MAX_QR_CHARS] so the code stays scannable on the car screen: route points are
     * thinned first, then the oldest logbook rows and long lists are left to the car screen.
     */
    fun toUrl(baseUrl: String): String = "$baseUrl#" + fitForQr(baseUrl.length + 1).encoded()

    /** The phone copy that fits; [prefix] = characters before the data. */
    fun fitForQr(prefix: Int = 0): Report {
        fun fits(r: Report) = prefix + r.encoded().length <= MAX_QR_CHARS
        var interval = 60
        var best = phoneCopy()
        while (routeTrip != null && !fits(best) && interval < 960) {
            interval *= 2
            best = phoneCopy().also { it.routeIntervalSec = interval }
        }
        best.routeIntervalSec = interval
        if (fits(best)) return best
        // Largest row counts that still fit (binary search; fewer rows never makes it longer).
        fun largest(max: Int, make: (Int) -> Report): Report? {
            var lo = 1; var hi = max; var found: Report? = null
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val r = make(mid).also { it.routeIntervalSec = interval }
                if (fits(r)) { found = r; lo = mid + 1 } else hi = mid - 1
            }
            return found
        }
        if (taxRows.isNotEmpty()) largest(minOf(taxRows.size, MAX_QR_TAX_ROWS)) { phoneCopy(taxRowsMax = it) }?.let { return it }
        val longest = sections.maxOf { it.rows.size }
        largest(minOf(longest, MAX_QR_SECTION_ROWS)) { phoneCopy(sectionRowsMax = it) }?.let { return it }
        return best // still too long: the car screen says the QR cannot be made
    }

    companion object {
        const val MAX_QR_TAX_ROWS = 120 // upper bound only; the real count is whatever fits in MAX_QR_CHARS
        const val MAX_QR_SECTION_ROWS = 30
        const val MAX_QR_CHARS = 1800 // beyond this a QR on a car screen gets hard to scan
    }
}

/** Builds reports from the logbook. All numbers come from recorded vehicle data. */
object ReportBuilder {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val SHORT_TRIP_KM = 5f

    fun build(type: ReportType, book: Logbook, current: VehicleSnapshot, nowMs: Long = System.currentTimeMillis()): Report =
        when (type) {
            ReportType.WEEKLY -> weekly(book, nowMs)
            ReportType.SERVICE -> service(book, current, nowMs)
            ReportType.BUYER -> buyer(book, current, nowMs)
            ReportType.TAX -> tax(book, nowMs)
            ReportType.ROUTE -> route(book.closedTrips.lastOrNull() ?: Trip(id = 0, startMs = nowMs, endMs = nowMs), nowMs)
        }

    // ---------------- 나 자신용: 운행 경로 (운행 1건) ----------------

    fun route(trip: Trip, now: Long = System.currentTimeMillis(), hideEnds: Boolean = false): Report {
        val located = trip.events.count { it.location != null }
        return Report(
            type = ReportType.ROUTE,
            createdMs = now,
            period = "${dateTime(trip.startMs)} ~ ${trip.endMs?.let { dateTime(it) } ?: "진행 중"}",
            sections = listOf(
                ReportSection(
                    "운행",
                    listOf(
                        "운행 시각" to tripTime(trip),
                        "운행 목적" to trip.purpose.label +
                            if (trip.purpose == TripPurpose.BUSINESS) " · " + trip.memo.ifBlank { "방문 목적 미기재" } else "",
                        "거리" to "${km(trip.distanceKm.toDouble())} (${trip.distanceSource?.label ?: "측정 없음"})",
                        "시간" to duration(trip.durationMs),
                        "최고 속도" to "%.0f km/h".format(trip.maxSpeedKmh),
                        "급가속" to "${trip.count(DriveEvent.Type.HARSH_ACCEL) + trip.count(DriveEvent.Type.QUICK_START)}회",
                        "급감속" to "${trip.count(DriveEvent.Type.HARSH_BRAKE) + trip.count(DriveEvent.Type.SUDDEN_STOP)}회",
                        "과속 (제한속도 +20km/h)" to "${trip.count(DriveEvent.Type.OVERSPEED)}회",
                        "1분 이상 정차" to "${trip.dwells.size}곳",
                        "위치 기록" to "${trip.track.size}개 지점 · 이벤트 ${trip.events.size}건 중 ${located}건 위치 확인",
                    )
                ),
                ReportSection(
                    "입력 이력 (사람이 입력한 값)",
                    if (trip.edits.isEmpty()) listOf("입력한 값 없음" to "")
                    else trip.edits.map { e -> "${dateTime(e.timeMs)} · ${e.field.label}" to e.value.ifBlank { "(비움)" } } +
                        listOfNotNull(trip.auditNote().takeIf { it.isNotEmpty() }?.let { "운행기록부 표시" to it }),
                ),
            ),
            notes = listOfNotNull(
                if (trip.demo) "시연용(가상) 운행입니다. 실제로 주행한 기록이 아닙니다." else null,
                "운행 시각·거리·급가속 등은 차량이 자동 기록한 값이고, 목적·방문 목적·사용자는 사람이 입력한 값입니다(입력 이력 참고).",
                "위치는 차량 내비게이션에서 약 15초마다 받은 값입니다. 점 사이는 직선으로 이어져 실제 도로와 다를 수 있습니다.",
                "이 경로는 나 자신용입니다. 정비용·차 이력·운행기록부 보고서에는 위치가 들어가지 않습니다.",
                if (hideEnds) "휴대폰 지도에는 출발·도착 지점 반경 ${RouteCodec.PHONE_HIDE_RADIUS_M}m 안의 경로와 표시를 넣지 않았습니다(집·회사 위치 보호). 차 화면 지도에는 모두 표시됩니다." else null,
            ),
            routeTrip = trip,
            hideEnds = hideEnds,
        )
    }

    /**
     * Guidance when some distance is uncertain or some driving was not recorded.
     * Estimated distances are never put into reports; the reader is asked to fill them in.
     */
    private fun uncertaintyNotes(book: Logbook, from: Long, to: Long, blankRows: Int = 0): List<String> {
        val notes = mutableListOf<String>()
        if (blankRows > 0) {
            notes += "직접 기재 필요: 거리 칸이 빈 운행 ${blankRows}건은 계기판 값을 믿을 수 있게 확인하지 못한 운행입니다. " +
                "앞뒤 운행의 계기판 값을 참고해 직접 적으세요. 이 운행은 ⑪·⑫ 합계에 들어가 있지 않습니다."
        }
        val demos = book.closedTrips.count { it.demo && it.startMs in from until to }
        if (demos > 0) notes += "시연용(가상) 운행 ${demos}건이 포함되어 있습니다. 실제 기록이 아니니 제출 전에 데이터 확인 탭에서 지워 주세요."
        val gaps = book.gaps.filter { it.toMs in from until to }
        if (gaps.isNotEmpty()) {
            val when_ = gaps.takeLast(3).joinToString(", ") { "${dateTime(it.fromMs)}~${dateTime(it.toMs)}" }
            notes += "기록되지 않은 운행이 있을 수 있습니다 (${gaps.size}건: $when_${if (gaps.size > 3) " 외" else ""}). " +
                "이 기간의 거리는 보고서에 포함하지 않았습니다. 필요하면 계기판을 확인해 직접 기재하세요."
        }
        return notes
    }

    // ---------------- 나 자신용 ----------------

    private fun weekly(book: Logbook, now: Long): Report {
        val thisWeek = book.closedTrips.filter { it.startMs >= now - 7 * DAY_MS }
        val lastWeek = book.closedTrips.filter { it.startMs in (now - 14 * DAY_MS) until (now - 7 * DAY_MS) }
        val weekKm = thisWeek.sumOf { it.distanceKm.toDouble() }
        val lastKm = lastWeek.sumOf { it.distanceKm.toDouble() }

        val summary = listOf(
            "운행 횟수" to "${thisWeek.size}회",
            "총 주행거리" to km(weekKm) + diffText(weekKm, lastKm, "km"),
            "총 운행 시간" to duration(thisWeek.sumOf { it.durationMs }),
            "배터리 사용" to (thisWeek.mapNotNull { it.batteryUsed }.sum().takeIf { thisWeek.isNotEmpty() }?.let { "$it %p" } ?: "-"),
            "최고 속도" to (thisWeek.maxOfOrNull { it.maxSpeedKmh }?.let { "%.0f km/h".format(it) } ?: "-"),
        )
        val habits = drivingHabits(thisWeek)
        val purposes = TripPurpose.entries.map { p ->
            p.label to km(thisWeek.filter { it.purpose == p }.sumOf { it.distanceKm.toDouble() })
        }
        return Report(
            type = ReportType.WEEKLY,
            createdMs = now,
            period = "${date(now - 7 * DAY_MS)} ~ ${date(now)}",
            sections = listOf(
                ReportSection("이번 주 운행", summary),
                ReportSection("주행거리 측정 근거", distanceBasis(thisWeek)),
                ReportSection("운전 습관 (한국교통안전공단 위험운전행동 기준)", habits),
                ReportSection("과속 기록 (제한속도 +20km/h 초과, 최근 10건)", overspeedRows(thisWeek, 10)),
                ReportSection("목적별 거리", purposes),
            ),
            notes = uncertaintyNotes(book, now - 7 * DAY_MS, now + 1) +
                "주행 중 화면 조작 없이 차량이 자동으로 기록한 데이터입니다.",
        )
    }

    // ---------------- 정비소용 ----------------

    private fun service(book: Logbook, current: VehicleSnapshot, now: Long): Report {
        val recent = book.closedTrips.filter { it.startMs >= now - 30 * DAY_MS }
        val sections = mutableListOf<ReportSection>()

        sections += ReportSection(
            "현재 차량 상태",
            listOf(
                "누적 주행거리" to (current.odometerKm?.let { km(it.toDouble()) } ?: "-"),
                "배터리 잔량" to (current.batteryPercent?.let { "$it %" } ?: "-"),
                "브레이크액" to ok(current.brakeFluidLow, "부족", "정상"),
                "워셔액" to ok(current.washerFluidLow, "부족", "정상"),
                "전원 차단 경고" to ok(current.powerDownWarning, "있음", "없음"),
                "에어백 전개 이력" to ok(current.airbagDeployed, "있음", "없음"),
                "켜진 경고등" to (current.telltales?.let { on ->
                    on.map { Telltale.label(it) }.sorted().joinToString().ifEmpty { "없음" }
                } ?: "확인 불가"),
            )
        )

        // Driving conditions that the owner's manual lists as severe (fact only, no judgement).
        sections += ReportSection("최근 30일 운행 조건 (제조사 매뉴얼의 가혹 조건 항목 기준)", severeConditions(recent))

        // Every warning so far, newest first, with how long it stayed on.
        val episodes = warningEpisodes(book.safetyEvents)
        val warnings = episodes.map {
            "${dateTime(it.startMs)} · ${it.label}" to listOfNotNull(
                it.odometerKm?.let { o -> km(o.toDouble()) },
                it.endMs?.let { end -> duration(end - it.startMs) + " 동안" } ?: "지금도 켜짐",
            ).joinToString(" · ")
        }
        sections += ReportSection("경고 기록 (전체 ${episodes.size}건)", warnings.ifEmpty { listOf("기록 없음" to "") })

        val lastVisit = book.visits.lastOrNull { !it.isOpen }
        if (lastVisit != null) {
            val pick = lastVisit.pickUp!!
            // "Again" only when the same warning was seen before the car went in; otherwise it is new.
            val seenBefore = book.safetyEvents.filter { it.active && it.timeMs <= lastVisit.dropOff.timeMs }.map { it.key }.toSet() +
                lastVisit.dropOff.activeWarnings
            // First time each warning came on after pick-up (later repeats would only repeat the row).
            val recurrence = book.safetyEvents.filter { it.active && it.timeMs > pick.timeMs }.distinctBy { it.key }.map { e ->
                val eventKm = e.odometerKm
                val pickKm = pick.odometerKm
                val after = if (eventKm != null && pickKm != null) eventKm - pickKm else null
                e.label to ("수리 후 " + (after?.let { km(it.toDouble()) } ?: "?") +
                    if (e.key in seenBefore) " 만에 다시 발생" else " 만에 처음 발생")
            }
            sections += ReportSection(
                "직전 정비 (${date(lastVisit.dropOff.timeMs)} 맡김 → ${date(pick.timeMs)} 찾음)",
                listOf(
                    "맡길 때 주행거리" to (lastVisit.dropOff.odometerKm?.let { km(it.toDouble()) } ?: "-"),
                    "찾을 때 주행거리" to (pick.odometerKm?.let { km(it.toDouble()) } ?: "-"),
                    "정비 중 주행" to book.serviceDrive(lastVisit).text,
                    "맡길 때 경고" to Warnings.labels(lastVisit.dropOff.activeWarnings),
                    "찾을 때 경고" to Warnings.labels(pick.activeWarnings),
                ) + recurrence,
            )
        }

        return Report(
            type = ReportType.SERVICE,
            createdMs = now,
            period = "${date(now - 30 * DAY_MS)} ~ ${date(now)}",
            sections = sections,
            notes = listOf(
                "차량이 자동으로 기록한 참고 자료이며, 고장 원인 진단이나 수리 판단을 대신하지 않습니다.",
                "복잡한 수리는 공식 서비스 네트워크의 점검을 함께 받아보시길 권장합니다.",
            ),
        )
    }

    // ---------------- 구매자용 ----------------

    private fun buyer(book: Logbook, current: VehicleSnapshot, now: Long): Report {
        val trips = book.closedTrips
        val first = trips.firstOrNull()?.startMs
        val totalKm = trips.sumOf { it.distanceKm.toDouble() }
        val airbagEver = book.safetyEvents.any { it.type == SafetyEvent.Type.AIRBAG_DEPLOYED && it.active }
        // "없음" is a claim to a buyer: only say it when the car actually reports this signal.
        val airbagText = when {
            airbagEver -> "있음"
            current.airbagDeployed == null -> "확인 불가 (이 차량에서 받을 수 없는 신호)"
            else -> "없음 (기록 기간 중)"
        }

        return Report(
            type = ReportType.BUYER,
            createdMs = now,
            period = if (first != null) "${date(first)} ~ ${date(now)}" else "기록 없음",
            sections = listOf(
                ReportSection(
                    "기록 개요",
                    listOf(
                        "기록 기간" to (first?.let { "${(now - it) / DAY_MS}일" } ?: "-"),
                        "기록된 주행거리" to km(totalKm),
                        "현재 누적 주행거리" to (current.odometerKm?.let { km(it.toDouble()) } ?: "-"),
                        "운행 횟수" to "${trips.size}회",
                        "평균 운행 거리" to (if (trips.isNotEmpty()) km(totalKm / trips.size) else "-"),
                    )
                ),
                ReportSection("운행 방식", drivingHabits(trips) + severeConditions(trips)),
                ReportSection("주행거리 측정 근거", distanceBasis(trips)),
                ReportSection(
                    "사고·정비 관련",
                    listOf(
                        "에어백 전개 기록" to airbagText,
                        "경고 발생 횟수" to "${book.safetyEvents.count { it.active }}회",
                        "켜진 적 있는 경고등" to book.safetyEvents.filter { it.active && it.code != null }
                            .groupingBy { it.label }.eachCount().entries.joinToString { "${it.key} ${it.value}회" }.ifEmpty { "없음" },
                        "정비 입고 기록" to "${book.visits.count { !it.isOpen }}회",
                    )
                ),
            ),
            notes = uncertaintyNotes(book, 0, now + 1) + listOf(
                "앱 설치 이후 기간만 기록되어 있습니다.",
                "확인 코드는 보고서 내용이 우연히 바뀌었는지 확인하는 용도이며, 위·변조 방지 인증은 아닙니다.",
            ),
        )
    }

    // ---------------- 세무용 ----------------

    /**
     * Follows the NTS notice on business-car logbooks (국세청고시 제2025-8호).
     * Distances use the instrument-cluster odometer only, as the form requires.
     * Article 2 of the notice accepts other formats that contain the same core items.
     */
    private fun tax(book: Logbook, now: Long): Report {
        val profile = book.profile
        val (fyStart, fyEnd) = fiscalYear(now, profile.fiscalStartMonth)
        val yearTrips = book.closedTrips.filter { it.startMs in fyStart until fyEnd }
        val user = profile.defaultUser.ifBlank { "(직접 기재)" }

        // All logbook numbers are whole tenths of a km, taken from the readings exactly as printed, so
        // ⑤ + ⑦ = ⑥ on every row and the ⑦ column adds up to ⑪ (no separate rounding anywhere).
        val rows = mutableListOf<TaxRow>()
        var total = 0L; var commute = 0L; var business = 0L
        var prevEnd: Long? = null
        yearTrips.forEach { t ->
            val start = t.startOdometerKm?.let(::tenths)
            val end = t.endOdometerKm?.let(::tenths)
            val dist = if (start != null && end != null) end - start else null
            // The car moved (another source measured it) but the odometer did not.
            val unchanged = dist == 0L && t.distanceKm >= Trip.MIN_MEASURED_KM
            val implausible = t.odometerImplausible
            val needsManual = implausible || t.startOdometerLate || dist == null || unchanged
            // Only readings that can be trusted are printed; the rest is left blank to be filled in.
            val shownStart = start.takeUnless { implausible || t.startOdometerLate }
            val shownEnd = end.takeUnless { implausible || unchanged }
            val odoValue = if (needsManual) "" else fmtTenths(dist!!)
            if (!needsManual) {
                total += dist!!
                if (t.purpose == TripPurpose.COMMUTE) commute += dist
                if (t.purpose == TripPurpose.BUSINESS) business += dist
            }
            val distanceNote = when {
                implausible -> "계기판 값 급변 → 직접 기재"
                t.startOdometerLate -> "출발 계기판 값 확인 못함 → 직접 기재"
                start == null || end == null -> "계기판 값 없음 → 직접 기재"
                unchanged -> "계기판 변화 없음 → 직접 기재"
                t.purpose == TripPurpose.UNSET -> "목적 미지정"
                t.duringServiceVisit -> "정비 중 운행"
                else -> ""
            }
            // The odometer at the start should be where the previous trip ended; a jump means driving
            // that was not recorded (it needs its own row) — shown, never filled in by the app.
            val continuityNote = if (shownStart != null && prevEnd != null) {
                val jump = shownStart - prevEnd!!
                when {
                    jump >= CONTINUITY_MIN_TENTHS -> "앞 운행 뒤 계기판 +${fmtTenths(jump)} km (기록 안 된 운행 → 직접 기재)"
                    jump < 0 -> "앞 운행보다 계기판 값이 작음 → 확인 필요"
                    else -> ""
                }
            } else ""
            // Business trips: the visit purpose is what shows the trip was for work.
            val memoNote = when {
                t.purpose != TripPurpose.BUSINESS -> ""
                t.memo.isBlank() -> "방문 목적 미기재"
                else -> t.memo
            }
            val note = listOf(distanceNote, continuityNote, memoNote, t.auditNote(), if (t.demo) "시연용 데이터" else "")
                .filter { it.isNotEmpty() }.joinToString(" / ")
            rows += TaxRow(
                date = dateWithDay(t.startMs),
                user = t.driver.ifBlank { user },
                startOdo = shownStart?.let(::fmtTenths) ?: "",
                endOdo = shownEnd?.let(::fmtTenths) ?: "",
                distance = odoValue,
                commute = if (t.purpose == TripPurpose.COMMUTE) odoValue else "",
                business = if (t.purpose == TripPurpose.BUSINESS) odoValue else "",
                note = note,
                tripId = t.id,
                needsManual = needsManual,
                day = java.time.Instant.ofEpochMilli(t.startMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay(),
                startT = shownStart,
                endT = shownEnd,
                distT = if (needsManual) null else dist,
                kind = when { needsManual -> 0; t.purpose == TripPurpose.COMMUTE -> 1; t.purpose == TripPurpose.BUSINESS -> 2; else -> 0 },
            )
            prevEnd = shownEnd
        }
        val missing = rows.count { it.needsManual }
        val unset = yearTrips.count { it.purpose == TripPurpose.UNSET }
        val excluded = if (missing > 0) " (직접 기재 ${missing}건 제외)" else ""

        fun orBlank(v: String) = v.ifBlank { "(직접 기재)" }
        return Report(
            type = ReportType.TAX,
            createdMs = now,
            period = "사업연도 ${date(fyStart)} ~ ${date(fyEnd - DAY_MS)}",
            sections = listOf(
                ReportSection(
                    "기본정보",
                    listOf(
                        "법인명" to orBlank(profile.companyName),
                        "사업자등록번호" to orBlank(profile.businessNumber),
                        "① 차종" to orBlank(profile.carModel),
                        "② 자동차등록번호" to orBlank(profile.plateNumber),
                    )
                ),
                ReportSection(
                    "업무용 사용비율 계산",
                    listOf(
                        "⑪ 사업연도 총주행 거리" to fmtTenths(total) + " km" + excluded,
                        "⑫ 사업연도 업무용 사용거리" to fmtTenths(commute + business) + " km" + excluded,
                        "    출·퇴근용 / 일반 업무용" to "${fmtTenths(commute)} km / ${fmtTenths(business)} km",
                        "⑬ 업무사용비율 (⑫/⑪)" to (if (total > 0) String.format(Locale.ROOT, "%.1f %%", (commute + business) * 100.0 / total) else "-"),
                        "목적 미지정 운행" to "${unset}건",
                        "방문 목적 미기재 업무 운행" to "${yearTrips.count { it.needsMemo }}건",
                        "직접 기재가 필요한 운행 (계기판 거리 없음)" to "${missing}건",
                    )
                ),
            ),
            // QR capacity: the phone page carries the most recent rows; the car screen shows all.
            taxRows = rows,
            notes = uncertaintyNotes(book, fyStart, fyEnd, missing) + listOf(
                "국세청고시 제2025-8호 「업무용승용차 운행기록 방법에 관한 고시」 별지 서식의 항목을 따릅니다.",
                "주행 전·후 거리는 차량 계기판(누적 주행거리) 값이며, 앱이 계산한 거리는 사용하지 않습니다.",
                "사용일자·거리는 차량이 운행 시점에 자동 기록했고, 목적·방문 목적·사용자는 사람이 입력했습니다. " +
                    "입력 시각이 함께 저장되며, 운행 하루 이상 뒤 입력하거나 고친 경우 ⑩비고에 표시됩니다.",
                "업무 목적은 운행 후 차주가 선택한 값입니다. 사용자와 비고는 필요 시 직접 보완하세요.",
                "법인세 신고 시 인정 여부는 세무 전문가와 확인하세요.",
            ),
        )
    }

    /** Start (inclusive) and end (exclusive) of the fiscal year containing [now]. */
    private fun fiscalYear(now: Long, startMonth: Int): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val startYear = if (month >= startMonth) year else year - 1
        val start = java.util.Calendar.getInstance().apply {
            clear(); set(startYear, startMonth - 1, 1, 0, 0, 0)
        }
        val end = (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.YEAR, 1) }
        return start.timeInMillis to end.timeInMillis
    }

    // ---------------- shared pieces ----------------

    private fun drivingHabits(trips: List<Trip>): List<Pair<String, String>> {
        val totalKm = trips.sumOf { it.distanceKm.toDouble() }
        fun per100(type: DriveEvent.Type): String {
            val n = trips.sumOf { it.count(type) }
            return if (totalKm >= 1) "%.1f회 / 100km".format(n / totalKm * 100) else "${n}회"
        }
        return listOf(
            DriveEvent.Type.HARSH_ACCEL, DriveEvent.Type.QUICK_START,
            DriveEvent.Type.HARSH_BRAKE, DriveEvent.Type.SUDDEN_STOP, DriveEvent.Type.OVERSPEED,
        ).map { it.label to per100(it) }
    }

    /** Which source measured each trip's distance, and how many trips had disagreeing sources. */
    private fun distanceBasis(trips: List<Trip>): List<Pair<String, String>> {
        if (trips.isEmpty()) return listOf("기록 없음" to "")
        val bySource = DistanceSource.entries.mapNotNull { src ->
            val n = trips.count { it.distanceSource == src }
            if (n > 0) "${src.label} ${n}건" else null
        }
        return listOf(
            "거리 측정 출처" to bySource.joinToString(", ").ifEmpty { "측정값 없음" },
            "출처 간 10% 이상 차이" to "${trips.count { it.distanceMismatch }}건",
            "계기판 값 급변으로 제외" to "${trips.count { it.odometerImplausible }}건",
        )
    }

    /** Overspeed records: time, speed / limit, location. */
    private fun overspeedRows(trips: List<Trip>, max: Int): List<Pair<String, String>> {
        val events = trips.flatMap { t -> t.events.filter { it.type == DriveEvent.Type.OVERSPEED } }
            .sortedByDescending { it.timeMs }.take(max)
        if (events.isEmpty()) return listOf("기록 없음" to "")
        return events.map { e ->
            val speed = e.speedKmh?.let { "%.0f".format(it) } ?: "?"
            // Coordinates mean nothing to a reader; the place is on the trip's map (운행 보고서).
            dateTime(e.timeMs) to "최고 $speed / 제한 ${e.limitKmh ?: "?"} km/h"
        }
    }

    private fun severeConditions(trips: List<Trip>): List<Pair<String, String>> {
        if (trips.isEmpty()) return listOf("기록 없음" to "")
        val totalKm = trips.sumOf { it.distanceKm.toDouble() }
        val shortTrips = trips.count { it.distanceKm < SHORT_TRIP_KM }
        val idle = trips.sumOf { it.idleSeconds }
        val drive = trips.sumOf { it.durationMs } / 1000
        val stops = trips.sumOf { it.stopCount }
        return listOf(
            "짧은 거리(5km 미만) 운행 비율" to "%.0f %%".format(shortTrips * 100.0 / trips.size),
            "정차 대기 시간 비율" to (if (drive > 0) "%.0f %%".format(idle * 100.0 / drive) else "-"),
            "정지·출발 빈도" to (if (totalKm >= 1) "%.1f회 / 10km".format(stops / totalKm * 10) else "${stops}회"),
        )
    }

    private fun ok(v: Boolean?, bad: String, good: String) = when (v) {
        true -> bad; false -> good; null -> "확인 불가"
    }

    private fun diffText(now: Double, before: Double, unit: String): String {
        if (before <= 0) return ""
        val d = now - before
        return " (지난주 대비 %+.1f %s)".format(d, unit)
    }

    fun km(v: Double) = String.format(Locale.ROOT, "%.1f km", v)

    /** Odometer readings in whole tenths of a km, as a 0.1 km display shows them. */
    fun tenths(km: Float): Long = Math.round(km.toDouble() * 10)
    fun fmtTenths(t: Long): String = (if (t < 0) "-" else "") + "${kotlin.math.abs(t) / 10}.${kotlin.math.abs(t) % 10}"

    /** A jump of this much between one trip's end and the next one's start is shown in ⑩ (0.1 km is reading lag). */
    private const val CONTINUITY_MIN_TENTHS = 2L
    fun date(ms: Long): String = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(ms))
    fun dateTime(ms: Long): String = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(Date(ms))
    fun dateWithDay(ms: Long): String = SimpleDateFormat("yyyy.MM.dd(E)", Locale.KOREA).format(Date(ms))
    private fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))

    /** "2026.10.30(금) 08:00 ~ 08:30", or with both dates when the trip crosses midnight. */
    fun tripTime(t: Trip): String {
        val end = t.endMs ?: return "${dateWithDay(t.startMs)} ${hm(t.startMs)} ~ 진행 중"
        return if (date(t.startMs) == date(end)) "${dateWithDay(t.startMs)} ${hm(t.startMs)} ~ ${hm(end)}"
        else "${dateWithDay(t.startMs)} ${hm(t.startMs)} ~ ${dateWithDay(end)} ${hm(end)}"
    }
    fun duration(ms: Long): String {
        val m = ms / 60_000
        val sec = (ms / 1000) % 60
        return when {
            m >= 60 -> "${m / 60}시간 ${m % 60}분"
            m >= 1 -> "${m}분 ${sec}초"
            else -> "${sec}초"
        }
    }
}
