package io.github.mgdx.sceau.jp2

/**
 * Protocole entre [IsolatedJpeg2000Decoder] (processus de l'app) et [Jpeg2000Service]
 * (processus isolé), décision D23. Kotlin pur, sans dépendance Android, testé sur JVM.
 *
 * Les octets du flux et les pixels décodés passent par binder, en mémoire uniquement, par
 * morceaux assez petits pour rester loin de la limite du tampon de transaction binder (1 Mo
 * partagé par toutes les transactions en cours du processus) :
 *  1. [TX_BEGIN] (longueur totale du flux) ;
 *  2. [TX_APPEND] (position, morceau d'au plus [BYTE_CHUNK] octets), dans l'ordre ;
 *  3. [TX_DECODE] : réponse largeur et hauteur ;
 *  4. [TX_READ] (position, nombre) : morceaux d'au plus [INT_CHUNK] pixels ARGB, dans l'ordre.
 * Chaque réponse commence par un statut ([STATUS_OK] ou [STATUS_ERROR]).
 */
internal object Jpeg2000Protocol {
    /** Jeton d'interface vérifié par le service à chaque transaction. */
    const val DESCRIPTOR = "io.github.mgdx.sceau.jp2.Jpeg2000Service"

    // IBinder.FIRST_CALL_TRANSACTION vaut 1.
    const val TX_BEGIN = 1
    const val TX_APPEND = 2
    const val TX_DECODE = 3
    const val TX_READ = 4

    const val STATUS_OK = 0
    const val STATUS_ERROR = 1

    /** Taille maximale du flux, identique à `SCEAU_JP2_MAX_INPUT_SIZE` (jp2_decode.h). */
    const val MAX_INPUT_BYTES = 16 * 1024 * 1024

    /** Côté maximal de l'image décodée, identique à `SCEAU_JP2_MAX_OUTPUT_DIMENSION`. */
    const val MAX_OUTPUT_SIDE = 2048

    /** Octets du flux par transaction (256 Kio). */
    const val BYTE_CHUNK = 256 * 1024

    /** Pixels par transaction (64 Ki pixels, soit 256 Kio). */
    const val INT_CHUNK = 64 * 1024

    /**
     * Délai maximal d'un décodage, démarrage du processus isolé et transferts compris. Au-delà,
     * le service est détaché (ce qui termine le processus isolé) et l'image n'est pas affichée.
     */
    const val TIMEOUT_MILLIS = 10_000L

    /** Morceaux (position, longueur) couvrant `[0, total)`, chacun d'au plus [chunk] éléments. */
    fun chunks(
        total: Int,
        chunk: Int,
    ): List<Pair<Int, Int>> {
        require(total >= 0 && chunk > 0)
        return (0 until total step chunk).map { start -> start to minOf(chunk, total - start) }
    }

    /** Vrai si [length] est une longueur de flux acceptable. */
    fun isValidInputLength(length: Int): Boolean = length in 1..MAX_INPUT_BYTES

    /** Vrai si les dimensions annoncées par le processus isolé sont plausibles et bornées. */
    fun isValidOutput(
        width: Int,
        height: Int,
    ): Boolean = width in 1..MAX_OUTPUT_SIDE && height in 1..MAX_OUTPUT_SIDE

    /** Vrai si `[offset, offset + count)` est un morceau valide d'un tableau de [total] éléments. */
    fun isValidSlice(
        total: Int,
        offset: Int,
        count: Int,
        maxChunk: Int,
    ): Boolean = offset in 0..total && count in 1..maxChunk && count <= total - offset
}

/**
 * Tampon d'octets rempli morceau par morceau, dans l'ordre strict : un morceau hors séquence,
 * vide ou qui déborderait est refusé. [wipe] remet le contenu à zéro.
 */
internal class SequentialBytes(
    size: Int,
) {
    val data = ByteArray(size)
    private var filled = 0

    val isComplete: Boolean get() = filled == data.size

    fun put(
        offset: Int,
        chunk: ByteArray,
    ): Boolean {
        if (offset != filled || chunk.isEmpty() || chunk.size > data.size - filled) return false
        chunk.copyInto(data, offset)
        filled += chunk.size
        return true
    }

    fun wipe() {
        data.fill(0)
        filled = 0
    }
}

/** Équivalent de [SequentialBytes] pour les pixels ARGB d'une image de [width] × [height]. */
internal class SequentialPixels(
    val width: Int,
    val height: Int,
) {
    val data = IntArray(width * height)
    private var filled = 0

    val isComplete: Boolean get() = filled == data.size

    fun put(
        offset: Int,
        chunk: IntArray,
    ): Boolean {
        if (offset != filled || chunk.isEmpty() || chunk.size > data.size - filled) return false
        chunk.copyInto(data, offset)
        filled += chunk.size
        return true
    }

    fun wipe() {
        data.fill(0)
        filled = 0
    }
}

/**
 * Plage des UID isolés d'un utilisateur Android (`Process.FIRST_ISOLATED_UID` à
 * `LAST_ISOLATED_UID`, API masquée) : sert à reconnaître le processus isolé sur les API 26 et
 * 27, où `Process.isIsolated()` n'existe pas encore.
 */
internal fun isIsolatedUid(uid: Int): Boolean = uid % 100_000 in 99_000..99_999
