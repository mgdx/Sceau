package io.github.mgdx.sceau.mrz

import java.util.PriorityQueue

/** Type d'une position de la MRZ, qui fixe les corrections permises. */
internal enum class PositionKind {
    /** Date ou chiffre de contrôle : chiffre seulement, lettres confondues ramenées au chiffre. */
    DIGIT,

    /** Chiffre de contrôle des données facultatives de TD3 : chiffre ou `<`. */
    DIGIT_OR_FILLER,

    /** Code de pays : lettre ou `<`, chiffres confondus ramenés à la lettre. */
    ALPHA,

    /** Numéro de document, données facultatives : tout l'alphabet, confusions explorées. */
    ALNUM,
}

/** Confusions usuelles de l'OCR-B entre lettres et chiffres. */
internal object Confusions {
    fun toDigit(c: Char): Char? =
        when (c) {
            in '0'..'9' -> c
            'O', 'D', 'Q' -> '0'
            'I', 'L' -> '1'
            'Z' -> '2'
            'S' -> '5'
            'G' -> '6'
            'B' -> '8'
            else -> null
        }

    fun toLetter(c: Char): Char? =
        when (c) {
            in 'A'..'Z', '<' -> c
            '0' -> 'O'
            '1' -> 'I'
            '2' -> 'Z'
            '5' -> 'S'
            '6' -> 'G'
            '8' -> 'B'
            else -> null
        }

    /** Pour une position alphanumérique : l'autre lecture possible de [c], s'il y en a une. */
    fun alternate(c: Char): Char? =
        when (c) {
            in '0'..'9' -> toLetter(c)?.takeIf { it != c }
            in 'A'..'Z' -> toDigit(c)?.takeIf { it != c }
            else -> null
        }

    fun isMrzChar(c: Char): Boolean = c == '<' || c in '0'..'9' || c in 'A'..'Z'

    /** [c] ramené au type [kind], ou `null` s'il ne peut pas y figurer. */
    fun mapTo(
        kind: PositionKind,
        c: Char,
    ): Char? =
        when (kind) {
            PositionKind.DIGIT -> toDigit(c)
            PositionKind.DIGIT_OR_FILLER -> if (c == '<') c else toDigit(c)
            PositionKind.ALPHA -> toLetter(c)
            PositionKind.ALNUM -> c.takeIf { isMrzChar(it) }
        }
}

/** Lectures retenues pour une position, par coût croissant. Le coût 0 est la meilleure lecture. */
internal class PositionOptions(
    val chars: CharArray,
    val costs: FloatArray,
) {
    val size: Int get() = chars.size

    fun wipe() {
        chars.fill('\u0000')
        costs.fill(0f)
    }

    override fun toString(): String = "PositionOptions(${chars.size})"
}

/** Combinaison retenue pour un champ (contrôles du champ justes) et son coût. */
internal class FieldCandidate(
    val chars: CharArray,
    val cost: Float,
) {
    fun wipe() {
        chars.fill('\u0000')
    }

    override fun toString(): String = "FieldCandidate(${chars.size})"
}

/** Issue du décodage d'une image, avant stabilisation. */
internal sealed interface Decoded {
    class Valid(
        val fields: MrzKeyFields,
    ) : Decoded {
        override fun toString(): String = "Valid($fields)"
    }

    /** Entrée incohérente ou contrôles faux. */
    data object Invalid : Decoded

    /** Numéro de document étendu de TD1. */
    data object ExtendedNumber : Decoded
}

/**
 * Décodage d'une MRZ reconnue (lot A) : positions des champs (ICAO 9303-4 et 9303-5), correction
 * selon le type de champ, exploration bornée des lectures ambiguës, chiffres de contrôle.
 *
 * Les scores de [GlyphCandidates] sont supposés comparables à une corrélation (de l'ordre de
 * [0, 1]) : le candidat de rang 2 n'est envisagé que si son écart au premier ne dépasse pas
 * [AMBIGUITY_MARGIN]. Le coût d'une combinaison est la somme de ces écarts, plus
 * [CONFUSION_PENALTY] par confusion lettre/chiffre supposée dans un champ alphanumérique.
 *
 * Seuls le numéro de document et les deux dates sortent d'ici, dans [MrzKeyFields]. Les tableaux
 * de travail sont remis à zéro ; nationalité, sexe et données facultatives ne sont jamais
 * copiés dans un `String`.
 */
