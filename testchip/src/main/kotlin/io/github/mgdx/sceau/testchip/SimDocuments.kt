package io.github.mgdx.sceau.testchip

import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG14File
import java.security.KeyPair

/** Copy whose DGs are [groups], with an EF.SOD **signed again** by the same DS. */
fun TestDocument.resigned(
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
 * Copy whose DG14 announces Chip Authentication [protocol] (ChipAuthenticationInfo), in addition
 * to the original CA key and ActiveAuthenticationInfo, if any; SOD signed again.
 * Without ChipAuthenticationInfo, JMRTD picks 3DES CA: this makes it possible to exercise AES CA.
 */
fun TestDocument.withChipAuthenticationProtocol(protocol: String = SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128): TestDocument {
    val caKeys = checkNotNull(caKeyPair) { "document without CA key" }
    val aaInfos =
        dataGroups[DG14]
            ?.let { DG14File(it.inputStream()).securityInfos }
            .orEmpty()
            .filterIsInstance<ActiveAuthenticationInfo>()
    val infos = listOf<SecurityInfo>(ChipAuthenticationPublicKeyInfo(caKeys.public), ChipAuthenticationInfo(protocol, 1)) + aaInfos
    return resigned(dataGroups + (DG14 to DG14File(infos).encoded))
}

/** Copy whose chip holds an AA key other than the one published in DG15 (cloned or forged chip). */
fun TestDocument.withChipAaKey(keys: KeyPair): TestDocument =
    TestDocument(pki, ds, sod, dataGroups, caKeyPair, keys, aaDigestAlgorithm, dateOfIssue, dateOfExpiry, documentCode)

private const val DG14 = 14
