# `:mrz` — lecture optique de la MRZ (D32)

Module Kotlin JVM pur (aucun import `android.*`, aucun code natif), testé sur JVM. Il reçoit
le plan de luminance d'une image de la caméra et ne rend que le format, le numéro de document
et les deux dates (`Api.kt`). Le contrat entre le traitement d'image (lot B) et le décodage des
champs (lot A) est dans `Internal.kt`.

## Traitement d'image (`TemplateLineRecognizer`)

1. Remise à l'endroit (`rotationDegrees`) et réduction par moyenne de blocs (largeur de travail
   ≤ 1600 px, 1 Mpx au plus).
2. Binarisation adaptative : pixel lissé par le noyau binomial (1 2 1)² (un trait d'un pixel et
   demi, fréquent aux petites tailles, garde son contraste) comparé à la moyenne locale (image
   intégrale, fenêtre de largeur/36), avec un contraste minimal absolu et relatif.
3. Inclinaison (±10°) : maximum de la somme des carrés du profil de projection de l'encre, pas
   de 1° puis de 0,1° ; l'encre est redressée au plus proche voisin, les niveaux de gris ne sont
   jamais recopiés (les cellules y sont échantillonnées par la rotation inverse). Les traits
   horizontaux plus longs que largeur/10 (bord de page ou de carte, filets) sont effacés de
   l'encre : sinon ils faussent le profil et se collent à la dernière ligne.
4. Profil horizontal → bandes ; dans chaque bande, profil vertical → blocs, pas médian, chaîne de
   blocs réguliers (un reflet peut effacer quelques caractères) et nombre de positions 30, 36 ou 44.
5. Groupe de 3 lignes de 30 (TD1) ou de 2 lignes de 36 ou 44 (TD2, TD3), alignées, de même pas et
   d'espacement régulier. La ligne du nom sert à reconnaître le format mais n'est jamais découpée
   ni classée : seules les lignes utiles sont rendues.
6. Grille au pas fixe recalée par moindres carrés sur les blocs ; ligne de base par moindres
   carrés sur le bas des glyphes ; hauteur déduite du pas (proportions de la police, insensibles au
   flou). Chaque cellule est échantillonnée en 16 × 20 et comparée aux 37 modèles par corrélation
   normalisée sur les niveaux de gris.
7. Réglage par ligne, sur une cellule sur trois, par descente coordonnée sur le score moyen de
   rang 1 : niveau de flou des modèles (écart type 0 ; 0,9 ; 1,6 ; 2,3 pixels de modèle, flous
   calculés au chargement à partir de la ressource), décalage vertical (±0,5 et ±1 pixel de
   modèle) et échelle de la hauteur (±7 %). La corrélation est maximale quand le modèle a le flou
   de l'image : aux petites tailles, des modèles nets départagent mal `0`/`O`, `H`/`M`, `2`/`Z`.
   Puis chaque cellule est décalée de ±1 pixel de modèle horizontalement et verticalement ; les
   décalages ne sont évalués que pour les 8 classes les mieux classées sans décalage.

**Scores** : `GlyphCandidates.ranked` contient les 5 meilleures classes, triées par score
décroissant. Le score est la corrélation normalisée (centrée, réduite) de la cellule et du modèle,
ramenée à [0, 1] (valeurs négatives mises à 0) : 1 = correspondance parfaite. Il ne dépend ni du
contraste ni de la luminosité, et se compare donc d'une position et d'une image à l'autre. Sur des
caractères nets, le premier candidat obtient typiquement 0,9 à 0,99 et le second 0,6 à 0,8 ; les
paires proches (`0`/`O`, `2`/`Z`, `5`/`S`) peuvent être à quelques centièmes. Aux petites tailles
(pas de 10 à 14 px, modèles flous), les scores se resserrent : premier vers 0,95, écart au
second de 0,03 à 0,1. Mesure sur les scènes « appareil » : le premier est faux 6 à 14 % du temps
quand l'écart est sous 0,05, 1,4 % entre 0,05 et 0,075, presque jamais au-delà de 0,1. Les marges
du décodage (second candidat envisagé jusqu'à 0,15, refus si deux résultats sont à moins de
0,05) restent donc adaptées et n'ont pas été changées ; les normaliser par l'écart médian de la
ligne prédit moins bien les erreurs. Une position clé (numéro, dates, leurs chiffres de contrôle,
composite) de score de rang 1 inférieur à 0,3 (cellule sans contraste, effacée par un reflet,
aux candidats arbitraires) fait refuser l'image.

Une ligne trouvée dont la moyenne des scores de rang 1 est inférieure à 0,5 est rejetée (`null`).
Les tampons de travail sont réutilisés d'un appel à l'autre et remis à zéro avant le retour ; un
seul fil à la fois.

## Police et modèles

