# `:testchip` — puce ICAO 9303 simulée et PKI factice

Module Kotlin JVM pur, **jamais embarqué en production**. Il sert :

- aux tests de `:core` (`testImplementation(project(":testchip"))`) ;
- au mode démo de l'APK **debug uniquement** (`debugImplementation(project(":testchip"))` dans
  `app/build.gradle.kts`) : menu de l'accueil → « Simuler une CNIe (démo) », branché par
  `app/src/debug/java/…/demo/DemoMode.kt` ; la version `app/src/release/` de `DemoMode` est vide.

`:core` (main) ne dépend pas de ce module, et `./gradlew :app:dependencies --configuration
releaseRuntimeClasspath` ne le mentionne pas (décision D17).

## Contenu

| Élément | Rôle |
|---|---|
| `TestPki`, `TestCredential`, `TestKeyType` | Hiérarchie factice CSCA (ancien, nouveau, lien) → DS, RSA ou EC, clés reproductibles (graine). |
| `TestDocument`, `TestSod`, `SodOptions` | DG factices et EF.SOD signé, y compris les altérations des cas d'échec. |
| `TestCardSecurity` | EF.CardSecurity signé par le DS (PACE-CAM), mêmes altérations que le SOD. |
| `TestTrustStore` | `TrustStore` de test contenant les CSCA choisis. |
| `SimulatedChip` | `CardTransport` qui se comporte comme une puce : EF.CardAccess, PACE, BAC, messagerie sécurisée 3DES/AES, lecture des fichiers, Chip Authentication, Active Authentication, liaison réinitialisable (`reconnect`). Note sous quelle session chaque fichier est servi (`fileReads`, `sessionsServing`). Peut réserver l'applet ou des fichiers aux autorités (`restrictedApplet`, `restrictedFiles` : SW 6982, D36). |
| `PaceSettings`, `PacePassword` | PACE de la puce simulée (EF.CardAccess, CAN). |
| `SimulatedDocuments.frenchIdCard()` | CNIe française simulée prête à lire (voir ci-dessous). |
| `SimulatedDocuments.germanEidUb()` | Carte eID allemande pour citoyens de l'Union (eID-UB) : DG1 sans donnée d'identité (MRZ « UBD<<<… »), CA sans AA (décision D36). |

Tous les échanges cryptographiques côté puce (BAC, PACE, messagerie sécurisée, CA) sont écrits
d'après ICAO 9303-11 et BSI TR-03110 avec les primitives de BouncyCastle, **sans** le code
protocolaire de JMRTD : un test de bout en bout confronte donc réellement JMRTD (utilisé par
Sceau) à une implémentation indépendante.

## CNIe simulée

```kotlin
val card = SimulatedDocuments.frenchIdCard()          // CAN 123456 par défaut
val report = readAndVerify(card.chip(), card.canKey!!, card.trustStore) { }
// ou card.mrzKey pour PACE avec la clé MRZ
```

- EF.CardAccess : `PACEInfo` id-PACE-ECDH-GM-AES-CBC-CMAC-128, paramètres standardisés 13
  (brainpoolP256r1). BAC reste accepté (transition ICAO).
- DG1 : MRZ TD1, code « ID », FRA, `SPECIMEN` / `MARIANNE`, document `SPEC12345`.
- DG2 : portrait JPEG 2000 synthétique (`specimen-portrait.jp2`), ISO 19794-5.
- DG11 : nom complet, lieu de naissance et adresse fictifs. DG12 : « PREFECTURE DE TEST »,
  délivrance le 15/03/2024.
- DG14 : clé CA ECDH brainpoolP256r1, `ChipAuthenticationInfo` AES-128,
  `ActiveAuthenticationInfo` ECDSA SHA-256. DG15 : clé AA EC.
- EF.SOD : SHA-256, signé par `DS-TEST-FRANCE`, émis par `CSCA-TEST-FRANCE` (EC brainpoolP256r1).
  Ces certificats sont générés à la volée et ne figurent dans aucun magasin réel.

Chaque appel à `chip()` renvoie une puce neuve : une puce simulée a un état, une par lecture.

### PACE simulé (fidélité)

MSE:Set AT (`00 22 C1 A4`, objets 80/83/84), puis GENERAL AUTHENTICATE en quatre étapes
chaînées : nonce chiffré `z = E(Kπ, s)` avec `Kπ = KDF(f(π), 3)` (f(π) = CAN en ISO 8859-1, ou
SHA-1 de l'information MRZ sur 20 octets), mapping générique ECDH `G' = s·G + H`, accord de clés
éphémère sur `G'`, jetons AES-CMAC sur `7F49{06 OID, 86 point}`, clés de session `KDF(K, 1)` et
`KDF(K, 2)`, SSC = 0. Un jeton du lecteur faux (mauvais CAN) donne `63Cx` (x = essais restants,
3 au départ) à la dernière étape. Non simulés : mapping intégré (IM), DH en corps fini, 3DES,
paramètres de domaine propriétaires, blocage du mot de passe.

PACE-CAM (`PaceSettings(can, protocolOid = ID_PACE_ECDH_CAM_AES_CBC_CMAC_128)`) : même échange,
mais la dernière réponse contient aussi `8A A.IC`, `A.IC = E(KSenc, CA.IC)` (AES-CBC, IV
`E(KSenc, −1)`), `CA.IC = SK.IC⁻¹ · SK.PICC.map mod n` avec la clé CA de la puce. EF.CardSecurity
(`TestCardSecurity.of(document, pace)` : `PACEInfo`, `ChipAuthenticationInfo`,
`ChipAuthenticationPublicKeyInfo`, signé par le DS) est passé à `SimulatedChip(cardSecurity = …)`
et servi au MF sous messagerie sécurisée seulement. `tamperChipAuthenticationMapping` fait
envoyer `CA.IC + 1`.

## Portrait synthétique

`src/main/resources/specimen-portrait.jp2` (240 × 320, environ 9 Ko) : fond en dégradé et
silhouette anonyme dessinés par script, **aucune photo de personne**. Pour le régénérer :

```bash
git submodule update --init                       # sous-module OpenJPEG
testchip/tools/generate-specimen-portrait.sh      # construit opj_compress hors du dépôt
```

Le script construit `opj_compress` depuis `app/src/main/cpp/openjpeg` dans un dossier
temporaire, dessine l'image en PPM avec `python3`, puis l'encode :
`opj_compress -i specimen.ppm -o specimen-portrait.jp2 -r 25 -I` (JP2, compression 9/7 avec
pertes, taux 25). Il échoue si le fichier dépasse 20 Ko.
