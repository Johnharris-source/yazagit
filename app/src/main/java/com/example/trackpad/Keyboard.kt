package com.example.trackpad

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Настройка клавиатуры: чем переключать раскладку на ноутбуке
object KeyboardConfig {
    var switchCombo by mutableStateOf("alt+shift")

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        val p = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
        prefs = p
        switchCombo = p.getString("switchCombo", "alt+shift") ?: "alt+shift"
    }

    fun save() {
        prefs?.edit()?.putString("switchCombo", switchCombo)?.apply()
    }
}

@Composable
fun KeyboardSettingsCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.panelHi)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Экранная клавиатура", color = Palette.text)
        Text(
            "Кнопка EN/RU на клавиатуре посылает на ноутбук это сочетание для смены раскладки. " +
                    "Впиши то, что стоит в системе: alt+shift, ctrl+shift или super+space.",
            color = Palette.textDim,
            fontSize = 12.sp
        )
        OutlinedTextField(
            value = KeyboardConfig.switchCombo,
            onValueChange = {
                KeyboardConfig.switchCombo = it.lowercase()
                KeyboardConfig.save()
            },
            label = { Text("Сочетание смены раскладки") },
            singleLine = true,
            isError = parseCombo(KeyboardConfig.switchCombo) == null,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )
    }
}

// kind: 0 обычная клавиша, 1 модификатор (Shift, Ctrl, Alt, Super)
class KbKey(
    val code: Int,
    val en: String,
    val ru: String = en,
    val weight: Float = 1f,
    val kind: Int = 0
)

private fun k(code: Int, en: String, ru: String = en, w: Float = 1f) = KbKey(code, en, ru, w, 0)
private fun m(code: Int, en: String, w: Float = 1f) = KbKey(code, en, en, w, 1)

private val NUM_ROW: List<KbKey> =
    listOf(k(41, "`", "ё")) +
            "1234567890".mapIndexed { i, c -> k(2 + i, c.toString()) } +
            listOf(k(12, "-"), k(13, "="), k(14, "⌫", w = 1.6f))

private val FN_ROW: List<KbKey> =
    listOf(k(1, "Esc")) +
            (1..10).map { k(58 + it, "F$it") } +
            listOf(k(87, "F11"), k(88, "F12"), k(111, "Del", w = 1.6f))

private val TOP_ROW: List<KbKey> =
    listOf(k(15, "Tab", w = 1.4f)) +
            "qwertyuiop".mapIndexed { i, c -> k(16 + i, c.toString(), "йцукенгшщз"[i].toString()) } +
            listOf(k(26, "[", "х"), k(27, "]", "ъ"), k(43, "\\", w = 1.2f))

private val HOME_ROW: List<KbKey> =
    listOf(k(58, "Caps", w = 1.7f)) +
            "asdfghjkl".mapIndexed { i, c -> k(30 + i, c.toString(), "фывапролд"[i].toString()) } +
            listOf(k(39, ";", "ж"), k(40, "'", "э"), k(28, "Enter", w = 1.9f))

private val SHIFT_ROW: List<KbKey> =
    listOf(m(42, "Shift", 2.2f)) +
            "zxcvbnm".mapIndexed { i, c -> k(44 + i, c.toString(), "ячсмить"[i].toString()) } +
            listOf(k(51, ",", "б"), k(52, ".", "ю"), k(53, "/"), m(54, "Shift", 2.4f))

private val SHIFTED = mapOf(
    "`" to "~", "1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%",
    "6" to "^", "7" to "&", "8" to "*", "9" to "(", "0" to ")", "-" to "_",
    "=" to "+", "[" to "{", "]" to "}", "\\" to "|", ";" to ":", "'" to "\"",
    "," to "<", "." to ">", "/" to "?"
)

private fun labelFor(key: KbKey, ru: Boolean, shift: Boolean): String {
    val base = if (ru) key.ru else key.en
    if (!shift || base.length != 1) return base
    return if (ru) base.uppercase() else (SHIFTED[base] ?: base.uppercase())
}

private fun keyJson(code: Int, down: Boolean): String =
    """{"type":"Key","code":$code,"down":$down}"""

