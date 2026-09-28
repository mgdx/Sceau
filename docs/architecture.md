# Architecture

Ce document décrit l'organisation du code de Sceau, l'API publique du module `:core`, le flux des données de la lecture à l'affichage et le cycle de vie des données sensibles. La séquence ICAO elle-même est détaillée dans `docs/protocol.md`.

## 1. Modules

```
:app   Android : NFC (IsoDep), UI Compose / Material 3, navigation, cycle de vie
  │  │
  │  │ debugImplementation (APK de debug seulement : mode démo)
  │  ▼
  │ :testchip  Kotlin JVM : PKI factice, puce ICAO simulée, CNIe spécimen
  │  │          (aussi testImplementation de :core)
  │  │ implementation
  ▼  ▼
:core  Kotlin pur (JVM 17) : lecture, vérification, magasin de confiance, modèles
```

| Module | Paquet | Rôle | Dépendances principales |
|---|---|---|---|
| `:core` | `io.github.mgdx.sceau.core` | Séquence ICAO 9303, Passive / Chip / Active Authentication, magasin de confiance, modèles de données, verdict | JMRTD, SCUBA (smartcards), BouncyCastle, kotlinx-coroutines |
| `:app` | `io.github.mgdx.sceau` | Transport `IsoDep`, écrans, navigation, session de lecture, stockage des Master Lists importées | `:core`, scuba-sc-android, Compose, Material 3, Navigation Compose, Lifecycle ; `:testchip` en debug seulement |
| `:testchip` | `io.github.mgdx.sceau.testchip` | PKI factice, puce simulée (BAC, PACE, messagerie sécurisée, CA, AA), CNIe spécimen (`testchip/README.md`) | `:core`, JMRTD, SCUBA, BouncyCastle |

Règles :

- `:core` n'importe **aucune** classe `android.*` : il est compilé et testé sur la JVM (`./gradlew :core:test`). Ses tests remplacent la puce soit par un transport qui rejoue des APDU enregistrés (`ScriptedTransport`), soit par la puce simulée de `:testchip`, et génèrent à la volée une PKI factice (CSCA, lien, DS, SOD, clés CA et AA, en RSA et ECDSA).
- `:testchip` n'entre jamais dans l'APK release : `testImplementation` de `:core`, `debugImplementation` de `:app` (décision D17). Le code principal de `:core` n'en dépend pas.
- Les dépendances de `:core` sont en `implementation` (décision D9) : son API publique n'expose que des types Kotlin, `java.security` et `java.time`, jamais JMRTD ni BouncyCastle.
- Les ressources du magasin de confiance embarqué sont dans `core/src/main/resources/trust/` : 5 certificats CSCA de l'ANTS et la Master List allemande du BSI (décision D1, provenance dans `docs/trust-store.md`), énumérés par `trust/index.txt` (décision D16).

### Organisation des sources

