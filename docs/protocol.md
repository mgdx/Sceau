# Protocole de lecture et de vérification

Ce document détaille la séquence ICAO 9303 suivie par `:core` (SPEC §6), les règles qui l'encadrent, la correspondance avec les étapes affichées et les erreurs, et le calcul du verdict. Il décrit le comportement fixé par la SPEC et par les contrats de `core/src/main/kotlin/` ; les points dépendant d'une implémentation encore en cours sont signalés « à confirmer ».

Références :

- **ICAO Doc 9303, partie 10** : *Logical Data Structure (LDS) for Storage of Biometrics and Other Data in the Contactless Integrated Circuit* : structure des fichiers (EF.COM, EF.SOD, EF.CardAccess, groupes de données).
- **ICAO Doc 9303, partie 11** : *Security Mechanisms for MRTDs* : BAC, PACE, Passive Authentication, Chip Authentication, Active Authentication, messagerie sécurisée.
- **ICAO Doc 9303, partie 12** : *Public Key Infrastructure for MRTDs* : CSCA, DS, certificats de lien, Master Lists.

## 1. Point d'entrée

```kotlin
suspend fun readAndVerify(
    transport: CardTransport,
    key: AccessKey,
    trustStore: TrustStore,
    progress: (Step) -> Unit,
): VerificationReport
```

