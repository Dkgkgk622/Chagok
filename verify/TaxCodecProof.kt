package com.chagok.app

/**
 * Prints a logbook's phone JSON ("tx", compact) and the cells the car screen shows for the same rows.
 * tax_roundtrip.py decodes "tx" with report.html's own decodeTax (Chromium) and compares every cell.
 * Run with -Duser.timezone=Asia/Seoul. Covers: a fiscal year across New Year, trips at 23:5x,
 * blank cells (late / unchanged / implausible / missing odometer), odometer jumps, other drivers,
 * notes with quotes and backslashes.
 */
fun main() {
    val rnd = java.util.Random(11)
    val zone = java.time.ZoneId.of("Asia/Seoul")
    val first = java.time.LocalDate.of(2026, 7, 1)
    var odo = 48_765.43f
    val purposes = TripPurpose.entries
    val trips = (0 until 360).map { i ->
        val date = first.plusDays(i.toLong())
        val hour = if (i % 9 == 0) 23 else 8 + i % 12
        val st = date.atTime(hour, 50 + i % 9).atZone(zone).toInstant().toEpochMilli()
        if (i % 41 == 0) odo += 7.7f                   // unrecorded driving before this trip
        val s0 = odo; val km = 0.4f + rnd.nextFloat() * 60f; odo += km
        var t = Trip(id = st, startMs = st, endMs = st + 2_400_000, startOdometerKm = s0, endOdometerKm = odo,
            maxSpeedKmh = 100f, speedDistanceKm = km, purpose = purposes[i % purposes.size],
            memo = if (i % 5 == 0) "\"A사\" 납품 \\ 회의 $i" else "", driver = if (i % 13 == 0) "관리팀 이영희" else "")
        when (i % 23) {
            3 -> t = t.copy(startOdometerLate = true)
            7 -> t = t.copy(endOdometerKm = s0)                  // odometer did not move
            11 -> t = t.copy(endOdometerKm = s0 + 9000f)         // impossible jump
            17 -> t = t.copy(endOdometerKm = null)
        }
        t
    }
    val book = Logbook(trips = trips, profile = VehicleProfile(fiscalStartMonth = 7, userDepartment = "영업팀", userName = "홍길동"))
    val now = java.time.LocalDate.of(2027, 6, 30).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val tax = ReportBuilder.build(ReportType.TAX, book, VehicleSnapshot(), now)
    println(tax.compactTax().toString())
    println(org.json.JSONArray(tax.taxRows.map { org.json.JSONArray(it.cells()) }).toString())
}
