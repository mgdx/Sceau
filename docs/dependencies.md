# Dépendances

Toute dépendance embarquée dans l'APK est listée ici avec sa version, sa licence, son rôle et sa compatibilité F-Droid (SPEC §3). Une nouvelle dépendance n'est acceptée qu'après ajout d'une ligne dans ce fichier.

Critères F-Droid appliqués à chaque ligne : code source libre, publiée sur un dépôt Maven standard (Maven Central ou Google Maven), reconstructible, sans binaire natif précompilé, sans service Google, sans télémétrie ni publicité. Toutes les licences retenues sont compatibles avec la GPLv3 de Sceau.

Liste établie le 2026-09-25 à partir de :

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Les versions sont celles **résolues** par Gradle (après arbitrage des conflits). Les versions déclarées sont dans `gradle/libs.versions.toml`. Relancer la commande à chaque montée de version et mettre ce fichier à jour dans le même commit.

Les licences ont été vérifiées le 2026-09-25 **sur les artefacts eux-mêmes**, jamais de mémoire : section `<licenses>` du POM (cache Gradle `~/.gradle/caches/modules-2/files-2.1/` ou dépôt Maven d'origine), fichier de licence contenu dans le jar ou l'AAR, ou, quand le POM ne précise pas la version de la licence, en-têtes des fichiers source du jar `-sources`. La colonne « Licence » indique la source quand elle n'est pas le POM.

## 1. Dépendances directes

### Module `:core`

| Artefact | Version | Licence | Rôle | F-Droid |
|---|---|---|---|---|
| `org.jmrtd:jmrtd` | 0.8.8 | LGPL 2.1 ou ultérieure (POM : « LGPL » sans version ; en-têtes des sources : « version 2.1 of the License, or (at your option) any later version ») | PACE, BAC, messagerie sécurisée, parsing de la LDS (EF.COM, SOD, DG), CA, AA | oui, Maven Central, pur Java |
| `net.sf.scuba:scuba-smartcards` | 0.0.21 | LGPL 2.1 ou ultérieure (idem) | Abstraction APDU (`CardService`) utilisée par JMRTD | oui, pur Java |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | Licence Bouncy Castle (POM), texte MIT (`META-INF/LICENSE.md` du jar) | Fournisseur JCA : RSA, RSA-PSS, ECDSA, courbes Brainpool, DH/ECDH, empreintes | oui, pur Java |
| `org.bouncycastle:bcpkix-jdk18on` | 1.86 | Licence Bouncy Castle (MIT) | X.509, CMS (SOD, Master Lists), construction de chaînes | oui, pur Java |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Apache 2.0 | `suspend fun readAndVerify`, exécution sur `Dispatchers.IO` | oui |

SPEC §3 et `CLAUDE.md` donnaient JMRTD et SCUBA sous « LGPL 3 » : l'artefact indique LGPL 2.1 ou toute version ultérieure. La conclusion ne change pas : la clause « ou ultérieure » permet de les utiliser sous LGPL 3, compatible avec la GPLv3 de Sceau.

Seuls les artefacts BouncyCastle `jdk18on` sont admis : les variantes `jdk15on` et `jdk15to18`, qui dupliqueraient les classes, sont exclues dans les `build.gradle.kts`. SpongyCastle n'est jamais utilisé (SPEC §3).

### Module `:app`

| Artefact | Version | Licence | Rôle | F-Droid |
|---|---|---|---|---|
| `project(":core")` | — | GPLv3 | Cœur de Sceau | oui |
| `net.sf.scuba:scuba-sc-android` | 0.0.27 | LGPL 2.1 ou ultérieure (POM « LGPL », en-têtes des sources) | Pont entre `IsoDep` d'Android et SCUBA | oui, pur Java |
| `androidx.compose:compose-bom` | 2026.02.01 | Apache 2.0 | Alignement des versions Compose | oui (Google Maven, AndroidX libre) |
| `androidx.compose.ui:ui`, `ui-graphics`, `ui-tooling-preview` | 1.11.2 | Apache 2.0 | Jetpack Compose | oui |
| `androidx.compose.material3:material3` | 1.4.0 | Apache 2.0 | Composants Material 3 | oui |
| `androidx.activity:activity-compose` | 1.13.0 | Apache 2.0 | `setContent`, `LocalActivity`, retour arrière | oui |
| `androidx.navigation:navigation-compose` | 2.10.2 | Apache 2.0 | Navigation entre écrans | oui |
| `androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose` | 2.11.0 | Apache 2.0 | `ViewModel`, cycle de vie (effacement en arrière-plan) | oui |
| `androidx.core:core-ktx` | 1.19.0 | Apache 2.0 | Utilitaires AndroidX | oui |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache 2.0 | `Dispatchers.Main` | oui |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.20 | Apache 2.0 | Bibliothèque standard Kotlin | oui |

Licences Apache 2.0 des AndroidX vérifiées sur le `LICENSE.txt` embarqué dans l'AAR (`META-INF/androidx/…/LICENSE.txt`) ; celles de Kotlin et de kotlinx sur le POM publié sur Maven Central.

### APK de debug uniquement

Ces dépendances sont déclarées en `debugImplementation` : elles n'apparaissent pas dans `releaseRuntimeClasspath` et n'entrent pas dans l'APK release.

| Artefact | Version | Licence | Rôle |
|---|---|---|---|
| `androidx.compose.ui:ui-tooling` | 1.11.2 | Apache 2.0 | Outils de prévisualisation Compose |
| `project(":testchip")` | — | GPLv3 (code de Sceau) | Mode démo : CNIe simulée, PKI de test (décision D17). Tire JMRTD, SCUBA et BouncyCastle, déjà présents. |

Vérification : `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` ne mentionne pas `project :testchip`, `debugRuntimeClasspath` le mentionne (constaté le 2026-09-25).

### Code natif compilé depuis les sources : décodeur JPEG 2000

| Composant | Version | Licence | Rôle | F-Droid |
|---|---|---|---|---|
| OpenJPEG (bibliothèque `openjp2` seule) | 2.5.4 (tag `v2.5.4`, commit `6c4a29b0`) | BSD-2-Clause | Décodage des portraits JPEG 2000 (DG2, DG12), via `Jpeg2000Decoder` et `libsceau_jp2.so` | oui : sous-module git `app/src/main/cpp/openjpeg` pointant sur le dépôt officiel `github.com/uclouvain/openjpeg`, compilé depuis les sources par CMake et le NDK à chaque build, lié statiquement ; aucun binaire précompilé dans le dépôt |

Ce composant n'est pas une dépendance Maven : il n'apparaît pas dans la sortie de `./gradlew :app:dependencies`. Il remplace jj2000, écarté car sa licence d'origine restreint le champ d'usage (non libre, incompatible GPLv3 et F-Droid) : voir la décision D3. Outils de compilation correspondants : NDK `28.2.13676358` et CMake `4.1.2` (section 4).

Montée de version : se placer dans le sous-module, `git fetch --tags && git checkout vX.Y.Z`, committer le nouveau pointeur, relancer `app/src/main/cpp/test/run-host-tests.sh` et mettre à jour ce tableau et la décision D3.

## 2. Dépendances transitives notables

| Artefact | Version | Licence | Tiré par | Remarque |
|---|---|---|---|---|
| `org.bouncycastle:bcutil-jdk18on` | 1.86 | Licence Bouncy Castle (MIT) | bcpkix, JMRTD | Structures ASN.1 communes |
| `org.ejbca.cvc:cert-cvc` | 1.4.13 | LGPL 2.1 (POM : « LGPL license, Version 2.1 ») | JMRTD | Certificats à vérification de carte (CV), utilisés par JMRTD pour Terminal Authentication, que Sceau n'exécute pas. Conservé par les règles R8 car JMRTD le référence. |
| `com.google.guava:listenablefuture` | 1.0 | Apache 2.0 (en-tête des sources ; le POM hérite de `guava-parent`) | AndroidX (`concurrent-futures`) | Artefact vide de Guava contenant la seule interface `ListenableFuture`. Libre, n'est pas un service Google. |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.7.3 | Apache 2.0 | Navigation, Lifecycle | Sérialisation des routes et états de navigation. Aucune donnée lue n'est sérialisée. |
| `androidx.profileinstaller:profileinstaller` | 1.4.0 | Apache 2.0 | Compose | Profils de compilation ART, local à l'appareil, aucun réseau |
| `androidx.startup:startup-runtime` | 1.1.1 | Apache 2.0 | Lifecycle, emoji2 | Initialisation au démarrage |
| `androidx.emoji2:emoji2` | 1.4.0 | Apache 2.0 | Compose | Compatibilité emoji. Peut interroger un fournisseur de polices installé sur l'appareil (composant système, hors de Sceau) ; Sceau lui-même n'ouvre aucune connexion (aucune permission réseau). Largement présent dans les applications de F-Droid. |
| `androidx.window:window` | 1.5.0 | Apache 2.0 | Activity, Compose | Informations sur la fenêtre et les écrans pliables |
| `org.jspecify:jspecify` | 1.0.0 | Apache 2.0 | AndroidX | Annotations de nullité |
| `org.jetbrains:annotations` | 23.0.0 | Apache 2.0 | kotlin-stdlib, coroutines | Annotations, sans code exécuté |

Autres transitives : bibliothèques AndroidX (`annotation`, `collection`, `arch.core`, `savedstate`, `navigationevent`, `customview-poolingcontainer`, `autofill`, `graphics-path`, `tracing`, `transition`, `dynamicanimation`, `interpolator`, `versionedparcelable`, `legacy-support-core-utils`, `loader`, `localbroadcastmanager`, `print`, `documentfile`, `concurrent-futures`, `core-viewtree`) et modules Compose (`runtime`, `runtime-saveable`, `runtime-retain`, `runtime-annotation`, `animation`, `animation-core`, `foundation`, `foundation-layout`, `material-ripple`, `ui-geometry`, `ui-text`, `ui-unit`, `ui-util`) : Apache 2.0, publiées sur Google Maven, libres. Ces transitives n'ont pas été contrôlées une à une ; la licence Apache 2.0 a été constatée sur l'AAR ou le POM de chaque dépendance directe AndroidX et Compose et de `emoji2`, `profileinstaller`, `startup-runtime` et `window`.

Aucune dépendance ne relève de Google Play Services, Firebase, d'une bibliothèque d'analyse, de publicité ou de rapport de plantage.

## 3. Contenu embarqué qui n'est pas du code

Le magasin de confiance de `core/src/main/resources/trust/` est composé de données publiques, pas de bibliothèques (décision D1) :

- certificats CSCA de l'ANTS, publiés sur ants.gouv.fr ;
- Master List allemande publiée par le BSI, redistribuée sans modification dans le respect de ses conditions de réutilisation (décision D1 dans `docs/decisions.md`).

Provenance et empreintes : `docs/trust-store.md`.

## 4. Outils de build (hors APK)

| Outil | Version | Licence |
|---|---|---|
| Android Gradle Plugin | 9.3.3 | Apache 2.0 |
| Kotlin (plugin Compose, plugin JVM) | 2.2.10 | Apache 2.0 |
| ktlint (`com.pinterest.ktlint`) | 1.8.0 | MIT (POM de `ktlint-cli`) |
| Plugin Gradle `org.jlleitschuh.gradle.ktlint` | 14.2.0 | non vérifiée sur l'artefact : le POM publié ne déclare pas de licence (MIT selon le dépôt amont, à confirmer) |
| JUnit (tests uniquement) | 4.13.2 | EPL 1.0 (`LICENSE-junit.txt` du jar) |
| Hamcrest Core (tests uniquement, tiré par JUnit) | 1.3 | BSD (`LICENSE.txt` du jar) |
| kotlinx-coroutines-test (tests uniquement) | 1.11.0 | Apache 2.0 |
| Plugin `org.gradle.toolchains.foojay-resolver-convention` | 1.0.0 | non vérifiée sur l'artefact : le POM publié ne déclare pas de licence (Apache 2.0 selon le dépôt amont, à confirmer) |
| Android NDK (compilation de `libsceau_jp2.so`) | 28.2.13676358 | Apache 2.0 (Clang/LLVM : Apache 2.0 avec exception LLVM) |
| CMake (compilation native) | 4.1.2 | BSD-3-Clause |