- `transport` : canal APDU brut (`transceive`, `maxTransceiveLength`, `timeoutMillis`, `close`). `:app` l'implémente au-dessus d'`IsoDep` ; les tests utilisent un transport simulé qui rejoue des échanges APDU enregistrés.
- `key` : `AccessKey.Can` (6 chiffres) ou `AccessKey.Mrz` (numéro de document sans les `<` de remplissage, date de naissance, date d'expiration).
- `trustStore` : magasin de confiance fusionné (CSCA ANTS, Master List allemande du BSI embarquée, Master Lists importées).
- `progress` : appelé **au début** de chaque étape (voir §4).

La fonction est bloquante côté E/S et s'exécute sur `Dispatchers.IO`. Elle lève une `SceauException` pour toute erreur qui **interrompt** la lecture ; tout échec de **vérification** est au contraire consigné dans le rapport, qui est toujours produit dès que les données ont été lues.

## 2. Séquence (SPEC §6.1)

### 2.1 Sélection de l'application ICAO

- Commande `SELECT` par nom d'application, AID `A0 00 00 02 47 10 01` (application LDS1 eMRTD, partie 10).
- Échec : `SceauException.NotIcaoDocument` (code `NOT_ICAO`). Aucune autre tentative (SPEC §4).

### 2.2 Canal sécurisé : PACE ou BAC

- Lecture de **EF.CardAccess** (FID `01 1C`, fichier du MF, partie 10). S'il est présent et contient des `PACEInfo`, PACE est annoncé.
- **PACE** (partie 11, *Password Authenticated Connection Establishment*) est exécuté avec la clé fournie : CAN pour `AccessKey.Can`, clé dérivée de la MRZ pour `AccessKey.Mrz`. Le mapping (générique, intégré) et les paramètres de domaine sont ceux annoncés par la puce dans EF.CardAccess, jamais codés en dur.
- **BAC** (partie 11, *Basic Access Control*) est utilisé si PACE n'est pas annoncé, avec la clé MRZ (numéro de document, date de naissance, date d'expiration et leurs chiffres de contrôle).
- **CAN sans PACE** : si la clé est un CAN et que la puce n'annonce pas PACE, la lecture s'arrête avec `SceauException.CanWithoutPace` (code `CAN_WITHOUT_PACE`) : BAC ne sait pas utiliser un CAN.
- Échec de l'authentification (mauvais CAN ou MRZ) : `SceauException.AccessDenied` (code `ACCESS_DENIED`).
- **Repli PACE → BAC** (clé MRZ seulement) : si PACE échoue sans refus explicite de la clé (SW `63xx`) ni délai dépassé, c'est-à-dire SW inattendu, erreur de JMRTD ou perte de liaison, la liaison est réinitialisée (`CardTransport.reconnect`), l'applet sélectionnée en clair, puis BAC est mené.
- **Délai d'authentification** : pendant PACE et BAC, le délai de réponse du transport est porté à 60 s (`SecureChannel.AUTHENTICATION_TIMEOUT_MILLIS`), puis rétabli (10 s par défaut) pour la lecture. Après des essais ratés, certaines puces imposent un délai croissant avant de répondre à la première commande d'authentification (contre-mesure anti-force brute, observée sur un passeport français muet au premier GENERAL AUTHENTICATE de PACE comme à l'EXTERNAL AUTHENTICATE de BAC, sur deux téléphones) : couper plus tôt ne la laisse jamais répondre et chaque essai interrompu peut aggraver la pénalité. Un délai dépassé pendant PACE arrête donc la lecture (`TIMEOUT-SECURE_CHANNEL-INS86-…`), sans repli sur BAC. Côté app, `IsoDepTransport` relit `IsoDep.getTimeout()` après chaque affectation et classe les échecs d'après le délai effectivement retenu par le système ; l'écran de lecture prévient l'utilisateur si l'étape dure plus de 5 s.
- Toutes les commandes suivantes passent par la messagerie sécurisée (*Secure Messaging*, partie 11) établie par PACE ou BAC.

> Note ICAO : EF.CardAccess est un fichier du MF, et PACE s'exécute normalement avant la sélection de l'application eMRTD, qui est alors resélectionnée sous messagerie sécurisée. La SPEC énumère les étapes dans l'ordre logique (reconnaître un document ICAO, puis ouvrir le canal) ; l'ordre exact des APDU est celui de l'implémentation de la lecture (à confirmer après sa fusion). Le résultat observable est le même : `NotIcaoDocument` si l'application ICAO est absente.

### 2.3 EF.COM puis EF.SOD

- **EF.COM** (FID `01 1E`, tag `60`) : version de la LDS et liste des groupes de données présents.
- **EF.SOD** (FID `01 1D`, tag `77`) : *Document Security Object*, structure CMS `SignedData` signée par le Document Signer (DS). Il contient l'algorithme d'empreinte, l'empreinte de chaque DG présent (`LDSSecurityObject`) et, en général, le certificat DS.

### 2.4 Groupes de données

| DG | FID | Tag | Contenu | Lecture |
|---|---|---|---|---|
| DG1 | `01 01` | `61` | MRZ | toujours |
| DG2 | `01 02` | `75` | Portrait (JPEG ou JPEG 2000, ISO/IEC 19794-5 ou 39794-5) | toujours |
| DG3 | `01 03` | `63` | Empreintes digitales | **jamais** |
| DG4 | `01 04` | `76` | Iris | **jamais** |
| DG11 | `01 0B` | `6B` | Données personnelles complémentaires | si annoncé |
| DG12 | `01 0C` | `6C` | Données complémentaires du document (autorité, date de délivrance, images) | si annoncé |
| DG14 | `01 0E` | `6E` | Informations de sécurité (Chip Authentication, algorithme AA) | si annoncé |
| DG15 | `01 0F` | `6F` | Clé publique d'Active Authentication | si annoncé |

Ordre de lecture : DG1, DG2, puis DG14, DG15, DG11, DG12 s'ils sont annoncés dans EF.COM **ou** dans le SOD. DG3 et DG4 sont protégés par Terminal Authentication (Extended Access Control), qui exige des certificats délivrés par les États : ils sont hors périmètre (SPEC §1) et ne sont jamais demandés.

Les octets bruts de chaque DG lu sont conservés dans `DocumentData.rawDataGroups` pour le recalcul des empreintes, puis remis à zéro par `wipe()` (voir `docs/architecture.md`).

### 2.5 Passive Authentication (partie 11 et partie 12)

Réalisée par `PassiveAuthentication.verify(sod, dataGroups, trustStore, dateOfIssue, dateOfExpiry, documentCode)`, sans accès à la puce. Elle produit quatre lignes de la liste de contrôle et les métadonnées de chaîne (`ChainInfo`).

1. **Certificat DS** : extrait du SOD. S'il en est absent, il est recherché dans le magasin par émetteur et numéro de série. Introuvable : ligne `CERTIFICATE_CHAIN` avec le détail `CheckDetail.DsCertificateMissing` (ce n'est pas une exception : le rapport est produit).
2. **Chaîne de certification** (`CERTIFICATE_CHAIN`) : construction et validation de la chaîne DS → certificats de lien éventuels → CSCA du magasin, en cherchant l'émetteur par sujet (`findBySubject`) et par identifiant de clé (`findBySubjectKeyId`). Les certificats de lien traversés sont listés dans `ChainInfo.linkCertificates`. Si aucun CSCA du magasin ne correspond, la ligne vaut `NOT_AVAILABLE` (décision D6) : c'est le cas « Émetteur inconnu ».
3. **Signature du SOD** (`SOD_SIGNATURE`) : vérification de la signature CMS avec la clé publique du DS, selon l'algorithme déclaré dans le SOD.
4. **Empreintes des DG** (`DG_HASHES`) : recalcul de l'empreinte de chaque DG lu avec l'algorithme déclaré dans le SOD et comparaison. Le détail `DataGroupHashes` liste les DG contrôlés et ceux dont l'empreinte diffère.
5. **Validité du DS** (`DS_VALIDITY`) : la période de validité du DS doit couvrir la date de délivrance du document, prise dans cet ordre :
   1. date de délivrance de DG12 (`IssuanceDateSource.DG12`) ;
   2. à défaut, attribut `signingTime` du SOD (`SOD_SIGNING_TIME`) ;
   3. à défaut, date d'expiration de DG1 moins la durée de validité usuelle du type de document (`ESTIMATED`), affichée « estimée ».

   Sur une date **estimée**, la ligne n'est jamais `FAILED` : elle vaut `NOT_AVAILABLE` (décision D6), car la durée usuelle peut être fausse (passeport de mineur valable 5 ans au lieu de 10). Les durées usuelles par code de document et le statut d'une date estimée qui tombe dans la période du DS relèvent de l'implémentation de la vérification (à confirmer après sa fusion).

