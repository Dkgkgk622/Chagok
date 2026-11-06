package com.chagok.app

/**
 * Prints RouteCodec output for a known trip and the values the phone page must rebuild from it.
 * route_roundtrip.py decodes the first line with report.html's own decodeRoute (Chromium) and compares.
 * Times are deliberately not whole seconds (15.7 s spacing) so rounding drift would show up.
 */
fun main() {
    val t0 = 1_793_300_000_123L
    val track = (0 until 240).map { i ->
        TrackPoint(t0 + i * 15_700L + (i % 3) * 137L, 37.41272 + i * 0.00031, 127.09468 + i * 0.00047, (i * 3 % 90).toFloat(), if (i > 20) 60 else null)
    }
    val events = listOf(
        DriveEvent(t0 + 30_456, DriveEvent.Type.QUICK_START, 1f, 21f, null, "37.41300,127.09500", peakRate = 19.4f, endMs = t0 + 40_999),
        DriveEvent(t0 + 2_400_777, DriveEvent.Type.OVERSPEED, 1f, 104f, 60, "37.42000,127.10500", endMs = t0 + 2_431_200),
    )
    val trip = Trip(id = 1, startMs = t0, endMs = t0 + 3_800_000, track = track, events = events,
        dwells = listOf(Dwell(t0 + 200_500, t0 + 285_900, "37.41800,127.10200")))
    println(RouteCodec.encode(trip, 60).toString())
    println(RouteCodec.sample(track, 60).joinToString(";") { "${it.timeMs},${it.lat},${it.lon},${it.speedKmh},${it.limitKmh}" })
    println(events.joinToString(";") { "${it.timeMs},${it.location},${it.speedKmh},${it.limitKmh},${it.peakRate},${it.endMs}" })
    println(trip.dwells.joinToString(";") { "${it.startMs},${it.location},${it.seconds}" })
}
