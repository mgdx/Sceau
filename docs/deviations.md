# Déviations connues et limites par pays

Ce document recense les non-conformités de documents réels à ICAO 9303 qui sont publiées ou rapportées, ainsi que les limites propres à certains pays. Il indique pour chaque cas ce que Sceau en fait aujourd'hui et ce qu'il pourrait en faire. Il complète `docs/trust-sources.md` §2.4 (Deviation Lists publiques), `docs/protocol.md` (séquence et règles de vérification) et les décisions D14 et D15 de `docs/decisions.md`.

- **Date de consultation** : 2026-10-04, pour toutes les pages et tous les fichiers cités, sauf mention contraire.
- **Méthode** : lecture des normes (ICAO 9303-12, BSI TR-03129-2, BSI TR-03127), des publications nationales et du code de lecteurs libres (JMRTD 0.8.8, pymrtd, NFCPassportReader). Les fichiers ont été téléchargés hors du dépôt, puis analysés (`openssl cms -verify -noverify`, `openssl asn1parse`, `asn1crypto`). Aucun captcha ni aucune condition d'utilisation n'ont été contournés.
- **Niveau de preuve** : chaque cas est marqué **vérifié** (lu dans la source primaire : norme, publication de l'État, fichier signé, code de Sceau) ou **rapporté** (affirmé par un tiers : commentaire de code d'une autre bibliothèque, ticket, éditeur commercial). Un cas rapporté n'a pas été reproduit sur un document réel.
- **Classement** :
  - **Codable** : peut entrer dans le registre des déviations connues (règles codées en dur). Le garde-fou actuel du registre ne tolère qu'un écart d'empreinte sur DG11 ou DG12, pour des documents désignés par des éléments signés, et écarte ce DG. Jamais DG1, DG2, DG14 ni DG15.
  - **À documenter** : limite à expliquer à l'utilisateur, ou à signaler à l'écran, sans tolérance.
  - **Déjà géré / hors périmètre** : Sceau le tolère déjà (code cité) ou n'est pas concerné.

## 1. Ce qu'est une déviation

### 1.1 Deviation List ICAO (9303-12 §10)

Une *Deviation List* (DL) est publiée par un État pour signaler des documents, des clés ou des certificats non conformes. Source : ICAO Doc 9303 partie 12, 8ᵉ édition (2021), §3.1.7 et §10, <https://www.icao.int/sites/default/files/publications/DocSeries/9303_p12_cons_en.pdf> (vérifié).

- **Format** : CMS `SignedData` en DER, type de contenu `id-icao-DeviationList` (`2.23.136.1.1.7`). Le signataire est un *Deviation List Signer* émis par le CSCA, avec l'usage étendu `id-icao-DeviationListSigningKey` (`2.23.136.1.1.8`). L'attribut `signingTime` est obligatoire.
- **Désignation des documents** (`DeviationDocuments`), critères combinés par un ET logique, jamais par un OU :
  - `documentType` : code de document de la MRZ (« P », « C »…) ;
  - `dscIdentifier` : certificat DS, par émetteur et numéro de série, par identifiant de clé (SKI) ou par empreinte ;
  - `issuingDate` : période de délivrance (`firstIssued`, `lastIssued`) ;
  - `documentNumbers` : liste de numéros de document.
- **Description** (`DeviationDescription`) : texte libre facultatif, type de déviation (OID), paramètres, champ d'usage national.

| OID | Nom 9303-12 | Paramètre |
|---|---|---|
| `2.23.136.1.1.7.1.1` | `id-Deviation-CertOrKey-DSSignature` | — |
| `2.23.136.1.1.7.1.2` | `id-Deviation-CertOrKey-DSEncoding` | `CertField` (champ ou extension du certificat) |
| `2.23.136.1.1.7.1.3` | `id-Deviation-CertOrKey-CSCAEncoding` | `CertField` |
| `2.23.136.1.1.7.1.4` | `id-Deviation-CertOrKey-AAKeyCompromised` | — |
| `2.23.136.1.1.7.2.1` | `id-Deviation-LDS-DGMalformed` | `Datagroup` (1 à 16, 20 = SOD, 21 = COM) |
| `2.23.136.1.1.7.2.2` | `id-Deviation-LDS-DGHashWrong` | `Datagroup` |
| `2.23.136.1.1.7.2.3` | `id-Deviation-LDS-SODSignatureWrong` | — |
| `2.23.136.1.1.7.2.4` | `id-Deviation-LDS-COMInconsistent` | — |
| `2.23.136.1.1.7.3.1` | `id-Deviation-MRZ-WrongData` | `MRZField` |
| `2.23.136.1.1.7.3.2` | `id-Deviation-MRZ-WrongCheckDigit` | `MRZField` |
| `2.23.136.1.1.7.4` | `id-Deviation-Chip` | — |
| `2.23.136.1.1.7.5` | `id-Deviation-NationalUse` | — |

La norme décrit le format mais ne prescrit pas le traitement par le lecteur : c'est à l'État récepteur de décider ce qu'il tolère.

### 1.2 Defect List du BSI (TR-03129-2 §7)

L'Allemagne définit un format voisin, la *Defect List* (DFL), qu'elle emploie entre systèmes d'inspection. Source : BSI TR-03129-2 v1.4.1, chapitre 7, <https://www.bsi.bund.de/SharedDocs/Downloads/EN/BSI/Publications/TechGuidelines/TG03129/BSI-TR-03129-2_V1_4_1.pdf> (vérifié).

