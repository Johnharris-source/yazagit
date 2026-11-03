package com.example.trackpad

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class Match(val name: String, val score: Float)

object Recognizer {
    private const val N = 64
    private const val MIN_SCORE = 0.82f
    private const val MIN_LENGTH = 80f // короче этого считаем случайным касанием

    private class Template(val name: String, val pts: List<Offset>)

    private val builtIn = mutableListOf<Template>()
    private var custom: List<Template> = emptyList()

    val builtInNames = listOf("circle", "triangle", "square", "check", "zigzag")

    init {
        val circle = (0 until 36).map { i ->
            val a = 2.0 * PI * i / 36
            Offset(cos(a).toFloat(), sin(a).toFloat())
        }
        addClosed("circle", circle)
        addClosed("triangle", listOf(Offset(0f, -1f), Offset(1f, 1f), Offset(-1f, 1f)))
        addClosed(
            "square",
            listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(1f, 1f), Offset(-1f, 1f))
        )
        addOpen("check", listOf(Offset(-1f, 0f), Offset(-0.4f, 0.8f), Offset(1f, -1f)))
        addOpen(
            "zigzag",
            listOf(
                Offset(-1f, 1f), Offset(-0.5f, -1f), Offset(0f, 1f),
                Offset(0.5f, -1f), Offset(1f, 1f)
            )
        )
    }

    // Свои фигуры: каждый нарисованный штрих становится шаблоном
    fun setCustom(list: List<CustomGesture>) {
        custom = list.flatMap { g ->
            g.strokes.filter { it.size >= 2 }.map { Template(g.name, normalize(resample(it, N))) }
        }
    }

    private fun addClosed(name: String, vertices: List<Offset>) {
        val base = resample(vertices + vertices.first(), N).dropLast(1)
        for (dir in 0..1) {
            val b = if (dir == 0) base else base.reversed()
            for (k in 0 until 16) {
                val shift = k * b.size / 16
                val rot = b.drop(shift) + b.take(shift)
                builtIn.add(Template(name, normalize(rot + rot.first())))
            }
        }
    }

    private fun addOpen(name: String, vertices: List<Offset>) {
        builtIn.add(Template(name, normalize(resample(vertices, N))))
    }

    fun recognize(stroke: List<Offset>): Match? {
        if (stroke.size < 8 || pathLength(stroke) < MIN_LENGTH) return null
        val cand = normalize(resample(stroke, N))
        var best: Match? = null
        for (t in builtIn + custom) {
            var sum = 0.0
            for (i in 0 until N) sum += (cand[i] - t.pts[i]).getDistance()
            val score = (1.0 - (sum / N) / 0.7071).toFloat()
            val b = best
            if (b == null || score > b.score) best = Match(t.name, score)
        }
        return best?.takeIf { it.score >= MIN_SCORE }
    }

    private fun pathLength(p: List<Offset>): Float {
        var d = 0f
        for (i in 1 until p.size) d += (p[i] - p[i - 1]).getDistance()
        return d
    }

    private fun resample(src: List<Offset>, n: Int): List<Offset> {
        val interval = pathLength(src) / (n - 1)
        if (interval == 0f) return List(n) { src[0] }
        val pts = src.toMutableList()
        val out = mutableListOf(pts[0])
        var d = 0f
        var i = 1
        while (i < pts.size) {
            val dist = (pts[i] - pts[i - 1]).getDistance()
            if (dist > 0f && d + dist >= interval) {
                val t = (interval - d) / dist
                val q = Offset(
                    pts[i - 1].x + t * (pts[i].x - pts[i - 1].x),
                    pts[i - 1].y + t * (pts[i].y - pts[i - 1].y)
                )
                out.add(q)
                pts.add(i, q)
                d = 0f
            } else {
                d += dist
            }
            i++
        }
        while (out.size < n) out.add(pts.last())
        return out.take(n)
    }

    private fun normalize(p: List<Offset>): List<Offset> {
        val minX = p.minOf { it.x }
        val maxX = p.maxOf { it.x }
        val minY = p.minOf { it.y }
        val maxY = p.maxOf { it.y }
        val w = max(maxX - minX, 1e-3f)
        val h = max(maxY - minY, 1e-3f)
        val s = p.map { Offset((it.x - minX) / w, (it.y - minY) / h) }
        val cx = s.map { it.x }.average().toFloat()
        val cy = s.map { it.y }.average().toFloat()
        return s.map { Offset(it.x - cx, it.y - cy) }
    }
}