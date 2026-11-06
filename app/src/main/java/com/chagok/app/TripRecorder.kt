package com.chagok.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Turns the stream of vehicle snapshots into a logbook.
 *
 * Run condition (Pleos background guide): records only while the gear is not P.
 * The open trip is saved on every event, so a restarted process resumes the same trip.
 */
class TripRecorder(private val store: LogbookStorage) {

    private val _logbook = MutableStateFlow(store.load())
    val logbook: StateFlow<Logbook> = _logbook

    /** Called when a trip starts/ends so the app can keep the process alive while driving. */
    var onDrivingChanged: ((Boolean) -> Unit)? = null

    /** Road speed limit from navigation (null = unknown). Set by the app. */
    @Volatile var speedLimitKmh: Int? = null

    /** Last known location from navigation ("lat,lon"). Set by the app. */
    @Volatile var lastLocation: String? = null

    /** Asks navigation for a fresh location; the answer comes back through [onLocation]. */
    var requestLocation: (() -> Unit)? = null

    // Per-trip working state (not saved; rebuilt from the next samples).
    private var lastSpeedKmh: Float? = null
    private var lastSpeedChangeMs: Long = 0
    private val speedHistory = ArrayDeque<Pair<Long, Float>>()
    // Harsh acceleration / braking are counted once per episode (like overspeed):
    // the episode stays open while the rate stays above 70% of the criterion.
    private var accelEpisodeMs: Long? = null   // event time of the open acceleration episode
    private var brakeEpisodeMs: Long? = null   // event time of the open braking episode
    private var lastStrongBrakeMs: Long? = null // last moment of strong deceleration
    private var lastStrongAccelMs: Long? = null // last moment of strong acceleration
    private var idleSinceMs: Long? = null
    private var pendingBrakeEventMs: Long? = null   // harsh deceleration that may end in a stop
    private var overspeedEventMs: Long? = null      // open overspeed episode
    // Overspeed is shown where its top speed was: the first location after the latest top speed is used.
    private var overspeedLocationWanted = false
    private var overspeedPeakMs: Long = 0
    private var lastPeakLocationRequestMs: Long = 0
    private var lastLocationMs: Long = 0      // vehicle time when [lastLocation] arrived
    private var lastPoint: Pair<Double, Double>? = null  // last navigation location in this trip
    private var wasMoving = false            // reached MOVING_KMH since the last stop
    private var leftStandstillMs: Long? = null // when the vehicle last started moving from a stop
    private var lastSnapshotMs: Long = 0      // vehicle time of the latest snapshot
    private var idleLocation: String? = null  // where the current stop is (for 1+ minute stops)
    private var lastLocationRequestMs: Long = 0
    private var lastSavedMs: Long = 0
    private var lastSafety: Map<String, Boolean> = emptyMap() // Warnings key -> on

    @Synchronized
    fun onSnapshot(s: VehicleSnapshot) {
        lastSnapshotMs = s.timeMs
        handleSafety(s)

        val open = _logbook.value.openTrip
        // Odometer moved while nothing was being recorded? (checked whenever parked)
        if (open == null && s.odometerKm != null) checkMissedDistance(s.odometerKm, s.timeMs)

        val gear = s.gear ?: return

        when {
            open == null && gear != Gear.P -> startTrip(s)
            open != null && gear == Gear.P -> endTrip(s)
            open != null -> updateTrip(open, s)
        }
    }

    // ---------------- trips ----------------

    private fun startTrip(s: VehicleSnapshot) {
        if (s.odometerKm != null) checkMissedDistance(s.odometerKm, s.timeMs)
        val trip = Trip(
            id = s.timeMs,
            startMs = s.timeMs,
            startOdometerKm = s.odometerKm,
            tripMeterStartKm = s.tripMeterKm,
            startBattery = s.batteryPercent,
            duringServiceVisit = _logbook.value.openVisit != null,
        )
        resetWorkingState(s)
        // Already stopped when the trip begins (e.g. idling in D before moving off).
        if ((s.speedKmh ?: 0f) < STOPPED_KMH) idleSinceMs = s.timeMs
        mutate { it.copy(trips = it.trips + trip) }
        onDrivingChanged?.invoke(true)
        requestLocation?.invoke() // where the trip starts
    }