```
core/src/main/kotlin/io/github/mgdx/sceau/core/
  Api.kt                   readAndVerify, CardTransport, AccessKey, Step, SceauException
  model/                   DocumentData, Dg1Data, Dg11Data, Dg12Data, EncodedImage, ArgbImage
  reading/                 ReadingSession (orchestration), SecureChannel (PACE, BAC, repli),
                           DocumentReader (EF.COM, SOD, DG), ChipVerifier (CA, AA),
                           Chip et TransportCardService (pont vers JMRTD), DataGroupParsers
  report/                  VerificationReport, Verdict, Check, CheckId, CheckStatus, CheckDetail, ChainInfo
  trust/                   TrustStore, TrustAnchor, TrustSource, TrustStores, MasterList, MasterListInfo,
                           TrustStoreLoader, MasterListParser
  verify/                  PassiveAuthentication, ActiveAuthentication, Verdicts, CertificateChains

app/src/main/java/io/github/mgdx/sceau/
  SceauApplication.kt      singleton TrustStoreRepository, préchargement du magasin au démarrage
  MainActivity.kt          activité unique, mode lecteur NFC, SessionViewModel à portée d'activité
  nfc/                     IsoDepTransport (CardTransport, classement des erreurs), NfcStatus
  session/                 SessionViewModel, ReadState, AccessForm (saisie de l'accueil, dates)
  trust/                   TrustStoreRepository
  demo/DemoCard            document simulé du mode démo (voir DemoMode ci-dessous)
  ui/                      SceauNavHost, Routes, écrans home/, reading/, result/, trust/, about/
  ui/common/SecureWindow   FLAG_SECURE compté par fenêtre
  ui/theme/                thème Material 3, palette du logo, mode sombre suivant le système
  jp2/Jpeg2000Decoder      décodage JPEG 2000 (JNI vers libsceau_jp2.so), appelé dans le seul processus isolé
  jp2/Jpeg2000Service      service isolatedProcess qui décode (D23)
  jp2/IsolatedJpeg2000Decoder  client du service, appelé par l'écran de résultat
  jp2/Jpeg2000Protocol     protocole binder par morceaux, testé sur JVM

app/src/debug/java/…/demo/DemoMode.kt     mode démo : CNIe simulée de :testchip
app/src/release/java/…/demo/DemoMode.kt   version vide (isAvailable = false)

app/src/main/cpp/
  CMakeLists.txt           construit libsceau_jp2.so (OpenJPEG statique + code de Sceau)
  jp2_decode.c/.h          cœur de décodage en mémoire, sans JNI, testé sur l'hôte
  jp2_jni.c                pont JNI
  openjpeg/                sous-module git, OpenJPEG master 8314119b, v2.5.4-29 (BSD-2-Clause, D3)
  test/                    harnais de test hôte (ASan/UBSan), hors APK : run-host-tests.sh
```

Les chaînes sont réparties par écran dans `res/values*/strings_<écran>.xml` (décision D5).

### Décodage des images

Le décodage vit dans `:app`, jamais dans `:core` (qui reste sans JNI). `ui/result/ImageDecoding.kt` choisit le décodeur selon le format annoncé et la signature des octets :

- JPEG : `BitmapFactory` d'Android ;
- JPEG 2000 (fichier JP2 ou flux J2K brut) : `IsolatedJpeg2000Decoder.decode(context, bytes): Bitmap?` (fonction `suspend`), qui confie le flux au service `Jpeg2000Service`, déclaré `android:isolatedProcess="true"` et non exporté, dans le processus `:jp2` (décision D23). Ce processus, sous un UID isolé, n'a aucune permission ni accès aux fichiers de l'app ; c'est le seul où `libsceau_jp2.so` est chargée et où `Jpeg2000Decoder.decode(bytes)` est appelé. La bibliothèque lie statiquement OpenJPEG, compilé depuis le sous-module `app/src/main/cpp/openjpeg` (décision D3), et décode depuis la mémoire ; elle renvoie null, sans exception ni trace, pour tout flux invalide, tronqué ou annonçant plus de 4096 pixels de côté.

  Chaque décodage lie le service (donc démarre un processus isolé neuf), envoie le flux par transactions binder de 256 Kio, reçoit largeur et hauteur puis les pixels ARGB par morceaux de 64 Ki pixels, et se détache ; le service efface ses tampons et termine son processus dans `onDestroy`. Rien ne passe par un fichier. Les décodages sont sérialisés. Processus isolé mort (bombe, bug natif), délai de 10 s dépassé (démarrage et transferts compris), service indisponible ou réponse hors protocole (dimensions au-delà de 2048 × 2048, morceau hors séquence) : null, l'image n'est pas affichée. `SceauApplication`, créée aussi dans le processus isolé, n'y précharge rien (`isIsolatedProcess()`).

Le code natif est compilé pour `armeabi-v7a`, `arm64-v8a`, `x86` et `x86_64`, d'où un APK par architecture plus un APK universel (décision D2). Le cœur `jp2_decode.c` se teste sur l'hôte : `app/src/main/cpp/test/run-host-tests.sh` (images synthétiques encodées par `opj_compress`, flux tronqués, altérés et démesurés, sous ASan et UBSan).

## 2. API publique de `:core`

### Lecture

