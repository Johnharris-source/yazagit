package com.example.trackpad

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

class CustomGesture(val name: String, val strokes: List<List<Offset>>)

// Хранит команды для всех фигур и шаблоны своих фигур в памяти приложения
class GestureStore(context: Context) {
    private val prefs = context.getSharedPreferences("gestures", Context.MODE_PRIVATE)
    val commands = mutableStateMapOf<String, String>()
    val custom = mutableStateListOf<CustomGesture>()

    init {
        Recognizer.builtInNames.forEach { commands[it] = "" }
        try {
            val raw = prefs.getString("data", null)
            if (raw != null) {
                val root = JSONObject(raw)
                val cm = root.optJSONObject("commands")
                if (cm != null) {
                    val keys = cm.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        commands[k] = cm.getString(k)
                    }
                }
                val arr = root.optJSONArray("custom")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val ss = o.getJSONArray("strokes")
                        val strokes = (0 until ss.length()).map { si ->
                            val pa = ss.getJSONArray(si)
                            (0 until pa.length() / 2).map { j ->
                                Offset(
                                    pa.getDouble(2 * j).toFloat(),
                                    pa.getDouble(2 * j + 1).toFloat()
                                )
                            }
                        }
                        custom.add(CustomGesture(o.getString("name"), strokes))
                    }
                }
            }
        } catch (e: Exception) {
            // повреждённые данные игнорируем
        }
        Recognizer.setCustom(custom)
    }

    private fun save() {
        val root = JSONObject()
        val cm = JSONObject()
        commands.forEach { (k, v) -> cm.put(k, v) }
        root.put("commands", cm)
        val arr = JSONArray()
        custom.forEach { g ->
            val o = JSONObject()
            o.put("name", g.name)
            val ss = JSONArray()
            g.strokes.forEach { st ->
                val pa = JSONArray()
                st.forEach { p ->
                    pa.put(p.x.toDouble())
                    pa.put(p.y.toDouble())
                }
                ss.put(pa)
            }
            o.put("strokes", ss)
            arr.put(o)
        }
        root.put("custom", arr)
        prefs.edit().putString("data", root.toString()).apply()
    }

    fun setCommand(name: String, cmd: String) {
        commands[name] = cmd
        save()
    }

    fun addCustom(g: CustomGesture, cmd: String) {
        custom.add(g)
        commands[g.name] = cmd
        Recognizer.setCustom(custom)
        save()
    }

    fun removeCustom(name: String) {
        custom.removeAll { it.name == name }
        commands.remove(name)
        Recognizer.setCustom(custom)
        save()
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.text,
    unfocusedTextColor = Palette.text,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.border,
    cursorColor = Palette.accent,
    focusedLabelColor = Palette.accent,
    unfocusedLabelColor = Palette.textDim,
    focusedPlaceholderColor = Palette.textDim,
    unfocusedPlaceholderColor = Palette.textDim
)

@Composable
fun GesturesScreen(store: GestureStore, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    BackHandler { if (adding) adding = false else onBack() }

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
            ClickButton("← Назад", Modifier.width(110.dp).fillMaxHeight(), false) {
                if (adding) adding = false else onBack()
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (adding) "Новая фигура" else "Жесты",
                color = Palette.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            if (!adding) {
                ClickButton("+ Добавить", Modifier.width(130.dp).fillMaxHeight(), true) {
                    adding = true
                }
            }
        }
        if (adding) AddGesture(store) { adding = false } else GestureList(store)
    }
}

@Composable
fun GestureList(store: GestureStore) {
    val names = Recognizer.builtInNames + store.custom.map { it.name }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(names, key = { it }) { name ->
            val isCustom = name !in Recognizer.builtInNames
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.panelHi)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.width(120.dp)) {
                    Text(name, color = Palette.text, fontWeight = FontWeight.Bold)
                    Text(
                        if (isCustom) "своя" else "встроенная",
                        color = Palette.textDim,
                        fontSize = 11.sp
                    )
                }
                OutlinedTextField(
                    value = store.commands[name] ?: "",
                    onValueChange = { store.setCommand(name, it) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("команда, например firefox") },
                    colors = fieldColors()
                )
                if (isCustom) {
                    TextButton(onClick = { store.removeCustom(name) }) {
                        Text("✕", color = Palette.warn)
                    }
                }
            }
        }
    }
}

@Composable
fun AddGesture(store: GestureStore, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    val needed = 3

    val cleanName = name.trim()
    val nameOk = cleanName.isNotEmpty() &&
            cleanName !in Recognizer.builtInNames &&
            store.custom.none { it.name == cleanName }
    val canSave = nameOk && strokes.size >= needed
    val hint = when {
        !nameOk -> "Введи уникальное имя"
        strokes.size < needed -> "Нарисуй фигуру ещё раз: ${needed - strokes.size}"
        else -> "Готово, можно сохранять"
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя фигуры") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                label = { Text("Команда на ноутбуке") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Text(
                "Нарисовано: ${strokes.size} из $needed. $hint",
                color = Palette.textDim,
                fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickButton("Сбросить", Modifier.weight(1f).height(44.dp), false) {
                    strokes.clear()
                    current.clear()
                }
                ClickButton("Сохранить", Modifier.weight(1f).height(44.dp), true) {
                    if (canSave) {
                        store.addCustom(CustomGesture(cleanName, strokes.toList()), cmd.trim())
                        onDone()
                    }
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.panel)
                .border(2.dp, Palette.accent, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            current.clear()
                            current.add(start)
                        },
                        onDragEnd = {
                            if (current.size >= 8 && strokes.size < needed) {
                                strokes.add(current.toList())
                            }
                            current.clear()
                        },
                        onDragCancel = { current.clear() },
                        onDrag = { change, _ ->
                            change.consume()
                            current.add(change.position)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                fun line(pts: List<Offset>, alpha: Float) {
                    if (pts.size > 1) {
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                        }
                        drawPath(
                            path,
                            Palette.accent.copy(alpha = alpha),
                            style = Stroke(
                                width = 5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
                strokes.forEach { line(it, 0.3f) }
                line(current, 1f)
            }
            if (strokes.isEmpty() && current.isEmpty()) {
                Text(
                    "Рисуй здесь",
                    color = Palette.textDim.copy(alpha = 0.35f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}