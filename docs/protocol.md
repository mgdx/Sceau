# Protocole de lecture et de vérification

Ce document détaille la séquence ICAO 9303 suivie par `:core` (SPEC §6), les règles qui l'encadrent, la correspondance avec les étapes affichées et les erreurs, et le calcul du verdict. Il décrit le comportement du code de `core/src/main/kotlin/` (paquets `reading/` et `verify/`) ; les écarts à la SPEC sont justifiés dans `docs/decisions.md`.

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

- `transport` : canal APDU brut (`transceive`, `maxTransceiveLength`, `timeoutMillis`, `reconnect`, `close`). `:app` l'implémente au-dessus d'`IsoDep` (`IsoDepTransport`) ; les tests utilisent un transport qui rejoue des échanges APDU enregistrés (`ScriptedTransport`) ou la puce simulée de `:testchip` (`SimulatedChip`).
- `key` : `AccessKey.Can` (6 chiffres) ou `AccessKey.Mrz` (numéro de document sans les `<` de remplissage, date de naissance, date d'expiration).
- `trustStore` : magasin de confiance fusionné (CSCA ANTS, Master List allemande du BSI embarquée, Master Lists importées).
- `progress` : appelé **au début** de chaque étape (voir §4).

La fonction est bloquante côté E/S et s'exécute sur `Dispatchers.IO`. Elle lève une `SceauException` pour toute erreur qui **interrompt** la lecture ; tout échec de **vérification** est au contraire consigné dans le rapport, qui est toujours produit dès que les données ont été lues. Si la lecture échoue, les données déjà lues sont remises à zéro.

Avant chaque lecture, les journaux `java.util.logging` de JMRTD et de SCUBA sont coupés (`LibraryLogging`) : ils écrivent des APDU en clair (décision D14).

## 2. Séquence (SPEC §6.1)

L'ordre réel des échanges suit ICAO 9303 partie 11 : EF.CardAccess est un fichier du MF, lu **avant** toute sélection d'application, et PACE s'exécute au niveau du MF, avant la sélection de l'application eMRTD. La SPEC énumère les étapes dans l'ordre logique (reconnaître un document ICAO, puis ouvrir le canal) ; le résultat observable est le même (`NotIcaoDocument` si l'application ICAO est absente). Mise en œuvre : `SecureChannel` (`core/src/main/kotlin/…/reading/`).

Séquence complète (décision D20) :

1. EF.CardAccess, puis PACE ou BAC (§2.1, §2.2) ; avec PACE-CAM, EF.CardSecurity au MF (§2.6.1) ;
2. EF.COM, EF.SOD (§2.3), puis DG14 s'il est annoncé dans EF.COM ou dans le SOD ;
3. Chip Authentication si DG14 annonce une clé (§2.6), sauf après PACE-CAM (la puce s'est déjà authentifiée) ;
4. DG1, DG2, puis DG15, DG11, DG12 et DG7 s'ils sont annoncés (§2.4), sous la messagerie sécurisée de la Chip Authentication si elle a réussi ;
5. Passive Authentication (§2.5), qui vérifie aussi l'empreinte de DG14 ;
6. Active Authentication si DG15 est présent (§2.7) ;
7. contrôle du déclassement : protocole mené comparé aux `PACEInfo` de DG14, signé (§2.2, audit V20).

La Chip Authentication précède la lecture des données, et non la Passive Authentication comme l'énumère SPEC §6.1 : ainsi DG1, DG2, DG7, DG11, DG12 et DG15 sont lus sous des clés de session que seule la puce qui détient la clé privée de DG14 peut dériver, ce qui les lie à cette puce (ICAO 9303-11 §6.2, procédure d'inspection).

### 2.1 EF.CardAccess, puis sélection de l'application ICAO

1. Lecture de **EF.CardAccess** (FID `01 1C`, fichier du MF, partie 10), en clair. S'il est présent et contient des `PACEInfo`, PACE est annoncé. Absent ou illisible : pas de PACE.
2. **Sans PACE**, l'application ICAO est sélectionnée en clair aussitôt : commande `SELECT` par nom d'application, AID `A0 00 00 02 47 10 01` (application LDS1 eMRTD, partie 10).
3. **Avec PACE**, la sélection a lieu après PACE, sous messagerie sécurisée (§2.2).

Réponse `6A82` (fichier introuvable) à la sélection : `SceauException.NotIcaoDocument` (code `NOT_ICAO`). Aucune autre tentative (SPEC §4). Tout autre échec de la sélection donne une erreur technique (`UNEXPECTED-<étape>-SELECT_APPLET-…`).

### 2.2 Canal sécurisé : PACE ou BAC