    private fun updateTrip(open: Trip, s: VehicleSnapshot) {
        // The odometer arrives some seconds after start-up; the car may already be moving by then.
        var lateStart = false
        val startOdo = open.startOdometerKm ?: s.odometerKm?.let { now ->
            // Distance so far, including the stretch since the last speed change (steady speed sends no updates).
            val moved = if (open.maxSpeedKmh < MOVING_KMH) 0f else open.addDistance(lastSpeedKmh, s.timeMs).distanceKm
            val last = _logbook.value.lastOdometerKm
            when {
                moved < Trip.MIN_MEASURED_KM -> { checkMissedDistance(now, open.startMs); now } // not moved yet: this is the start
                // Moved already: the last trip's end is the true start if the odometer grew only by what this trip drove.
                last != null && last <= now && now - last <= moved + LATE_START_TOLERANCE_KM -> last
                else -> { lateStart = true; now } // cannot tell where it started: the logbook asks for it by hand
            }
        }
        var trip = open.copy(
            startOdometerKm = startOdo,
            startOdometerLate = open.startOdometerLate || lateStart,
            tripMeterStartKm = open.tripMeterStartKm ?: s.tripMeterKm,
            startBattery = open.startBattery ?: s.batteryPercent,
        )
        // "important" changes are written to disk right away; others ride the periodic save.
        var important = trip != open
        val speed = s.speedKmh

        // First sample after a process restart in the middle of a trip: working state starts from here.
        if (lastSpeedKmh == null && speed != null) {
            wasMoving = speed >= MOVING_KMH
            lastSpeedChangeMs = s.timeMs
        }
        val changed = speed != null && speed != lastSpeedKmh
        if (speed != null && changed) {
            val prev = lastSpeedKmh
            // Speed is reported on change, so the previous speed held until now.
            trip = trip.addDistance(prev, s.timeMs)
            lastSpeedKmh = speed
            lastSpeedChangeMs = s.timeMs

            if (speed > trip.maxSpeedKmh) trip = trip.copy(maxSpeedKmh = speed)
            if (prev != null && prev < STOPPED_KMH && speed >= STOPPED_KMH) leftStandstillMs = s.timeMs
        }

        // Rate checks use every speed sample, also unchanged ones (the vehicle reports speed
        // about once a second), so a steady stretch is part of the window.
        if (speed != null) {
            // An episode with no strong change for a while has ended, even if no rate could be
            // computed in between (e.g. samples stopped arriving).
            if (accelEpisodeMs != null && s.timeMs - (lastStrongAccelMs ?: 0) > EPISODE_GAP_MS) accelEpisodeMs = null
            if (brakeEpisodeMs != null && s.timeMs - (lastStrongBrakeMs ?: 0) > EPISODE_GAP_MS) brakeEpisodeMs = null

            // Harsh acceleration / braking: speed change over roughly the last second.
            speedHistory.addLast(s.timeMs to speed)
            while (speedHistory.isNotEmpty() && s.timeMs - speedHistory.first().first > RATE_WINDOW_MS) {
                speedHistory.removeFirst()
            }
            val oldest = speedHistory.first()
            val dtSec = (s.timeMs - oldest.first) / 1000f
            if (dtSec >= MIN_RATE_SEC) {
                val rateKmhPerSec = (speed - oldest.second) / dtSec

                // Acceleration episode: one event from the first moment above 11 km/h/s
                // until the rate drops below 70% of it.
                val accelOpen = accelEpisodeMs
                if (accelOpen == null) {
                    if (rateKmhPerSec >= HARSH_ACCEL_KMH_PER_SEC) {
                        // Started from a stop within the last few seconds = quick start, otherwise harsh acceleration.
                        val fromStop = oldest.second < QUICK_START_FROM_KMH ||
                            leftStandstillMs?.let { s.timeMs - it <= QUICK_START_WINDOW_MS } == true
                        val type = if (fromStop) DriveEvent.Type.QUICK_START else DriveEvent.Type.HARSH_ACCEL
                        trip = trip.addEvent(DriveEvent(s.timeMs, type, s.odometerKm, speed, speedLimitKmh, peakRate = rateKmhPerSec, endMs = s.timeMs))
                        accelEpisodeMs = s.timeMs; lastStrongAccelMs = s.timeMs; important = true
                        requestLocation?.invoke()
                    }
                } else if (rateKmhPerSec >= HARSH_ACCEL_KMH_PER_SEC * EPISODE_END_RATIO) {
                    trip = trip.extendEpisode(accelOpen, rateKmhPerSec, s.timeMs)
                    lastStrongAccelMs = s.timeMs
                } else {
                    accelEpisodeMs = null
                }

                // Braking episode, same rule with 7.5 km/h/s.
                val decel = -rateKmhPerSec
                val brakeOpen = brakeEpisodeMs
                if (brakeOpen == null) {
                    if (decel >= HARSH_BRAKE_KMH_PER_SEC) {
                        val type = if (speed < STOPPED_KMH) DriveEvent.Type.SUDDEN_STOP else DriveEvent.Type.HARSH_BRAKE
                        trip = trip.addEvent(DriveEvent(s.timeMs, type, s.odometerKm, speed, speedLimitKmh, peakRate = decel, endMs = s.timeMs))
                        brakeEpisodeMs = s.timeMs; important = true
                        requestLocation?.invoke()
                        pendingBrakeEventMs = if (type == DriveEvent.Type.HARSH_BRAKE) s.timeMs else null
                        lastStrongBrakeMs = s.timeMs
                    }
                } else if (decel >= HARSH_BRAKE_KMH_PER_SEC * EPISODE_END_RATIO) {
                    trip = trip.extendEpisode(brakeOpen, decel, s.timeMs)
                    lastStrongBrakeMs = s.timeMs
                } else {
                    brakeEpisodeMs = null
                }
            }

        }

        if (speed != null && changed) {
            // Stop count: reached moving speed, then came to a stop (even through 1-5 km/h steps).
            if (speed >= MOVING_KMH) wasMoving = true
            if (wasMoving && speed < STOPPED_KMH) {
                wasMoving = false
                trip = trip.copy(stopCount = trip.stopCount + 1); important = true
                // A harsh deceleration that ended in a stop shortly after is a sudden stop.
                // (measured from the last moment of strong deceleration in that episode)
                val pending = pendingBrakeEventMs
                val strongUntil = lastStrongBrakeMs ?: pending
                if (pending != null && strongUntil != null && s.timeMs - strongUntil <= SUDDEN_STOP_WINDOW_MS) {
                    trip = trip.copy(events = trip.events.map {
                        if (it.timeMs == pending && it.type == DriveEvent.Type.HARSH_BRAKE) it.copy(type = DriveEvent.Type.SUDDEN_STOP) else it
                    })
                }
                pendingBrakeEventMs = null
            }

            // Location for distance: asked only while moving, at most every 15 s.
            if (speed >= MOVING_KMH && s.timeMs - lastLocationRequestMs > LOCATION_REQUEST_INTERVAL_MS) {
                lastLocationRequestMs = s.timeMs
                requestLocation?.invoke()
            }

            // Overspeed: speed limit + 20 km/h, one record per episode with its highest speed.
            val limit = speedLimitKmh
            val openEpisode = overspeedEventMs
            if (limit != null && speed >= limit + OVERSPEED_MARGIN_KMH) {
                if (openEpisode == null) {
                    // An old location (up to 15 s at speed) would put the mark hundreds of metres off.
                    val fresh = lastLocation.takeIf { s.timeMs - lastLocationMs <= FRESH_LOCATION_MS }
                    trip = trip.addEvent(
                        DriveEvent(s.timeMs, DriveEvent.Type.OVERSPEED, s.odometerKm, speed, limit, fresh, endMs = s.timeMs)
                    )
                    overspeedEventMs = s.timeMs; important = true
                    overspeedPeakMs = s.timeMs; overspeedLocationWanted = true
                    lastPeakLocationRequestMs = s.timeMs
                    requestLocation?.invoke()
                } else {
                    val ev = trip.events.lastOrNull { it.timeMs == openEpisode && it.type == DriveEvent.Type.OVERSPEED }
                    val newTop = ev != null && speed > (ev.speedKmh ?: 0f)
                    // Top speed, the limit at that moment and how long the episode has lasted.
                    trip = trip.copy(events = trip.events.map {
                        if (it === ev) it.copy(speedKmh = if (newTop) speed else it.speedKmh, limitKmh = if (newTop) limit else it.limitKmh, endMs = s.timeMs) else it
                    })
                    if (newTop) {
                        overspeedPeakMs = s.timeMs; overspeedLocationWanted = true
                        if (s.timeMs - lastPeakLocationRequestMs >= PEAK_LOCATION_REQUEST_MS) {
                            lastPeakLocationRequestMs = s.timeMs
                            requestLocation?.invoke()
                        }
                    }
                }
            } else if (openEpisode != null && (limit == null || speed < limit + OVERSPEED_MARGIN_KMH - OVERSPEED_HYSTERESIS_KMH)) {
                trip = trip.copy(events = trip.events.map {
                    if (it.timeMs == openEpisode && it.type == DriveEvent.Type.OVERSPEED) it.copy(endMs = s.timeMs) else it
                })
                overspeedEventMs = null
                important = true
            }

            // Idle: stopped while not in P.
            if (speed < STOPPED_KMH) {
                if (idleSinceMs == null) {
                    idleSinceMs = s.timeMs
                    idleLocation = null
                    requestLocation?.invoke() // where we stopped, in case it lasts a minute or more
                }
            } else {
                val closed = closeIdle(trip, s)
                if (closed != trip) { trip = closed; important = true }
            }
        }

        if (trip != open) {
            val persist = important || s.timeMs - lastSavedMs > SAVE_INTERVAL_MS
            mutate(persist) { book -> book.copy(trips = book.trips.dropLast(1) + trip) }
        } else if (s.timeMs - lastSavedMs > SAVE_INTERVAL_MS) {
            // Steady speed sends no changes, so nothing above saved. Save the progress anyway (with the
            // distance since the last speed change) so a restart does not lose this stretch. Memory keeps
            // the trip as it is: that distance is added there at the next speed change, not twice.
            val book = _logbook.value
            store.save(book.copy(trips = book.trips.dropLast(1) + trip.addDistance(lastSpeedKmh, s.timeMs)))
            lastSavedMs = s.timeMs
        }
    }

