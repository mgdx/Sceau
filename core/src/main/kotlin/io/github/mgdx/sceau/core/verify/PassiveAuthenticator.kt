package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.ChainInfo
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.IssuanceDateSource
import io.github.mgdx.sceau.core.report.KnownDeviation
import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustStore
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.icao.ICAOObjectIdentifiers
import org.bouncycastle.asn1.icao.LDSSecurityObject
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignerDigestMismatchException
import org.bouncycastle.cms.DefaultCMSSignatureAlgorithmNameGenerator
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset

/** Decoded EF.SOD: CMS SignedData, its single signer, and the signed LDSSecurityObject. */
internal class ParsedSod(
    val signedData: CMSSignedData,
    val signer: SignerInformation,
    val securityObject: LDSSecurityObject,
) {
    companion object {
        private const val SOD_TAG = 0x77
        private const val SEQUENCE_TAG = 0x30

        /** Throws an exception without personal data if EF.SOD is malformed. */
        fun parse(sod: ByteArray): ParsedSod {
            val contentInfo = if (sod.isNotEmpty() && sod[0].toInt() and 0xFF == SEQUENCE_TAG) sod else Tlv.value(sod, SOD_TAG)
            val signedData = CMSSignedData(contentInfo)
            // Audit V10: the signed content must be declared as an LDSSecurityObject, not as
            // another object signed by the same DS that would happen to decode.
            if (signedData.signedContentTypeOID != ICAOObjectIdentifiers.id_icao_ldsSecurityObject.id) {
                throw IllegalArgumentException("SOD_CONTENT_TYPE")
            }
            val signer = signedData.signerInfos.signers.singleOrNull() ?: throw IllegalArgumentException("SOD_SIGNER_COUNT")
            if (!hasRequiredSignedAttributes(signer, ICAOObjectIdentifiers.id_icao_ldsSecurityObject)) {
                throw IllegalArgumentException("SOD_SIGNED_ATTRIBUTES")
            }
            val content = signedData.signedContent?.content as? ByteArray ?: throw IllegalArgumentException("SOD_NO_CONTENT")
            val securityObject = LDSSecurityObject.getInstance(ASN1Primitive.fromByteArray(content))
            return ParsedSod(signedData, signer, securityObject)
        }

        /**
         * Audit V40: ICAO 9303-10 requires signed attributes, including `messageDigest`; their
         * `contentType` must be the encapsulated content type [contentType]. Otherwise BouncyCastle
         * accepts a `SignerInfo` without attributes (direct signature of the content), which no issuer
         * produces. Applies to EF.SOD and to EF.CardSecurity.
         */
        fun hasRequiredSignedAttributes(
            signer: SignerInformation,
            contentType: ASN1ObjectIdentifier,
        ): Boolean {
            val attributes = signer.signedAttributes ?: return false
            if (attributes.get(CMSAttributes.messageDigest) == null) return false
            val declared = attributes.get(CMSAttributes.contentType)?.attrValues ?: return false
            return declared.size() == 1 && declared.getObjectAt(0) == contentType
        }
    }
}

/** Minimal decoding of a definite-length BER TLV (single-byte tag). */
internal object Tlv {
    private const val MAX_LENGTH_BYTES = 4

    fun value(
        data: ByteArray,
        expectedTag: Int,
    ): ByteArray {
        require(data.size >= 2 && data[0].toInt() and 0xFF == expectedTag) { "TLV_TAG" }
        var offset = 1
        val first = data[offset++].toInt() and 0xFF
        val length =
            if (first < 0x80) {
                first
            } else {
                val count = first and 0x7F
                require(count in 1..MAX_LENGTH_BYTES && offset + count <= data.size) { "TLV_LENGTH" }
                var value = 0L
                repeat(count) { value = (value shl 8) or (data[offset++].toLong() and 0xFF) }
                require(value <= Int.MAX_VALUE) { "TLV_LENGTH" }
                value.toInt()
            }
        require(offset + length <= data.size) { "TLV_LENGTH" }
        return data.copyOfRange(offset, offset + length)
    }
}