- **PACE** (partie 11, *Password Authenticated Connection Establishment*) est exécuté avec la clé fournie : CAN pour `AccessKey.Can`, clé dérivée de la MRZ pour `AccessKey.Mrz`. Chaque `PACEInfo` annoncé est essayé dans l'ordre, ceux de PACE-CAM en premier (audit V20) ; le mapping et les paramètres de domaine sont ceux qu'annonce la puce, jamais codés en dur. Les paramètres de domaine propriétaires (`PACEDomainParameterInfo`) ne sont pas pris en charge : le `PACEInfo` correspondant est sauté. PACE réussi : sélection de l'application ICAO sous messagerie sécurisée. Si PACE a abouti avec le mapping **PACE-CAM**, EF.CardSecurity est lu au MF sous la nouvelle messagerie sécurisée, avant cette sélection (§2.6.1).
- **BAC** (partie 11, *Basic Access Control*) est utilisé si PACE n'est pas annoncé, avec la clé MRZ (numéro de document, date de naissance, date d'expiration et leurs chiffres de contrôle).
- **CAN sans PACE** : si la clé est un CAN et que la puce n'annonce pas PACE, la lecture s'arrête avec `SceauException.CanWithoutPace` (code `CAN_WITHOUT_PACE`) : BAC ne sait pas utiliser un CAN.
- **Clé refusée** : un SW `63xx` pendant PACE (ICAO 9303-11, BSI TR-03110) ou l'échec de BAC donnent `SceauException.AccessDenied` (code `ACCESS_DENIED`), sans autre essai.
- **Repli PACE → BAC** (clé MRZ seulement, décision D13) : si PACE échoue sans refus explicite de la clé ni délai dépassé, c'est-à-dire sur un SW inattendu, une erreur de JMRTD ou une perte de liaison, la liaison est réinitialisée (`CardTransport.reconnect()` : `IsoDep.close()` puis `connect()`), l'application ICAO est sélectionnée en clair, puis BAC est mené. Les documents qui annoncent PACE restent tenus d'accepter BAC pendant la transition ICAO. Avec un CAN, aucun repli : une erreur de transport ressort telle quelle, un échec de PACE donne `ACCESS_DENIED`. Une reconnexion impossible donne `CONNECTION_LOST-SECURE_CHANNEL-RECONNECT`.
- **Délai d'authentification** (décision D12) : pendant PACE et BAC, le délai de réponse du transport est porté à 60 s (`SecureChannel.AUTHENTICATION_TIMEOUT_MILLIS`), puis rétabli à sa valeur précédente (10 s par défaut, `IsoDepTransport.DEFAULT_TIMEOUT_MILLIS`) pour la lecture. Après des essais ratés, une puce peut imposer un délai croissant avant de répondre à la première commande d'authentification (contre-mesure anti-force brute) : un passeport français réel, muet quand le délai était de 5 s (GENERAL AUTHENTICATE de PACE) puis de 10 s (EXTERNAL AUTHENTICATE de BAC) sur Fairphone 3 et Fairphone 5, a mis 23,3 s à répondre au premier GENERAL AUTHENTICATE de PACE après plusieurs tentatives ratées. Couper plus tôt ne laisse jamais la puce répondre, et chaque essai interrompu peut aggraver la pénalité. Un délai dépassé pendant PACE arrête donc la lecture (`TIMEOUT-SECURE_CHANNEL-INS86-…`), **sans** repli sur BAC. Côté app, `IsoDepTransport` relit `IsoDep.getTimeout()` après chaque affectation et classe les échecs d'après le délai effectivement retenu par le système ; l'écran de lecture affiche un message d'attente si l'étape « Ouverture du canal sécurisé » dure plus de 5 s (`SlowChipHint`).
- Toutes les commandes suivantes passent par la messagerie sécurisée (*Secure Messaging*, partie 11) établie par PACE ou BAC ; le MAC des réponses est toujours vérifié.
- **Déclassement** (audit V20, décision D40) : EF.CardAccess n'est pas signé, un clone peut y retirer PACE-CAM ou PACE. À l'étape `VERIFY_CHIP`, si l'empreinte de DG14 est vérifiée et que ni la CA via DG14 ni l'AA n'ont réussi, ses `PACEInfo` sont comparés au canal mené (`ProtocolDowngrade`) : PACE-CAM annoncé mais canal sans CAM → `CHIP_AUTHENTICATION` `FAILED` (`VERIFY_CHIP-DOWNGRADE-CAM`) ; PACE annoncé mais canal BAC → `SECURE_CHANNEL` `FAILED` (`VERIFY_CHIP-DOWNGRADE-BAC`).

#### Paramètres de transport

- **APDU courtes uniquement** : la longueur maximale transmise à JMRTD est celle du transport (`IsoDep.getMaxTransceiveLength()`), plafonnée à 256 (`Chip`) ; aucune APDU étendue n'est émise (décision D14).
- Lecture des fichiers par `SELECT` puis `READ BINARY`, par blocs de 223 octets ; l'adressage court (SFI) n'est pas utilisé.
- Côté app, `IsoDepTransport` lit en mode lecteur NFC (`NfcAdapter.enableReaderMode`, NFC-A et NFC-B, sans vérification NDEF), avec des tests de présence espacés de 2 s pour ne pas perturber une puce lente pendant PACE.

### 2.3 EF.COM puis EF.SOD

- **EF.COM** (FID `01 1E`, tag `60`) : version de la LDS et liste des groupes de données présents. Facultatif : s'il manque ou est illisible, la liste des DG vient du SOD. Ses longueurs TLV internes sont contrôlées contre la taille réelle du fichier avant JMRTD (`BerStructure`) : un EF.COM dont un élément déborde de son contenant est illisible, sans rien allouer (JMRTD alloue sinon la longueur annoncée, 2 Go compris).
- **EF.SOD** (FID `01 1D`, tag `77`) : *Document Security Object*, structure CMS `SignedData` signée par le Document Signer (DS). Il contient l'algorithme d'empreinte, l'empreinte de chaque DG présent (`LDSSecurityObject`) et, en général, le certificat DS. Obligatoire : un échec de lecture donne `UNEXPECTED-READ_DATA-SOD-…`.