    private fun endTrip(s: VehicleSnapshot) {
        val open = _logbook.value.openTrip ?: return
        var trip = closeIdle(open, s).addDistance(lastSpeedKmh, s.timeMs)
        trip = trip.copy(
            endMs = s.timeMs,
            endOdometerKm = s.odometerKm, // only a value actually read (never copied from the start)
            tripMeterEndKm = s.tripMeterKm ?: trip.tripMeterStartKm,
            endBattery = s.batteryPercent ?: trip.startBattery,
        )
        val shortShift = trip.durationMs < MIN_TRIP_MS && trip.distanceKm < MIN_TRIP_KM
        // Standing in D/R/N without ever moving is not a trip, however long (stops during a real trip are kept).
        val neverMoved = trip.maxSpeedKmh < MOVING_KMH && trip.distanceKm < MIN_TRIP_KM
        if (shortShift || neverMoved) {
            mutate { book -> book.copy(trips = book.trips.dropLast(1)) }
        } else {
            mutate { book ->
                val trips = book.trips.dropLast(1) + trip
                // Routes take space: keep them for the most recent trips only.
                val keepFrom = trips.size - TRACK_KEEP_TRIPS
                book.copy(trips = trips.mapIndexed { i, t -> if (i < keepFrom && t.track.isNotEmpty()) t.copy(track = emptyList()) else t })
            }
        }
        // Everything up to here is accounted for (recorded or discarded as not a trip).
        val endOdo = trip.endOdometerKm
        if (endOdo != null && !trip.odometerImplausible) {
            mutate { it.copy(lastOdometerKm = endOdo, lastOdometerMs = s.timeMs) }
        }
        resetWorkingState(s)
        onDrivingChanged?.invoke(false)
    }

