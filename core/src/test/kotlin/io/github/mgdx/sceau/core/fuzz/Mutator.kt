package io.github.mgdx.sceau.core.fuzz

import java.util.Random

/** Entrée mutée et noms des mutations appliquées, pour le message d'échec. */
internal class Mutant(
    val bytes: ByteArray,
    val operations: List<String>,
)

/**
 * Mutations déterministes (tout vient de [random]) d'une graine : opérations sur les octets
 * (inversion de bit, remplacement, troncature, insertion, duplication ou suppression de bloc)
 * et opérations sur l'arbre TLV/DER (longueurs gonflées ou réduites, forme longue sur 4 octets
 * ou plus, longueur indéfinie, imbrication profonde, tags inattendus, enfants dupliqués,
 * supprimés ou permutés, valeurs remplacées). Le résultat ne dépasse jamais [maxSize] octets.
 */
internal class Mutator(
    private val random: Random,
    private val maxSize: Int,
) {
    fun mutate(seed: ByteArray): Mutant {
        var bytes = seed.copyOf()
        val operations = mutableListOf<String>()
        repeat(1 + random.nextInt(MAX_STACKED)) {
            val (next, name) = if (random.nextBoolean()) tlvMutation(bytes) ?: byteMutation(bytes) else byteMutation(bytes)
            bytes = if (next.size > maxSize) next.copyOf(maxSize) else next
            operations += name
        }
        return Mutant(bytes, operations)
    }

    // --- Octets -------------------------------------------------------------------------

    private fun byteMutation(input: ByteArray): Pair<ByteArray, String> {
        if (input.isEmpty()) return randomBytes(1 + random.nextInt(SMALL_BLOCK)) to "insert"
        return when (random.nextInt(BYTE_OPERATIONS)) {
            0 -> {
                val at = random.nextInt(input.size)
                input.copyOf().also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(Byte.SIZE_BITS))).toByte() } to "flip-bit"
            }

            1 -> {
                input.copyOf().also { it[random.nextInt(it.size)] = interestingByte() } to "set-byte"
            }

            2 -> {
                input.copyOf(random.nextInt(input.size)) to "truncate"
            }

            3 -> {
                splice(input, random.nextInt(input.size + 1), 0, randomBytes(1 + random.nextInt(SMALL_BLOCK))) to "insert"
            }

            4 -> {
                val start = random.nextInt(input.size)
                val length = 1 + random.nextInt(minOf(input.size - start, BLOCK))
                splice(input, random.nextInt(input.size + 1), 0, input.copyOfRange(start, start + length)) to "duplicate-block"
            }

            5 -> {
                val start = random.nextInt(input.size)
                splice(input, start, 1 + random.nextInt(minOf(input.size - start, BLOCK)), ByteArray(0)) to "delete-block"
            }

            else -> {
                val word = INTERESTING_WORDS[random.nextInt(INTERESTING_WORDS.size)]
                val width = if (random.nextBoolean()) 2 else 4
                val at = random.nextInt(input.size)
                input.copyOf().also { out ->
                    for (i in 0 until minOf(width, out.size - at)) out[at + i] = (word ushr (8 * (width - 1 - i))).toByte()
                } to "set-word"
            }
        }
    }

    // --- TLV / DER ----------------------------------------------------------------------

    private fun tlvMutation(input: ByteArray): Pair<ByteArray, String>? {
        val document = BerDocument.parse(input) ?: return null
        val nodes = document.nodes()
        val node = nodes[random.nextInt(nodes.size)]
        val name =
            when (random.nextInt(TLV_OPERATIONS)) {
                0 -> {
                    node.lengthEncoding = inflatedLength(node)
                    "length-inflate"
                }

                1 -> {
                    val actual = node.encode().size
                    node.lengthEncoding = LengthEncoding.Minimal(random.nextInt(maxOf(actual, 1)).toLong())
                    "length-deflate"
                }

                2 -> {
                    node.lengthEncoding = LengthEncoding.LongForm(LONG_FORM_BYTES[random.nextInt(LONG_FORM_BYTES.size)])
                    "length-long-form"
                }

                3 -> {
                    if (node.children == null) return null
                    node.lengthEncoding = LengthEncoding.Indefinite
                    "length-indefinite"
                }

                4 -> {
                    if (!nest(document, node, input.size)) return null
                    "deep-nesting"
                }

                5 -> {
                    node.tag = unexpectedTag(nodes)
                    "unexpected-tag"
                }

                6 -> {
                    node.tag = node.tag.copyOf().also { it[0] = (it[0].toInt() xor BerNode.CONSTRUCTED).toByte() }
                    "toggle-constructed"
                }

                7 -> {
                    val children = node.children?.takeIf { it.isNotEmpty() } ?: return null
                    when (random.nextInt(3)) {
                        0 -> children.add(random.nextInt(children.size + 1), children[random.nextInt(children.size)])
                        1 -> children.removeAt(random.nextInt(children.size))
                        else -> children.shuffle(random)
                    }
                    "children-edit"
                }

                else -> {
                    node.children = null
                    node.prefix = ByteArray(0)
                    node.value = replacementValue(node.value)
                    "value-replace"
                }
            }
        return document.encode() to name
    }

    private fun inflatedLength(node: BerNode): LengthEncoding {
        val actual = node.encode().size.toLong()
        val declared =
            when (random.nextInt(3)) {
                0 -> actual + INFLATE_DELTAS[random.nextInt(INFLATE_DELTAS.size)]
                1 -> HUGE_LENGTHS[random.nextInt(HUGE_LENGTHS.size)]
                else -> actual + 1 + random.nextInt(Short.MAX_VALUE.toInt())
            }
        return if (random.nextBoolean()) LengthEncoding.LongForm(4, declared and MAX_UINT32) else LengthEncoding.Minimal(declared)
    }

    /**
     * Enveloppe [node] dans une pile de TLV construits, dans la limite de taille. La pile est
     * encodée d'un bloc, sans récursion (elle peut compter des dizaines de milliers de niveaux),
     * et devient un nœud opaque.
     */
    private fun nest(
        document: BerDocument,
        node: BerNode,
        size: Int,
    ): Boolean {
        val budget = (maxSize - size) / NEST_LEVEL_COST
        if (budget < 1) return false
        val depth = minOf(budget, NEST_DEPTHS[random.nextInt(NEST_DEPTHS.size)])
        val tag =
            when (random.nextInt(3)) {
                0 -> byteArrayOf(SEQUENCE.toByte())
                1 -> byteArrayOf(CONTEXT_0.toByte())
                else -> node.tag.copyOf().also { it[0] = (it[0].toInt() or BerNode.CONSTRUCTED).toByte() }
            }
        val indefinite = random.nextInt(4) == 0
        val inner = node.encode()
        // Longueurs de l'intérieur vers l'extérieur ; le niveau le plus externe reste un nœud.
        val headers = ArrayList<ByteArray>(depth - 1)
        var contentSize = inner.size.toLong()
        repeat(depth - 1) {
            val header = tag + if (indefinite) byteArrayOf(INDEFINITE.toByte()) else LengthEncoding.Minimal().header(contentSize.toInt())
            headers += header
            contentSize += header.size + if (indefinite) 2 else 0
        }
        val out = java.io.ByteArrayOutputStream(contentSize.toInt())
        for (i in headers.indices.reversed()) out.write(headers[i])
        out.write(inner)
        if (indefinite) repeat(headers.size) { out.write(byteArrayOf(0, 0)) }
        val wrapped = BerNode(tag, null, out.toByteArray(), lengthEncoding = if (indefinite) LengthEncoding.Indefinite else null)
        val siblings = findParent(document.roots, node)?.children ?: document.roots
        siblings[siblings.indexOfFirst { it === node }] = wrapped
        return true
    }

    private fun findParent(
        roots: List<BerNode>,
        target: BerNode,
    ): BerNode? {
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.children ?: continue
            if (children.any { it === target }) return current
            stack.addAll(children)
        }
        return null
    }

    private fun unexpectedTag(nodes: List<BerNode>): ByteArray =
        when (random.nextInt(4)) {
            0 -> {
                byteArrayOf(random.nextInt(256).toByte())
            }

            1 -> {
                nodes[random.nextInt(nodes.size)].tag.copyOf()
            }

            2 -> {
                // Tag multi-octets, éventuellement avec des octets de continuation 0x80 superflus.
                val continuation = random.nextInt(MAX_TAG_CONTINUATION)
                ByteArray(continuation + 2) { i ->
                    when (i) {
                        0 -> (random.nextInt(8) shl 5 or HIGH_TAG).toByte()
                        continuation + 1 -> random.nextInt(0x80).toByte()
                        else -> (0x80 or random.nextInt(0x80)).toByte()
                    }
                }
            }

            else -> {
                byteArrayOf(SPECIAL_TAGS[random.nextInt(SPECIAL_TAGS.size)].toByte())
            }
        }

    private fun replacementValue(original: ByteArray): ByteArray =
        when (random.nextInt(4)) {
            0 -> ByteArray(0)
            1 -> randomBytes(1 + random.nextInt(SMALL_BLOCK))
            2 -> ByteArray(1 + random.nextInt(SMALL_BLOCK)) { if (it == 0) 0x7F else 0xFF.toByte() }
            else -> ByteArray(minOf(original.size * 2 + 1, maxSize / 2)) { interestingByte() }
        }

    // --- Outils -------------------------------------------------------------------------

    private fun interestingByte(): Byte =
        if (random.nextBoolean()) INTERESTING_BYTES[random.nextInt(INTERESTING_BYTES.size)].toByte() else random.nextInt(256).toByte()

    private fun randomBytes(length: Int): ByteArray = ByteArray(length).also(random::nextBytes)

    private fun splice(
        input: ByteArray,
        at: Int,
        removed: Int,
        inserted: ByteArray,
    ): ByteArray = input.copyOfRange(0, at) + inserted + input.copyOfRange(at + removed, input.size)

    private companion object {
        const val MAX_STACKED = 3
        const val BYTE_OPERATIONS = 7
        const val TLV_OPERATIONS = 9
        const val SMALL_BLOCK = 16
        const val BLOCK = 256
        const val SEQUENCE = 0x30
        const val CONTEXT_0 = 0xA0
        const val INDEFINITE = 0x80
        const val HIGH_TAG = 0x1F
        const val MAX_TAG_CONTINUATION = 6
        const val MAX_UINT32 = 0xFFFFFFFFL

        /** Coût maximal d'un niveau d'imbrication : tag, longueur sur 4 octets, fin 00 00. */
        const val NEST_LEVEL_COST = 8
        val NEST_DEPTHS = intArrayOf(2, 16, 100, 1_000, 10_000, 100_000)
        val LONG_FORM_BYTES = intArrayOf(2, 3, 4, 4, 4, 5, 8)
        val INFLATE_DELTAS = longArrayOf(1, 2, 127, 128, 255, 256, 65_536)
        val HUGE_LENGTHS = longArrayOf(0x7FFFFFFF, 0x80000000, 0xFFFFFFFF, 0xFFFFFF, 0x7FFFFFF7, 0x3FFFFFFF)
        val INTERESTING_BYTES = intArrayOf(0x00, 0x01, 0x7F, 0x80, 0x81, 0x82, 0x83, 0x84, 0x88, 0xFF, 0x30, 0x04, 0x02, 0x06)
        val INTERESTING_WORDS = longArrayOf(0, 0xFFFFFFFF, 0x7FFFFFFF, 0x80000000, 0x84FFFFFF, 0x847FFFFF, 0x0000FFFF)
        val SPECIAL_TAGS = intArrayOf(0x00, 0xFF, 0x05, 0x02, 0x04, 0x06, 0x30, 0x31, 0x77, 0x61, 0x75, 0x5F, 0x7F, 0xA0, 0xA3)
    }
}