@Composable
fun KeyboardScreen(send: (String) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val mods = remember { mutableStateListOf<Int>() }
    var ru by remember { mutableStateOf(false) }
    var fn by remember { mutableStateOf(false) }
    val shift = (42 in mods) || (54 in mods)
    val rows = listOf(if (fn) FN_ROW else NUM_ROW, TOP_ROW, HOME_ROW, SHIFT_ROW)

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .safeDrawingPadding()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ClickButton("← Назад", Modifier.width(110.dp).fillMaxHeight(), false) { onBack() }
            Spacer(Modifier.width(12.dp))
            Text("Клавиатура", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        rows.forEach { row ->
            KbRow(row, ru, shift, mods, send, Modifier.weight(1f))
        }

        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModKey(29, "Ctrl", Modifier.weight(1.3f).fillMaxHeight(), mods)
            ModKey(125, "⌘", Modifier.weight(1.2f).fillMaxHeight(), mods)
            ModKey(56, "Alt", Modifier.weight(1.2f).fillMaxHeight(), mods)
            RegularKey(57, "Space", Modifier.weight(3.7f).fillMaxHeight(), mods, send)
            ModKey(100, "Alt", Modifier.weight(1.2f).fillMaxHeight(), mods)
            ToggleCap("Fn", fn, Modifier.weight(1f).fillMaxHeight()) { fn = !fn }
            ToggleCap(if (ru) "RU" else "EN", false, Modifier.weight(1f).fillMaxHeight()) {
                ru = !ru
                parseCombo(KeyboardConfig.switchCombo)?.let { sendCombo(it, send) }
            }
            RegularKey(105, "←", Modifier.weight(1f).fillMaxHeight(), mods, send)
            RegularKey(103, "↑", Modifier.weight(1f).fillMaxHeight(), mods, send)
            RegularKey(108, "↓", Modifier.weight(1f).fillMaxHeight(), mods, send)
            RegularKey(106, "→", Modifier.weight(1f).fillMaxHeight(), mods, send)
        }
    }
}

@Composable
fun KbRow(
    row: List<KbKey>,
    ru: Boolean,
    shift: Boolean,
    mods: MutableList<Int>,
    send: (String) -> Unit,
    modifier: Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        row.forEach { key ->
            val mod = Modifier.weight(key.weight).fillMaxHeight()
            if (key.kind == 1) {
                ModKey(key.code, key.en, mod, mods)
            } else {
                RegularKey(key.code, labelFor(key, ru, shift), mod, mods, send)
            }
        }
    }
}

@Composable
fun KeyCap(label: String, modifier: Modifier, active: Boolean, pressed: Boolean) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    active -> Palette.accent
                    pressed -> Palette.border
                    else -> Palette.panelHi
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) androidx.compose.ui.graphics.Color.White else Palette.text,
            fontSize = if (label.length > 1) 12.sp else 16.sp,
            maxLines = 1
        )
    }
}

// Обычная клавиша: нажатие и отпускание отправляются отдельно, поэтому удержание повторяет символ.
// Выбранные модификаторы нажимаются вместе с ней и отпускаются после.
@Composable
fun RegularKey(
    code: Int,
    label: String,
    modifier: Modifier,
    mods: MutableList<Int>,
    send: (String) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    var used by remember { mutableStateOf<List<Int>>(emptyList()) }
    val sendNow by rememberUpdatedState(send)

    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val d = awaitFirstDown()
                d.consume()
                pressed = true
                used = mods.toList()
                used.forEach { sendNow(keyJson(it, true)) }
                sendNow(keyJson(code, true))
                while (true) {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { it.consume() }
                    if (ev.changes.none { it.pressed }) break
                }
                pressed = false
                sendNow(keyJson(code, false))
                used.asReversed().forEach { sendNow(keyJson(it, false)) }
                if (used.isNotEmpty()) mods.clear()
            }
        }
    ) {
        KeyCap(label, Modifier.matchParentSize(), false, pressed)
    }
}

@Composable
fun ModKey(code: Int, label: String, modifier: Modifier, mods: MutableList<Int>) {
    val active = code in mods
    Box(
        modifier.clickable {
            if (code in mods) mods.remove(code) else mods.add(code)
        }
    ) {
        KeyCap(label, Modifier.matchParentSize(), active, false)
    }
}

@Composable
fun ToggleCap(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clickable(onClick = onClick)) {
        KeyCap(label, Modifier.matchParentSize(), active, false)
    }
}