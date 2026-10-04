package io.github.mgdx.sceau.mrz

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Fuzzing de l'entrée de [TemplateLineRecognizer], sur le modèle de `core/…/fuzz/FuzzHarness.kt`
 * (variables d'environnement `SCEAU_FUZZ_ITERATIONS`, `SCEAU_FUZZ_SEED`, `SCEAU_FUZZ_INDEX`,
 * `SCEAU_FUZZ_TIMEOUT_MILLIS` ; propriétés système `sceau.fuzz.*` depuis l'IDE) :
 * dimensions extrêmes ou incohérentes, `rowStride` faux, tampon trop court, octets aléatoires,
 * images de MRZ de synthèse mutées (bandes, blocs, octets). Propriété : jamais d'exception ni
 * de délai dépassé, et un résultat non nul respecte le contrat de [LineRecognizer].
 *
 * Mode long : `SCEAU_FUZZ_ITERATIONS=20000 ./gradlew :mrz:test --tests '*Fuzz*' --rerun`.
 */
class RecognizerFuzzTest {
    private fun setting(name: String): String? =
        System.getProperty("sceau.fuzz.$name")?.takeIf { it.isNotBlank() }
            ?: System.getenv("SCEAU_FUZZ_" + name.replace(Regex("([A-Z])"), "_$1").uppercase())?.takeIf { it.isNotBlank() }

    private val seed = setting("seed")?.let { if (it.startsWith("0x")) it.substring(2).toLong(16) else it.toLong() } ?: DEFAULT_SEED
    private val iterations = setting("iterations")?.toInt() ?: DEFAULT_ITERATIONS
    private val only = setting("index")?.toInt()
    private val timeoutMillis = setting("timeoutMillis")?.toLong() ?: DEFAULT_TIMEOUT_MILLIS

    /** Images valides servant de graines aux mutations. */
    private val seeds: List<LumaFrame> by lazy {
        listOf(
            Scene(MrzFormat.TD3, seed = 1, width = 640, height = 200),
            Scene(MrzFormat.TD2, seed = 2, width = 640, height = 220, rotationDegrees = 90, rowPadding = 8),
            Scene(MrzFormat.TD1, seed = 3, width = 640, height = 300, angleDegrees = 5.0, rotationDegrees = 180),
        ).map { SceneRenderer.render(it).frame }
    }

    @Test
    fun recognizerNeverThrows() {
        val recognizer = TemplateLineRecognizer()
        val failures = ArrayList<String>()
        val indices = only?.let { it..it } ?: 0 until iterations
        for (index in indices) {
            val random = Random(seed xor (index.toLong() * -0x61c8864680b583ebL))
            val frame = input(random)
            val where = "seed=0x${seed.toString(16)} index=$index"
            val start = System.nanoTime()
            try {
                val result = recognizer.recognize(frame)
                if (result != null) checkContract(result)?.let { failures += "$where : $it" }
            } catch (e: Throwable) {
                failures += "$where : ${e.javaClass.name} à ${e.stackTrace.firstOrNull()}"
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (elapsed > timeoutMillis) failures += "$where : ${elapsed}ms"
            if (failures.size >= MAX_REPORTED) break
        }
        assertTrue("fuzz[recognizer] : ${failures.size} échec(s)\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun checkContract(result: RecognizedMrz): String? {
        val expected =
            when (result.format) {
                MrzFormat.TD1 -> listOf(30, 30)
                MrzFormat.TD2 -> listOf(36)
                MrzFormat.TD3 -> listOf(44)
            }
        if (result.lines.map { it.size } != expected) return "lignes ${result.lines.map { it.size }} pour ${result.format}"
        for (glyph in result.lines.flatten()) {
            if (glyph.ranked.size < 2) return "moins de 2 candidats"
            if (glyph.ranked.any { it.first !in OcrbTemplates.CLASSES || it.second !in 0f..1f }) return "candidat hors contrat"
            if (glyph.ranked.zipWithNext().any { (a, b) -> a.second < b.second }) return "candidats mal triés"
        }
        return null
    }

    private fun input(random: Random): LumaFrame =
        when (random.nextInt(4)) {
            0 -> arbitrary(random)
            1 -> noise(random)
            else -> mutated(random)
        }

    /** Dimensions, pas de ligne, rotation et taille de tampon quelconques (souvent incohérents). */
    private fun arbitrary(random: Random): LumaFrame {
        val width = dimension(random)
        val height = dimension(random)
        val stride =
            when (random.nextInt(5)) {
                0 -> width
                1 -> width + random.nextInt(64)
                2 -> width - 1 - random.nextInt(4)
                3 -> random.nextInt()
                else -> dimension(random)
            }
        val rotation = if (random.nextInt(4) == 0) random.nextInt() else 90 * random.nextInt(4)
        val needed = if (width > 0 && height > 0 && stride > 0) (height - 1).toLong() * stride + width else 0L
        val size =
            when {
                needed in 1..MAX_ALLOCATION && random.nextBoolean() -> needed.toInt() - random.nextInt(2)
                needed in 1..MAX_ALLOCATION -> needed.toInt()
                else -> random.nextInt(4096)
            }.coerceAtLeast(0)
        val data = ByteArray(size).also(random::nextBytes)
        return LumaFrame(data, width, height, stride, rotation)
    }

    private fun dimension(random: Random): Int =
        when (random.nextInt(6)) {
            0 -> random.nextInt(4) - 1
            1 -> 4095 + random.nextInt(3)
            2 -> random.nextInt()
            3 -> 1 + random.nextInt(64)
            else -> 1 + random.nextInt(1400)
        }

    /** Image de bonne taille, octets aléatoires ou bandes horizontales régulières (fausses lignes). */
    private fun noise(random: Random): LumaFrame {
        val width = 200 + random.nextInt(1200)
        val height = 40 + random.nextInt(600)
        val data = ByteArray(width * height)
        if (random.nextBoolean()) {
            random.nextBytes(data)
        } else {
            val period = 4 + random.nextInt(60)
            val pitch = 2 + random.nextInt(40)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val dark = (y % period) < period / 2 && (x % pitch) < pitch / 2
                    data[y * width + x] = (if (dark) random.nextInt(80) else 160 + random.nextInt(96)).toByte()
                }
            }
        }
        return LumaFrame(data, width, height, width, 90 * random.nextInt(4))
    }

    /** Graine mutée : octets, blocs, rangées, ou métadonnées décalées. */
    private fun mutated(random: Random): LumaFrame {
        val base = seeds[random.nextInt(seeds.size)]
        val data = base.data.copyOf()
        repeat(1 + random.nextInt(8)) {
            when (random.nextInt(5)) {
                0 -> {
                    repeat(1 + random.nextInt(500)) { data[random.nextInt(data.size)] = random.nextInt().toByte() }
                }

                1 -> {
                    val start = random.nextInt(data.size)
                    data.fill(random.nextInt().toByte(), start, minOf(data.size, start + random.nextInt(20_000)))
                }

                2 -> {
                    val row = random.nextInt(base.height)
                    val value = random.nextInt().toByte()
                    for (r in row until minOf(base.height, row + 1 + random.nextInt(40))) {
                        data.fill(value, r * base.rowStride, r * base.rowStride + base.width)
                    }
                }

                3 -> {
                    val column = random.nextInt(base.width)
                    for (r in 0 until base.height) data[r * base.rowStride + column] = 0
                }

                else -> {
                    data.reverse(0, random.nextInt(data.size))
                }
            }
        }
        return when (random.nextInt(4)) {
            0 -> LumaFrame(data, base.width, base.height, base.rowStride + random.nextInt(3) - 1, base.rotationDegrees)
            1 -> LumaFrame(data, base.width - random.nextInt(50), base.height - random.nextInt(50), base.rowStride, 90 * random.nextInt(4))
            else -> LumaFrame(data, base.width, base.height, base.rowStride, base.rotationDegrees)
        }
    }

    private companion object {
        const val DEFAULT_SEED = 0x5CEA_2026L
        const val DEFAULT_ITERATIONS = 300
        const val DEFAULT_TIMEOUT_MILLIS = 2_000L
        const val MAX_REPORTED = 20
        const val MAX_ALLOCATION = 20_000_000L
    }
}
