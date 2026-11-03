package com.example.trackpad

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

const val PEN_UP = """{"type":"Pen","phase":"up"}"""

// Настройки режима планшета
object TabletConfig {
    var aspect by mutableStateOf(16f / 9f)
    var usePressure by mutableStateOf(true)

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        val p = context.getSharedPreferences("tablet", Context.MODE_PRIVATE)
        prefs = p
        aspect = p.getFloat("aspect", 16f / 9f)
        usePressure = p.getBoolean("usePressure", true)
    }

    fun save() {
        prefs?.edit()
            ?.putFloat("aspect", aspect)
            ?.putBoolean("usePressure", usePressure)
            ?.apply()
    }
}

// Самый большой прямоугольник нужных пропорций по центру поля
fun tabletRect(w: Float, h: Float, aspect: Float): Rect {
    val aw: Float
    val ah: Float
    if (w / h > aspect) {
        ah = h
        aw = h * aspect
    } else {
        aw = w
        ah = w / aspect
    }
    val left = (w - aw) / 2f
    val top = (h - ah) / 2f
    return Rect(left, top, left + aw, top + ah)
}

fun penMsg(phase: String, pos: Offset, pressure: Float, rect: Rect): String {
    val nx = ((pos.x - rect.left) / rect.width).coerceIn(0f, 1f)
    val ny = ((pos.y - rect.top) / rect.height).coerceIn(0f, 1f)
    val p = if (TabletConfig.usePressure) pressure.coerceIn(0.02f, 1f) else 1f
    return """{"type":"Pen","phase":"$phase","x":$nx,"y":$ny,"p":$p}"""
}

val ASPECTS = listOf(
    "16:9" to 16f / 9f,
    "16:10" to 1.6f,
    "3:2" to 1.5f,
    "4:3" to 4f / 3f,
    "21:9" to 21f / 9f
)

@Composable
fun TabletSettingsCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.panelHi)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Режим планшета", color = Palette.text)
        Text(
            "Рабочая область на поле соответствует экрану ноутбука. Выбери его пропорции.",
            color = Palette.textDim,
            fontSize = 12.sp
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ASPECTS.forEach { (name, value) ->
                val selected = abs(TabletConfig.aspect - value) < 0.01f
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Palette.accent else Palette.bg)
                        .clickable {
                            TabletConfig.aspect = value
                            TabletConfig.save()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(name, color = if (selected) Color.White else Palette.textDim)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Нажим пера (если телефон его отдаёт)",
                color = Palette.text,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = TabletConfig.usePressure,
                onCheckedChange = {
                    TabletConfig.usePressure = it
                    TabletConfig.save()
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Palette.accent,
                    checkedThumbColor = Color.White
                )
            )
        }
    }
}