Les listes de révocation (CRL) ne sont pas consultées en v1 : elles nécessitent le réseau (SPEC §7.4).

### 2.6 Chip Authentication (partie 11)

- Exécutée si DG14 est présent et annonce Chip Authentication (`ChipAuthenticationInfo`, `ChipAuthenticationPublicKeyInfo`).
- Accord de clé (DH ou ECDH) entre une clé éphémère du terminal et la clé statique de la puce publiée dans DG14 ; la messagerie sécurisée est alors renouvelée avec les clés dérivées, et toutes les commandes suivantes passent par ce nouveau canal.
- Une puce clonée ne possède pas la clé privée : le canal renouvelé ne peut pas s'établir. Comme DG14 est couvert par le SOD, sa clé publique est elle-même authentifiée par la Passive Authentication.
- Résultat : ligne `CHIP_AUTHENTICATION` (`OK`, `FAILED`, `UNSUPPORTED_ALGORITHM`, ou `NOT_AVAILABLE` sans DG14).

### 2.7 Active Authentication (partie 11)

- Exécutée si DG15 est présent.
- Nonce de **8 octets tiré d'un `SecureRandom`**, envoyé par `INTERNAL AUTHENTICATE`.
- La réponse est vérifiée par `ActiveAuthentication.verifyResponse(publicKey, digestAlgorithm, challenge, response)` avec la clé publique de DG15 :
  - clé RSA : signature ISO/IEC 9796-2 schéma 1 ;
  - clé EC : ECDSA, avec l'algorithme de hachage annoncé dans DG14 (`ActiveAuthenticationInfo`).
- Résultat : ligne `ACTIVE_AUTHENTICATION` (`OK`, `FAILED`, `UNSUPPORTED_ALGORITHM`, ou `NOT_AVAILABLE` sans DG15).

### 2.8 Rapport

`VerificationReport` immuable : verdict, exactement une `Check` par `CheckId` dans l'ordre de l'énumération, `ChainInfo` (sans donnée personnelle) et `DocumentData` (données lues). Son `toString()` ne révèle que le verdict. Il n'est jamais persisté ; il est sérialisable pour les tests uniquement (SPEC §6.2).

## 3. Règles (SPEC §6.2)

- **Aucun algorithme codé en dur.** Signature et courbe sont celles que déclarent les certificats et le SOD : RSA PKCS#1 v1.5, RSA-PSS, ECDSA sur toute courbe connue de BouncyCastle, et tout algorithme que BouncyCastle ajoutera. Un algorithme inconnu donne le statut `UNSUPPORTED_ALGORITHM` avec le détail `CheckDetail.UnsupportedAlgorithm(algorithm)` (nom ou OID), jamais un échec silencieux.
- **Non disponible n'est pas échoué.** Une étape qui n'a pas lieu (DG14 ou DG15 absent, étape non atteinte) vaut `NOT_AVAILABLE`, distinct de `FAILED`.
- **Aucune donnée personnelle** dans les exceptions, les logs ou les identifiants d'erreur. Les classes de `model/` ne sont pas des `data class` et leur `toString()` est masqué ; `AccessKey` affiche `***`.
- **Aucune référence Android** dans le rapport ni dans `:core`.

