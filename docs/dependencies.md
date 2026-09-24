# Dépendances

Toute dépendance embarquée dans l'APK est listée ici avec sa version, sa licence, son rôle et sa compatibilité F-Droid (SPEC §3). Une nouvelle dépendance n'est acceptée qu'après ajout d'une ligne dans ce fichier.

Critères F-Droid appliqués à chaque ligne : code source libre, publiée sur un dépôt Maven standard (Maven Central ou Google Maven), reconstructible, sans binaire natif précompilé, sans service Google, sans télémétrie ni publicité. Toutes les licences retenues sont compatibles avec la GPLv3 de Sceau.

Liste établie le 2026-09-25 à partir de :

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Les versions sont celles **résolues** par Gradle (après arbitrage des conflits). Les versions déclarées sont dans `gradle/libs.versions.toml`. Relancer la commande à chaque montée de version et mettre ce fichier à jour dans le même commit.

## 1. Dépendances directes

### Module `:core`

| Artefact | Version | Licence | Rôle | F-Droid |
|---|---|---|---|---|
| `org.jmrtd:jmrtd` | 0.8.8 | LGPL (le POM ne précise pas la version ; SPEC §3 : LGPL 3) | PACE, BAC, messagerie sécurisée, parsing de la LDS (EF.COM, SOD, DG), CA, AA | oui, Maven Central, pur Java |
| `net.sf.scuba:scuba-smartcards` | 0.0.21 | LGPL (idem) | Abstraction APDU (`CardService`) utilisée par JMRTD | oui, pur Java |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | MIT (licence Bouncy Castle) | Fournisseur JCA : RSA, RSA-PSS, ECDSA, courbes Brainpool, DH/ECDH, empreintes | oui, pur Java |
| `org.bouncycastle:bcpkix-jdk18on` | 1.86 | MIT | X.509, CMS (SOD, Master Lists), construction de chaînes | oui, pur Java |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Apache 2.0 | `suspend fun readAndVerify`, exécution sur `Dispatchers.IO` | oui |

Seuls les artefacts BouncyCastle `jdk18on` sont admis : les variantes `jdk15on` et `jdk15to18`, qui dupliqueraient les classes, sont exclues dans les `build.gradle.kts`. SpongyCastle n'est jamais utilisé (SPEC §3).

### Module `:app`

| Artefact | Version | Licence | Rôle | F-Droid |
|---|---|---|---|---|
| `project(":core")` | — | GPLv3 | Cœur de Sceau | oui |
| `net.sf.scuba:scuba-sc-android` | 0.0.27 | LGPL | Pont entre `IsoDep` d'Android et SCUBA | oui, pur Java |
| `androidx.compose:compose-bom` | 2026.02.01 | Apache 2.0 | Alignement des versions Compose | oui (Google Maven, AndroidX libre) |
| `androidx.compose.ui:ui`, `ui-graphics`, `ui-tooling-preview` | 1.11.2 | Apache 2.0 | Jetpack Compose | oui |
| `androidx.compose.material3:material3` | 1.4.0 | Apache 2.0 | Composants Material 3 | oui |
| `androidx.activity:activity-compose` | 1.13.0 | Apache 2.0 | `setContent`, `LocalActivity`, retour arrière | oui |
| `androidx.navigation:navigation-compose` | 2.10.2 | Apache 2.0 | Navigation entre écrans | oui |
| `androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose` | 2.11.0 | Apache 2.0 | `ViewModel`, cycle de vie (effacement en arrière-plan) | oui |
| `androidx.core:core-ktx` | 1.19.0 | Apache 2.0 | Utilitaires AndroidX | oui |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache 2.0 | `Dispatchers.Main` | oui |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.20 | Apache 2.0 | Bibliothèque standard Kotlin | oui |

`androidx.compose.ui:ui-tooling` n'est présent qu'en `debugImplementation` : il n'entre pas dans l'APK release.

### À confirmer : décodeur JPEG 2000