    /** Ends an idle period; counts it only when it lasted long enough. */
    private fun closeIdle(trip: Trip, s: VehicleSnapshot): Trip {
        val since = idleSinceMs ?: return trip
        idleSinceMs = null
        val seconds = (s.timeMs - since) / 1000
        if (seconds < IDLE_MIN_SEC) return trip
        val withIdle = trip.copy(idleSeconds = trip.idleSeconds + seconds)
        return if (seconds >= DWELL_MIN_SEC) {
            // Fallback only if the last location is from just before the car stopped (not from minutes before).
            // While moving, a location is asked every 15 s, so the slowing-down part is covered.
            val fallback = lastLocation.takeIf { lastLocationMs >= since - LOCATION_REQUEST_INTERVAL_MS }
            withIdle.copy(dwells = withIdle.dwells + Dwell(since, s.timeMs, idleLocation ?: fallback))
        } else withIdle
    }

    /**
     * Location from navigation: adds a route point and the location-based distance, and fills in
     * the location of recent events and of the current stop.
     */
    @Synchronized
    fun onLocation(location: String) {
        // No fix yet (0,0), out of range or unreadable text: ignore, or the map would span the globe.
        val valid = TrackPoint.parse(location)
        if (valid == null || (kotlin.math.abs(valid.first) < 0.01 && kotlin.math.abs(valid.second) < 0.01)) return
        lastLocation = location
        lastLocationMs = lastSnapshotMs
        val open = _logbook.value.openTrip ?: return
        var trip = open

        val point = parseLatLon(location)
        if (point != null) {
            val prev = lastPoint
            if (prev != null) {
                val km = haversineKm(prev, point)
                if (km in MIN_LOCATION_STEP_KM..MAX_LOCATION_STEP_KM) {
                    trip = trip.copy(locationDistanceKm = trip.locationDistanceKm + km.toFloat())
                }
            }
            lastPoint = point
            // Route point (same place again is skipped unless a minute has passed).
            val last = trip.track.lastOrNull()
            val samePlace = last != null && last.lat == point.first && last.lon == point.second
            if (last == null || !samePlace || lastSnapshotMs - last.timeMs >= 60_000) {
                trip = trip.copy(track = trip.track + TrackPoint(lastSnapshotMs, point.first, point.second, lastSpeedKmh, speedLimitKmh))
            }
        }

        if (idleSinceMs != null && idleLocation == null) idleLocation = location

        // Events from the last few seconds (vehicle clock) get the first location after them.
        // Overspeed gets the first location after its latest top speed (where the top speed was).
        val lastOverspeed = trip.events.lastOrNull { it.type == DriveEvent.Type.OVERSPEED }
        val overspeedHere = overspeedLocationWanted && lastSnapshotMs - overspeedPeakMs <= LOCATION_ATTACH_WINDOW_MS
        trip = trip.copy(events = trip.events.map {
            when {
                it === lastOverspeed && overspeedHere -> it.copy(location = location)
                lastSnapshotMs - it.timeMs > LOCATION_ATTACH_WINDOW_MS -> it
                it.location == null -> it.copy(location = location)
                else -> it
            }
        })
        if (overspeedHere) overspeedLocationWanted = false

        if (trip != open) {
            mutate(persist = trip.events != open.events) { book -> book.copy(trips = book.trips.dropLast(1) + trip) }
        }
    }

