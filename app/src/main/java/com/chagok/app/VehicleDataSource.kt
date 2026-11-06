package com.chagok.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.PowerDownWarningIndicatorListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Status of one signal, for the "데이터 확인" screen. */
data class Signal(
    val value: String = "-",
    val status: Status = Status.WAITING,
    val detail: String = "",
) {
    enum class Status { WAITING, OK, UNSUPPORTED, ERROR }
}

/** Every signal the app reads. */
enum class Field(val label: String, val source: String) {
    SPEED("차속", "AAOS"),
    GEAR("기어", "AAOS"),
    ODOMETER("누적 주행거리", "Pleos"),
    TRIP_METER("차량 트립 미터", "Pleos"),
    BATTERY("배터리 잔량", "Pleos"),
    BRAKE("브레이크 페달", "Pleos"),
    STEERING("조향각", "Pleos"),
    BRAKE_FLUID("브레이크액", "Pleos"),
    WASHER_FLUID("워셔액", "Pleos"),
    POWER_DOWN("전원 차단 경고", "Pleos"),
    AIRBAG("에어백 전개(운전석)", "Pleos"),
    TELLTALES("경고등", "Pleos"),
    SPEED_LIMIT("도로 제한속도", "Navi"),
    PLATE("자동차등록번호", "Pleos"),
}

/**
 * Collects vehicle signals from two sources:
 *  - Pleos Vehicle SDK: odometer, EV battery, brake pedal, steering, safety signals
 *  - Android Automotive car API: vehicle speed, gear (the SDK says to use GEAR_SELECTION)
 *
 * Change listeners only, no polling (Pleos platform guide).
 */
class VehicleDataSource(private val context: Context) {

    private val _snapshot = MutableStateFlow(VehicleSnapshot())

    private val _plate = MutableStateFlow<String?>(null)
    /** Registration number from the car (CarInfo CRN), used to fill the logbook header. */
    val plate: kotlinx.coroutines.flow.StateFlow<String?> = _plate
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot

    private val _signals = MutableStateFlow(Field.entries.associateWith { Signal() })
    val signals: StateFlow<Map<Field, Signal>> = _signals

    // ---------------- Pleos Vehicle SDK ----------------

