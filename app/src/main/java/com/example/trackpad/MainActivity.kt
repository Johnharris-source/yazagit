package com.example.trackpad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

// Все цвета интерфейса в одном месте; акцент меняется в настройках
object Palette {
    val bg = Color(0xFF0B0D10)
    val panel = Color(0xFF14171C)
    val panelHi = Color(0xFF1A1E24)
    val border = Color(0xFF262B33)
    val warn = Color(0xFFE0A030)
    val text = Color(0xFFE6E9EE)
    val textDim = Color(0xFF9AA3AF)

    val DEFAULT_ACCENT = 0xFF3B82F6.toInt()
    var accent by mutableStateOf(Color(0xFF3B82F6))
}

enum class PadMode { MOUSE, DRAW, TABLET }

const val LEFT_CLICK = """{"type":"Click","button":"left"}"""
const val RIGHT_CLICK = """{"type":"Click","button":"right"}"""
const val MIDDLE_CLICK = """{"type":"Click","button":"middle"}"""
const val BTN_DOWN = """{"type":"Button","button":"left","down":true}"""
const val BTN_UP = """{"type":"Button","button":"left","down":false}"""
const val SUPER_DOWN = """{"type":"Key","code":125,"down":true}"""
const val SUPER_UP = """{"type":"Key","code":125,"down":false}"""

fun scrollJson(steps: Int): String {
    val s = if (Settings.invertScroll) -steps else steps
    return """{"type":"Scroll","dy":$s}"""
}

// Накапливает дробные доли смещения, чтобы медленные движения не терялись
class MoveAcc {
    private var rx = 0f
    private var ry = 0f

    fun json(d: Offset): String? {
        rx += d.x * Settings.sensitivity
        ry += d.y * Settings.sensitivity
        val dx = rx.toInt()
        val dy = ry.toInt()
        rx -= dx
        ry -= dy
        return if (dx == 0 && dy == 0) null else """{"type":"Move","dx":$dx,"dy":$dy}"""
    }
}

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    private var active = false

    private var connected by mutableStateOf(false)
    private var wantConnected by mutableStateOf(true)

    private fun send(s: String) {
        ws?.send(s)
    }

    private fun connect() {
        if (!active || !wantConnected || ws != null) return
        ws = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:9000").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    handler.post { if (ws === webSocket) connected = true }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    lost(webSocket)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    lost(webSocket)
                }
            }
        )
    }

    private fun lost(webSocket: WebSocket) {
        handler.post {
            if (ws === webSocket) {
                ws = null
                connected = false
                handler.postDelayed({ connect() }, 1000)
            }
        }
    }

    private fun toggleConnection() {
        if (wantConnected) {
            wantConnected = false
            ws?.close(1000, null)
            ws = null
            connected = false
        } else {
            wantConnected = true
            connect()
        }
    }

    override fun onStart() {
        super.onStart()
        active = true
        connect()
    }

    override fun onStop() {
        active = false
        ws?.close(1000, null)
        ws = null
        connected = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.load(applicationContext)
        TabletConfig.load(applicationContext)
        setContent {
            val store = remember { GestureStore(applicationContext) }
            val keys = remember { QuickKeyStore(applicationContext) }
            TrackpadScreen(
                connected = connected,
                wantConnected = wantConnected,
                onToggleConnection = { toggleConnection() },
                send = { send(it) },
                store = store,
                keys = keys
            )
        }
    }
}

