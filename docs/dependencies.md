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
| `androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-compose` | 1.6.2 | Apache 2.0 (POM de chaque artefact, vérifié le 2026-10-04 sur Google Maven) | Scan de la MRZ (D32) : caméra arrière, cas d'usage Aperçu et Analyse d'image seulement (`ImageCapture` jamais lié), aperçu en Compose (`CameraXViewfinder`) | oui (Google Maven, AndroidX libre, utilisé par des applications du dépôt F-Droid) ; voir la remarque sur les bibliothèques natives de `camera-core` en section 2 |

Licences Apache 2.0 des AndroidX vérifiées sur le `LICENSE.txt` embarqué dans l'AAR (`META-INF/androidx/…/LICENSE.txt`) ; celles de Kotlin et de kotlinx sur le POM publié sur Maven Central.

### APK de debug uniquement

Ces dépendances sont déclarées en `debugImplementation` : elles n'apparaissent pas dans `releaseRuntimeClasspath` et n'entrent pas dans l'APK release.

| Artefact | Version | Licence | Rôle |
|---|---|---|---|
| `androidx.compose.ui:ui-tooling` | 1.11.2 | Apache 2.0 | Outils de prévisualisation Compose |
| `project(":testchip")` | — | GPLv3 (code de Sceau) | Mode démo : CNIe simulée, PKI de test (décision D17). Tire JMRTD, SCUBA et BouncyCastle, déjà présents. |
| `androidx.compose.ui:ui-test-manifest` | 1.11.2 (BOM Compose) | Apache 2.0 (POM) | Déclare l'activité vide `androidx.activity.ComponentActivity` (exportée, sans contenu) qui accueille les tests Compose sous Robolectric (`TextOverflowTest`, D28). Rien d'autre : ni code, ni permission. Présente dans l'APK de debug seulement. |

Vérification : `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` ne mentionne pas `project :testchip`, `debugRuntimeClasspath` le mentionne (constaté le 2026-09-25).

### Code natif compilé depuis les sources : décodeur JPEG 2000

| Composant | Version | Licence | Rôle | F-Droid |
|---|---|---|---|---|
| OpenJPEG (bibliothèque `openjp2` seule) | 2.5.4+ (`master`, commit `8314119b`, `v2.5.4-29` : correctifs de sécurité postérieurs au tag, voir D3) | BSD-2-Clause | Décodage des portraits JPEG 2000 (DG2, DG12), via `Jpeg2000Decoder` et `libsceau_jp2.so` | oui : sous-module git `app/src/main/cpp/openjpeg` pointant sur le dépôt officiel `github.com/uclouvain/openjpeg`, compilé depuis les sources par CMake et le NDK à chaque build, lié statiquement ; aucun binaire précompilé dans le dépôt |

Ce composant n'est pas une dépendance Maven : il n'apparaît pas dans la sortie de `./gradlew :app:dependencies`. Il remplace jj2000, écarté car sa licence d'origine restreint le champ d'usage (non libre, incompatible GPLv3 et F-Droid) : voir la décision D3. Outils de compilation correspondants : NDK `28.2.13676358` et CMake `4.1.2` (section 4).

Montée de version : se placer dans le sous-module, `git fetch --tags && git checkout vX.Y.Z`, committer le nouveau pointeur, relancer `app/src/main/cpp/test/run-host-tests.sh` et mettre à jour ce tableau et la décision D3.

## 2. Dépendances transitives notables

