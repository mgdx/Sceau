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
 * Fuzzing settings, read from the `sceau.fuzz.*` system properties and, failing that, from the
 * `SCEAU_FUZZ_*` environment variables (only the latter get through `./gradlew`, which does
 * not pass its `-D` options to the test JVM):
 * - `iterations`: iterations per target (otherwise, each target's default count);
 * - `seed`: random seed (otherwise [DEFAULT_SEED]);
 * - `index`: replay only this iteration (to reproduce a failure);
 * - `timeoutMillis`: timeout per input (otherwise [DEFAULT_TIMEOUT_MILLIS]).
 */
internal object FuzzConfig {
    const val DEFAULT_SEED = 0x5CEA_2026L
    const val DEFAULT_TIMEOUT_MILLIS = 2_000L

    val seed: Long get() = setting("seed")?.let(::parseLong) ?: DEFAULT_SEED
    val iterations: Int? get() = setting("iterations")?.toInt()
    val index: Int? get() = setting("index")?.toInt()
    val timeoutMillis: Long get() = setting("timeoutMillis")?.toLong() ?: DEFAULT_TIMEOUT_MILLIS

    /** Iteration count for a target: the global setting, or [default]; [scale] lightens a slow target. */
    fun iterations(
        default: Int,
        scale: Int = 1,
    ): Int = iterations?.let { maxOf(1, it / scale) } ?: default

    private fun setting(name: String): String? =
        System.getProperty("sceau.fuzz.$name")?.takeIf { it.isNotBlank() }
            ?: System.getenv("SCEAU_FUZZ_" + name.replace(Regex("([A-Z])"), "_$1").uppercase())?.takeIf { it.isNotBlank() }

    private fun parseLong(value: String): Long = if (value.startsWith("0x")) value.substring(2).toLong(16) else value.toLong()
}

/** Input passed to the property: [pristine] is true for an unmutated seed. */
internal class FuzzInput(
    val bytes: ByteArray,
    val seedIndex: Int,
    val pristine: Boolean,
)

/** Property violated by an input (wrong result, unexpected exception). */
internal class PropertyViolation(
    message: String,
    cause: Throwable? = null,
) : AssertionError(message, cause)

/**
 * Fuzzing campaign for one target: each iteration draws a seed and mutations from a generator
 * derived from (global seed, target, index), so it can be replayed alone with
 * `SCEAU_FUZZ_SEED` and `SCEAU_FUZZ_INDEX`. The property runs in a dedicated thread, with a
 * timeout per input: an infinite loop, a `StackOverflowError` or an `OutOfMemoryError` are
 * failures like any other.
 *
 * Failures are grouped by signature (exception class and first stack frame in the project):
 * the campaign runs to the end and reports each signature with the first iteration that
 * produces it; only a timeout stops it (the stuck thread is abandoned, its stack is attached).
 * Messages never contain the input bytes.
 */
internal class FuzzCampaign(
    private val target: String,
    private val seeds: List<ByteArray>,
    private val maxSize: Int,
    private val iterations: Int,
    private val timeoutMillis: Long = FuzzConfig.timeoutMillis,
    /** Inputs the real caller never passes to the parser (file rejected earlier): skipped. */
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
        require(seeds.isNotEmpty()) { "no seed for $target" }
        val seed = FuzzConfig.seed
        try {
            val only = FuzzConfig.index
            if (only == null) {
                // Intact seeds first, with a generous timeout (class loading).
                seeds.forEachIndexed { i, bytes ->
                    execute(FuzzInput(bytes, i, pristine = true), "intact seed $i", WARM_UP_TIMEOUT_MILLIS, property)
                }
            }
            val indices = if (only != null) only..only else 0 until iterations
            for (index in indices) {
                val random = Random(mix(seed, target.hashCode(), index))
                val seedIndex = random.nextInt(seeds.size)
                val mutant = Mutator(random, maxSize).mutate(seeds[seedIndex])
                val where = "seed=0x${seed.toString(16)} index=$index seedIndex=$seedIndex mutations=${mutant.operations}"
                if (!precondition(mutant.bytes)) continue
                if (!execute(FuzzInput(mutant.bytes, seedIndex, pristine = false), where, timeoutMillis, property)) break
            }
        } finally {
            executor.shutdownNow()
        }
        if (failures.isNotEmpty()) {
            val summary =
                failures.entries.joinToString("\n") { (signature, failure) ->
                    "  - $signature ×${failure.count}, first: ${failure.first}"
                }
            throw AssertionError("fuzz[$target]: ${failures.size} failure signature(s)\n$summary", failures.values.first().error)
        }
    }

    /** False if the campaign must stop (timeout: the stuck thread is abandoned). */
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
            // Stack of the stuck thread, to locate the loop.
            worker?.let { e.stackTrace = it.stackTrace }
            future.cancel(true)
            executor.shutdownNow()
            executor = newExecutor()
            record("timeout of ${timeout}ms exceeded", where, e)
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
        if (error is PropertyViolation) return "property: ${error.message?.lineSequence()?.first()}"
        val frame =
            error.stackTrace.firstOrNull { it.className.startsWith(PROJECT_PACKAGE) && !it.className.contains(".fuzz.") }
                ?: error.stackTrace.firstOrNull()
        return "${error.javaClass.name} at ${frame?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }}" +
            " (origin ${error.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}" }})"
    }

    private fun newExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(null, runnable, "fuzz-$target", WORKER_STACK_BYTES).apply { isDaemon = true }.also { worker = it }
        }

    private companion object {
        const val PROJECT_PACKAGE = "io.github.mgdx.sceau"
        const val WARM_UP_TIMEOUT_MILLIS = 60_000L

        /** Stack of an ordinary I/O thread (1 MB): unbounded recursion must overflow it. */
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
 * Precondition for files read from the chip: `BoundedReadBinarySender` rejects, before any
 * parser, a file whose header announces more than the cap for [fid].
 */
internal fun withinSizeLimit(fid: Short): (ByteArray) -> Boolean =
    { bytes -> FileSizeLimits.announcedSize(bytes)?.let { it <= FileSizeLimits.maxSize(fid) } ?: true }

/** Throws a [PropertyViolation] if [condition] is false. */
internal fun property(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) throw PropertyViolation(message())
}
