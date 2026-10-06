# Contribuer à Sceau

Merci de votre intérêt. Sceau manipule des données d'identité : les règles ci-dessous ne sont pas des préférences de style, elles conditionnent l'acceptation d'une contribution. Elles reprennent SPEC §2 (contraintes générales), §3 (dépendances) et §8 (vie privée).

Avant de commencer, lisez [`SPEC.md`](SPEC.md) (source de vérité), [`docs/architecture.md`](docs/architecture.md) et [`docs/protocol.md`](docs/protocol.md). Toute modification qui s'écarte de la SPEC doit être consignée et justifiée dans [`docs/decisions.md`](docs/decisions.md).

## Règles

### Qualité

- **Zéro avertissement** sur `test`, `lint` et `ktlintCheck`. Les avertissements Kotlin sont traités en erreurs (`allWarningsAsErrors`), lint en `warningsAsErrors`. Un seul avertissement fait échouer la CI.
- Ne désactivez pas une règle lint ou ktlint pour faire passer une build. Une exception, si elle est inévitable, est ciblée au plus près (un fichier, un jar), commentée dans le fichier de configuration et consignée dans `docs/decisions.md`.
- Tout changement de comportement de `:core` s'accompagne d'un test JVM. Les cas de SPEC §9.1 doivent rester couverts.

### Architecture

- **`:core` est du Kotlin pur** : aucun import `android.*`, aucune dépendance Android. Il doit se compiler et se tester sur la JVM.
- L'API publique de `:core` n'expose aucun type de JMRTD ni de BouncyCastle.
- **Aucun algorithme codé en dur** : on accepte ce que déclarent les certificats et le SOD ; un algorithme inconnu donne `UNSUPPORTED_ALGORITHM`, jamais un échec silencieux (SPEC §6.2).
- Une étape non disponible (`NOT_AVAILABLE`) reste distincte d'une étape échouée (`FAILED`).
- **`:testchip` n'entre jamais dans l'APK release** : il reste en `testImplementation` de `:core` et en `debugImplementation` de `:app`. Tout ce qui s'en sert dans `:app` passe par le jeu de sources `debug`, avec une version vide dans `release` (décision D17).

### Interface

- **Aucune chaîne codée en dur.** Toutes les chaînes sont dans `app/src/main/res/values/strings_<écran>.xml` (français) avec leur traduction dans `values-en/` (décision D5). Une chaîne ajoutée sans traduction anglaise est incomplète.
- Compose et Material 3 ; le thème suit le mode sombre du système.

### Vie privée et sécurité

- **Aucune donnée lue** (DG1, photo, DG11, DG12) ni clé d'accès (CAN, MRZ) n'est écrite sur disque, en cache, en base, en préférences ou dans les journaux, en debug comme en release. Pas de `rememberSaveable` ni de `SavedStateHandle` pour ces données.
- **Aucun journal, même temporaire pour déboguer** : ni `Log`, ni `println`, ni `printStackTrace`. Pour diagnostiquer sur appareil, enrichissez plutôt le code d'erreur affiché, avec des éléments sans donnée personnelle (étape, INS, longueur arrondie, sous-type d'une liste fermée : décision D11).
- Aucune donnée personnelle dans les messages d'exception ni dans les codes d'erreur. N'attachez jamais une exception de JMRTD comme cause : ses messages contiennent des APDU en hexadécimal (décision D14). Les classes de modèle ne sont pas des `data class` et masquent leur `toString()`.
- Les écrans qui affichent des données lues posent `FLAG_SECURE` (`SecureWindow()`).
- Les tableaux d'octets lus sont remis à zéro (`wipe()`) à la sortie du résultat et en arrière-plan.
- Tout aléa cryptographique vient de `SecureRandom`.
- **Aucune permission réseau.** Le manifeste ne demande que `android.permission.NFC`.
- Aucune télémétrie, aucun rapport de plantage, aucune bibliothèque d'analyse ou de publicité.

### Dépendances

- Dépendances imposées : JMRTD, scuba-sc-android, BouncyCastle `jdk18on` (jamais SpongyCastle), OpenJPEG pour le JPEG 2000 (code natif compilé depuis les sources, décision D3), Compose, Material 3, Navigation Compose.
- **Toute nouvelle dépendance**, y compris une transitive notable, est justifiée dans [`docs/dependencies.md`](docs/dependencies.md) : version, licence, rôle, compatibilité F-Droid, dans le même commit que son ajout.
- **Aucun service Google** (Play Services, Firebase, ML Kit…), **aucun blob binaire** non reconstructible, aucune bibliothèque native sans justification. Le seul code natif, OpenJPEG, est compilé depuis les sources d'un sous-module git (décisions D2 et D3).
- Dépôts autorisés : Maven Central et Google Maven.
- Versions dans `gradle/libs.versions.toml`. Une montée de version est un commit à part.