### 2.4 Groupes de données

| DG | FID | Tag | Contenu | Lecture |
|---|---|---|---|---|
| DG1 | `01 01` | `61` | MRZ | toujours (obligatoire) |
| DG2 | `01 02` | `75` | Portrait (JPEG ou JPEG 2000, ISO/IEC 19794-5 ou 39794-5) | toujours demandé ; absent toléré |
| DG3 | `01 03` | `63` | Empreintes digitales | **jamais** |
| DG4 | `01 04` | `76` | Iris | **jamais** |
| DG7 | `01 07` | `67` | Signature manuscrite du titulaire (image JPEG ou JPEG 2000, décision D37) | si annoncé |
| DG11 | `01 0B` | `6B` | Données personnelles complémentaires | si annoncé |
| DG12 | `01 0C` | `6C` | Données complémentaires du document (autorité, date de délivrance, images) | si annoncé |
| DG14 | `01 0E` | `6E` | Informations de sécurité (Chip Authentication, algorithme AA) | si annoncé |
| DG15 | `01 0F` | `6F` | Clé publique d'Active Authentication | si annoncé |

Ordre de lecture (décision D20) : DG14 d'abord, s'il est annoncé dans EF.COM **ou** dans le SOD, puis la Chip Authentication (§2.6), puis DG1, DG2, et DG15, DG11, DG12, DG7 s'ils sont annoncés. DG3 et DG4 sont protégés par Terminal Authentication (Extended Access Control), qui exige des certificats délivrés par les États : ils sont hors périmètre (SPEC §1) et ne sont jamais demandés (`DocumentReader` ne demande que DG14, `SECURITY_DATA_GROUP`, puis DG1, DG2 et `OPTIONAL_DATA_GROUPS` = 15, 11, 12, 7 ; les tests de bout en bout échouent si la puce simulée reçoit une demande de DG3 ou DG4). Les autres DG (DG5, DG6, DG8 à DG10, DG13, DG16) ne sont pas demandés non plus.

Un échec de lecture de DG1 donne `UNEXPECTED-READ_DATA-DG1-…`. Un DG facultatif (DG2 compris) absent ou illisible est simplement omis : une puce sans DG2 est lue sans photo (décision D14). Avant JMRTD, les longueurs TLV de DG7, DG11, DG12 et DG2 sont contrôlées contre la taille réelle du DG (`BerStructure`, qui lit les en-têtes comme SCUBA), ainsi que, dans DG2, la longueur de chaque bloc d'image ISO 19794-5 : un DG dont une longueur interne déborde est illisible, sans allocation démesurée (le rattrapage d'`OutOfMemoryError` de `DataGroupParsers` ne reste qu'un filet). DG1 n'est pas concerné (JMRTD refuse déjà une longueur de MRZ anormale). Une erreur de transport (document retiré, délai) interrompt toujours la lecture.

Les octets bruts de chaque DG lu sont conservés dans `DocumentData.rawDataGroups` pour le recalcul des empreintes, puis remis à zéro par `wipe()` (voir `docs/architecture.md`).

### 2.5 Passive Authentication (partie 11 et partie 12)

Réalisée par `PassiveAuthentication.verify(sod, dataGroups, trustStore, dateOfIssue, dateOfExpiry, documentCode)`, sans accès à la puce. Elle produit quatre lignes de la liste de contrôle et les métadonnées de chaîne (`ChainInfo`).