@Composable
fun TrackpadScreen(
    connected: Boolean,
    wantConnected: Boolean,
    onToggleConnection: () -> Unit,
    send: (String) -> Unit,
    store: GestureStore,
    keys: QuickKeyStore
) {
    var mode by remember { mutableStateOf(PadMode.MOUSE) }
    var showGestures by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    var keyboardSoon by remember { mutableStateOf(false) }
    var superHeld by remember { mutableStateOf(false) }
    val battery = rememberBattery()

    // Если связь пропала, сервер сам отпустил клавиши, сбрасываем и кнопку
    LaunchedEffect(connected) { if (!connected) superHeld = false }

    if (showSettings) {
        SettingsScreen { showSettings = false }
    } else if (showGestures) {
        GesturesScreen(store) { showGestures = false }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.bg)
                .safeDrawingPadding()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TopBar(connected, wantConnected, battery, { showSettings = true }, onToggleConnection)
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScrollStrip(Modifier.weight(1.1f).fillMaxHeight(), send)
                Pad(Modifier.weight(6f).fillMaxHeight(), mode, store, send)
                SidePanel(Modifier.weight(2.4f).fillMaxHeight(), mode, { mode = it }, send)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleButton("⌘ Super", Modifier.width(110.dp).fillMaxHeight(), superHeld) {
                    superHeld = !superHeld
                    send(if (superHeld) SUPER_DOWN else SUPER_UP)
                }
                keys.items.forEach { k ->
                    ClickButton(k.label, Modifier.widthIn(min = 72.dp).fillMaxHeight(), false) {
                        parseCombo(k.combo)?.let { sendCombo(it, send) }
                    }
                }
                ClickButton("+ Кнопки", Modifier.width(110.dp).fillMaxHeight(), false) {
                    showKeys = true
                }
                ClickButton("✋  Жесты", Modifier.width(110.dp).fillMaxHeight(), false) {
                    showGestures = true
                }
                ClickButton("⌨  Клавиатура", Modifier.width(140.dp).fillMaxHeight(), false) {
                    keyboardSoon = true
                }
            }
        }
    }

    if (showKeys) QuickKeysDialog(keys) { showKeys = false }

    if (keyboardSoon) {
        AlertDialog(
            onDismissRequest = { keyboardSoon = false },
            confirmButton = { TextButton(onClick = { keyboardSoon = false }) { Text("Ок") } },
            title = { Text("Клавиатура") },
            text = { Text("Полная экранная клавиатура появится позже. Пока можно добавлять нужные сочетания кнопкой «+ Кнопки».") }
        )
    }
}

@Composable
fun rememberBattery(): Int {
    val context = LocalContext.current
    var level by remember { mutableStateOf(-1) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0 && s > 0) level = l * 100 / s
            }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        receiver.onReceive(context, sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return level
}