    /**
     * The odometer is ahead of the last value we accounted for: the car moved while nothing was
     * recorded. Saved as an estimate for the app only (reports never include it).
     */
    private fun checkMissedDistance(odometerKm: Float, timeMs: Long) {
        val book = _logbook.value
        val last = book.lastOdometerKm
        if (last == null || odometerKm < last) {
            // First reading ever, or the odometer went backwards (reset / different source): start from here.
            if (last != odometerKm) mutate { it.copy(lastOdometerKm = odometerKm, lastOdometerMs = timeMs) }
            return
        }
        val diff = odometerKm - last
        if (diff < MISSED_MIN_KM) return
        if (diff > MISSED_MAX_KM) {
            mutate { it.copy(lastOdometerKm = odometerKm, lastOdometerMs = timeMs) } // a reset, not driving
            return
        }
        // While the gear signal is missing the odometer creeps up 0.1 km at a time: extend the
        // previous gap instead of adding dozens of tiny ones.
        val prev = book.gaps.lastOrNull()
        val gaps = if (prev != null && prev.endOdometerKm == last && timeMs - prev.toMs <= GAP_MERGE_MS) {
            book.gaps.dropLast(1) + prev.copy(toMs = timeMs, endOdometerKm = odometerKm)
        } else {
            book.gaps + MissedGap(book.lastOdometerMs ?: timeMs, timeMs, last, odometerKm)
        }
        mutate { it.copy(gaps = gaps, lastOdometerKm = odometerKm, lastOdometerMs = timeMs) }
    }

    private fun parseLatLon(text: String): Pair<Double, Double>? {
        val parts = text.split(",")
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun haversineKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(a.first)) * kotlin.math.cos(Math.toRadians(b.first)) *
            kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    private fun Trip.addDistance(speedKmh: Float?, nowMs: Long): Trip {
        if (speedKmh == null || speedKmh <= 0f || lastSpeedChangeMs == 0L) return this
        val hours = (nowMs - lastSpeedChangeMs) / 3_600_000f
        if (hours <= 0f || hours > MAX_GAP_HOURS) return this // ignore gaps (e.g. process restart)
        return copy(speedDistanceKm = speedDistanceKm + speedKmh * hours)
    }

