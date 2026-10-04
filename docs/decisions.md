# Décisions et écarts par rapport à SPEC.md

> Les décisions D1 à D19 sont intégrées à SPEC.md depuis le 2026-09-25.

`SPEC.md` est la source de vérité. Toute décision qui s'en écarte, ou qui tranche un point que la SPEC laisse ouvert, est consignée ici avec sa justification. Une entrée par décision, la plus récente en bas.

Format : date, contexte, décision, justification, écart à la SPEC concerné.

---

## D1. Magasin de confiance embarqué : CSCA ANTS et Master List allemande du BSI

- **Date** : 2026-09-25 (décision de l'utilisateur).
- **Contexte** : SPEC §7.1 prévoit d'embarquer les certificats CSCA de l'ANTS et la Master List de l'ICAO (`icao-master-list.ml`), téléchargée depuis le site du PKD de l'ICAO.
- **Décision** : la v1 embarque dans `core/src/main/resources/trust/` :
  - les 5 certificats CSCA français de l'ANTS : passeport 2010, 2015, 2020, 2025, et e-ID 2021 (cartes d'identité) ;
  - la German Master List publiée par le BSI (Bundesamt für Sicherheit in der Informationstechnik), fichier `de-bsi-master-list.ml`, signée le 2026-05-28 : 588 certificats, 112 émetteurs, couvrant les 27 pays de l'UE, l'EEE, la Suisse et le Royaume-Uni.

  La provenance, les empreintes et la procédure de mise à jour de chaque fichier figurent dans `docs/trust-store.md`.
- **Justification** :
  - *Pourquoi pas la Master List de l'ICAO* : ses conditions d'utilisation interdisent la redistribution, et son téléchargement passe par un captcha (interaction humaine obligatoire), ce qui empêche aussi une mise à jour outillée.
  - *Pourquoi celle du BSI* : parmi les Master Lists nationales publiques évaluées, celle du BSI est publiée avec des conditions de réutilisation explicites, qui autorisent la réutilisation, y compris commerciale, sous trois réserves : pas d'usage publicitaire, aucune apparence de coopération avec le BSI ou de caution de sa part, fichier redistribué sans modification. Sceau respecte ces trois réserves : le fichier est embarqué octet pour octet (son empreinte est contrôlée par un test), et la documentation comme les métadonnées de publication le désignent comme « Master List allemande du BSI » sans laisser entendre de partenariat.
  - *Pourquoi garder les certificats ANTS* : le CSCA e-ID français (`CN=eID-FRANCE`), qui signe les CNIe, ne figure dans aucune Master List nationale, dont celle du BSI. Sans le certificat ANTS, aucune CNIe ne pourrait être vérifiée.
  - *Ancrage de la liste embarquée* : vérifier la signature CMS de la Master List avec le certificat signataire qu'elle contient serait circulaire (n'importe qui peut signer une liste avec son propre certificat). La signature est donc vérifiée **et** le certificat signataire doit être signé par le CSCA allemand dont l'empreinte SHA-256, publiée par le BSI, est épinglée dans le code (`2084aed7…ada8`, ou son certificat de lien `5f28cb69…20bd` ; empreintes complètes dans le code et dans `docs/trust-store.md`). Sinon la liste embarquée est rejetée.
  - *Imports utilisateur* : ils suivent la règle de SPEC §5.4 : signature CMS vérifiée avec le certificat signataire embarqué dans le fichier, dont l'empreinte est montrée à l'utilisateur pour confirmation avant import.
- **Limites connues de la liste BSI** (à rappeler dans l'écran « À propos » avec les limites de SPEC §7.4) :
  - le CSCA des cartes d'identité slovaques et 4 CSCA hongrois de 2024 y manquent ; ils sont présents dans les Master Lists italienne et suédoise, que l'utilisateur peut importer ;
  - le CSCA e-ID français n'y figure pas (d'où les certificats ANTS).
  Un document dont l'émetteur n'est couvert ni par l'ANTS, ni par la liste BSI, ni par un import donne « Émetteur inconnu » (SPEC §7.4).
