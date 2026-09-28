package io.github.mgdx.sceau.core.fuzz

import io.github.mgdx.sceau.core.reading.FileSizeLimits
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Réglages du fuzzing, lus dans les propriétés système `sceau.fuzz.*` puis, à défaut, dans les
 * variables d'environnement `SCEAU_FUZZ_*` (seules ces dernières traversent `./gradlew`, qui
 * ne transmet pas ses `-D` à la JVM des tests) :
 * - `iterations` : itérations par cible (sinon, le nombre par défaut de chaque cible) ;
 * - `seed` : graine aléatoire (sinon [DEFAULT_SEED]) ;
 * - `index` : ne rejouer que cette itération (reproduction d'un échec) ;
 * - `timeoutMillis` : délai par entrée (sinon [DEFAULT_TIMEOUT_MILLIS]).
 */
internal object FuzzConfig {
    const val DEFAULT_SEED = 0x5CEA_2026L
    const val DEFAULT_TIMEOUT_MILLIS = 2_000L

    val seed: Long get() = setting("seed")?.let(::parseLong) ?: DEFAULT_SEED
    val iterations: Int? get() = setting("iterations")?.toInt()
    val index: Int? get() = setting("index")?.toInt()
    val timeoutMillis: Long get() = setting("timeoutMillis")?.toLong() ?: DEFAULT_TIMEOUT_MILLIS

    /** Nombre d'itérations d'une cible : réglage global, ou [default] ; [scale] allège une cible lente. */
    fun iterations(
        default: Int,
        scale: Int = 1,
    ): Int = iterations?.let { maxOf(1, it / scale) } ?: default

    private fun setting(name: String): String? =
        System.getProperty("sceau.fuzz.$name")?.takeIf { it.isNotBlank() }
            ?: System.getenv("SCEAU_FUZZ_" + name.replace(Regex("([A-Z])"), "_$1").uppercase())?.takeIf { it.isNotBlank() }

    private fun parseLong(value: String): Long = if (value.startsWith("0x")) value.substring(2).toLong(16) else value.toLong()
}

/** Entrée soumise à la propriété : [pristine] vaut vrai pour une graine non mutée. */
internal class FuzzInput(
    val bytes: ByteArray,
    val seedIndex: Int,
    val pristine: Boolean,
)

/** Violation d'une propriété par une entrée (résultat faux, exception inattendue). */
internal class PropertyViolation(
    message: String,
    cause: Throwable? = null,
) : AssertionError(message, cause)

/**
 * Campagne de fuzzing d'une cible : chaque itération tire une graine et des mutations d'un
 * générateur dérivé de (graine globale, cible, index), donc rejouable seule avec
 * `SCEAU_FUZZ_SEED` et `SCEAU_FUZZ_INDEX`. La propriété s'exécute dans un fil dédié, avec un
 * délai par entrée : une boucle infinie, une `StackOverflowError` ou une `OutOfMemoryError`
 * sont des échecs comme les autres.
 *
 * Les échecs sont regroupés par signature (classe d'exception et première ligne de pile du
 * projet) : la campagne va jusqu'au bout et rapporte chaque signature avec la première
 * itération qui la produit ; seul un délai dépassé l'arrête (le fil bloqué est abandonné, sa
 * pile est jointe). Les messages ne contiennent jamais les octets des entrées.
 */
internal class FuzzCampaign(
    private val target: String,
    private val seeds: List<ByteArray>,
    private val maxSize: Int,
    private val iterations: Int,
    private val timeoutMillis: Long = FuzzConfig.timeoutMillis,
    /** Entrées que l'appelant réel ne soumet jamais au parseur (fichier refusé plus tôt) : ignorées. */
    private val precondition: (ByteArray) -> Boolean = { true },
) {
    private class Failure(
        val first: String,
        val error: Throwable,
        var count: Int = 1,
    )

    @Volatile
    private var worker: Thread? = null
    private var executor: ExecutorService = newExecutor()
    private val failures = LinkedHashMap<String, Failure>()

    fun run(property: (FuzzInput) -> Unit) {
        require(seeds.isNotEmpty()) { "aucune graine pour $target" }
        val seed = FuzzConfig.seed
        try {
            val only = FuzzConfig.index
            if (only == null) {
                // Les graines intactes d'abord, avec un délai large (chargement des classes).
                seeds.forEachIndexed { i, bytes ->
                    execute(FuzzInput(bytes, i, pristine = true), "graine $i intacte", WARM_UP_TIMEOUT_MILLIS, property)
                }
            }
            val indices = if (only != null) only..only else 0 until iterations
            for (index in indices) {
                val random = Random(mix(seed, target.hashCode(), index))
                val seedIndex = random.nextInt(seeds.size)
                val mutant = Mutator(random, maxSize).mutate(seeds[seedIndex])
                val where = "seed=0x${seed.toString(16)} index=$index graine=$seedIndex mutations=${mutant.operations}"
                if (!precondition(mutant.bytes)) continue
                if (!execute(FuzzInput(mutant.bytes, seedIndex, pristine = false), where, timeoutMillis, property)) break
            }
        } finally {
            executor.shutdownNow()
        }
        if (failures.isNotEmpty()) {
            val summary =
                failures.entries.joinToString("\n") { (signature, failure) ->
                    "  - $signature ×${failure.count}, première : ${failure.first}"
                }
            throw AssertionError("fuzz[$target] : ${failures.size} signature(s) d'échec\n$summary", failures.values.first().error)
        }
    }

    /** Faux si la campagne doit s'arrêter (délai dépassé : le fil bloqué est abandonné). */
    private fun execute(
        input: FuzzInput,
        where: String,
        timeout: Long,
        property: (FuzzInput) -> Unit,
    ): Boolean {
        val future = executor.submit(Callable { property(input) })
        try {
            future.get(timeout, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Pile du fil bloqué, pour situer la boucle.
            worker?.let { e.stackTrace = it.stackTrace }
            future.cancel(true)
            executor.shutdownNow()
            executor = newExecutor()
            record("délai de ${timeout}ms dépassé", where, e)
            return false
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            record(signature(cause), where, cause)
        }
        return true
    }

    private fun record(
        signature: String,
        where: String,
        error: Throwable,
    ) {
        failures.getOrPut(signature) { Failure(where, error, 0) }.count++
    }

    private fun signature(error: Throwable): String {
        if (error is PropertyViolation) return "propriété : ${error.message?.lineSequence()?.first()}"
        val frame =
            error.stackTrace.firstOrNull { it.className.startsWith(PROJECT_PACKAGE) && !it.className.contains(".fuzz.") }
                ?: error.stackTrace.firstOrNull()
        return "${error.javaClass.name} à ${frame?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }}" +
            " (origine ${error.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}" }})"
    }

    private fun newExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(null, runnable, "fuzz-$target", WORKER_STACK_BYTES).apply { isDaemon = true }.also { worker = it }
        }

    private companion object {
        const val PROJECT_PACKAGE = "io.github.mgdx.sceau"
        const val WARM_UP_TIMEOUT_MILLIS = 60_000L

        /** Pile d'un fil d'E/S ordinaire (1 Mo) : une récursion non bornée doit y déborder. */
        const val WORKER_STACK_BYTES = 1L shl 20

        fun mix(
            seed: Long,
            target: Int,
            index: Int,
        ): Long {
            var z = seed xor (target.toLong() shl 32) xor index.toLong() * -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}

/**
 * Précondition des fichiers lus sur la puce : `BoundedReadBinarySender` refuse, avant tout
 * parseur, un fichier dont l'en-tête annonce plus que le plafond de [fid].
 */
internal fun withinSizeLimit(fid: Short): (ByteArray) -> Boolean =
    { bytes -> FileSizeLimits.announcedSize(bytes)?.let { it <= FileSizeLimits.maxSize(fid) } ?: true }

/** Lève une [PropertyViolation] si [condition] est fausse. */
internal fun property(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) throw PropertyViolation(message())
}