    private fun resetWorkingState(s: VehicleSnapshot) {
        lastSpeedKmh = s.speedKmh
        lastSpeedChangeMs = s.timeMs
        speedHistory.clear()
        idleSinceMs = null
        accelEpisodeMs = null
        brakeEpisodeMs = null
        lastStrongBrakeMs = null
        lastStrongAccelMs = null
        pendingBrakeEventMs = null
        overspeedEventMs = null
        overspeedLocationWanted = false
        wasMoving = (s.speedKmh ?: 0f) >= MOVING_KMH
        leftStandstillMs = null
        lastPoint = null
        lastLocationRequestMs = 0
        idleLocation = null
    }

    private fun Trip.addEvent(e: DriveEvent) = copy(events = events + e)

    /** Keep the strongest rate and the latest time of an open harsh accel/brake episode. */
    private fun Trip.extendEpisode(eventMs: Long, rate: Float, nowMs: Long) = copy(events = events.map {
        if (it.timeMs == eventMs && it.type != DriveEvent.Type.OVERSPEED)
            it.copy(peakRate = maxOf(it.peakRate ?: 0f, rate), endMs = nowMs) else it
    })

    // ---------------- safety signals ----------------

    private fun handleSafety(s: VehicleSnapshot) {
        val now = mapOf(
            SafetyEvent.Type.BRAKE_FLUID_LOW.name to s.brakeFluidLow,
            SafetyEvent.Type.WASHER_FLUID_LOW.name to s.washerFluidLow,
            SafetyEvent.Type.POWER_DOWN_WARNING.name to s.powerDownWarning,
            SafetyEvent.Type.AIRBAG_DEPLOYED.name to s.airbagDeployed,
        ).filterValues { it != null }.mapValues { it.value!! }

        val newEvents = now.mapNotNull { (key, active) ->
            val before = lastSafety[key] ?: lastSavedState(key) ?: false
            if (before == active) null else SafetyEvent(s.timeMs, SafetyEvent.Type.valueOf(key), active, s.odometerKm)
        }.toMutableList()
        lastSafety = lastSafety + now
        s.telltales?.let { newEvents += handleTelltales(s, it) }
        if (newEvents.isNotEmpty()) {
            mutate { it.copy(safetyEvents = (it.safetyEvents + newEvents).sortedBy { e -> e.timeMs }) }
        }
    }

    /** Lamps seen on but not yet on long enough to count: key -> (first seen on, odometer then). */
    private val pendingLamps = mutableMapOf<String, Pair<Long, Float?>>()

    /**
     * Warning lamps. Cars light every lamp for a few seconds at start-up as a bulb check, so a
     * lamp counts only after it has stayed on for [LAMP_CONFIRM_MS]; it is then logged with the
     * time and odometer of when it first came on. The snapshot lists the lamps that are on, so a
     * lamp known to be on and missing now has gone off.
     */
    private fun handleTelltales(s: VehicleSnapshot, codes: Set<String>): List<SafetyEvent> {
        val p = Warnings.TELLTALE_PREFIX
        val onNow = codes.map { p + it }.toSet()
        fun isOn(key: String) = (lastSafety[key] ?: lastSavedState(key)) == true
        fun lamp(t: Long, key: String, on: Boolean, odo: Float?) =
            SafetyEvent(t, SafetyEvent.Type.TELLTALE, on, odo, key.removePrefix(p))
        val out = mutableListOf<SafetyEvent>()

        for (key in onNow.filterNot(::isOn)) {
            val since = pendingLamps.getOrPut(key) { s.timeMs to s.odometerKm }
            if (s.timeMs - since.first >= LAMP_CONFIRM_MS) {
                out += lamp(since.first, key, true, since.second)
                pendingLamps.remove(key); lastSafety = lastSafety + (key to true)
            }
        }
        for (key in pendingLamps.keys - onNow) {
            val since = pendingLamps.remove(key)!!
            // Went off before anyone looked again: still a real warning if it lasted long enough.
            if (s.timeMs - since.first >= LAMP_CONFIRM_MS) {
                out += lamp(since.first, key, true, since.second)
                out += lamp(s.timeMs, key, false, s.odometerKm)
                lastSafety = lastSafety + (key to false)
            }
        }
        val knownOn = (lastSafety.keys + savedActiveKeys()).filter { it.startsWith(p) && isOn(it) }
        for (key in knownOn - onNow) {
            out += lamp(s.timeMs, key, false, s.odometerKm)
            lastSafety = lastSafety + (key to false)
        }
        return out
    }