@Composable
fun TopBar(
    connected: Boolean,
    wantConnected: Boolean,
    battery: Int,
    onSettings: () -> Unit,
    onToggle: () -> Unit
) {
    val statusColor = when {
        connected -> Palette.accent
        wantConnected -> Palette.warn
        else -> Palette.textDim
    }
    val statusText = when {
        connected -> "Подключено к ноутбуку"
        wantConnected -> "Подключение…"
        else -> "Отключено"
    }
    Row(
        Modifier.fillMaxWidth().height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor))
        Spacer(Modifier.width(8.dp))
        Text(statusText, color = Palette.text, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        if (battery >= 0) {
            Text("Телефон $battery%", color = Palette.textDim, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
        }
        ClickButton("⚙", Modifier.width(48.dp).height(32.dp), false) { onSettings() }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onToggle,
            border = BorderStroke(1.dp, Palette.accent),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(
                if (wantConnected) "Отключить" else "Подключить",
                color = Palette.accent,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun ScrollStrip(modifier: Modifier, send: (String) -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.panelHi)
            .pointerInput(Unit) {
                var acc = 0f
                detectDragGestures { change, drag ->
                    change.consume()
                    acc += drag.y
                    val step = 40f / Settings.scrollSpeed
                    val steps = (acc / step).toInt()
                    if (steps != 0) {
                        acc -= steps * step
                        send(scrollJson(steps))
                    }
                }
            }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text("▲", color = Palette.textDim)
        Box(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .width(3.dp)
                .background(Palette.accent.copy(alpha = 0.5f))
        )
        Text("▼", color = Palette.textDim)
    }
}

@Composable
fun Pad(modifier: Modifier, mode: PadMode, store: GestureStore, send: (String) -> Unit) {
    val points = remember { mutableStateListOf<Offset>() }
    var drawing by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf(0) }
    var recognized by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(strokes) {
        if (strokes > 0) {
            delay(700)
            if (!drawing) points.clear()
        }
    }
    LaunchedEffect(mode) {
        points.clear()
        recognized = null
    }

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.panel)
            .border(
                2.dp,
                if (mode != PadMode.MOUSE) Palette.accent else Palette.border,
                RoundedCornerShape(16.dp)
            )
            .pointerInput(mode) {
                if (mode == PadMode.MOUSE) {
                    // Один палец: тап = клик, движение = курсор,
                    // удержание без движения = зажать левую кнопку и тащить.
                    // Два пальца: скролл, тап = правый клик.
                    // Три и четыре пальца: тап (три) = средний клик, свайпы = сочетания клавиш.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val slop = viewConfiguration.touchSlop
                        val acc = MoveAcc()
                        var total = Offset.Zero
                        var moved = false
                        var released = false
                        var multi = false

                        withTimeoutOrNull(Settings.holdMs.toLong()) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } >= 2) {
                                    multi = true
                                    return@withTimeoutOrNull
                                }
                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                    ?: return@withTimeoutOrNull
                                total += ch.positionChange()
                                if (!ch.pressed) {
                                    released = true
                                    return@withTimeoutOrNull
                                }
                                if (total.getDistance() > slop) {
                                    moved = true
                                    return@withTimeoutOrNull
                                }
                            }
                        }

                        if (!multi) {
                            if (released && !moved) {
                                send(LEFT_CLICK)
                                return@awaitEachGesture
                            }

                            val dragging = !moved && !released
                            if (dragging) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                send(BTN_DOWN)
                            }
                            if (moved) acc.json(total)?.let(send)

                            while (true) {
                                val ev = awaitPointerEvent()
                                if (!dragging && ev.changes.count { it.pressed } >= 2) {
                                    multi = true
                                    break
                                }
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                val d = ch.positionChange()
                                if (d != Offset.Zero) acc.json(d)?.let(send)
                                ch.consume()
                                if (!ch.pressed) break
                            }
                            if (dragging) send(BTN_UP)
                        }

                        if (multi) {
                            val t0 = down.uptimeMillis
                            var fingers = 2
                            var travel = Offset.Zero
                            var scrollAcc = 0f
                            var scrolled = false
                            var endTime = t0

                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressedNow = ev.changes.count { it.pressed }
                                if (pressedNow > fingers) fingers = pressedNow

                                val movers = ev.changes.filter { it.pressed && it.previousPressed }
                                if (movers.isNotEmpty()) {
                                    var s = Offset.Zero
                                    for (c in movers) s += c.positionChange()
                                    val avg = s / movers.size.toFloat()
                                    travel += avg
                                    if (fingers == 2) {
                                        scrollAcc += avg.y
                                        val step = 40f / Settings.scrollSpeed
                                        val steps = (scrollAcc / step).toInt()
                                        if (steps != 0) {
                                            scrollAcc -= steps * step
                                            scrolled = true
                                            send(scrollJson(steps))
                                        }
                                    }
                                }
                                ev.changes.forEach { it.consume() }
                                endTime = ev.changes.maxOf { it.uptimeMillis }
                                if (pressedNow == 0) break
                            }

                            val dist = travel.getDistance()
                            val quick = (endTime - t0) < 300 && dist < slop * 2
                            when {
                                fingers == 2 -> if (!scrolled && quick) send(RIGHT_CLICK)
                                quick -> if (fingers == 3) send(MIDDLE_CLICK)
                                dist > 70.dp.toPx() -> {
                                    val dir = if (abs(travel.x) > abs(travel.y)) {
                                        if (travel.x > 0) "R" else "L"
                                    } else {
                                        if (travel.y > 0) "D" else "U"
                                    }
                                    val combo = Settings.swipes["${minOf(fingers, 4)}$dir"]
                                    if (!combo.isNullOrBlank()) {
                                        parseCombo(combo)?.let { sendCombo(it, send) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(mode) {
                if (mode == PadMode.DRAW) {
                    detectDragGestures(
                        onDragStart = { start ->
                            drawing = true
                            recognized = null
                            points.clear()
                            points.add(start)
                        },
                        onDragEnd = {
                            drawing = false
                            strokes++
                            val result = Recognizer.recognize(points.toList())
                            if (result != null) {
                                val cmd = store.commands[result.name] ?: ""
                                send(
                                    JSONObject()
                                        .put("type", "Gesture")
                                        .put("name", result.name)
                                        .put("cmd", cmd)
                                        .toString()
                                )
                                recognized = "${result.name} ${(result.score * 100).toInt()}%"
                            } else {
                                recognized = "не распознано"
                            }
                        },
                        onDragCancel = {
                            drawing = false
                            strokes++
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            points.add(change.position)
                        }
                    )
                }
            }
            .pointerInput(mode) {
                if (mode == PadMode.TABLET) {
                    // Планшет: касание внутри рабочей области = перо на соответствующей точке экрана
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val rect = tabletRect(
                            size.width.toFloat(),
                            size.height.toFloat(),
                            TabletConfig.aspect
                        )
                        if (!rect.contains(down.position)) return@awaitEachGesture

                        send(penMsg("down", down.position, down.pressure, rect))
                        down.consume()
                        var upSent = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ch.consume()
                            if (!ch.pressed) {
                                send(PEN_UP)
                                upSent = true
                                break
                            }
                            send(penMsg("move", ch.position, ch.pressure, rect))
                        }
                        if (!upSent) send(PEN_UP)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (mode == PadMode.TABLET) {
                val r = tabletRect(size.width, size.height, TabletConfig.aspect)
                drawRect(
                    Palette.accent.copy(alpha = 0.08f),
                    topLeft = r.topLeft,
                    size = r.size
                )
                drawRect(
                    Palette.accent.copy(alpha = 0.6f),
                    topLeft = r.topLeft,
                    size = r.size,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            if (points.size > 1) {
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                drawPath(
                    path,
                    Palette.accent,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
        if (points.isEmpty()) {
            val label = when (mode) {
                PadMode.MOUSE -> "Трекпад"
                PadMode.DRAW -> recognized ?: "Нарисуй фигуру"
                PadMode.TABLET -> "Планшет"
            }
            Text(
                label,
                color = if (mode == PadMode.DRAW && recognized != null) Palette.accent
                else Palette.textDim.copy(alpha = 0.35f),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SidePanel(
    modifier: Modifier,
    mode: PadMode,
    onMode: (PadMode) -> Unit,
    send: (String) -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ClickButton("Левый клик", Modifier.weight(1f).fillMaxWidth(), true) { send(LEFT_CLICK) }
        ClickButton("Правый клик", Modifier.weight(1f).fillMaxWidth(), false) { send(RIGHT_CLICK) }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.panelHi)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Режим", color = Palette.textDim, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.bg)
            ) {
                ModeOption("↖", mode == PadMode.MOUSE, Modifier.weight(1f)) { onMode(PadMode.MOUSE) }
                ModeOption("✎", mode == PadMode.DRAW, Modifier.weight(1f)) { onMode(PadMode.DRAW) }
                ModeOption("🖊", mode == PadMode.TABLET, Modifier.weight(1f)) { onMode(PadMode.TABLET) }
            }
        }
    }
}

@Composable
fun ModeOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Palette.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else Palette.textDim, fontSize = 18.sp)
    }
}

@Composable
fun ClickButton(text: String, modifier: Modifier, accent: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.panelHi,
            contentColor = if (accent) Palette.accent else Palette.text
        ),
        border = BorderStroke(1.dp, if (accent) Palette.accent else Palette.border)
    ) {
        Text(text, fontSize = 14.sp, maxLines = 1, softWrap = false)
    }
}

// Кнопка-переключатель: когда включена, залита акцентным цветом
@Composable
fun ToggleButton(text: String, modifier: Modifier, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Palette.accent else Palette.panelHi,
            contentColor = if (active) Color.White else Palette.text
        ),
        border = BorderStroke(1.dp, if (active) Palette.accent else Palette.border)
    ) {
        Text(text, fontSize = 14.sp, maxLines = 1, softWrap = false)
    }
}