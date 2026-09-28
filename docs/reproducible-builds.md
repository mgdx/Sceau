# Builds reproductibles

Objectif : que F-Droid reconstruise depuis les sources des APK release **identiques octet pour octet** à ceux construits par le développeur, afin de publier les APK signés par le développeur ([Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)). Décision D25.

## Ce qui est garanti

À commit identique et avec les versions d'outils ci-dessous, `./gradlew :app:assembleRelease` produit les mêmes cinq APK non signés (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, universel), quel que soit :

- le chemin du clone et la profondeur de ce chemin ;
- le chemin d'installation du SDK Android et du NDK.

Vérifié le 2026-09-29 sur Linux x86_64 (voir D25 pour les empreintes). La signature n'est pas couverte : F-Droid compare son APK non signé à l'APK signé du développeur privé de sa signature (voir plus bas).

Deux causes d'écart ont été corrigées :

| Écart | Cause | Correctif |
|---|---|---|
| `lib/<abi>/libsceau_jp2.so` : 20 octets (note `.note.gnu.build-id`) | le build-id est l'empreinte du `.so` avant le strip, dont les informations de débogage contiennent les chemins absolus du clone, du répertoire `.cxx` et des en-têtes du NDK | `-ffile-prefix-map` pour ces trois racines dans `app/src/main/cpp/CMakeLists.txt`, appliqué à OpenJPEG comme au code de Sceau |
| bloc « dependency metadata » dans le bloc de signature (APK signés seulement) | ajouté par AGP, chiffré avec une clé de Google, refusé par F-Droid | `dependenciesInfo { includeInApk = false; includeInBundle = false }` dans `app/build.gradle.kts` |

La bibliothèque livrée reste strippée (ni `.symtab` ni `.debug_*`) ; le build-id est conservé, désormais stable.

Contenus vérifiés et déjà stables : `classes.dex` (R8), `resources.arsc`, `assets/dexopt/baseline.prof{,m}`, ordre et horodatages des entrées ZIP (AGP les fixe), `META-INF/version-control-info.textproto`. Ce dernier contient le hash du commit construit : deux commits différents donnent donc des APK différents, ce qui est voulu.

## Versions à figer

Une montée de l'une de ces versions change les APK : le développeur et F-Droid doivent construire avec les mêmes.

| Outil | Version | Où elle est fixée |
|---|---|---|
| Gradle | 9.5.0 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP (R8, D8, aapt2, zipalign internes) | 9.3.3 | `gradle/libs.versions.toml` |
| Plugins Kotlin (compilateur intégré à AGP 9 pour `:app`) | `kotlin` | `gradle/libs.versions.toml` |
| JDK de compilation (toolchain) | 17 | `kotlin { jvmToolchain(17) }` |
| JDK du démon Gradle (exécute R8) | 25 | `gradle/gradle-daemon-jvm.properties` |
| NDK | 28.2.13676358 | `ndkVersion` dans `app/build.gradle.kts` |
| CMake | 4.1.2 | `externalNativeBuild.cmake.version` |
| OpenJPEG | commit `8314119b` | sous-module `app/src/main/cpp/openjpeg` |
| Dépendances Maven | versions exactes | `gradle/libs.versions.toml` |

Les build-tools du SDK ne sont pas utilisés pour produire l'APK (aapt2 vient de Maven avec AGP) : leur version n'a pas d'effet.

## Vérifier

```bash
scripts/check-reproducible.sh            # commit HEAD
scripts/check-reproducible.sh v1.0       # une révision donnée
KEEP=1 scripts/check-reproducible.sh     # garde les deux arbres pour enquêter
```

Le script clone deux fois le dépôt au commit demandé (sous-module compris), dans deux répertoires de chemins différents, le second voyant le SDK par un lien symbolique (autre chemin de NDK). Il construit `:app:assembleRelease` dans chacun, compare les APK par `cmp` et affiche leur SHA-256 ; en cas d'écart, il liste les entrées de l'APK qui diffèrent. Il ne dépend que de `git`, `bash`, `cmp`, `sha256sum`, `unzip` et `diff`. Sur un `.so` qui diffère, `readelf -n` (build-id) et `strings -a` sur la version non strippée de `app/build/intermediates/cxx/` montrent les chemins en cause ; `diffoscope` fait l'analyse complète s'il est installé.

Le script ne vérifie que ce qui est commité : les changements locaux non commités sont ignorés.

## Côté F-Droid

À renseigner dans la recette fdroiddata à la soumission (non fait ici) : `Binaries:` pointe vers l'URL des APK signés publiés par le développeur, et `AllowedAPKSigningKeys:` donne l'empreinte SHA-256 du certificat de signature. F-Droid construit l'APK depuis le tag, recopie la signature de l'APK du développeur sur son propre APK, vérifie la signature obtenue, et ne publie l'APK du développeur que si elle est valide, c'est-à-dire si le contenu est identique. Une recette par ABI (`VercodeOperation` et `gradle` avec le split voulu) est à prévoir pour les APK par architecture.