| Artefact | Version | Licence | Tiré par | Remarque |
|---|---|---|---|---|
| `org.bouncycastle:bcutil-jdk18on` | 1.86 | Licence Bouncy Castle (MIT) | bcpkix, JMRTD | Structures ASN.1 communes |
| `org.ejbca.cvc:cert-cvc` | 1.4.13 | LGPL 2.1 (POM : « LGPL license, Version 2.1 ») | JMRTD | Certificats à vérification de carte (CV), utilisés par JMRTD pour Terminal Authentication, que Sceau n'exécute pas. Retiré de l'APK par R8 : Sceau n'atteint aucun de ses chemins (D24). |
| `com.google.guava:listenablefuture` | 1.0 | Apache 2.0 (en-tête des sources ; le POM hérite de `guava-parent`) | AndroidX (`concurrent-futures`) | Artefact vide de Guava contenant la seule interface `ListenableFuture`. Libre, n'est pas un service Google. |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.7.3 | Apache 2.0 | Navigation, Lifecycle | Sérialisation des routes et états de navigation. Aucune donnée lue n'est sérialisée. |
| `androidx.profileinstaller:profileinstaller` | 1.4.0 | Apache 2.0 | Compose | Profils de compilation ART, local à l'appareil, aucun réseau |
| `androidx.startup:startup-runtime` | 1.1.1 | Apache 2.0 | Lifecycle, emoji2 | Initialisation au démarrage |
| `androidx.emoji2:emoji2` | 1.4.0 | Apache 2.0 | Compose | Compatibilité emoji. Peut interroger un fournisseur de polices installé sur l'appareil (composant système, hors de Sceau) ; Sceau lui-même n'ouvre aucune connexion (aucune permission réseau). Largement présent dans les applications de F-Droid. |
| `androidx.window:window` | 1.5.0 | Apache 2.0 | Activity, Compose | Informations sur la fenêtre et les écrans pliables |
| `org.jspecify:jspecify` | 1.0.0 | Apache 2.0 | AndroidX | Annotations de nullité |
| `org.jetbrains:annotations` | 23.0.0 | Apache 2.0 | kotlin-stdlib, coroutines | Annotations, sans code exécuté |
| `androidx.camera:camera-core` | 1.6.2 | Apache 2.0 et BSD-3-Clause (POM : les deux licences, la seconde pour libyuv) | CameraX | Cœur de CameraX. **Contient deux bibliothèques natives précompilées** (`libimage_processing_util_jni.so`, conversion et rotation d'images par libyuv, et `libsurface_util_jni.so`), embarquées pour chaque ABI : environ 37 Ko par APK arm64. Elles sont construites par Google à partir des sources d'AndroidX (AOSP), comme `libandroidx.graphics.path.so` déjà présent via Compose ; F-Droid accepte les AAR AndroidX de Google Maven. À trancher par le superviseur au regard de la règle « aucun blob binaire » de `CLAUDE.md`. |
| `androidx.camera:camera-camera2-pipe` | 1.6.2 | Apache 2.0 (POM) | camera-camera2 | Pilotage de Camera2 |
| `androidx.camera.viewfinder:viewfinder-compose`, `viewfinder-core` | 1.6.2 | Apache 2.0 (POM) | camera-compose | Surface d'aperçu en Compose |
| `androidx.camera.featurecombinationquery:featurecombinationquery` | 1.6.2 | Apache 2.0 (POM) | CameraX | Interrogation locale des combinaisons de flux prises en charge ; aucun réseau |
| `com.google.dagger:dagger` | 2.59 | Apache 2.0 (POM) | camera-camera2-pipe | Injection de dépendances à la compilation, bibliothèque d'exécution minimale. Libre, n'est pas un service Google. |
| `javax.inject:javax.inject` 1, `jakarta.inject:jakarta.inject-api` 2.0.1 | — | Apache 2.0 (POM) | dagger | Annotations `@Inject` |
| `com.google.auto.value:auto-value-annotations` | 1.6.3 | Apache 2.0 (en-tête du POM parent `auto-value-parent`) | CameraX | Annotations, sans code exécuté |
| `org.jetbrains.kotlinx:atomicfu` | 0.28.0 | Apache 2.0 (POM) | CameraX | Opérations atomiques Kotlin |
| `androidx.exifinterface:exifinterface` | 1.4.2 | Apache 2.0 (POM) | CameraX | Lecture et écriture de métadonnées EXIF ; Sceau ne produit aucune image, rien n'est écrit |

Autres transitives : bibliothèques AndroidX (`annotation`, `collection`, `arch.core`, `savedstate`, `navigationevent`, `customview-poolingcontainer`, `autofill`, `graphics-path`, `tracing`, `transition`, `dynamicanimation`, `interpolator`, `versionedparcelable`, `legacy-support-core-utils`, `loader`, `localbroadcastmanager`, `print`, `documentfile`, `concurrent-futures`, `concurrent-futures-ktx`, `core-backported-fixes`, `tracing-android`, `tracing-ktx`, `core-viewtree`) et modules Compose (`runtime`, `runtime-saveable`, `runtime-retain`, `runtime-annotation`, `animation`, `animation-core`, `foundation`, `foundation-layout`, `material-ripple`, `ui-geometry`, `ui-text`, `ui-unit`, `ui-util`) : Apache 2.0, publiées sur Google Maven, libres. Ces transitives n'ont pas été contrôlées une à une ; la licence Apache 2.0 a été constatée sur l'AAR ou le POM de chaque dépendance directe AndroidX et Compose et de `emoji2`, `profileinstaller`, `startup-runtime` et `window`.

Aucune dépendance ne relève de Google Play Services, Firebase, d'une bibliothèque d'analyse, de publicité ou de rapport de plantage.

Ajout de CameraX (D32, 2026-10-04) : la comparaison de `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` avant et après n'introduit que les artefacts listés ci-dessus et des montées de version AndroidX déjà présentes (`arch.core` 2.2.0, `tracing` 1.3.0) ; aucun artefact `com.google.android.gms`, Firebase, ML Kit ou propriétaire. `com.google.guava:listenablefuture` était déjà présent. Les règles R8 consommateur de CameraX suffisent : aucun ajout à `app/src/main/keepRules/rules.keep`. APK release arm64 : 4 178 251 octets avant, 4 688 067 après (+0,49 Mo).

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
| Robolectric `org.robolectric:robolectric` (tests uniquement, D28) | 4.17 | MIT (POM, comme `nativeruntime` 4.17) |
| Jars Android de Robolectric `org.robolectric:android-all-instrumented` (téléchargés par Robolectric au premier lancement des tests, depuis Maven Central) | 17-robolectric-15733970-i7 (Android 37) | Apache 2.0 (code d'AOSP) |
| Compose UI test `androidx.compose.ui:ui-test-junit4` (tests uniquement, D28) | 1.11.2 (BOM Compose) | Apache 2.0 (POM) |
| Espresso `androidx.test.espresso:espresso-core` (tests uniquement, D28) | 3.7.0 | Apache 2.0 (POM de Google Maven, en-têtes du jar `-sources`) |
| Plugin `org.gradle.toolchains.foojay-resolver-convention` | 1.0.0 | non vérifiée sur l'artefact : le POM publié ne déclare pas de licence (Apache 2.0 selon le dépôt amont, à confirmer) |
| Android NDK (compilation de `libsceau_jp2.so`) | 28.2.13676358 | Apache 2.0 (Clang/LLVM : Apache 2.0 avec exception LLVM) |
| CMake (compilation native) | 4.1.2 | BSD-3-Clause |

Robolectric, Compose UI test et Espresso ne servent qu'au contrôle des débordements de texte (`app/src/test/.../l10n/TextOverflowTest.kt`, D28) et n'entrent dans aucun APK : `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` n'en mentionne aucun, ni `ui-test-manifest` (constaté le 2026-09-29). Le rendu natif de Robolectric (`nativeruntime`) contient des bibliothèques précompilées, exécutées seulement par les tests JVM : F-Droid construit l'APK sans lancer les tests. Espresso 3.7.0 est imposé parce que la version 3.5.0 tirée par `ui-test-junit4` appelle `InputManager.getInstance()`, absent d'Android 37. Les tests de `:app` tournent sur le JDK 25 (celui du démon Gradle et de la CI) : Robolectric exige Java 21 ou plus pour Android 37 ; le code reste compilé pour Java 17.