## 4. Étapes affichées : correspondance avec `Step`

`progress(step)` est appelé au début de chaque étape ; les étapes précédentes sont alors terminées, et le retour de `readAndVerify` marque la fin de la dernière.

| `Step` | Libellé (SPEC §5.2) | Opérations (§2 ci-dessus) |
|---|---|---|
| `CONNECT` | Connexion à la puce | 2.1 sélection de l'application ICAO |
| `SECURE_CHANNEL` | Ouverture du canal sécurisé (PACE ou BAC) | 2.2 EF.CardAccess, PACE ou BAC |
| `READ_DATA` | Lecture des données | 2.3 EF.COM, EF.SOD ; 2.4 groupes de données |
| `VERIFY_SIGNATURE` | Vérification de la signature | 2.5 Passive Authentication |
| `VERIFY_CHIP` | Vérification de la puce | 2.6 Chip Authentication, 2.7 Active Authentication |

## 5. Erreurs : correspondance avec `SceauException`

Seules ces erreurs interrompent la lecture ; l'écran de lecture affiche un message en français et un bouton « Réessayer » (SPEC §5.2).

| Exception | `code` | Cause | Message de l'écran de lecture |
|---|---|---|---|
| `NotIcaoDocument` | `NOT_ICAO` | pas d'application ICAO | Document sans application ICAO |
| `AccessDenied` | `ACCESS_DENIED` | échec de PACE ou de BAC | CAN ou MRZ incorrects |
| `CanWithoutPace` | `CAN_WITHOUT_PACE` | CAN fourni, PACE non annoncé | Erreur explicite : utiliser la MRZ |
| `ConnectionLost` | `CONNECTION_LOST` | document retiré (`TagLostException` ou équivalent) | Document retiré trop tôt |
| `Timeout` | `TIMEOUT` | délai dépassé | Délai dépassé |
| `Unexpected` | `UNEXPECTED-<id>` | toute autre erreur ; `<id>` est un court identifiant technique (classe d'exception, mot d'état SW) | Erreur inattendue, avec le code affiché en petit |

Les codes sont stables et ne contiennent aucune donnée personnelle. Les situations suivantes **ne sont pas** des exceptions et figurent dans le rapport : certificat DS introuvable (`CheckDetail.DsCertificateMissing`), algorithme non pris en charge (`UNSUPPORTED_ALGORITHM`), signature ou empreinte invalide, challenge CA ou AA raté (`FAILED`), émetteur inconnu (`NOT_AVAILABLE`).

## 6. Verdict

Calculé par `Verdicts.compute(checks)` à partir des sept lignes de la liste de contrôle (SPEC §5.3, décision D6). Les règles s'appliquent **dans l'ordre**, la première qui s'applique l'emporte :

| # | Condition | Verdict |
|---|---|---|
| 1 | au moins une ligne `FAILED` ou `UNSUPPORTED_ALGORITHM` | **Échec** (`FAILED`) |
| 2 | sinon, `CERTIFICATE_CHAIN` vaut `NOT_AVAILABLE` | **Émetteur inconnu** (`UNKNOWN_ISSUER`) |
| 3 | sinon, PA réussie et (`CHIP_AUTHENTICATION` ou `ACTIVE_AUTHENTICATION` vaut `OK`) | **Authentique** (`AUTHENTIC`) |
| 4 | sinon, PA réussie, `CHIP_AUTHENTICATION` et `ACTIVE_AUTHENTICATION` valent `NOT_AVAILABLE` | **Signature valide, puce non vérifiée** (`SIGNATURE_VALID_CHIP_UNVERIFIED`) |
| 5 | tout autre cas | **Échec** (`FAILED`) |

« PA réussie » signifie : `SOD_SIGNATURE`, `CERTIFICATE_CHAIN` et `DG_HASHES` valent `OK`. `DS_VALIDITY` peut valoir `NOT_AVAILABLE` quand la date de délivrance est estimée (décision D6) sans empêcher la PA de réussir ; le traitement exact de ce cas dans `Verdicts.compute` est à confirmer après fusion de l'implémentation de la vérification.

Conséquences :

- un challenge raté l'emporte sur tout le reste : un CA réussi et un AA raté donnent « Échec » ;
- un émetteur absent du magasin donne « Émetteur inconnu » même si la signature du SOD et les empreintes sont cohérentes : rien n'est garanti, les données sont affichées ;
- un document expiré n'affecte pas le verdict : l'écran de résultat affiche un bandeau « Document expiré » (SPEC §5.3).
