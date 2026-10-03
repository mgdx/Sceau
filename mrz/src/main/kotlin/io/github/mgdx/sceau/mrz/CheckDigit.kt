package io.github.mgdx.sceau.mrz

/** Chiffre de contrôle ICAO 9303-3 §4.9 : poids 7-3-1 répétés, somme modulo 10. */
internal object CheckDigit {
    private val WEIGHTS = intArrayOf(7, 3, 1)

    /** Valeur d'un caractère de la MRZ : `<` = 0, `0-9`, `A` = 10 … `Z` = 35 ; -1 hors alphabet. */
    fun value(c: Char): Int =
        when (c) {
            '<' -> 0
            in '0'..'9' -> c - '0'
            in 'A'..'Z' -> c - 'A' + 10
            else -> -1
        }

    /**
     * Somme pondérée de `chars[from until to]`, le premier caractère ayant le rang [offset] dans
     * la chaîne contrôlée (pour enchaîner les champs du composite). -1 si un caractère est hors
     * alphabet.
     */
    fun weightedSum(
        chars: CharArray,
        from: Int,
        to: Int,
        offset: Int = 0,
    ): Int {
        var sum = 0
        for (i in from until to) {
            val v = value(chars[i])
            if (v < 0) return -1
            sum += v * WEIGHTS[(offset + i - from) % WEIGHTS.size]
        }
        return sum
    }

    /** Chiffre de contrôle de `chars[from until to]`, -1 si un caractère est hors alphabet. */
    fun compute(
        chars: CharArray,
        from: Int = 0,
        to: Int = chars.size,
    ): Int {
        val sum = weightedSum(chars, from, to)
        return if (sum < 0) -1 else sum % 10
    }

    /** Vrai si [check] est un chiffre égal au chiffre de contrôle de `chars[from until to]`. */
    fun matches(
        chars: CharArray,
        from: Int,
        to: Int,
        check: Char,
    ): Boolean = check in '0'..'9' && compute(chars, from, to) == check - '0'
}
