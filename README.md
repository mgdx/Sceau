# Sceau

Sceau est une application Android libre qui lit par NFC la puce des documents d'identité conformes à la norme ICAO 9303 (carte nationale d'identité électronique française, cartes d'identité européennes, passeports biométriques), affiche les données et la photo qu'elle contient, et vérifie cryptographiquement que le document est authentique et que la puce n'est pas un clone.

Sceau fonctionne entièrement hors ligne : il ne demande aucune permission réseau, ne conserve rien et n'envoie rien.

## Fonctionnalités

- **Lecture NFC** avec le CAN (6 chiffres imprimés sur la carte d'identité) ou la MRZ (numéro de document, date de naissance, date d'expiration), par PACE ou BAC selon ce que la puce annonce.
- **Affichage** de la photo, de l'identité (nom, prénoms, sexe, date de naissance, nationalité, type et numéro de document, État émetteur, date d'expiration) et, si la puce les contient, des données complémentaires (DG11, DG12).
- **Vérification de l'authenticité** :
  - *Passive Authentication* : la signature des données par l'État émetteur est vérifiée jusqu'à un certificat racine (CSCA) connu, et chaque groupe de données est comparé à son empreinte signée ;
  - *Chip Authentication* et *Active Authentication* : la puce prouve qu'elle détient une clé secrète, ce qu'un clone ne peut pas faire.
- **Verdict clair** : Authentique, Signature valide mais puce non vérifiée, Émetteur inconnu ou Échec, avec une liste de contrôle détaillée.
- **Magasin de confiance** consultable : CSCA français de l'ANTS et Master List allemande du BSI embarqués, et import d'une Master List au choix de l'utilisateur.
- **Effacement** : un bouton « Effacer », le retour arrière ou la mise en arrière-plan vident immédiatement la mémoire.
- Interface en français et en anglais, mode sombre suivant le système.

Sceau ne lit pas les empreintes digitales ni l'iris (réservés aux autorités), ne lit pas la MRZ par la caméra et ne fait aucune reconnaissance faciale.

## Documents pris en charge

| Document | Clé d'accès | Remarque |
|---|---|---|
| Carte nationale d'identité française (format carte bancaire, depuis 2021) | CAN | Vérifiée grâce au certificat CSCA e-ID de l'ANTS |
| Cartes d'identité européennes (règlement UE 2019/1157) | CAN si imprimé, sinon MRZ | Certaines cartes anciennes n'ont pas d'application ICAO |
| Passeports biométriques de tous pays | MRZ | |

Le magasin de confiance embarqué comprend les 5 CSCA français publiés par l'ANTS et la Master List publiée par le BSI (office fédéral allemand de la sécurité informatique), qui couvre les pays de l'Union européenne, de l'EEE, la Suisse, le Royaume-Uni et d'autres États, soit 112 émetteurs. Un document dont l'émetteur n'y figure pas donne le verdict « Émetteur inconnu » : ses données sont affichées mais rien n'est garanti. L'utilisateur peut alors importer une autre Master List publiée par un État (Italie, Suède…). Les listes de révocation ne sont pas consultées, car elles demanderaient le réseau.

Sceau n'est ni affilié au BSI, ni à l'ANTS, ni à l'ICAO, ni soutenu par eux : il redistribue sans modification des certificats et une liste que ces organismes publient.

## Captures d'écran

<!-- Captures à ajouter dans fastlane/metadata/android/fr-FR/images/phoneScreenshots/ -->

| Accueil | Lecture | Résultat | Magasin de confiance |
|---|---|---|---|
| *(à venir)* | *(à venir)* | *(à venir)* | *(à venir)* |

## Cadre d'usage

Sceau est conçu pour qu'une personne vérifie **son propre document**, ou un document que son titulaire lui présente lui-même, par exemple lors d'une transaction entre particuliers. La lecture de la puce exige le CAN ou la MRZ, imprimés sur le document : elle suppose que le titulaire le remet volontairement.

La photo stockée dans la puce est une donnée biométrique au sens du RGPD dès lors qu'elle sert à une comparaison automatisée avec un visage. Sceau ne fait pas cette comparaison et ne doit pas être utilisé pour la faire sans base légale dédiée. La comparaison entre la photo et la personne présente reste un contrôle visuel humain.

Le verdict de Sceau porte sur l'authenticité de la puce et des données signées par l'État émetteur ; il ne dit pas si le document a été déclaré perdu ou volé.

## Vie privée

- Seule permission demandée : NFC. **Aucune permission réseau** : l'application ne peut rien envoyer.
- Aucune donnée lue n'est écrite sur le disque, en cache, en base ou dans les journaux. La clé d'accès n'est jamais mémorisée d'une lecture à l'autre.
- Les données en mémoire sont effacées à la sortie de l'écran de résultat et à la mise en arrière-plan.
- Captures d'écran et aperçu dans le multitâche bloqués sur les écrans de lecture et de résultat.
- Aucune télémétrie, aucun rapport de plantage, aucune bibliothèque d'analyse ni de publicité, aucun service Google.

Détails : [`docs/architecture.md`](docs/architecture.md), section « Cycle de vie des données sensibles ».

## Installation

- **F-Droid** : soumission prévue après la première release.
- **Releases GitHub** : APK publiés sur la page Releases du dépôt, pour les versions étiquetées `vX.Y.Z`.

Configuration requise : Android 8.0 ou plus récent, avec NFC. L'application s'installe aussi sans NFC et l'indique à l'ouverture.

## Compilation

Prérequis : JDK 17 (compilation des modules) et JDK 25 (démon Gradle, voir `gradle/gradle-daemon-jvm.properties`), SDK Android, NDK `28.2.13676358` et CMake `4.1.2` (décodeur JPEG 2000 natif).

Le décodeur JPEG 2000 s'appuie sur [OpenJPEG](https://github.com/uclouvain/openjpeg), compilé depuis ses sources, présentes en sous-module git. Récupérez-les avant la première compilation :

```bash
git clone --recurse-submodules <url-du-dépôt>   # ou, dans un clone existant :
git submodule update --init
```

```bash
./gradlew :app:assembleDebug      # APK de debug : app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # APK release (R8), non signé, un par architecture + universel
./gradlew :core:test              # tests JVM du cœur
./gradlew check                   # tests, lint et ktlint, comme la CI
```

Le projet compte deux modules : `:core`, en Kotlin pur, qui lit et vérifie la puce, et `:app`, qui porte l'interface Android. Voir [`CONTRIBUTING.md`](CONTRIBUTING.md) et [`docs/`](docs/).

## Documentation

| Fichier | Contenu |
|---|---|
| [`SPEC.md`](SPEC.md) | Spécification, source de vérité |
| [`docs/architecture.md`](docs/architecture.md) | Modules, flux de données, cycle de vie des données sensibles |
| [`docs/protocol.md`](docs/protocol.md) | Séquence ICAO 9303, erreurs, calcul du verdict |
| [`docs/trust-store.md`](docs/trust-store.md) | Provenance et empreintes des certificats embarqués |
| [`docs/dependencies.md`](docs/dependencies.md) | Dépendances et licences |
| [`docs/test-plan.md`](docs/test-plan.md) | Plan de test sur appareil |
| [`docs/decisions.md`](docs/decisions.md) | Écarts à la spécification, justifiés |

## Licence

Sceau est distribué sous licence GNU GPL version 3 : voir [`LICENSE`](LICENSE). Les bibliothèques utilisées et leurs licences (LGPL, MIT, Apache 2.0) sont listées dans [`docs/dependencies.md`](docs/dependencies.md).