    /** True while a lamp has been seen on but not yet counted (the vehicle side re-checks once). */
    fun hasPendingLamps() = pendingLamps.isNotEmpty()

    /** Last known state from the saved log, so a restart does not log the same warning twice. */
    private fun lastSavedState(key: String): Boolean? =
        _logbook.value.safetyEvents.lastOrNull { it.key == key }?.active

    private fun savedActiveKeys(): Set<String> =
        _logbook.value.safetyEvents.groupBy { it.key }.filterValues { it.last().active }.keys

    /** Warnings on right now (signals first, then lamps), as Warnings keys. */
    fun activeWarnings(): List<String> {
        val keys = (SafetyEvent.Type.entries.filter { it != SafetyEvent.Type.TELLTALE }.map { it.name } +
            (lastSafety.keys + savedActiveKeys()).filter { it.startsWith(Warnings.TELLTALE_PREFIX) }.sorted()).distinct()
        return keys.filter { (lastSafety[it] ?: lastSavedState(it)) == true }
    }

    // ---------------- user actions (parked screen) ----------------

    /** Clock for the time a person entered something (replaceable in tests). */
    var clock: () -> Long = System::currentTimeMillis

    /** Changes a human-entered value and keeps when it was entered; unchanged values are not logged. */
    private fun edit(tripId: Long, field: TripEdit.Field, value: String, apply: (Trip) -> Trip) = mutate { book ->
        book.copy(trips = book.trips.map { t ->
            if (t.id != tripId) return@map t
            val changed = apply(t)
            if (changed == t) t else changed.copy(edits = t.edits + TripEdit(clock(), field, value))
        })
    }

    @Synchronized
    fun setPurpose(tripId: Long, purpose: TripPurpose) =
        edit(tripId, TripEdit.Field.PURPOSE, purpose.label) { it.copy(purpose = purpose) }

    @Synchronized
    fun setMemo(tripId: Long, memo: String) {
        val v = memo.take(MEMO_MAX)
        edit(tripId, TripEdit.Field.MEMO, v) { it.copy(memo = v) }
    }

    @Synchronized
    fun setDriver(tripId: Long, driver: String) {
        val v = driver.trim().take(MEMO_MAX)
        edit(tripId, TripEdit.Field.DRIVER, v) { it.copy(driver = v) }
    }

    /** Adds a generated trip (marked as demo) so the map and reports can be tried without driving. */
    @Synchronized
    fun addDemoTrip(nowMs: Long) {
        if (_logbook.value.openTrip != null) return
        val trip = DemoTrip.build(nowMs, _logbook.value.lastOdometerKm ?: 1000f)
        mutate { it.copy(trips = it.trips + trip) }
    }

    @Synchronized
    fun dropOffForService(s: VehicleSnapshot) {
        if (_logbook.value.openVisit != null) return
        val visit = ServiceVisit(id = s.timeMs, dropOff = capture(s))
        mutate { it.copy(visits = it.visits + visit) }
    }

    @Synchronized
    fun pickUpFromService(s: VehicleSnapshot) {
        val open = _logbook.value.openVisit ?: return
        val done = open.copy(pickUp = capture(s))
        mutate { book -> book.copy(visits = book.visits.map { if (it.id == done.id) done else it }) }
    }

    private fun capture(s: VehicleSnapshot) = StateCapture(
        timeMs = System.currentTimeMillis(),
        odometerKm = s.odometerKm,
        batteryPercent = s.batteryPercent,
        activeWarnings = activeWarnings(),
    )

    /**
     * The default user applies to trips that do not name a driver. When it changes, earlier trips
     * keep the person who was the default then (the logbook must not rewrite who drove in the past).
     */
    @Synchronized
    fun updateProfile(profile: VehicleProfile) = mutate { book ->
        val old = book.profile.defaultUser
        val trips = if (old.isNotBlank() && old != profile.defaultUser) {
            book.trips.map { if (it.driver.isBlank() && !it.isOpen) it.copy(driver = old) else it }
        } else book.trips
        book.copy(profile = profile, trips = trips)
    }

