# Sceau : lecteur et vérificateur de documents d'identité ICAO 9303

Ce document est la source de vérité du projet. Toute décision d'implémentation qui s'en écarte doit être justifiée dans `docs/decisions.md` et validée.

## 1. Objet

Sceau est une application Android libre qui lit par NFC la puce des documents d'identité conformes à ICAO 9303 (carte nationale d'identité électronique française, cartes d'identité européennes, passeports biométriques de tous pays), affiche les données et la photo stockées dans la puce, et vérifie cryptographiquement que le document est authentique et que la puce n'est pas un clone, à partir d'un magasin de certificats CSCA embarqué.

L'application ne conserve rien, n'envoie rien et fonctionne entièrement hors ligne.

### Hors périmètre v1

- Lecture des empreintes digitales (DG3) et de l'iris (DG4), qui exigent Terminal Authentication et des certificats délivrés par l'État.
- Lecture optique de la MRZ ou du CAN par la caméra (aucun OCR).
- Reconnaissance faciale automatique.
- Tout stockage, historique, export ou envoi des données lues.
- Mise à jour du magasin de confiance par le réseau.

## 2. Contraintes générales

- Licence GPLv3. Publication visée sur F-Droid : aucune dépendance propriétaire, aucun service Google, aucun blob binaire non reconstruisible.
- Aucune permission réseau dans le manifeste. Permissions demandées : `android.permission.NFC` uniquement. La feature `android.hardware.nfc` est déclarée non obligatoire pour que l'appli s'installe partout et affiche un message clair si le NFC est absent.
- Android 8.0+ (minSdk 26), compileSdk courant, Kotlin, Jetpack Compose, Material 3, mode sombre suivant le système.
- Interface en français. Toutes les chaînes dans `res/values/strings.xml`, aucune chaîne codée en dur, fichiers prêts pour les traductions (`values-en` fourni dès la v1).
- Architecture en modules :
  - `:core` : lecture, vérification, magasin de confiance, modèles. Kotlin pur, aucun import `android.*`, testable sur JVM.
  - `:app` : NFC, UI Compose, navigation, cycle de vie.
- Qualité : zéro avertissement sur `test`, `lint` et `ktlintCheck`. Un avertissement bloque la CI.
- APK par architecture, objectif inférieur à 8 Mo par APK, Master List comprise.

## 3. Dépendances retenues

| Besoin | Bibliothèque | Licence | Remarque |
|---|---|---|---|
| PACE, BAC, lecture des DG, PA, CA, AA | JMRTD | LGPL 3 | Version la plus récente publiée sur Maven Central |
| Pont JMRTD vers `IsoDep` | scuba-sc-android | LGPL 3 | |
| Cryptographie, X.509, CMS | BouncyCastle (bcprov, bcpkix) | MIT | Ne pas utiliser SpongyCastle |
| Décodage JPEG 2000 (photo DG2) | jj2000 (fork JMRTD) | BSD-like | Pur Java, pas de JNI. Remplaçable par openjpeg si trop lent |
| UI | Compose, Material 3, Navigation Compose | Apache 2 | |

Toute nouvelle dépendance doit être justifiée dans `docs/dependencies.md` avec sa licence et sa compatibilité F-Droid.

## 4. Documents pris en charge

| Document | Clé d'accès | Protocole | Remarque |
|---|---|---|---|
| CNIe française (format CB, depuis 2021) | CAN, 6 chiffres au recto | PACE | Cas principal |
| Cartes d'identité UE (règlement 2019/1157) | CAN si imprimé, sinon MRZ | PACE | Certaines cartes plus anciennes n'ont pas d'application ICAO |
| Passeports biométriques, tous pays | MRZ (numéro, naissance, expiration) | PACE si annoncé dans EF.CardAccess, sinon BAC | |

Un document sans application ICAO (AID `A0 00 00 02 47 10 01`) est signalé « document non conforme ICAO 9303 » sans autre tentative.

## 5. Parcours utilisateur

### 5.1 Écran d'accueil

Deux onglets en haut :

- **Carte d'identité** : champ CAN, clavier numérique, 6 chiffres, validation quand le champ est complet.
- **Passeport** : trois champs, numéro de document (alphanumérique, majuscules forcées), date de naissance et date d'expiration (sélecteur de date). Le numéro de document est complété par des `<` à 9 caractères conformément à la MRZ.

Sous les champs, une phrase rappelle que le document doit être présenté par son titulaire, et un bouton « Lire ».