internal object MrzDecoding {
    const val AMBIGUITY_MARGIN = 0.15f
    const val CONFUSION_PENALTY = 0.2f

    /** Écart de coût en deçà duquel deux résultats différents sont jugés aussi probables l'un que l'autre. */
    const val RESULT_MARGIN = 0.05f

    /** Écart retenu quand un score n'est pas un nombre fini. */
    private const val UNRELIABLE_GAP = 1f
    private const val MAX_RAW_OPTIONS = 4
    private const val MAX_OPTIONS = 3
    private const val MAX_COMBINATIONS_PER_FIELD = 256
    private const val MAX_VALID_PER_FIELD = 3
    private const val NUMBER_LENGTH = 9
    private const val DATE_LENGTH = 6

    private enum class Role { NUMBER, DATE, OPTIONAL_CHECKED, OPTIONAL }

    private class FieldSpec(
        val role: Role,
        val line: Int,
        val from: Int,
        val kinds: List<PositionKind>,
    )

    /** Plage de positions alphabétiques (codes de pays), contrôlée pour la vraisemblance seulement. */
    private class AlphaRange(
        val line: Int,
        val from: Int,
        val length: Int,
    )

    private class Layout(
        val lineLength: Int,
        val lineCount: Int,
        /** Champs dans l'ordre du chiffre de contrôle composite. */
        val fields: List<FieldSpec>,
        val numberField: Int,
        val birthField: Int,
        val expiryField: Int,
        val alphaRanges: List<AlphaRange>,
        val compositeLine: Int,
        val compositeIndex: Int,
    )

    private fun kinds(
        count: Int,
        kind: PositionKind,
        last: PositionKind? = null,
    ): List<PositionKind> = List(count) { kind } + listOfNotNull(last)

    private fun numberField(
        line: Int,
        from: Int,
    ) = FieldSpec(Role.NUMBER, line, from, kinds(NUMBER_LENGTH, PositionKind.ALNUM, PositionKind.DIGIT))

    private fun dateField(
        line: Int,
        from: Int,
    ) = FieldSpec(Role.DATE, line, from, kinds(DATE_LENGTH + 1, PositionKind.DIGIT))

    private fun optionalField(
        line: Int,
        from: Int,
        length: Int,
    ) = FieldSpec(Role.OPTIONAL, line, from, kinds(length, PositionKind.ALNUM))

    // Positions ICAO 9303-4 (TD3) et 9303-5 (TD1, TD2), comptées ici à partir de 0.
    private val TD3 =
        Layout(
            lineLength = 44,
            lineCount = 1,
            fields =
                listOf(
                    numberField(0, 0),
                    dateField(0, 13),
                    dateField(0, 21),
                    FieldSpec(Role.OPTIONAL_CHECKED, 0, 28, kinds(14, PositionKind.ALNUM, PositionKind.DIGIT_OR_FILLER)),
                ),
            numberField = 0,
            birthField = 1,
            expiryField = 2,
            alphaRanges = listOf(AlphaRange(0, 10, 3)),
            compositeLine = 0,
            compositeIndex = 43,
        )

    private val TD2 =
        Layout(
            lineLength = 36,
            lineCount = 1,
            fields = listOf(numberField(0, 0), dateField(0, 13), dateField(0, 21), optionalField(0, 28, 7)),
            numberField = 0,
            birthField = 1,
            expiryField = 2,
            alphaRanges = listOf(AlphaRange(0, 10, 3)),
            compositeLine = 0,
            compositeIndex = 35,
        )

    private val TD1 =
        Layout(
            lineLength = 30,
            lineCount = 2,
            fields =
                listOf(
                    numberField(0, 5),
                    optionalField(0, 15, 15),
                    dateField(1, 0),
                    dateField(1, 8),
                    optionalField(1, 18, 11),
                ),
            numberField = 0,
            birthField = 2,
            expiryField = 3,
            alphaRanges = listOf(AlphaRange(0, 2, 3), AlphaRange(1, 15, 3)),
            compositeLine = 1,
            compositeIndex = 29,
        )

