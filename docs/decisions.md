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
  - sources d'OpenJPEG en sous-module git `app/src/main/cpp/openjpeg`, épinglé sur le tag `v2.5.4` (dernière version stable publiée à la date de la décision) ; seule la bibliothèque `openjp2` est compilée (sans codecs, outils ni tests), en statique, puis liée dans `libsceau_jp2.so` par CMake via le NDK. Aucun binaire précompilé dans le dépôt ;
  - API Kotlin `io.github.mgdx.sceau.jp2.Jpeg2000Decoder.decode(bytes): Bitmap?` ; cœur de décodage en mémoire dans `app/src/main/cpp/jp2_decode.c`, séparé du pont JNI (`jp2_jni.c`) et testé sur l'hôte sous AddressSanitizer et UndefinedBehaviorSanitizer (`app/src/main/cpp/test/run-host-tests.sh`) ;
  - `:core` reste en Kotlin pur sans JNI : l'ancien `Images.decodeJpeg2000` est supprimé ; `:core` transmet les octets du portrait (`EncodedImage`) et `:app` les décode.
- **Justification** : OpenJPEG est l'implémentation de référence du JPEG 2000, sous licence libre compatible GPLv3, maintenue, présente dans F-Droid comme dans les distributions. La compiler depuis les sources satisfait l'exigence F-Droid de reconstructibilité. Le décodage d'une donnée potentiellement hostile est borné : dimensions limitées à 4096 × 4096 (vérifiées dès la lecture de l'en-tête), flux limité à 16 Mo, 1 à 4 composantes de 16 bits au plus, mode strict d'OpenJPEG (flux tronqué refusé), gestionnaires de messages silencieux (aucune trace, SPEC §8), tampons natifs intermédiaires (copie du flux, échantillons décodés, pixels ARGB) remis à zéro avant libération. Compilation durcie : `-fstack-protector-strong`, `_FORTIFY_SOURCE=2`, `-fvisibility=hidden` (seule la fonction JNI est exportée), RELRO complet.
- **Limite connue** : les tampons internes d'OpenJPEG (blocs de code, tuiles en cours de décodage, tampon de lecture du flux) sont libérés par la bibliothèque sans remise à zéro, OpenJPEG n'offrant pas d'allocateur personnalisable. Ils restent en mémoire du processus, ne sont ni écrits sur disque ni journalisés.
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
