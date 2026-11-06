package com.chagok.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Driving (gear not P): a single quiet "recording" card. No touch targets, nothing to read.
 * Parked (gear P): home, trips, reports, service, data check.
 */
class MainActivity : ComponentActivity() {

    private val app get() = application as ChagokApp

    private val speedPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> app.dataSource.startCarApi(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.startRecorderService("앱 열림")  // allowed while on screen; keeps recording after leaving
        if (!app.hasSpeedPermission()) speedPermission.launch(ChagokApp.CAR_SPEED)

        setContent {
            // Car screens are read from further away: every text size is scaled up by TEXT_SCALE.
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, base.fontScale * TEXT_SCALE),
                // Release-build fix: lifecycle 2.9 finds Compose UI 1.6's lifecycle owner by reflection,
                // which R8 renames away ("CompositionLocal LocalLifecycleOwner not present").
                // Providing it directly needs no reflection.
                androidx.lifecycle.compose.LocalLifecycleOwner provides this@MainActivity,
            ) {
            MaterialTheme(typography = readableTypography()) {
                // Screen updates stop while the app is not visible (Pleos guide); recording continues.
                val snapshot by app.dataSource.snapshot.collectAsStateWithLifecycle()
                val book by app.recorder.logbook.collectAsStateWithLifecycle()
                val signals by app.dataSource.signals.collectAsStateWithLifecycle()

                val driving = snapshot.gear != null && snapshot.gear != Gear.P
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.bg) {
                    if (driving) {
                        DrivingScreen(snapshot, book.openTrip)
                    } else {
                        ParkedScreen(
                            snapshot = snapshot,
                            book = book,
                            signals = signals,
                            onPurpose = { id, p -> app.recorder.setPurpose(id, p) },
                            onMemo = { id, m -> app.recorder.setMemo(id, m) },
                            onDriver = { id, d -> app.recorder.setDriver(id, d) },
                            onDemo = { app.recorder.addDemoTrip(System.currentTimeMillis()) },
                            onDropOff = { app.recorder.dropOffForService(snapshot) },
                            onPickUp = { app.recorder.pickUpFromService(snapshot) },
                            onAskSpeed = { speedPermission.launch(ChagokApp.CAR_SPEED) },
                            onClear = { app.recorder.clearAll() },
                            onProfile = { app.recorder.updateProfile(it) },
                        )
                    }
                }
            }
            }
        }
    }

    companion object {
        /** Text size multiplier for the whole app (1.0 = Android default). */
        const val TEXT_SCALE = 1.49f // 1.35 + about 10%
    }
}

/**
 * Pleos design guide: general text at least 3 mm tall, key information (speed, warnings) 4 mm.
 * With TEXT_SCALE 1.35 at standard density (1 sp ≈ 0.159 mm, Hangul ≈ 0.9 of the font size):
 * 16 sp → ≈ 3.1 mm, 21 sp → ≈ 4.1 mm. So app text is 16 sp or more, warnings 21 sp or more,
 * and the Material styles used inside text fields are raised to 16 sp.
 */
private fun readableTypography(): androidx.compose.material3.Typography {
    val base = androidx.compose.material3.Typography()
    fun androidx.compose.ui.text.TextStyle.atLeast16() = if (fontSize.value < 16f) copy(fontSize = 16.sp, lineHeight = 22.sp) else this
    return base.copy(
        bodySmall = base.bodySmall.atLeast16(),
        bodyMedium = base.bodyMedium.atLeast16(),
        labelSmall = base.labelSmall.atLeast16(),
        labelMedium = base.labelMedium.atLeast16(),
        labelLarge = base.labelLarge.atLeast16(),
    )
}

private object Palette {
    val bg = Color(0xFFF3F4F6)
    val card = Color.White
    val ink = Color(0xFF111827)
    val sub = Color(0xFF6B7280)
    val accent = Color(0xFF0F766E)
    val accentSoft = Color(0xFFCCFBF1)
    val warn = Color(0xFFB91C1C)
    val ok = Color(0xFF15803D)
}

// ======================= driving =======================

@Composable
private fun DrivingScreen(s: VehicleSnapshot, trip: Trip?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(28.dp).background(Palette.warn, CircleShape))
            Spacer(Modifier.height(24.dp))
            Text("운행 기록 중", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
            Spacer(Modifier.height(12.dp))
            // Live distance: same priority as saved trips (odometer, trip meter, location, speed).
            val km = trip?.copy(endOdometerKm = s.odometerKm, tripMeterEndKm = s.tripMeterKm)?.distanceKm
            Text(
                listOfNotNull(
                    km?.let { "%.1f km".format(it) },
                    trip?.let { ReportBuilder.duration(it.durationMs) },
                ).joinToString(" · ").ifEmpty { "시작" },
                fontSize = 24.sp, color = Palette.sub,
            )
            Spacer(Modifier.height(32.dp))
            Text("주행 중에는 화면을 보지 않아도 됩니다.\n주차(P)하면 기록을 볼 수 있어요.",
                fontSize = 18.sp, color = Palette.sub, textAlign = TextAlign.Center)
        }
    }
}

// ======================= parked =======================

private enum class Tab(val label: String) { HOME("홈"), TRIPS("운행 기록"), REPORTS("보고서"), SERVICE("정비"), DATA("데이터 확인") }