Si le NFC est désactivé, un bandeau l'indique avec un bouton vers les réglages NFC du système. Si le téléphone n'a pas de NFC, l'écran l'explique et désactive le bouton.

Les valeurs saisies ne sont jamais persistées : elles vivent dans le `ViewModel` et sont effacées à la fermeture de l'écran de résultat.

### 5.2 Écran de lecture

Invite « Posez le document contre le dos du téléphone et ne le retirez pas », avec une animation sobre et une liste d'étapes qui se cochent au fur et à mesure :

1. Connexion à la puce
2. Ouverture du canal sécurisé (PACE ou BAC)
3. Lecture des données
4. Vérification de la signature
5. Vérification de la puce

Erreurs gérées, chacune avec un message en français et un bouton « Réessayer » :

- CAN ou MRZ incorrects (échec de PACE ou BAC)
- Document retiré trop tôt (perte de la connexion)
- Document sans application ICAO
- Délai dépassé
- Erreur inattendue, avec un identifiant d'erreur technique affiché en petit, sans donnée personnelle

### 5.3 Écran de résultat

De haut en bas :

**Verdict global**, en grand, sur fond coloré :

- **Authentique** (vert) : PA réussie et au moins un des deux challenges (CA ou AA) réussi.
- **Signature valide, puce non vérifiée** (orange) : PA réussie mais ni DG14 ni DG15 présents. Le document est authentique mais la puce pourrait être un clone.
- **Émetteur inconnu** (gris) : la chaîne du SOD ne remonte à aucun CSCA du magasin. Les données sont affichées mais rien n'est garanti.
- **Échec** (rouge) : signature invalide, empreinte d'un DG non conforme, ou challenge raté.

**Photo** de DG2 en grand, avec un bouton pour l'afficher en plein écran.

**Identité** (DG1) : nom, prénoms, sexe, date de naissance, nationalité, type et numéro de document, État émetteur (drapeau et nom du pays à partir du code à trois lettres), date d'expiration. Si le document est expiré, un bandeau « Document expiré » s'affiche sans changer le verdict d'authenticité.

**Données complémentaires**, section affichée uniquement si DG11 ou DG12 est présent :

- DG11 : nom complet, autres noms, numéro personnel, lieu de naissance, adresse, téléphone, profession, titre, résumé du profil, autres nationalités, données de garde.
- DG12 : autorité de délivrance, date de délivrance, mentions et observations, image du recto et du verso si présentes.

Seuls les champs renseignés sont affichés.

**Vérifications**, liste de contrôle avec pour chaque ligne un état (ok, échec, non disponible) et un détail dépliable :

1. Canal sécurisé établi (PACE ou BAC, et lequel)
2. Signature du SOD valide
3. Chaîne de certification : DS rattaché à un CSCA du magasin, avec le nom du CSCA, le pays, la source (ANTS, Master List embarquée, Master List importée) et l'algorithme de signature
4. Certificat DS dans sa période de validité à la date de délivrance du document
5. Empreintes des groupes de données conformes au SOD, avec la liste des DG contrôlés
6. Chip Authentication (DG14) réussie
7. Active Authentication (DG15) réussie

**Bouton « Effacer »** en bas et dans la barre d'action : vide immédiatement toutes les données en mémoire et revient à l'accueil. Le retour arrière du système a le même effet.

### 5.4 Écran « Magasin de confiance »

Accessible depuis un menu :

- Liste des CSCA connus, groupés par pays, avec nom du sujet, période de validité, empreinte SHA-256 et source.
- Bouton « Importer une Master List » : ouvre le sélecteur de fichiers du système (`ACTION_OPEN_DOCUMENT`), accepte un fichier CMS (`.ml`, `.der`, `.p7b`). La signature CMS est vérifiée avec le certificat signataire embarqué dans le fichier, dont l'empreinte est affichée à l'utilisateur pour confirmation avant import. Les CSCA importés sont stockés dans le stockage interne de l'appli et marqués « importé ».
- Bouton « Supprimer les certificats importés ».

Aucune donnée personnelle ne transite par cet écran.

### 5.5 Écran « À propos »

Version, licence, lien vers le dépôt, date de la Master List embarquée, rappel du cadre d'usage (voir section 8).

## 6. Protocole de lecture et de vérification (module `:core`)

L'API publique de `:core` est une fonction suspendue `readAndVerify(transport: CardTransport, key: AccessKey, trustStore: TrustStore, progress: (Step) -> Unit): VerificationReport`. `CardTransport` est une interface abstraite implémentée dans `:app` au-dessus d'`IsoDep`, ce qui permet de tester `:core` avec un transport simulé.

