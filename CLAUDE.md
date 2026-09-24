# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## État du dépôt

Le socle (jalon 1) est en place : modules Gradle `:core` (Kotlin JVM, package `io.github.mgdx.sceau.core`) et `:app` (package `io.github.mgdx.sceau`), dépendances, ktlint, lint, CI GitHub Actions, manifeste, navigation et écrans stub. Les contrats d'interface (`core/src/main/kotlin/**`, `SessionViewModel`, `TrustStoreRepository`, signatures des écrans dans `app/.../ui/`) sont figés ; leurs corps `TODO("lot X")` sont remplis par les lots suivants. Les chaînes sont réparties par écran (`res/values*/strings_<écran>.xml`) pour limiter les conflits. Les `docs/` restent à écrire. `SPEC.md` est la source de vérité : lis-le en entier avant toute implémentation. Tout écart doit être consigné et justifié dans `docs/decisions.md`. Les jalons (section 11) fixent l'ordre de travail : Socle, Lecture, Vérification, Finitions, Publication.

## Ce qu'est Sceau

Application Android libre (GPLv3, visée F-Droid) qui lit par NFC la puce des documents ICAO 9303 (CNIe française, cartes d'identité UE, passeports), affiche DG1/DG2/DG11/DG12 et vérifie l'authenticité : Passive Authentication contre un magasin CSCA embarqué, puis Chip Authentication (DG14) et Active Authentication (DG15). Entièrement hors ligne, sans aucune persistance des données lues.

## Commandes

Projet Gradle Kotlin (AGP 9, Kotlin intégré), deux modules `:core` et `:app`. Toolchain JVM 17 ; le démon Gradle tourne sur JDK 25 (`gradle/gradle-daemon-jvm.properties`). Versions dans `gradle/libs.versions.toml`.

```bash
./gradlew :core:test                                                   # tests JVM du cœur
./gradlew :core:test --tests "io.github.mgdx.sceau.core.SomeTest"       # un seul test
./gradlew :app:assembleDebug                                            # APK de debug
./gradlew :app:assembleRelease                                          # APK release (R8), non signé
./gradlew lint ktlintCheck                                              # zéro avertissement exigé
./gradlew ktlintFormat                                                  # reformate selon .editorconfig
./gradlew check                                                         # tout ce qui bloque la CI
./gradlew :app:dependencies --configuration releaseRuntimeClasspath     # audit des dépendances
```

Règle CI (`.github/workflows/ci.yml` : `./gradlew check :app:assembleDebug`) : avertissements Kotlin traités en erreurs (`allWarningsAsErrors`), lint en `warningsAsErrors`, ktlint bloquant. Les règles lint désactivées sont listées et justifiées dans les `build.gradle.kts` et `app/lint.xml`. Règles R8 dans `app/src/main/keepRules/rules.keep`. Pas de splits ABI : l'app est pur Java/Kotlin, un seul APK universel, objectif de moins de 8 Mo, Master List comprise.

## Architecture

**`:core` — Kotlin pur, aucun import `android.*`, testé sur JVM.** Point d'entrée unique :

```kotlin
suspend fun readAndVerify(transport: CardTransport, key: AccessKey, trustStore: TrustStore, progress: (Step) -> Unit): VerificationReport
```

- `CardTransport` est une interface abstraite ; `:app` l'implémente au-dessus d'`IsoDep`, les tests utilisent un transport simulé qui rejoue des APDU enregistrés.
- Séquence (SPEC §6.1) : sélection AID ICAO → EF.CardAccess → PACE (CAN ou MRZ) sinon BAC (MRZ) → EF.COM, EF.SOD → DG1, DG2, puis DG14/DG15/DG11/DG12 si annoncés → PA (chaîne DS→CSCA avec link certificates, signature SOD, empreintes des DG, validité du DS à la date de délivrance) → CA si DG14 → AA si DG15 → `VerificationReport` immuable.
- DG3 et DG4 ne sont jamais lus. Un CAN sans PACE annoncé est une erreur explicite. Un document sans AID ICAO donne `NotIcaoDocument`.
- Les algorithmes ne sont jamais codés en dur : on accepte ce que déclarent les certificats et le SOD via BouncyCastle ; un algorithme inconnu donne `UnsupportedAlgorithm`. Une étape absente (pas de DG14/DG15) est distincte d'une étape échouée.
- Verdicts : Authentique (PA + CA ou AA ok), Signature valide puce non vérifiée (PA ok, ni DG14 ni DG15), Émetteur inconnu (chaîne sans CSCA connu), Échec.
- Magasin de confiance dans `core/src/main/resources/trust/` : certificats ANTS (`ants-csca-eid-*.der`, `ants-csca-*.der`) et `icao-master-list.ml` (CMS signé, rejeté si signature invalide). Un test unitaire compare les empreintes SHA-256 des fichiers embarqués à celles de `docs/trust-store.md`. Les CSCA importés par l'utilisateur sont fusionnés et marqués « importé ».
- Tests `:core` : une fabrique génère à la volée une hiérarchie factice (CSCA, link, DS, SOD, clés CA/AA) en RSA et ECDSA. Les cas à couvrir sont listés en SPEC §9.1.

**`:app` — NFC, Compose/Material 3, Navigation Compose.** Écrans : Accueil (onglets Carte d'identité / Passeport), Lecture (étapes cochées, erreurs avec Réessayer), Résultat (verdict, photo, identité, DG11/DG12, liste de contrôle, bouton Effacer), Magasin de confiance (liste + import de Master List via `ACTION_OPEN_DOCUMENT`), À propos.

## Dépendances imposées

JMRTD et scuba-sc-android (LGPL 3), BouncyCastle bcprov/bcpkix (jamais SpongyCastle), jj2000 fork JMRTD pour le JPEG 2000 (pur Java, pas de JNI), Compose/Material 3/Navigation. Toute autre dépendance doit être justifiée dans `docs/dependencies.md` (licence, compatibilité F-Droid). Aucun service Google, aucun blob binaire.

## Contraintes non négociables

- Manifeste : permission `android.permission.NFC` uniquement, aucune permission réseau, `android.hardware.nfc` en `required="false"`. minSdk 26.
- Aucune donnée lue n'est écrite sur disque, en cache, en base ni en log, en debug comme en release. Aucune donnée personnelle dans les exceptions ou les identifiants d'erreur.
- `FLAG_SECURE` sur les écrans Lecture et Résultat. Tableaux d'octets DG1/DG2/DG11/DG12 remis à zéro et libérés à la sortie du résultat et en arrière-plan. CAN/MRZ jamais mémorisés entre deux lectures.
- Nonce AA tiré d'un `SecureRandom`.
- Interface en français, toutes les chaînes dans `res/values/strings.xml`, `values-en` fourni dès la v1. Aucune chaîne codée en dur.
- Aucune télémétrie ni rapport de plantage.

## Skills du projet

`android-securite` avant chaque release, `android-test` pour le plan `docs/test-plan.md` sur appareil réel, `android-fdroid` avant la première soumission, `android-supervision` pour paralléliser un jalon entre plusieurs agents.