@Composable
private fun ParkedScreen(
    snapshot: VehicleSnapshot,
    book: Logbook,
    signals: Map<Field, Signal>,
    onPurpose: (Long, TripPurpose) -> Unit,
    onMemo: (Long, String) -> Unit,
    onDriver: (Long, String) -> Unit,
    onDemo: () -> Unit,
    onDropOff: () -> Unit,
    onPickUp: () -> Unit,
    onAskSpeed: () -> Unit,
    onClear: () -> Unit,
    onProfile: (VehicleProfile) -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var detail by remember { mutableStateOf<Pair<Long, TripView>?>(null) }  // trip id + which screen
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("차곡", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
            Spacer(Modifier.width(12.dp))
            Text("차가 스스로 쌓는 운행 기록", fontSize = 16.sp, color = Palette.sub)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A tab also closes the trip detail screen.
            Tab.entries.forEach { t -> Chip(t.label, selected = t == tab) { tab = t; detail = null } }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val open = detail?.let { (id, view) -> book.closedTrips.firstOrNull { it.id == id }?.let { it to view } }
            val openTrip: (Long, TripView) -> Unit = { id, view -> detail = id to view }
            if (open != null) {
                when (open.second) {
                    TripView.DETAIL -> RouteScreen(open.first) { detail = null }
                    TripView.REPORT -> TripReportScreen(open.first) { detail = null }
                    TripView.MEMO -> MemoScreen(open.first, onMemo) { detail = null; tab = Tab.TRIPS }
                }
            } else when (tab) {
                Tab.HOME -> HomeTab(snapshot, book, TripActions(onPurpose, onMemo, openTrip)) { tab = Tab.TRIPS }
                Tab.TRIPS -> TripsTab(book, TripActions(onPurpose, onMemo, openTrip))
                Tab.REPORTS -> ReportsTab(book, snapshot, onProfile, onDriver)
                Tab.SERVICE -> ServiceTab(book, snapshot, onDropOff, onPickUp)
                Tab.DATA -> DataTab(signals, onAskSpeed, onClear, onDemo)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeTab(s: VehicleSnapshot, book: Logbook, actions: TripActions, onGoTrips: () -> Unit) {
    Card {
        Text("지금 차 상태", fontSize = 18.sp, color = Palette.sub)
        Spacer(Modifier.height(8.dp))
        KeyValue("기어", s.gear?.label ?: "확인 중")
        KeyValue("누적 주행거리", s.odometerKm?.let { "%.1f km".format(it) } ?: "-")
        KeyValue("배터리", s.batteryPercent?.let { "$it %" } ?: "-")
        if (book.openVisit != null) {
            Spacer(Modifier.height(8.dp))
            Text("정비 맡김 상태입니다. 차를 찾으면 정비 탭에서 '찾음'을 눌러주세요.", color = Palette.accent, fontSize = 16.sp)
        }
    }

    // Logbook reminder: trips with no purpose, business trips with no visit purpose.
    val real = book.closedTrips.filter { !it.demo }
    val unset = real.count { it.purpose == TripPurpose.UNSET }
    val noMemo = real.count { it.needsMemo }
    if (unset + noMemo > 0) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFFFEF3C7), RoundedCornerShape(20.dp)).padding(24.dp)
        ) {
            Text("운행기록부에 빠진 정보가 있어요", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
            Spacer(Modifier.height(6.dp))
            Text(listOfNotNull(
                if (unset > 0) "목적을 정하지 않은 운행 ${unset}건" else null,
                if (noMemo > 0) "방문 목적을 안 적은 업무 운행 ${noMemo}건" else null,
            ).joinToString(" · "), fontSize = 17.sp, color = Color(0xFF92400E))
            Spacer(Modifier.height(4.dp))
            Text("업무 운행은 방문 목적이 있어야 업무용으로 인정받기 쉬워요.", fontSize = 16.sp, color = Color(0xFFB45309))
            Spacer(Modifier.height(12.dp))
            ActionButton("운행 기록에서 채우기", onClick = onGoTrips)
        }
    }

    val last = book.closedTrips.lastOrNull()
    if (last == null) {
        Card { Text("아직 기록된 운행이 없어요. 기어를 D로 바꾸면 자동으로 기록이 시작됩니다.", fontSize = 18.sp, color = Palette.sub) }
    } else {
        TripCard(last, "최근 운행", actions)
    }

    // Rebuilt only when the logbook changes (not on every speed/battery update).
    val week = remember(book) { ReportBuilder.build(ReportType.WEEKLY, book, s) }
    Card {
        Text("이번 주", fontSize = 18.sp, color = Palette.sub)
        Spacer(Modifier.height(8.dp))
        week.sections.first().rows.take(3).forEach { KeyValue(it.first, it.second) }
    }
}

@Composable
private fun TripsTab(book: Logbook, actions: TripActions) {
    val trips = book.closedTrips.reversed()
    // Driving the odometer shows but nothing recorded: estimate only, never in reports.
    book.gaps.takeLast(5).reversed().forEach { g ->
        Card {
            Text("기록되지 않은 운행 (추정)", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Palette.warn)
            Spacer(Modifier.height(6.dp))
            Text("${ReportBuilder.dateTime(g.fromMs)} ~ ${ReportBuilder.dateTime(g.toMs)} · 약 ${"%.1f".format(g.km)} km (계기판 차이)",
                fontSize = 18.sp, color = Palette.ink)
            Spacer(Modifier.height(6.dp))
            Text("앱이 꺼져 있었거나 신호를 놓친 동안 움직인 거리로 보여요. 추정값이라 보고서에는 넣지 않았어요. " +
                "필요하면 보고서에 직접 기재하세요.", fontSize = 16.sp, color = Palette.sub)
        }
    }
    if (trips.isEmpty()) {
        Card { Text("기록된 운행이 없어요.", fontSize = 18.sp, color = Palette.sub) }
        return
    }
    trips.take(50).forEach { trip -> TripCard(trip, null, actions) }
}

