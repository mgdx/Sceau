# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## État du dépôt

Jalons atteints (SPEC §11) : Socle, Lecture, Vérification et l'essentiel des Finitions (DG11/DG12, écran Magasin de confiance et import, À propos, effacement mémoire, `FLAG_SECURE`, traductions en-US). La lecture réelle d'un passeport français a été validée sur Fairphone 3 le 2026-09-25. Reste le jalon Publication (revue sécurité, plan de test complet sur appareil, première release, soumission F-Droid).

`SPEC.md` est la source de vérité : lis-le en entier avant toute modification. Tout écart est consigné et justifié dans `docs/decisions.md` (D1 à D18 à ce jour). La documentation vit dans `docs/` : `architecture.md`, `protocol.md` (séquence ICAO réelle, délais, codes d'erreur), `trust-store.md`, `dependencies.md`, `test-plan.md`, `decisions.md`.

## Ce qu'est Sceau

Application Android libre (GPLv3, visée F-Droid) qui lit par NFC la puce des documents ICAO 9303 (CNIe française, cartes d'identité UE, passeports), affiche DG1/DG2/DG11/DG12 et vérifie l'authenticité : Passive Authentication contre un magasin CSCA embarqué, puis Chip Authentication (DG14) et Active Authentication (DG15). Entièrement hors ligne, sans aucune persistance des données lues.

## Commandes

Projet Gradle Kotlin (AGP 9, Kotlin intégré), trois modules : `:core`, `:app`, `:testchip`. Toolchain JVM 17 ; le démon Gradle tourne sur JDK 25 (`gradle/gradle-daemon-jvm.properties`). Versions dans `gradle/libs.versions.toml`.

Le décodeur JPEG 2000 (OpenJPEG) est un sous-module git : après un clone, `git submodule update --init` (ou `git clone --recurse-submodules`). La compilation native demande le NDK `28.2.13676358` et CMake `4.1.2`.

```bash
./gradlew :core:test                                                   # tests JVM du cœur
./gradlew :core:test --tests "io.github.mgdx.sceau.core.SomeTest"       # un seul test
./gradlew :testchip:test                                               # tests de la puce simulée
./gradlew :app:testDebugUnitTest                                        # tests JVM de l'app (dont le mode démo)
./gradlew :app:assembleDebug                                            # APK de debug (mode démo inclus)
./gradlew :app:assembleRelease                                          # APK release (R8), non signés
./gradlew lint ktlintCheck                                              # zéro avertissement exigé
./gradlew ktlintFormat                                                  # reformate selon .editorconfig
./gradlew check                                                         # tout ce qui bloque la CI
./gradlew :app:dependencies --configuration releaseRuntimeClasspath     # audit des dépendances
app/src/main/cpp/test/run-host-tests.sh                                 # tests hôte du décodeur JPEG 2000 (ASan, UBSan)
```

Règle CI (`.github/workflows/ci.yml` : checkout avec sous-modules, puis `./gradlew check :app:assembleDebug`) : avertissements Kotlin traités en erreurs (`allWarningsAsErrors`), lint en `warningsAsErrors`, ktlint bloquant. Les règles lint désactivées sont listées et justifiées dans les `build.gradle.kts`, `app/lint.xml` et `docs/decisions.md` (D8, D17). Règles R8 dans `app/src/main/keepRules/rules.keep`.

Splits ABI (décision D2) : un APK par architecture (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) plus un APK universel, chacun sous l'objectif de 8 Mo, Master List comprise.

## Architecture

**`:core` — Kotlin pur, aucun import `android.*`, testé sur JVM** (paquet `io.github.mgdx.sceau.core`). Point d'entrée unique :

```kotlin
suspend fun readAndVerify(transport: CardTransport, key: AccessKey, trustStore: TrustStore, progress: (Step) -> Unit): VerificationReport
```

- `CardTransport` est une interface abstraite (`transceive`, `maxTransceiveLength`, `timeoutMillis`, `reconnect`, `close`) ; `:app` l'implémente au-dessus d'`IsoDep` (`IsoDepTransport`), les tests utilisent un transport qui rejoue des APDU enregistrés ou la puce simulée de `:testchip`.
- Séquence réelle (`docs/protocol.md`) : EF.CardAccess lu au niveau MF → sans PACE, sélection de l'AID ICAO en clair puis BAC (MRZ) ; avec PACE, PACE (CAN ou MRZ) au MF puis sélection sous messagerie sécurisée → EF.COM, EF.SOD → DG1, DG2, puis DG14/DG15/DG11/DG12 si annoncés → PA (chaîne DS→CSCA avec link certificates, signature SOD, empreintes des DG, validité du DS à la date de délivrance) → CA si DG14 → AA si DG15 → `VerificationReport` immuable.
- Pendant PACE et BAC, délai de réponse porté à 60 s (puce qui fait patienter après des essais ratés), sans repli sur BAC après un délai dépassé. Avec une clé MRZ, si PACE échoue autrement que par un refus de clé ou un délai, reconnexion puis BAC (D12, D13). APDU courtes uniquement (≤ 256), DG2 absent toléré (D14).
- DG3 et DG4 ne sont jamais lus. Un CAN sans PACE annoncé est une erreur explicite (`CAN_WITHOUT_PACE`). Un document sans AID ICAO donne `NotIcaoDocument`.
- Les algorithmes ne sont jamais codés en dur : on accepte ce que déclarent les certificats et le SOD via BouncyCastle ; un algorithme inconnu donne `UNSUPPORTED_ALGORITHM`. Une étape absente (pas de DG14/DG15) est distincte d'une étape échouée.
- Verdicts (`Verdicts.compute`, table dans `docs/protocol.md` §6) : Authentique (PA + CA ou AA ok), Signature valide puce non vérifiée (PA ok, ni DG14 ni DG15), Émetteur inconnu (chaîne sans CSCA connu), Échec.
- Erreurs : `SceauException` avec un code stable sans donnée personnelle, suivi d'un diagnostic (étape, INS, longueur arrondie, sous-type d'E/S) ; l'écran de lecture affiche ce code pour toutes les erreurs (D11).
- Magasin de confiance dans `core/src/main/resources/trust/`, énuméré par `trust/index.txt` : 5 certificats CSCA de l'ANTS (`ants-csca-2010/2015/2020/2025.der`, `ants-csca-eid-2021.der`) et la Master List allemande du BSI `de-bsi-master-list.ml` (CMS signé, rejetée si la signature est invalide ou si son signataire n'est pas émis par le CSCA allemand dont l'empreinte est épinglée dans `TrustStoreLoader`, décision D1). `TrustStoreFingerprintTest` compare les empreintes SHA-256 des fichiers embarqués à celles de `docs/trust-store.md`. Les Master Lists importées par l'utilisateur sont fusionnées et marquées « importé ». Le magasin est préchargé au démarrage de l'app (D16).

**`:testchip` — Kotlin JVM, jamais dans l'APK release** (paquet `io.github.mgdx.sceau.testchip`) : PKI factice (CSCA, lien, DS, SOD, clés CA/AA en RSA et EC), puce simulée à état (BAC, PACE, messagerie sécurisée, CA, AA) écrite sans le code protocolaire de JMRTD, CNIe spécimen. `testImplementation` de `:core`, `debugImplementation` de `:app`. Voir `testchip/README.md` et D17.

**`:app` — NFC, Compose/Material 3, Navigation Compose** (paquet `io.github.mgdx.sceau`). Écrans : Accueil (onglets Carte d'identité / Passeport, dates tapées au clavier dans l'ordre de la locale, D10), Lecture (étapes cochées, message d'attente après 5 s d'authentification, erreurs avec code et Réessayer), Résultat (verdict, photo, identité, DG11/DG12, liste de contrôle, bouton Effacer), Magasin de confiance (liste + import de Master List via `ACTION_OPEN_DOCUMENT`), À propos. JPEG 2000 décodé par OpenJPEG en JNI (`jp2/Jpeg2000Decoder`, `app/src/main/cpp/`, D3). Palette du logo, couleurs dynamiques désactivées (D18).

**Mode démo (APK de debug uniquement)** : menu de l'accueil → « Simuler une CNIe (démo) » lit la CNIe simulée de `:testchip` dans la vraie interface, avec son propre magasin de test (jamais mêlé au magasin réel) et le bandeau « Document simulé ». `DemoMode` existe en version `src/debug` et en version vide `src/release`.

## Dépendances

JMRTD et SCUBA (`scuba-smartcards`, `scuba-sc-android` ; LGPL 2.1 ou ultérieure), BouncyCastle `bcprov`/`bcpkix` `jdk18on` (jamais SpongyCastle), OpenJPEG (BSD-2-Clause) compilé depuis le sous-module `app/src/main/cpp/openjpeg` pour le JPEG 2000 (jj2000 écarté : licence non libre, D3), Compose/Material 3/Navigation. Toute autre dépendance doit être justifiée dans `docs/dependencies.md` (licence vérifiée sur l'artefact, compatibilité F-Droid). Aucun service Google, aucun blob binaire.

## Contraintes non négociables

- Manifeste : permission `android.permission.NFC` uniquement (plus la permission interne ajoutée par androidx.core, D7), aucune permission réseau, `android.hardware.nfc` en `required="false"`. minSdk 26.
- Aucune donnée lue n'est écrite sur disque, en cache, en base ni en log, en debug comme en release. **Aucun journal, même temporaire pour déboguer** : ni `Log`, ni `println`, ni `printStackTrace` ; le diagnostic passe par le code d'erreur affiché. Les loggers de JMRTD et SCUBA sont coupés, et aucune exception de JMRTD n'est attachée comme cause (leurs messages contiennent des APDU). Aucune donnée personnelle dans les exceptions ou les identifiants d'erreur.
- `FLAG_SECURE` sur les écrans Lecture et Résultat. Tableaux d'octets DG1/DG2/DG11/DG12 remis à zéro et libérés à la sortie du résultat et en arrière-plan. CAN/MRZ jamais mémorisés entre deux lectures.
- Nonce AA tiré d'un `SecureRandom`.
- Interface en français, aucune chaîne codée en dur : chaînes réparties par écran dans `res/values/strings_<écran>.xml` (D5), `values-en` fourni.
- Aucune télémétrie ni rapport de plantage.

## Skills du projet

`android-securite` avant chaque release, `android-test` pour le plan `docs/test-plan.md` sur appareil réel, `android-fdroid` avant la première soumission, `android-supervision` pour paralléliser un jalon entre plusieurs agents.
