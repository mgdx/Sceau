# Plan : lecture optique de la MRZ (D32)

Plan de travail de la décision D32, découpé en lots pour le skill `android-supervision`. Chaque lot se fait dans son propre worktree, se vérifie par `./gradlew check` et se commite sur sa propre branche. Le superviseur fusionne, mesure et recette.

## Ordonnancement

```
Lot 0 (superviseur) ──┬── Lot A (:mrz texte) ────┐
                      ├── Lot B (:mrz image) ────┼── Lot D (accueil) ──┬── Lot E (traductions) ──┐
                      └── Lot C (caméra :app) ───┘                     └── Lot F (docs) ─────────┴── Lot G (recette)
```

- Vague 1 : A, B et C en parallèle, après le lot 0.
- Vague 2 : D, puis E et F en parallèle (E attend que les chaînes françaises de C et D soient figées).
- Vague 3 : G par le superviseur.

## Lot 0 : squelette et contrats (superviseur, avant de lancer la vague 1)

Il fige les interfaces entre lots pour qu'A, B et C avancent sans s'attendre.

- Module `:mrz` (Kotlin JVM, paquet `io.github.mgdx.sceau.mrz`), configuré comme `:testchip` : toolchain 17, `allWarningsAsErrors`, ktlint, inclus dans `check`. `implementation(project(":mrz"))` dans `:app`.
- API publique, avec des implémentations qui renvoient `null` :

```kotlin
/** Image en niveaux de gris (plan Y). [data] appartient à l'appelant, qui la remet à zéro. */
class LumaFrame(val data: ByteArray, val width: Int, val height: Int, val rowStride: Int, val rotationDegrees: Int)

enum class MrzFormat { TD1, TD2, TD3 }

/** Seuls champs exposés. Dates en AAMMJJ. `toString` ne révèle rien. */
class MrzKeyFields(val format: MrzFormat, val documentNumber: String, val dateOfBirth: String, val dateOfExpiry: String)

/** Une ligne découpée : pour chaque position, les classes candidates triées par score (lot B → lot A). */
class GlyphCandidates(val ranked: List<Pair<Char, Float>>)

/** Point d'entrée de :app. Garde l'état de stabilisation entre images. */
class MrzScanner {
    fun analyze(frame: LumaFrame): MrzScanResult
    fun reset()
}

sealed interface MrzScanResult {
    data object NothingFound : MrzScanResult
    data object Unstable : MrzScanResult           // MRZ vue, contrôles faux ou pas encore confirmée
    data object UnsupportedDocumentNumber : MrzScanResult   // numéro étendu de TD1
    class Found(val fields: MrzKeyFields) : MrzScanResult
}
```

- Interne : `internal fun interface LineRecognizer { fun recognize(frame: LumaFrame): List<List<GlyphCandidates>>? }` (lot B), consommé par `MrzFieldDecoder` (lot A).
- Commit sur `main` avant la vague 1.

## Lot A : décodage des champs et chiffres de contrôle (`:mrz`, sans image)