### 6.1 Séquence

1. Sélection de l'application ICAO. Échec : `NotIcaoDocument`.
2. Lecture de EF.CardAccess. Si présent et si PACE y est annoncé, PACE avec la clé fournie (CAN ou MRZ). Sinon BAC avec la clé MRZ. Un CAN sans PACE annoncé est une erreur explicite.
3. Lecture de EF.COM puis EF.SOD.
4. Lecture de DG1, DG2, puis DG14, DG15, DG11, DG12 s'ils sont annoncés dans EF.COM ou dans le SOD. Les DG3 et DG4 ne sont jamais lus.
5. Passive Authentication :
   - extraction du certificat DS embarqué dans le SOD (s'il est absent, recherche par émetteur et numéro de série dans le magasin, sinon `DsCertificateMissing`),
   - construction et validation de la chaîne DS vers un CSCA du magasin, en tenant compte des certificats de lien (link certificates),
   - vérification de la signature du SOD,
   - recalcul de l'empreinte de chaque DG lu avec l'algorithme déclaré dans le SOD et comparaison,
   - vérification que la période de validité du DS couvre la date de délivrance du document (date de DG12 si disponible, sinon date de signature du SOD, sinon expiration moins la durée de validité usuelle, avec mention « estimée »).
6. Chip Authentication si DG14 est présent. Après CA, le canal est renouvelé et toutes les lectures suivantes passent par ce canal.
7. Active Authentication si DG15 est présent : nonce de 8 octets tiré d'un `SecureRandom`, envoi, vérification de la signature avec la clé de DG15.
8. Construction d'un `VerificationReport` immuable contenant les données lues, le résultat de chaque étape, le verdict global et les métadonnées de la chaîne de certification.

### 6.2 Règles

- L'algorithme de signature et la courbe ne sont jamais codés en dur. La vérification accepte ce que les certificats et le SOD déclarent (RSA PKCS#1 v1.5, RSA-PSS, ECDSA sur toute courbe supportée par BouncyCastle, et tout futur algorithme ajouté à BouncyCastle). Un algorithme inconnu produit `UnsupportedAlgorithm`, pas un échec silencieux.
- Une étape non disponible (DG14 ou DG15 absent) est distinguée d'une étape échouée.
- Aucune donnée personnelle n'apparaît dans les exceptions, les logs ou les identifiants d'erreur.
- Le rapport ne contient aucune référence Android et est sérialisable pour les tests uniquement (jamais persisté par l'appli).

## 7. Magasin de confiance

### 7.1 Contenu embarqué

Dans `core/src/main/resources/trust/` :

- `ants-csca-eid-*.der` : certificats CSCA e-ID de l'ANTS (cartes d'identité), téléchargés depuis la page CSCA e-ID de ants.gouv.fr.
- `ants-csca-*.der` : certificats CSCA passeport de l'ANTS, y compris les certificats de lien, depuis la page CSCA de ants.gouv.fr.
- `icao-master-list.ml` : Master List ICAO (fichier CMS signé), téléchargée depuis le site du PKD de l'ICAO.

`docs/trust-store.md` documente pour chaque fichier : l'URL d'origine, la date de téléchargement, l'empreinte SHA-256 publiée par la source et l'empreinte constatée. Un test unitaire vérifie que les empreintes des fichiers embarqués correspondent à celles listées dans `docs/trust-store.md`, pour détecter toute substitution.

### 7.2 Chargement

Au démarrage, `:core` charge les certificats DER, parse la Master List (vérification de la signature CMS avec le certificat signataire qu'elle contient, rejet si invalide) et fusionne avec les certificats importés par l'utilisateur. Chaque CSCA est indexé par pays, sujet, identifiant de clé et empreinte, avec sa source.

### 7.3 Mise à jour

Les CSCA tournent tous les trois à cinq ans. Le magasin embarqué est mis à jour à chaque release selon la procédure de `docs/trust-store.md`. Entre deux releases, l'utilisateur peut importer une Master List plus récente (section 5.4). L'appli ne télécharge jamais rien.

### 7.4 Limites documentées

Un pays absent de la Master List et du magasin donne le verdict « Émetteur inconnu », pas « Échec ». Les listes de révocation ne sont pas consultées en v1 (elles nécessitent le réseau). Ces deux limites sont affichées dans l'écran « À propos ».

## 8. Vie privée et sécurité

- Aucune donnée lue n'est écrite sur disque, en cache, en base ou en log, ni en debug ni en release.
- `FLAG_SECURE` sur les écrans de lecture et de résultat : pas de capture d'écran, pas d'aperçu dans le multitâche.
- Les tableaux d'octets de DG1, DG2, DG11 et DG12 sont remis à zéro puis libérés à la sortie de l'écran de résultat et lors de la mise en arrière-plan de l'appli.
- La clé d'accès (CAN ou MRZ) n'est jamais mémorisée entre deux lectures.
- Aucune télémétrie, aucun rapport de plantage, aucune bibliothèque d'analyse.
- Le nonce d'Active Authentication est tiré d'un `SecureRandom`.
- Le README et l'écran « À propos » rappellent le cadre d'usage : la lecture suppose que le titulaire présente lui-même son document. La photo est une donnée biométrique au sens du RGPD si elle sert à une comparaison automatisée, ce que Sceau ne fait pas et ne doit pas faire sans base légale dédiée.
- La revue de sécurité du code (skill `android-securite`) est passée avant chaque release.

## 9. Tests

### 9.1 Tests unitaires (`:core`, JVM)

Une fabrique de test génère à la volée une hiérarchie factice : CSCA de test, certificat de lien, DS de test, SOD signé sur des DG factices, DG14 avec une paire de clés CA et DG15 avec une paire de clés AA, en RSA et en ECDSA. Cas couverts :

- chaîne nominale : verdict Authentique
- signature du SOD altérée : Échec
- DG2 modifié après signature : Échec sur l'empreinte
- DS expiré avant la date de délivrance : Échec sur la validité
- DS non rattaché à un CSCA connu : Émetteur inconnu
- DG14 et DG15 absents : Signature valide, puce non vérifiée
- réponse AA signée avec une mauvaise clé : Échec
- Master List avec signature CMS invalide : rejetée au chargement
- empreintes des fichiers embarqués conformes à `docs/trust-store.md`
- algorithme de signature inconnu : `UnsupportedAlgorithm`

Le transport simulé rejoue des échanges APDU enregistrés pour tester la séquence sans matériel.

### 9.2 Tests sur appareil

Plan de test manuel dans `docs/test-plan.md`, exécuté via adb sur téléphone réel (skill `android-test`) avec une vraie CNIe et un vrai passeport :

- cas nominal carte d'identité, cas nominal passeport
- CAN faux, MRZ fausse
- retrait du document pendant la lecture
- document expiré
- rotation de l'écran et mise en arrière-plan pendant la lecture et sur l'écran de résultat (vérification de l'effacement)
- NFC désactivé, puis réactivé
- import d'une Master List valide puis invalide

## 10. Livrables et organisation du dépôt

```
README.md               présentation, captures, cadre d'usage, installation
SPEC.md                 ce document
LICENSE                 GPLv3
CONTRIBUTING.md         règles de contribution (sections 2 et 3)
docs/architecture.md    modules, flux de données, cycle de vie des données sensibles
docs/protocol.md        séquence ICAO détaillée et décisions d'implémentation
docs/trust-store.md     provenance et empreintes des certificats, procédure de mise à jour
docs/dependencies.md    dépendances, licences, compatibilité F-Droid
docs/test-plan.md       plan de test manuel
docs/decisions.md       écarts par rapport à SPEC.md, justifiés
fastlane/metadata/      fr-FR et en-US : description, captures, changelog
core/                   module Kotlin pur
app/                    module Android
```

Releases : APK par architecture publiés sur les releases GitHub, tag `vX.Y.Z`, changelog Fastlane à jour. Préparation de la soumission à F-Droid avec le skill `android-fdroid` avant la première release publique.

## 11. Jalons

1. **Socle** : modules, CI, ktlint, magasin de confiance chargé et testé, fabrique de test crypto.
2. **Lecture** : transport `IsoDep`, PACE et BAC, lecture des DG, affichage brut de DG1 et de la photo.
3. **Vérification** : PA, CA, AA, `VerificationReport`, écran de résultat complet avec verdict et liste de contrôle.
4. **Finitions** : données complémentaires DG11 et DG12, écran magasin de confiance et import, écran À propos, effacement mémoire vérifié, `FLAG_SECURE`, traductions en-US.
5. **Publication** : revue sécurité, plan de test sur appareil, métadonnées Fastlane, première release, soumission F-Droid.
