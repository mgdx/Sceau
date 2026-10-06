# Sceau : lecteur et vérificateur de documents d'identité ICAO 9303

Version du 2026-09-25, intègre les décisions D1 à D19 et l'audit de sécurité du 2026-09-25 (`docs/audit-securite-2026-09-25.md`) ; mise à jour au fil des décisions suivantes, renvoyées par leur numéro, en dernier le 2026-10-06 (D36, D38).

Ce document est la source de vérité du projet. Toute décision d'implémentation future qui s'en écarte doit être justifiée dans `docs/decisions.md` et validée. Les décisions D1 à D19, désormais intégrées ici, y restent pour l'historique.

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

- Licence GNU GPL version 3 ou ultérieure (GPL-3.0-or-later), comme l'indiquent l'écran « À propos » et les en-têtes des sources C ; logo et icône sous la même licence (D38). Publication visée sur F-Droid : aucune dépendance propriétaire, aucun service Google, aucun blob binaire non reconstruisible. Le code natif est compilé depuis les sources à chaque build.
- Aucune permission réseau dans le manifeste. Permissions demandées : `android.permission.NFC` uniquement, plus la permission interne de niveau `signature` `<paquet>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` qu'ajoute androidx.core (D7). La feature `android.hardware.nfc` est déclarée non obligatoire pour que l'appli s'installe partout et affiche un message clair si le NFC est absent. Sauvegarde Android désactivée.
- Android 8.0+ (minSdk 26), compileSdk courant, Kotlin, Jetpack Compose, Material 3, mode sombre suivant le système. Palette propre à l'application (celle du logo), couleurs dynamiques désactivées pour garder la lecture des verdicts stable (D18).
- Identifiant d'application et paquet `io.github.mgdx.sceau`, `io.github.mgdx.sceau.core` pour `:core` (D4).
- Interface en français. Aucune chaîne codée en dur ; chaînes dans `res/values/`, réparties en un fichier par écran (`strings_<écran>.xml`) plus `strings.xml` pour les chaînes communes (D5), fichiers prêts pour les traductions (`values-en` fourni dès la v1, 43 autres langues et choix de la langue par application depuis D27).
- Architecture en modules :
  - `:core` : lecture, vérification, magasin de confiance, modèles. Kotlin pur, aucun import `android.*`, aucun code natif, testable sur JVM. Ses dépendances sont en `implementation` : son API publique n'expose que des types Kotlin, `java.security` et `java.time` (D9).
  - `:app` : NFC, UI Compose, navigation, cycle de vie, décodage des images (dont le décodeur JPEG 2000 natif).
  - `:testchip` : PKI factice, puce ICAO simulée et CNIe spécimen, Kotlin JVM. Réservé aux tests (`testImplementation` de `:core`) et à l'APK de debug (`debugImplementation` de `:app`) ; il n'entre jamais dans l'APK release (D17).