- Calcul du chiffre de contrôle ICAO 9303-3 (pondération 7-3-1, `<` = 0, `A` = 10 … `Z` = 35).
- Positions des champs pour TD1 (lignes 1 et 2), TD2 et TD3 (ligne 2). Le format se déduit du nombre de lignes et de leur longueur. La ligne du nom n'est jamais décodée.
- Correction selon le type de champ : sur une position numérique, `O`/`D`/`Q`→`0`, `I`/`L`→`1`, `Z`→`2`, `S`→`5`, `G`→`6`, `B`→`8`, et l'inverse sur une position alphabétique. On explore les candidats de rang 2 aux positions ambiguës, avec un nombre de combinaisons borné, jusqu'à ce que les contrôles passent.
- Acceptation : chiffres de contrôle du numéro, de la naissance et de l'expiration, plus le composite en TD2 et TD3. Dates `AAMMJJ` existantes. Numéro étendu de TD1 (`<` à la place du chiffre de contrôle) : `UnsupportedDocumentNumber`.
- Stabilisation : `Found` après deux résultats identiques consécutifs. Remise à zéro sur `reset()` et après 2 s sans MRZ.
- Hygiène : tableaux `CharArray` de travail remis à zéro. Nationalité, sexe et données facultatives jamais copiés dans un `String`.
- Tests : exemples de la norme (spécimens « UTO » d'ICAO 9303-4 et 9303-5), chaque confusion corrigée, contrôle faux refusé, composite faux refusé, numéro étendu, dates impossibles, stabilisation, `toString` muet.

## Lot B : traitement d'image et classification (`:mrz`)

- **Avant tout code**, trouver une police OCR-B sous licence libre compatible avec la GPLv3 (pistes : paquets CTAN `ocr-b` et `ocr-b-outline`) et vérifier sa licence sur l'archive elle-même. Sans police acceptable, s'arrêter et le signaler au superviseur : la décision D32 serait à revoir.
- Script `scripts/generate-ocrb-templates.*` : produit depuis la police les 37 modèles normalisés (par exemple 16 × 24, quelques graisses et décalages d'un sous-pixel), dans une ressource binaire de quelques Ko de `:mrz`. La ressource est versionnée. Son empreinte SHA-256 est vérifiée par un test, comme `TrustStoreFingerprintTest`. La police n'entre jamais dans l'APK.
- Chaîne de traitement : recadrage sur la zone de visée, binarisation adaptative (Sauvola sur image intégrale), estimation et correction de l'inclinaison (±10°), profil horizontal → 2 ou 3 lignes de hauteur et d'espacement réguliers, découpage au pas fixe recalé sur les composantes connexes, normalisation de chaque caractère, scores contre les modèles → `GlyphCandidates`.
- Limites d'entrée : dimensions et `rowStride` contrôlés, image refusée au-delà de 4096 × 4096, aucune allocation proportionnelle à une valeur non vérifiée.
- Performance : moins de 100 ms par image 1280 × 720 sur un cœur de Snapdragon 632 (Fairphone 3). À mesurer au lot G ; sur JVM, un test de non-régression grossier.
- Tests : générateur de MRZ de synthèse (Java2D en test uniquement, rendu avec la police) avec des dégradations : flou, bruit, rotation de ±8°, perspective légère, éclairage non uniforme, reflet saturé sur une bande, faible contraste, échelle variable. Taux de reconnaissance minimal fixé par format, et aucun faux positif sur 1 000 images sans MRZ ou avec une MRZ aux contrôles faux. Entrée ajoutée au fuzzing (`SCEAU_FUZZ_ITERATIONS`) : tailles extrêmes, `rowStride` incohérent, octets aléatoires.

## Lot C : caméra et écran de scan (`:app`, avec un `MrzScanner` de substitution)

- Dépendances CameraX (`camera-camera2`, `camera-lifecycle`, `camera-compose`) dans `libs.versions.toml`. Licence vérifiée sur les artefacts et graphe des dépendances transitives dans `docs/dependencies.md` : aucun artefact `com.google.android.gms` ni Firebase (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`).
- Manifeste : `android.permission.CAMERA`, `<uses-feature>` en `required="false"` pour `android.hardware.camera` et `android.hardware.camera.autofocus` (que la permission rendrait sinon obligatoires). Vérifier avec `aapt2 dump badging` qu'aucune feature caméra n'est requise. Le test de manifeste existant, s'il y en a un, est mis à jour pour n'autoriser que NFC et CAMERA.
- Écran `ui/scan/MrzScanScreen` : aperçu plein écran, cadre de visée au format de la MRZ, consigne (« Placez la bande en bas de la page dans le cadre »), bouton torche si l'appareil en a une, retour. Pas de bouton de prise de vue.
- Analyse : `ImageAnalysis` en `STRATEGY_KEEP_ONLY_LATEST`, résolution cible 1280 × 720 (1920 × 1080 si les essais l'exigent), exécuteur à un seul fil. Le plan Y est copié dans un `ByteArray` réutilisé, remis à zéro après chaque analyse et à la sortie. `ImageProxy` fermé dans un `finally`.
- Permission demandée à l'appui sur le bouton : premier refus, refus définitif avec un bouton vers les réglages, pas de caméra (bouton masqué via `PackageManager.FEATURE_CAMERA_ANY`).
- Cycle de vie : caméra liée à l'écran, libérée en arrière-plan. Rotation sans fuite. Mort du processus : retour sur un accueil vide.
- Navigation : route `scan`. Le résultat remonte au `SessionViewModel`, jamais par `SavedStateHandle` ni par argument de navigation.
- Retour haptique et annonce TalkBack au succès.
- Chaînes `strings_scan.xml` en `values` et `values-en` seulement (les autres langues au lot E).

## Lot D : intégration dans l'accueil (après A et C)

- Bouton « Scanner la MRZ » au-dessus des trois champs MRZ, dans l'onglet Passeport et dans le segment MRZ de la carte d'identité (D31).
- `AccessForm.fillFromMrz(fields, today, dateOrder)` : numéro tel quel, dates `AAMMJJ` converties en 8 chiffres dans l'ordre de la locale (D10), siècle de naissance en 20AA si la date ne dépasse pas aujourd'hui, 19AA sinon, expiration en 20AA. Les champs remplis passent par les mêmes validations que la saisie.
- Au retour sur l'accueil, l'onglet et le segment du scan sont gardés, le clavier est fermé et un message court invite à vérifier les champs avant « Lire ». La lecture NFC n'est jamais déclenchée automatiquement.
- Oubli : mêmes règles que la saisie manuelle (« Effacer », arrière-plan, fin de lecture réussie).
- Mode démo : entrée « Simuler un scan de MRZ » dans l'APK de debug, qui remplit les champs avec la MRZ de la CNIe spécimen pour tester sans caméra.
- Tests JVM : `fillFromMrz` (siècles, ordres de date, numéro de 9 caractères), `SessionViewModel` (le résultat arrive, l'effacement l'oublie).

## Lot E : traductions et débordements (après C et D)

- Nouvelles chaînes dans les 43 autres langues, selon les glossaires de `docs/traduction/` (« MRZ » jamais traduit), avec le skill `android-traduction`.
- `TextOverflowTest` : bouton de scan sur les deux onglets et écran de scan avec un aperçu factice (pas de caméra sous Robolectric) : consigne, refus de permission, refus définitif. Mise à jour de `known-overflows.txt` si besoin, avec une justification.

## Lot F : documentation (en parallèle de D et E)

- `SPEC.md` : §1, §2, §3, §5.1, nouvelle §5.6 (écran de scan), §8 et §9, conformément à D32. Note en tête de `decisions.md` si D32 est intégrée.
- `docs/architecture.md` : module `:mrz`, flux d'une image, cycle de vie du tableau de luminance (§4).
- `docs/dependencies.md` : CameraX et ses dépendances transitives, police OCR-B (outil de génération seulement, hors APK).
- `docs/test-plan.md` : nouveaux cas TP (voir lot G).
- `CLAUDE.md` et `README` : module `:mrz`, permission caméra et sa justification. Métadonnées F-Droid : la description mentionne la caméra, utilisée uniquement pour lire la MRZ, sans prise de photo.

## Lot G : intégration et recette (superviseur)

- Fusion, `./gradlew check :app:assembleDebug :app:assembleRelease`, `scripts/check-reproducible.sh`.
- Taille des APK release par ABI comparée à l'avant D32, sous les 8 Mo (D2), à reporter dans D32.
- Recette sur le Fairphone 3 (skill `android-test`) :
  - passeport français (TD3) et CNIe (TD1), lumière normale, faible, et reflet direct sur le film ;
  - scan puis lecture NFC complète jusqu'au verdict ;
  - refus de la permission, refus définitif puis réglages, révocation pendant le scan ;
  - mise en arrière-plan et rotation pendant le scan, retour arrière ;
  - temps d'analyse par image et délai jusqu'à la reconnaissance ;
  - aucun fichier créé : `adb shell run-as io.github.mgdx.sceau find . -newer <repère>` avant et après un scan ;
  - aperçu absent du multitâche et des captures (`FLAG_SECURE`) ;
  - `adb shell dumpsys package io.github.mgdx.sceau` : seules NFC et CAMERA sont demandées.
- Revue sécurité ciblée sur le delta (skill `android-securite`) : entrée de `:mrz`, cycle de vie des images, manifeste.
