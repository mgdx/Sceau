package io.github.mgdx.sceau.core.verify

import io.github.mgdx.sceau.core.report.ChainInfo
import io.github.mgdx.sceau.core.report.Check
import io.github.mgdx.sceau.core.report.CheckDetail
import io.github.mgdx.sceau.core.report.CheckId
import io.github.mgdx.sceau.core.report.CheckStatus
import io.github.mgdx.sceau.core.report.IssuanceDateSource
import io.github.mgdx.sceau.core.trust.TrustAnchor
import io.github.mgdx.sceau.core.trust.TrustStore
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

/** EF.SOD décodé : SignedData CMS, son unique signataire et le LDSSecurityObject signé. */
internal class ParsedSod(
    val signedData: CMSSignedData,
    val signer: SignerInformation,
    val securityObject: LDSSecurityObject,
) {
    companion object {
        private const val SOD_TAG = 0x77
        private const val SEQUENCE_TAG = 0x30

        /** Lève une exception sans donnée personnelle si EF.SOD est mal formé. */
        fun parse(sod: ByteArray): ParsedSod {
            val contentInfo = if (sod.isNotEmpty() && sod[0].toInt() and 0xFF == SEQUENCE_TAG) sod else Tlv.value(sod, SOD_TAG)
            val signedData = CMSSignedData(contentInfo)
            // Audit V10 : le contenu signé doit être déclaré comme LDSSecurityObject, et non comme
            // un autre objet signé par le même DS qui se décoderait par hasard.
            if (signedData.signedContentTypeOID != ICAOObjectIdentifiers.id_icao_ldsSecurityObject.id) {
                throw IllegalArgumentException("SOD_CONTENT_TYPE")
            }
            val signer = signedData.signerInfos.signers.singleOrNull() ?: throw IllegalArgumentException("SOD_SIGNER_COUNT")
            val content = signedData.signedContent?.content as? ByteArray ?: throw IllegalArgumentException("SOD_NO_CONTENT")
            val securityObject = LDSSecurityObject.getInstance(ASN1Primitive.fromByteArray(content))
            return ParsedSod(signedData, signer, securityObject)
        }
    }
}

