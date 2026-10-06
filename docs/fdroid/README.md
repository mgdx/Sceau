# Recette F-Droid

`io.github.mgdx.sceau.yml` est le brouillon de la recette à proposer à [fdroiddata](https://gitlab.com/fdroid/fdroiddata) (fichier `metadata/io.github.mgdx.sceau.yml`). Sa forme suit celle de la recette acceptée de Roue Libre, l'autre application de l'auteur. Choix et justification : D25 (builds reproductibles) et D38 (APK universel seul pour F-Droid) dans [`../decisions.md`](../decisions.md).

## Principe

F-Droid construit l'APK **universel** depuis le tag (`app-universal-release-unsigned.apk`), télécharge l'APK signé par l'auteur (champ `binary:`), recopie sa signature sur son propre APK et vérifie le résultat. Si le contenu est identique octet pour octet, F-Droid publie l'APK de l'auteur, avec sa signature : une installation venue de F-Droid et une autre venue des releases GitHub (même APK universel) peuvent alors se mettre à jour l'une l'autre. Détails : [`../reproducible-builds.md`](../reproducible-builds.md).

L'asset attendu sur la release GitHub s'appelle **`sceau-<version>-universal.apk`** (par exemple `sceau-0.9-universal.apk`, URL `https://github.com/mgdx/Sceau/releases/download/v0.9/sceau-0.9-universal.apk`). Ce nom est fixé par [`../release.md`](../release.md) : le changer impose de changer la recette.

## Remplir avant soumission

1. **`commit:`** : hash complet du commit du tag, jamais le nom du tag (un tag peut être déplacé) :

   ```bash
   git rev-parse 'v0.9^{commit}'
   ```

2. **`AllowedAPKSigningKeys:`** : empreinte SHA-256 du certificat de signature, en minuscules, sans `:` ni espace :

   ```bash
   apksigner verify --print-certs sceau-0.9-universal.apk | grep 'SHA-256'
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

Les besoins d'environnement listés en tête de la recette (JDK 25 du démon Gradle, CMake 4.1.2, NDK r28c) sont à confirmer à cette étape ; ajouter un `sudo:` ou un `prebuild:` si le serveur de build ne les fournit pas. Puis ouvrir une merge request sur fdroiddata (branche nommée d'après l'identifiant de l'application), en joignant le rapport du skill `android-fdroid`.

Pour une nouvelle version, F-Droid ajoute lui-même le bloc `Builds` (`AutoUpdateMode: Version`, tags `v0.9`, `v1.0`, `v1.0.1`…), à condition que l'asset `sceau-<version>-universal.apk` soit publié sous ce nom.