Les modèles dérivent de la police **OCR-B** de Norbert Schwarz (sources METAFONT, paquet CTAN
[`ocr-b`](https://ctan.org/pkg/ocr-b)), vectorisée par Zdeněk Wagner (paquet CTAN
[`ocr-b-outline`](https://ctan.org/pkg/ocr-b-outline), fichier `opentype/ocrb10.otf`). Licence,
dans `fonts/README.ocr-b` (copie du README du paquet `ocr-b`) : « you may freely use, modify,
and/or distribute any of these files or the resulting fonts, without limitation » ; et dans
`fonts/README.ocr-b-outline` : « The license of the outline fonts is the same as for the original
fonts, i.e. you may freely use, modify, and/or distribute any of these files, without
limitation. » Licence permissive, compatible avec la GPLv3 et avec F-Droid.

La police vit dans `fonts/`, **hors de l'APK** : seuls les modèles qui en dérivent sont embarqués.
Elle sert aussi aux tests, pour rendre des MRZ de synthèse.

| Fichier | Rôle | SHA-256 |
|---|---|---|
| `ocrb10.otf` | police source (`mrz/fonts/`, archive CTAN `ocr-b-outline.zip`) | `6270b592af1cb319fe4122777d14fdbfc4a2edd111c6f87af3f4ac04c82ba48a` |
| `ocrb-templates.bin` | modèles embarqués (`src/main/resources/io/github/mgdx/sceau/mrz/`, 11 885 octets) | `cfa591c076c608fdc2f21b2c85740ebf0705120c5feef9ee682e602f39f4fb07` |

`OcrbTemplatesFingerprintTest` vérifie ces deux empreintes.

### Régénérer les modèles

```bash
scripts/generate-ocrb-templates.sh
```

Le script vérifie l'empreinte de la police, puis lance `scripts/GenerateOcrbTemplates.java`
(programme Java en un seul fichier, JDK 17 ou ultérieur). Pour chaque classe, la couverture
d'encre du glyphe est estimée par 8 × 8 points par pixel, testés sur le contour exact
(`Shape.contains`), sans rendu anti-crénelé : le résultat ne dépend que de la géométrie
(identique avec Temurin 17 et OpenJDK 25). La grille couvre la chasse du glyphe (723 unités)
et 740 unités au-dessus de la ligne de base, avec 12 % de marge en haut et en bas. Format :
`"OCRB"`, version 1, largeur 16, hauteur 20, 37 classes, puis les 37 caractères en ASCII et
37 × 20 × 16 octets de couverture. Reporter l'empreinte affichée dans le tableau ci-dessus.

## Tests

```bash
./gradlew :mrz:test
SCEAU_FUZZ_ITERATIONS=20000 ./gradlew :mrz:test --tests '*Fuzz*' --rerun   # fuzzing long de l'entrée
```

- `RecognitionRateTest` : MRZ de synthèse (police OCR-B, ligne du nom, texte de page, aplat de
  photo) dégradées : flou, bruit, inclinaison de ±8°, perspective, éclairage non uniforme, bande de
  reflet saturée, faible contraste, graisse, échelles de 800 à 2400 px de large, rotation du
  capteur, rangées avec remplissage. Seuils par format, sur 40 scènes tirées au hasard : MRZ trouvée
  dans au moins 95 % des images, format jamais faux, au moins 95 % des caractères justes au rang 1
  et 99 % dans les deux premiers. Mesuré (200 scènes par format) : TD1 100 % trouvées, 99,88 % au
  rang 1, 99,93 % dans les deux premiers ; TD2 100 %, 99,94 %, 99,96 % ; TD3 99 %, 99,27 %,
  99,56 %. Les erreurs restantes viennent surtout de caractères effacés par le reflet ou des plus
  petites échelles floues. Ces chiffres valent pour des images de synthèse : la robustesse réelle
  (reflets du film plastique, mise au point) se mesure sur appareil (lot G).
- `RecognizerInputTest` : images vides, uniformes, bruit pur, pages sans MRZ (120), dimensions et
  `rowStride` invalides, tampon trop court → `null` sans exception ; l'image d'entrée n'est pas
  modifiée.
- `RecognizerFuzzTest` : fuzzing de l'entrée, mêmes variables `SCEAU_FUZZ_*` que `:core`.
- `PerformanceTest` : non-régression grossière du temps de calcul sur JVM, dont un recadrage
  de 1080 × 323 comme en 1920 × 1080 portrait (environ 11 ms par image sur JVM de bureau, contre
  10 ms avant le lot I1).
- `DeviceRecognitionTest` (lot I1) : scènes « appareil » (`DeviceScenes.kt`) qui reproduisent
  la caméra d'un téléphone en portrait : recadrage du cadre de visée (rapport 2,8 à 3,8), MRZ sur
  60 à 92 % de la largeur, pas de 10 à 22 px, rendu suréchantillonné ×4 puis moyenné, flou
  gaussien de 0,5 à 1,5 px, dématriçage (sous- puis sur-échantillonnage), renforcement de
  netteté, guilloches, bruit, éclairage, reflet, bord de page, inclinaison de ±5°, capteur à 90°,
  ligne du nom et texte de page. Bout en bout = `Found` juste avec deux images distinctes de la
  même scène. Mesuré sur 100 scènes par tranche (avant le lot I1 → après) :

  | Pas (px) | Localisées | Rang 1 | Bout en bout |
  |---|---|---|---|
  | 10-12 | 79 % → 90 % | 90,4 % → 98,7 % | 40 % → 73 % |
  | 12-14 | 86 % → 93 % | 96,2 % → 99,9 % | 60 % → 85 % |
  | 14-16 | 91 % → 94 % | 99,3 % → 99,6 % | 85 % → 91 % |
  | 16-18 | 94 % → 97 % | 99,6 % → 100 % | 90 % → 97 % |
  | 18-22 | 97 % → 97 % | 99,8 % → 100 % | 95 % → 97 % |

  Aucune fausse MRZ. Une image seule peut encore donner des champs faux aux contrôles justes
  (2 images sur 500, aux plus petits pas) ; l'accord de deux images les a toujours écartées. Seuils des tests fixés d'après ces mesures, sur 40 scènes.