1. **Certificat DS** : extrait du SOD (certificat qui correspond au `SignerIdentifier`). S'il en est absent, il est recherché dans le magasin : par (émetteur, numéro de série) parmi les ancres, ou par identifiant de clé (SKI) si le `SignerIdentifier` est de cette forme (décision D15). Introuvable : lignes `SOD_SIGNATURE` et `CERTIFICATE_CHAIN` à `NOT_AVAILABLE` avec le détail `CheckDetail.DsCertificateMissing`, `DS_VALIDITY` à `NOT_AVAILABLE`, d'où le verdict « Émetteur inconnu » (ce n'est pas une exception : le rapport est produit) ; mais si le magasin contient des certificats du pays émetteur de DG1, `CERTIFICATE_CHAIN` vaut `FAILED` (`NoChainForKnownCountry`, voir l'étape 2).
2. **Chaîne de certification** (`CERTIFICATE_CHAIN`) : construction et validation de la chaîne DS → certificats de lien éventuels → CSCA auto-signé du magasin (`CertificateChains`). Les émetteurs candidats sont cherchés par Authority Key Identifier (`findBySubjectKeyId`), puis par nom (`findBySubject`) en écartant un candidat de même nom dont le SKI diffère de l'AKI ; 8 liens au plus. Seules les signatures comptent : la période de validité des CSCA et des liens n'est pas vérifiée, un document restant valide jusqu'à dix ans après l'expiration de son CSCA (décision D15). Les certificats de lien traversés sont listés dans `ChainInfo.linkCertificates`. Résultat : `OK` (chaîne complète), `FAILED` (un émetteur du magasin correspond mais la signature est fausse), `UNSUPPORTED_ALGORITHM`, ou `NOT_AVAILABLE` si aucun certificat du magasin ne correspond (décision D6) : c'est le cas « Émetteur inconnu ». **Pays connu** (audit V25, décision D40) : si le magasin contient au moins un certificat (CSCA ou lien) du pays émetteur de DG1, l'absence de chaîne (DS introuvable, ou DS sous un CSCA inconnu) donne `FAILED` avec le détail `CheckDetail.NoChainForKnownCountry(pays, chaîne tentée)`, donc « Échec » : pour un pays dont des CSCA sont connus, une chaîne absente désigne plus probablement une contrefaçon qu'un CSCA manquant. « Émetteur inconnu » reste réservé aux pays absents du magasin, aux codes d'organisation (`UNO`, `EUE`…) et aux codes inconnus d'ICAO.
3. **Signature du SOD** (`SOD_SIGNATURE`) : vérification de la signature CMS avec la clé publique du DS, selon l'algorithme déclaré dans le SOD. Le vérifieur est construit sur la clé seule, pour qu'un DS expiré à la date de signature relève de la ligne `DS_VALIDITY` et non de celle-ci. Sans chaîne (`CERTIFICATE_CHAIN` `NOT_AVAILABLE` ou `NoChainForKnownCountry`), une signature que le DS vérifie ne prouve rien : la ligne vaut `NOT_AVAILABLE`, et non `OK` (audit V25).
4. **Empreintes des DG** (`DG_HASHES`) : recalcul de l'empreinte de chaque DG lu avec l'algorithme déclaré dans le SOD et comparaison. Un DG lu mais absent du SOD compte comme non conforme. Le détail `DataGroupHashes` liste les DG contrôlés et ceux dont l'empreinte diffère. Un DG que le SOD, signé, annonce parmi ceux que Sceau lit (1, 2, 7, 11, 12, 14, 15 : `PassiveAuthenticator.READ_DATA_GROUPS`) mais que la puce ne fournit pas est listé dans `missing` et met la ligne en échec (audit V1 : un clone ne doit pas pouvoir retenir un DG signé ; DG7 depuis la décision D37).

   **Anomalies connues des émetteurs** (décision D34, registre `KnownDeviations`) : un écart d'empreinte sur DG11 ou DG12, et seulement sur eux, est toléré s'il correspond à une anomalie publiée par l'émetteur (Deviation List). La recherche n'a lieu que si `SOD_SIGNATURE` et `CERTIFICATE_CHAIN` valent `OK`, que l'empreinte de DG1 est vérifiée et qu'aucun DG signé ne manque ; le critère porte sur des éléments signés (pays du CSCA, DG1, signingTime du SOD). Le DG toléré quitte `mismatched` pour `deviations` (`KnownDeviation`, ex. `IT-CIE3-DG12`), la ligne vaut `OK` s'il ne reste pas d'autre écart, et le DG est écarté (`PassiveAuthResult.discardedDataGroups`) : retiré de `DocumentData`, octets et images remis à zéro, date de délivrance de DG12 ignorée à l'étape 5. Seul cas à ce jour : CIE 3.0 italiennes à DG12 erroné (`docs/trust-sources.md` §2.4).
5. **Validité du DS** (`DS_VALIDITY`) : la période de validité du DS doit couvrir la date de délivrance du document, prise dans cet ordre :
   1. date de délivrance de DG12 (`IssuanceDateSource.DG12`) ;
   2. à défaut, attribut `signingTime` du SOD (`SOD_SIGNING_TIME`) ;
   3. à défaut, date d'expiration de DG1 moins la durée de validité usuelle, fixée à 10 ans pour tous les documents (`ESTIMATED`), affichée « estimée ».

   Date dans la période du DS (bornes comprises, en UTC) : `OK`, quelle que soit sa source. Hors de la période : `FAILED` pour une date de DG12 ou du SOD ; `NOT_AVAILABLE` pour une date **estimée**, jamais `FAILED` (décisions D6 et D15), car la durée usuelle peut être fausse (passeport de mineur valable 5 ans au lieu de 10). Aucune date disponible : `NOT_AVAILABLE`.

Les listes de révocation (CRL) ne sont pas consultées en v1 : elles nécessitent le réseau (SPEC §7.4).

### 2.6 Chip Authentication (partie 11)