/** Décodage minimal d'un TLV BER à longueur définie (tag sur un octet). */
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
 * Vérification d'EF.CardSecurity ([PassiveAuthenticator.verifyCardSecurity]).
 *
 * [status] : `OK` (signature et chaîne valides), `NOT_AVAILABLE` (émetteur introuvable :
 * DS ni embarqué ni dans le magasin, ou chaîne sans CSCA connu), `FAILED` ou
 * `UNSUPPORTED_ALGORITHM` (alors [unsupported] porte l'algorithme). [reason] : cause stable,
 * sans donnée personnelle, null si `OK`. [content] : `SET OF SecurityInfo` signé, voir la
 * fonction de vérification pour les cas où il est rendu.
 */
internal class CardSecurityVerification(
    val status: CheckStatus,
    val reason: String?,
    val content: ByteArray?,
    val unsupported: CheckDetail? = null,
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

/** Implémentation de la Passive Authentication (SPEC §6.1 étape 5). */
internal class PassiveAuthenticator(
    private val trustStore: TrustStore,
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
    ): PassiveAuthResult {
        val parsed =
            try {
                ParsedSod.parse(sod)
            } catch (e: Exception) {
                return malformed(ERROR_SOD_MALFORMED)
            }
        return try {
            verifyParsed(parsed, dataGroups, dateOfIssue, dateOfExpiry, documentCode, issuingState)
        } catch (e: Exception) {
            malformed(ERROR_UNEXPECTED)
        }
    }

    private fun verifyParsed(
        parsed: ParsedSod,
        dataGroups: Map<Int, ByteArray>,
        dateOfIssue: LocalDate?,
        dateOfExpiry: LocalDate?,
        documentCode: String,
        issuingState: String?,
    ): PassiveAuthResult {
        val hashes = guarded(CheckId.DG_HASHES) { checkHashes(parsed.securityObject, dataGroups) }
        val ds =
            findDsCertificate(parsed.signedData, parsed.signer)
                ?: return PassiveAuthResult(
                    sodSignature = Check(CheckId.SOD_SIGNATURE, CheckStatus.NOT_AVAILABLE, CheckDetail.DsCertificateMissing),
                    certificateChain = Check(CheckId.CERTIFICATE_CHAIN, CheckStatus.NOT_AVAILABLE, CheckDetail.DsCertificateMissing),
                    dsValidity = Check(CheckId.DS_VALIDITY, CheckStatus.NOT_AVAILABLE),
                    dataGroupHashes = hashes,
                    chain = null,
                )

        val signature = guarded(CheckId.SOD_SIGNATURE) { checkSignature(parsed.signer, ds) }
        val validity = guarded(CheckId.DS_VALIDITY) { checkValidity(parsed.signer, ds, dateOfIssue, dateOfExpiry, documentCode) }
        var chainInfo: ChainInfo? = null
        val chain =
            guarded(CheckId.CERTIFICATE_CHAIN) {
                val (check, info) = checkChain(ds, issuingState)
                chainInfo = info
                check
            }
        return PassiveAuthResult(
            sodSignature = signature,
            certificateChain = chain,
            dsValidity = validity,
            dataGroupHashes = hashes,
            chain = chainInfo,
        )
    }

    // --- EF.CardSecurity (PACE-CAM) ------------------------------------------------------

    /**
     * Vérifie EF.CardSecurity (ICAO 9303-11 §4.4, PACE-CAM ; décision D21) avec les mêmes règles
     * que le SOD : certificat DS (embarqué ou cherché dans le magasin), signature CMS (algorithmes
     * faibles refusés, audit V9), chaîne DS → CSCA (profil d'émetteur, keyUsage du DS, pays
     * cohérents avec [issuingState], audit V3 et V8). Le contenu signé doit être déclaré
     * id-SecurityObject (BSI TR-03110) et n'avoir qu'un signataire.
     *
     * Le contenu n'est rendu que si la signature est vérifiée, ou si le DS est introuvable (la
     * chaîne est alors `NOT_AVAILABLE` : ce contenu ne peut que faire échouer un contrôle, jamais
     * le faire réussir). La validité calendaire du DS n'est pas contrôlée ici : elle l'est pour
     * le DS du SOD (ligne DS_VALIDITY).
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
        if (signedData.signedContentTypeOID != ID_SECURITY_OBJECT || signer == null || content == null) {
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
        val (chain, _) = checkChain(ds, issuingState)
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
        )
    }

    // --- Certificat DS ----------------------------------------------------------------

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

        // DS non embarqué : on le cherche dans le magasin par émetteur et numéro de série,
        // ou par identifiant de clé si le SignerIdentifier est de cette forme.
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

    // --- Signature du SOD --------------------------------------------------------------

    private fun checkSignature(
        signer: SignerInformation,
        ds: DsCertificate,
    ): Check {
        val algorithm = signatureAlgorithmName(signer)
        // Audit V9 : hachage cassé (MD5, RIPEMD-128…) refusé, dans l'empreinte comme dans la signature.
        if (Crypto.isWeakAlgorithm(signer.digestAlgorithmID) ||
            Crypto.isWeakAlgorithm(signer.toASN1Structure().digestEncryptionAlgorithm)
        ) {
            return Check(CheckId.SOD_SIGNATURE, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(algorithm))
        }
        return try {
            // Vérifieur construit sur la clé seule : BouncyCastle refuserait sinon la signature d'un
            // DS expiré au signingTime, ce qui relève de la ligne DS_VALIDITY et non de celle-ci.
            val publicKey = JcaX509CertificateConverter().setProvider(Crypto.provider).getCertificate(ds.holder).publicKey
            Crypto.weakKey(publicKey)?.let { weak ->
                return Check(CheckId.SOD_SIGNATURE, CheckStatus.UNSUPPORTED_ALGORITHM, CheckDetail.UnsupportedAlgorithm(weak))
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

    // --- Chaîne DS → CSCA --------------------------------------------------------------

    private fun checkChain(
        ds: DsCertificate,
        issuingState: String?,
    ): Pair<Check, ChainInfo> =
        when (val outcome = CertificateChains(trustStore).build(ds.holder)) {
            is ChainOutcome.Found -> {
                val info = chainInfo(ds, outcome.root, outcome.links)
                // Audit V3 : une chaîne valide vers le CSCA d'un autre pays ne prouve rien.
                val dsCountry = Crypto.country(ds.holder.subject)
                when {
                    // Audit V8 : un certificat dont le keyUsage exclut la signature numérique
                    // n'est pas un DS, quelle que soit sa chaîne.
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

    /** keyUsage du DS, s'il est présent, avec digitalSignature (ICAO 9303-12) ; absent : toléré. */
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

    // --- Empreintes des DG -------------------------------------------------------------

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
        // Le SOD, signé, fait foi : un DG qu'il annonce parmi ceux que Sceau lit doit avoir été
        // fourni par la puce (audit V1 : un clone ne doit pas pouvoir retenir DG2, DG14 ou DG15).
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

    // --- Validité du DS à la date de délivrance ------------------------------------------

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

                // Une date estimée n'est jamais un motif d'échec : un passeport de mineur vaut 5 ans.
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
     * Durée de validité usuelle, pour estimer la délivrance. Passeports et cartes
     * d'identité (CNIe depuis 2021) valent 10 ans pour un adulte, quel que soit le code.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun usualValidityYears(documentCode: String): Long = USUAL_VALIDITY_YEARS

    // --- Erreurs ---------------------------------------------------------------------

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
        const val USUAL_VALIDITY_YEARS = 10L
        const val HEX = 16

        /** eContentType d'EF.CardSecurity : id-SecurityObject (BSI TR-03110 partie 3, ICAO 9303-11). */
        const val ID_SECURITY_OBJECT = "0.4.0.127.0.7.3.2.1"

        /** DG que Sceau demande à la puce (voir DocumentReader) : jamais DG3 ni DG4. */
        val READ_DATA_GROUPS = setOf(1, 2, 11, 12, 14, 15)
    }
}