    /** Ligne 1 de TD1 : chiffre de contrôle du numéro (position 15) et suite du numéro étendu. */
    private const val TD1_NUMBER_CHECK_INDEX = 14

    private fun layoutOf(format: MrzFormat): Layout =
        when (format) {
            MrzFormat.TD1 -> TD1
            MrzFormat.TD2 -> TD2
            MrzFormat.TD3 -> TD3
        }

    fun decode(recognized: RecognizedMrz): Decoded {
        val layout = layoutOf(recognized.format)
        val lines = recognized.lines
        if (lines.size != layout.lineCount) return Decoded.Invalid
        if (lines.any { line -> line.size != layout.lineLength || line.any { it.ranked.isEmpty() } }) return Decoded.Invalid
        for (range in layout.alphaRanges) {
            for (i in range.from until range.from + range.length) {
                val glyph = lines[range.line][i]
                if (glyph.ranked.none { Confusions.mapTo(PositionKind.ALPHA, it.first) != null }) return Decoded.Invalid
            }
        }
        if (recognized.format == MrzFormat.TD1 && isExtendedNumber(lines[0])) return decodeExtended(layout, lines)
        return decodeStandard(recognized.format, layout, lines)
    }

    private fun isExtendedNumber(line1: List<GlyphCandidates>): Boolean =
        line1[TD1_NUMBER_CHECK_INDEX].ranked[0].first == '<' && line1[TD1_NUMBER_CHECK_INDEX + 1].ranked[0].first != '<'

    /** Numéro étendu : on vérifie seulement que les deux dates sont lisibles avant de le signaler. */
    private fun decodeExtended(
        layout: Layout,
        lines: List<List<GlyphCandidates>>,
    ): Decoded {
        for (index in listOf(layout.birthField, layout.expiryField)) {
            val found = fieldCandidates(layout.fields[index], lines) ?: return Decoded.Invalid
            val readable = found.isNotEmpty()
            found.forEach { it.wipe() }
            if (!readable) return Decoded.Invalid
        }
        return Decoded.ExtendedNumber
    }

    private fun decodeStandard(
        format: MrzFormat,
        layout: Layout,
        lines: List<List<GlyphCandidates>>,
    ): Decoded {
        val perField = ArrayList<List<FieldCandidate>>(layout.fields.size)
        var compositeOptions: PositionOptions? = null
        try {
            for (spec in layout.fields) {
                val found = fieldCandidates(spec, lines)
                if (found.isNullOrEmpty()) return Decoded.Invalid
                perField.add(found)
            }
            compositeOptions =
                optionsFor(lines[layout.compositeLine][layout.compositeIndex], PositionKind.DIGIT)
                    ?: return Decoded.Invalid
            val best =
                bestComposite(perField, compositeOptions, intArrayOf(layout.numberField, layout.birthField, layout.expiryField))
                    ?: return Decoded.Invalid
            val number = perField[layout.numberField][best[layout.numberField]].chars
            val birth = perField[layout.birthField][best[layout.birthField]].chars
            val expiry = perField[layout.expiryField][best[layout.expiryField]].chars
            val fields =
                MrzKeyFields(
                    format = format,
                    documentNumber = String(number, 0, numberLength(number)),
                    dateOfBirth = String(birth, 0, DATE_LENGTH),
                    dateOfExpiry = String(expiry, 0, DATE_LENGTH),
                )
            return Decoded.Valid(fields)
        } finally {
            perField.forEach { candidates -> candidates.forEach { it.wipe() } }
            compositeOptions?.wipe()
        }
    }