/**
 * Verification of EF.CardSecurity ([PassiveAuthenticator.verifyCardSecurity]).
 *
 * [status]: `OK` (valid signature and chain), `NOT_AVAILABLE` (issuer not found: DS neither
 * embedded nor in the store, or chain without a known CSCA), `FAILED` or
 * `UNSUPPORTED_ALGORITHM` (then [unsupported] carries the algorithm). [reason]: stable cause,
 * without personal data, null if `OK`. [content]: signed `SET OF SecurityInfo`, see the
 * verification function for the cases where it is returned. [chain]: DS → CSCA chain of
 * EF.CardSecurity, null if the DS is not found or the signature is wrong; it must lead to the
 * same CSCA as the SOD chain (audit V17, decision D40).
 */
internal class CardSecurityVerification(
    val status: CheckStatus,
    val reason: String?,
    val content: ByteArray?,
    val unsupported: CheckDetail? = null,
    val chain: ChainInfo? = null,
) {
    companion object {
        const val MALFORMED = "MALFORMED"
        const val DS_MISSING = "DS_MISSING"
        const val SIGNATURE = "SIGNATURE"
        const val CHAIN = "CHAIN"
        const val COUNTRY = "COUNTRY"
        const val UNKNOWN_ISSUER = "UNKNOWN_ISSUER"
    }
}

/**
 * Passive Authentication implementation (SPEC §6.1 step 5).
 *
 * [deviations]: known issuer anomalies (decision D34), the real registry by default; tests
 * inject others for the fake PKI.
 */
