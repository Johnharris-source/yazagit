package com.example.trackpad

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

// Все настройки приложения, сохраняются в памяти телефона
object Settings {
    var sensitivity by mutableStateOf(1.5f)
    var scrollSpeed by mutableStateOf(1f)
    var holdMs by mutableStateOf(400f)
    var invertScroll by mutableStateOf(false)

    // Сочетания клавиш для свайпов: ключ вида "3L" (3 пальца влево), L R U D
    val swipes = mutableStateMapOf<String, String>()

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        sensitivity = p.getFloat("sensitivity", 1.5f)
        scrollSpeed = p.getFloat("scrollSpeed", 1f)
        holdMs = p.getFloat("holdMs", 400f)
        invertScroll = p.getBoolean("invertScroll", false)
        Palette.accent = Color(p.getInt("accent", Palette.DEFAULT_ACCENT))
        swipes.clear()
        try {
            val o = JSONObject(p.getString("swipes", "{}") ?: "{}")
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                swipes[k] = o.getString(k)
            }
        } catch (e: Exception) {
            // повреждённые данные игнорируем
        }
    }

    fun save() {
        prefs?.edit()
            ?.putFloat("sensitivity", sensitivity)
            ?.putFloat("scrollSpeed", scrollSpeed)
            ?.putFloat("holdMs", holdMs)
            ?.putBoolean("invertScroll", invertScroll)
            ?.putInt("accent", Palette.accent.toArgb())
            ?.putString("swipes", JSONObject(swipes.toMap()).toString())
            ?.apply()
    }

    fun reset() {
        sensitivity = 1.5f
        scrollSpeed = 1f
        holdMs = 400f
        invertScroll = false
        swipes.clear()
        Palette.accent = Color(Palette.DEFAULT_ACCENT)
        save()
    }
}

val ACCENT_PRESETS = listOf<Long>(
    0xFF3B82F6, 0xFF06B6D4, 0xFF22C55E, 0xFFF97316,
    0xFFEF4444, 0xFFEC4899, 0xFF8B5CF6
)

private fun hexOf(c: Color): String = "%06X".format(c.toArgb() and 0xFFFFFF)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .safeDrawingPadding()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ClickButton("← Назад", Modifier.width(110.dp).fillMaxHeight(), false) { onBack() }
            Spacer(Modifier.width(12.dp))
            Text("Настройки", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            ClickButton("Сбросить", Modifier.width(110.dp).fillMaxHeight(), false) {
                Settings.reset()
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SliderRow(
                "Чувствительность курсора",
                "%.1f×".format(Settings.sensitivity),
                Settings.sensitivity,
                0.5f..4f
            ) { Settings.sensitivity = it }
            SliderRow(
                "Скорость скролла",
                "%.1f×".format(Settings.scrollSpeed),
                Settings.scrollSpeed,
                0.3f..3f
            ) { Settings.scrollSpeed = it }
            SliderRow(
                "Удержание для перетаскивания",
                "${Settings.holdMs.toInt()} мс",
                Settings.holdMs,
                200f..900f
            ) { Settings.holdMs = it }
            SwitchRow("Обратное направление скролла", Settings.invertScroll) {
                Settings.invertScroll = it
            }
            SwipesEditor()
            TabletSettingsCard()
            AccentPicker()
        }
    }
}

@Composable
fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.panelHi)
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = Palette.text, modifier = Modifier.weight(1f))
            Text(valueText, color = Palette.accent, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = { Settings.save() },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Palette.accent,
                activeTrackColor = Palette.accent,
                inactiveTrackColor = Palette.border
            )
        )
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.panelHi)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Palette.text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = {
                onChange(it)
                Settings.save()
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.accent,
                checkedThumbColor = Color.White
            )
        )
    }
}

@Composable
fun SwipeField(key: String, label: String, modifier: Modifier) {
    val value = Settings.swipes[key] ?: ""
    OutlinedTextField(
        value = value,
        onValueChange = {
            Settings.swipes[key] = it.lowercase()
            Settings.save()
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        isError = value.isNotBlank() && parseCombo(value) == null,
        modifier = modifier,
        colors = fieldColors()
    )
}

@Composable
fun SwipesEditor() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.panelHi)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Жесты несколькими пальцами", color = Palette.text)
        Text(
            "Два пальца: скролл, тап = правый клик. Три пальца: тап = средний клик. " +
                    "Свайпы привязываются к сочетаниям клавиш, например alt+left. " +
                    "Пустое поле: ничего не делать.",
            color = Palette.textDim,
            fontSize = 12.sp
        )
        for (n in 3..4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SwipeField("${n}L", "$n пальца ←", Modifier.weight(1f))
                SwipeField("${n}R", "$n пальца →", Modifier.weight(1f))
                SwipeField("${n}U", "$n пальца ↑", Modifier.weight(1f))
                SwipeField("${n}D", "$n пальца ↓", Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun AccentPicker() {
    var hex by remember(Palette.accent) { mutableStateOf(hexOf(Palette.accent)) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.panelHi)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Цвет акцента", color = Palette.text)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ACCENT_PRESETS.forEach { p ->
                val c = Color(p)
                val selected = c.toArgb() == Palette.accent.toArgb()
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) Color.White else Palette.border,
                            CircleShape
                        )
                        .clickable {
                            Palette.accent = c
                            Settings.save()
                        }
                )
            }
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { v ->
                val clean = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                hex = clean
                if (clean.length == 6) {
                    Palette.accent = Color(0xFF000000 or clean.toLong(16))
                    Settings.save()
                }
            },
            label = { Text("Свой цвет, HEX (например 3B82F6)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )
    }
}