private enum class TripView { DETAIL, REPORT, MEMO }

private class TripActions(
    val onPurpose: (Long, TripPurpose) -> Unit,
    val onMemo: (Long, String) -> Unit,
    val onOpen: (Long, TripView) -> Unit,
)

/**
 * One trip: summary on the left, purpose on the right (stacked on narrow windows),
 * and the two ways in (상세 보기 / 보고서) as outlined buttons under a divider.
 */
@Composable
private fun TripCard(t: Trip, header: String?, actions: TripActions) {
    Card {
        if (header != null) {
            Text(header, fontSize = 18.sp, color = Palette.sub)
            Spacer(Modifier.height(8.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) { TripSummary(t); VisitPurposeLine(t, actions) }
                    Spacer(Modifier.width(24.dp))
                    // Fixed width: the summary keeps the rest (a fill-width child here squeezed it to 0).
                    PurposeBox(t, actions, Modifier.width(400.dp))
                }
            } else {
                Column {
                    TripSummary(t)
                    VisitPurposeLine(t, actions)
                    Spacer(Modifier.height(16.dp))
                    PurposeBox(t, actions, Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB)))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("상세 보기", Modifier.weight(1f)) { actions.onOpen(t.id, TripView.DETAIL) }
            ActionButton("보고서", Modifier.weight(1f)) { actions.onOpen(t.id, TripView.REPORT) }
        }
    }
}

@Composable
private fun PurposeBox(t: Trip, actions: TripActions, modifier: Modifier) {
    Column(
        modifier.background(Color(0xFFF9FAFB), RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Text(
            if (t.purpose == TripPurpose.UNSET) "운행 목적 · 선택해 주세요" else "운행 목적",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (t.purpose == TripPurpose.UNSET) Palette.warn else Palette.sub,
        )
        Spacer(Modifier.height(8.dp))
        // Choosing 업무 opens the visit-purpose page right away.
        PurposePicker(t) { id, p ->
            actions.onPurpose(id, p)
            if (p == TripPurpose.BUSINESS && t.purpose != TripPurpose.BUSINESS) actions.onOpen(id, TripView.MEMO)
        }
    }
}

/** Business trips: the visit purpose under the trip summary, with a small edit button. */
@Composable
private fun VisitPurposeLine(t: Trip, actions: TripActions) {
    if (t.purpose != TripPurpose.BUSINESS) return
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (t.memo.isBlank()) "방문 목적 미기재" else "방문 목적 · ${t.memo}",
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = if (t.memo.isBlank()) Palette.warn else Palette.ink,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (t.memo.isBlank()) "적기 ›" else "고치기 ›",
            modifier = Modifier
                .background(Palette.accentSoft, RoundedCornerShape(20.dp))
                .clickable { actions.onOpen(t.id, TripView.MEMO) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.accent,
        )
    }
}

/** Common visit purposes: one tap, no typing in the car. */
private val MEMO_PRESETS = listOf("거래처 방문", "납품·배송", "회의·미팅", "현장 점검", "교육·출장", "관공서·은행 업무")

