package io.github.mgdx.sceau.core.reading.sim

import io.github.mgdx.sceau.core.testing.SodOptions
import io.github.mgdx.sceau.core.testing.TestDocument
import io.github.mgdx.sceau.core.testing.TestSod
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG14File
import java.security.KeyPair

/** Copie dont les DG valent [groups], avec un EF.SOD **signé à nouveau** par le même DS. */
internal fun TestDocument.resigned(
    groups: Map<Int, ByteArray> = dataGroups,
    options: SodOptions = SodOptions(),
): TestDocument =
    TestDocument(
        pki,
        ds,
        TestSod.build(ds, groups, options),
        groups,
        caKeyPair,
        aaKeyPair,
        aaDigestAlgorithm,
        dateOfIssue,
        dateOfExpiry,
        documentCode,
    )

/**
 * Copie dont DG14 annonce la Chip Authentication [protocol] (ChipAuthenticationInfo), en plus
 * de la clé CA et de l'éventuelle ActiveAuthenticationInfo d'origine ; SOD signé à nouveau.
 * Sans ChipAuthenticationInfo, JMRTD choisit la CA 3DES : ceci permet d'exercer la CA AES.
 */
internal fun TestDocument.withChipAuthenticationProtocol(protocol: String = SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128): TestDocument {
    val caKeys = checkNotNull(caKeyPair) { "document sans clé CA" }
    val aaInfos =
        dataGroups[DG14]
            ?.let { DG14File(it.inputStream()).securityInfos }
            .orEmpty()
            .filterIsInstance<ActiveAuthenticationInfo>()
    val infos = listOf<SecurityInfo>(ChipAuthenticationPublicKeyInfo(caKeys.public), ChipAuthenticationInfo(protocol, 1)) + aaInfos
    return resigned(dataGroups + (DG14 to DG14File(infos).encoded))
}

/** Copie dont la puce détient une autre clé AA que celle publiée dans DG15 (puce clonée ou falsifiée). */
internal fun TestDocument.withChipAaKey(keys: KeyPair): TestDocument =
    TestDocument(pki, ds, sod, dataGroups, caKeyPair, keys, aaDigestAlgorithm, dateOfIssue, dateOfExpiry, documentCode)

private const val DG14 = 14