internal class PassiveAuthenticator(
    private val trustStore: TrustStore,
    private val deviations: KnownDeviations = KnownDeviations.REGISTRY,
) {
    private class DsCertificate(
        val holder: X509CertificateHolder,
    )

    fun verify(
        sod: ByteArray,
        dataGroups: Map<Int, ByteArray>,
        dateOfIssue: LocalDate?,
        dateOfExpiry: LocalDate?,
        documentCode: String,
        issuingState: String? = null,
        documentNumber: String? = null,
    ): PassiveAuthResult {
        val parsed =
            try {
                ParsedSod.parse(sod)
            } catch (e: Exception) {
                return malformed(ERROR_SOD_MALFORMED)
            }
        return try {
            verifyParsed(parsed, dataGroups, DocumentFacts(dateOfIssue, dateOfExpiry, documentCode, issuingState, documentNumber))
        } catch (e: Exception) {
            malformed(ERROR_UNEXPECTED)
        }
    }

    /** Read data (DG1, DG12 date of issue) passed to the verification. */
    private class DocumentFacts(
        val dateOfIssue: LocalDate?,
        val dateOfExpiry: LocalDate?,
        val documentCode: String,
        val issuingState: String?,
        val documentNumber: String?,
    )

    private fun verifyParsed(
        parsed: ParsedSod,
        dataGroups: Map<Int, ByteArray>,
        facts: DocumentFacts,
    ): PassiveAuthResult {
        val rawHashes = guarded(CheckId.DG_HASHES) { checkHashes(parsed.securityObject, dataGroups) }
        // Audit V25: issuing country for which the store holds certificates; a missing chain
        // is then a failure, not an unknown issuer.
        val knownCountry = knownIssuingCountry(facts.issuingState)
        val ds =
            findDsCertificate(parsed.signedData, parsed.signer)
                ?: return PassiveAuthResult(
                    sodSignature = Check(CheckId.SOD_SIGNATURE, CheckStatus.NOT_AVAILABLE, CheckDetail.DsCertificateMissing),
                    certificateChain =
                        knownCountry?.let { noChain(it, null) }
                            ?: unknownStateCode(facts.issuingState)
                            ?: Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.NOT_AVAILABLE, CheckDetail.DsCertificateMissing),
                    dsValidity = Check(CheckId.DS_VALIDITY, CheckStatus.NOT_AVAILABLE),
                    dataGroupHashes = rawHashes,
                    chain = null,
                )

        val verifiedSignature = guarded(CheckId.SOD_SIGNATURE) { checkSignature(parsed.signer, ds) }
        var chainInfo: ChainInfo? = null
        var chainMissing = false
        val chain =
            guarded(CheckId.CERTIFICATE_CHAIN) {
                val (check, info) = checkChain(ds, facts.issuingState)
                chainInfo = info
                chainMissing = check.status == CheckStatus.NOT_AVAILABLE
                when {
                    !chainMissing -> check

                    knownCountry != null -> noChain(knownCountry, info)

                    // Audit V35: without a chain, an unknown state code or a DS from another country fails.
                    else -> unknownStateCode(facts.issuingState) ?: dsCountryMismatch(ds, facts.issuingState) ?: check
                }
            }
        // Audit V25: without a chain, nobody vouches for the DS; a signature it verifies
        // proves nothing and is not presented as verified.
        val signature =
            if (chainMissing && verifiedSignature.status == CheckStatus.OK) {
                verifiedSignature.copy(status = CheckStatus.NOT_AVAILABLE)
            } else {
                verifiedSignature
            }
        // Decision D34: a known anomaly is only looked for on a SOD whose signature and chain
        // are valid; otherwise, no tolerance.
        val tolerated =
            if (signature.status == CheckStatus.OK && chain.status == CheckStatus.OK) {
                knownDeviations(parsed.signer, rawHashes, chainInfo, facts)
            } else {
                emptyList()
            }
        val hashes = tolerate(rawHashes, tolerated)
        val discarded = tolerated.map { it.dataGroup }.toSet()
        // A discarded DG12 does not provide the date of issue: fall back on signingTime or the estimate.
        val dateOfIssue = facts.dateOfIssue.takeUnless { DG12 in discarded }
        val validity =
            guarded(CheckId.DS_VALIDITY) {
                checkValidity(parsed.signer, ds, dateOfIssue, facts.dateOfExpiry, facts.documentCode)
            }
        return PassiveAuthResult(
            sodSignature = signature,
            certificateChain = chain,
            dsValidity = validity,
            dataGroupHashes = hashes,
            chain = chainInfo,
            discardedDataGroups = discarded,
        )
    }

    // --- Known issuer anomalies (decision D34) ------------------------------------------

    /**
     * Known anomalies that cover the hash mismatches of [hashes]. Requires the DG1 hash to be
     * verified (the DG1 elements are then signed) and no signed DG to be missing.
     */
    private fun knownDeviations(
        signer: SignerInformation,
        hashes: Check,
        chainInfo: ChainInfo?,
        facts: DocumentFacts,
    ): List<KnownDeviation> {
        val detail = hashes.detail as? CheckDetail.DataGroupHashes ?: return emptyList()
        val dg1Verified = DG1 in detail.checked && DG1 !in detail.mismatched
        if (!dg1Verified || detail.missing.isNotEmpty() || detail.mismatched.isEmpty()) return emptyList()
        val evidence =
            DeviationEvidence(
                cscaCountry = chainInfo?.cscaCountry,
                issuingState = facts.issuingState,
                documentCode = facts.documentCode,
                documentNumber = facts.documentNumber,
                dateOfExpiry = facts.dateOfExpiry,
                sodSigningTime = signingDate(signer),
                dg12DateOfIssue = facts.dateOfIssue,
            )
        return deviations
            .applicable(evidence, detail.mismatched)
            .filter { it.dataGroup in KnownDeviations.TOLERABLE_DATA_GROUPS }
    }

    /** Removes the tolerated DGs from the list of mismatches and recomputes the line status. */
    private fun tolerate(
        hashes: Check,
        tolerated: List<KnownDeviation>,
    ): Check {
        val detail = hashes.detail as? CheckDetail.DataGroupHashes
        if (tolerated.isEmpty() || detail == null) return hashes
        val numbers = tolerated.map { it.dataGroup }.toSet()
        val mismatched = detail.mismatched.filter { it !in numbers }
        val status = if (mismatched.isEmpty() && detail.missing.isEmpty()) CheckStatus.OK else CheckStatus.FAILED
        return Check(CheckId.DG_HASHES, status, detail.copy(mismatched = mismatched, deviations = tolerated))
    }

    // --- EF.CardSecurity (PACE-CAM) ------------------------------------------------------

    /**
     * Verifies EF.CardSecurity (ICAO 9303-11 §4.4, PACE-CAM; decision D21) with the same rules
     * as the SOD: DS certificate (embedded or looked up in the store), CMS signature (weak
     * algorithms rejected, audit V9), DS → CSCA chain (issuer profile, DS keyUsage, countries
     * consistent with [issuingState], audits V3 and V8). The signed content must be declared as
     * id-SecurityObject (BSI TR-03110) and have only one signer.
     *
     * The content is returned only if the signature is verified, or if the DS is not found (the
     * chain is then `NOT_AVAILABLE`: this content can only make a check fail, never make it
     * pass). The DS calendar validity is not checked here: it is checked for the SOD's DS
     * (DS_VALIDITY line).
     */
    fun verifyCardSecurity(
        cardSecurity: ByteArray,
        issuingState: String?,
    ): CardSecurityVerification =
        try {
            verifyCardSecurityOrThrow(cardSecurity, issuingState)
        } catch (e: Exception) {
            CardSecurityVerification(CheckStatus.FAILED, CardSecurityVerification.MALFORMED, null)
        }

    private fun verifyCardSecurityOrThrow(
        cardSecurity: ByteArray,
        issuingState: String?,
    ): CardSecurityVerification {
        val signedData = CMSSignedData(cardSecurity)
        val signer = signedData.signerInfos.signers.singleOrNull()
        val content = signedData.signedContent?.content as? ByteArray
        if (signedData.signedContentTypeOID != ID_SECURITY_OBJECT ||
            signer == null ||
            content == null ||
            // Audit V40: same signed attributes required as for the SOD.
            !ParsedSod.hasRequiredSignedAttributes(signer, ASN1ObjectIdentifier(ID_SECURITY_OBJECT))
        ) {
            return CardSecurityVerification(CheckStatus.FAILED, CardSecurityVerification.MALFORMED, null)
        }
        val ds =
            findDsCertificate(signedData, signer)
                ?: return CardSecurityVerification(CheckStatus.NOT_AVAILABLE, CardSecurityVerification.DS_MISSING, content)
        val signature = checkSignature(signer, ds)
        when (signature.status) {
            CheckStatus.OK -> {}

            CheckStatus.UNSUPPORTED_ALGORITHM -> {
                return CardSecurityVerification(signature.status, CardSecurityVerification.SIGNATURE, null, signature.detail)
            }

            else -> {
                return CardSecurityVerification(CheckStatus.FAILED, CardSecurityVerification.SIGNATURE, null)
            }
        }
        val (chain, chainInfo) = checkChain(ds, issuingState)
        val reason =
            when {
                chain.status == CheckStatus.OK -> null
                chain.detail is CheckDetail.CountryMismatch -> CardSecurityVerification.COUNTRY
                chain.status == CheckStatus.NOT_AVAILABLE -> CardSecurityVerification.UNKNOWN_ISSUER
                else -> CardSecurityVerification.CHAIN
            }
        return CardSecurityVerification(
            chain.status,
            reason,
            content,
            chain.detail.takeIf {
                chain.status ==
                    CheckStatus.UNSUPPORTED_ALGORITHM
            },
            chainInfo,
        )
    }

    // --- DS certificate --------------------------------------------------------------

    private fun findDsCertificate(
        signedData: CMSSignedData,
        signer: SignerInformation,
    ): DsCertificate? {
        val sid = signer.sid
        val embedded =
            signedData.certificates
                .getMatches(null)
                .firstOrNull { sid.match(it) }
        if (embedded != null) return DsCertificate(embedded)

        // DS not embedded: look it up in the store by issuer and serial number,
        // or by key identifier if the SignerIdentifier has that form.
        val issuer = sid.issuer
        val serial = sid.serialNumber
        val anchor: TrustAnchor? =
            if (issuer != null && serial != null) {
                val issuerPrincipal = Crypto.principal(issuer)
                trustStore.anchors.firstOrNull {
                    it.certificate.serialNumber == serial && it.certificate.issuerX500Principal == issuerPrincipal
                }
            } else {
                sid.subjectKeyIdentifier?.let { trustStore.findBySubjectKeyId(it).firstOrNull() }
            }
        return anchor?.let { DsCertificate(JcaX509CertificateHolder(it.certificate)) }
    }

    // --- SOD signature ---------------------------------------------------------------

    private fun checkSignature(
        signer: SignerInformation,
        ds: DsCertificate,
    ): Check {
        val algorithm = signatureAlgorithmName(signer)
        // Audit V9: broken hash (MD5, RIPEMD-128…) rejected, in the digest as in the signature.
        if (Crypto.isWeakAlgorithm(signer.digestAlgorithmID) ||
            Crypto.isWeakAlgorithm(signer.toASN1Structure().digestEncryptionAlgorithm)
        ) {
            return Check(CheckId.SOD_SIGNATURE, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(algorithm))
        }
        // Audits V9 and V30: out-of-bounds DS key, read encoded, rejected before any reconstruction.
        Crypto.unsupportedKey(ds.holder.subjectPublicKeyInfo)?.let { key ->
            return Check(CheckId.SOD_SIGNATURE, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(key))
        }
        return try {
            // Verifier built on the key alone: otherwise BouncyCastle would reject the signature of
            // a DS expired at signingTime, which belongs to the DS_VALIDITY line, not to this one.
            val publicKey = JcaX509CertificateConverter().setProvider(Crypto.provider).getCertificate(ds.holder).publicKey
            Crypto.unsupportedKey(publicKey)?.let { key ->
                return Check(CheckId.SOD_SIGNATURE, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(key))
            }
            val verifier = JcaSimpleSignerInfoVerifierBuilder().setProvider(Crypto.provider).build(publicKey)
            val valid = signer.verify(verifier)
            Check(CheckId.SOD_SIGNATURE, if (valid) CheckStatus.OK else CheckStatus.FAILED, CheckDetail.Signature(algorithm))
        } catch (e: CMSSignerDigestMismatchException) {
            Check(CheckId.SOD_SIGNATURE, CheckStatus.FAILED, CheckDetail.Signature(algorithm))
        } catch (e: Exception) {
            if (Crypto.isUnsupportedAlgorithm(e)) {
                Check(CheckId.SOD_SIGNATURE, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(algorithm))
            } else {
                Check(CheckId.SOD_SIGNATURE, CheckStatus.FAILED, CheckDetail.Signature(algorithm))
            }
        }
    }

    private fun signatureAlgorithmName(signer: SignerInformation): String {
        val encryption = signer.toASN1Structure().digestEncryptionAlgorithm
        if (!Crypto.isKnownAlgorithm(encryption.algorithm)) return encryption.algorithm.id
        return DefaultCMSSignatureAlgorithmNameGenerator().getSignatureName(signer.digestAlgorithmID, encryption)
    }

    // --- DS → CSCA chain --------------------------------------------------------------

    private fun checkChain(
        ds: DsCertificate,
        issuingState: String?,
    ): Pair<Check, ChainInfo> =
        when (val outcome = CertificateChains(trustStore).build(ds.holder)) {
            is ChainOutcome.Found -> {
                val info = chainInfo(ds, outcome.root, outcome.links)
                // Audit V3: a valid chain to another country's CSCA proves nothing.
                val dsCountry = Crypto.country(ds.holder.subject)
                when {
                    // Audit V8: a certificate whose keyUsage excludes digital signature
                    // is not a DS, whatever its chain.
                    !mayBeDocumentSigner(ds.holder) -> {
                        Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.Error(ERROR_DS_KEY_USAGE)) to info
                    }

                    IcaoCountries.consistent(info.cscaCountry, dsCountry, issuingState) -> {
                        Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.OK, CheckDetail.Chain(info)) to info
                    }

                    else -> {
                        val mismatch = CheckDetail.CountryMismatch(info.cscaCountry, dsCountry, issuingState)
                        Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, mismatch) to info
                    }
                }
            }

            is ChainOutcome.BadSignature -> {
                val info = chainInfo(ds, outcome.issuer, emptyList())
                Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.Chain(info)) to info
            }

            is ChainOutcome.Unsupported -> {
                val info = chainInfo(ds, null, emptyList())
                Check(
                    CheckId.CERTIFICATE_CHAIN,
                    CheckStatus.UNSUPPORTED_ALGORITHM,
                    CheckDetail.UnsupportedAlgorithm(outcome.algorithm),
                ) to info
            }

            ChainOutcome.NotFound -> {
                val info = chainInfo(ds, null, emptyList())
                Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.NOT_AVAILABLE, CheckDetail.Chain(info)) to info
            }

            ChainOutcome.BudgetExceeded -> {
                val info = chainInfo(ds, null, emptyList())
                Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.Error(ERROR_CHAIN_BUDGET)) to info
            }
        }

    /**
     * Alpha-2 country of the issuing state [issuingState] if the store holds at least one
     * certificate of that country (CSCA or link), null otherwise: state absent from the store,
     * organization (`UNO`, `EUE`…), unknown or missing code (audit V25).
     */
    private fun knownIssuingCountry(issuingState: String?): String? {
        if (issuingState == null || IcaoCountries.isOrganization(issuingState)) return null
        val country = IcaoCountries.alpha2(issuingState) ?: return null
        val known =
            trustStore.anchors.any { anchor ->
                runCatching { IcaoCountries.consistent(anchor.country, anchor.country, issuingState) }.getOrDefault(false)
            }
        return country.takeIf { known }
    }

    /**
     * Without a chain, an issuing state [issuingState] unknown to ICAO 9303-3 (neither a country
     * nor an organization: `UTO`, `ZZZ`, `FR<`…) fails, since no signed document can declare it
     * (audit V35). Null if the code is a country, an organization, or if it is not provided.
     */
    private fun unknownStateCode(issuingState: String?): Check? {
        if (issuingState == null || IcaoCountries.isOrganization(issuingState) || IcaoCountries.alpha2(issuingState) != null) return null
        return Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.Error(ERROR_UNKNOWN_STATE_CODE))
    }

    /**
     * Without a chain, a DS country (C attribute, including when absent) different from the DG1
     * issuing state fails with [CheckDetail.CountryMismatch], as with a chain (audit V35). Null if
     * the countries match, if the state is an organization (the DS then carries a code such as
     * `UN` or `EU`), or if it is not provided.
     */
    private fun dsCountryMismatch(
        ds: DsCertificate,
        issuingState: String?,
    ): Check? {
        if (issuingState == null) return null
        val dsCountry = Crypto.country(ds.holder.subject)
        if (IcaoCountries.consistent(dsCountry, dsCountry, issuingState)) return null
        return Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.CountryMismatch(null, dsCountry, issuingState))
    }

    /** No chain although certificates of country [country] are known: failure (audit V25). */
    private fun noChain(
        country: String,
        chain: ChainInfo?,
    ) = Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.NoChainForKnownCountry(country, chain))

    /** DS keyUsage, if present, with digitalSignature (ICAO 9303-12); absent: tolerated. */
    private fun mayBeDocumentSigner(holder: X509CertificateHolder): Boolean =
        runCatching {
            KeyUsage.fromExtensions(holder.extensions)?.hasUsages(KeyUsage.digitalSignature) ?: true
        }.getOrDefault(false)

    private fun chainInfo(
        ds: DsCertificate,
        csca: TrustAnchor?,
        links: List<TrustAnchor>,
    ): ChainInfo =
        ChainInfo(
            dsSubject = Crypto.subject(ds.holder),
            dsSerialNumber =
                ds.holder.serialNumber
                    .toString(HEX)
                    .uppercase(),
            dsNotBefore = ds.holder.notBefore.toInstant(),
            dsNotAfter = ds.holder.notAfter.toInstant(),
            signatureAlgorithm = Crypto.algorithmName(ds.holder.signatureAlgorithm.algorithm),
            cscaSubject = csca?.certificate?.subjectX500Principal?.name,
            cscaCountry = csca?.let { Crypto.country(it.certificate) },
            cscaSha256 = csca?.let { Crypto.fingerprint(it.certificate) },
            cscaSource = csca?.source,
            linkCertificates = links.map { it.certificate.subjectX500Principal.name },
        )

    // --- DG hashes -------------------------------------------------------------------

    private fun checkHashes(
        securityObject: LDSSecurityObject,
        dataGroups: Map<Int, ByteArray>,
    ): Check {
        val algorithmId = securityObject.digestAlgorithmIdentifier
        val algorithm = Crypto.algorithmName(algorithmId.algorithm)
        if (Crypto.isWeakAlgorithm(algorithmId)) {
            return Check(CheckId.DG_HASHES, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(algorithm))
        }
        val calculators = JcaDigestCalculatorProviderBuilder().setProvider(Crypto.provider).build()
        try {
            calculators.get(algorithmId)
        } catch (e: OperatorCreationException) {
            return Check(CheckId.DG_HASHES, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(algorithm))
        }

        val expected = securityObject.datagroupHash.associate { it.dataGroupNumber to it.dataGroupHashValue.octets }
        val checked = dataGroups.keys.sorted()
        // The signed SOD is authoritative: a DG it declares among those Sceau reads must have been
        // provided by the chip (audit V1: a clone must not be able to withhold DG2, DG14 or DG15).
        val missing = expected.keys.filter { it in READ_DATA_GROUPS && it !in dataGroups }.sorted()
        val mismatched =
            checked.filter { number ->
                val reference = expected[number] ?: return@filter true
                val calculator = calculators.get(algorithmId)
                calculator.outputStream.use { it.write(dataGroups.getValue(number)) }
                !MessageDigest.isEqual(reference, calculator.digest)
            }
        val status =
            when {
                mismatched.isNotEmpty() || missing.isNotEmpty() -> CheckStatus.FAILED
                checked.isEmpty() -> CheckStatus.NOT_AVAILABLE
                else -> CheckStatus.OK
            }
        return Check(CheckId.DG_HASHES, status, CheckDetail.DataGroupHashes(algorithm, checked, mismatched, missing))
    }

    // --- DS validity at the date of issue ----------------------------------------------

    private fun checkValidity(
        signer: SignerInformation,
        ds: DsCertificate,
        dateOfIssue: LocalDate?,
        dateOfExpiry: LocalDate?,
        documentCode: String,
    ): Check {
        val notBefore = ds.holder.notBefore.toInstant()
        val notAfter = ds.holder.notAfter.toInstant()
        val signingTime = signingDate(signer)
        val (date, source) =
            when {
                dateOfIssue != null -> dateOfIssue to IssuanceDateSource.DG12
                signingTime != null -> signingTime to IssuanceDateSource.SOD_SIGNING_TIME
                dateOfExpiry != null -> dateOfExpiry.minusYears(usualValidityYears(documentCode)) to IssuanceDateSource.ESTIMATED
                else -> null to null
            }
        val detail = CheckDetail.DsValidity(notBefore, notAfter, date, source)
        if (date == null) return Check(CheckId.DS_VALIDITY, CheckStatus.NOT_AVAILABLE, detail)

        val covered =
            !date.isBefore(notBefore.atOffset(ZoneOffset.UTC).toLocalDate()) &&
                !date.isAfter(notAfter.atOffset(ZoneOffset.UTC).toLocalDate())
        val status =
            when {
                covered -> CheckStatus.OK

                // An estimated date is never a reason to fail: a minor's passport is valid for 5 years.
                source == IssuanceDateSource.ESTIMATED -> CheckStatus.NOT_AVAILABLE

                else -> CheckStatus.FAILED
            }
        return Check(CheckId.DS_VALIDITY, status, detail)
    }

    private fun signingDate(signer: SignerInformation): LocalDate? =
        runCatching {
            val attribute = signer.signedAttributes?.get(CMSAttributes.signingTime) ?: return null
            Time
                .getInstance(attribute.attrValues.getObjectAt(0))
                .date
                .toInstant()
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
        }.getOrNull()

    /**
     * Usual validity period, used to estimate the date of issue. Passports and identity
     * cards (CNIe since 2021) are valid for 10 years for an adult, whatever the code.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun usualValidityYears(documentCode: String): Long = USUAL_VALIDITY_YEARS

    // --- Errors ----------------------------------------------------------------------

    private inline fun guarded(
        id: CheckId,
        block: () -> Check,
    ): Check =
        try {
            block()
        } catch (e: Exception) {
            Check(id, CheckStatus.FAILED, CheckDetail.Error(ERROR_UNEXPECTED))
        }

    private fun malformed(code: String): PassiveAuthResult =
        PassiveAuthResult(
            sodSignature = Check(CheckId.SOD_SIGNATURE, CheckStatus.FAILED, CheckDetail.Error(code)),
            certificateChain = Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.FAILED, CheckDetail.Error(code)),
            dsValidity = Check(CheckId.DS_VALIDITY, CheckStatus.FAILED, CheckDetail.Error(code)),
            dataGroupHashes = Check(CheckId.DG_HASHES, CheckStatus.FAILED, CheckDetail.Error(code)),
            chain = null,
        )

    private companion object {
        const val ERROR_SOD_MALFORMED = "SOD_MALFORMED"
        const val ERROR_UNEXPECTED = "PA_UNEXPECTED"
        const val ERROR_DS_KEY_USAGE = "DS_KEY_USAGE"
        const val ERROR_CHAIN_BUDGET = "CHAIN_BUDGET"
        const val ERROR_UNKNOWN_STATE_CODE = "UNKNOWN_STATE_CODE"
        const val USUAL_VALIDITY_YEARS = 10L
        const val HEX = 16
        const val DG1 = 1
        const val DG12 = 12

        /** eContentType of EF.CardSecurity: id-SecurityObject (BSI TR-03110 part 3, ICAO 9303-11). */
        const val ID_SECURITY_OBJECT = "0.4.0.127.0.7.3.2.1"

        /** DGs that Sceau requests from the chip (see DocumentReader): never DG3 or DG4. */
        val READ_DATA_GROUPS = setOf(1, 2, 7, 11, 12, 14, 15)
    }
}