- Qualité : zéro avertissement sur `test`, `lint` et `ktlintCheck`. Un avertissement bloque la CI. Les règles lint désactivées sont justifiées dans `docs/decisions.md` (D8).
- APK : splits ABI activés, un APK par architecture (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`), qui n'embarque que la bibliothèque native de son architecture, plus un APK universel. Objectif inférieur à 8 Mo par APK, Master List comprise (D2).
- `FLAG_SECURE` sur toute l'activité, posé dès sa création : aucun écran, accueil compris (saisie du CAN et de la MRZ), n'apparaît dans une capture ni dans l'aperçu du multitâche (section 8, audit V7).

## 3. Dépendances retenues

| Besoin | Bibliothèque | Licence | Remarque |
|---|---|---|---|
| PACE, BAC, lecture des DG, PA, CA, AA | JMRTD | LGPL 2.1 ou ultérieure | Version la plus récente publiée sur Maven Central |
| Abstraction APDU, pont JMRTD vers `IsoDep` | SCUBA (scuba-smartcards, scuba-sc-android) | LGPL 2.1 ou ultérieure | |
| Cryptographie, X.509, CMS | BouncyCastle (bcprov, bcpkix, variantes `jdk18on` seulement) | MIT | Ne pas utiliser SpongyCastle |
| Décodage JPEG 2000 (photo DG2, signature DG7, images DG12) | OpenJPEG (bibliothèque `openjp2` seule) | BSD-2-Clause | Code natif en JNI dans `:app`, compilé depuis les sources (sous-module git `app/src/main/cpp/openjpeg`) par CMake et le NDK, lié statiquement ; aucun binaire précompilé |
| Exécution asynchrone | kotlinx-coroutines | Apache 2 | |
| UI | Compose, Material 3, Navigation Compose, Lifecycle | Apache 2 | |

La clause « ou ultérieure » permet d'utiliser JMRTD et SCUBA sous LGPL 3, compatible avec la licence de Sceau (GPL-3.0-or-later).

jj2000 (fork JMRTD) est écarté : sa licence d'origine restreint le champ d'usage (non libre, retiré de Debian pour cette raison), ce qui la rend incompatible avec la GPLv3 et avec la politique d'inclusion de F-Droid ; ce fork n'est pas non plus publié sur Maven Central (D3). OpenJPEG, implémentation de référence sous licence libre, le remplace. Le sous-module suit la veille de sécurité amont : il est avancé dès qu'un correctif de sécurité est publié, et le harnais de test natif est rejoué à chaque montée de version (audit V5).

Toute nouvelle dépendance doit être justifiée dans `docs/dependencies.md`, qui liste chaque dépendance avec sa version résolue, sa licence vérifiée sur l'artefact et sa compatibilité F-Droid.

## 4. Documents pris en charge

| Document | Clé d'accès | Protocole | Remarque |
|---|---|---|---|
| CNIe française (format CB, depuis 2021) | CAN, 6 chiffres au recto | PACE | Cas principal |
| Cartes d'identité UE (règlement 2019/1157) | CAN si imprimé, sinon MRZ | PACE | Certaines cartes plus anciennes n'ont pas d'application ICAO |
| Passeports biométriques, tous pays | MRZ (numéro, naissance, expiration) | PACE si annoncé dans EF.CardAccess, sinon BAC ; repli sur BAC selon §6.1 | |

Un document sans application ICAO (AID `A0 00 00 02 47 10 01`) est signalé « document non conforme ICAO 9303 » sans autre tentative.

## 5. Parcours utilisateur

### 5.1 Écran d'accueil

Deux onglets en haut, le Passeport en premier et sélectionné à l'ouverture (D29) :

- **Passeport** : trois champs, numéro de document (alphanumérique, majuscules forcées), date de naissance et date d'expiration. Le numéro de document est complété par des `<` à 9 caractères conformément à la MRZ. Dès que le huitième chiffre de la date d'expiration est tapé, le clavier se retire (D29).
- **Carte d'identité** : un sélecteur CAN / MRZ, sur CAN par défaut (D31). CAN : champ unique, clavier numérique, 6 chiffres, validation quand le champ est complet. MRZ : les trois champs de l'onglet Passeport, pour les cartes dont la puce n'accepte pas le CAN ou dont le CAN n'a pas 6 chiffres.

Les deux dates se saisissent au clavier numérique (clavier de type mot de passe numérique, pour que le clavier du système n'apprenne ni ne suggère ces dates), en huit chiffres, sans sélecteur de date. Les séparateurs `/` s'affichent au fil de la frappe sans être stockés. L'ordre des champs suit le format de date court de la locale : JJ/MM/AAAA en français, MM/JJ/AAAA en anglais américain, jour, mois, année par défaut. Une date doit exister, la naissance ne pas dépasser aujourd'hui, l'expiration être postérieure à 1990 ; l'erreur s'affiche sous le champ et « Lire » reste inactif tant qu'une date est invalide (D10).

Les champs CAN, numéro de document et dates sont exclus de la saisie automatique (autofill) : aucun gestionnaire de mots de passe ne doit se les voir proposer (audit V14).

Sous les champs, une phrase rappelle que le document doit être présenté par son titulaire, et un bouton « Lire ».

Si le NFC est désactivé, un bandeau l'indique avec un bouton vers les réglages NFC du système. Si le téléphone n'a pas de NFC, l'écran l'explique et désactive le bouton.

Un menu donne accès aux écrans « Magasin de confiance » et « À propos ». Dans l'APK de debug seulement, il propose aussi « Simuler une CNIe (démo) », qui lit la CNIe spécimen de `:testchip` par le même chemin qu'un document réel (section 9.3). Cette entrée n'existe pas dans l'APK release.

Les valeurs saisies ne sont jamais persistées : elles vivent dans le `ViewModel`, jamais dans un `Bundle` ni un `SavedStateHandle`, et sont effacées à la fin d'une lecture réussie et à la fermeture de l'écran de résultat.

### 5.2 Écran de lecture

Invite « Posez le document contre le dos du téléphone et ne le retirez pas », avec une animation sobre et une liste d'étapes qui se cochent au fur et à mesure :

1. Connexion à la puce
2. Ouverture du canal sécurisé (PACE ou BAC)
3. Lecture des données
4. Vérification de la signature
5. Vérification de la puce

Si l'étape « Ouverture du canal sécurisé » dure plus de 5 s, un message d'attente explique que la puce peut faire patienter jusqu'à une minute après des essais ratés (contre-mesure anti-force brute, §6.1) et invite à ne pas retirer le document (D12).

Erreurs gérées, chacune avec un message en français et un bouton « Réessayer », sauf l'accès réservé :

- CAN ou MRZ incorrects (échec de PACE ou BAC) ; le message prévient qu'un essai raté peut allonger le délai de réponse de la puce
- CAN fourni pour un document qui n'annonce pas PACE ; le message invite à utiliser l'onglet Passeport et la MRZ
- Document retiré trop tôt (perte de la connexion)
- Document sans application ICAO
- Puce réservée aux autorités habilitées (`ACCESS_RESTRICTED`, cartes d'identité allemandes délivrées avant août 2021) : le message dit qu'un nouvel essai n'y changera rien, et seul « Annuler » est proposé, sans « Réessayer » (D36)
- Délai dépassé
- Erreur inattendue

Pour **toutes** les erreurs, le code technique complet est affiché en petit sous le message : aucun journal n'étant permis (section 8), c'est le seul moyen de diagnostic (D11). Le message est choisi sur le code de base (texte avant le premier `-`). Codes de base stables : `NOT_ICAO`, `ACCESS_DENIED`, `ACCESS_RESTRICTED`, `CAN_WITHOUT_PACE`, `CONNECTION_LOST`, `TIMEOUT`, `UNEXPECTED`. `ACCESS_RESTRICTED` porte en suffixe l'étape et l'élément refusé (`ACCESS_RESTRICTED-SECURE_CHANNEL-SELECT_APPLET`, `ACCESS_RESTRICTED-READ_DATA-SOD`, `ACCESS_RESTRICTED-READ_DATA-DG1`, D36). `CONNECTION_LOST`, `TIMEOUT` et `UNEXPECTED` portent un suffixe de diagnostic fait uniquement d'éléments sans donnée personnelle : étape (`SECURE_CHANNEL`…), octet INS de la commande en cours (`INS86`), longueur de l'APDU arrondie à la dizaine (`L10`), étiquette de l'opération (`SELECT_APPLET`, `SOD`, `DG1`, `RECONNECT`), sous-type d'erreur d'E/S pris dans une liste fermée (`IO-TRANSCEIVE_FAILED`, `IO-TOO_LONG`, `IO-SERVICE_DIED`, `IO-OTHER`, `IO-NO_MESSAGE`), nom simple de la classe d'exception et mot d'état SW. Exemple : `TIMEOUT-SECURE_CHANNEL-INS86-L10`. Un code ne contient jamais de donnée lue, de clé, d'octet d'APDU ni de message d'exception brut. La grammaire complète est dans `docs/protocol.md` §5.

### 5.3 Écran de résultat

De haut en bas :

**Verdict global**, en grand, sur fond coloré :

- **Authentique** (vert) : PA réussie et au moins un des deux challenges (CA ou AA) réussi.
- **Signature valide, puce non vérifiée** (orange) : PA réussie mais ni DG14 ni DG15 annoncés dans le SOD. Le document est authentique mais la puce pourrait être un clone.
- **Émetteur inconnu** (gris) : la chaîne du SOD ne remonte à aucun CSCA du magasin, ou le certificat DS est introuvable. Les données sont affichées mais rien n'est garanti.
- **Échec** (rouge) : signature invalide, empreinte d'un DG non conforme, DG signé non fourni par la puce, chaîne invalide ou pays incohérents, challenge raté, ou algorithme non pris en charge.

Le verdict se calcule par la table de décision de `docs/protocol.md` §6 : une ligne en échec ou en algorithme non pris en charge donne « Échec » ; sinon une chaîne non disponible donne « Émetteur inconnu » ; sinon PA réussie et CA ou AA réussie donne « Authentique » ; CA et AA non disponibles donnent « Signature valide, puce non vérifiée » ; tout autre cas donne « Échec » (D6).

Quand l'ancre de la chaîne est un CSCA issu d'une Master List **importée** par l'utilisateur, la carte du verdict le signale elle-même par une mention visible sans rien déplier (audit V6).

Après une lecture en mode démo (APK de debug), un bandeau « Document simulé — démonstration » est affiché (D17).

**Photo** de DG2 en grand, avec un bouton pour l'afficher en plein écran.

**Identité** (DG1) : nom, prénoms, sexe, date de naissance, nationalité, type et numéro de document, État émetteur (drapeau et nom du pays à partir du code à trois lettres), date d'expiration. Si le document est expiré, un bandeau « Document expiré » s'affiche sans changer le verdict d'authenticité.

**Carte eID allemande pour citoyens de l'Union (eID-UB)** : sa puce ne contient aucune donnée d'identité (DG1 au code de document « UB », État « D », « < » partout ailleurs ; DG2 porte le logo eID, identique pour toutes les cartes). Si la signature du SOD est valide et l'empreinte de DG1 conforme, la section Identité est remplacée par un bandeau qui l'explique, suivi du seul genre de document et de l'État émetteur, et la photo n'est pas affichée ; l'empreinte de DG2 reste contrôlée. Le verdict se calcule normalement (D36).

**Signature du titulaire** (DG7), section affichée uniquement si la puce l'annonce et la fournit : image de la signature manuscrite, décodée comme la photo, sur fond blanc dans les deux thèmes pour rester lisible (D37).

**Données complémentaires**, section affichée uniquement si DG11 ou DG12 est présent :

- DG11 : nom complet, autres noms, numéro personnel, lieu de naissance, adresse, téléphone, profession, titre, résumé du profil, autres nationalités, données de garde.
- DG12 : autorité de délivrance, date de délivrance, mentions et observations, image du recto et du verso si présentes.

Seuls les champs renseignés sont affichés. Tout texte venu de la puce (DG1, DG11, DG12, sujets de certificats) est assaini à l'affichage : caractères de contrôle, de mise en forme bidirectionnelle et séparateurs de ligne ou de paragraphe retirés, longueur bornée, nombre de lignes limité (audit V12).

**Vérifications**, liste de contrôle avec pour chaque ligne un état (ok, échec, non disponible, algorithme non pris en charge) et un détail dépliable :

1. Canal sécurisé établi (PACE ou BAC, et lequel)
2. Signature du SOD valide
3. Chaîne de certification : DS rattaché à un CSCA du magasin, avec le nom du CSCA, le pays, la source (ANTS, publication nationale, Master List embarquée, Master List importée) et l'algorithme de signature. Le pays du CSCA, celui du DS et l'État émetteur de DG1 doivent concorder ; sinon la ligne est en échec avec le détail des trois pays (audit V3)
4. Certificat DS dans sa période de validité à la date de délivrance du document
5. Empreintes des groupes de données conformes au SOD, avec la liste des DG contrôlés. Un DG que le SOD annonce, parmi ceux que Sceau lit (1, 2, 7, 11, 12, 14, 15), et que la puce ne fournit pas est un échec, listé comme manquant : une puce ne peut pas retenir un DG signé (audit V1)
6. Chip Authentication (DG14) réussie
7. Active Authentication (DG15) réussie

**Bouton « Effacer »** en bas et dans la barre d'action : vide immédiatement toutes les données en mémoire, images décodées comprises, et revient à l'accueil. Le retour arrière du système a le même effet.

### 5.4 Écran « Magasin de confiance »

Accessible depuis le menu de l'accueil :

- Liste des CSCA connus, groupés par pays, avec nom du sujet, période de validité, empreinte SHA-256 et source.
- Bouton « Importer une Master List » : ouvre le sélecteur de fichiers du système (`ACTION_OPEN_DOCUMENT`, sans permission persistante), accepte un fichier CMS (`.ml`, `.der`, `.p7b`) de 20 Mo au plus. La signature CMS est vérifiée avec le certificat signataire embarqué dans le fichier, dont l'empreinte est affichée à l'utilisateur pour confirmation avant import. Le dialogue de confirmation avertit qu'un certificat importé peut rendre authentique n'importe quel document de son pays, et qu'il ne faut importer qu'une liste de provenance sûre (audit V6). Les Master Lists importées sont stockées telles quelles dans le stockage interne de l'appli (`filesDir/trust/`, nom de fichier = SHA-256 du contenu, écriture atomique) et leurs CSCA marqués « importé ».
- Le même bouton accepte un certificat CSCA auto-signé ou un certificat de lien isolé (DER ou PEM, un seul par fichier), reconnu automatiquement, avec un aperçu (sujet, pays, validité, empreinte SHA-256) et l'avertissement qu'aucune signature d'État ne le garantit ; stocké en DER dans `filesDir/trust/<sha256>.der`, marqué « Certificat importé » (D33).
- Section « Éléments importés » : chaque Master List ou certificat importé, supprimable à l'unité après confirmation (D33).
- Bouton « Supprimer les certificats importés ».

Aucune donnée personnelle ne transite par cet écran.

### 5.5 Écran « À propos »

Version, licence, lien vers le dépôt, rappel du cadre d'usage (voir section 8), et description du magasin de confiance embarqué : CSCA français de l'ANTS et Master List allemande du BSI, avec la date de signature de cette Master List, puis les pays dont des certificats publiés par l'État lui-même sont embarqués. Une mention précise que la Master List du BSI est redistribuée sans modification, sans coopération ni approbation du BSI (conditions de réutilisation, D1). L'attribution de l'Open Government Licence v3.0 accompagne les certificats britanniques (D26). Les limites de la section 7.4 y sont rappelées.

### 5.6 Introduction et écran « Ce que contient la puce »

Au premier lancement, une introduction de 4 pages balayables explique la puce ICAO 9303, la clé d'accès (CAN ou MRZ) et le canal chiffré, ce que vérifie Sceau et le sens des quatre verdicts, puis la confidentialité. « Passer l'introduction » (toutes les pages sauf la dernière), « Commencer » (dernière page) et le retour arrière mènent à l'accueil et la marquent comme vue ; elle est rejouable depuis « À propos ». L'écran « Ce que contient la puce », ouvert depuis « À propos » et depuis la dernière page de l'introduction, liste les fichiers lus et affichés, ceux lus pour la seule vérification, ceux jamais lus (DG3, DG4…) et ce que Sceau ne fait pas (D35).

## 6. Protocole de lecture et de vérification (module `:core`)

L'API publique de `:core` est une fonction suspendue `readAndVerify(transport: CardTransport, key: AccessKey, trustStore: TrustStore, progress: (Step) -> Unit): VerificationReport`, exécutée sur `Dispatchers.IO`. `CardTransport` est une interface abstraite (`transceive`, `maxTransceiveLength`, `timeoutMillis`, `reconnect`, `close`) implémentée dans `:app` au-dessus d'`IsoDep`, ce qui permet de tester `:core` avec un transport simulé. `reconnect()` réinitialise la liaison (vide par défaut ; `IsoDep.close()` puis `connect()` dans l'app, délai réappliqué) pour le repli PACE → BAC (D13).

Une erreur qui interrompt la lecture lève une `SceauException` au code stable (§5.2) ; tout échec de vérification est au contraire consigné dans le rapport, toujours produit dès que les données ont été lues. Si la lecture échoue, les données déjà lues sont remises à zéro.

La séquence détaillée, les délais et la grammaire des codes d'erreur sont dans `docs/protocol.md`.

### 6.1 Séquence

L'ordre suit ICAO 9303 partie 11 : EF.CardAccess est un fichier du MF et PACE s'exécute au niveau du MF, avant la sélection de l'application.

1. **EF.CardAccess** : lu en clair au niveau du MF, avant toute sélection d'application. Présent et contenant des `PACEInfo` : PACE est annoncé. Absent ou illisible : pas de PACE.
2. **Canal sécurisé et sélection de l'application ICAO** :
   - PACE annoncé : PACE avec la clé fournie (CAN, ou clé dérivée de la MRZ), chaque `PACEInfo` essayé dans l'ordre, mapping et paramètres de domaine pris dans ce qu'annonce la puce ; puis sélection de l'application ICAO sous messagerie sécurisée.
   - PACE non annoncé : sélection de l'application ICAO en clair, puis BAC avec la clé MRZ. Un CAN sans PACE annoncé est une erreur explicite (`CAN_WITHOUT_PACE`).
   - Réponse `6A82` à la sélection de l'application : `NotIcaoDocument`, sans autre tentative.
   - Clé refusée (SW `63xx` pendant PACE, échec de BAC) : `ACCESS_DENIED`, sans autre essai.
   - **Repli PACE → BAC** (clé MRZ seulement) : si PACE échoue autrement que par un refus de la clé ou un délai dépassé (SW inattendu, erreur de la bibliothèque, perte de liaison), la liaison est réinitialisée par `CardTransport.reconnect()`, l'application ICAO est sélectionnée en clair, puis BAC est mené ; ICAO 9303 impose aux documents qui annoncent PACE d'accepter BAC pendant la transition. Avec un CAN, aucun repli. Une reconnexion impossible donne `CONNECTION_LOST-SECURE_CHANNEL-RECONNECT` (D13).
   - **Délai d'authentification** : pendant PACE et BAC, le délai de réponse du transport est porté à 60 s, puis rétabli à sa valeur précédente (10 s) pour la lecture. Une puce peut imposer un délai croissant après des essais ratés ; couper plus tôt ne la laisse jamais répondre et aggrave la pénalité. Un délai dépassé pendant PACE arrête la lecture (`TIMEOUT-…`), sans repli sur BAC (D12).
   - Toutes les commandes suivantes passent par la messagerie sécurisée ; le MAC des réponses est toujours vérifié. APDU courtes uniquement (256 octets au plus, aucune APDU étendue), lecture par `SELECT` puis `READ BINARY` par blocs de 223 octets, sans SFI (D14).
3. **EF.COM puis EF.SOD**. EF.COM est facultatif : absent ou illisible, la liste des DG présents vient du SOD. EF.SOD est obligatoire.
4. **Groupes de données** : DG1, DG2, puis DG14, DG15, DG11, DG12 et DG7 (signature manuscrite, D37) s'ils sont annoncés dans EF.COM ou dans le SOD. Les DG3 et DG4 ne sont jamais lus, pas plus que DG5, DG6, DG8 à DG10, DG13 et DG16.
   - DG1 est obligatoire : son absence interrompt la lecture.
   - DG2 est toujours demandé. Un DG, DG2 compris, que la puce ne fournit pas alors que le SOD l'annonce met la ligne « Empreintes » en échec (§5.3, audit V1) ; un DG qui n'est pas annoncé dans le SOD est simplement absent (pas de photo sans DG2).
   - Taille bornée : la longueur annoncée par l'en-tête TLV de chaque fichier est contrôlée avant lecture, contre un plafond propre au fichier ; une longueur au-delà est refusée sans être lue. Un manque de mémoire est traité comme une erreur de lecture, sans empêcher l'effacement des données déjà lues. Un portrait dont l'en-tête d'image est corrompu donne « photo absente » plutôt que l'échec de toute la lecture (audit V11).
   - Une erreur de transport (document retiré, délai) interrompt toujours la lecture.
5. **Passive Authentication** :
   - extraction du certificat DS embarqué dans le SOD. S'il est absent, recherche dans le magasin par (émetteur, numéro de série) ou par identifiant de clé ; introuvable : signature du SOD et chaîne « non disponibles » (`DsCertificateMissing`), d'où « Émetteur inconnu » (D15),
   - construction et validation de la chaîne DS vers un CSCA auto-signé du magasin, à travers 8 certificats de lien au plus ; émetteurs candidats cherchés par Authority Key Identifier puis par nom. Seules les signatures des CSCA et des liens comptent, pas leur période de validité : un document reste valide jusqu'à dix ans après l'expiration de son CSCA (D15). Les certificats contenus dans le SOD ne servent jamais d'intermédiaires. Aucun CSCA du magasin ne correspond : chaîne « non disponible », verdict « Émetteur inconnu » (D6),
   - contrôle de cohérence des pays : pays du CSCA = pays du DS = État émetteur de DG1 (code ICAO alpha-3 ramené en alpha-2, codes spéciaux compris : `D` = DE, `GBD`…`GBS` = GB) ; sinon la chaîne est en échec (audit V3). Toute exception (organisation internationale) est documentée dans `docs/decisions.md`,
   - vérification de la signature du SOD avec la clé du DS, selon l'algorithme déclaré dans le SOD,
   - recalcul de l'empreinte de chaque DG lu avec l'algorithme déclaré dans le SOD et comparaison ; un DG lu mais absent du SOD est non conforme ; un DG annoncé dans le SOD et non fourni est un échec (étape 4),
   - vérification que la période de validité du DS couvre la date de délivrance du document : date de DG12 si disponible, sinon date de signature du SOD, sinon expiration moins 10 ans, avec mention « estimée ». Hors de la période : échec pour une date de DG12 ou du SOD ; « non disponible », jamais échec, pour une date estimée, la durée usuelle pouvant être fausse (passeport de mineur) (D6, D15).
6. **Chip Authentication** si DG14 est présent et l'annonce : accord de clé avec la clé statique de DG14, puis renouvellement de la messagerie sécurisée ; toutes les lectures suivantes passent par ce canal. La CA n'est réussie que si la puce répond sous les nouvelles clés : un échange court (lecture de DG1) le confirme, MAC de la réponse vérifié. CA « non disponible » seulement si DG14 est absent du SOD.
7. **Active Authentication** si DG15 est présent : nonce de 8 octets tiré d'un `SecureRandom`, envoi par `INTERNAL AUTHENTICATE`, vérification de la signature avec la clé de DG15 (RSA : ISO/IEC 9796-2 schéma 1, hachage désigné par le trailer ; EC : ECDSA avec le hachage annoncé dans DG14, ou, s'il n'en annonce pas, SHA-1 à SHA-512 essayés dans l'ordre, D15). AA « non disponible » seulement si DG15 est absent du SOD. Une perte de liaison pendant CA ou AA interrompt la lecture ; toute autre erreur de la puce est consignée dans la ligne correspondante.
8. Construction d'un `VerificationReport` immuable contenant les données lues, le résultat de chaque étape (exactement une ligne par contrôle), le verdict global et les métadonnées de la chaîne de certification.

### 6.2 Règles

- L'algorithme de signature et la courbe ne sont jamais codés en dur. La vérification accepte ce que les certificats et le SOD déclarent (RSA PKCS#1 v1.5, RSA-PSS, ECDSA sur toute courbe supportée par BouncyCastle, et tout futur algorithme ajouté à BouncyCastle). Un algorithme inconnu produit `UnsupportedAlgorithm`, pas un échec silencieux.
- Algorithmes refusés, qui ne peuvent jamais donner une ligne « ok » : MD5 (empreintes des DG comme signatures), RIPEMD-128, et clé RSA de moins de 1024 bits pour l'Active Authentication. SHA-1 reste accepté, des documents qui l'emploient étant encore en circulation (audit V9).
- Extensions de la chaîne : tout émetteur de la chaîne (CSCA, certificat de lien) doit porter `basicConstraints` avec CA=true et l'usage de clé `keyCertSign` ; le DS doit porter `digitalSignature`. Une tolérance n'est admise que pour des CSCA réels qui n'ont pas ces extensions, après vérification sur les ancres embarquées, et doit être documentée dans `docs/decisions.md` (audit V8).
- Type de contenu du SOD : le contenu signé doit être de type `id-icao-ldsSecurityObject` (`2.23.136.1.1.1`) ; sinon la vérification du SOD échoue (audit V10).
- La recherche de chaîne est bornée : chaque certificat n'est visité qu'une fois par recherche, et le nombre de vérifications de signature est plafonné, pour qu'un magasin hostile ne rende pas la recherche exponentielle (audit V15).
- Une étape non disponible (DG14 ou DG15 absent du SOD, étape non atteinte) est distinguée d'une étape échouée.
- Accès réservé : un SW `6982` reçu **après** l'établissement du canal sécurisé, à la sélection de l'application ICAO ou à la lecture d'un fichier indispensable (EF.SOD, DG1), lève `ACCESS_RESTRICTED` : la puce réserve ces fichiers aux terminaux étatiques (Terminal Authentication), qu'un logiciel libre ne peut pas mener. Un `6982` avant l'authentification reste `UNEXPECTED`, un refus de clé reste `ACCESS_DENIED`, et un `6982` sur un fichier facultatif (EF.COM, DG2, DG7, DG11, DG12, DG14, DG15) le rend absent (D36).
- Aucune donnée personnelle n'apparaît dans les exceptions, les logs ou les identifiants d'erreur. Aucune exception de JMRTD n'est propagée ni attachée comme cause : leurs messages contiennent des APDU en hexadécimal (D14). Les modèles, la clé et le rapport ont un `toString()` masqué.
- Le rapport ne contient aucune référence Android et n'est ni sérialisé ni persisté : les tests travaillent sur les objets en mémoire produits avec `:testchip` (D19). `:core` ne produit aucun texte destiné à l'utilisateur : l'app met en forme les détails structurés avec ses propres chaînes.

## 7. Magasin de confiance

### 7.1 Contenu embarqué

Dans `core/src/main/resources/trust/` :

- `ants-csca-2010.der`, `ants-csca-2015.der`, `ants-csca-2020.der`, `ants-csca-2025.der` : certificats CSCA passeport de l'ANTS (`CN=CSCA-FRANCE`), depuis la page CSCA de ants.gouv.fr. Le CSCA 2010, expiré, est conservé : il a signé des DS de passeports encore valables (D16).
- `ants-csca-eid-2021.der` : certificat CSCA e-ID de l'ANTS (`CN=eID-FRANCE`, cartes d'identité), depuis la page CSCA e-ID de ants.gouv.fr. Il ne figure dans aucune Master List nationale : sans lui, aucune CNIe ne pourrait être vérifiée.
- `de-bsi-master-list.ml` : German Master List publiée par le BSI (fichier CMS signé), redistribuée octet pour octet, seulement renommée. Elle couvre les 27 pays de l'UE, l'EEE, la Suisse et le Royaume-Uni, parmi 117 émetteurs. La Master List de l'ICAO n'est pas embarquée : ses conditions d'utilisation interdisent la redistribution et son téléchargement impose un captcha. Celle du BSI est publiée avec des conditions de réutilisation explicites (pas d'usage publicitaire, aucune apparence de coopération ou de caution du BSI, fichier inchangé), que Sceau respecte (D1).
- `<pays>-csca-*.der` (par exemple `gb-csca-2026.der`) : CSCA et certificats de lien publiés par un État pour ses propres documents, absents de la liste BSI, encore valides et issus d'une source de statut A de `docs/trust-sources.md` (licence ouverte, ou publication officielle sans restriction de réutilisation). Royaume-Uni, Grèce (titres de séjour), Géorgie et Luxembourg au 2026-09-29 (D26).
- `index.txt` : liste des fichiers du répertoire, un nom par ligne, car lister un répertoire du classpath n'est pas fiable dans un APK (D16).

`docs/trust-store.md` documente pour chaque fichier : l'URL d'origine, la date de téléchargement, l'empreinte publiée par la source et l'empreinte SHA-256 constatée. Un test unitaire vérifie que les empreintes des fichiers embarqués correspondent à celles listées dans `docs/trust-store.md`, et que ce document, `index.txt` et le répertoire listent exactement les mêmes fichiers, pour détecter toute substitution, tout ajout ou tout retrait.

### 7.2 Chargement

`:core` charge les certificats DER (source `ANTS` pour les fichiers `ants-*.der`, `NATIONAL` pour les autres, D26), puis la Master List embarquée. Vérifier sa signature CMS avec le seul certificat signataire qu'elle contient serait circulaire : la signature est vérifiée **et** le signataire doit être émis par le CSCA allemand dont l'empreinte SHA-256 est épinglée dans le code (ou par son certificat de lien, également épinglé ; empreintes dans `docs/trust-store.md`). Sinon la liste embarquée est rejetée et le chargement échoue (D1). Le type de contenu de la Master List est vérifié.

Les Master Lists importées par l'utilisateur sont vérifiées selon §5.4 (signature CMS avec le signataire contenu dans le fichier, empreinte confirmée par l'utilisateur) ; un import invalide est ignoré.

Les sources sont fusionnées. En cas de doublon, la priorité est ANTS, puis publications nationales, puis Master List embarquée, puis Master Lists importées. Chaque CSCA est indexé par pays, sujet, identifiant de clé et empreinte, avec sa source (`ANTS`, `NATIONAL`, `EMBEDDED_MASTER_LIST`, `IMPORTED_MASTER_LIST`). Le caractère auto-signé d'une ancre se déduit de ses identifiants de clé, la signature n'étant vérifiée qu'en repli ; la construction de la chaîne vérifie toujours chaque signature (D16).

Le magasin est préchargé en arrière-plan au démarrage du processus, pour que ni la première lecture ni l'écran « Magasin de confiance » n'attendent ; une erreur de préchargement est retentée et signalée à l'accès suivant (D16).

### 7.3 Mise à jour

Les CSCA tournent tous les trois à cinq ans. Le magasin embarqué est mis à jour à chaque release selon la procédure de `docs/trust-store.md`. Entre deux releases, l'utilisateur peut importer une Master List plus récente (section 5.4). L'appli ne télécharge jamais rien.

### 7.4 Limites documentées

- Un pays absent du magasin (ANTS, publications nationales, liste BSI et imports) donne le verdict « Émetteur inconnu », pas « Échec ».
- La liste BSI ne contient pas le CSCA des cartes d'identité slovaques ni 4 CSCA hongrois de 2024 ; ils figurent dans les Master Lists italienne et suédoise, que l'utilisateur peut importer (D1).
- Les listes de révocation ne sont pas consultées en v1 (elles nécessitent le réseau).

Ces limites sont affichées dans l'écran « À propos ».

## 8. Vie privée et sécurité

- Aucune donnée lue n'est écrite sur disque, en cache, en base ou en log, ni en debug ni en release. Aucun `Bundle`, `SavedStateHandle`, `rememberSaveable` ni préférence ne contient de donnée lue ni de clé (la seule préférence est l'indicateur « introduction vue », D35) ; après la mort du processus, l'application redémarre sur l'accueil, vide, ou sur l'introduction tant qu'elle n'a pas été vue.
- Aucun journal, même temporaire pour déboguer : ni `Log`, ni `println`, ni `printStackTrace`. Le diagnostic passe par le code d'erreur affiché (§5.2). Les loggers `java.util.logging` de JMRTD et SCUBA (`org.jmrtd`, `net.sf.scuba`) sont coupés au début de chaque lecture, car ils écrivent des APDU en clair (D14).
- `FLAG_SECURE` sur toute l'activité dès sa création : pas de capture d'écran, pas d'aperçu dans le multitâche, sur l'accueil (saisie du CAN et de la MRZ) comme sur la lecture, le résultat et la photo en plein écran (audit V7).
- Effacement : les tableaux d'octets de DG1, DG2, DG7, DG11 et DG12 (DG bruts, portrait, signature, images de DG12) et les images décodées (bitmaps remis à zéro puis recyclés) sont effacés à la sortie de l'écran de résultat (« Effacer », retour arrière), lors de la mise en arrière-plan de l'appli pendant une lecture ou sur le résultat, à l'annulation d'une lecture et à la fin du `ViewModel`. Les bitmaps sont possédés par la session, de sorte que l'effacement les atteigne directement, y compris quand un décodage se termine après la sortie de l'écran (audit V13). Une rotation n'efface rien. Un rapport produit par une lecture abandonnée entre-temps est effacé dès sa réception, sans être publié.
- Limites de l'effacement, assumées et documentées : les `String` et dates (nom, numéro, CAN, champs de DG11 et DG12) sont immuables et ne peuvent pas être remises à zéro, seul le ramasse-miettes les libère ; les tampons internes de JMRTD (flux de lecture, objets de la LDS, messagerie sécurisée), d'OpenJPEG et du décodeur d'images d'Android sont libérés sans remise à zéro. Ces copies restent dans la mémoire du processus, ne sont ni écrites ni journalisées. Sceau remet à zéro tous ses propres tableaux, y compris les tampons natifs intermédiaires du décodage JPEG 2000 (D3, D14).
- La clé d'accès (CAN ou MRZ) n'est jamais mémorisée entre deux lectures : elle est oubliée après une lecture réussie (conservée après une erreur pour « Réessayer ») et à l'effacement.
- Décodage des images sous contrainte : une image venue de la puce est une donnée potentiellement hostile.
  - JPEG : dimensions lues avant décodage, plafond de pixels, sous-échantillonnage vers une taille d'affichage raisonnable (audit V2).
  - JPEG 2000 (natif) : dimensions refusées au-delà de 4096 × 4096 dès l'en-tête, flux de 16 Mo au plus, 1 à 4 composantes de 16 bits au plus, mode strict (flux tronqué refusé), messages d'OpenJPEG silencieux. Budget mémoire estimé avant décodage (composantes, précision, pixels, tuiles, blocs de code) avec refus au-delà, réduction de résolution pour les grandes images (audit V4). Compilation durcie (`-fstack-protector-strong`, `_FORTIFY_SOURCE=2`, visibilité cachée sauf la fonction JNI, RELRO complet) (D3).
  - Les décodages sont sérialisés, jamais en parallèle (audit V4). Un décodage refusé donne une image absente, jamais un plantage.
- Aucune télémétrie, aucun rapport de plantage, aucune bibliothèque d'analyse.
- Le nonce d'Active Authentication est tiré d'un `SecureRandom`.
- Le README et l'écran « À propos » rappellent le cadre d'usage : la lecture suppose que le titulaire présente lui-même son document. La photo est une donnée biométrique au sens du RGPD si elle sert à une comparaison automatisée, ce que Sceau ne fait pas et ne doit pas faire sans base légale dédiée. Ils précisent aussi que Sceau est fourni sans garantie, que son verdict n'a pas valeur de preuve et ne dit pas si le document a été déclaré perdu ou volé, et que Sceau n'est ni un outil officiel ni un service de vérification d'identité certifié (D30).
- La revue de sécurité du code (skill `android-securite`) est passée avant chaque release ; son rapport est versé dans `docs/audit-securite-<date>.md`.

## 9. Tests

### 9.1 Tests unitaires et de bout en bout (JVM)

Le module `:testchip` fournit :

- une fabrique de PKI factice générée à la volée : CSCA ancien et nouveau, certificat de lien, DS, SOD signé sur des DG factices, DG14 avec une paire de clés CA et DG15 avec une paire de clés AA, en RSA et en ECDSA ;
- une puce ICAO simulée à état, écrite sans le code protocolaire de JMRTD : EF.CardAccess, BAC, PACE (mapping générique ECDH), messagerie sécurisée 3DES et AES, Chip Authentication, Active Authentication ;
- une CNIe spécimen (identité SPECIMEN, CAN 123456, portrait JPEG 2000 synthétique) et son magasin de test.

Les tests de `:core` utilisent cette puce simulée pour des lectures de bout en bout de `readAndVerify` (BAC et PACE), et un transport qui rejoue des échanges APDU enregistrés pour les cas de séquence et d'erreur. Ils échouent si DG3 ou DG4 est demandé. Cas couverts au minimum :

- chaîne nominale : verdict Authentique
- signature du SOD altérée : Échec
- DG2 modifié après signature : Échec sur l'empreinte
- DG annoncé dans le SOD mais non fourni (DG2, DG7, DG14 ou DG15) : Échec
- DS expiré avant la date de délivrance : Échec sur la validité ; date estimée hors période : non disponible
- DS non rattaché à un CSCA connu : Émetteur inconnu
- pays du CSCA, du DS et de DG1 incohérents : Échec
- DG14 et DG15 absents du SOD : Signature valide, puce non vérifiée
- réponse AA signée avec une mauvaise clé : Échec
- algorithme de signature inconnu : `UnsupportedAlgorithm` ; algorithme refusé (§6.2) : jamais « ok »
- repli PACE → BAC, et absence de repli sur refus de clé ou délai dépassé
- Master List avec signature CMS invalide, ou liste embarquée dont le signataire n'est pas ancré : rejetée au chargement
- empreintes des fichiers embarqués conformes à `docs/trust-store.md`
- codes d'erreur sans donnée personnelle

Les tests de `:app` (JVM) couvrent la saisie de l'accueil, la mise en forme des contrôles, la correspondance des erreurs et le mode démo de bout en bout.

### 9.2 Code natif

Le cœur de décodage JPEG 2000 (`jp2_decode.c`), séparé du pont JNI, est testé sur l'hôte sous AddressSanitizer et UndefinedBehaviorSanitizer (`app/src/main/cpp/test/run-host-tests.sh`) : images synthétiques, flux tronqués, altérés et démesurés, bombes de décompression. Le harnais est rejoué à chaque modification du code natif ou montée de version d'OpenJPEG.

### 9.3 Mode démo

L'APK de debug propose « Simuler une CNIe (démo) » (§5.1) : la CNIe spécimen de `:testchip` est lue par le même chemin qu'un document réel, avec les mêmes règles d'effacement, et vérifiée avec son propre magasin de test, qui ne remplace ni ne complète jamais le magasin réel. La clé saisie est oubliée au lancement de la démo. Dans l'APK release, l'entrée n'existe pas et aucune classe de `:testchip` n'est présente : `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` ne doit pas contenir `project :testchip` (D17).

### 9.4 Tests sur appareil

Plan de test manuel dans `docs/test-plan.md`, exécuté via adb sur téléphone réel (skill `android-test`) avec une vraie CNIe et un vrai passeport :

- cas nominal carte d'identité, cas nominal passeport
- CAN faux, MRZ fausse, puis attente de la puce en pénalité
- retrait du document pendant la lecture
- document expiré
- rotation de l'écran et mise en arrière-plan pendant la lecture et sur l'écran de résultat (vérification de l'effacement)
- aperçu du multitâche et capture d'écran refusés sur tous les écrans
- NFC désactivé, puis réactivé
- import d'une Master List valide puis invalide
- saisie des dates au clavier, mode démo (APK de debug)

## 10. Livrables et organisation du dépôt

```
README.md                    présentation, captures, cadre d'usage, installation
SPEC.md                      ce document
LICENSE                      GNU GPL version 3 (le projet est sous GPL-3.0-or-later)
CONTRIBUTING.md              règles de contribution (sections 2 et 3)
docs/architecture.md         modules, flux de données, cycle de vie des données sensibles
docs/protocol.md             séquence ICAO détaillée, délais, codes d'erreur, table du verdict
docs/trust-store.md          provenance et empreintes des certificats, procédure de mise à jour
docs/dependencies.md         dépendances, licences, compatibilité F-Droid
docs/test-plan.md            plan de test manuel
docs/decisions.md            décisions et écarts par rapport à SPEC.md, justifiés
docs/release.md              procédure de release : contrôles, signature, assets, tag, F-Droid
docs/reproducible-builds.md  builds reproductibles (D25)
docs/fdroid/                 brouillon de recette fdroiddata (APK universel)
docs/audit-securite-*.md     rapports de revue de sécurité
fastlane/metadata/           fr-FR et en-US : description, captures, changelog
core/                        module Kotlin pur
app/                         module Android
app/src/main/cpp/            décodeur JPEG 2000 natif (pont JNI, cœur de décodage, CMake)
app/src/main/cpp/openjpeg/   sous-module git OpenJPEG
app/src/main/cpp/test/       harnais de test hôte sous sanitizers (hors APK)
testchip/                    PKI factice, puce simulée, CNIe spécimen (tests et debug)
```

Releases : dépôt public https://github.com/mgdx/Sceau ; APK par architecture et APK universel, signés par l'auteur, publiés sur les releases GitHub (`sceau-<version>-<abi>.apk`, `sceau-<version>-universal.apk`, `SHA256SUMS`), tag annoté `vX.Y` (`vX.Y.Z` pour un correctif), changelog Fastlane à jour. F-Droid reconstruit et publie le seul APK universel, signé par l'auteur grâce aux builds reproductibles (D25, D38). Procédure : `docs/release.md`. Préparation de la soumission à F-Droid avec le skill `android-fdroid`.

## 11. Jalons

État au 2026-10-06 : Socle, Lecture, Vérification et Finitions atteints ; Publication en cours, première release publique en version 0.9 (tag `v0.9`, D38).

1. **Socle** (atteint) : modules, CI, ktlint, magasin de confiance chargé et testé, fabrique de test crypto.
2. **Lecture** (atteint) : transport `IsoDep`, PACE et BAC, lecture des DG, affichage brut de DG1 et de la photo.
3. **Vérification** (atteint) : PA, CA, AA, `VerificationReport`, écran de résultat complet avec verdict et liste de contrôle.
4. **Finitions** (atteint) : données complémentaires DG11 et DG12, écran magasin de confiance et import, écran À propos, effacement mémoire vérifié, `FLAG_SECURE`, traductions en-US, corrections de l'audit de sécurité.
5. **Publication** (en cours) : revue sécurité, plan de test sur appareil (APK release signé compris), métadonnées Fastlane, première release 0.9, soumission F-Droid.