```kotlin
suspend fun readAndVerify(transport: CardTransport, key: AccessKey, trustStore: TrustStore, progress: (Step) -> Unit): VerificationReport
```

| Type | Rôle |
|---|---|
| `CardTransport` | Canal APDU brut : `transceive(apdu)`, `maxTransceiveLength`, `timeoutMillis` (modifié par `:core` pendant l'authentification), `reconnect()` (réinitialisation de la liaison pour le repli PACE → BAC, vide par défaut), `close()`. Lève `SceauException.ConnectionLost` si le document est retiré, `SceauException.Timeout` si la puce ne répond pas à temps. |
| `AccessKey.Can` / `AccessKey.Mrz` | Clé d'accès. `toString()` masqué (`***`). |
| `Step` | `CONNECT`, `SECURE_CHANNEL`, `READ_DATA`, `VERIFY_SIGNATURE`, `VERIFY_CHIP`. |
| `SceauException` | Erreurs qui interrompent la lecture, chacune avec un `code` stable sans donnée personnelle, éventuellement suivi d'un diagnostic (`TIMEOUT-SECURE_CHANNEL-INS86-L10` ; liste dans `docs/protocol.md` §5). |

### Rapport

| Type | Rôle |
|---|---|
| `VerificationReport` | Verdict, sept `Check` (un par `CheckId`), `ChainInfo?`, `DocumentData`. `wipe()` remet à zéro les octets lus. |
| `Verdict` | `AUTHENTIC`, `SIGNATURE_VALID_CHIP_UNVERIFIED`, `UNKNOWN_ISSUER`, `FAILED`. |
| `Check`, `CheckId`, `CheckStatus` | Ligne de la liste de contrôle ; statut `OK`, `FAILED`, `NOT_AVAILABLE`, `UNSUPPORTED_ALGORITHM`. |
| `CheckDetail` | Détail structuré (protocole du canal, algorithme, chaîne, validité du DS, DG contrôlés, erreur technique). L'app le met en forme avec ses propres chaînes : `:core` ne produit aucun texte destiné à l'utilisateur. |
| `ChainInfo` | Sujet et numéro de série du DS, période, algorithme, CSCA trouvé (sujet, pays, empreinte, source), certificats de lien. Aucune donnée personnelle. |
| `DocumentData` | DG1, portrait, DG11, DG12 et octets bruts des DG lus. Aucune `data class`, `toString()` masqué. |

### Vérification (utilisée par `readAndVerify`, testable séparément)

| Objet | Rôle |
|---|---|
| `PassiveAuthentication.verify(…)` | Chaîne DS → CSCA, signature du SOD, empreintes, validité du DS. N'accède pas à la puce. |
| `ActiveAuthentication.verifyResponse(…)` | Vérifie la réponse au challenge AA avec la clé de DG15. |
| `Verdicts.compute(checks)` | Verdict global (table de décision dans `docs/protocol.md` §6). |

`:core` ne décode aucune image : il transmet les octets du portrait et des images de DG12 (`EncodedImage`, avec leur format) à `:app`, qui les décode (section 1, « Décodage des images »).

### Magasin de confiance

| Élément | Rôle |
|---|---|
| `TrustStores.load(importedMasterLists)` | Charge le magasin embarqué (fichiers énumérés par `trust/index.txt`) et le fusionne avec les Master Lists importées (octets bruts fournis par l'app), par ordre de priorité ANTS, liste embarquée, imports. Une liste embarquée invalide ou dont le signataire n'est pas ancré lève `InvalidMasterListException` ; un import invalide est ignoré. |
| `TrustStores.parseMasterList(bytes)` | Parse une Master List CMS (`.ml`, `.der`, `.p7b`) et vérifie sa signature avec le certificat signataire qu'elle contient. |
| `TrustStore` | `anchors`, `embeddedMasterList`, `findBySubject`, `findBySubjectKeyId`. Lecture seule. |
| `TrustAnchor` | Certificat et source (`ANTS`, `EMBEDDED_MASTER_LIST`, `IMPORTED_MASTER_LIST`), pays, empreinte SHA-256, validité, auto-signé ou lien (`isSelfSigned`, déduit des identifiants de clé AKI et SKI, signature vérifiée seulement en repli : décision D16). |

## 3. Flux de données

```
Accueil ──saisie CAN ou MRZ──► SessionViewModel.prepare(key)            état WaitingForCard
                                   │
Lecture   tag IsoDep détecté ─────► SessionViewModel.onCardDetected(transport)
          (MainActivity)            │  coroutine sur Dispatchers.IO
                                   ▼
                     readAndVerify(transport, key, trustStore, progress)
                       │  progress(step) ─────────────────────────► état Reading(step)
                       │  SceauException ─────────────────────────► état Error(code)  → « Réessayer » : retry()
                       ▼
                     VerificationReport ──────────────────────────► état Done(report)
                                   │
Résultat  affiche verdict, photo, DG1, DG11/DG12, liste de contrôle
          « Effacer » ou retour ───► SessionViewModel.clear()       report.wipe(), clé oubliée, état Idle
```

1. **Accueil** (`HomeScreen`) : l'utilisateur saisit le CAN (onglet Carte d'identité) ou la MRZ (onglet Passeport : numéro, puis dates tapées au clavier numérique dans l'ordre de la locale, décision D10). La saisie vit dans `SessionViewModel.form` (`AccessForm`), qui la valide. La clé est construite en `AccessKey` et confiée à `SessionViewModel.prepare(key)`, qui passe en `WaitingForCard`. Le bouton « Lire » navigue vers l'écran de lecture.
2. **Détection NFC** : `MainActivity` (lancement `singleTop`) active le mode lecteur NFC (`enableReaderMode`, NFC-A et NFC-B, tests de présence toutes les 2 s) entre `onResume` et `onPause`. Elle reçoit le tag `IsoDep` pendant que la session est en `WaitingForCard` ou `Error`, l'enveloppe dans un `IsoDepTransport` et appelle `onCardDetected(transport)`.
3. **Lecture** (`ReadingScreen`) : `readAndVerify` s'exécute dans une coroutine du `ViewModel`, sur `Dispatchers.IO`. Chaque appel de `progress` fait passer l'état en `Reading(step)` ; l'écran coche les étapes terminées et affiche un message d'attente si l'ouverture du canal sécurisé dure plus de 5 s (décision D12). Une `SceauException` donne `Error(code)`, affiché avec un message en français, le code technique en petit (décision D11) et « Réessayer » (`retry()`, même clé). Le transport est toujours fermé hors du thread principal (`IsoDep.close()` attend la fin de l'APDU en cours).
4. **Magasin de confiance** : `TrustStoreRepository.get()` fournit le `TrustStore` fusionné, chargé une fois puis gardé en mémoire jusqu'au prochain import ou effacement des imports. `SceauApplication` le précharge en arrière-plan au démarrage du processus (décision D16).
5. **Résultat** (`ResultScreen`) : sur `Done(report)`, la navigation remplace l'écran de lecture par l'écran de résultat (`popUpTo(READING) inclusive`). L'écran met en forme le rapport ; le portrait JPEG 2000 est décodé par OpenJPEG dans le processus isolé de `Jpeg2000Service` (décision D23), le JPEG par le décodeur Android. Aucune donnée n'est copiée hors du rapport au-delà de ce qu'exige l'affichage.
6. **Magasin de confiance, écran dédié** (`TrustStoreScreen`) : liste des CSCA, import d'une Master List via `ACTION_OPEN_DOCUMENT` → `preview(bytes)` (signature vérifiée, empreinte du signataire montrée) → confirmation → `import(bytes)`. Les Master Lists importées sont stockées telles quelles dans `filesDir/trust/` : ce sont des certificats publics, pas des données personnelles. « Supprimer les certificats importés » appelle `clearImported()`.
7. **Mode démo** (APK de debug seulement, décision D17) : l'entrée « Simuler une CNIe (démo) » du menu de l'accueil construit, hors du thread principal, une CNIe simulée de `:testchip` (`DemoMode.newSimulatedCnie()`), puis `SessionViewModel.startDemo(card)` la lit par le même chemin qu'un document réel (`launchRead`), avec sa clé CAN et son magasin de test, qui ne sert qu'à cette lecture. La clé saisie est oubliée. Le résultat porte le bandeau « Document simulé — démonstration » (`ReadState.Done.isDemo`). Dans l'APK release, `DemoMode.isAvailable` vaut `false` et l'entrée n'existe pas.

## 4. Cycle de vie des données sensibles

Données sensibles : la clé d'accès (CAN ou MRZ) et le contenu de `VerificationReport.document` (DG1, portrait DG2, DG11, DG12, octets bruts des DG). Règles de SPEC §8.

### Où elles vivent

| Donnée | Emplacement | Durée de vie |
|---|---|---|
| Saisie CAN / MRZ | `SessionViewModel.form` (`AccessForm`, en mémoire, jamais dans un `Bundle` ni un `SavedStateHandle` ; `toString()` masqué) | de la frappe à la fin d'une lecture réussie (formulaire vidé, onglet gardé) ou à `clear()` |
| `AccessKey` | `SessionViewModel` (portée d'activité) | de `prepare(key)` à la fin d'une lecture réussie ou à `clear()` ; conservée après une erreur pour « Réessayer » |
| `VerificationReport` | `SessionViewModel`, état `ReadState.Done` | de la fin de la lecture à `clear()` |
| Images décodées (bitmap) | écran de résultat | tant que l'écran est composé ; effacées par `wipeAndRecycle()`. Les tampons intermédiaires du décodage JPEG 2000 (copie native du flux, échantillons, tableau ARGB natif et Java, flux et pixels reçus ou envoyés par binder, des deux côtés) sont remis à zéro dès le bitmap construit ; le processus isolé est terminé après chaque image (D23) |
| Master Lists importées | `filesDir/trust/` | jusqu'à « Supprimer les certificats importés » (données publiques) |

Rien d'autre : aucune base, aucun fichier, aucun cache, aucun log, aucune préférence ne contient de donnée lue ni de clé. Le `SavedStateHandle` et le `Bundle` d'état de l'activité n'en contiennent pas non plus : après la mort du processus, l'application redémarre sur l'accueil, vide. La sauvegarde Android est désactivée (`allowBackup="false"`, et règles de sauvegarde et de transfert qui excluent tous les domaines).

### Quand elles sont effacées

`SessionViewModel.clear()` interrompt la lecture en cours (fermeture du transport hors du thread principal), appelle `report.wipe()` (remise à zéro des tableaux d'octets de `DocumentData` : DG bruts, portrait, images DG12), oublie la clé, la saisie et le rapport, et repasse en `Idle`. Il est appelé :

| Événement | Déclencheur |
|---|---|
| Bouton « Effacer » (bas de l'écran et barre d'action) | `ResultScreen` → `clear()` puis retour à l'accueil |
| Retour arrière système sur le résultat | même effet que « Effacer » (SPEC §5.3) |
| Mise en arrière-plan pendant une lecture ou sur le résultat | `MainActivity.onStop()` hors changement de configuration → `onBackground()` → `clear()` si l'état est `Reading` ou `Done` ; l'application revient à l'accueil au retour |
| Annulation de la lecture | bouton « Annuler » ou retour arrière sur l'écran de lecture → `clear()` |
| Fin du `ViewModel` | `onCleared()` (activité terminée) |

Une mise en arrière-plan depuis l'accueil (en `Idle`, `WaitingForCard` ou `Error`) ne vide pas la saisie ni la clé, qui ne vivent qu'en mémoire : l'utilisateur peut passer par les réglages NFC sans tout ressaisir (SPEC §5.1). Une rotation n'efface rien (`isChangingConfigurations`). Un rapport produit par une lecture abandonnée entre-temps est remis à zéro dès sa réception. Si la lecture échoue, `:core` remet à zéro les données déjà lues.

Limites assumées :

- Kotlin/JVM ne permet pas d'effacer les `String` (nom, numéro, CAN) ni de garantir qu'aucune copie n'a été faite par le ramasse-miettes. La remise à zéro porte sur les tableaux d'octets, qui contiennent la photo et les DG bruts ; les chaînes deviennent inaccessibles dès que la session est vidée.
- JMRTD et OpenJPEG gardent des copies intermédiaires dans des tampons internes qu'ils libèrent sans remise à zéro (décisions D3 et D14). Elles restent en mémoire et ne sont ni écrites ni journalisées ; celles d'OpenJPEG disparaissent avec le processus isolé, terminé après chaque image (D23). Les tampons des `Parcel` binder qui transportent le flux et les pixels sont libérés sans remise à zéro (l'API publique ne le permet pas).

### Journaux

Aucun appel à `android.util.Log`, `println` ni `printStackTrace` dans le code de Sceau, même temporaire. Les journaux `java.util.logging` de JMRTD et SCUBA sont coupés avant chaque lecture, et aucune exception de JMRTD n'est attachée comme cause (leurs messages contiennent des APDU) : décision D14. Le diagnostic passe par le code d'erreur affiché (décision D11).

### Protection de l'écran

- `FLAG_SECURE` est posé sur la fenêtre tant que l'écran de lecture ou de résultat est composé (`SecureWindow()`) : capture d'écran refusée, aperçu masqué dans le multitâche, affichage bloqué sur un écran externe non sécurisé. Les demandes sont comptées par fenêtre pour que la transition Lecture → Résultat ne lève pas la protection.
- Les autres écrans (accueil, magasin de confiance, à propos) n'affichent aucune donnée lue et restent capturables.

### Réseau

Le manifeste ne déclare aucune permission réseau (`android.permission.NFC` seulement, décision D7 pour la permission interne ajoutée par androidx.core). Aucune donnée ne peut quitter l'appareil ; le magasin de confiance n'est jamais mis à jour par le réseau.

## 5. Tests

### Fuzzing des parseurs

`core/src/test/kotlin/…/core/fuzz/` contient un fuzzer mutationnel déterministe, sans dépendance (pas de Jazzer), lancé par `./gradlew :core:test`. Les graines sont des entrées valides produites par `:testchip` (EF.COM, EF.SOD, DG1 TD1 et TD3, DG2, DG11, DG12, DG14, DG15, EF.CardAccess, Master List de test) et la Master List embarquée. Les mutations touchent les octets (bit, octet, troncature, insertion, duplication ou suppression de bloc) et l'arbre TLV/DER (longueur gonflée, réduite, en forme longue sur 4 octets ou plus, indéfinie, imbrication profonde, tag inattendu, enfants dupliqués ou permutés, valeur remplacée) ; le contenu signé est aussi muté puis re-signé (LDSSecurityObject, `CscaMasterList`). Chaque cible vérifie le contrat de son appelant réel : résultat ou exception attendue, jamais d'`Error` (mémoire, pile), de délai dépassé (2 s par entrée), ni de SOD_SIGNATURE, DG_HASHES ou AA « OK » pour une entrée mutée dans sa partie signée.

Le nombre d'itérations par défaut tient l'ensemble sous 30 s. Mode long, reproduction et délai (`SCEAU_FUZZ_TIMEOUT_MILLIS`), par variables d'environnement (les `-D` de `./gradlew` n'atteignent pas la JVM des tests ; les propriétés système `sceau.fuzz.*` restent lues, pour un lancement depuis l'IDE) :

```bash
SCEAU_FUZZ_ITERATIONS=200000 ./gradlew :core:test --tests "io.github.mgdx.sceau.core.fuzz.*" --rerun   # mode long
SCEAU_FUZZ_SEED=0x5cea2026 SCEAU_FUZZ_INDEX=736 ./gradlew :core:test --tests "…FuzzTest.comDataGroups" --rerun  # une itération
```

Un échec donne la cible, la graine, l'index et la liste des mutations, jamais les octets ; les graines signées sont resignées de façon déterministe (ECDSA RFC 6979, heure de signature fixe) pour que graine et index suffisent à le rejouer. Les échecs sont regroupés par signature (exception et ligne du projet). Une entrée minimale devient un test de non-régression ; un bogue non encore corrigé reste dans `FuzzRegressionTest`, marqué `@Ignore`.