- Un défaut est « une erreur de production qui touche un grand nombre de documents ». Il est **toujours identifié par le certificat DS** (émetteur et numéro de série, ou SKI, plus l'empreinte du certificat si ces deux moyens sont ambigus).
- Type de contenu `id-DefectList` (`0.4.0.127.0.7.3.1.5`) ; signataire avec l'usage étendu `id-electronicDefectListSigningKey`. Un système qui applique une DFL « SHALL » en vérifier la signature et l'ignorer sinon.
- Les catégories sont plus fines que celles de l'ICAO. Celles qui concernent ce que lit Sceau :

| Défaut BSI | Signification | Correspondance dans Sceau |
|---|---|---|
| `id-CertRevoked` | DS révoqué (équivaut à une CRL) | ligne « Chaîne » ou « Signature » |
| `id-CertReplaced` | DS mal formé, certificat de remplacement fourni | ligne « Signature » |
| `id-ChipAuthKeyRevoked`, `id-ActiveAuthKeyRevoked` | clés CA ou AA compromises | lignes CA, AA |
| `id-AuthenticationProtocolFailure` | CA, AA ou PACE-CAM connus pour échouer | lignes CA, AA |
| `id-ValidityPeriodIncorrect` | validité du DS ou du CSCA erronée | ligne « Validité du DS » |
| `id-ePassportDGMalformed` | DG mal encodés (liste) | lecture des DG |
| `id-SODInvalid` | SOD invalide (signature, encodage, empreintes) | lignes « Signature », « Empreintes » |
| `id-COMSODDiscrepancy` | listes de DG d'EF.COM et du SOD divergentes | DG annoncés |
| `id-sodWrongSignerIdentifier` | `SignerIdentifier` du SOD faux, valeur corrigée fournie | recherche du DS |
| `id-IssuingCountryDefect` | pays du DS absent ou faux (alpha-3 au lieu d'alpha-2…), valeur corrigée fournie | cohérence des pays (audit V3) |
| `id-CardSecurityMalformed` | EF.CardSecurity mal encodé, version corrigée fournie | PACE-CAM |

La DFL désigne les documents par leur DS, alors que la DL de l'ICAO peut les désigner par numéro et période. Les deux formats sont des sources de modèle pour le registre de Sceau : un critère « certificat DS » est disponible pour tout document dont la Passive Authentication aboutit.

### 1.3 Sources de déviations accessibles

| Source | Accès | Contenu utile |
|---|---|---|
| Collection « eMRTD DL » de l'ICAO PKD (version 10 au 2026-10-04), <https://pkddownload.icao.int/> | captcha et conditions d'utilisation qui interdisent la redistribution (« *except for your own personal/ organizational non-commercial use* ») | **non consultée** : l'accès demande un captcha, et le contenu ne pourrait pas être redistribué dans l'APK |
| DL italienne, <https://csca-ita.interno.gov.it/html/tddl.html> | libre | fiche [IT-1](#it-1-cie-30--dg12-erroné-346-275-cartes) |
| Page du CSCA du laissez-passer européen, <https://eu-csca.jrc.ec.europa.eu/> | libre (texte) | fiche [EU-1](#eu-1-laissez-passer--nationalité-deu) |
| Page du CSCA estonien, <https://pki.politsei.ee/> | libre | « No DLs available » (`docs/trust-sources.md` §2.4) |
| BSI TR-03127 v1.40, annexe D « Varianten » | libre | variantes des cartes allemandes, identifiées par le numéro de série du DS (§3.1) |

Aucune autre Deviation List nationale publique n'a été trouvée (recherche web, pages nationales déjà recensées dans `docs/trust-sources.md` §3).

## 2. Inventaire

### 2.1 Tableau

| Réf. | Émetteur | Non-conformité | Preuve | Classement |
|---|---|---|---|---|
| IT-1 | Italie, CIE 3.0 | empreinte de DG12 fausse (date de délivrance figée, mention « non valable pour l'expatriation » absente) | vérifié (DL signée, note de l'IPZS) | **codable**, dans le garde-fou DG11/DG12, avec une condition sur la ligne « Validité du DS » |
| CN-1 | Chine, passeports | type de contenu du SOD `id-data` au lieu de `id-icao-ldsSecurityObject` | rapporté (pymrtd) | **codable hors garde-fou** ; à n'envisager que sur un cas réel |
| FRBE-1 | France, Belgique, anciens passeports | type de contenu du SOD `1.3.27.1.1.1` | rapporté (JMRTD) | hors périmètre en pratique (documents expirés) |
| DE-1 | Allemagne, cartes d'identité délivrées avant le 2021-08-02 | application ICAO réservée aux systèmes d'inspection authentifiés (TA2) | vérifié (BSI TR-03127) | **traité, D36** : code `ACCESS_RESTRICTED` et message dédié (§3.1) |
| DE-2 | Allemagne, carte eID pour citoyens de l'Union (eID-UB) | DG1 de remplacement (« UB », champs vides), DG2 = logo | vérifié (BSI TR-03127) | **traité, D36** : bandeau « aucune donnée d'identité », photo masquée (§3.1) |
| EU-1 | UE, laissez-passer | nationalité « DEU » au lieu de « D<< » dans la MRZ | vérifié (page du CSCA UE) | déjà géré (sans effet) |
| GEN-1 | divers, non nommés | empreintes mal calculées, photos tronquées, MRZ de la puce différente de l'imprimé | rapporté (E. Poll, Radboud) | à documenter |
| GEN-2 | aucun cas public | EF.COM et SOD divergents (`COMInconsistent`) | type prévu par 9303-12 et TR-03129 | à documenter ; amélioration à étudier (§4.4) |
| UK-1, HU-1 | Royaume-Uni, Hongrie, titres de séjour | vérification impossible pour un éditeur commercial | rapporté (Inverid) | à documenter |
| BM-1 | Bermudes (UKKPA) | CSCA « BMU » certifié croisé avec le CSCA britannique | vérifié (Master List du BSI) | à vérifier sur un document réel |
| H-1 | Roumanie, Albanie, Royaume-Uni, Slovénie | attribut `C` des certificats en minuscules | vérifié (Master List du BSI) | déjà géré |
| H-2 | Hong Kong, Macao | CSCA `C=CN`, État émetteur « CHN » | vérifié (Master List du BSI) | déjà géré |
| H-3 | Portugal, Cameroun, Italie, Turquie, Luxembourg… | CSCA et liens au profil non conforme (pathLen, basicConstraints, keyUsage) | vérifié (code de Sceau), rapporté (pymrtd, ZeroPass) | déjà géré |
| H-4 | Moldavie, Chine, Tanzanie, Philippines, France | extension « liste des types de document » du DS mal encodée | rapporté (pymrtd) | hors périmètre |
| H-5 | France, passeports 2013-2014 | DG14 sans `ChipAuthenticationInfo` | rapporté (JMRTD) | déjà géré |
| H-6 | non nommés | AA sur clé EC sans `ActiveAuthenticationInfo` | vérifié (D15) | déjà géré |
| H-7 | non nommés | DG2 absent | vérifié (D14) | déjà géré |
| H-8 | France ou Belgique ; Bosnie | dates de DG11/DG12 en DCB (4 octets) ; date-heure en 7 octets | rapporté (JMRTD) | déjà géré |
| H-9 | Royaume-Uni (?) | EF.CardAccess sans adressage court (SFI) | rapporté (JMRTD) | déjà géré |
| H-10 | divers | noms X.500 codés en PrintableString ou UTF8String | rapporté (E. Poll) | probablement déjà géré, à confirmer par un test |
| H-11 | Chine (ticket) | DG14 absent, CA impossible | rapporté (passport-reader) | déjà géré |
| H-12 | Allemagne (Master List du 2019-09-25), Hongrie | Master List signée directement par le CSCA ; signature de ML invérifiable | rapporté (pymrtd, ZeroPass) | hors périmètre |

Total : 1 cas codable dans le garde-fou, 1 cas codable hors garde-fou, 2 cas traités hors registre (DE-1, DE-2, D36), 5 cas à documenter (GEN-1, GEN-2, UK-1, HU-1, BM-1), 13 cas déjà gérés ou hors périmètre (EU-1, FRBE-1, H-1 à H-12, H-10 restant à confirmer).

### 2.2 Fiches

#### IT-1 CIE 3.0 : DG12 erroné (346 275 cartes)

- **Émetteur** : Italie, ministère de l'Intérieur ; personnalisation par l'IPZS (*Istituto Poligrafico e Zecca dello Stato*).
- **Sources** (vérifié) :
  - page TDDL : <https://csca-ita.interno.gov.it/html/tddl.html> ;
  - DL signée : <https://csca-ita.interno.gov.it/certificatiCSCA/IT_CIE_DeviationList.zip> → `TDDL-CIE-Signed-20180530.der` ; signature CMS valide, `signingTime` 2018-05-30 11:02:37 UTC, signataire `CN=ITDeviationListSigner, serialNumber=001` émis par `CN=Italian Country Signer CA` (CSCA03, voir `docs/trust-sources.md` §2.4) ;
  - note de l'IPZS (italien) : <https://csca-ita.interno.gov.it/certificatiCSCA/CIE3.0-NotaAnomaliaDG12ITA.pdf> ; version anglaise annoncée sur la page TDDL (`CIE3.0-NotaAnomaliaDG12ENG.pdf`, non lue) ; liste PDF signée des numéros (`ItalianIdentityCardListof346.275cardswithwrongDG12-signed.pdf`, non lue).
- **Contenu de la DL** (décodé) : deux `Deviation`, chacune avec `documentType` = « C », `issuingDate` du 2017-10-01 00:00:00 au 2018-02-05 23:59:59,999 UTC, une liste `documentNumbers` et un type `id-Deviation-LDS-DGHashWrong` (`2.23.136.1.1.7.2.2`) de paramètre 12 (DG12). **Aucun `dscIdentifier`.**
  - 299 400 numéros : « *THE HASH OF THE DG12 INSIDE THE EFSOD DOES NOT CORRESPOND TO THE ONE COMPUTED READING THE DG12 FROM THE CHIP. ISSUANCE DATE INSIDE THE DG12 READ FROM THE CARD IS SET TO 05th DECEMBER 2015 FOR ALL THE DOCUMENTS.* »
  - 46 875 numéros : même texte, plus « *ENDORSMENTS/OBSERVATIONS DOES NOT CONTAIN THE STRING ‘NON VALIDA PER L’ESPATRIO’. THE DOCUMENTS CANNOT BE USED TO GO ABROAD* ».
  - Numéros au format `CA` + 5 chiffres + 2 lettres, de `CA00000AL` à `CA99999AQ`, tous distincts (346 275).
- **Note de l'IPZS** : DG12 d'une partie des CIE émises de **juin 2017 à février 2018** ; la date de délivrance vaut toujours « 20151205 » ; pour les cartes non valables pour l'expatriation, le champ 04 *Endorsement(s)/Observation(s)* manque. La note précise que les données imprimées et les autres structures de la puce sont conformes. La période de la note (juin 2017) est plus large que celle de la DL (octobre 2017) : c'est la DL, signée, qui fait foi.
- **Effet actuel dans Sceau** :
  - ligne « Empreintes » `FAILED` (DG12 lu et différent du SOD), donc verdict « Échec » ;
  - et, indépendamment, ligne « Validité du DS » : la date de délivrance est prise dans DG12 en priorité (`ReadingSession` passe `data.dg12?.dateOfIssue` à `PassiveAuthenticator.checkValidity`). La date fausse du 2015-12-05 tombe très probablement avant le début de validité d'un DS utilisé fin 2017 : la ligne serait alors `FAILED` elle aussi (déduction, non vérifiée sur une carte réelle).
- **Classement : codable, dans le garde-fou DG11/DG12.**
  - Critère sur éléments signés : DG1 vérifié par la Passive Authentication, avec État émetteur « ITA », code de document commençant par « C », numéro de document dans la liste de la DL ; en option, `signingTime` du SOD dans la période de la DL. La DL ne désigne pas de DS : le critère « certificat DS » n'est pas disponible sans carte réelle pour relever les DS concernés.
  - Effet : écart d'empreinte de DG12 toléré, DG12 écarté (ni affiché ni utilisé). **La date de délivrance de DG12 ne doit pas non plus servir à la ligne « Validité du DS »** : se rabattre sur le `signingTime` du SOD, comme si DG12 était absent. Sinon la tolérance laisse le verdict à « Échec ».
  - Coût : la liste brute fait 3,1 Mo (346 275 × 9 caractères), 834 Ko en gzip, 246 Ko en xz ; un champ de bits (19 suffixes de deux lettres × 100 000 numéros) fait 238 Ko avant compression. La DL signée elle-même pèse 620 Ko zippée : l'embarquer telle quelle et vérifier sa signature au chargement est une autre option.
  - Variante sans liste, à arbitrer : critère « ITA + code C + `signingTime` dans la période de la DL ». Elle tolère aussi les CIE de la période absentes de la liste. L'effet se limite à écarter DG12, donc rien de faux n'est affiché, mais un DG12 falsifié sur une vraie carte de la période ne serait plus signalé.
  - Limite à afficher : pour 46 875 de ces cartes, la puce ne dit pas que la carte n'est pas valable pour l'expatriation. Sceau ne peut pas distinguer ces cartes sans la liste. Le message qui accompagne la tolérance devrait renvoyer à la mention imprimée au verso.

#### CN-1 Passeports chinois : type de contenu du SOD `id-data`

- **Source** (rapporté) : pymrtd (ZeroPass), `src/pymrtd/ef/sod.py`, commentaire « *Some Chinese passports has this OID instead of id_mrtd_ldsSecurityObject* » (`1.2.840.113549.1.7.1`), <https://github.com/ZeroPass/pymrtd>. Aucun numéro de série ni période n'est donné.
- **Effet actuel dans Sceau** : `ParsedSod.parse` exige `id-icao-ldsSecurityObject` (audit V10, `PassiveAuthenticator.kt`) ; sinon `SOD_MALFORMED` et la Passive Authentication échoue.
- **Classement : codable hors garde-fou.** Critère possible : certificat DS (pays CN, émetteur et numéros de série relevés sur des documents réels). Effet : accepter `id-data` comme type de contenu pour ces DS seulement. Le risque est faible, car un DS ne signe que des SOD. Mais la règle touche la vérification du SOD entier, donc DG1, DG2, DG14 et DG15, et non un DG facultatif. À ne coder que sur un document réel et un DS identifié.

#### FRBE-1 Anciens passeports français et belges : type de contenu `1.3.27.1.1.1`

- **Source** (rapporté) : JMRTD 0.8.8, `org/jmrtd/lds/SODFile.java`, constante `ICAO_LDS_SOD_ALT_OID` : « *Seen in live French and Belgian MRTDs* » ; JMRTD accepte aussi un OID de test de l'imprimerie néerlandaise SDU (`2.16.528.1.1006.1.20.1`).
- **Effet actuel dans Sceau** : `SOD_MALFORMED`, comme CN-1.
- **Classement : hors périmètre en pratique.** Ces documents datent des premières générations : passeports électroniques français à partir de 2006, valables 10 ans, et passeports belges valables 5 à 7 ans. Ils sont tous expirés. Un document expiré n'est pas refusé par Sceau, mais ce cas ne justifie pas d'élargir la vérification du SOD.

#### DE-1, DE-2 Cartes allemandes

Voir §3.1.

#### EU-1 Laissez-passer : nationalité « DEU »

- **Source** (vérifié) : <https://eu-csca.jrc.ec.europa.eu/>. Les laissez-passer délivrés à des agents de nationalité allemande avant le 2022-05-05 portent « DEU » dans le champ nationalité de la MRZ ; ceux délivrés depuis portent « D<< », conformément à 9303-3.
- **Effet dans Sceau** : aucun. La nationalité n'entre dans aucun contrôle : la cohérence des pays (audit V3, `IcaoCountries`) porte sur l'**État émetteur** de DG1 (« EUE », code d'organisation : seuls le CSCA et le DS sont comparés, `C=EU`). « DEU » et « D » désignent tous deux l'Allemagne.
- **Classement : déjà géré.**

#### GEN-1 Défauts génériques sans pays nommé

- **Source** (rapporté) : E. Poll, *e-passports*, Radboud Universiteit, diapositive « *Technical things that go wrong: defects* », <https://www.cs.ru.nl/E.Poll/ufrj/C_ePassport.pdf> : « *Wrongly calculated hashes ; Encoding of certificate fields: printable string ↔ UTF8 ; Wrong identifiers in certificates ; Printed MRZ data not matching the one stored in the chip ; Truncated photos* ».
- **Classement : à documenter.** Sans document ni DS désigné, rien n'est codable. Une empreinte de DG1 ou DG2 fausse doit rester un échec : c'est précisément ce que la Passive Authentication doit signaler.

#### GEN-2 EF.COM et SOD divergents

- **Sources** (vérifié) : types `id-Deviation-LDS-COMInconsistent` (9303-12) et `id-COMSODDiscrepancy` (TR-03129-2 §7.3.2.3). Aucun cas public trouvé.
- **Effet actuel dans Sceau** : `DocumentReader` lit DG11, DG12, DG14 et DG15 s'ils sont annoncés dans EF.COM **ou** dans le SOD. Un DG annoncé par EF.COM seul et fourni par la puce compte comme non conforme (`checkHashes` : « Un DG lu mais absent du SOD »), donc « Échec ».
- **Classement : à documenter.** Voir §4.4 pour l'amélioration possible.

#### UK-1, HU-1 Titres de séjour britanniques et une partie des hongrois

- **Source** (rapporté) : Inverid, *ReadID Supported Identity Documents Summary*, version 2, 2023-07-14, §4, via <https://www.notaris.nl/files/Documenten/overzicht-readid-documenten-samenvatting.pdf> : « *ReadID can also verify all of them, except residence permits from UK, and part of Hungarian residence permits* ». La cause n'est pas donnée.
- **Effet attendu dans Sceau** : probablement « Émetteur inconnu » (CSCA absent du magasin), sans certitude.
- **Classement : à documenter** ; à confirmer sur un document réel.

#### BM-1 Bermudes : CSCA « BMU » certifié croisé

- **Source** (vérifié dans le magasin embarqué) : la Master List du BSI contient le CSCA auto-signé `CN=BMU CSCA, O=UKKPA, C=BM` (2024-01-17 → 2040-05-17) et deux certificats croisés avec `CN=Country Signing Authority, O=UKKPA, C=GB`.
- **Effet dans Sceau** : `CertificateChains` préfère à chaque niveau un CSCA auto-signé : un DS `C=BM` s'ancre sur le CSCA `C=BM`. La cohérence des pays exige alors un État émetteur « BMU » dans DG1. Or Inverid rapporte que depuis 2015 le Royaume-Uni délivre les passeports de ses territoires d'outre-mer avec l'État émetteur « GBR » (même source qu'UK-1, note 11). Si un passeport signé par un DS `C=BM` portait « GBR », Sceau conclurait à une incohérence de pays (« Échec »).
- **Classement : à vérifier sur un document réel.** Rien à coder tant qu'aucun cas n'est observé.

#### H-1 à H-12 Cas déjà gérés ou hors périmètre

| Réf. | Constat | Pourquoi Sceau n'est pas gêné |
|---|---|---|
| H-1 | 13 certificats de la Master List du BSI ont un attribut `C` en minuscules (`ro`, `al`, `gb`, `si`), dont deux avec seulement l'émetteur en minuscules (relevé sur le fichier embarqué) | `IcaoCountries.consistent` compare en majuscules |
| H-2 | CSCA de Hong Kong et de Macao en `C=CN` ; leurs passeports portent l'État émetteur « CHN » (relevé sur le fichier embarqué) | CHN → CN : cohérent |
| H-3 | CSCA sans `pathLenConstraint`, lien portugais à `pathLen` 2 (pymrtd, `pki/x509.py`) ; 7 liens en `cA=FALSE` (Cameroun, Italie, Portugal, Turquie ×2, Luxembourg ×2) | `pathLen` non contrôlé ; profil toléré si l'extension est absente, liens non conformes écartés sans effet (`CertificateChains`, audit V8) |
| H-4 | extension « liste des types de document » du DS mal encodée (pymrtd : DS moldave `02B27F8C79935F02`, passeports chinois, tanzaniens, philippins ; OID `2.23.136.1.1.4` au lieu de `2.23.136.1.1.6.2` sur un DS français) | Sceau ne lit pas cette extension |
| H-5 | DG14 sans `ChipAuthenticationInfo` (JMRTD, `EACCAProtocol.inferChipAuthenticationOIDfromPublicKeyOID` : « *This seems to work for French passports (generation 2013, 2014)* ») | `ChipVerifier` passe un OID nul ; JMRTD le déduit de la clé |
| H-6 | AA sur clé EC sans algorithme de hachage annoncé | D15 : SHA-1 à SHA-512 essayés |
| H-7 | DG2 absent | D14 : lecture sans photo |
| H-8 | dates de DG11/DG12 en DCB sur 4 octets (JMRTD, `AdditionalDetailDataGroup.readFullDate` : « *Either France or Belgium uses this encoding for dates* ») ; date-heure de personnalisation sur 7 octets (« *Bosnia apparently uses this encoding* ») | JMRTD rend la chaîne hexadécimale, soit 8 chiffres AAAAMMJJ, que `Dates.parsePast` lit ; la date-heure n'est pas utilisée |
| H-9 | EF.CardAccess sans SFI (JMRTD, `PassportService` : « *Some passports (UK?) don't support SFI for EF.CardAccess* ») | Sceau n'utilise jamais le SFI (`docs/protocol.md` §2.2) |
| H-10 | noms X.500 en PrintableString ou UTF8String selon le certificat | BouncyCastle (`X500Name`, style BC) et `X500Principal` comparent des formes canoniques ; aucun test ne le vérifie encore |
| H-11 | « Chip authentication failed » sur un passeport chinois : `6A82` sur EF.CardAccess et DG14 (tananaev/passport-reader, ticket 54, 2023-08-30, <https://github.com/tananaev/passport-reader/issues/54>) | pas de DG14 : ligne CA `NOT_AVAILABLE`, pas `FAILED` |
| H-12 | Master List allemande du 2019-09-25 signée directement par le CSCA (pymrtd) ; signature de la Master List hongroise invérifiable (ZeroPass, <https://github.com/ZeroPass/Port-documntation-and-tools/blob/master/eMRTD%20Non-Conformancy%20Report.md>) | ne concerne pas la lecture d'un document ; une Master List importée dont la signature est invalide est rejetée (SPEC §7.2) |

## 3. Limites par pays

### 3.1 Allemagne

Source principale (vérifié) : BSI TR-03127 *eID-Dokumente basierend auf Extended Access Control*, version 1.40 du 2021-10-06, <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/Publikationen/TechnischeRichtlinien/TR03127/BSI-TR-03127_1-40.pdf>. Pour comparaison, version 1.21 du 2018-05-02, <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/Publikationen/TechnischeRichtlinien/TR03127/BSI-TR-03127.pdf>.

Les cartes allemandes (carte d'identité *Personalausweis*, PA ; titre de séjour électronique, eAT ; carte eID pour citoyens de l'Union, eID-UB) portent trois applications (§3.4.1) :

- **application biométrique** (AID `A0 00 00 02 47 10 01`, l'application ICAO) ;
- **application eID** (AID `E8 07 04 00 7F 00 07 03 02`) ;
- **application de signature** (eSign).

#### Ce que l'application ICAO expose (tableau 1 de la v1.40)

| Fichier | Contenu | Accès |
|---|---|---|
| EF.COM | liste des DG (« son usage est déconseillé, car il n'est pas signé ») | BIS/EIS |
| EF.SOD | empreintes de DG1, DG2, DG3, certificat DS | IS ; BIS/EIS |
| EF.CVCA | points de confiance de la Terminal Authentication | BIS/EIS |
| DG1 | MRZ (eID-UB : MRZ de remplacement, voir plus bas) | IS ; BIS/EIS |
| DG2 | photo, identique à la photo imprimée (eID-UB : logo eID statique) | IS ; BIS/EIS |
| DG3 | deux empreintes digitales (obligatoires dans la PA depuis le 2021-08-02, facultatives avant) | IS + droit DG3 ; EIS + droit DG3 |
| DG14 | `ChipAuthenticationInfo`, `ChipAuthenticationPublicKeyInfo`, `TerminalAuthenticationInfo`, `PACEInfo` | BIS/EIS |

- BIS : système d'inspection de base, *Standard Inspection Procedure* (BAC ou PACE), avec CA1 s'il la gère. C'est le cas de Sceau.
- EIS : *Advanced Inspection Procedure* (BAC ou PACE, CA1, TA1). IS : *General Authentication Procedure* (PACE, TA2, CA2/CA3). TA1 et TA2 exigent un certificat de terminal délivré sous la CVCA du BSI.
- **DG4 à DG13, DG15 et DG16 ne sont pas présents** (§3.4.2). Donc ni DG11, ni DG12, ni DG15.
- **Pas d'Active Authentication** : « *Die Aktive Authentisierung nach [ICAO 9303], Part 11, wird aus Datenschutzgründen nicht implementiert* » (§3.3).
- EF.CardSecurity (MF) : lisible après PACE dans la v1.40 (tableau 3). La v1.21 exigeait PACE + TA2. Il contient `ChipAuthenticationPublicKeyInfo` et une signature DS. TR-03127 ne dit pas si PACE-CAM est proposé : non vérifié.
- Le tableau d'EF.SOD ne cite que les empreintes de DG1, DG2 et DG3, alors que DG14 est présent. Si DG14 était annoncé par EF.COM sans figurer dans le SOD, Sceau le lirait et conclurait à « Échec » (GEN-2). C'est probablement une simplification du tableau, puisque 9303 exige l'empreinte de chaque DG présent ; à vérifier sur une carte réelle.

#### Le point décisif : la date de délivrance de la carte d'identité

- Note 7 du §3.4.2, et note 16 du §4.2 : l'accès par *Standard/Advanced Inspection Procedure* existe « *nicht für vor dem 02.08.2021 ausgegebene Personalausweise* ».
- Annexe D : « *Zugriff auf die Biometrieanwendung des Personalausweises nur mit General Authentication Procedure* » pour les PA dont le **numéro de série du DS est ≤ 198**, délivrés jusqu'au 2021-08-01. La v1.21 (2018) réservait déjà toutes les données de la PA à la *General Authentication Procedure* (§3.1.2 et §3.2).
- Rapporté, et concordant : Signicat (Inverid), 2025-11-17, <https://www.signicat.com/blog/which-european-countries-have-identity-cards-with-nfc> : « *Germany started to issue ICAO compliant identity cards since August 2021 […] Before that, the identity cards already had an NFC chip, but not an ICAO 9303 compliant one.* »
- Ce calendrier suit le règlement (UE) 2019/1157, applicable à partir du 2021-08-02 (art. 16), qui impose une puce sans contact conforme à 9303 avec photo et empreintes (art. 3), <https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32019R1157>.

#### Ce que Sceau peut lire et vérifier

| Document | Accès possible | Données affichables | Vérification |
|---|---|---|---|
| **PA délivrée depuis le 2021-08-02** | PACE avec le CAN (ou la MRZ à trois lignes), puis EF.COM, EF.SOD, DG1, DG2, DG14 | MRZ de DG1 (nom, prénoms, numéro, nationalité, date de naissance, sexe, expiration) et photo | PA contre le CSCA allemand (présent dans la Master List du BSI), puis CA1 via DG14 : verdict attendu « Authentique ». Pas de DG12, donc date de délivrance tirée du `signingTime` du SOD ou estimée (PA valable 6 ans avant 24 ans, 10 ans ensuite : une estimation hors période donne `NOT_AVAILABLE`, jamais `FAILED`, D15) |
| **PA délivrée avant le 2021-08-02** | PACE avec le CAN aboutit, mais l'application ICAO et EF.SOD exigent TA2 | **aucune donnée** | aucune. Réponse attendue `6982` (*security status not satisfied*) à la sélection ou à la lecture d'EF.SOD (non vérifié sur une carte réelle). **Traité, D36** : code `ACCESS_RESTRICTED-<étape>-<élément>` et message « puce réservée aux autorités habilitées », sans « Réessayer » ; auparavant « Erreur inattendue » (`UNEXPECTED-…-6982`) |
| **eAT** (titre de séjour) | BAC (seulement pour les titres délivrés avant le 2019-11-01, note 6 du §3.3) ou PACE ; EF.COM, EF.SOD, DG1, DG2, DG14 | MRZ et photo | PA puis CA1 : « Authentique » attendu. Les mentions du titre (*Nebenbestimmungen*) sont dans l'application eID (DG19, DG20), hors d'atteinte |
| **eID-UB** (depuis le 2020-11-01) | comme l'eAT | DG1 : code de document « UB », État « D », « < » dans tous les autres champs ; DG2 : logo eID | la signature peut être valide, mais le document ne porte **aucune donnée d'identité** dans l'application ICAO. **Traité, D36** : MRZ vide lue sans erreur (vérifié sur la puce simulée), « Validité du DS » non évaluable faute de date ; l'écran Résultat affiche un bandeau « aucune donnée d'identité » au lieu des champs vides, et masque le logo de DG2 |
| **Passeport** | BAC ou PACE ; DG1, DG2, DG14 ; DG3 sous EAC | MRZ et photo | PA puis CA via DG14. Rapporté et non vérifié dans une source primaire pour le passeport : pas d'AA, pas de DG11/DG12 |

#### Ce qui reste hors d'atteinte

- **DG3** (empreintes) : TA1 ou TA2 avec un certificat d'inspection étatique. Sceau ne lit jamais DG3 (SPEC §1).
- **Application eID** (tableau 2 de la v1.40) : lieu de naissance (DG9), adresse (DG17), nom de naissance (DG13), doctorat (DG7), nom d'artiste ou de religion (DG6), mentions du titre de séjour (DG19, DG20)… Sa lecture exige PACE avec le PIN eID (ou le CAN avec le droit *CAN allowed* pour une lecture sur place), puis TA2 avec un **certificat d'autorisation** (*Berechtigungszertifikat*) délivré sous la CVCA du BSI, puis CA2/CA3. Ses DG ne sont pas signés par le DS : seules les empreintes de DG4, DG5, DG8 et DG9 figurent dans EF.ChipSecurity, lui-même réservé aux terminaux privilégiés. Un logiciel libre et hors ligne ne peut pas obtenir ce certificat : ces données sont hors périmètre.
- **Variantes de l'annexe D** (PrivilegedTerminalInfo absent, eIDSecurityInfo absent, DG10 ou DG13 absents, *power down* exigé entre deux authentifications) : elles concernent l'application eID ou TA2, sans effet sur ce que lit Sceau.

### 3.2 Autres cas notables

- **Cartes d'identité de l'UE délivrées avant le 2021-08-02** : elles peuvent ne pas avoir de puce, ou une puce sans application ICAO. Inverid relevait en juin 2023 des cartes sans puce en Bulgarie, en Grèce, au Portugal, au Liechtenstein et en Suisse (rapporté, même source qu'UK-1, tableau 2). Le règlement 2019/1157 (art. 5) met fin à la validité de ces cartes à leur expiration, au plus tard le 2031-08-03, ou le 2026-08-03 pour celles sans MRZ fonctionnelle (vérifié). Dans Sceau : pas de détection NFC, ou `NOT_ICAO`.
- **DG3 et DG4** : protégés par l'EAC dans tous les documents qui les portent ; jamais lus (SPEC §1, `docs/protocol.md` §2.4).
- **Titres de séjour britanniques et une partie des hongrois** : vérification impossible selon Inverid (UK-1, HU-1).
- **Laissez-passer européen** : la nationalité « DEU » sur les titres antérieurs au 2022-05-05 est sans effet (EU-1).

## 4. Recommandations pour Sceau

### 4.1 Cas codables, par priorité

| Priorité | Cas | Critère (éléments signés) | Effet proposé | Garde-fou |
|---|---|---|---|---|
| 1 | IT-1, CIE 3.0 | Passive Authentication aboutie (chaîne, signature, empreintes de DG1/DG2/DG14/DG15) ; DG1 : État « ITA », code « C… », numéro dans la liste de la DL ; en option `signingTime` du SOD dans [2017-10-01, 2018-02-05] | écart d'empreinte de DG12 toléré, DG12 écarté ; **date de délivrance de DG12 ignorée pour la ligne « Validité du DS »** ; message qui renvoie à la mention « non valable pour l'expatriation » imprimée | dans le garde-fou DG11/DG12 |
| 2 | DE-1, carte d'identité allemande antérieure au 2021-08-02 | après un PACE réussi, `6982` à la sélection de l'application ICAO ou à la lecture d'EF.SOD | message dédié (« document qui réserve sa puce aux autorités », voir §4.3) au lieu de « Erreur inattendue » ; **traité, D36**, avec un nouveau code `ACCESS_RESTRICTED` | hors registre : aucune tolérance, seulement un message |
| 3 | DE-2, eID-UB | DG1 vérifié : code « UB », État « D » | bandeau « carte sans données d'identité dans la puce » ; aucun champ vide affiché comme une donnée ; **traité, D36** (photo masquée) | hors registre : affichage seulement |
| 4 | CN-1, SOD en `id-data` | certificat DS précis relevé sur un document réel | accepter ce type de contenu pour ce DS seulement | **hors garde-fou** : la règle porte sur le SOD entier, donc sur DG1 et DG2. À ne coder que sur preuve |

Pour la structure du registre : identifier chaque règle par l'OID 9303-12 de la déviation (`2.23.136.1.1.7.2.2` et le paramètre 12 pour IT-1). La règle reste alors rattachable à une DL signée si Sceau en importe un jour. Le critère « certificat DS » (TR-03129) est préférable dès qu'il est connu : il est signé par le CSCA, court, et ne demande pas de liste de numéros.

### 4.2 Risques à garder en tête

- Toute règle qui toucherait DG1, DG2, DG14, DG15 ou la signature du SOD permettrait à un faussaire qui réunit le critère de faire passer des données non signées. Le critère serait alors la seule protection.
- Une règle par numéro de document n'est sûre que si DG1 est lui-même vérifié (empreinte de DG1 correcte dans un SOD valide) : le numéro est lu dans DG1, jamais dans la clé saisie.
- Une tolérance qui écarte un DG ne doit laisser aucune trace de ce DG ailleurs : affichage, date de délivrance (IT-1), calcul du verdict.

### 4.3 Texte proposé pour signaler la limite allemande

À reprendre dans le README (section des documents pris en charge) et dans l'écran À propos (chaîne à ajouter dans `strings_about.xml` et ses traductions). Ces fichiers ne sont pas modifiés par ce document.

> **Cartes d'identité allemandes.** Sceau lit les cartes d'identité allemandes délivrées depuis le 2 août 2021 : il affiche les données de la bande MRZ et la photo, puis vérifie la signature et la puce. Les cartes délivrées avant cette date réservent leur puce aux autorités habilitées : Sceau ne peut pas les lire. L'adresse et le lieu de naissance ne sont jamais lisibles : ils sont réservés à la fonction d'identification en ligne, qui exige le code PIN de la carte et un certificat délivré par l'État allemand.

Version anglaise :

> **German identity cards.** Sceau reads German identity cards issued since 2 August 2021: it shows the MRZ data and the photo, then verifies the signature and the chip. Cards issued before that date reserve their chip to authorised authorities, so Sceau cannot read them. The address and place of birth can never be read: they are reserved to the online identification function, which requires the card's PIN and a certificate issued by the German state.

### 4.4 Autres pistes, hors registre

- **DG annoncés** (GEN-2) : ne demander DG11, DG12, DG14 et DG15 que s'ils figurent dans le SOD (signé), et non dans EF.COM (non signé, et déconseillé par le BSI). Un DG présent seulement dans EF.COM ne pourrait de toute façon pas être vérifié. À étudier : ce choix modifie `DocumentReader` et le contrôle V1 des DG manquants.
- **Test de non-régression H-10** : un SOD dont le `SignerIdentifier` code l'émetteur en UTF8String alors que le certificat DS le code en PrintableString, à fabriquer avec `:testchip`.
- **À observer sur des documents réels** avant tout code : carte d'identité allemande récente (DG14 dans le SOD, PACE-CAM ou non), eID-UB (comportement sur une MRZ vide), passeport des Bermudes (État émetteur et pays du DS).
