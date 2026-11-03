package com.example.trackpad

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.unit.dp

// Названия клавиш и их коды в Linux (evdev)
val KEY_CODES: Map<String, Int> = mutableMapOf<String, Int>().apply {
    put("esc", 1)
    put("escape", 1)
    "1234567890".forEachIndexed { i, c -> put(c.toString(), 2 + i) }
    put("minus", 12)
    put("equal", 13)
    put("backspace", 14)
    put("tab", 15)
    "qwertyuiop".forEachIndexed { i, c -> put(c.toString(), 16 + i) }
    put("enter", 28)
    put("return", 28)
    put("ctrl", 29)
    put("control", 29)
    "asdfghjkl".forEachIndexed { i, c -> put(c.toString(), 30 + i) }
    put("shift", 42)
    "zxcvbnm".forEachIndexed { i, c -> put(c.toString(), 44 + i) }
    put("alt", 56)
    put("space", 57)
    put("capslock", 58)
    for (i in 1..10) put("f$i", 58 + i)
    put("f11", 87)
    put("f12", 88)
    put("print", 99)
    put("home", 102)
    put("up", 103)
    put("pageup", 104)
    put("left", 105)
    put("right", 106)
    put("end", 107)
    put("down", 108)
    put("pagedown", 109)
    put("insert", 110)
    put("delete", 111)
    put("mute", 113)
    put("volumedown", 114)
    put("volumeup", 115)
    put("super", 125)
    put("win", 125)
    put("meta", 125)
    put("next", 163)
    put("playpause", 164)
    put("prev", 165)
    put("[", 26)
    put("]", 27)
    put(";", 39)
    put("'", 40)
    put("`", 41)
    put("\\", 43)
    put(",", 51)
    put(".", 52)
    put("/", 53)
    put("-", 12)
    put("=", 13)
    put("rctrl", 97)
    put("rshift", 54)
    put("ralt", 100)
    put("menu", 127)
    put("pause", 119)
    put("numlock", 69)
    put("scrolllock", 70)
    put("kp0", 82)
    put("kp1", 79)
    put("kp2", 80)
    put("kp3", 81)
    put("kp4", 75)
    put("kp5", 76)
    put("kp6", 77)
    put("kp7", 71)
    put("kp8", 72)
    put("kp9", 73)
    put("kpenter", 96)
    put("kpplus", 78)
    put("kpminus", 74)
    put("kpmul", 55)
    put("kpdiv", 98)
    put("kpdot", 83)
    put("stop", 166)
    put("brightnessdown", 224)
    put("brightnessup", 225)
    for (i in 13..24) put("f$i", 170 + i)
}

fun parseCombo(text: String): List<Int>? {
    val parts = text.lowercase().split("+").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.map { p ->
        KEY_CODES[p]
            ?: (if (p.startsWith("code")) p.substring(4).toIntOrNull()?.takeIf { it in 1..248 } else null)
            ?: return null
    }
}
// Нажимаем все клавиши по порядку, потом отпускаем в обратном
fun sendCombo(codes: List<Int>, send: (String) -> Unit) {
    codes.forEach { send("""{"type":"Key","code":$it,"down":true}""") }
    codes.reversed().forEach { send("""{"type":"Key","code":$it,"down":false}""") }
}

class QuickKey(val label: String, val combo: String)

class QuickKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("quickkeys", Context.MODE_PRIVATE)
    val items = mutableStateListOf<QuickKey>()

    init {
        try {
            val arr = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(QuickKey(o.getString("label"), o.getString("combo")))
            }
        } catch (e: Exception) {
            // повреждённые данные игнорируем
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("label", it.label).put("combo", it.combo))
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun add(k: QuickKey) {
        items.add(k)
        save()
    }

    fun remove(k: QuickKey) {
        items.remove(k)
        save()
    }
}

@Composable
fun QuickKeysDialog(store: QuickKeyStore, onClose: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var combo by remember { mutableStateOf("") }
    val codes = parseCombo(combo)
    val canAdd = label.isNotBlank() && codes != null

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
        title = { Text("Кнопки на экране") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                store.items.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${k.label}  (${k.combo})", Modifier.weight(1f))
                        TextButton(onClick = { store.remove(k) }) { Text("✕") }
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Название кнопки") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = combo,
                    onValueChange = { combo = it },
                    label = { Text("Сочетание, например ctrl+c") },
                    singleLine = true
                )
                if (combo.isNotBlank() && codes == null) {
                    Text("Не знаю такую клавишу", fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        if (canAdd) {
                            store.add(QuickKey(label.trim(), combo.trim().lowercase()))
                            label = ""
                            combo = ""
                        }
                    },
                    enabled = canAdd
                ) { Text("Добавить") }
                Text(
                    "Клавиши: ctrl, shift, alt, super, буквы, цифры, f1-f12, esc, tab, enter, " +
                            "space, backspace, delete, home, end, pageup, pagedown, up, down, left, " +
                            "right, print, mute, volumeup, volumedown, playpause, next, prev. " +
                            "Соединяй знаком +.",
                    fontSize = 11.sp
                )
            }
        }
    )
}