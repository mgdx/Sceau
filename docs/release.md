# Publier une release

Procédure pas à pas pour publier une version de Sceau sur les releases GitHub (https://github.com/mgdx/Sceau/releases) puis sur F-Droid. Décisions : D2 (splits ABI), D25 (builds reproductibles), D38 (première release 0.9, licence, hébergement, APK universel pour F-Droid). Exemple pris : la version **0.9**.

Règles qui ne souffrent aucune exception :

- **aucun secret dans le dépôt** : ni keystore, ni mot de passe, ni `keystore.properties`. `*.jks` et `*.keystore` sont dans `.gitignore`, mais le keystore doit de toute façon vivre **hors** du clone ;
- **pas de `signingConfig` dans Gradle** : `assembleRelease` produit des APK non signés, signés ensuite à la main par `apksigner`. C'est ce qui permet à F-Droid de reconstruire les mêmes APK non signés et de leur appliquer la signature de l'auteur ;
- **ne jamais modifier un APK après `assembleRelease`** autrement que par `apksigner sign` (pas de `zipalign` qui réécrirait l'archive, pas de recompression) : F-Droid compare son APK non signé à celui de l'auteur privé de sa signature.

## 1. Préparer la version

1. `app/build.gradle.kts` : `versionName` (format `X.Y`, ou `X.Y.Z` pour un correctif) et `versionCode` (entier strictement croissant ; `1` pour 0.9). Un seul `versionCode` pour tous les APK : pas de code par ABI (D38).
2. Changelog Fastlane : `fastlane/metadata/android/fr-FR/changelogs/<versionCode>.txt` et `en-US/…` (500 caractères au plus).
3. `docs/` à jour (SPEC §11, `CLAUDE.md`, décisions), commit `chore(release): version 0.9` sur `main`.

## 2. Contrôles avant release

Sur un clone propre de `main` (`git status` vide, sous-module à jour : `git submodule update --init`) :

| Contrôle | Commande ou document | Attendu |
|---|---|---|
| Tests, lint, ktlint | `./gradlew check` | succès, zéro avertissement |
| Magasin de confiance | `scripts/update-trust-store.sh --ants-dir DOSSIER` (procédure de `docs/trust-store.md`) | aucun certificat ni Master List à mettre à jour ; sinon mise à jour dans un commit séparé, avant la release |
| OpenJPEG | veille de D3 : `git log` amont depuis le commit épinglé, avis de sécurité ; `app/src/main/cpp/test/run-host-tests.sh` | aucun correctif de décodage en attente, harnais vert |
| Revue de sécurité | skill `android-securite` | aucune vulnérabilité ouverte de gravité haute ou critique |
| Reproductibilité | `scripts/check-reproducible.sh` | cinq APK identiques octet pour octet (universel compris) |
| Plan de test | `docs/test-plan.md`, **sur l'APK release signé** (étape 4) installé sur un vrai téléphone, cas TP-38 à TP-41 au minimum, plus les cas touchés par la version | tous OK, compte rendu daté |
| Revue F-Droid | skill `android-fdroid` (première soumission, puis à chaque changement de dépendance) | aucun bloquant |

Le plan de test se fait sur l'APK release, pas sur le debug : R8 (D24) peut retirer une classe que seule la lecture réelle atteint.

## 3. Keystore (une fois pour toutes)

À faire **une seule fois** pour toute la vie de l'application : une autre clé interdirait toute mise à jour aux utilisateurs déjà installés, et changerait `AllowedAPKSigningKeys` sur F-Droid.

```bash
keytool -genkeypair -v \
  -keystore ~/cles/sceau-release.jks -storetype PKCS12 \
  -alias sceau -keyalg RSA -keysize 4096 -validity 11000 \
  -dname "CN=mgdx, O=Sceau"
```

- RSA 4096, validité de 11 000 jours (30 ans et plus) : Google Play comme F-Droid recommandent une validité au-delà de 2033 ;
- le fichier vit **hors du dépôt** (ici `~/cles/`), avec un mot de passe fort tenu dans un gestionnaire de mots de passe ;
- **sauvegardes** chiffrées sur au moins deux supports distincts. Une clé perdue impose une nouvelle application (autre signature) ; une clé volée permet de publier une fausse mise à jour.

Empreinte du certificat, à reporter dans la recette F-Droid (`AllowedAPKSigningKeys`, en minuscules, sans `:`) :

```bash
keytool -list -v -keystore ~/cles/sceau-release.jks -alias sceau | grep 'SHA256:'
```

## 4. Construire et signer

```bash
./gradlew clean :app:assembleRelease
ls app/build/outputs/apk/release/
# app-arm64-v8a-release-unsigned.apk   app-armeabi-v7a-release-unsigned.apk
# app-x86-release-unsigned.apk         app-x86_64-release-unsigned.apk
# app-universal-release-unsigned.apk
```

Les APK sortent de Gradle déjà alignés (entrées sur 4 octets, bibliothèques natives non compressées sur des pages de 16 Ko) : **pas de `zipalign`**. Pour s'en assurer :

```bash
BT=$ANDROID_HOME/build-tools/37.0.0
$BT/zipalign -c -P 16 4 app/build/outputs/apk/release/app-universal-release-unsigned.apk
```

Signature avec les schémas v1, v2 et v3 (v1 n'est pas exigé par minSdk 26 mais `apksigcopier`, l'outil de F-Droid, sait le recopier, et il ne coûte rien), en préservant l'alignement existant, avec le nommage des assets :

```bash
VERSION=0.9
OUT=~/releases/sceau-$VERSION && mkdir -p "$OUT"
for abi in arm64-v8a armeabi-v7a x86 x86_64 universal; do
  $BT/apksigner sign \
    --ks ~/cles/sceau-release.jks --ks-key-alias sceau \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --alignment-preserved true \
    --out "$OUT/sceau-$VERSION-$abi.apk" \
    "app/build/outputs/apk/release/app-$abi-release-unsigned.apk"
done
rm -f "$OUT"/*.idsig
```

Vérifications :

```bash
for f in "$OUT"/*.apk; do $BT/apksigner verify --print-certs "$f" | grep 'SHA-256 digest'; done   # même certificat partout
$BT/zipalign -c -P 16 4 "$OUT/sceau-$VERSION-universal.apk"
# Contenu inchangé hors META-INF/ (MANIFEST.MF, *.SF, *.RSA ajoutés par la signature v1) :
diff <(unzip -Z1 app/build/outputs/apk/release/app-universal-release-unsigned.apk | sort) \
     <(unzip -Z1 "$OUT/sceau-$VERSION-universal.apk" | grep -v '^META-INF/' | sort)
# Si fdroidserver est installé, la vérification même de F-Droid :
apksigcopier compare "$OUT/sceau-$VERSION-universal.apk" --unsigned app/build/outputs/apk/release/app-universal-release-unsigned.apk
```

`dependenciesInfo` est déjà désactivé dans `app/build.gradle.kts` (D25) : aucun bloc de métadonnées de dépendances n'est ajouté au bloc de signature.

Nommage des assets, à ne pas changer (la recette F-Droid en dépend pour l'universel) :

| Asset | Contenu |
|---|---|
| `sceau-<version>-arm64-v8a.apk` | téléphones 64 bits ARM (la quasi-totalité des appareils récents) |
| `sceau-<version>-armeabi-v7a.apk` | téléphones 32 bits ARM |
| `sceau-<version>-x86.apk`, `sceau-<version>-x86_64.apk` | émulateurs, rares tablettes Intel |
| `sceau-<version>-universal.apk` | toutes les architectures ; c'est l'APK publié par F-Droid |
| `SHA256SUMS` | empreintes SHA-256 des cinq APK |

```bash
(cd "$OUT" && sha256sum sceau-*.apk > SHA256SUMS && cat SHA256SUMS)
```

## 5. Tag et release GitHub

Tag **annoté**, au format `vX.Y` (`vX.Y.Z` pour un correctif), sur le commit exact construit à l'étape 4 :

```bash
git tag -a v0.9 -m "Sceau 0.9"
git push origin main
git push --tags
gh release create v0.9 \
  --title "Sceau 0.9" \
  --notes-file fastlane/metadata/android/fr-FR/changelogs/1.txt \
  "$OUT"/sceau-0.9-*.apk "$OUT"/SHA256SUMS
```

Ne jamais déplacer un tag publié : F-Droid et Obtainium s'y fient. Une erreur se corrige par une nouvelle version (`v0.9.1`).

Puis installer l'APK publié (téléchargé depuis la page de la release, pas le fichier local) sur un téléphone et refaire une lecture réelle : c'est l'APK que recevront les utilisateurs.

## 6. F-Droid

Première soumission : remplir `docs/fdroid/io.github.mgdx.sceau.yml` (hash du commit du tag, empreinte du certificat) et suivre [`docs/fdroid/README.md`](fdroid/README.md) : `fdroid build` dans un clone de fdroiddata, puis merge request sur https://gitlab.com/fdroid/fdroiddata.

Versions suivantes : rien à faire si le tag `vX.Y` et l'asset `sceau-X.Y-universal.apk` sont publiés et que le `versionCode` a augmenté : F-Droid détecte le tag (`UpdateCheckMode: Tags`), ajoute le bloc de build et compare son APK à celui de la release. Si la comparaison échoue, F-Droid ne publie rien : relancer `scripts/check-reproducible.sh vX.Y` et lire `docs/reproducible-builds.md`.
