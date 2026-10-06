package io.github.mgdx.sceau.ui.trust

import android.content.res.Resources
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.trust.TrustStores
import io.github.mgdx.sceau.ui.result.displaySafe
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/** Taille maximale d'une Master List importée : 20 Mo. */
const val MAX_MASTER_LIST_BYTES: Int = 20 * 1024 * 1024

/** Fichier refusé car plus gros que la limite. */
class FileTooLargeException : Exception("TOO_LARGE")

/**
 * Lit tout [input] en refusant au-delà de [limit] octets, sans jamais allouer plus que
 * `limit + 1` octets. Lève [FileTooLargeException] si la limite est dépassée.
 */
fun readAtMost(
    input: InputStream,
    limit: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw FileTooLargeException()
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val BUFFER_SIZE = 64 * 1024

/** Empreinte hexadécimale regroupée par blocs de quatre caractères, en majuscules. */
fun formatFingerprint(hex: String): String = hex.uppercase(Locale.ROOT).chunked(4).joinToString(" ")

/** Longueur maximale affichée d'un sujet X.500, en points de code. */
const val SUBJECT_MAX_LENGTH = 256

/** Nombre maximal de lignes affichées pour un sujet X.500. */
const val SUBJECT_MAX_LINES = 4

/** Longueur maximale affichée d'un code pays non reconnu, en points de code. */
const val COUNTRY_CODE_MAX_LENGTH = 8

/**
 * Sujet X.500 d'un certificat ou du signataire d'une Master List, rendu par
 * `X500Principal.getName()` qui n'échappe ni les sauts de ligne ni les caractères de contrôle
 * bidirectionnels : assaini par [displaySafe] avant tout affichage (audit V23).
 */
fun displaySubject(subject: String): String = displaySafe(subject, SUBJECT_MAX_LENGTH)

/** Code pays lu dans un certificat (attribut C), assaini quand il n'est pas reconnu (audit V23). */
fun displayCountryCode(code: String): String = displaySafe(code, COUNTRY_CODE_MAX_LENGTH)

/** Code de `MasterListParser` pour une Master List de plus de [TrustStores.MAX_MASTER_LIST_CERTIFICATES] certificats. */
internal const val MASTER_LIST_TOO_MANY_CERTIFICATES = "TOO_MANY_CERTIFICATES"

/**
 * Message d'une Master List refusée par `MasterListParser` (`InvalidMasterListException`). Une
 * liste trop grosse (D22, audit V22) peut être lisible et bien signée : elle reçoit un message
 * qui donne le plafond. Les autres codes gardent le message générique, code compris.
 */
fun invalidMasterListMessage(
    resources: Resources,
    code: String,
): String =
    if (code == MASTER_LIST_TOO_MANY_CERTIFICATES) {
        resources.getQuantityString(
            R.plurals.trust_import_master_list_too_many_certificates,
            TrustStores.MAX_MASTER_LIST_CERTIFICATES,
            TrustStores.MAX_MASTER_LIST_CERTIFICATES,
        )
    } else {
        resources.getString(R.string.trust_import_invalid, code)
    }
