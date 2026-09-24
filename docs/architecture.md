# Architecture

Ce document décrit l'organisation du code de Sceau, l'API publique du module `:core`, le flux des données de la lecture à l'affichage et le cycle de vie des données sensibles. La séquence ICAO elle-même est détaillée dans `docs/protocol.md`.

## 1. Modules

```
:app   Android : NFC (IsoDep), UI Compose / Material 3, navigation, cycle de vie
  │
  │ implementation
  ▼
:core  Kotlin pur (JVM 17) : lecture, vérification, magasin de confiance, modèles
```

| Module | Paquet | Rôle | Dépendances principales |
|---|---|---|---|
| `:core` | `io.github.mgdx.sceau.core` | Séquence ICAO 9303, Passive / Chip / Active Authentication, magasin de confiance, modèles de données, verdict | JMRTD, SCUBA (smartcards), BouncyCastle, kotlinx-coroutines |
| `:app` | `io.github.mgdx.sceau` | Transport `IsoDep`, écrans, navigation, session de lecture, stockage des Master Lists importées | `:core`, scuba-sc-android, Compose, Material 3, Navigation Compose, Lifecycle |

Règles :

- `:core` n'importe **aucune** classe `android.*` : il est compilé et testé sur la JVM (`./gradlew :core:test`). Les tests remplacent la puce par un transport simulé qui rejoue des APDU enregistrés, et génèrent à la volée une PKI factice (CSCA, lien, DS, SOD, clés CA et AA, en RSA et ECDSA).
- Les dépendances de `:core` sont en `implementation` (décision D9) : son API publique n'expose que des types Kotlin, `java.security` et `java.time`, jamais JMRTD ni BouncyCastle.
- Les ressources du magasin de confiance embarqué sont dans `core/src/main/resources/trust/` : 5 certificats CSCA de l'ANTS et la Master List allemande du BSI (décision D1, provenance dans `docs/trust-store.md`).

### Organisation des sources

```
core/src/main/kotlin/io/github/mgdx/sceau/core/
  Api.kt                   readAndVerify, CardTransport, AccessKey, Step, SceauException
  model/                   DocumentData, Dg1Data, Dg11Data, Dg12Data, EncodedImage, ArgbImage, Images
  report/                  VerificationReport, Verdict, Check, CheckId, CheckStatus, CheckDetail, ChainInfo
  trust/                   TrustStore, TrustAnchor, TrustSource, TrustStores, MasterList, MasterListInfo
  verify/                  PassiveAuthentication, ActiveAuthentication, Verdicts

app/src/main/java/io/github/mgdx/sceau/
  SceauApplication.kt      singleton TrustStoreRepository
  MainActivity.kt          activité unique, détection NFC, SessionViewModel à portée d'activité
  session/                 SessionViewModel, ReadState
  trust/                   TrustStoreRepository
  ui/                      SceauNavHost, Routes, écrans home/, reading/, result/, trust/, about/
  ui/common/SecureWindow   FLAG_SECURE compté par fenêtre
  ui/theme/                thème Material 3, mode sombre suivant le système
```

Les chaînes sont réparties par écran dans `res/values*/strings_<écran>.xml` (décision D5).

## 2. API publique de `:core`

### Lecture

```kotlin
suspend fun readAndVerify(transport: CardTransport, key: AccessKey, trustStore: TrustStore, progress: (Step) -> Unit): VerificationReport
```

| Type | Rôle |
|---|---|
| `CardTransport` | Canal APDU brut : `transceive(apdu)`, `maxTransceiveLength`, `timeoutMillis`, `close()`. Lève `SceauException.ConnectionLost` si le document est retiré. |
| `AccessKey.Can` / `AccessKey.Mrz` | Clé d'accès. `toString()` masqué (`***`). |
| `Step` | `CONNECT`, `SECURE_CHANNEL`, `READ_DATA`, `VERIFY_SIGNATURE`, `VERIFY_CHIP`. |
| `SceauException` | Erreurs qui interrompent la lecture, chacune avec un `code` stable sans donnée personnelle. |

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
| `Images.decodeJpeg2000(bytes)` | Décode un portrait JPEG 2000 en pixels ARGB, en pur Java (jj2000). Le JPEG classique est décodé côté app. |

### Magasin de confiance