    /**
     * Parcourt toutes les associations des combinaisons retenues par champ et rend, pour la moins
     * coûteuse dont le composite est juste, l'indice retenu dans chaque champ. `null` si aucune ne
     * convient, ou si une autre association aux champs clés ([keyFields]) différents est presque
     * aussi probable (écart inférieur à [RESULT_MARGIN]) : on ne devine pas.
     */
    private fun bestComposite(
        perField: List<List<FieldCandidate>>,
        composite: PositionOptions,
        keyFields: IntArray,
    ): IntArray? {
        val choice = IntArray(perField.size)
        val solutions = ArrayList<Pair<Float, IntArray>>()

        fun visit(
            field: Int,
            offset: Int,
            sum: Int,
            cost: Float,
        ) {
            if (field == perField.size) {
                val expected = '0' + sum % 10
                for (k in 0 until composite.size) {
                    if (composite.chars[k] == expected) solutions.add(cost + composite.costs[k] to choice.copyOf())
                }
                return
            }
            for ((index, candidate) in perField[field].withIndex()) {
                val part = CheckDigit.weightedSum(candidate.chars, 0, candidate.chars.size, offset)
                if (part < 0) continue
                choice[field] = index
                visit(field + 1, offset + candidate.chars.size, sum + part, cost + candidate.cost)
            }
        }
        visit(0, 0, 0, 0f)
        val best = solutions.minByOrNull { it.first } ?: return null
        val ambiguous =
            solutions.any { (cost, other) ->
                cost < best.first + RESULT_MARGIN && keyFields.any { other[it] != best.second[it] }
            }
        return if (ambiguous) null else best.second
    }

    /** Combinaisons d'un champ qui passent ses propres contrôles, au plus [MAX_VALID_PER_FIELD]. */
    private fun fieldCandidates(
        spec: FieldSpec,
        lines: List<List<GlyphCandidates>>,
    ): List<FieldCandidate>? {
        val options = ArrayList<PositionOptions>(spec.kinds.size)
        try {
            for ((i, kind) in spec.kinds.withIndex()) {
                options.add(optionsFor(lines[spec.line][spec.from + i], kind) ?: return null)
            }
            val found = ArrayList<FieldCandidate>(MAX_VALID_PER_FIELD)
            enumerateCombinations(options, MAX_COMBINATIONS_PER_FIELD) { chars, cost ->
                if (isValidField(spec.role, chars)) found.add(FieldCandidate(chars.copyOf(), cost))
                found.size >= MAX_VALID_PER_FIELD
            }
            return found
        } finally {
            options.forEach { it.wipe() }
        }
    }

    private fun isValidField(
        role: Role,
        chars: CharArray,
    ): Boolean =
        when (role) {
            Role.NUMBER -> {
                CheckDigit.matches(chars, 0, NUMBER_LENGTH, chars[NUMBER_LENGTH]) && numberLength(chars) > 0
            }

            Role.DATE -> {
                CheckDigit.matches(chars, 0, DATE_LENGTH, chars[DATE_LENGTH]) && isValidDate(chars, 0)
            }

            Role.OPTIONAL_CHECKED -> {
                val last = chars.size - 1
                if (chars[last] == '<') {
                    (0 until last).all { chars[it] == '<' }
                } else {
                    CheckDigit.matches(chars, 0, last, chars[last])
                }
            }

            Role.OPTIONAL -> {
                true
            }
        }

    /**
     * Longueur du numéro sans les `<` de remplissage finaux, ou 0 s'il est vide ou contient un
     * caractère hors `A-Z0-9` (un `<` au milieu notamment).
     */
    fun numberLength(chars: CharArray): Int {
        var length = NUMBER_LENGTH
        while (length > 0 && chars[length - 1] == '<') length--
        for (i in 0 until length) {
            val c = chars[i]
            if (c !in '0'..'9' && c !in 'A'..'Z') return 0
        }
        return length
    }

    /** AAMMJJ existante ; le 29 février est accepté si AA est multiple de 4 (19AA ou 20AA bissextile). */
    fun isValidDate(
        chars: CharArray,
        from: Int,
    ): Boolean {
        for (i in from until from + DATE_LENGTH) if (chars[i] !in '0'..'9') return false

        fun twoDigits(at: Int) = (chars[at] - '0') * 10 + (chars[at + 1] - '0')
        val year = twoDigits(from)
        val month = twoDigits(from + 2)
        val day = twoDigits(from + 4)
        val maxDay =
            when (month) {
                2 -> if (year % 4 == 0) 29 else 28
                4, 6, 9, 11 -> 30
                in 1..12 -> 31
                else -> return false
            }
        return day in 1..maxDay
    }