- Exécutée si DG14 est présent et annonce Chip Authentication (`ChipAuthenticationInfo`, `ChipAuthenticationPublicKeyInfo`), **après la lecture de DG14 et avant celle des autres DG** (décision D20), pendant l'étape `READ_DATA`.
- Accord de clé (DH ou ECDH) entre une clé éphémère du terminal et la clé statique de la puce publiée dans DG14 ; la messagerie sécurisée est alors renouvelée avec les clés dérivées, et toutes les commandes suivantes (lecture de DG1, DG2, DG15, DG11, DG12, DG7, puis AA) passent par ce nouveau canal.
- Une puce clonée ne possède pas la clé privée : le canal renouvelé ne peut pas s'établir. L'échange seul ne prouve rien tant que la puce n'a pas répondu sous le nouveau canal : une confirmation explicite (SELECT de DG1 puis READ BINARY d'un octet, MAC des réponses vérifié) le vérifie avant toute lecture de données. Elle est gardée, bien que la lecture de DG1 qui suit passe elle aussi par le nouveau canal, pour que la ligne CA soit juste : un échec de la confirmation est un échec de la CA (et le signal qu'il faut rétablir le canal), non une erreur de lecture de DG1 qui interromprait toute la lecture.
- Comme DG14 est couvert par le SOD, sa clé publique est elle-même authentifiée par la Passive Authentication : un DG14 falsifié met la ligne des empreintes en échec.
- **CA en échec** : la ligne `CHIP_AUTHENTICATION` vaut `FAILED` (donc le verdict « Échec »), et la lecture continue pour afficher les données :
  - si la puce refuse l'échange (SW d'erreur au MSE ou au GENERAL AUTHENTICATE), JMRTD garde l'ancienne messagerie ; la même confirmation vérifie que la puce y répond encore, et les DG sont lus sous l'ancien canal ;
  - si la puce ne répond plus sous la messagerie courante (confirmation ratée après le changement de clés, ou puce qui a clos la session), la liaison est réinitialisée (`CardTransport.reconnect()`), un état JMRTD neuf est créé, puis le canal est rétabli avec la même clé, dans la même lecture (EF.CardAccess, PACE ou BAC, repli PACE → BAC compris : `SecureChannel.reestablish`), et les DG sont lus sous ce canal. La clé n'est pas conservée au-delà de la lecture. Une erreur à ce rétablissement, quelle qu'elle soit (reconnexion impossible, clé refusée, délai, document retiré, `6982` de l'accès réservé de D36), n'interrompt pas la lecture par une erreur : la CA ayant déjà échoué, un rapport est produit avec `CHIP_AUTHENTICATION` `FAILED`, donc le verdict « Échec », sans aucune donnée d'identité (DG1 vide, `VerificationReport.identityRead` faux : l'écran Résultat n'affiche ni identité ni photo) ; les autres lignes, non réalisées, valent `NOT_AVAILABLE` avec le code `READ_DATA-REESTABLISH-<code de l'erreur>` (ex. `READ_DATA-REESTABLISH-ACCESS_RESTRICTED-SELECT_APPLET`). Une puce hostile ne peut ainsi pas remplacer l'échec de la CA par le message de l'accès réservé (audit V24, décision D40).
- Résultat : ligne `CHIP_AUTHENTICATION` (`OK` avec le détail `CheckDetail.ChipAuthentication(DG14)`, `FAILED`, `UNSUPPORTED_ALGORITHM`, ou `NOT_AVAILABLE` sans DG14 ou avec un DG14 qui n'annonce aucune clé). La présence d'une clé est lue dans les octets bruts de DG14 (OID `id-PK-*`), et non dans ce qu'en a compris JMRTD : une clé annoncée mais inexploitable (structure que JMRTD écarte, courbe inconnue) donne `UNSUPPORTED_ALGORITHM` (audit V19, décision D40). Les codes d'erreur de la CA commencent par `READ_DATA-CA` (`READ_DATA-CA-DG14Missing`, `READ_DATA-CA_CONFIRM-…`).
- Si PACE a abouti avec le mapping CAM, la puce s'est déjà authentifiée pendant PACE : la CA via DG14 n'est pas menée (§2.6.1). DG14, s'il est annoncé, est lu quand même et son empreinte vérifiée.

#### 2.6.1 PACE-CAM, *Chip Authentication Mapping* (partie 11 §4.4 ; décision D21)

Avec le mapping CAM (`id-PACE-ECDH-CAM-AES-CBC-CMAC-128/192/256`), la puce prouve pendant PACE qu'elle détient la clé privée statique de Chip Authentication : sa dernière réponse contient, en plus du jeton d'authentification, `A_IC = E(KSenc, CA_IC)` (tag `8A`), avec `CA_IC = SK_IC⁻¹ · SK_Map,IC`. Toutes les commandes suivantes passent par le canal de PACE : les données lues sont liées à la puce authentifiée, sans autre échange.

1. **Pendant l'étape `SECURE_CHANNEL`** : JMRTD mène PACE, vérifie le jeton de la puce et déchiffre `CA_IC` (IV = `E(KSenc, −1)`, `PACECAMResult`) ; Sceau garde `CA_IC` et la clé publique de mapping de la puce `PK_Map,IC`, puis lit **EF.CardSecurity** (FID `01 1D` au MF, sous la messagerie de PACE, avant la sélection de l'application ; cache distinct de celui d'EF.SOD, de même FID dans l'application). D'après le code de JMRTD 0.8.8 (cas non couvert par un test), une puce qui annonce CAM mais n'envoie pas `A_IC` fait échouer PACE dans JMRTD : repli sur BAC avec une clé MRZ (la CA via DG14 est alors menée), `ACCESS_DENIED` avec un CAN.
2. **À l'étape `VERIFY_CHIP`**, hors ligne, une fois connu l'État émetteur de DG1 (`ChipAuthenticationMapping`) :
   - EF.CardSecurity (CMS `SignedData`, contenu `id-SecurityObject` = `0.4.0.127.0.7.3.2.1`, un seul signataire) est vérifié avec **les mêmes règles que le SOD** (`PassiveAuthenticator.verifyCardSecurity`) : certificat DS embarqué ou cherché dans le magasin, algorithmes faibles refusés (audit V9), signature CMS, chaîne DS → CSCA avec profil d'émetteur et keyUsage du DS (V8), pays cohérents avec DG1 (V3). La validité calendaire du DS n'y est pas contrôlée : elle l'est pour le DS du SOD (§2.5) ;
   - la clé `PK_IC` est prise dans les `ChipAuthenticationPublicKeyInfo` du contenu signé ;
   - contrôle `PK_Map,IC = KA(CA_IC, PK_IC, D_IC)` : `CA_IC · PK_IC` en ECDH, `PK_IC^CA_IC mod p` en DH, sur les paramètres de domaine de `PK_IC`, qui doivent être ceux de `PK_Map,IC`, avec `CA_IC` dans `[1, n − 1]`. L'algorithme vient des clés ; une clé ni EC ni DH donne `UNSUPPORTED_ALGORITHM`.
3. **Résultat**, ligne `CHIP_AUTHENTICATION` (pas de ligne propre) :

| Situation | Statut | Détail |
|---|---|---|
| tout est vérifié | `OK` | `ChipAuthentication(PACE_CAM)` |
| EF.CardSecurity absent ou illisible | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_MISSING` |
| EF.CardSecurity mal formé (type de contenu, signataires) | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_MALFORMED` |
| signature d'EF.CardSecurity fausse | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_SIGNATURE` |
| chaîne fausse, DS sans digitalSignature, budget dépassé | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_CHAIN` |
| pays incohérents | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_COUNTRY` |
| chaîne valide, mais vers un autre CSCA que celle du SOD (ni même certificat, ni même clé ; audit V17, décision D40) | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_ANCHOR` |
| aucun CSCA connu pour EF.CardSecurity, alors que la chaîne du SOD aboutit | `FAILED` | `VERIFY_CHIP-CAM-CARD_SECURITY_UNKNOWN_ISSUER` (ou `…_DS_MISSING`) |
| aucun CSCA connu, ni pour EF.CardSecurity ni pour le SOD | `NOT_AVAILABLE` | `ChipAuthentication(PACE_CAM)` ; verdict « Émetteur inconnu » |
| pas de `ChipAuthenticationPublicKeyInfo` | `FAILED` | `VERIFY_CHIP-CAM-NO_CHIP_KEY` |
| `CA_IC` ou `PK_Map,IC` absent (déchiffrement raté) | `FAILED` | `VERIFY_CHIP-CAM-NO_CHIP_AUTHENTICATION_DATA`, `…-NO_MAPPING_KEY` |
| contrôle KA faux | `FAILED` | `VERIFY_CHIP-CAM-KEY_MISMATCH` |
| algorithme inconnu (signature, chaîne, type de clé) | `UNSUPPORTED_ALGORITHM` | `UnsupportedAlgorithm` |

Le verdict se calcule comme pour la CA via DG14 (§6). La CA via DG14 n'est pas menée, même si CAM échoue : la ligne est alors déjà en échec, et le verdict « Échec ».

### 2.7 Active Authentication (partie 11)

- Exécutée si DG15 est présent.
- Nonce de **8 octets tiré d'un `SecureRandom`**, envoyé par `INTERNAL AUTHENTICATE`.
- La réponse est vérifiée par `ActiveAuthentication.verifyResponse(publicKey, digestAlgorithm, challenge, response)` avec la clé publique de DG15 :
  - clé RSA : signature ISO/IEC 9796-2 schéma 1, fonction de hachage désignée par le trailer (implicite `BC` : SHA-1 ; explicite : SHA-1 à SHA-512, RIPEMD, Whirlpool) ;
  - clé EC : ECDSA (réponse brute `r‖s`), avec l'algorithme de hachage annoncé dans DG14 (`ActiveAuthenticationInfo`). Si DG14 n'en annonce pas (cas hors norme rencontré en pratique), SHA-1, SHA-224, SHA-256, SHA-384 et SHA-512 sont essayés dans cet ordre (décision D15).
- Une perte de liaison pendant CA ou AA interrompt la lecture ; toute autre erreur de la puce est consignée dans la ligne correspondante.
- Résultat : ligne `ACTIVE_AUTHENTICATION` (`OK`, `FAILED`, `UNSUPPORTED_ALGORITHM`, ou `NOT_AVAILABLE` sans DG15).

### 2.8 Rapport

`VerificationReport` immuable : verdict, exactement une `Check` par `CheckId` dans l'ordre de l'énumération, `ChainInfo` du SOD et, avec PACE-CAM, celui d'EF.CardSecurity (`cardSecurityChain`, sans donnée personnelle ; l'écran signale un CSCA importé sur l'une ou l'autre chaîne, audit V17), et `DocumentData` (données lues). Son `toString()` ne révèle que le verdict. Il n'est jamais persisté. SPEC §6.2 le permet sérialisable pour les tests ; aucun test n'en a eu besoin, et il n'implémente aucune sérialisation.

## 3. Règles (SPEC §6.2)

- **Aucun algorithme codé en dur.** Signature et courbe sont celles que déclarent les certificats et le SOD : RSA PKCS#1 v1.5, RSA-PSS, ECDSA sur toute courbe connue de BouncyCastle, et tout algorithme que BouncyCastle ajoutera. Un algorithme inconnu donne le statut `UNSUPPORTED_ALGORITHM` avec le détail `CheckDetail.UnsupportedAlgorithm(algorithm)` (nom ou OID), jamais un échec silencieux.
- **Non disponible n'est pas échoué.** Une étape qui n'a pas lieu (DG14 ou DG15 absent, étape non atteinte) vaut `NOT_AVAILABLE`, distinct de `FAILED`.
- **Aucune donnée personnelle** dans les exceptions, les logs ou les identifiants d'erreur. Les classes de `model/` ne sont pas des `data class` et leur `toString()` est masqué ; `AccessKey` affiche `***`.
- **Aucune référence Android** dans le rapport ni dans `:core`.

## 4. Étapes affichées : correspondance avec `Step`

`progress(step)` est appelé au début de chaque étape ; les étapes précédentes sont alors terminées, et le retour de `readAndVerify` marque la fin de la dernière.

| `Step` | Libellé (SPEC §5.2) | Opérations (§2 ci-dessus) |
|---|---|---|
| `CONNECT` | Connexion à la puce | 2.1 lecture de EF.CardAccess ; sélection de l'application ICAO en clair si PACE n'est pas annoncé |
| `SECURE_CHANNEL` | Ouverture du canal sécurisé (PACE ou BAC) | 2.2 PACE puis sélection de l'application sous messagerie sécurisée, ou BAC (repli compris) |
| `READ_DATA` | Lecture des données | 2.3 EF.COM, EF.SOD ; DG14 ; 2.6 Chip Authentication ; 2.4 autres groupes de données |
| `VERIFY_SIGNATURE` | Vérification de la signature | 2.5 Passive Authentication |
| `VERIFY_CHIP` | Vérification de la puce | 2.6.1 vérification hors ligne de PACE-CAM ; 2.7 Active Authentication (la CA via DG14 est déjà faite, D20) |

## 5. Erreurs : correspondance avec `SceauException`

Seules ces erreurs interrompent la lecture ; l'écran de lecture affiche un message en français, le **code complet en petit, pour toutes les erreurs** (décision D11), et un bouton « Réessayer » (SPEC §5.2), sauf pour `ACCESS_RESTRICTED`, où un nouvel essai ne changerait rien (décision D36 : seul « Annuler » est proposé, `ReadingErrors.canRetry`). Le message est choisi sur le code de base, avant le premier `-` (`ReadingErrors.messageFor`).

| Exception | `code` | Cause | Message de l'écran de lecture |
|---|---|---|---|
| `NotIcaoDocument` | `NOT_ICAO` | pas d'application ICAO (`6A82` à la sélection) | Document non conforme ICAO 9303 |
| `AccessDenied` | `ACCESS_DENIED` | clé refusée par PACE (SW `63xx`) ou échec de BAC ; avec un CAN, tout échec de PACE autre qu'une erreur de transport | CAN ou MRZ incorrects, en rappelant qu'un essai raté peut allonger le délai de la puce |
| `CanWithoutPace` | `CAN_WITHOUT_PACE` | CAN fourni, PACE non annoncé | Utiliser l'onglet Passeport et la MRZ |
| `AccessRestricted` | `ACCESS_RESTRICTED-<ÉTAPE>-<élément>` | canal sécurisé établi (PACE ou BAC réussi), puis SW `6982` (*security status not satisfied*) à la sélection de l'application ICAO sous messagerie sécurisée (`SELECT_APPLET`) ou à la lecture d'EF.SOD (`SOD`) ou de DG1 (`DG1`) : puce réservée aux terminaux étatiques (Terminal Authentication), comme les cartes d'identité allemandes délivrées avant le 2021-08-02 (décision D36). Un `6982` avant l'authentification (sélection en clair avant BAC) garde le code `UNEXPECTED-…-6982` ; sur EF.COM, DG2, DG7, DG11, DG12, DG14 ou DG15, le fichier est traité comme absent. Au rétablissement du canal après une CA ratée, ce n'est plus une erreur : rapport « Échec » (§2.6, audit V24) | Puce réservée aux autorités habilitées, un nouvel essai n'y changera rien (cas des cartes allemandes d'avant août 2021), sans bouton « Réessayer » |
| `ConnectionLost` | `CONNECTION_LOST[-<diag>]` | document retiré (`TagLostException`, liaison coupée, « Tag is out of date »), lecture annulée, reconnexion impossible | Document retiré trop tôt |
| `Timeout` | `TIMEOUT[-<diag>]` | pas de réponse dans le délai (échec survenu après 90 % du délai effectif, ou message de délai d'`IsoDep`) | Délai dépassé |
| `Unexpected` | `UNEXPECTED-<diag>` | toute autre erreur | Erreur inattendue |

Les codes sont stables et ne contiennent aucune donnée personnelle : ni donnée lue, ni clé, ni octet d'APDU, ni message d'exception brut. Le diagnostic `<diag>` se compose ainsi :

| Origine | Forme | Exemple |
|---|---|---|
| Délai ou perte de liaison pendant une APDU (`IsoDepTransport`) | `<ÉTAPE>-INS<xx>-L<n>` : étape (`Step`), octet INS de la commande en hexadécimal, longueur de l'APDU arrondie à la dizaine | `TIMEOUT-SECURE_CHANNEL-INS86-L10`, `CONNECTION_LOST-READ_DATA-INSB0-L20` |
| Reconnexion du repli PACE → BAC impossible | `<ÉTAPE>-RECONNECT` | `CONNECTION_LOST-SECURE_CHANNEL-RECONNECT` |
| Erreur d'E/S NFC ni délai ni perte | `<ÉTAPE>-IO-<code>-INS<xx>-L<n>`, `<code>` pris dans une liste fermée de messages d'`android.nfc` : `TRANSCEIVE_FAILED`, `TOO_LONG`, `SERVICE_DIED`, sinon `OTHER` ou `NO_MESSAGE` | `UNEXPECTED-SECURE_CHANNEL-IO-TRANSCEIVE_FAILED-INS86-L10` |
| Transport fermé sans demande | `<ÉTAPE>-STATE-INS<xx>-L<n>` | `UNEXPECTED-READ_DATA-STATE-INSB0-L20` |
| Échec dans `:core` (`technicalCode`) | `<ÉTAPE>[-<étiquette>]-<classe>[-<SW>]` : étiquette `SELECT_APPLET`, `SOD` ou `DG1`, nom simple de la classe d'exception, mot d'état en hexadécimal | `UNEXPECTED-CONNECT-SELECT_APPLET-CardServiceException-6D00` |
| Erreur hors `readAndVerify` dans l'app (`SessionViewModel`) | `<classe>` | `UNEXPECTED-OutOfMemoryError` |
| Accès réservé (D36) | `<ÉTAPE>-<élément>` : élément refusé `SELECT_APPLET`, `SOD` ou `DG1` | `ACCESS_RESTRICTED-SECURE_CHANNEL-SELECT_APPLET`, `ACCESS_RESTRICTED-READ_DATA-SOD` |

Un `CONNECTION_LOST` ou un `TIMEOUT` sans diagnostic reste possible (transport de test, fermeture avant toute APDU). Aucune exception de JMRTD n'est attachée comme cause à une `SceauException` : leurs messages contiennent des APDU en hexadécimal (décision D14).

Les situations suivantes **ne sont pas** des exceptions et figurent dans le rapport : certificat DS introuvable (`CheckDetail.DsCertificateMissing`), algorithme non pris en charge (`UNSUPPORTED_ALGORITHM`), signature ou empreinte invalide, challenge CA ou AA raté (`FAILED`), émetteur inconnu (`NOT_AVAILABLE`).

## 6. Verdict

Calculé par `Verdicts.compute(checks)` à partir des sept lignes de la liste de contrôle (SPEC §5.3, décision D6). Les règles s'appliquent **dans l'ordre**, la première qui s'applique l'emporte :

| # | Condition | Verdict |
|---|---|---|
| 1 | au moins une ligne `FAILED` ou `UNSUPPORTED_ALGORITHM` | **Échec** (`FAILED`) |
| 2 | sinon, `CERTIFICATE_CHAIN` vaut `NOT_AVAILABLE` (aucun certificat du pays émetteur dans le magasin) | **Émetteur inconnu** (`UNKNOWN_ISSUER`) |
| 3 | sinon, PA réussie et (`CHIP_AUTHENTICATION` ou `ACTIVE_AUTHENTICATION` vaut `OK`) | **Authentique** (`AUTHENTIC`) |
| 4 | sinon, PA réussie, `CHIP_AUTHENTICATION` et `ACTIVE_AUTHENTICATION` valent `NOT_AVAILABLE` | **Signature valide, puce non vérifiée** (`SIGNATURE_VALID_CHIP_UNVERIFIED`) |
| 5 | tout autre cas | **Échec** (`FAILED`) |

« PA réussie » signifie : `SECURE_CHANNEL`, `SOD_SIGNATURE`, `CERTIFICATE_CHAIN` et `DG_HASHES` valent `OK`, et `DS_VALIDITY` vaut `OK` ou `NOT_AVAILABLE` (date de délivrance inconnue, ou estimée hors de la période du DS : décision D6). Une ligne manquante ou `SOD_SIGNATURE` à `NOT_AVAILABLE` hors du cas « Émetteur inconnu » tombe dans la règle 5.

Conséquences :

- un challenge raté l'emporte sur tout le reste : un CA réussi et un AA raté donnent « Échec » ;
- un émetteur absent du magasin donne « Émetteur inconnu » même si les empreintes sont cohérentes : rien n'est garanti, les données sont affichées ; la signature du SOD y est « non disponible » ;
- un document d'un pays dont le magasin contient des certificats, mais dont aucune chaîne n'aboutit, donne « Échec » (`CERTIFICATE_CHAIN` `FAILED`, `NoChainForKnownCountry` ; audit V25, décision D40), et non « Émetteur inconnu » ;
- les lignes `CHIP_AUTHENTICATION` et `SECURE_CHANNEL` peuvent aussi être mises en échec par un déclassement de protocole (§2.2), une clé de CA annoncée mais inexploitable (§2.6), une chaîne d'EF.CardSecurity qui n'aboutit pas au CSCA du SOD (§2.6.1) ou un canal impossible à rétablir après une CA ratée (§2.6) : audits V17, V19, V20, V24, décision D40 ;
- un document expiré n'affecte pas le verdict : l'écran de résultat affiche un bandeau « Document expiré » (SPEC §5.3).
- un écart d'empreinte DG11 ou DG12 couvert par une anomalie connue de l'émetteur (§2.5, décision D34) n'est pas un échec : `DG_HASHES` vaut `OK` et le verdict se calcule normalement, « Authentique » compris ; les données du DG écarté ne sont pas affichées.
