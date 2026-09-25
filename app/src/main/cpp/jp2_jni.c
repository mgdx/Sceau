/*
 * Sceau - pont JNI du décodeur JPEG 2000 (io.github.mgdx.sceau.jp2.Jpeg2000Decoder).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Ne lève jamais d'exception Java : tout échec renvoie null. Aucune trace (SPEC §8).
 */
#include <jni.h>
#include <stdlib.h>

#include "jp2_decode.h"

/*
 * private external fun nativeDecode(data: ByteArray, dimensions: IntArray): IntArray?
 * Renvoie les pixels ARGB, et écrit largeur et hauteur dans dimensions[0] et dimensions[1].
 * Les pixels sont convertis ligne par ligne directement dans le tableau Java renvoyé : aucun
 * tampon ARGB complet n'est alloué côté natif.
 */
JNIEXPORT jintArray JNICALL
Java_io_github_mgdx_sceau_jp2_Jpeg2000Decoder_nativeDecode(JNIEnv *env, jobject thiz, jbyteArray data,
                                                          jintArray dimensions) {
    jsize length;
    uint8_t *input;
    sceau_jp2_decoded *decoded = NULL;
    sceau_jp2_status status;
    jintArray result = NULL;
    uint32_t *row = NULL;
    uint32_t width;
    uint32_t height;
    uint32_t y;
    jint size[2];

    (void) thiz;
    if (data == NULL || dimensions == NULL || (*env)->GetArrayLength(env, dimensions) < 2) {
        return NULL;
    }
    length = (*env)->GetArrayLength(env, data);
    if (length <= 0 || (size_t) length > SCEAU_JP2_MAX_INPUT_SIZE) {
        return NULL;
    }

    /* Copie privée du flux, effacée après usage (GetByteArrayElements pourrait en faire une
     * que l'on ne maîtrise pas). */
    input = (uint8_t *) malloc((size_t) length);
    if (input == NULL) {
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *) input);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        sceau_jp2_secure_zero(input, (size_t) length);
        free(input);
        return NULL;
    }

    status = sceau_jp2_decode_image(input, (size_t) length, &decoded, &width, &height);
    sceau_jp2_secure_zero(input, (size_t) length);
    free(input);
    if (status != SCEAU_JP2_OK) {
        return NULL;
    }

    /* width et height sont bornés par SCEAU_JP2_MAX_OUTPUT_DIMENSION : le produit tient dans
     * un jsize. */
    row = (uint32_t *) malloc((size_t) width * sizeof(uint32_t));
    if (row != NULL) {
        result = (*env)->NewIntArray(env, (jsize) (width * height));
    }
    if (result != NULL) {
        for (y = 0; y < height && !(*env)->ExceptionCheck(env); y++) {
            sceau_jp2_decoded_row(decoded, y, row);
            (*env)->SetIntArrayRegion(env, result, (jsize) (y * width), (jsize) width, (const jint *) row);
        }
        size[0] = (jint) width;
        size[1] = (jint) height;
        (*env)->SetIntArrayRegion(env, dimensions, 0, 2, size);
    }
    if ((*env)->ExceptionCheck(env)) {
        /* OutOfMemoryError de NewIntArray : l'appelant reçoit simplement null. */
        (*env)->ExceptionClear(env);
        result = NULL;
    }
    if (row != NULL) {
        sceau_jp2_secure_zero(row, (size_t) width * sizeof(uint32_t));
        free(row);
    }
    sceau_jp2_decoded_free(decoded);
    return result;
}