- **Taille** : la Master List ajoute environ 0,5 Mo compressé à l'APK.
- **Tests** : le test « empreintes des fichiers embarqués conformes à `docs/trust-store.md` » porte sur les 5 certificats ANTS et sur `de-bsi-master-list.ml`. Le test « Master List à signature CMS invalide rejetée » s'applique au chargement de la liste embarquée comme à l'import.
- **Écarts à la SPEC** : §7.1 (Master List du BSI au lieu de celle de l'ICAO ; fichier `de-bsi-master-list.ml` au lieu de `icao-master-list.ml`) ; §7.2 (ancrage supplémentaire du signataire de la liste embarquée sur un CSCA allemand épinglé, plus strict que la SPEC).

## D2. Splits ABI : un APK par architecture, plus un APK universel

- **Date** : 2026-09-25 (révisée le même jour à la suite de la décision D3).
- **Contexte** : SPEC §2 et §10 demandent des APK par architecture, de moins de 8 Mo chacun. Une première version de cette décision retenait un APK universel unique, tant que l'application restait en pur Java/Kotlin.
- **Décision** : les splits ABI sont activés dans `app/build.gradle.kts` pour `armeabi-v7a`, `arm64-v8a`, `x86` et `x86_64`, avec en plus un APK universel (`isUniversalApk = true`).
- **Justification** : le décodeur JPEG 2000 est désormais natif (OpenJPEG en JNI, décision D3). Chaque APK par ABI n'embarque que la `libsceau_jp2.so` de son architecture (170 à 255 Ko) ; l'APK universel les contient toutes, pour les canaux qui ne distinguent pas les architectures. Tailles des APK release non signés, Master List BSI comprise : environ 3,7 Mo par ABI, 4,4 Mo pour l'universel, sous l'objectif de 8 Mo. La bibliothèque est alignée sur des pages de 16 Ko (Android 15+), et stockée non compressée et alignée dans l'APK.
- **Écart à la SPEC** : aucun : conforme à §2 (« APK par architecture ») et §10. L'APK universel est un complément.

## D3. Décodeur JPEG 2000 : OpenJPEG en JNI, compilé depuis les sources

- **Date** : 2026-09-25 (décision de l'utilisateur).
- **Contexte** : SPEC §3 retient jj2000 (fork JMRTD), « pur Java, pas de JNI », et cite openjpeg comme remplaçant possible. La licence d'origine de jj2000 n'est pas libre : elle précise que « No license under any patent, copyright or other intellectual property rights is granted for non JPEG 2000 Standard conforming products », restriction de champ d'usage que Debian a jugée non libre (le module y a été retiré pour cette raison). Elle est incompatible avec la GPLv3 de Sceau et avec la politique d'inclusion de F-Droid. Par ailleurs, le fork JMRTD n'est pas publié sur Maven Central.
- **Décision** : le portrait JPEG 2000 (DG2, et les images de DG12) est décodé par **OpenJPEG** (BSD-2-Clause), en JNI, dans le module `:app` :
  - sources d'OpenJPEG en sous-module git `app/src/main/cpp/openjpeg`, épinglé sur le commit `8314119b` de `master` (`v2.5.4-29`, 2026-09-05 ; voir « Version épinglée » ci-dessous) ; seule la bibliothèque `openjp2` est compilée (sans codecs, outils ni tests), en statique, puis liée dans `libsceau_jp2.so` par CMake via le NDK. Aucun binaire précompilé dans le dépôt ;
  - API Kotlin `io.github.mgdx.sceau.jp2.Jpeg2000Decoder.decode(bytes): Bitmap?` ; cœur de décodage en mémoire dans `app/src/main/cpp/jp2_decode.c`, séparé du pont JNI (`jp2_jni.c`) et testé sur l'hôte sous AddressSanitizer et UndefinedBehaviorSanitizer (`app/src/main/cpp/test/run-host-tests.sh`) ;
  - `:core` reste en Kotlin pur sans JNI : l'ancien `Images.decodeJpeg2000` est supprimé ; `:core` transmet les octets du portrait (`EncodedImage`) et `:app` les décode.
- **Justification** : OpenJPEG est l'implémentation de référence du JPEG 2000, sous licence libre compatible GPLv3, maintenue, présente dans F-Droid comme dans les distributions. La compiler depuis les sources satisfait l'exigence F-Droid de reconstructibilité. Le décodage d'une donnée potentiellement hostile est borné : dimensions limitées à 4096 × 4096 (vérifiées dès la lecture de l'en-tête), budget mémoire et réduction de résolution (voir « Bombe de décompression »), flux limité à 16 Mo, 1 à 4 composantes de 16 bits au plus, mode strict d'OpenJPEG (flux tronqué refusé), gestionnaires de messages silencieux (aucune trace, SPEC §8), tampons natifs intermédiaires (copie du flux, échantillons décodés, pixels ARGB) remis à zéro avant libération. Compilation durcie : `-fstack-protector-strong`, `_FORTIFY_SOURCE=2`, `-fvisibility=hidden` (seule la fonction JNI est exportée), RELRO complet.
- **Tampons internes d'OpenJPEG** (blocs de code, tuiles en cours de décodage, tampon de lecture du flux, copies des données compressées) : toutes leurs allocations passent par `jp2_alloc.c`, qui connaît la taille de chaque bloc et le remet à zéro avant `free`, y compris l'ancien bloc d'une réallocation, par `sceau_jp2_secure_zero` (écriture suivie d'une barrière de compilation, que l'optimiseur ne peut pas supprimer ; `explicit_bzero` n'est pas garanti par bionic pour minSdk 26). **Limite restante** : les pages rendues au système par l'allocateur et les copies éventuellement faites par la JVM (tableau d'octets du flux, `Bitmap`) ne sont pas couvertes ici ; ces dernières relèvent de l'écran de résultat.
- **Bombe de décompression (audit V4, 2026-09-25)** : un flux J2K de quelques kilo-octets peut faire allouer à OpenJPEG plus d'un gigaoctet (blocs de code de 4 × 4 : 1,2 Go ; précincts de 4 × 4 : 7 Go ; 16 384 tuiles : 450 Mo), alors que DG2 et les images de DG12 sont décodés dans le même processus, sur des téléphones de 2 à 3 Go. Défense en trois couches :
  - *estimation avant `opj_decode`* (`jp2_decode.c`, après `opj_read_header`, à partir d'`opj_get_cstr_info`) : échantillons décodés (composantes × pixels à la résolution retenue × 4 octets, OpenJPEG stockant chaque échantillon sur 32 bits quelle que soit sa précision, elle-même bornée à 16 bits), échantillons de la tuile en cours si le flux est tuilé, paramètres de chaque tuile (8 Ko + 1,1 Ko par composante), blocs de code de toutes les résolutions d'une tuile (448 octets chacun + 16 par couche de qualité), sous-bandes, copie des données compressées et 4 Mo fixes (codec, tampon de lecture de 1 Mo, ondelettes). Coûts unitaires relevés sur les structures d'OpenJPEG en 64 bits et vérifiés par mesure : l'estimation majore le pic réel de 5 à 20 % sur des flux légitimes de 4096 × 4096 ;
  - *réduction plutôt que refus* : l'image décodée est plafonnée à 2048 × 2048 (largement au-delà d'un portrait ou d'une signature) ; la plus petite réduction de résolution (`opj_set_decoded_resolution_factor`, division par 2 à chaque niveau) qui tient ce plafond **et** le budget est retenue. Si aucune ne convient (trop de blocs de code ou de tuiles, dont le coût ne baisse pas avec la résolution), le flux est refusé (`SCEAU_JP2_ERR_TOO_LARGE`) avant tout décodage. Les dimensions annoncées restent bornées à 4096 × 4096 ;
  - *plafond dur à l'allocation* : `opj_malloc.c` est retiré de la cible `openjp2` au moment de la configuration CMake (le sous-module n'est pas modifié) et remplacé par `jp2_alloc.c`, qui compte chaque allocation d'OpenJPEG (surcoût de l'allocateur système compris) et refuse celles qui dépasseraient le budget du décodage en cours. Il couvre ce que l'estimation ne voit pas : tables allouées pendant `opj_read_header` (paramètres des 65 535 tuiles possibles), paramètres propres à une tuile, palettes JP2, et précincts déclarés (`opj_get_cstr_info` en copie mal les tailles, octets au lieu d'entiers : ils ne sont pas estimés).

  **Budget retenu** : 64 Mo pour OpenJPEG (`SCEAU_JP2_MEMORY_BUDGET`), plus les pixels de sortie, convertis ligne par ligne directement dans le tableau Java (au plus 2048 × 2048 × 4 = 16 Mo) puis recopiés une fois dans le `Bitmap` (16 Mo) : un décodage reste sous 96 Mo tout compris. `Jpeg2000Decoder` sérialise les décodages (un seul à la fois dans le processus), si bien que DG2 et DG12 ne cumulent plus leurs pics.

  **Mesures** (`maxrss` du processus sur l'hôte, pilote de mesure hors dépôt ; avant : `v2.5.4` et l'ancien `jp2_decode.c`) : `bomb_cb4p` 1226 → 2 Mo (refusé) ; `bomb_cb8` 565 → 2 Mo (refusé) ; `bomb_tiles` 449 → 3 Mo (refusé) ; `bomb_base` (4 composantes de 16 bits, 4096 × 4096) 328 → 29 Mo (décodé en 1024 × 1024) ; précincts de 4 × 4 7069 → 70 Mo (refusé par le plafond d'allocation). Images légitimes : les images du harnais et `specimen-portrait.jp2` décodent au pixel près comme avant ; un 4096 × 4096 RGB légitime passe de 274 Mo à 76 Mo et sort en 2048 × 2048. Le harnais (`run-host-tests.sh`) génère par script une grande image légitime et cinq flux hostiles (blocs de 4 × 4 en J2K et JP2, précincts de 4 × 4, 16 384 tuiles, 4 composantes de 16 bits) et vérifie que le pic d'allocation d'OpenJPEG ne dépasse jamais le budget.
- **Version épinglée (audit V5, 2026-09-25)** : le tag `v2.5.4` (`6c4a29b0`) ne contient pas les correctifs de sécurité publiés depuis sur `master`, et aucune version 2.5.5 n'est sortie. Le sous-module est donc avancé sur `8314119b`, dernier commit de `origin/master` à la date de l'audit, qui intègre :
  - `91d08b11` (2026-02-10) : dépassement de tas dans `opj_j2k_read_sod` (index des parties de tuile), atteignable en décodage par un flux forgé ;
  - `1bb9e915` (2026-06-10) : parties de tuile entrelacées, index invalide et lecture à une mauvaise position ;
  - `91966314` (2026-09-04) : marqueur MCO sans étage, décalage de niveau remis à zéro à tort ;
  - hors décodage ou sans effet sur Sceau : `839936aa` (dépassement d'entier côté encodeur), IDWT 5-3 et 9-7 optimisées NEON (`0186472b`, `0fc6e243`, actives sur arm64-v8a et armeabi-v7a), suppression de variables inutilisées, `cmake_minimum_required` porté à 3.10, génération pkg-config, CI et README.

  Examen du journal `v2.5.4..8314119b` : aucun changement de `openjpeg.h` ni de l'API utilisée par `jp2_decode.c`, aucune nouvelle dépendance, aucun fichier binaire (uniquement du C, du CMake, des scripts de CI et un test de non-régression en C). Harnais hôte rejoué sous ASan et UBSan : vert. Les IDWT NEON ne s'exécutent que sur ARM et restent à valider sur appareil (décodage du portrait de référence).
- **Veille (projet non maintenu)** : le README amont déclare depuis `06bbae8b` (2026-07-07) le dépôt « unmaintained » : des commits peuvent encore arriver, sans revue régulière des tickets. À chaque release de Sceau : relire `git log` de `origin/master` depuis `8314119b` et les avis de sécurité visant OpenJPEG (NVD, OSS-Fuzz, tracker Debian `openjpeg2`), avancer le sous-module si un correctif touche le décodage, et rejouer `run-host-tests.sh`. Plan de repli si une faille n'est plus corrigée en amont : appliquer le correctif dans un fork du sous-module publié sous le compte du projet (sources seulement, exigence F-Droid inchangée), ou suivre la branche maintenue par une distribution (Debian, Fedora). Les défenses propres à Sceau (mode strict, bornes de taille, budget mémoire et plafond d'allocation, harnais sous sanitizers) limitent l'exposition dans l'intervalle. Toute avance du sous-module doit aussi vérifier que `opj_malloc.c` expose toujours les mêmes fonctions (sinon `jp2_alloc.c` est à adapter : l'édition de liens échoue) et que les coûts unitaires de l'estimation restent valables (`big.j2k` et les bombes du harnais).
- **Écart à la SPEC** : §3 (OpenJPEG au lieu de jj2000 ; code natif en JNI, alors que la SPEC demandait « pas de JNI »). Conséquence : splits ABI activés (D2).

## D4. Nom de paquet `io.github.mgdx.sceau`

- **Date** : 2026-09-25.
- **Contexte** : `CLAUDE.md` citait le paquet `fr.sceau.core` à titre d'exemple.
- **Décision** : identifiant d'application et paquet `io.github.mgdx.sceau` pour `:app`, `io.github.mgdx.sceau.core` pour `:core`.
- **Justification** : un identifiant de paquet doit correspondre à un domaine que l'on contrôle. Le projet ne possède pas `sceau.fr` ; le domaine `mgdx.github.io` est rattaché au compte qui héberge le dépôt, ce qui est aussi la convention reconnue par F-Droid.
- **Écart à la SPEC** : aucun (la SPEC ne fixe pas le paquet).

## D5. Chaînes réparties par écran

- **Date** : 2026-09-25.
- **Contexte** : SPEC §2 place toutes les chaînes dans `res/values/strings.xml`.
- **Décision** : les chaînes sont réparties en un fichier par écran (`strings_home.xml`, `strings_reading.xml`, `strings_result.xml`, `strings_trust.xml`, `strings_about.xml`), plus `strings.xml` pour les chaînes communes, dans `res/values/` et `res/values-en/`.
- **Justification** : plusieurs développeurs travaillent en parallèle sur des écrans différents ; un fichier unique serait une source permanente de conflits de fusion. Pour Android, tous les fichiers de `res/values*/` forment une seule table de ressources : l'esprit de la règle (aucune chaîne codée en dur, traductions prêtes, `values-en` fourni) est respecté.
- **Écart à la SPEC** : §2 (lettre de la règle « dans `res/values/strings.xml` »).

## D6. Statut « non disponible » de la chaîne de certification et de la validité du DS

- **Date** : 2026-09-25.
- **Contexte** : SPEC §5.3 distingue « Émetteur inconnu » de « Échec », et SPEC §6.1 autorise une date de délivrance « estimée » (expiration moins la durée de validité usuelle) quand ni DG12 ni la date de signature du SOD ne la donnent. Or la durée usuelle n'est pas toujours la bonne : un passeport de mineur est valable 5 ans au lieu de 10.
- **Décision** :
  - quand aucun CSCA du magasin ne correspond au DS, la ligne « Chaîne de certification » (`CERTIFICATE_CHAIN`) vaut `NOT_AVAILABLE`, et non `FAILED` ; c'est ce statut qui produit le verdict « Émetteur inconnu » ;
  - la ligne « Validité du DS » (`DS_VALIDITY`) n'est jamais `FAILED` sur une date de délivrance **estimée** : elle vaut alors `NOT_AVAILABLE`, le détail indiquant que la date est estimée.
  - Le verdict se calcule par la table de décision de `docs/protocol.md` : une ligne `FAILED` ou `UNSUPPORTED_ALGORITHM` donne « Échec » ; sinon une chaîne `NOT_AVAILABLE` donne « Émetteur inconnu » ; sinon PA réussie et CA ou AA réussie donne « Authentique » ; CA et AA non disponibles donnent « Signature valide, puce non vérifiée » ; tout autre cas donne « Échec ».
- **Justification** : un émetteur absent du magasin n'est pas une preuve de falsification (SPEC §7.4) ; une estimation ne peut pas fonder un échec, sous peine de déclarer faux un passeport de mineur authentique.
- **Écart à la SPEC** : aucun sur le fond ; précise §5.3 et §6.1.

## D7. Permission interne `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

- **Date** : 2026-09-25.
- **Contexte** : SPEC §2 n'autorise que `android.permission.NFC`. Le manifeste fusionné contient en plus `io.github.mgdx.sceau.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, ajoutée par androidx.core.
- **Décision** : la permission est conservée.
- **Justification** : c'est une permission de niveau `signature`, déclarée et détenue par l'application elle-même, qui protège les récepteurs dynamiques non exportés ; elle est nécessaire à `ContextCompat.registerReceiver` sous l'API 33. Elle n'ouvre aucun accès au réseau ni à une donnée de l'utilisateur, et n'est pas présentée à l'utilisateur.
- **Écart à la SPEC** : §2 (lettre de la règle « `android.permission.NFC` uniquement »), sans effet sur son objet.

## D8. Règles lint désactivées ou ignorées

- **Date** : 2026-09-25.
- **Contexte** : SPEC §2 exige zéro avertissement lint, en erreur dans la CI.
- **Décision** :
  - règles `AndroidGradlePluginVersion`, `GradleDependency` et `NewerVersionAvailable` désactivées dans `app/build.gradle.kts` et `core/build.gradle.kts` ;
  - règle `TrustAllX509TrustManager` ignorée pour le seul jar `bcpkix-jdk18on` (`app/lint.xml`).
- **Justification** :
  - les trois premières signalent qu'une version plus récente existe : elles interrogent le réseau et cassaient la CI à chaque publication amont, sans lien avec le code. Les montées de version sont faites délibérément ;
  - `TrustAllX509TrustManager` vise une classe du paquet `org.bouncycastle.est` (protocole EST, RFC 7030) que Sceau n'utilise pas, qui ne peut rien faire sans permission réseau et que R8 retire en release. L'exclusion porte sur ce seul jar : la règle reste active pour le code du projet.
- **Écart à la SPEC** : aucun (zéro avertissement maintenu).

## D9. Dépendances de `:core` en `implementation`

- **Date** : 2026-09-25.
- **Contexte** : `:app` dépend de `:core`, qui dépend de JMRTD, SCUBA, BouncyCastle et kotlinx-coroutines.
- **Décision** : ces dépendances sont déclarées en `implementation` dans `core/build.gradle.kts`, pas en `api`.
- **Justification** : l'API publique de `:core` (`readAndVerify`, `CardTransport`, `AccessKey`, `VerificationReport`, `TrustStore`…) n'expose aucun type de JMRTD ni de BouncyCastle, seulement des types Kotlin et `java.security` / `java.time`. `:app` ne doit pas dépendre des détails de mise en œuvre de `:core`. Seul `scuba-sc-android`, nécessaire au pont `IsoDep`, est déclaré directement dans `:app`.
- **Écart à la SPEC** : aucun.

## D10. Dates du passeport saisies au clavier

- **Date** : 2026-09-25 (retour de l'utilisateur sur téléphone).
- **Contexte** : SPEC §5.1 prévoit un sélecteur de date pour la date de naissance et la date d'expiration (onglet Passeport). À l'essai, le sélecteur s'ouvrait en calendrier, peu pratique pour recopier une date de naissance ou une expiration lointaine.
- **Décision** : les deux dates se saisissent au clavier numérique (`KeyboardType.NumberPassword`), en huit chiffres (jour et mois sur deux, année sur quatre). Les séparateurs `/` s'affichent au fil de la frappe (`DateDigitsTransformation`) sans être stockés. L'ordre des champs suit le format de date court de la locale (`DateOrder.forLocale`) : JJ/MM/AAAA en français, MM/JJ/AAAA en anglais américain ; jour, mois, année par défaut. Validation (`AccessForm`) : date réelle, naissance au plus tard aujourd'hui, expiration à partir de 1990 ; l'erreur s'affiche sous le champ et « Lire » reste inactif tant qu'une date est invalide. Les chiffres vivent dans le `SessionViewModel`, jamais dans un `Bundle`.
- **Justification** : saisie plus rapide que le calendrier pour des dates recopiées depuis le document ; le clavier de type mot de passe numérique évite que le clavier du système apprenne ou suggère ces dates.
- **Écart à la SPEC** : §5.1 (champs numériques formatés au lieu d'un sélecteur de date).

## D11. Code technique affiché pour toutes les erreurs de lecture

- **Date** : 2026-09-25.
- **Contexte** : SPEC §5.2 prévoit un identifiant technique « en petit » pour la seule erreur inattendue. Sur appareil réel, des erreurs présentées comme « délai dépassé » ou « document retiré » avaient d'autres causes ; or aucun journal n'est permis (SPEC §8), en debug comme en release : le code affiché est le seul moyen de diagnostic.
- **Décision** : l'écran de lecture affiche, sous le message en français, le code complet de la `SceauException` pour **toutes** les erreurs. Le message est choisi sur le code de base (`ReadingErrors.messageFor`, texte avant le premier `-`). Les codes `TIMEOUT`, `CONNECTION_LOST` et `UNEXPECTED` portent un suffixe de diagnostic fait uniquement d'éléments sans donnée personnelle : étape (`SECURE_CHANNEL`…), octet INS de la commande en cours (`INS86`), longueur de l'APDU arrondie à la dizaine (`L10`), sous-type d'erreur d'E/S pris dans une liste fermée de messages d'`android.nfc` (`IO-TRANSCEIVE_FAILED`, `IO-TOO_LONG`, `IO-SERVICE_DIED`, sinon `IO-OTHER` ou `IO-NO_MESSAGE` ; le message brut n'est jamais repris), classe d'exception et mot d'état SW. Exemple : `TIMEOUT-SECURE_CHANNEL-INS86-L10`. Liste complète dans `docs/protocol.md` §5.
- **Justification** : diagnostiquer sur appareil sans rien écrire dans les journaux ; le suffixe ne contient ni donnée lue, ni clé, ni octets d'APDU.
- **Écart à la SPEC** : §5.2 (identifiant technique affiché pour toutes les erreurs, pas seulement l'erreur inattendue).

## D12. Délai de réponse de 60 s pendant l'authentification

- **Date** : 2026-09-25.
- **Contexte** : un vrai passeport français, après plusieurs tentatives ratées, restait muet à la première commande d'authentification (premier GENERAL AUTHENTICATE de PACE, puis EXTERNAL AUTHENTICATE de BAC) sur Fairphone 3 et Fairphone 5 : la lecture échouait en délai dépassé (`TIMEOUT-SECURE_CHANNEL-INS82-L50`). Sans limite courte, il a mis 23,3 s à répondre au premier GENERAL AUTHENTICATE de PACE : c'est la contre-mesure anti-force brute des puces, qui imposent un délai croissant après des essais ratés.
- **Décision** :
  - pendant PACE et BAC, le délai de réponse du transport est porté à 60 s (`SecureChannel.AUTHENTICATION_TIMEOUT_MILLIS`), puis rétabli à sa valeur précédente (10 s, `IsoDepTransport.DEFAULT_TIMEOUT_MILLIS`) pour la lecture ;
  - `IsoDepTransport` relit `IsoDep.getTimeout()` après chaque affectation et classe un échec en délai dépassé d'après le délai effectivement retenu par le système (échec survenu après 90 % de ce délai) ;
  - après 5 s dans l'étape « Ouverture du canal sécurisé », l'écran de lecture affiche un message d'attente (la puce fait patienter, jusqu'à une minute) ; le message « CAN ou MRZ incorrects » prévient qu'un essai raté peut allonger ce délai ;
  - un délai dépassé pendant PACE arrête la lecture (`TIMEOUT-…`), sans repli sur BAC (décision D13).
- **Justification** : couper plus tôt ne laisse jamais la puce répondre, et chaque essai interrompu peut aggraver la pénalité. Relancer BAC sur une puce en pénalité ajouterait un essai interrompu.
- **Écart à la SPEC** : aucun ; précise §5.2 (« Délai dépassé ») et §6.1 étape 2.

## D13. Repli de PACE sur BAC pour une clé MRZ

- **Date** : 2026-09-25.
- **Contexte** : SPEC §6.1 étape 2 prévoit PACE si EF.CardAccess l'annonce, BAC sinon. Un passeport français réel (ISO 14443-B, Fairphone 3) annonçait PACE mais ne répondait pas à son premier GENERAL AUTHENTICATE, puis perdait la liaison. ICAO 9303 impose encore aux documents qui annoncent PACE d'accepter BAC pendant la transition.
- **Décision** : avec une clé MRZ, si PACE échoue autrement que par un refus de la clé (SW `63xx`) ou un délai dépassé, c'est-à-dire sur un SW inattendu, une erreur de JMRTD ou une perte de liaison, la liaison est réinitialisée par `CardTransport.reconnect()` (méthode ajoutée à l'interface, vide par défaut ; `IsoDepTransport` l'implémente par `close()` puis `connect()`, délai réappliqué), l'applet ICAO est sélectionnée en clair, puis BAC est mené. Avec un CAN, aucun repli : BAC ne sait pas utiliser un CAN, l'erreur ressort telle quelle (refus ou échec de PACE : `ACCESS_DENIED`). Une reconnexion impossible donne `CONNECTION_LOST-SECURE_CHANNEL-RECONNECT`. Tests : `PaceFallbackTest`.
- **Justification** : lire les documents dont la mise en œuvre de PACE est défaillante, sans multiplier les essais de clé (un refus explicite arrête tout) ni relancer une puce en pénalité (D12).
- **Écart à la SPEC** : §6.1 étape 2 (BAC tenté après l'échec d'un PACE annoncé) ; §6 (`CardTransport` gagne une méthode, `readAndVerify` inchangée).

## D14. Lecture de la puce : APDU courtes, DG2 facultatif, journaux et exceptions de JMRTD

- **Date** : 2026-09-25.
- **Contexte** : points de mise en œuvre de la lecture (`core/src/main/kotlin/…/reading/`) que la SPEC ne tranche pas, ou qu'elle ne permet de respecter qu'en partie.
- **Décision** :
  - **APDU courtes** : la longueur maximale transmise à JMRTD est celle du transport, plafonnée à 256 (`Chip`) ; aucune APDU étendue n'est émise, beaucoup de puces les refusant. Lecture par blocs de 223 octets, sans SFI (SELECT puis READ BINARY), MAC des réponses toujours vérifié ;
  - **DG2 manquant toléré** : DG2 est toujours demandé, mais une puce qui ne le fournit pas n'interrompt pas la lecture : la photo est absente et la ligne « Empreintes » ne porte que sur les DG lus. DG1 et EF.SOD restent obligatoires ; EF.COM est facultatif (le SOD indique alors les DG présents) ;
  - **journaux de JMRTD et SCUBA coupés** : leurs loggers `java.util.logging` (`org.jmrtd`, `net.sf.scuba`) sont mis au niveau `OFF` au début de chaque lecture (`LibraryLogging`), car ils écrivent des APDU en clair ;
  - **exceptions sans cause attachée** : les messages des exceptions de JMRTD contiennent des APDU en hexadécimal, donc potentiellement des données lues. Aucune exception de JMRTD n'est propagée ni attachée comme cause à une `SceauException` ; seul un identifiant technique est construit (étape, étiquette, classe, SW : `technicalCode`) ;
  - **tampons non effaçables** : JMRTD (flux de lecture des fichiers, objets de la LDS, messagerie sécurisée) et OpenJPEG (décision D3) gardent des copies intermédiaires des données dans des tampons internes qu'ils libèrent sans remise à zéro. Sceau remet à zéro ses propres tableaux (`DocumentData.wipe()`, tampons natifs de `jp2_decode.c`) mais ne peut pas atteindre ceux-là.
- **Justification** : compatibilité la plus large ; SPEC §8 (aucune donnée lue dans un journal ni une exception). La limite sur les tampons découle des bibliothèques retenues (SPEC §3, D3) ; ces copies restent dans la mémoire du processus, ne sont ni écrites sur disque ni journalisées.
- **Écart à la SPEC** : §6.1 étape 4 (DG2 absent toléré) ; limite de §8 (remise à zéro de DG1, DG2, DG11 et DG12 garantie pour les seuls tableaux de Sceau).

## D15. Passive et Active Authentication : points laissés ouverts par la SPEC

- **Date** : 2026-09-25.
- **Contexte** : SPEC §6.1 étapes 5 et 7 ; décision D6.
- **Décision** :
  - **DS absent du SOD** : il est cherché dans le magasin par (émetteur, numéro de série) parmi les ancres, ou par identifiant de clé (SKI) si le `SignerIdentifier` est de cette forme. Introuvable : lignes « Signature du SOD » et « Chaîne de certification » `NOT_AVAILABLE` avec le détail `DsCertificateMissing`, « Validité du DS » `NOT_AVAILABLE`, d'où le verdict « Émetteur inconnu » ;
  - **durée usuelle d'estimation** : 10 ans pour tous les documents, quel que soit le code de DG1 (passeports et CNIe depuis 2021 pour un adulte). Une date de délivrance **estimée** qui tombe dans la période du DS donne `OK` ; hors de la période, `NOT_AVAILABLE`, jamais `FAILED` (D6) ;
  - **validité calendaire des CSCA et des certificats de lien non vérifiée** : seules leurs signatures comptent dans la chaîne (`CertificateChains`), un document restant valide jusqu'à dix ans après l'expiration de son CSCA. La période du DS, elle, est vérifiée à la date de délivrance. Les émetteurs candidats sont cherchés par Authority Key Identifier puis par nom (un candidat de même nom mais de SKI différent est écarté), à travers 8 liens au plus, jusqu'à un CSCA auto-signé du magasin ;
  - **AA sur clé EC sans algorithme de hachage annoncé** (DG14 sans `ActiveAuthenticationInfo`, cas hors norme rencontré en pratique) : ECDSA est essayé successivement avec SHA-1, SHA-224, SHA-256, SHA-384 et SHA-512 ; le premier qui vérifie donne `OK`. Clé RSA : ISO/IEC 9796-2 schéma 1, hachage désigné par le trailer (implicite : SHA-1).
- **Justification** : ne jamais déclarer faux un document authentique sur une estimation ou sur l'expiration normale d'un CSCA ; accepter les puces réelles qui s'écartent de la norme sans affaiblir la vérification (la signature reste vérifiée avec la clé de DG15, elle-même couverte par le SOD).
- **Écart à la SPEC** : aucun ; précise §6.1 étapes 5 et 7.

## D16. Magasin de confiance : index, CSCA expiré, auto-signature, préchargement

- **Date** : 2026-09-25.
- **Contexte** : SPEC §7.1 et §7.2 ; décision D1.
- **Décision** :
  - **`trust/index.txt`** énumère les fichiers de `core/src/main/resources/trust/` (un nom par ligne) : lister un répertoire du classpath n'est pas fiable dans un APK. `TrustStoreFingerprintTest` vérifie que l'index, le répertoire et `docs/trust-store.md` listent les mêmes fichiers ;
  - le **CSCA passeport ANTS 2010**, expiré en mars 2026, est conservé : il a signé des DS dont les passeports, valables dix ans, circulent encore (voir D15, validité des CSCA non vérifiée) ;
  - **`TrustAnchor.isSelfSigned`** se déduit des identifiants de clé : sujet égal à l'émetteur et AKI égal au SKI, ou AKI absent et SKI présent ; la signature n'est vérifiée qu'en repli, quand ces extensions ne permettent pas de conclure. Vérifier la signature des 590 ancres (dont 160 clés EC à paramètres explicites) prenait environ 35 s sur Fairphone 3 à l'ouverture de l'écran Magasin de confiance ; le résultat est identique sur les 590 ancres, en moins d'une seconde. La construction de la chaîne DS → CSCA vérifie toujours chaque signature (D15) ;
  - **préchargement** : `SceauApplication` charge le magasin en arrière-plan au démarrage du processus, pour que ni la première lecture ni l'écran Magasin de confiance n'attendent. Une erreur de préchargement est ignorée : l'accès suivant retente le chargement et la signale.
- **Justification** : fiabilité du chargement dans un APK ; vérification des documents encore valides ; performances sur un téléphone ancien.
- **Écart à la SPEC** : aucun ; précise §7.1 et §7.2.

## D17. Module `:testchip` et mode démo réservé à l'APK de debug

- **Date** : 2026-09-25.
- **Contexte** : SPEC §2 prévoit deux modules (`:core`, `:app`) et §9.1 une fabrique de test. Cette fabrique, un simulateur de puce et un document spécimen servent aux tests de `:core` comme à la démonstration de l'interface sans document réel.
- **Décision** :
  - module Kotlin JVM **`:testchip`** (paquet `io.github.mgdx.sceau.testchip`) : PKI factice (CSCA ancien et nouveau, lien, DS, SOD, en RSA et EC), puce simulée à état (`SimulatedChip` : EF.CardAccess, BAC, PACE à mapping générique ECDH, messagerie sécurisée 3DES et AES, Chip Authentication, Active Authentication) écrite sans le code protocolaire de JMRTD, et CNIe spécimen (`SimulatedDocuments.frenchIdCard()` : identité SPECIMEN / MARIANNE, CAN 123456, portrait JPEG 2000 synthétique). `:core` l'utilise en `testImplementation` seulement ; son code principal n'en dépend pas. Voir `testchip/README.md` ;
  - **mode démo** : entrée « Simuler une CNIe (démo) » du menu de l'accueil, dans l'APK de debug seulement. `:testchip` y est en `debugImplementation` ; l'objet `DemoMode` existe en deux versions : `app/src/debug/` lit la CNIe simulée, `app/src/release/` est vide (`isAvailable = false`, l'entrée de menu n'est pas affichée). La lecture démo suit le même chemin que celle d'un document réel (`SessionViewModel.launchRead`), avec les mêmes règles d'effacement, et l'écran de résultat affiche le bandeau « Document simulé — démonstration » ;
  - **magasin de test distinct** : la démo vérifie la CNIe simulée avec le magasin de test du document (`TestTrustStore`, CSCA `CSCA-TEST-FRANCE` généré à la volée) ; ce magasin ne sert qu'à cette lecture et ne remplace ni ne complète jamais le magasin réel. La clé saisie est oubliée au lancement de la démo, pour qu'un vrai document présenté ensuite ne soit jamais lu avec la clé ou le magasin de la démo ;
  - **preuve que rien n'entre dans le release** : `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` ne contient pas `project :testchip` (présent dans `debugRuntimeClasspath`, vérifié le 2026-09-25), et la version release de `DemoMode` ne référence aucune classe de `:testchip` ; `DemoModeTest` (tests unitaires de la variante debug) lit la CNIe simulée de bout en bout ;
  - lint : `TrulyRandom` désactivé dans `:testchip` (aléa à graine fixe, voulu pour des clés reproductibles) ; `@Suppress("GetInstance")` sur `SimCrypto.aesBlock` (chiffrement AES d'un seul bloc imposé par ICAO 9303-11), signalé par le lint de `:app` qui analyse ses dépendances.
- **Justification** : tester `:core` contre une mise en œuvre indépendante de la puce ; permettre une démonstration et des essais d'interface sans document d'identité réel, sans qu'aucune clé ni aucun certificat de test n'atteigne l'APK distribué.
- **Écart à la SPEC** : §2 (troisième module) ; §5 (entrée de menu hors SPEC, APK de debug uniquement).

## D18. Couleurs dynamiques désactivées

- **Date** : 2026-09-25.
- **Contexte** : Material 3 propose sur Android 12+ des couleurs tirées du fond d'écran (couleurs dynamiques).
- **Décision** : `SceauTheme` utilise toujours la palette du logo (marine, ivoire, vert), en clair comme en sombre (`dynamicColor = false` par défaut, jamais activé). Le mode sombre suit le système.
- **Justification** : une application de vérification doit garder une apparence stable et reconnaissable ; une couleur primaire rouge ou verte tirée du fond d'écran brouillerait la lecture des verdicts, qui reposent sur des couleurs propres à chacun (contraste vérifié par `VerdictColorsTest`).
- **Écart à la SPEC** : aucun (§2 : Material 3, mode sombre suivant le système).

## D19. Rapport de vérification non sérialisé

- **Date** : 2026-09-25 (mise à jour de la SPEC décidée par l'utilisateur).
- **Contexte** : SPEC §6.2 prévoyait un `VerificationReport` « sérialisable pour les tests uniquement (jamais persisté par l'appli) ».
- **Décision** : le rapport n'implémente aucune sérialisation. La mention « sérialisable pour les tests » est retirée de SPEC §6.2.
- **Justification** : les tests de `:core` et de `:app` construisent leurs cas avec la PKI factice et la puce simulée de `:testchip` (D17) et vérifient le rapport en mémoire ; aucun n'a eu besoin de le sérialiser. Ne pas offrir de sérialisation supprime aussi un chemin par lequel des données lues pourraient être écrites (SPEC §8).
- **Écart à la SPEC** : aucun désormais (SPEC §6.2 mise à jour le 2026-09-25).

## D20. Chip Authentication avant la lecture des données

- **Date** : 2026-09-28.
- **Contexte** : SPEC §6.1 énumère la lecture de DG1, DG2, DG14, DG15, DG11 et DG12 (étape 4), puis la Passive Authentication (étape 5), puis la Chip Authentication (étape 6). Menée après la lecture, la CA ne liait pas à la puce authentifiée les données lues avant elle, sous les clés de PACE ou de BAC : limite documentée par l'audit de sécurité du 2026-09-25.
- **Décision** :
  - ordre de lecture : EF.COM, EF.SOD, puis DG14 s'il est annoncé (EF.COM ou SOD), puis la Chip Authentication si DG14 annonce une clé, puis DG1, DG2, DG15, DG11 et DG12 sous la messagerie sécurisée de la CA, puis la Passive Authentication (qui vérifie toujours l'empreinte de DG14 : un DG14 falsifié met la ligne des empreintes en échec), puis l'Active Authentication ;
  - la CA a lieu pendant l'étape `READ_DATA` ; `Step` est inchangé, `VERIFY_CHIP` reste émise et ne couvre plus que l'AA. Les codes d'erreur de la CA commencent par `READ_DATA-CA` au lieu de `VERIFY_CHIP-CA` ;
  - la confirmation explicite du nouveau canal (SELECT de DG1 et READ BINARY d'un octet, MAC vérifié) est gardée avant la lecture des DG : c'est elle, et non la lecture de DG1 qui suit, qui décide de la ligne CA, si bien qu'un clone produit une ligne CA en échec et non une erreur de lecture ;
  - CA en échec : ligne `FAILED`, et la lecture continue. Si la puce a refusé l'échange, JMRTD garde l'ancienne messagerie ; la même confirmation vérifie que la puce y répond encore. Si la puce ne répond plus sous la messagerie courante, la liaison est réinitialisée (`CardTransport.reconnect()`, nouvel état JMRTD), puis le canal est rétabli avec la même clé dans la même lecture (EF.CardAccess, PACE ou BAC, repli compris : `SecureChannel.reestablish`), et les DG sont lus sous ce canal. La clé n'est pas conservée au-delà de la lecture ;
  - règle V1 inchangée : DG14 ou DG15 signés dans le SOD mais non fournis donnent un échec ;
  - `readAndVerify`, `VerificationReport`, `CheckId` et `Step` sont inchangés. Tests : `ChipAuthenticationOrderTest` (la puce simulée note sous quelle session chaque fichier est servi).
- **Justification** : procédure d'inspection d'ICAO 9303-11 (§6.2) : la CA suit la lecture de DG14 et précède celle des autres données, qui sont alors lues sous des clés que seule la puce détentrice de la clé privée de DG14 peut dériver. Un relais ou une puce qui substituerait les données après l'authentification est ainsi écarté. Garder la lecture après une CA ratée permet d'afficher les données, le verdict restant « Échec ».
- **Écart à la SPEC** : §6.1, ordre des étapes 4 à 6 (la CA précède la lecture de DG1, DG2, DG15, DG11, DG12 et la Passive Authentication) ; §6.1 étape 6 précisée (reprise du canal après une CA ratée).

## D21. PACE-CAM (Chip Authentication Mapping)

- **Date** : 2026-09-28.
- **Contexte** : ICAO 9303-11 §4.4 définit le mapping PACE-CAM, par lequel la puce prouve détenir sa clé privée de Chip Authentication pendant PACE. `SecureChannel` acceptait n'importe quel mapping annoncé sans rien vérifier de propre à CAM : avec une puce CAM, la preuve fournie était ignorée. SPEC §6.1 ne prévoit que la CA via DG14.
- **Décision** :
  - si PACE aboutit avec le mapping CAM, EF.CardSecurity (FID 011D au MF) est lu sous la messagerie de PACE avant la sélection de l'application ; JMRTD déchiffre `CA_IC` (`PACECAMResult`) et fournit `PK_Map,IC` ;
  - à l'étape `VERIFY_CHIP`, une fois l'État émetteur de DG1 connu : EF.CardSecurity est vérifié avec les mêmes règles que le SOD (`PassiveAuthenticator.verifyCardSecurity`, qui réutilise la recherche du DS, la vérification de signature et `CertificateChains` : profil d'émetteur, keyUsage du DS, pays cohérents, algorithmes faibles refusés), sa `ChipAuthenticationPublicKeyInfo` donne `PK_IC`, puis `PK_Map,IC = KA(CA_IC, PK_IC, D_IC)` est contrôlé (ECDH ou DH, selon les clés) ;
  - résultat sur la ligne `CHIP_AUTHENTICATION` (pas de nouveau `CheckId`), avec un nouveau détail `CheckDetail.ChipAuthentication(method)` (`DG14` ou `PACE_CAM`) affiché dans la liste de contrôle. CAM réussi : la CA via DG14 n'est pas refaite, les données étant déjà lues sous le canal de PACE. CAM mené mais EF.CardSecurity absent, mal formé, à signature ou chaîne fausse, ou contrôle KA faux : `FAILED` (codes `VERIFY_CHIP-CAM-…`). Algorithme inconnu : `UNSUPPORTED_ALGORITHM`. Verdict inchangé (`Verdicts.compute`) ;
  - émetteur d'EF.CardSecurity absent du magasin : `NOT_AVAILABLE` si la chaîne du SOD est elle aussi sans CSCA connu (verdict « Émetteur inconnu », comme tout document d'un pays absent du magasin), `FAILED` sinon ;
  - la validité calendaire du DS d'EF.CardSecurity n'est pas contrôlée (elle l'est pour le DS du SOD, ligne « Validité du DS ») ;
  - `:testchip` simule PACE-CAM en ECDH (A_IC, EF.CardSecurity signé par le DS factice). Tests : `PaceCamEndToEndTest`.
- **Justification** : ICAO 9303-11 §4.4.3.5 impose au terminal, après PACE-CAM, d'authentifier `PK_IC` par EF.CardSecurity et de contrôler `CA_IC` ; sans quoi une puce clonée annonçant CAM passerait sans preuve. Réutiliser `PassiveAuthenticator` garantit que EF.CardSecurity n'est pas vérifié plus faiblement que le SOD. Traiter un émetteur inconnu comme la chaîne du SOD évite de déclarer « Échec » un document authentique d'un pays absent du magasin, sans ouvrir de déclassement : si le SOD remonte à un CSCA connu, un EF.CardSecurity non rattachable est un échec.
- **Écart à la SPEC** : §6.1 étape 6 (la Chip Authentication peut aussi être prouvée par PACE-CAM, auquel cas la CA via DG14 n'est pas menée) ; §5.3 (détail de la méthode dans la ligne « Chip Authentication »).

## D22. Nombre et taille cumulée des Master Lists importées limités

- **Date** : 2026-09-28.
- **Contexte** : audit de sécurité du 2026-09-25, recommandations de durcissement (« limiter le nombre de Master Lists importées »). Toutes les Master Lists importées sont relues et fusionnées au préchargement du magasin (D16). Chaque fichier est déjà borné à 20 Mo à la lecture (`MAX_MASTER_LIST_BYTES`, SPEC §5.4), mais rien ne bornait leur nombre, donc ni la mémoire ni le temps de démarrage.
- **Décision** : `ImportLimits` (`app/.../trust/ImportLimits.kt`) fixe **10 Master Lists importées au plus** et une **taille cumulée de 40 Mo au plus** (deux fois la limite par fichier, qu'elle réutilise). La règle est vérifiée avant l'analyse du fichier, pour ne pas demander de confirmer un import voué à l'échec, puis de nouveau sous verrou au moment de l'écriture (`TrustStoreRepository.import`). Au-delà, l'import est refusé par un message qui invite à supprimer les certificats importés. Une liste déjà présente (même empreinte SHA-256, donc même nom de fichier) reste sans effet et ne compte pas, même quand les limites sont atteintes. `:core` n'est pas modifié.
- **Justification** : une Master List réelle pèse de l'ordre du mégaoctet (celle du BSI embarquée : 0,9 Mo) et couvre déjà des dizaines d'émetteurs ; dix listes suffisent largement (quelques autorités publiant une Master List, plus leurs mises à jour), et 40 Mo représentent plus de quarante fois la liste du BSI. Ces bornes gardent le préchargement dans la mémoire d'un téléphone ancien (Fairphone 3) quels que soient les fichiers choisis par l'utilisateur.
- **Écart à la SPEC** : aucun ; précise SPEC §5.4 (import de Master List).

## D23. Décodage JPEG 2000 dans un processus isolé

- **Date** : 2026-09-28.
- **Contexte** : le portrait (DG2) et les images de DG12 en JPEG 2000 viennent d'une puce potentiellement hostile et sont décodés par OpenJPEG en JNI (D3). C'est la seule surface native exposée à des octets hostiles ; jusqu'ici elle tournait dans le processus de l'app, avec l'accès aux données lues, à la clé d'accès et aux fichiers de l'app. Recommandation de durcissement de l'audit de sécurité du 2026-09-25.
- **Décision** :
  - le décodage est confié au service `jp2/Jpeg2000Service`, déclaré dans le manifeste `android:isolatedProcess="true"`, `android:exported="false"`, `android:process=":jp2"` : UID isolé, aucune permission, aucun accès aux fichiers ni aux services de l'app. `libsceau_jp2.so` n'est chargée que là : `Jpeg2000Decoder` (inchangé, méthode `nativeDecode` et règle R8 comprises) n'est appelé que par le service. `SceauApplication`, qu'Android crée aussi dans ce processus, n'y fait rien (`isIsolatedProcess()` : `Process.isIsolated()` dès l'API 28, plage des UID isolés 99000 à 99999 avant) ;
  - côté app, `jp2/IsolatedJpeg2000Decoder.decode(context, bytes)` (`suspend`) lie le service, transfère, puis se détache. Un processus neuf sert une seule image : dans `onDestroy`, le service efface ses tampons puis termine son processus, et avec lui tout ce qu'OpenJPEG a laissé dans son tas. Les décodages restent sérialisés (un `Mutex` dans le client, en plus de celui de l'écran de résultat) ;
  - **transport** : binder brut (`Binder.onTransact`, sans AIDL, donc sans toucher à `build.gradle.kts`), jeton d'interface vérifié, en mémoire uniquement. Le flux part en morceaux de 256 Kio (`TX_BEGIN`, `TX_APPEND`), le service répond largeur et hauteur (`TX_DECODE`), puis les pixels ARGB reviennent par morceaux de 64 Ki pixels (`TX_READ`), chacun loin de la limite de 1 Mo du tampon de transaction : un portrait de 480 × 640 (1,2 Mo) passe en cinq morceaux. `Jpeg2000Protocol` (Kotlin pur, testé sur JVM) fixe les bornes : flux de 1 octet à 16 Mo, image de 2048 × 2048 au plus, morceaux reçus dans l'ordre strict, sans débordement. Les tableaux du flux, des morceaux et des pixels sont remis à zéro des deux côtés après usage ;
  - **robustesse** : processus isolé mort (bombe, bug natif : `DeadObjectException`), délai de 10 s dépassé (démarrage du processus et transferts compris), liaison refusée ou perdue, réponse hors protocole : `null`, l'image n'est pas affichée, comme pour un flux invalide. Après un délai dépassé, le client se détache, ce qui termine le processus isolé et débloque la transaction en cours ; des pixels reçus plus tard sont effacés aussitôt.
- **Justification** : une faille d'OpenJPEG exploitée par une puce forgée ne donne plus accès qu'à un processus sans droits, qui ne voit que l'image en cours ; le processus de l'app ne peut plus être abattu par un plantage natif ou une bombe mémoire. Le découpage en morceaux fonctionne dès l'API 26, sans `SharedMemory` (API 27) ni double chemin. Coût : un démarrage de processus par image (quelques centaines de millisecondes sur un téléphone ancien) et une copie des pixels de plus, négligeables pour trois images au plus.
- **Limites** : les tampons natifs des `Parcel` binder (flux et pixels en transit) sont libérés sans remise à zéro, l'API publique ne permettant pas de marquer une transaction comme sensible ; ils restent en mémoire des deux processus, jamais sur disque. Un plantage natif du processus isolé laisse, comme avant D23 dans le processus de l'app, un rapport système (tombstone, lisible par le seul système, qui peut contenir quelques centaines d'octets de mémoire autour des registres, donc de l'image en cours) et, selon la version d'Android, un message de plantage : à vérifier sur appareil.
- **Écart à la SPEC** : aucun (SPEC §8 renforcée ; complète D3).

## D24. Règles R8 réduites pour BouncyCastle, JMRTD, SCUBA et cert-cvc

- **Date** : 2026-09-28.
- **Contexte** : `rules.keep` gardait en entier (`-keep class … { *; }`) les paquets de fournisseurs de BouncyCastle (`jcajce.provider`, `jce.provider`), JMRTD, SCUBA et cert-cvc. R8 ne pouvait ni retirer leurs membres inutilisés ni les obfusquer (recommandation de l'audit du 2026-09-25).
- **Décision** :
  - BouncyCastle : `-keep class org.bouncycastle.jcajce.provider.** { <init>(...); }` et de même pour `org.bouncycastle.jce.provider.**`. Seuls le nom des classes et leurs constructeurs sont gardés. Le fournisseur charge ses classes `$Mappings` par leur nom (`ClassUtil.loadClass` puis `newInstance`), puis enregistre chaque implémentation sous forme de nom de classe, que `Provider.Service` instancie par réflexion. Les méthodes appelées ensuite redéfinissent des SPI Java (`CipherSpi`, `SignatureSpi`, `KeyFactorySpi`…) ou `AlgorithmProvider.configure`, que R8 garde de lui-même pour une classe instanciée. `<init>(...)` plutôt que `<init>()` couvre les services construits avec un paramètre (`CertStore`). Inventaire fait sur BC 1.86 : 1 403 services enregistrés, dont 1 361 dans ces deux paquets ; les 42 autres (post-quantique, `org.bouncycastle.pqc.jcajce.provider`) n'étaient déjà pas gardés et restent absents, car Sceau ne s'en sert pas. Les attributs `SupportedKeyClasses` ne nomment que des interfaces du JDK. Les autres réflexions de BC (`GcmSpecUtil`, `SpecUtil`, `BaseBlockCipher`, `DRBG`, `RFC3280CertPathUtilities`) visent des classes du JDK.
  - JMRTD, SCUBA et cert-cvc : plus aucune règle. Le bytecode de JMRTD 0.8.8, SCUBA 0.0.21 et cert-cvc 1.4.13 ne fait que trois réflexions. `Country.values()` appelle `values()` sur les énumérations de SCUBA, ce que couvre la règle `enum` de `proguard-android-optimize.txt`. `CardService.getInstance` instancie `IsoDepCardService` ou `TerminalCardService` par leur nom, et `CVCProvider` est un fournisseur JCA : Sceau n'utilise aucun des deux (`TransportCardService` étend `CardService` directement). Les noms de loggers coupés par Sceau (`org.jmrtd`, `net.sf.scuba`) sont des chaînes littérales dans ces bibliothèques, donc indifférents à l'obfuscation. Les `getSimpleName()` de quelques `toString()` de JMRTD ne servent qu'à l'affichage.
  - Inchangées : `-dontwarn javax.naming.**` et la règle JNI de `Jpeg2000Decoder`.
- **Justification** : APK release non signés (`./gradlew :app:assembleRelease`), en octets :

  | APK | Avant | Après | Gain |
  |---|---|---|---|
  | arm64-v8a | 3 661 003 | 3 464 396 | −196 607 (−5,4 %) |
  | armeabi-v7a | 3 611 019 | 3 414 412 | −196 607 (−5,4 %) |
  | x86 | 3 698 123 | 3 501 516 | −196 607 (−5,3 %) |
  | x86_64 | 3 696 221 | 3 499 614 | −196 607 (−5,3 %) |
  | universel | 4 466 750 | 4 270 143 | −196 607 (−4,4 %) |

  `classes.dex` passe de 5 561 300 à 5 058 276 octets (−9 %). D'après `mapping.txt`, cert-cvc disparaît (62 classes, jamais atteintes), JMRTD passe de 235 à 131 classes et SCUBA de 40 à 17, toutes obfusquées ; les classes de BouncyCastle hors fournisseur sont réduites, et celles du fournisseur perdent leurs membres inutilisés. L'essentiel de BC reste présent : chaque service enregistré référence son moteur. Aller plus loin demanderait de remplacer `BouncyCastleProvider` par un fournisseur restreint aux algorithmes ICAO, ce qui touche au code de `:core` et sort du périmètre de cette décision. Garder par une liste explicite les seules classes enregistrées gagnerait peu (les classes abstraites restent de toute façon en tant que supertypes) et casserait à chaque montée de version de BC.

  Vérification du code minifié, faite à la main (les tests JVM ne passent pas par R8) : R8 (`build-tools/37.0.0/lib/d8.jar`, mode `--classfile`, JDK 17 comme bibliothèque) minifie `core.jar`, `testchip.jar`, leurs dépendances et un petit programme de contrôle, avec `proguard-android-optimize.txt`, les règles des coroutines et `rules.keep`. Seules les ressources (`trust/`, portrait du spécimen) sont remises à côté, sans aucune classe non minifiée. Le programme exécute ensuite sur le code minifié : chargement du magasin embarqué (Master List CMS du BSI, 590 ancres), import de cette même Master List, puis `readAndVerify` sur la puce simulée en cinq variantes : CNIe PACE CAN et PACE MRZ (brainpoolP256r1, messagerie AES, DS ECDSA, CA AES, AA ECDSA), puis passeports BAC (3DES) avec CSCA/DS RSA PKCS#1, RSA-PSS et ECDSA, CA et AA RSA. Résultat : verdict Authentique et les sept contrôles OK partout. Témoin négatif : sans les deux règles BC, le même programme échoue dès le chargement (`CertificateException: X.509 not found`). Limites : R8 visait le JDK et non `android.jar`, et `:testchip` était minifié avec le reste, donc il peut garder des membres de JMRTD dont Sceau seul n'aurait pas besoin. Le contrôle ne remplace donc pas un essai de l'APK release sur appareil : démarrage (préchargement), import d'une Master List et lecture d'une vraie CNIe et d'un vrai passeport.
- **Écart à la SPEC** : aucun. La SPEC ne fixe pas les règles R8 ; le gain va dans le sens de l'objectif de moins de 8 Mo par APK (SPEC §2, D2).

## D25. Builds reproductibles des APK release

- **Date** : 2026-09-29.
- **Contexte** : pour publier sur F-Droid les APK signés par le développeur (et non des APK signés par F-Droid), F-Droid doit reconstruire depuis les sources des APK identiques octet pour octet ([Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)). Mesure faite sur le commit `b12ec5a` : deux clones construits dans des chemins différents (`./gradlew :app:assembleRelease`, mêmes JDK, NDK et CMake) donnaient des APK différents pour les cinq sorties. Seul `lib/<abi>/libsceau_jp2.so` différait, sur 20 octets : la note `.note.gnu.build-id`. L'éditeur de liens calcule le build-id sur le `.so` non strippé, dont les informations de débogage contiennent les chemins absolus des sources (`app/src/main/cpp`, OpenJPEG compris), du répertoire de build `.cxx` et des en-têtes du NDK ; le strip retire ces informations mais garde le build-id. Le reste (`classes.dex`, `resources.arsc`, `baseline.prof`, `META-INF/version-control-info.textproto`, ordre et horodatages des entrées ZIP) était déjà identique.
- **Décision** :
  - `app/src/main/cpp/CMakeLists.txt` : `-ffile-prefix-map` (qui couvre `__FILE__` et les informations de débogage) remplace la racine des sources par `src`, le répertoire de build CMake par `build` et la racine du NDK par `ndk`, par `add_compile_options` placé avant `add_subdirectory(openjpeg)` pour s'appliquer aux deux cibles. Le build-id est gardé (utile pour symboliser une trace native), il devient stable ;
  - `app/build.gradle.kts` : `dependenciesInfo { includeInApk = false; includeInBundle = false }`. Ce bloc, ajouté par AGP au bloc de signature des APK signés et chiffré pour Google Play, n'apparaît pas dans les APK non signés mais ferait différer l'APK signé du développeur de celui de F-Droid, qui le refuse de toute façon ;
  - `scripts/check-reproducible.sh` fait la double construction (deux clones au même commit, chemins de longueurs différentes, SDK vu sous un autre chemin pour le second) et compare les APK ; procédure et versions à figer dans `docs/reproducible-builds.md`.
- **Justification** : la mesure après correctif, avec un clone et un SDK sous deux chemins différents, donne cinq APK identiques octet pour octet (SHA-256 des APK non signés, mesurés sur un commit intermédiaire de cette branche, car ils changent à chaque commit par `version-control-info.textproto` : arm64-v8a `366a9eba…`, armeabi-v7a `e346e1f6…`, x86 `00430b49…`, x86_64 `13f3bfb7…`, universel `0ccddb62…`). Le témoin négatif (même script sur le correctif sans le remappage du NDK) montre que le seul changement de chemin du SDK suffit à faire différer les cinq APK : le remappage du NDK est nécessaire, F-Droid n'installant pas le SDK au même endroit que le développeur. Les tailles des APK sont inchangées à l'octet près (le build-id a une taille fixe). `-Wl,--build-id=none` aurait aussi supprimé l'écart, au prix de la symbolisation et sans retirer les chemins absolus du `.so` non strippé.
- **Écart à la SPEC** : aucun. La SPEC vise F-Droid (§2) et publie les APK sur les releases (§10) sans fixer le mode de signature ; les builds reproductibles permettent de publier les APK signés par le développeur. Limite : la reproductibilité est vérifiée sur Linux x86_64 ; une construction sur une autre plateforme hôte (préfixe `prebuilt/darwin-x86_64` du NDK, autre binaire du compilateur) n'est pas garantie identique.

## D26. Certificats publiés par leur propre État (sources de statut A)

- **Date** : 2026-09-29 (décision de l'utilisateur).
- **Contexte** : le catalogue `docs/trust-sources.md` recense les publications officielles de CSCA. Parmi les certificats absents du magasin et encore valides, sept viennent de sources de statut A, c'est-à-dire sous licence ouverte ou publiées par un État pour ses propres documents sans restriction de réutilisation. Ils ne sont dans aucune Master List embarquée : les documents concernés donnaient « Émetteur inconnu », ou le donneront dès que le nouveau CSCA signera des DS (bascule britannique du 2026-09-26).
- **Décision** : embarquer ces sept certificats, sous forme de fichiers DER dans `core/src/main/resources/trust/`, avec une nouvelle source `TrustSource.NATIONAL` :
  - GB : CSCA GBR_2026_Root et lien GBR_2021-2026 (OGL v3, A1) ;
  - GR : CSCA des titres de séjour CSCAeRP-HELLAS 003 et lien 002 → 003 (A2) ;
  - GE : CSCA n° 5 et n° 6 « G2 » (A2) ;
  - LU : lien CSCA ePassport → CSCA eTravel Documents (A2).

  Un fichier `.der` dont le nom commence par `ants-` reste de source `ANTS`, et tout autre `.der` devient `NATIONAL`. Priorité en cas de doublon : ANTS, publications nationales, Master List embarquée, imports. L'écran « Magasin de confiance » affiche le badge « Publication nationale », le résultat affiche « certificats publiés par l'État émetteur », et « À propos » liste les pays concernés et l'attribution OGL exigée pour le Royaume-Uni. Provenance, empreintes et contrôles dans `docs/trust-store.md`.
- **Justification** :
  - Ces sources ont le même statut que les certificats de l'ANTS : chaque État publie ses propres CSCA pour qu'on vérifie ses documents. Les sources B (Master Lists italienne, suédoise et néerlandaise, par exemple) attendent une autorisation écrite et ne sont pas embarquées.
  - Une source distincte d'`ANTS` évite d'afficher « ANTS » pour un certificat britannique. Le préfixe de nom suffit et évite un second fichier d'index.
  - Seuls les certificats valides sont ajoutés : les 33 autres certificats nouveaux de statut A sont expirés depuis longtemps et n'apportent rien à des documents en circulation.
  - Taille : 7 fichiers d'environ 1,2 à 1,6 Ko, négligeable devant l'objectif de 8 Mo (D2).
- **Écart à la SPEC** : §7.1 ne listait que l'ANTS et la Master List du BSI, §7.2 que trois sources. SPEC mise à jour (§5.5, §6 contrôle 3, §7.1, §7.2, §7.4).

## D27. Traductions dans 43 langues, choix de la langue par application

- **Date** : 2026-09-29 (décision de l'utilisateur).
- **Contexte** : la v1 ne prévoyait que le français et l'anglais (SPEC §2). Le magasin de confiance couvre 110 pays (Master List du BSI, ANTS, publications nationales) : les porteurs de ces documents, et les agents qui les contrôlent, ne lisent souvent ni l'une ni l'autre langue.
- **Décision** :
  - 43 langues ajoutées : les 24 langues officielles de l'UE et les grandes langues des pays du magasin (de, nl, da, sv, nb, is, es, it, pt, pt-BR, ro, pl, cs, sk, sl, hr, bs, sr en cyrillique, mk, bg, ru, uk, el, lt, lv, et, fi, hu, ga, mt, sq, ar, hébreu sous le qualificatif historique `iw`, tr, ka, zh-CN, zh-TW, ja, ko, hi, vi, th, ms) ;
  - `res/xml/locales_config.xml` (`android:localeConfig`) propose ces langues dans les réglages Android 13+ (langue de l'application). Sous Android 12 et avant, l'application suit la langue du système : pas d'AppCompat pour si peu ;
  - termes ICAO jamais traduits (CAN, MRZ, SOD, CSCA, DG1…DG15, « Master List », « Chip Authentication », « Active Authentication ») ; le reste reprend le vocabulaire d'Android et celui des documents d'identité de chaque pays ; un glossaire par langue dans `docs/traduction/glossaire-<qualificatif>.md` ;
  - pluriels selon les catégories attendues par le lint (par exemple `many` en es, it et pt, `two` en maltais, pas de `many` en hébreu).
- **Justification** : les chaînes étaient déjà toutes externalisées (D5), l'ajout ne touche ni le code ni les écrans. Coût : environ 635 Ko par APK release (3,5 → 4,1 Mo par ABI, 4,9 Mo pour l'universel), sous l'objectif de 8 Mo (D2).
- **Limite** : traductions faites sans relecture par des locuteurs natifs. Les plus fragiles (maltais, irlandais, islandais, géorgien) et le rendu droite-à-gauche (arabe, hébreu) sont à faire relire avant d'être mis en avant ; les doutes relevés sont notés dans les glossaires.
- **Écart à la SPEC** : §2 ne prévoyait que `values-en` ; SPEC mise à jour.

## D28. Contrôle automatique des débordements de texte dans toutes les langues

- **Date** : 2026-09-29.
- **Contexte** : l'interface est traduite en 45 langues (D27), sans relecture visuelle possible : `FLAG_SECURE` interdit toute capture sur appareil, et relire 45 langues à la main à chaque changement de chaîne n'est pas tenable. Un libellé trop long pour son onglet, un titre coupé ou un texte qui sort de l'écran passeraient inaperçus.
- **Décision** :
  - test JVM `TextOverflowTest` (`app/src/test/java/io/github/mgdx/sceau/l10n/`), lancé par `./gradlew check` via `:app:testDebugUnitTest` : Robolectric 4.17 avec son rendu natif (`GraphicsMode.NATIVE` : vrai moteur de texte d'Android et ses polices, écritures non latines comprises) et Compose UI test ; dépendances de test uniquement, plus `ui-test-manifest` en `debugImplementation` (voir `docs/dependencies.md`) ;
  - écran de 360 × 640 dp en xxhdpi, police à 100 %, SDK simulé 37 (le plus haut que gère Robolectric 4.17, égal au `compileSdk`) ;
  - langues lues dans `res/xml/locales_config.xml` à chaque exécution ; la langue et le sens d'écriture (arabe, hébreu) sont changés par les `CompositionLocal` de configuration au-dessus des vrais écrans, sans recréer l'activité. Garde-fou : chaque langue doit résoudre ses propres chaînes, et `ar` et `iw` s'écrire de droite à gauche ;
  - vrais écrans, avec le vrai `SessionViewModel` et le vrai magasin de confiance : Accueil (onglet Carte d'identité avec NFC désactivé, menu de débordement ouvert, onglet Passeport sans NFC avec les trois erreurs de date), Lecture (puce qui fait patienter, obtenue en retenant la CNIe simulée à l'ouverture du canal sécurisé ; six erreurs : `ACCESS_DENIED`, `CAN_WITHOUT_PACE`, `NOT_ICAO`, `CONNECTION_LOST`, `TIMEOUT`, `UNEXPECTED`), Résultat (CNIe simulée du mode démo, verdicts Authentique et Émetteur inconnu, contrôles dépliés ; portrait indisponible, le décodeur JPEG 2000 tournant dans un processus isolé absent sous Robolectric), Magasin de confiance (liste complète, France et Royaume-Uni dépliés), À propos ;
  - pour chaque nœud de texte (arbre non fusionné) : ligne plus large que la boîte du texte ou texte plus haut qu'elle, dernière ligne ellipsée, mot coupé entre deux lignes faute de largeur (écritures à espaces seulement), boîte qui sort d'un conteneur (onglet, barre) ou de l'écran ; un conteneur qui défile arrête le contrôle sur son axe ;
  - toutes les anomalies sont collectées puis comparées à `app/src/test/resources/l10n/known-overflows.txt` (`langue|clé`) : échec si une anomalie n'y figure pas, ou si une ligne ne se produit plus. Un second test vérifie sur des textes factices que chaque sorte de débordement est bien signalée ;
  - tests de `:app` exécutés sur le JDK 25 (celui du démon Gradle et de la CI), avec `--add-exports java.base/jdk.internal.access` que demande Robolectric sur ce JDK ; le code reste compilé pour Java 17.
- **Justification** :
  - Robolectric plutôt qu'un test instrumenté : `check` tourne sans appareil ni émulateur, en CI comme en local. Durée mesurée : environ 20 s pour les 45 langues et 16 écrans ou états (hors téléchargement initial des jars Android de Robolectric).
  - `TextLayoutResult.hasVisualOverflow` n'est pas utilisé pour la largeur : le résultat fourni par la sémantique est remis en page sur toute la largeur des contraintes, si bien que `didOverflowWidth` est vrai pour tout texte plus étroit que son conteneur. La largeur est comparée ligne par ligne, avec une tolérance d'1 dp (les lignes centrées sont arrondies au pixel entier par Android) ; `didOverflowHeight` est gardé.
  - Le mot coupé et la sortie de conteneur ont été ajoutés après un témoin négatif : trois libellés allemands volontairement démesurés (onglet, titres) n'étaient pas signalés, car les composants Material 3 s'agrandissent en hauteur au lieu de rogner. Avec ces contrôles, l'onglet et le titre d'un seul mot démesurés sont signalés (mot coupé), sans aucune fausse alerte dans les 45 langues réelles ; le titre de plusieurs mots, passé sur trois lignes dans une barre agrandie, ne l'est pas (voir Limites).
  - Langue changée dans la composition plutôt que par recréation de l'activité : un seul chargement du magasin de confiance et une seule lecture simulée par état, d'où la durée.
- **Limites** :
  - un titre de barre d'application sur plusieurs lignes n'est pas signalé : la barre de Material 3 s'agrandit et rien n'est rogné ;
  - données exclues : aucune. Les valeurs venues de la puce (`FieldRow`, détails des contrôles) ont un `maxLines` explicite et une ellipse voulue, mais celles du spécimen sont courtes et ne l'atteignent pas ;
  - non couverts : bandeau « document expiré », mention « ancre importée », verdicts « Signature valide, puce non vérifiée » et « Échec », photo affichée, dialogues d'import et de suppression de Master List, messages ponctuels (snackbars) ;
  - le rendu des polices de Robolectric est celui d'Android 37 ; un fabricant qui remplace les polices système peut élargir certains textes.
- **Écart à la SPEC** : aucun sur le produit. La SPEC ne prévoit pas ce contrôle ; il ajoute des dépendances de test (hors APK) et une activité vide de test dans l'APK de debug seulement.

## D29. Onglet Passeport en premier, clavier retiré après la date d'expiration

- **Date** : 2026-10-04.
- **Contexte** : SPEC §5.1 plaçait l'onglet Carte d'identité en premier. Le passeport est le cas d'usage principal, et une fois la date d'expiration tapée le clavier masquait le bouton « Lire » : il fallait le fermer à la main.
- **Décision** :
  - onglet Passeport à gauche et sélectionné par défaut (`DocumentTab.PASSPORT` en tête de l'énumération, valeur par défaut d'`AccessForm`) ;
  - quand la date d'expiration atteint ses 8 chiffres, le clavier est masqué et le focus retiré, que la date soit valide ou non (une erreur reste affichée sous le champ). Seul le passage à 8 chiffres le déclenche : corriger un chiffre d'une date déjà complète ne referme pas le clavier en pleine saisie.
- **Écart à la SPEC** : §5.1 mise à jour.

## D30. Mentions de responsabilité dans l'écran À propos

- **Date** : 2026-10-04.
- **Contexte** : un utilisateur peut se fier au verdict « Authentique » pour conclure une transaction, ou un professionnel l'employer à la place d'un contrôle d'identité réglementé. Le README disait déjà que le verdict ne couvre pas la déclaration de perte ou de vol ; l'application ne le disait pas, et rien ne rappelait l'absence de garantie de la GPL.
- **Décision** : deux paragraphes ajoutés à la section « Cadre d'usage » de l'écran À propos, dans les 45 langues :
  - `about_usage_no_warranty` : Sceau est fourni sans aucune garantie ; le verdict porte sur l'authenticité de la puce et des données signées par l'État émetteur, n'a pas valeur de preuve et ne dit pas si le document a été déclaré perdu ou volé ;
  - `about_usage_not_official` : Sceau n'est ni un outil officiel, ni un service de vérification d'identité certifié, et ne remplace pas les contrôles d'identité qu'une réglementation impose à certaines professions.
  Le README reprend ces points et ajoute qu'un professionnel qui contrôle ses clients avec Sceau devient responsable de ce traitement au sens du RGPD.
- **Justification** : l'exclusion de garantie de la GPL (articles 15 et 16) n'est visible que dans la licence ; la rappeler à l'écran, à côté du cadre d'usage, limite le risque qu'un verdict soit pris pour une attestation. Les traductions n'ont pas été relues par des locuteurs natifs.
- **Écart à la SPEC** : §8, cadre d'usage, complété.

## D31. Choix entre CAN et MRZ dans l'onglet Carte d'identité

- **Date** : 2026-10-04.
- **Contexte** : l'onglet Carte d'identité n'acceptait qu'un CAN de 6 chiffres. Une carte d'identité ICAO porte aussi une MRZ, dont la clé ouvre PACE comme BAC ; certaines cartes européennes n'acceptent pas le CAN, ou impriment un CAN d'une autre longueur. Il fallait alors passer par l'onglet Passeport, ce que son nom ne suggère pas.
- **Décision** :
  - un sélecteur à deux segments CAN / MRZ en tête de l'onglet Carte d'identité, sur CAN par défaut (`IdCardKey`, champ `idCardKey` d'`AccessForm`) ; en MRZ, les trois champs de l'onglet Passeport, avec une aide propre au numéro de document (« Tel qu'imprimé sur la carte ») ;
  - les saisies CAN et MRZ partagent l'état du formulaire : passer d'un segment à l'autre ne les efface pas. Quitter le résultat ou la lecture (« Effacer », retour, annulation, arrière-plan) remet tout le formulaire à son état initial, choix compris : onglet Passeport, et CAN dans l'onglet Carte d'identité ;
  - « MRZ » n'est pas traduit (glossaire), la chaîne est `translatable="false"`.
- **Écart à la SPEC** : §5.1 mise à jour.

## D32. Lecture optique de la MRZ par la caméra : essayée puis abandonnée

- **Date** : 2026-10-04 (décision de l'utilisateur).
- **Contexte** : pour éviter de taper le numéro de document et les deux dates, une lecture optique de la MRZ par la caméra a été développée le même jour. Elle comprenait :
  - un module `:mrz` en Kotlin pur : OCR par modèles tirés d'une police OCR-B libre, décodage guidé par les chiffres de contrôle, confirmation sur deux images ;
  - un écran de scan CameraX (permission `CAMERA`) ;
  - un bouton dans l'accueil ;
  - les traductions dans les 45 langues.
- **Constat** : sur un vrai passeport français (Fairphone 3), la reconnaissance est restée peu fiable, même après passage de l'analyse en 1080p et réglage pour les petits caractères. Les taux mesurés sur des images de synthèse (97 % de MRZ justes) ne se sont pas retrouvés sur l'appareil. La seule bibliothèque libre crédible, Tesseract, aurait ajouté plusieurs Mo de code natif par ABI (au-delà de l'objectif de D2), une compilation lourde depuis les sources pour F-Droid et une nouvelle surface native.
- **Décision** : fonction abandonnée et retirée en totalité : code, module `:mrz`, CameraX, permission `CAMERA`, chaînes, SPEC et documentation. SPEC §1 garde « Lecture optique de la MRZ ou du CAN par la caméra (aucun OCR) » hors périmètre. Le travail est conservé dans la branche git locale `archive/ocr-mrz`, qui comprend aussi les mesures et le plan `docs/plan-ocr-mrz.md`.
- **Conservé** : la correction de `docs/architecture.md` §4 et de TP-16 sur `FLAG_SECURE`, déjà posé sur toute l'activité avant ce travail.
- **Écart à la SPEC** : aucun.


## D34. Anomalies connues des émetteurs : règle codée en dur, cas italien

- **Date** : 2026-10-04 (décisions de l'utilisateur : règle codée en dur, puis critère large).
- **Contexte** : l'Italie publie une Deviation List (ICAO 9303-12, `id-icao-DeviationList`, `docs/trust-sources.md` §2.4) : 346 275 cartes d'identité CIE 3.0 portent un DG12 dont l'empreinte ne correspond pas au SOD, et dont la date de délivrance vaut le 2015-12-05. Sceau donnait « Échec » sur ces cartes authentiques.
- **Décision** :
  - **règle codée en dur, sans fichier embarqué** : le TDDL (SHA-256 `1219d8c74a7acf13c55e265fa8125230167160f4f999b64b28644275a084d31d`, signé le 2018-05-30) a été téléchargé, sa signature CMS vérifiée jusqu'au CSCA03 italien, puis décodé ; il n'est pas embarqué. La source italienne est classée B pour la réutilisation (`docs/trust-sources.md`), et ses 346 275 numéros de document, qui ne forment pas de plages, pèseraient de 125 Ko à 3 Mo : ils ne sont pas repris ;
  - **registre générique** `KnownDeviations` (`core/…/verify/KnownDeviations.kt`), interne, injecté dans `PassiveAuthenticator` (registre réel par défaut, autre registre dans les tests). Chaque règle porte un identifiant stable sans donnée personnelle (`KnownDeviation`, ici `IT-CIE3-DG12`), sa source, la date et l'empreinte SHA-256 de la liste publiée ;
  - **critère italien**, plus large que la liste, tous cumulatifs : CSCA de pays IT ; État émetteur `ITA` et code de document commençant par `C` (le type `C` de la liste : premier caractère du code MRZ de la carte) dans DG1 ; numéro de la forme `CA` + 5 chiffres + 2 lettres, celle de tous les numéros listés ; signingTime du SOD présent et compris entre le 2017-09-01 et le 2018-03-05. Cette marge d'un mois autour de la période de délivrance de la liste (2017-10-01 au 2018-02-05) couvre l'écart entre la signature du SOD, à la personnalisation, et la date de délivrance retenue par l'émetteur. Restriction facultative sur une donnée non vérifiée : un DG12 lisible dont la date de délivrance n'est pas le 2015-12-05 n'est pas couvert. Cette donnée ne sert qu'à restreindre la règle, jamais à l'étendre ;
  - **garde-fous** :
    - une règle ne peut viser que DG11 ou DG12 : le constructeur la refuse sur tout autre DG, et l'application filtre de nouveau. Un écart sur DG1, DG2, DG14, DG15 ou EF.CardSecurity n'est jamais toléré ;
    - une anomalie n'est recherchée que si la signature du SOD et la chaîne sont valides (`OK`), l'empreinte de DG1 vérifiée et aucun DG signé manquant. Le critère ne porte donc que sur des éléments signés ;
    - une anomalie n'est signalée que si le DG visé est réellement en écart ;
  - **effet** : le DG toléré quitte `DataGroupHashes.mismatched` et figure dans `DataGroupHashes.deviations` ; la ligne « Empreintes » le signale (« anomalie connue publiée par l'émetteur, données écartées »). Le DG est écarté (`PassiveAuthResult.discardedDataGroups`) : `ReadingSession` le retire de `DocumentData` et remet ses octets et ses images à zéro. La date de délivrance de DG12 n'est pas utilisée pour la validité du DS, qui retombe sur signingTime. Le verdict se calcule normalement : « Authentique » si le reste est bon.
- **Justification** : la portée est plus large que la liste, sans risque pour le verdict. Seul DG12, données complémentaires sans rôle dans la vérification, voit son écart toléré, et il n'est alors ni affiché ni utilisé. Les éléments qui fondent le verdict (SOD, chaîne, DG1, DG2, DG14, DG15) restent vérifiés sans exception. Coder la règle évite d'embarquer un fichier de réutilisation incertaine.
- **Écart à la SPEC** : §6.1 étape 5 (une empreinte DG12 fausse donnait toujours « Échec ») précisée par cette exception bornée.