/** Separate page for a business trip's visit purpose (goes to the logbook's ⑩ 비고). */
@Composable
private fun MemoScreen(trip: Trip, onMemo: (Long, String) -> Unit, onBack: () -> Unit) {
    var memo by remember(trip.id) { mutableStateOf(trip.memo) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Typing changes only this screen; the logbook is written once, when leaving (button, tab or back).
    val latest = androidx.compose.runtime.rememberUpdatedState(memo)
    androidx.compose.runtime.DisposableEffect(trip.id) {
        onDispose { if (latest.value.trim() != trip.memo) onMemo(trip.id, latest.value.trim()) }
    }
    val save = {
        keyboard?.hide(); focus.clearFocus()
        onBack()
    }

    Row { Chip("← 운행 기록으로", selected = false, onClick = { keyboard?.hide(); focus.clearFocus(); onBack() }) }
    Card {
        Text("업무 운행 · 방문 목적", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
        Spacer(Modifier.height(4.dp))
        Text("운행기록부 ⑩비고에 들어가요. 업무 운행은 방문 목적이 있어야 업무용으로 인정받기 쉬워요.",
            fontSize = 16.sp, color = Palette.sub)
        Spacer(Modifier.height(16.dp))
        TripSummary(trip)
    }
    Card {
        Text("자주 쓰는 목적", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MEMO_PRESETS.forEach { p -> Chip(p, selected = memo == p) { memo = p } }
        }
        Spacer(Modifier.height(16.dp))
        Text("직접 적기 (예: 한빛상사 납품 미팅)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = memo,
            onValueChange = { memo = it.take(TripRecorder.MEMO_MAX) },
            singleLine = true,
            placeholder = { Text("방문한 곳과 목적") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            supportingText = { Text("${memo.length}/${TripRecorder.MEMO_MAX}") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        BigButton(if (memo.isBlank()) "비워 두고 돌아가기" else "저장하고 돌아가기") { save() }
    }
}

/** Navigation button: same colours as the tab chips (light green, green text), with an arrow. */
@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        "$label  ›",
        modifier = modifier
            .background(Palette.accentSoft, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = Palette.accent,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TripSummary(t: Trip) {
    if (t.demo) {
        Text("시연용 (가상 운행)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7C3AED))
        Spacer(Modifier.height(4.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${ReportBuilder.dateTime(t.startMs)} · ${"%.1f".format(t.distanceKm)} km · ${ReportBuilder.duration(t.durationMs)}",
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink,
        )
        if (t.count(DriveEvent.Type.OVERSPEED) > 0) {
            // Purple = this trip has overspeed records (same colour as on the map).
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(14.dp).background(Color(0xFF7C3AED), CircleShape))
        }
    }
    Spacer(Modifier.height(6.dp))
    val parts = listOfNotNull(
        t.distanceSource?.let { "${it.label} 기준" + if (t.distanceMismatch) " (출처 간 차이 있음)" else "" },
        if (t.odometerImplausible) "계기판 값 급변 → 제외" else null,
        t.batteryUsed?.let { "배터리 ${it}%p 사용" },
        "최고 ${"%.0f".format(t.maxSpeedKmh)} km/h",
        // Summary shows two groups; the record keeps the four KoTSA types apart (reports use them).
        "급가속 ${t.count(DriveEvent.Type.HARSH_ACCEL) + t.count(DriveEvent.Type.QUICK_START)}",
        "급감속 ${t.count(DriveEvent.Type.HARSH_BRAKE) + t.count(DriveEvent.Type.SUDDEN_STOP)}",
        "과속 ${t.count(DriveEvent.Type.OVERSPEED)}",
        "정지 ${t.stopCount}",
        if (t.idleSeconds > 0) "정차 대기 ${t.idleSeconds / 60}분 ${t.idleSeconds % 60}초" else null,
        if (t.duringServiceVisit) "정비 중 운행" else null,
    )
    val odo = t.odometerDistanceKm
    val logbookManual = t.odometerImplausible || odo == null || (odo < Trip.MIN_MEASURED_KM && t.distanceKm >= Trip.MIN_MEASURED_KM)
    Text(parts.joinToString(" · "), fontSize = 16.sp, color = Palette.sub)
    if (logbookManual && !t.demo) {
        Spacer(Modifier.height(4.dp))
        Text("운행기록부: 계기판 거리를 확인하지 못했어요 → 보고서 › 운행기록부에 직접 기재", fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, color = Color(0xFFB45309))
    }
}

@Composable
private fun PurposePicker(t: Trip, onPurpose: (Long, TripPurpose) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TripPurpose.BUSINESS, TripPurpose.COMMUTE, TripPurpose.PERSONAL).forEach { p ->
            Chip(p.label, selected = t.purpose == p) { onPurpose(t.id, p) }
        }
    }
}

@Composable
private fun ReportsTab(book: Logbook, s: VehicleSnapshot, onProfile: (VehicleProfile) -> Unit, onDriver: (Long, String) -> Unit) {
    var type by remember { mutableStateOf(ReportType.WEEKLY) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SHAREABLE_REPORTS.forEach { r -> Chip(r.tab, selected = r == type) { type = r } }
    }
    // Rebuild when the type, the logbook or the vehicle state shown in reports changes (not on every speed update).
    val shownState = listOf(s.telltales, s.brakeFluidLow, s.washerFluidLow, s.powerDownWarning, s.airbagDeployed, s.odometerKm, s.batteryPercent)
    val report = remember(type, book, shownState) { ReportBuilder.build(type, book, s) }
    val qr = remember(report) { QrCode.render(report.toUrl(ChagokApp.REPORT_BASE_URL)) }

    Card {
        Text(report.type.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
        Text(report.period, fontSize = 16.sp, color = Palette.sub)
        Spacer(Modifier.height(16.dp))
        if (qr != null) {
            // QR follows the window width (Pleos windows resize), within a readable range.
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = (maxWidth * 0.6f).coerceIn(160.dp, 360.dp)
                Image(bitmap = qr, contentDescription = "보고서 QR 코드", modifier = Modifier.size(side))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "휴대폰 카메라로 찍으면 이 보고서가 열려요. (데이터는 서버에 저장되지 않아요)",
                fontSize = 16.sp, color = Palette.sub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("내용이 많아 QR 하나에 담을 수 없어요. 기간을 줄여 다시 만들어 주세요.", fontSize = 16.sp, color = Palette.warn)
        }
    }

    report.sections.forEach { sec ->
        Card {
            Text(sec.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
            Spacer(Modifier.height(8.dp))
            sec.rows.forEach { KeyValue(it.first, it.second) }
        }
    }

    if (type == ReportType.TAX) {
        ProfileForm(book.profile, onProfile)
        val manual = report.taxRows.count { it.needsManual }
        if (manual > 0) {
            Column(Modifier.fillMaxWidth().background(Color(0xFFFEF3C7), RoundedCornerShape(20.dp)).padding(24.dp)) {
                Text("직접 기재가 필요한 운행 ${manual}건", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
                Spacer(Modifier.height(4.dp))
                Text("계기판 거리를 확인하지 못한 운행이에요(노란 줄). 운행기록부에는 계기판 거리를 써야 해서 " +
                    "⑦~⑨칸을 비워 두었어요. 계기판을 확인해 직접 적어 주세요.", fontSize = 16.sp, color = Color(0xFF92400E))
            }
        }
        var editing by remember { mutableStateOf<Long?>(null) }
        Card {
            Text("운행 내역 (국세청 별지 서식 ③~⑩)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
            Text("④ 사용자를 누르면 그 운행의 사용자를 바꿀 수 있어요.", fontSize = 16.sp, color = Palette.sub)
            Spacer(Modifier.height(8.dp))
            TaxTableRow(listOf("③ 사용일자", "④ 사용자", "⑤ 주행 전", "⑥ 주행 후", "⑦ 주행거리", "⑧ 출·퇴근", "⑨ 일반업무", "⑩ 비고"), header = true)
            if (report.taxRows.isEmpty()) {
                Text("이번 사업연도에 기록된 운행이 없어요.", fontSize = 16.sp, color = Palette.sub)
            }
            report.taxRows.asReversed().forEach { row ->
                TaxTableRow(row.cells(), highlight = row.needsManual, onUserClick = { editing = row.tripId })
                if (editing == row.tripId) {
                    DriverEditor(row, book) { name -> onDriver(row.tripId, name); editing = null }
                }
            }
        }
    }

    Card {
        report.notes.forEach { Text("· $it", fontSize = 16.sp, color = Palette.sub) }
        Spacer(Modifier.height(8.dp))
        Text("확인 코드 ${report.verificationCode()}", fontSize = 16.sp, color = Palette.sub)
    }
}

@Composable
private fun TaxTableRow(cells: List<String>, header: Boolean = false, highlight: Boolean = false, onUserClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (highlight) Color(0xFFFEF3C7) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        cells.forEachIndexed { i, text ->
            val editable = i == 1 && onUserClick != null
            Text(
                if (editable) "$text ✎" else text,
                modifier = Modifier.weight(1f).then(if (editable) Modifier.clickable { onUserClick!!() } else Modifier),
                fontSize = 16.sp,
                fontWeight = if (header || editable) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    header -> Palette.ink
                    editable -> Palette.accent
                    highlight && i == 7 -> Color(0xFF92400E)
                    else -> Palette.sub
                },
            )
        }
    }
}

/** Change who drove one trip (logbook ④). Suggests names already used. */
@Composable
private fun DriverEditor(row: TaxRow, book: Logbook, onSave: (String) -> Unit) {
    val defaultUser = listOf(book.profile.userDepartment, book.profile.userName).filter { it.isNotBlank() }.joinToString(" ")
    val known = (listOf(defaultUser) + book.closedTrips.map { it.driver }).filter { it.isNotBlank() }.distinct().take(6)
    var name by remember(row.tripId) { mutableStateOf(row.user.takeIf { it != "(직접 기재)" } ?: "") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val save = { keyboard?.hide(); focus.clearFocus(); onSave(if (name == defaultUser) "" else name) }
    Column(Modifier.fillMaxWidth().background(Color(0xFFF9FAFB), RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text("${row.date} 운행의 ④ 사용자", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
        Spacer(Modifier.height(8.dp))
        if (known.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                known.forEach { k -> Chip(k, selected = name == k) { name = k } }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(TripRecorder.MEMO_MAX) }, singleLine = true,
            placeholder = { Text("부서 성명 (예: 영업팀 홍길동)") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ActionButton("저장", Modifier.fillMaxWidth()) { save() }
    }
}

@Composable
private fun ServiceTab(book: Logbook, s: VehicleSnapshot, onDropOff: () -> Unit, onPickUp: () -> Unit) {
    val open = book.openVisit
    Card {
        if (open == null) {
            Text("정비소에 차를 맡기기 전에 눌러주세요", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
            Spacer(Modifier.height(8.dp))
            Text("지금의 주행거리·배터리·경고 상태를 기록해 두고, 찾을 때와 비교해요.", fontSize = 16.sp, color = Palette.sub)
            Spacer(Modifier.height(16.dp))
            BigButton("정비 맡김 기록", onDropOff)
        } else {
            Text("정비 맡김 · ${ReportBuilder.dateTime(open.dropOff.timeMs)}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
            Spacer(Modifier.height(8.dp))
            KeyValue("맡길 때 주행거리", open.dropOff.odometerKm?.let { "%.1f km".format(it) } ?: "-")
            KeyValue("지금 주행거리", s.odometerKm?.let { "%.1f km".format(it) } ?: "-")
            KeyValue("맡긴 뒤 주행", book.serviceDrive(open).text)
            KeyValue("맡길 때 경고", Warnings.labels(open.dropOff.activeWarnings))
            Spacer(Modifier.height(16.dp))
            BigButton("차 찾음 기록", onPickUp)
        }
    }

    book.visits.filter { !it.isOpen }.reversed().forEach { v ->
        Card {
            Text("${ReportBuilder.date(v.dropOff.timeMs)} 맡김 → ${ReportBuilder.date(v.pickUp!!.timeMs)} 찾음",
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
            Spacer(Modifier.height(8.dp))
            KeyValue("정비 중 주행", book.serviceDrive(v).text)
            KeyValue("맡길 때 경고", Warnings.labels(v.dropOff.activeWarnings))
            KeyValue("찾을 때 경고", Warnings.labels(v.pickUp!!.activeWarnings))
        }
    }
}

@Composable
private fun DataTab(signals: Map<Field, Signal>, onAskSpeed: () -> Unit, onClear: () -> Unit, onDemo: () -> Unit) {
    Card {
        Text("차량 데이터가 실제로 들어오는지 확인하는 화면이에요.", fontSize = 16.sp, color = Palette.sub)
        Spacer(Modifier.height(8.dp))
        Field.entries.forEach { f ->
            val sig = signals[f] ?: Signal()
            val dot = when (sig.status) {
                Signal.Status.OK -> Palette.ok
                Signal.Status.ERROR -> Palette.warn
                Signal.Status.UNSUPPORTED, Signal.Status.WAITING -> Color(0xFF9CA3AF)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(dot, CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${f.label}  ·  ${f.source}", fontSize = 18.sp, color = Palette.ink)
                    if (sig.detail.isNotEmpty()) {
                        Text(sig.detail, fontSize = 16.sp, color = if (sig.status == Signal.Status.ERROR) Palette.warn else Palette.sub)
                    }
                }
                Text(sig.value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
            }
        }
    }
    Card {
        BigButton("차속 권한 다시 요청", onAskSpeed)
        // Test/demo helper, only in debug builds (not in a release APK).
        val debuggable = (LocalContext.current.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            Spacer(Modifier.height(12.dp))
            BigButton("시연용 운행 1건 만들기 (판교 9분)", onDemo)
            Text("지도·보고서·QR을 직접 운전하지 않고 확인하는 용도예요. '시연용'으로 표시되고, 보고서에도 안내가 붙어요.",
                fontSize = 16.sp, color = Palette.sub)
        }
        Spacer(Modifier.height(12.dp))
        var armed by remember { mutableStateOf(false) }
        BigButton(if (armed) "한 번 더 누르면 모든 기록 삭제" else "기록 전체 삭제 (시연 초기화)") {
            if (armed) { onClear(); armed = false } else armed = true
        }
    }
}

@Composable
private fun ProfileForm(profile: VehicleProfile, onProfile: (VehicleProfile) -> Unit) {
    // Edited locally and saved once (button, or leaving the screen): saving on every keystroke would
    // also turn half-typed names into "previous default users" of past trips.
    var draft by remember(profile) { mutableStateOf(profile) }
    val latest = androidx.compose.runtime.rememberUpdatedState(draft)
    val saved = androidx.compose.runtime.rememberUpdatedState(profile)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (latest.value != saved.value) onProfile(latest.value) }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Card {
        Text("운행기록부 기본정보", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
        Text("국세청 운행기록부 서식의 머리말 항목이에요. 처음 한 번만 입력하면 돼요.", fontSize = 16.sp, color = Palette.sub)
        Spacer(Modifier.height(8.dp))
        ProfileField("법인명 (개인사업자는 상호)", draft.companyName) { draft = draft.copy(companyName = it) }
        ProfileField("사업자등록번호", draft.businessNumber) { draft = draft.copy(businessNumber = it) }
        ProfileField("① 차종", draft.carModel) { draft = draft.copy(carModel = it) }
        ProfileField("② 자동차등록번호", draft.plateNumber) { draft = draft.copy(plateNumber = it) }
        ProfileField("④ 기본 사용자 부서", draft.userDepartment) { draft = draft.copy(userDepartment = it) }
        ProfileField("④ 기본 사용자 성명", draft.userName) { draft = draft.copy(userName = it) }
        Text("기본 사용자는 사용자를 따로 정하지 않은 운행에 쓰여요. 바꾸면 이전 운행은 그때의 사용자 이름을 그대로 유지해요.",
            fontSize = 16.sp, color = Palette.sub)
        Spacer(Modifier.height(8.dp))
        Text("사업연도 시작 월", fontSize = 16.sp, color = Palette.sub)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..12).forEach { m ->
                Chip("${m}월", selected = draft.fiscalStartMonth == m) { draft = draft.copy(fiscalStartMonth = m) }
            }
        }
        Spacer(Modifier.height(12.dp))
        ActionButton(if (draft == profile) "저장됨" else "기본정보 저장", Modifier.fillMaxWidth()) {
            keyboard?.hide(); focus.clearFocus()
            if (draft != profile) onProfile(draft)
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

// ======================= route (나 자신용) =======================

private fun eventColor(type: DriveEvent.Type): Color = when (type) {
    DriveEvent.Type.QUICK_START, DriveEvent.Type.HARSH_ACCEL -> Color(0xFFEA580C)
    DriveEvent.Type.HARSH_BRAKE, DriveEvent.Type.SUDDEN_STOP -> Color(0xFFBE123C)
    DriveEvent.Type.OVERSPEED -> Color(0xFF7C3AED)
    DriveEvent.Type.LONG_IDLE -> Color(0xFF2563EB)
}

// Stops are drawn as a hollow ring so an event at the same spot stays visible inside it.
private val DWELL_COLOR = Color(0xFF0891B2)

@Composable
private fun RouteScreen(trip: Trip, onBack: () -> Unit) {
    val allMarks = remember(trip) { RouteModel.marks(trip) }
    var selected by remember(trip) { mutableStateOf<RouteMark?>(null) }
    // Optional layers (off by default); overspeed is always shown.
    var showAccel by remember { mutableStateOf(false) }
    var showBrake by remember { mutableStateOf(false) }
    var showDwell by remember { mutableStateOf(false) }
    val marks = remember(allMarks, showAccel, showBrake, showDwell) {
        allMarks.filter { m ->
            when (m.kind) {
                RouteMark.Kind.DWELL -> showDwell
                RouteMark.Kind.EVENT -> when (m.event!!.type) {
                    DriveEvent.Type.QUICK_START, DriveEvent.Type.HARSH_ACCEL -> showAccel
                    DriveEvent.Type.HARSH_BRAKE, DriveEvent.Type.SUDDEN_STOP -> showBrake
                    else -> true
                }
                else -> true
            }
        }
    }
    if (selected != null && selected !in marks) selected = null
    val report = remember(trip) { ReportBuilder.route(trip) }

    // Same "운행" rows as the phone page (운행 시각, 거리, 시간, ...), shown under the map.
    val tripInfo: @Composable () -> Unit = {
        Card {
            Text("운행", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
            Spacer(Modifier.height(8.dp))
            report.sections.first().rows.forEach { KeyValue(it.first, it.second) }
        }
    }

    Row { Chip("← 돌아가기", selected = false, onClick = onBack) }
    if (allMarks.isEmpty()) {
        Card {
            Text("이 운행에는 위치 기록이 없어요.", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
            Spacer(Modifier.height(6.dp))
            Text("경로 기능을 넣기 전에 기록된 운행이거나, 운행 중 내비게이션 위치를 받지 못했어요. " +
                "새로 운행하면 경로가 기록돼요.", fontSize = 16.sp, color = Palette.sub)
        }
        tripInfo()
        return
    }
    Card {
        Text("운행 상세", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Legend(Color.White, "1분마다 위치", outlined = true)
            Legend(Color(0xFF7C3AED), "과속")
        }
        Spacer(Modifier.height(10.dp))
        Text("더 보기", fontSize = 16.sp, color = Palette.sub)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayerToggle("급가속", Color(0xFFEA580C), showAccel, ring = true) { showAccel = !showAccel }
            LayerToggle("급감속", Color(0xFFBE123C), showBrake, ring = true) { showBrake = !showBrake }
            LayerToggle("1분 이상 정차", DWELL_COLOR, showDwell, ring = true) { showDwell = !showDwell }
        }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(440.dp).background(Color(0xFFF8FAFC), RoundedCornerShape(16.dp))
        ) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            // Fitted to everything recorded, so turning a layer on or off does not move or zoom the map.
            val proj = remember(allMarks, w, h) { RouteModel.Projection(allMarks, w, h, 48f) }
            Canvas(
                Modifier.fillMaxSize().pointerInput(marks, proj) {
                    detectTapGestures { o -> selected = RouteModel.nearest(marks, proj, o.x, o.y, 64f) }
                }
            ) {
                fun at(m: RouteMark) = Offset(proj.x(m.lon), proj.y(m.lat))
                val line = marks.filter { it.kind == RouteMark.Kind.POINT || it.kind == RouteMark.Kind.MINUTE }
                for (i in 1 until line.size) {
                    drawLine(Color(0xFF64748B), at(line[i - 1]), at(line[i]), strokeWidth = 16f, cap = StrokeCap.Round)
                }
                line.firstOrNull()?.let { drawCircle(Color(0xFF111827), 14f, at(it), style = Stroke(5f)) }  // start
                line.lastOrNull()?.let { drawCircle(Color(0xFF111827), 14f, at(it)) }                       // end
                line.filter { it.kind == RouteMark.Kind.MINUTE }.forEach {
                    drawCircle(Color.White, 6f, at(it))   // white dot on the wide line, every minute
                }
                // Accel, brake and 1-minute stops: hollow rings of one size; overspeed: filled dot.
                fun ring(color: Color, c: Offset) {
                    drawCircle(Color.White, RouteModel.RING_PX, c, style = Stroke(10f))   // halo for contrast
                    drawCircle(color, RouteModel.RING_PX, c, style = Stroke(6f))
                }
                // 1-minute stops: a bigger ring, so an event at the same spot shows inside it.
                marks.filter { it.kind == RouteMark.Kind.DWELL }.forEach {
                    drawCircle(Color.White, RouteModel.DWELL_RING_PX, at(it), style = Stroke(10f))
                    drawCircle(DWELL_COLOR, RouteModel.DWELL_RING_PX, at(it), style = Stroke(6f))
                }
                marks.filter { it.kind == RouteMark.Kind.EVENT }.forEach {
                    val type = it.event!!.type
                    if (type == DriveEvent.Type.OVERSPEED) {
                        drawCircle(eventColor(type), 13f, at(it))
                        drawCircle(Color.White, 13f, at(it), style = Stroke(4f))
                    } else ring(eventColor(type), at(it))
                }
                // Selected place: a downward triangle pointing at it.
                selected?.let {
                    val p = at(it)
                    val tipY = p.y // tip on the centre of the road
                    val tri = androidx.compose.ui.graphics.Path().apply {
                        moveTo(p.x, tipY); lineTo(p.x - 18f, tipY - 34f); lineTo(p.x + 18f, tipY - 34f); close()
                    }
                    drawPath(tri, Color.White, style = Stroke(6f))
                    drawPath(tri, Color(0xFF111827))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("○ 출발  ● 도착 · 흰 점은 1분마다의 위치예요. 점 사이는 직선이라 실제 도로와 다를 수 있어요.", fontSize = 16.sp, color = Palette.sub)
        Spacer(Modifier.height(12.dp))
        val sel = selected
        if (sel == null) {
            Text("경로의 흰 점이나 표시를 누르면 그때의 시각·위치·속도·제한속도가 나와요.", fontSize = 17.sp, color = Palette.sub)
        } else {
            MarkInfo(sel)
        }
    }
    tripInfo()
}

/** One trip's report: the same rows, notes, check code and the QR for the phone page (map, address, road view). */
@Composable
private fun TripReportScreen(trip: Trip, onBack: () -> Unit) {
    // Off by default: the owner usually scans this with their own phone. Turn on before showing someone else.
    var hideEnds by remember(trip) { mutableStateOf(false) }
    val report = remember(trip, hideEnds) { ReportBuilder.route(trip, hideEnds = hideEnds) }
    val qr = remember(report) { QrCode.render(report.toUrl(ChagokApp.REPORT_BASE_URL)) }

    Row { Chip("← 돌아가기", selected = false, onClick = onBack) }
    Card {
        Text(report.type.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
        Text(report.period, fontSize = 16.sp, color = Palette.sub)
        Spacer(Modifier.height(16.dp))
        if (trip.track.isNotEmpty()) {
            LayerToggle("출발·도착 주변 가리기", Palette.accent, hideEnds) { hideEnds = !hideEnds }
            Spacer(Modifier.height(6.dp))
            Text("다른 사람에게 보여 줄 때 켜세요. 켜면 출발·도착 지점 반경 ${RouteCodec.PHONE_HIDE_RADIUS_M}m 안의 경로와 표시가 휴대폰 지도에서 빠져요.",
                fontSize = 16.sp, color = Palette.sub)
            Spacer(Modifier.height(16.dp))
        }
        if (qr != null) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = (maxWidth * 0.6f).coerceIn(160.dp, 360.dp)
                Image(bitmap = qr, contentDescription = "운행 보고서 QR 코드", modifier = Modifier.size(side))
            }
            Spacer(Modifier.height(8.dp))
            Text("휴대폰 카메라로 찍으면 지도·주소·로드뷰와 함께 열려요. (데이터는 서버에 저장되지 않아요)",
                fontSize = 16.sp, color = Palette.sub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            Text("경로가 길어 QR 하나에 담을 수 없어요.", fontSize = 16.sp, color = Palette.warn)
        }
    }
    report.sections.forEach { sec ->
        Card {
            Text(sec.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
            Spacer(Modifier.height(8.dp))
            sec.rows.forEach { KeyValue(it.first, it.second) }
        }
    }
    Card {
        report.notes.forEach { Text("· $it", fontSize = 16.sp, color = Palette.sub) }
        Spacer(Modifier.height(8.dp))
        Text("확인 코드 ${report.verificationCode()}", fontSize = 16.sp, color = Palette.sub)
    }
}

@Composable
private fun LayerToggle(label: String, color: Color, on: Boolean, ring: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .background(if (on) Palette.accentSoft else Color(0xFFF3F4F6), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (on) color else Color(0xFFD1D5DB)
        Box(Modifier.size(14.dp).then(if (ring) Modifier.border(3.dp, c, CircleShape) else Modifier.background(c, CircleShape)))
        Spacer(Modifier.width(8.dp))
        Text((if (on) "켜짐 · " else "꺼짐 · ") + label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (on) Palette.ink else Palette.sub)
    }
}

@Composable
private fun Legend(color: Color, label: String, outlined: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(Color(0xFF64748B), CircleShape).padding(if (outlined) 3.dp else 0.dp)
            .background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 16.sp, color = Palette.sub)
    }
}

private val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)

@Composable
private fun MarkInfo(m: RouteMark) {
    val title = when (m.kind) {
        RouteMark.Kind.EVENT -> m.event!!.type.label
        RouteMark.Kind.DWELL -> "1분 이상 정차"
        else -> "경로 지점"
    }
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
    Spacer(Modifier.height(6.dp))
    val overspeed = m.event?.type == DriveEvent.Type.OVERSPEED
    KeyValue(if (overspeed) "과속 시작" else "시각", "${ReportBuilder.date(m.timeMs)} ${clock.format(java.util.Date(m.timeMs))}")
    KeyValue(if (overspeed) "최고 속도 (이 지점)" else "속도", m.speedKmh?.let { "%.0f km/h".format(it) } ?: "-")
    KeyValue("제한속도", m.limitKmh?.let { "$it km/h" } ?: "정보 없음")
    m.event?.let { e ->
        e.peakRate?.let { KeyValue(if (e.type == DriveEvent.Type.QUICK_START || e.type == DriveEvent.Type.HARSH_ACCEL) "최고 가속" else "최고 감속", "%.1f km/h/s".format(it)) }
        e.endMs?.let { end ->
            val sec = (end - e.timeMs) / 1000
            KeyValue(if (overspeed) "과속 지속" else "이어진 시간", if (sec < 1) "1초 미만" else "${sec}초")
        }
    }
    m.dwell?.let { KeyValue("정차 시간", "${it.seconds / 60}분 ${it.seconds % 60}초") }
    Text("주소와 주변 사진(로드뷰)은 휴대폰 지도에서 볼 수 있어요.", fontSize = 16.sp, color = Palette.sub)
}

// ======================= small components =======================

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.card, RoundedCornerShape(20.dp)).padding(24.dp)
    ) { content() }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(key, modifier = Modifier.weight(1f), fontSize = 18.sp, color = Palette.sub)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink, textAlign = TextAlign.End)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .background(if (selected) Palette.accent else Palette.accentSoft, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Color.White else Palette.accent,
    )
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.accent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center,
    )
}
