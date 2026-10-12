# Recette F-Droid

`io.github.mgdx.sceau.yml` est le brouillon de la recette à proposer à [fdroiddata](https://gitlab.com/fdroid/fdroiddata) (fichier `metadata/io.github.mgdx.sceau.yml`). Sa forme suit celle de la recette acceptée de Roue Libre, l'autre application de l'auteur. Choix et justification : D25 (builds reproductibles) et D38 (APK universel seul pour F-Droid) dans [`../decisions.md`](../decisions.md).

## Principe

F-Droid construit l'APK **universel** depuis le tag (`app-universal-release-unsigned.apk`), télécharge l'APK signé par l'auteur (champ `binary:`), recopie sa signature sur son propre APK et vérifie le résultat. Si le contenu est identique octet pour octet, F-Droid publie l'APK de l'auteur, avec sa signature : une installation venue de F-Droid et une autre venue des releases GitHub (même APK universel) peuvent alors se mettre à jour l'une l'autre. Détails : [`../reproducible-builds.md`](../reproducible-builds.md).

L'asset attendu sur la release GitHub s'appelle **`sceau-<version>-universal.apk`** (par exemple `sceau-1.0.0-universal.apk`, URL `https://github.com/mgdx/Sceau/releases/download/v1.0.0/sceau-1.0.0-universal.apk`). Ce nom est fixé par [`../release.md`](../release.md) : le changer impose de changer la recette.

## Manifeste

L'APK release ne demande que `android.permission.NFC` et n'exporte qu'un composant, `MainActivity` : la permission interne d'androidx.core (D7), le récepteur exporté `ProfileInstallReceiver` et les initialiseurs de profileinstaller et d'emoji2 (D46) sont retirés du manifeste fusionné. À contrôler sur l'APK universel avant soumission :

```bash
aapt2 dump xmltree --file AndroidManifest.xml app-universal-release-unsigned.apk | grep -B2 exported
```

## Environnement de build

Le serveur de build de F-Droid (Debian trixie) ne fournit ni les JDK ni le CMake du projet, et F-Droid refuse tout JDK binaire téléchargé pendant le build. La recette les installe elle-même (D49) :

- **`sudo:`** : JDK 17 (toolchains `jvmToolchain(17)` des trois modules), pris dans bookworm puisque trixie ne l'a plus, et JDK 25 (démon Gradle, `gradle/gradle-daemon-jvm.properties`), puis `update-java-alternatives -a`. Gradle trouve seul les JDK installés dans `/usr/lib/jvm`.
- **`prebuild:`** (lancé depuis `app/`, d'où les `../`) : retrait du plugin `foojay-resolver-convention` de `settings.gradle.kts` et `org.gradle.java.installations.auto-download=false` ajouté à `gradle.properties`, pour qu'un JDK manquant fasse échouer le build au lieu d'être téléchargé (les `toolchainUrl` du démon pointent aussi vers foojay) ; `sdkmanager 'cmake;4.1.2'` (`externalNativeBuild.cmake.version`). Le NDK r28c vient du champ `ndk:`.

Ces modifications ne changent pas l'APK. Vérifié dans un clone : `./gradlew :app:assembleRelease --offline` après le prebuild donne un `app-universal-release-unsigned.apk` identique octet pour octet (`cmp`) à celui construit sans. Le build local et la CI gardent foojay.

## Remplir avant soumission

1. **`commit:`** : hash complet du commit du tag, jamais le nom du tag (un tag peut être déplacé) :

   ```bash
   git rev-parse 'v1.0.0^{commit}'
   ```

2. **`AllowedAPKSigningKeys:`** : empreinte SHA-256 du certificat de signature, en minuscules, sans `:` ni espace :

   ```bash
   apksigner verify --print-certs sceau-1.0.0-universal.apk | grep 'SHA-256'
   ```

   Elle ne change jamais tant que le keystore est le même (`docs/release.md`, étape « Keystore »).

3. **`versionName`, `versionCode`, `CurrentVersion`, `CurrentVersionCode`** : ceux de `app/build.gradle.kts` au tag.

## Vérifier, puis soumettre

Dans un clone de fdroiddata avec `fdroidserver` installé :

```bash
cp …/Sceau/docs/fdroid/io.github.mgdx.sceau.yml metadata/
fdroid readmeta && fdroid rewritemeta io.github.mgdx.sceau   # syntaxe et ordre canonique des champs
fdroid lint io.github.mgdx.sceau
fdroid build -v -l io.github.mgdx.sceau                       # construit et compare à l'APK de l'auteur
```

Ce qu'il faut sur le poste pour que `fdroid build` aboutisse (constaté le 2026-10-12, D54) :

- **fdroidserver de master** dans un virtualenv (`pip install git+https://gitlab.com/fdroid/fdroidserver.git apksigcopier platformdirs`) : c'est ce que tourne la CI de fdroiddata. Pour `rewritemeta`, un second virtualenv avec `fdroidserver` publié et `ruamel.yaml==0.18.6` (version de Debian trixie) : les versions récentes de ruamel replient les longues valeurs autrement que la CI (leçon de Roue Libre) ;
- **`gradlew-fdroid`** (dépôt https://gitlab.com/fdroid/gradlew-fdroid, `gradlew.py`, qui vérifie l'empreinte de la distribution Gradle) derrière un script `gradle`, désigné par `gradle:` dans `config.yml` ;
- dans `config.yml`, **`ndk_paths: {r28c: …/ndk/28.2.13676358}`** ;
- un **`sdkmanager` lancé sous JDK 21** : celui des cmdline-tools plante sous JDK 25 (`NumberFormatException` dans `Properties.store`) ;
- ces ajouts à `config.yml` restent locaux : le restaurer avant tout commit dans fdroiddata.

Le scanner refuse un chemin `scandelete` ou `scanignore` qui ne sert à rien (« Chemin scandelete inutilisé ») : la recette n'en porte aucun. Le résultat attendu est « compared built binary to supplied reference binary successfully ».

Puis ouvrir une merge request sur fdroiddata (branche `io.github.mgdx.sceau` du fork https://gitlab.com/mgdx/fdroiddata, titre « New app: Sceau », modèle « App inclusion »). Retours obtenus sur Roue Libre, déjà appliqués ici : hash de commit complet et jamais le tag, pas de `scanignore`, `subdir` renseigné.

Pour une nouvelle version, F-Droid ajoute lui-même le bloc `Builds` (`AutoUpdateMode: Version`, tags `v1.0.0`, `v1.0.1`, `v1.1.0`…), à condition que l'asset `sceau-<version>-universal.apk` soit publié sous ce nom.