| Élément | Rôle |
|---|---|
| `TrustStores.load(importedMasterLists)` | Charge le magasin embarqué et le fusionne avec les Master Lists importées (octets bruts fournis par l'app). Une liste embarquée invalide lève `InvalidMasterListException` ; un import invalide est ignoré. |
| `TrustStores.parseMasterList(bytes)` | Parse une Master List CMS (`.ml`, `.der`, `.p7b`) et vérifie sa signature avec le certificat signataire qu'elle contient. |
| `TrustStore` | `anchors`, `embeddedMasterList`, `findBySubject`, `findBySubjectKeyId`. Lecture seule. |
| `TrustAnchor` | Certificat et source (`ANTS`, `EMBEDDED_MASTER_LIST`, `IMPORTED_MASTER_LIST`), pays, empreinte SHA-256, validité, auto-signé ou lien. |

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

1. **Accueil** (`HomeScreen`) : l'utilisateur saisit le CAN (onglet Carte d'identité) ou la MRZ (onglet Passeport). La clé est construite en `AccessKey` et confiée à `SessionViewModel.prepare(key)`, qui passe en `WaitingForCard`. Le bouton « Lire » navigue vers l'écran de lecture.
2. **Détection NFC** : `MainActivity` (lancement `singleTop`) reçoit le tag `IsoDep` pendant que la session est en `WaitingForCard` ou `Error`, l'enveloppe dans un `CardTransport` et appelle `onCardDetected(transport)`.
3. **Lecture** (`ReadingScreen`) : `readAndVerify` s'exécute dans une coroutine du `ViewModel`. Chaque appel de `progress` fait passer l'état en `Reading(step)` ; l'écran coche les étapes terminées. Une `SceauException` donne `Error(code)`, affiché avec un message en français et « Réessayer » (`retry()`, même clé).
4. **Magasin de confiance** : `TrustStoreRepository.get()` fournit le `TrustStore` fusionné, chargé une fois puis gardé en mémoire jusqu'au prochain import ou effacement des imports.
5. **Résultat** (`ResultScreen`) : sur `Done(report)`, la navigation remplace l'écran de lecture par l'écran de résultat (`popUpTo(READING) inclusive`). L'écran met en forme le rapport ; le portrait JPEG 2000 est décodé par `Images.decodeJpeg2000`, le JPEG par le décodeur Android. Aucune donnée n'est copiée hors du rapport au-delà de ce qu'exige l'affichage.
6. **Magasin de confiance, écran dédié** (`TrustStoreScreen`) : liste des CSCA, import d'une Master List via `ACTION_OPEN_DOCUMENT` → `preview(bytes)` (signature vérifiée, empreinte du signataire montrée) → confirmation → `import(bytes)`. Les Master Lists importées sont stockées telles quelles dans `filesDir/trust/` : ce sont des certificats publics, pas des données personnelles. « Supprimer les certificats importés » appelle `clearImported()`.

## 4. Cycle de vie des données sensibles

Données sensibles : la clé d'accès (CAN ou MRZ) et le contenu de `VerificationReport.document` (DG1, portrait DG2, DG11, DG12, octets bruts des DG). Règles de SPEC §8.

### Où elles vivent

| Donnée | Emplacement | Durée de vie |
|---|---|---|
| Saisie CAN / MRZ | état Compose de l'écran d'accueil (`remember`, jamais `rememberSaveable`) | tant que l'écran est composé |
| `AccessKey` | `SessionViewModel` (portée d'activité) | de `prepare(key)` à `clear()` ; conservée après une erreur pour « Réessayer » |
| `VerificationReport` | `SessionViewModel`, état `ReadState.Done` | de la fin de la lecture à `clear()` |
| Images décodées (bitmap, `ArgbImage`) | écran de résultat | tant que l'écran est composé ; `ArgbImage.wipe()` disponible |
| Master Lists importées | `filesDir/trust/` | jusqu'à « Supprimer les certificats importés » (données publiques) |

Rien d'autre : aucune base, aucun fichier, aucun cache, aucun log, aucune préférence ne contient de donnée lue ni de clé. Le `SavedStateHandle` et le `Bundle` d'état de l'activité n'en contiennent pas non plus : après la mort du processus, l'application redémarre sur l'accueil, vide. La sauvegarde Android est désactivée (`allowBackup="false"`, et règles de sauvegarde et de transfert qui excluent tous les domaines).

### Quand elles sont effacées

`SessionViewModel.clear()` appelle `report.wipe()` (remise à zéro des tableaux d'octets de `DocumentData` : DG bruts, portrait, images DG12), oublie la clé et le rapport, et repasse en `Idle`. Il est appelé :

| Événement | Déclencheur |
|---|---|
| Bouton « Effacer » (bas de l'écran et barre d'action) | `ResultScreen` → `clear()` puis retour à l'accueil |
| Retour arrière système sur le résultat | même effet que « Effacer » (SPEC §5.3) |
| Mise en arrière-plan de l'application | passage de l'activité en arrière-plan (`ON_STOP`) : `clear()`, l'application revient à l'accueil au retour |
| Annulation de la lecture | retour depuis l'écran de lecture vers l'accueil |
| Fin du `ViewModel` | `onCleared()` (activité terminée) |

Le mécanisme exact d'observation du cycle de vie (observateur sur l'activité ou sur le processus) et l'effacement de la clé à l'annulation relèvent de l'implémentation de la session (à confirmer après sa fusion). Le plan de test (`docs/test-plan.md`) vérifie le comportement observable : aucune donnée à l'écran après retour d'arrière-plan.

Limite assumée : Kotlin/JVM ne permet pas d'effacer les `String` (nom, numéro, CAN) ni de garantir qu'aucune copie n'a été faite par le ramasse-miettes. La remise à zéro porte sur les tableaux d'octets, qui contiennent la photo et les DG bruts ; les chaînes deviennent inaccessibles dès que la session est vidée.

### Protection de l'écran

- `FLAG_SECURE` est posé sur la fenêtre tant que l'écran de lecture ou de résultat est composé (`SecureWindow()`) : capture d'écran refusée, aperçu masqué dans le multitâche, affichage bloqué sur un écran externe non sécurisé. Les demandes sont comptées par fenêtre pour que la transition Lecture → Résultat ne lève pas la protection.
- Les autres écrans (accueil, magasin de confiance, à propos) n'affichent aucune donnée lue et restent capturables.

### Réseau

Le manifeste ne déclare aucune permission réseau (`android.permission.NFC` seulement, décision D7 pour la permission interne ajoutée par androidx.core). Aucune donnée ne peut quitter l'appareil ; le magasin de confiance n'est jamais mis à jour par le réseau.
