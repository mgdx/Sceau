# Décisions et écarts par rapport à SPEC.md

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
- **Justification** : OpenJPEG est l'implémentation de référence du JPEG 2000, sous licence libre compatible GPLv3, maintenue, présente dans F-Droid comme dans les distributions. La compiler depuis les sources satisfait l'exigence F-Droid de reconstructibilité. Le décodage d'une donnée potentiellement hostile est borné : dimensions limitées à 4096 × 4096 (vérifiées dès la lecture de l'en-tête), flux limité à 16 Mo, 1 à 4 composantes de 16 bits au plus, mode strict d'OpenJPEG (flux tronqué refusé), gestionnaires de messages silencieux (aucune trace, SPEC §8), tampons natifs intermédiaires (copie du flux, échantillons décodés, pixels ARGB) remis à zéro avant libération. Compilation durcie : `-fstack-protector-strong`, `_FORTIFY_SOURCE=2`, `-fvisibility=hidden` (seule la fonction JNI est exportée), RELRO complet.
- **Limite connue** : les tampons internes d'OpenJPEG (blocs de code, tuiles en cours de décodage, tampon de lecture du flux) sont libérés par la bibliothèque sans remise à zéro, OpenJPEG n'offrant pas d'allocateur personnalisable. Ils restent en mémoire du processus, ne sont ni écrits sur disque ni journalisés.
- **Version épinglée (audit V5, 2026-09-25)** : le tag `v2.5.4` (`6c4a29b0`) ne contient pas les correctifs de sécurité publiés depuis sur `master`, et aucune version 2.5.5 n'est sortie. Le sous-module est donc avancé sur `8314119b`, dernier commit de `origin/master` à la date de l'audit, qui intègre :
  - `91d08b11` (2026-02-10) : dépassement de tas dans `opj_j2k_read_sod` (index des parties de tuile), atteignable en décodage par un flux forgé ;
  - `1bb9e915` (2026-06-10) : parties de tuile entrelacées, index invalide et lecture à une mauvaise position ;
  - `91966314` (2026-09-04) : marqueur MCO sans étage, décalage de niveau remis à zéro à tort ;
  - hors décodage ou sans effet sur Sceau : `839936aa` (dépassement d'entier côté encodeur), IDWT 5-3 et 9-7 optimisées NEON (`0186472b`, `0fc6e243`, actives sur arm64-v8a et armeabi-v7a), suppression de variables inutilisées, `cmake_minimum_required` porté à 3.10, génération pkg-config, CI et README.

  Examen du journal `v2.5.4..8314119b` : aucun changement de `openjpeg.h` ni de l'API utilisée par `jp2_decode.c`, aucune nouvelle dépendance, aucun fichier binaire (uniquement du C, du CMake, des scripts de CI et un test de non-régression en C). Harnais hôte rejoué sous ASan et UBSan : vert. Les IDWT NEON ne s'exécutent que sur ARM et restent à valider sur appareil (décodage du portrait de référence).
- **Veille (projet non maintenu)** : le README amont déclare depuis `06bbae8b` (2026-07-07) le dépôt « unmaintained » : des commits peuvent encore arriver, sans revue régulière des tickets. À chaque release de Sceau : relire `git log` de `origin/master` depuis `8314119b` et les avis de sécurité visant OpenJPEG (NVD, OSS-Fuzz, tracker Debian `openjpeg2`), avancer le sous-module si un correctif touche le décodage, et rejouer `run-host-tests.sh`. Plan de repli si une faille n'est plus corrigée en amont : appliquer le correctif dans un fork du sous-module publié sous le compte du projet (sources seulement, exigence F-Droid inchangée), ou suivre la branche maintenue par une distribution (Debian, Fedora). Les défenses propres à Sceau (mode strict, bornes de taille, harnais sous sanitizers) limitent l'exposition dans l'intervalle.
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