    private val vehicle = Vehicle(context.applicationContext)
    private var pleosStarted = false
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) = onOdometer(value)
        override fun onFailed(e: Exception) = fail(Field.ODOMETER, e)
    }
    private val tripMeterListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) = onTripMeter(value)
        override fun onFailed(e: Exception) = fail(Field.TRIP_METER, e)
    }
    private val batteryListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) = onBattery(value)
        override fun onFailed(e: Exception) = fail(Field.BATTERY, e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = onBrake(status)
        override fun onFailed(e: Exception) = fail(Field.BRAKE, e)
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = onSteering(angle)
        override fun onFailed(e: Exception) = fail(Field.STEERING, e)
    }
    private val brakeFluidListener = object : OilLevelStateListener {
        override fun onOilLevelUpdated(level: OilLevelState?) = onBrakeFluid(level)
        override fun onFailed(e: Exception) = fail(Field.BRAKE_FLUID, e)
    }
    private val washerListener = object : LowWasherFluidWaringListener {
        override fun onLowWasherFluidWaringUpdated(state: Boolean?) = onWasher(state)
        override fun onFailed(e: Exception) = fail(Field.WASHER_FLUID, e)
    }
    private val powerDownListener = object : PowerDownWarningIndicatorListener {
        override fun onPowerDownWarningIndicatorUpdated(state: Boolean?) = onPowerDown(state)
        override fun onFailed(e: Exception) = fail(Field.POWER_DOWN, e)
    }
    private val airbagListener = object : SeatAirbagDeployedListener {
        override fun onSeatAirbagDeployUpdated(deployed: Boolean?) = onAirbag(deployed)
        override fun onFailed(e: Exception) = fail(Field.AIRBAG, e)
    }

    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(info: Telltales?) = onTelltales(info)
        override fun onFailed(e: Exception) = fail(Field.TELLTALES, e)
    }

    fun startPleos() {
        if (pleosStarted) return
        try {
            // The SDK queues requests until the vehicle service connects.
            vehicle.initialize()
            pleosStarted = true
        } catch (e: Exception) {
            Field.entries.filter { it.source == "Pleos" }.forEach { fail(it, e) }
            return
        }
        // Each signal is started separately so one unsupported signal cannot stop the others.
        guard(Field.PLATE) {
            vehicle.getCarInfo().getCRNInfo({ v ->
                val plate = CarPlate.normalize(v)
                _plate.value = plate
                if (plate != null) ok(Field.PLATE, plate)
                // Placeholder such as "invalid": not a number, so nothing is filled in.
                else setSignal(Field.PLATE, Signal("확인 불가", Signal.Status.UNSUPPORTED, "차량이 보낸 값: ${v ?: "없음"} (기본정보에 직접 입력)"))
            }) { fail(Field.PLATE, it) }
        }
        guard(Field.ODOMETER) {
            val api = vehicle.getOdometer()
            api.getCurrentDistanceDriven(::onOdometer) { fail(Field.ODOMETER, it) }
            api.registerDistanceDriven(odometerListener)
        }
        guard(Field.TRIP_METER) {
            val api = vehicle.getOdometer()
            api.getTripCurrentDrivingDistance(::onTripMeter) { fail(Field.TRIP_METER, it) }
            api.registerTripCurrentDrivingDistance(tripMeterListener)
        }
        guard(Field.BATTERY) {
            val api = vehicle.getEvBattery()
            api.getEvBatteryStateOfCharge(::onBattery) { fail(Field.BATTERY, it) }
            api.registerEvBatteryStateOfCharge(batteryListener)
        }
        guard(Field.BRAKE) {
            val api = vehicle.getBrake()
            api.getCurrentBrakePedalStatus(::onBrake) { fail(Field.BRAKE, it) }
            api.registerBrakePedalStatus(brakeListener)
        }
        guard(Field.STEERING) {
            val api = vehicle.getSteeringWheel()
            api.getCurrentSteeringWheelAngle(::onSteering) { fail(Field.STEERING, it) }
            api.registerSteeringWheelAngle(steeringListener)
        }
        val safety = vehicle.getSafety()
        guard(Field.BRAKE_FLUID) {
            safety.getBrakeFluidLevelState(::onBrakeFluid) { fail(Field.BRAKE_FLUID, it) }
            safety.registerBrakeFluidLevelState(brakeFluidListener)
        }
        guard(Field.WASHER_FLUID) {
            safety.hasLowWasherFluidWarning(::onWasher) { fail(Field.WASHER_FLUID, it) }
            safety.registerLowWasherFluidWaringState(washerListener)
        }
        guard(Field.POWER_DOWN) {
            safety.hasPowerDownWarningIndicator(::onPowerDown) { fail(Field.POWER_DOWN, it) }
            safety.registerPowerDownWarningIndicator(powerDownListener)
        }
        guard(Field.AIRBAG) {
            safety.hasSeatAirbagBeenDeployed(driverSeat, ::onAirbag) { fail(Field.AIRBAG, it) }
            safety.registerSeatAirbagDeployed(driverSeat, airbagListener)
        }
        // Warning lamps need PRIVATE_CAR_SAFETY; without it this shows as unsupported and the rest keeps working.
        guard(Field.TELLTALES) {
            safety.getTelltalesInfo(::onTelltales) { fail(Field.TELLTALES, it) }
            safety.registerTelltales(telltaleListener)
        }
    }

    private fun onOdometer(v: Float?) {
        _snapshot.update { it.copy(timeMs = now(), odometerKm = v ?: it.odometerKm) }
        ok(Field.ODOMETER, v?.let { String.format(Locale.US, "%.1f km", it) })
    }

    /** The SDK reports the trip meter in meters. */
    private fun onTripMeter(meters: Float?) {
        val km = meters?.div(1000f)
        _snapshot.update { it.copy(timeMs = now(), tripMeterKm = km ?: it.tripMeterKm) }
        ok(Field.TRIP_METER, km?.let { String.format(Locale.US, "%.2f km", it) })
    }

    private fun onBattery(v: Int?) {
        _snapshot.update { it.copy(timeMs = now(), batteryPercent = v ?: it.batteryPercent) }
        ok(Field.BATTERY, v?.let { "$it %" })
    }

    private fun onBrake(v: Boolean?) {
        _snapshot.update { it.copy(timeMs = now(), brakePressed = v) }
        ok(Field.BRAKE, v?.let { if (it) "밟음" else "뗌" })
    }

    private fun onSteering(v: Float?) {
        _snapshot.update { it.copy(timeMs = now(), steeringDeg = v) }
        ok(Field.STEERING, v?.let { String.format(Locale.US, "%.0f°", it) })
    }

    private fun onBrakeFluid(level: OilLevelState?) {
        val low = level?.let { it == OilLevelState.LOW || it == OilLevelState.CRITICALLY_LOW }
        _snapshot.update { it.copy(timeMs = now(), brakeFluidLow = low) }
        ok(Field.BRAKE_FLUID, level?.name)
    }

    private fun onWasher(v: Boolean?) {
        _snapshot.update { it.copy(timeMs = now(), washerFluidLow = v) }
        ok(Field.WASHER_FLUID, v?.let { if (it) "부족" else "정상" })
    }

    private fun onPowerDown(v: Boolean?) {
        _snapshot.update { it.copy(timeMs = now(), powerDownWarning = v) }
        ok(Field.POWER_DOWN, v?.let { if (it) "경고" else "정상" })
    }

    private fun onAirbag(v: Boolean?) {
        _snapshot.update { it.copy(timeMs = now(), airbagDeployed = v) }
        ok(Field.AIRBAG, v?.let { if (it) "전개됨" else "정상" })
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lampRecheck = Runnable { _snapshot.update { it.copy(timeMs = now()) } }

    /** Keeps only the lamps that mean a fault or needed service (see [Telltale]). */
    private fun onTelltales(info: Telltales?) {
        val list = info?.telltales ?: return
        val on = list.filter { it.state != TelltaleState.OFF }
            .filter { Telltale.accept(it.type.name, blinking = it.state == TelltaleState.BLINK) }
            .map { it.type.name }.toSet()
        _snapshot.update { it.copy(timeMs = now(), telltales = on) }
        // Short value on the right (a long list there squeezes the row); lamp names go underneath.
        setSignal(Field.TELLTALES, Signal(if (on.isEmpty()) "없음" else "${on.size}개 켜짐", Signal.Status.OK,
            on.map { Telltale.label(it) }.sorted().joinToString()))
        // A lamp counts only after staying on for a while (start-up bulb check). Parked, nothing else
        // may change, so look once more after that time. One delayed check, not polling.
        mainHandler.removeCallbacks(lampRecheck)
        if (on.isNotEmpty()) mainHandler.postDelayed(lampRecheck, TripRecorder.LAMP_CONFIRM_MS + 1_000)
        if (ChagokApp.verbose) Log.d(TAG, "경고등 수신 ${list.size}개 중 기록 대상 켜짐: $on")
    }

    // ---------------- Android Automotive car API ----------------

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private val lastGearRaw = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private val carCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val metersPerSecond = (value.value as? Float) ?: return
                    val kmh = metersPerSecond * 3.6f
                    _snapshot.update { it.copy(timeMs = now(), speedKmh = kmh) }
                    ok(Field.SPEED, String.format(Locale.US, "%.0f km/h", kmh))
                }
                // Vehicles (and the emulator console) may publish the selected gear, the actual gear,
                // or both. Whichever of the two changed last is the gear we use.
                VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR -> {
                    val raw = (value.value as? Int) ?: return
                    if (lastGearRaw[value.propertyId] == raw) return
                    lastGearRaw[value.propertyId] = raw
                    val name = if (value.propertyId == VehiclePropertyIds.CURRENT_GEAR) "CURRENT_GEAR" else "GEAR_SELECTION"
                    if (ChagokApp.verbose) Log.i(TAG, "$name = $raw")
                    val gear = toGear(raw)
                    _snapshot.update { it.copy(timeMs = now(), gear = gear) }
                    ok(Field.GEAR, if (gear == Gear.OTHER) "기타($raw)" else gear.label)
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            val field = if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) Field.SPEED else Field.GEAR
            fail(field, Exception("property error (area $areaId)"))
        }
    }

    /** Call once the CAR_SPEED runtime permission has been answered. */
    fun startCarApi(speedPermissionGranted: Boolean) {
        if (propertyManager != null) {
            // Already running for gear; add speed if permission arrived later.
            if (speedPermissionGranted) register(propertyManager!!, VehiclePropertyIds.PERF_VEHICLE_SPEED, Field.SPEED)
            return
        }
        try {
            val newCar = Car.createCar(context.applicationContext)
            car = newCar
            val manager = newCar.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = manager
            register(manager, VehiclePropertyIds.GEAR_SELECTION, Field.GEAR)
            registerOptional(manager, VehiclePropertyIds.CURRENT_GEAR)
            if (speedPermissionGranted) {
                register(manager, VehiclePropertyIds.PERF_VEHICLE_SPEED, Field.SPEED)
            } else {
                fail(Field.SPEED, Exception("차속 권한(CAR_SPEED)이 허용되지 않음"))
            }
        } catch (e: Exception) {
            fail(Field.SPEED, e)
            fail(Field.GEAR, e)
        }
    }

    private fun register(manager: CarPropertyManager, propertyId: Int, field: Field) {
        try {
            val registered = manager.registerCallback(
                carCallback, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE
            )
            if (!registered) fail(field, Exception("registerCallback returned false"))
        } catch (e: Exception) {
            fail(field, e)
        }
    }

    /** Second source for the same field: if it is missing, the first one still works. */
    private fun registerOptional(manager: CarPropertyManager, propertyId: Int) {
        try {
            if (!manager.registerCallback(carCallback, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                Log.i(TAG, "optional property $propertyId not registered")
            }
        } catch (e: Exception) {
            Log.i(TAG, "optional property $propertyId unavailable: ${e.message}")
        }
    }

    private fun toGear(raw: Int): Gear = when (raw) {
        GEAR_PARK -> Gear.P
        GEAR_REVERSE -> Gear.R
        GEAR_NEUTRAL -> Gear.N
        GEAR_DRIVE -> Gear.D
        else -> if (raw >= GEAR_FIRST) Gear.D else Gear.OTHER // manual gears count as driving
    }

    // ---------------- helpers ----------------

    private inline fun guard(field: Field, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail(field, e)
        }
    }

    private fun ok(field: Field, value: String?) =
        setSignal(field, Signal(value ?: "값 없음", Signal.Status.OK))

    private fun fail(field: Field, e: Exception) {
        Log.w(TAG, "$field failed", e)
        val message = e.message ?: e.javaClass.simpleName
        // The vehicle does not have this signal at all: not an error, just not available.
        // e.g. "... not supported", "ERROR_NOT_SUPPORTED_PROPERTY", "not in carPropertyConfig"
        val unsupported = UNSUPPORTED_PATTERN.containsMatchIn(message)
        setSignal(
            field,
            if (unsupported) Signal("미지원", Signal.Status.UNSUPPORTED, "이 차량에서 제공하지 않는 신호")
            else Signal("오류", Signal.Status.ERROR, message)
        )
    }

    private val UNSUPPORTED_PATTERN = Regex("not[ _]supported|not in carPropertyConfig", RegexOption.IGNORE_CASE)

    private fun setSignal(field: Field, signal: Signal) = _signals.update { it + (field to signal) }

    /** Lets other sources (navigation) show their status on the data check screen. */
    fun reportExternal(field: Field, signal: Signal) = setSignal(field, signal)

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "Chagok"

        // android.car.VehicleGear values
        private const val GEAR_NEUTRAL = 0x0001
        private const val GEAR_REVERSE = 0x0002
        private const val GEAR_PARK = 0x0004
        private const val GEAR_DRIVE = 0x0008
        private const val GEAR_FIRST = 0x0010
    }
}
