package com.hinnka.mycamera.raw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Neutral firmware parameters, with no image-dependent calibration or additional controls. */
internal data class FujiRenderPlan(
    val style: FujiFilmSimulation,
    val tables: FloatArray,
    val curveEdges: FloatArray,
    val matrix: FloatArray,
    val colorMatrix: FloatArray,
    val luminance: FloatArray,
    val cube: FloatArray,
)

internal object FujiProfile {
    private const val ROOT = "fuji/fwup0030"
    private val cache = mutableMapOf<FujiFilmSimulation, FujiRenderPlan>()

    @Synchronized
    fun createRenderPlan(context: Context, style: FujiFilmSimulation): FujiRenderPlan =
        cache.getOrPut(style) {
            val root = JSONObject(context.assets.open("$ROOT/profiles.json")
                .bufferedReader().use { it.readText() })
            require(root.getInt("format_version") == 2)
            val p = root.getJSONObject("profiles").getJSONObject(style.persistedValue)
            require(p.getInt("internal") == style.firmwareMode && p.getInt("renderer") == style.renderer)
            val fields = p.getJSONObject("reference_fields")
            require(fields.getInt("0x114") == 8 && fields.getInt("0x118") == 8 &&
                fields.getInt("0x124") == 18 && fields.getInt("0x128") == 18 &&
                fields.getInt("0x13c") == 4 && fields.getInt("0x12c") == 1 &&
                fields.getInt("0x130") == style.renderer)
            // Row 0: 40 Q9 gain coefficients. Rows 1..3: 31 u14 output coefficients.
            val tables = FloatArray(40 * 4)
            p.getJSONArray("curve40").numbers(40, 0..32767).forEachIndexed { i, v ->
                tables[i] = (v / 512.0).toFloat()
            }
            val curves = p.getJSONArray("tables31")
            require(curves.length() == 3)
            for (channel in 0..2) {
                curves.getJSONArray(channel).numbers(31, 0..16383).forEachIndexed { i, v ->
                    tables[(channel + 1) * 40 + i] = (v / 16384.0).toFloat()
                }
            }
            // The packer writes one shared coordinate schedule, taken from the middle slot.
            val controls = p.getJSONArray("tables31_shared_controls").numbers(10, 0..9)
            val edges = FloatArray(11)
            for (i in 0..9) edges[i + 1] = edges[i] + (1 shl (controls[i].toInt() + 5))
            require(edges.last() == 16384f)
            val matrix = p.getJSONArray("coefficients9").numbers(9, -4096..4095).map { it / 1024.0 }
            val forward = p.getJSONArray("rgb_to_ycc_coefficients9").numbers(9, -4096..4095).map { it / 1024.0 }
            val chroma = p.getJSONArray("chroma_coefficients4").numbers(4, -4096..4095).map { it / 1024.0 }
            // Documented inference: Cr-first four-coefficient operator, not a fitted matrix.
            val op = listOf(1.0, 0.0, 0.0, 0.0, chroma[2], chroma[3], 0.0, chroma[1], chroma[0])
            val color = multiply(inverse(forward), multiply(op, forward))
            val lut = p.getInt("lut_index")
            require(p.getInt("lut_node_transform") == 0)
            require(lut == when (style) {
                FujiFilmSimulation.NostalgicNeg -> 2
                FujiFilmSimulation.RealaAce -> 3
                else -> 0
            })
            val bytes = context.assets.open("$ROOT/lut-$lut.bin").use { it.readBytes() }
            require(bytes.size == 18 * 18 * 18 * 8)
            val expectedHash = root.getJSONObject("lut_sha256").getString(lut.toString())
            require(MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) } == expectedHash)
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val cube = FloatArray(17 * 17 * 17 * 3)
            for (z in 0..17) for (y in 0..17) for (x in 0..17) {
                val padding = x == 17 || y == 17 || z == 17
                for (channel in 0..3) {
                    val code = input.short.toInt() and 0xffff
                    require(code in 0..4095 && (!(padding || channel == 3) || code == 0))
                    if (!padding && channel < 3) {
                        cube[((z * 17 + y) * 17 + x) * 3 + channel] = code / 4095f
                    }
                }
            }
            FujiRenderPlan(style, tables, edges, columnMajor(matrix), columnMajor(color),
                FloatArray(3) { forward[it].toFloat() }, cube)
        }

    private fun JSONArray.numbers(count: Int, range: IntRange): List<Double> {
        require(length() == count)
        return List(count) { getInt(it).also { v -> require(v in range) }.toDouble() }
    }

    private fun columnMajor(m: List<Double>) = FloatArray(9) { m[(it % 3) * 3 + it / 3].toFloat() }

    private fun multiply(a: List<Double>, b: List<Double>) = List(9) { i ->
        (0..2).sumOf { k -> a[(i / 3) * 3 + k] * b[k * 3 + i % 3] }
    }

    private fun inverse(m: List<Double>): List<Double> {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(kotlin.math.abs(determinant) > 1e-8)
        return listOf(e*i-f*h, c*h-b*i, b*f-c*e, f*g-d*i, a*i-c*g, c*d-a*f,
            d*h-e*g, b*g-a*h, a*e-b*d).map { it / determinant }
    }
}