| Artefact | Version | Licence | Rôle | F-Droid |
|---|---|---|---|---|
| `edu.ucar:jj2000` (envisagé) | 5.2 | licence JJ2000, de type BSD : **à confirmer** | Décodage des portraits JPEG 2000 (DG2, DG12), `Images.decodeJpeg2000` | pur Java, sans JNI, Maven Central : **à confirmer** |

SPEC §3 retient le fork JMRTD de jj2000, qui n'est pas publié sur Maven Central. L'artefact `edu.ucar:jj2000`, issu de la même base de code, est en cours d'évaluation (décision D3). Cette ligne sera confirmée ou remplacée après fusion de l'implémentation de la lecture, avec vérification du texte de licence.

## 2. Dépendances transitives notables

| Artefact | Version | Licence | Tiré par | Remarque |
|---|---|---|---|---|
| `org.bouncycastle:bcutil-jdk18on` | 1.86 | MIT | bcpkix, JMRTD | Structures ASN.1 communes |
| `org.ejbca.cvc:cert-cvc` | 1.4.13 | LGPL 2.1 | JMRTD | Certificats à vérification de carte (CV), utilisés par JMRTD pour Terminal Authentication, que Sceau n'exécute pas. Conservé par les règles R8 car JMRTD le référence. |
| `com.google.guava:listenablefuture` | 1.0 | Apache 2.0 | AndroidX (`concurrent-futures`) | Artefact vide de Guava contenant la seule interface `ListenableFuture`. Libre, n'est pas un service Google. |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.7.3 | Apache 2.0 | Navigation, Lifecycle | Sérialisation des routes et états de navigation. Aucune donnée lue n'est sérialisée. |
| `androidx.profileinstaller:profileinstaller` | 1.4.0 | Apache 2.0 | Compose | Profils de compilation ART, local à l'appareil, aucun réseau |
| `androidx.startup:startup-runtime` | 1.1.1 | Apache 2.0 | Lifecycle, emoji2 | Initialisation au démarrage |
| `androidx.emoji2:emoji2` | 1.4.0 | Apache 2.0 | Compose | Compatibilité emoji. Peut interroger un fournisseur de polices installé sur l'appareil (composant système, hors de Sceau) ; Sceau lui-même n'ouvre aucune connexion (aucune permission réseau). Largement présent dans les applications de F-Droid. |
| `androidx.window:window` | 1.5.0 | Apache 2.0 | Activity, Compose | Informations sur la fenêtre et les écrans pliables |
| `org.jspecify:jspecify` | 1.0.0 | Apache 2.0 | AndroidX | Annotations de nullité |
| `org.jetbrains:annotations` | 23.0.0 | Apache 2.0 | kotlin-stdlib, coroutines | Annotations, sans code exécuté |

Autres transitives : bibliothèques AndroidX (`annotation`, `collection`, `arch.core`, `savedstate`, `navigationevent`, `customview-poolingcontainer`, `autofill`, `graphics-path`, `tracing`, `transition`, `dynamicanimation`, `interpolator`, `versionedparcelable`, `legacy-support-core-utils`, `loader`, `localbroadcastmanager`, `print`, `documentfile`, `concurrent-futures`, `core-viewtree`) et modules Compose (`runtime`, `runtime-saveable`, `runtime-retain`, `runtime-annotation`, `animation`, `animation-core`, `foundation`, `foundation-layout`, `material-ripple`, `ui-geometry`, `ui-text`, `ui-unit`, `ui-util`) : toutes Apache 2.0, publiées sur Google Maven, libres.

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
| ktlint (plugin Gradle `org.jlleitschuh.gradle.ktlint` 14.2.0) | 1.8.0 | MIT |
| JUnit (tests uniquement) | 4.13.2 | EPL 1.0 |
| kotlinx-coroutines-test (tests uniquement) | 1.11.0 | Apache 2.0 |
| Plugin `org.gradle.toolchains.foojay-resolver-convention` | 1.0.0 | Apache 2.0 |