    /** Lectures envisagées pour une position, ou `null` si aucune ne convient au type [kind]. */
    fun optionsFor(
        glyph: GlyphCandidates,
        kind: PositionKind,
    ): PositionOptions? {
        val ranked = glyph.ranked
        if (ranked.isEmpty()) return null
        val chars = CharArray(MAX_RAW_OPTIONS)
        val costs = FloatArray(MAX_RAW_OPTIONS)
        var count = 0

        fun add(
            c: Char,
            cost: Float,
        ) {
            for (i in 0 until count) {
                if (chars[i] == c) {
                    if (cost < costs[i]) costs[i] = cost
                    return
                }
            }
            if (count < MAX_RAW_OPTIONS) {
                chars[count] = c
                costs[count] = cost
                count++
            }
        }

        fun addMapped(
            c: Char,
            gap: Float,
        ) {
            val mapped = Confusions.mapTo(kind, c) ?: return
            add(mapped, gap)
            if (kind == PositionKind.ALNUM) Confusions.alternate(mapped)?.let { add(it, gap + CONFUSION_PENALTY) }
        }

        val top = ranked[0].second
        for (rank in 0 until minOf(2, ranked.size)) {
            val gap = if (rank == 0) 0f else gapBetween(top, ranked[rank].second)
            if (gap > AMBIGUITY_MARGIN) break
            addMapped(ranked[rank].first, gap)
        }
        if (count == 0) {
            // Meilleure lecture impossible pour ce type : premier candidat qui convient, quel que soit l'écart.
            for ((rank, candidate) in ranked.withIndex()) {
                addMapped(candidate.first, if (rank == 0) 0f else gapBetween(top, candidate.second))
                if (count > 0) break
            }
        }
        if (count == 0) return null
        // Tri par insertion sur le coût (au plus MAX_RAW_OPTIONS éléments).
        for (i in 1 until count) {
            var j = i
            while (j > 0 && costs[j] < costs[j - 1]) {
                val c = chars[j]
                chars[j] = chars[j - 1]
                chars[j - 1] = c
                val f = costs[j]
                costs[j] = costs[j - 1]
                costs[j - 1] = f
                j--
            }
        }
        val kept = minOf(count, MAX_OPTIONS)
        val result = PositionOptions(chars.copyOf(kept), costs.copyOf(kept))
        chars.fill('\u0000')
        return result
    }

    private fun gapBetween(
        top: Float,
        score: Float,
    ): Float = if (top.isFinite() && score.isFinite()) (top - score).coerceAtLeast(0f) else UNRELIABLE_GAP

    private class Combination(
        val indices: IntArray,
        val cost: Float,
    )

    /**
     * Énumère par coût croissant au plus [limit] combinaisons des [options] (au plus 31
     * positions). [visit] reçoit un tampon réutilisé, remis à zéro à la fin, et rend `true`
     * pour arrêter.
     */
    fun enumerateCombinations(
        options: List<PositionOptions>,
        limit: Int,
        visit: (CharArray, Float) -> Boolean,
    ) {
        require(options.size <= 31) { "too many positions" }
        val buffer = CharArray(options.size)
        val queue = PriorityQueue<Combination>(compareBy { it.cost })
        val seen = HashSet<Long>()
        var initialCost = 0f
        for (option in options) initialCost += option.costs[0]
        queue.add(Combination(IntArray(options.size), initialCost))
        seen.add(0L)
        try {
            var visited = 0
            while (visited < limit) {
                val current = queue.poll() ?: break
                visited++
                for (p in options.indices) buffer[p] = options[p].chars[current.indices[p]]
                if (visit(buffer, current.cost)) break
                for (p in options.indices) {
                    val index = current.indices[p]
                    if (index + 1 >= options[p].size) continue
                    val next = current.indices.copyOf()
                    next[p] = index + 1
                    if (seen.add(encode(next))) {
                        queue.add(Combination(next, current.cost - options[p].costs[index] + options[p].costs[index + 1]))
                    }
                }
            }
        } finally {
            buffer.fill('\u0000')
        }
    }

    private fun encode(indices: IntArray): Long {
        var key = 0L
        for ((p, index) in indices.withIndex()) key = key or (index.toLong() shl (2 * p))
        return key
    }
}