### Magasin de confiance

Les fichiers de `core/src/main/resources/trust/` ne se modifient qu'en suivant la procédure de [`docs/trust-store.md`](docs/trust-store.md) : source officielle, empreinte publiée, mise à jour des empreintes attendues par le test. Un fichier embarqué est toujours redistribué sans modification.

## Commandes

Les sources d'OpenJPEG sont un sous-module git : clonez avec `git clone --recurse-submodules`, ou, dans un clone existant, lancez une fois `git submodule update --init`. La compilation native demande le NDK `28.2.13676358` et CMake `4.1.2` (installables par le SDK Manager ou `sdkmanager "ndk;28.2.13676358" "cmake;4.1.2"`).

```bash
./gradlew :core:test                                                   # tests JVM du cœur
./gradlew :core:test --tests "io.github.mgdx.sceau.core.SomeTest"       # un seul test
./gradlew :testchip:test                                               # tests de la puce simulée
./gradlew :app:testDebugUnitTest                                        # tests JVM de l'app (dont le mode démo)
./gradlew :app:assembleDebug                                            # APK de debug (mode démo inclus)
./gradlew :app:assembleRelease                                          # APK release (R8), non signé
./gradlew lint ktlintCheck                                              # lint et style
./gradlew ktlintFormat                                                  # reformate selon .editorconfig
./gradlew check                                                         # tout ce qui bloque la CI
./gradlew :app:dependencies --configuration releaseRuntimeClasspath     # audit des dépendances
app/src/main/cpp/test/run-host-tests.sh                                 # tests hôte du décodeur JPEG 2000 (ASan, UBSan)
scripts/update-trust-store.sh --ants-dir DOSSIER [--apply]              # vérifie les sources du magasin de confiance (à chaque release)
scripts/check-reproducible.sh                                           # double build release, APK identiques octet pour octet
```

`update-trust-store.sh` (bash, curl, openssl, unzip) n'écrit rien sans `--apply` ; voir la procédure de [`docs/trust-store.md`](docs/trust-store.md).

Les APK release doivent rester reproductibles (décision D25, [`docs/reproducible-builds.md`](docs/reproducible-builds.md)) : tout changement du build (Gradle, CMake, versions d'outils ou de dépendances) passe par `scripts/check-reproducible.sh` avant une release.

Tout changement de `app/src/main/cpp/` (code C ou version d'OpenJPEG) passe par `run-host-tests.sh`, qui demande `cmake` et un compilateur C hôte avec AddressSanitizer. Le code C de Sceau est compilé avec `-Wall -Wextra -Wconversion -Werror`.

La CI (`.github/workflows/ci.yml`) récupère le sous-module OpenJPEG puis exécute `./gradlew check :app:assembleDebug`. Lancez `./gradlew check` avant de proposer une contribution.

Toolchain : JDK 17 pour la compilation, JDK 25 pour le démon Gradle.

## Commits

Format : `type(portée): description`, en français, au présent, sans point final. Un commit = un changement cohérent.

| Type | Usage |
|---|---|
| `feat` | nouvelle fonctionnalité |
| `fix` | correction de bogue |
| `docs` | documentation seule |
| `test` | ajout ou correction de tests |
| `refactor` | restructuration sans changement de comportement |
| `chore` | build, dépendances, CI, outillage |

Portées usuelles : `core`, `app`, `trust`, `reading`, `result`, `home`, `about`, `nfc`, `jp2`, `sim` (`:testchip`), `debug` (mode démo), `socle`, `ci`. Exemple : `fix(core): distinguer DG15 absent d'un échec d'Active Authentication`.

Le corps du message explique le pourquoi, et cite le cas échéant la section de la SPEC ou la décision de `docs/decisions.md` concernée.

## Proposer une contribution

1. Créez une branche depuis `main`.
2. Faites vos changements en respectant les règles ci-dessus, avec les tests et la documentation correspondants (`docs/`, et `fastlane/metadata/` si une fonctionnalité visible change).
3. Vérifiez `./gradlew check`.
4. Ouvrez une demande de fusion décrivant le changement, sa justification et la façon dont il a été vérifié (tests, et cas de [`docs/test-plan.md`](docs/test-plan.md) exécutés sur appareil le cas échéant).

En contribuant, vous acceptez que votre contribution soit distribuée sous la licence du projet, GNU GPL version 3 ou ultérieure (GPL-3.0-or-later).
