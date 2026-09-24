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

## D2. Pas de splits ABI : un APK universel

- **Date** : 2026-09-25.
- **Contexte** : SPEC §2 et §10 demandent des APK par architecture, de moins de 8 Mo chacun.
- **Décision** : un seul APK universel, sans splits ABI.
- **Justification** : l'application est entièrement en Java/Kotlin et n'embarque aucune bibliothèque native (jj2000 est pur Java, voir D3). Des APK par ABI seraient identiques octet pour octet, hormis le nom. L'APK release pèse environ 2,2 Mo sans Master List, soit environ 2,7 Mo avec la liste BSI (D1), bien en dessous de l'objectif de 8 Mo.
- **Écart à la SPEC** : §2 (« APK par architecture ») et §10 (« APK par architecture publiés sur les releases GitHub »). Si une bibliothèque native devenait nécessaire (par exemple openjpeg à la place de jj2000, SPEC §3), les splits ABI seraient réintroduits.

## D3. Décodeur JPEG 2000 : jj2000 publié sur Maven Central

- **Date** : 2026-09-25.
- **Contexte** : SPEC §3 retient « jj2000 (fork JMRTD) ». Ce fork n'est pas publié sur Maven Central, et le dépôt n'accepte que `google()` et `mavenCentral()` (reproductibilité et compatibilité F-Droid).
- **Décision** : l'implémentation de la lecture évalue `edu.ucar:jj2000:5.2`, publié sur Maven Central, issu de la même base de code JJ2000, pur Java, sans JNI.
- **Justification** : même code d'origine que le fork JMRTD, disponible depuis un dépôt standard, pas de binaire natif.
- **Statut** : **à confirmer après fusion du lot lecture** : choix définitif de l'artefact, et vérification de sa licence (licence JJ2000, de type BSD) et de sa compatibilité F-Droid. `docs/dependencies.md` sera mis à jour en conséquence.
- **Écart à la SPEC** : §3 (artefact différent du fork JMRTD, même base de code).

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