    /** Fills the registration number from the car if the user has not typed one. */
    @Synchronized
    fun fillPlateFromCar(raw: String) {
        val plate = CarPlate.normalize(raw) ?: return
        if (_logbook.value.profile.plateNumber.isNotBlank()) return
        mutate { it.copy(profile = it.profile.copy(plateNumber = plate)) }
    }

    init {
        // Earlier versions copied the emulator's placeholder ("invalid") into the profile; clear it.
        val p = _logbook.value.profile.plateNumber
        if (p.trim().trim('"', '“', '”').equals("invalid", ignoreCase = true)) {
            mutate { it.copy(profile = it.profile.copy(plateNumber = "")) }
        }
    }

    /** Clears records but keeps the vehicle/company profile. */
    @Synchronized
    fun clearAll() {
        // Forget what was on, so lamps still on are logged again into the new, empty log.
        lastSafety = emptyMap(); pendingLamps.clear()
        mutate { Logbook(profile = it.profile) }
    }

    // ---------------- persistence ----------------

    private fun mutate(persist: Boolean = true, change: (Logbook) -> Logbook) {
        val next = change(_logbook.value)
        _logbook.value = next
        if (persist) {
            store.save(next)
            // Same clock as the snapshots it is compared with (the vehicle signal time).
            lastSavedMs = if (lastSnapshotMs > 0) lastSnapshotMs else System.currentTimeMillis()
        }
    }

    companion object {
        // Risky-driving criteria of the Korea Transportation Safety Authority's operation record
        // analysis system, as cited in published research (KoreaScience JAKO201502150666203).
        const val HARSH_ACCEL_KMH_PER_SEC = 11f
        const val HARSH_BRAKE_KMH_PER_SEC = 7.5f
        const val QUICK_START_FROM_KMH = 5f
        const val QUICK_START_WINDOW_MS = 3_000L
        const val SUDDEN_STOP_WINDOW_MS = 3_000L
        const val OVERSPEED_MARGIN_KMH = 20
        const val OVERSPEED_HYSTERESIS_KMH = 3
        const val LOCATION_ATTACH_WINDOW_MS = 10_000L
        /** A location older than this is not used for a new mark (at 100 km/h, 3 s ≈ 80 m). */
        const val FRESH_LOCATION_MS = 3_000L
        /** Odometer step (0.1 km) plus a little lag, when tying a late odometer to the last trip's end. */
        const val LATE_START_TOLERANCE_KM = 0.3f
        /** While speeding up past the limit, ask for the top-speed location at most this often. */
        const val PEAK_LOCATION_REQUEST_MS = 2_000L
        const val LOCATION_REQUEST_INTERVAL_MS = 15_000L
        const val MIN_LOCATION_STEP_KM = 0.005  // ignore GPS jitter
        const val MAX_LOCATION_STEP_KM = 2.0    // ignore jumps (lost signal, tunnel)
        /** Start-up bulb check lights every lamp for a few seconds; shorter than this is ignored. */
        const val LAMP_CONFIRM_MS = 10_000L
        const val EPISODE_END_RATIO = 0.7f
        const val EPISODE_GAP_MS = 2_000L
        const val RATE_WINDOW_MS = 1_500L
        const val MIN_RATE_SEC = 0.5f

        const val MOVING_KMH = 5f
        const val STOPPED_KMH = 1f
        // "정차 대기 시간": every stop while not in P, ignoring very short stops (no regulatory basis claimed).
        const val IDLE_MIN_SEC = 5L
        // Routine progress (distance, route points) is written at most every 30 s; events, stops,
        // trip start/end are written immediately. Fewer full-file writes while driving.
        const val SAVE_INTERVAL_MS = 30_000L
        const val MIN_TRIP_MS = 10_000L
        const val MIN_TRIP_KM = 0.05f
        const val MAX_GAP_HOURS = 0.5f
        const val MEMO_MAX = 60
        const val DWELL_MIN_SEC = 60L        // stops shown on the route map
        const val TRACK_KEEP_TRIPS = 20      // routes kept for the most recent trips only
        const val MISSED_MIN_KM = 0.1f       // odometer resolution margin
        const val MISSED_MAX_KM = 3_000f     // larger jumps are treated as a reset, not driving
        const val GAP_MERGE_MS = 30 * 60_000L // gaps closer than this are one stretch of unrecorded driving
    }
}
