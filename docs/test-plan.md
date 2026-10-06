# Plan de test sur appareil

Plan de test manuel de SPEC §9.2, exécuté via adb sur un téléphone réel (skill `android-test`) avant chaque release. Il complète les tests unitaires de `:core` (SPEC §9.1), qui couvrent la cryptographie sur une PKI factice ; ici on vérifie la lecture NFC réelle, l'interface et le cycle de vie.

## Préparation

### Matériel

| Élément | Usage |
|---|---|
| Téléphone Android 8.0+ avec NFC | appareil de test (noter modèle et version d'Android) |
| CNIe française (format carte bancaire, 2021 ou après) | cas nominal carte d'identité (PACE, CAN), puis par la MRZ (TP-25) |
| Carte d'identité d'un autre pays de l'UE, délivrée en août 2021 ou après (facultatif) | TP-25, étape 6 |
| Passeport biométrique français | cas nominal passeport (MRZ) |
| Document expiré (passeport ou carte) | TP-09 |
| Document d'un émetteur absent du magasin embarqué, par exemple une carte d'identité slovaque (voir décision D1) ; à défaut, un passeport d'un pays absent de la Master List du BSI | TP-19 |
| Carte sans contact non ICAO (carte bancaire, carte de transport) | TP-20 |
| Master List nationale valide, autre que celle du BSI déjà embarquée : Italie ou Suède (`.ml`) | TP-17, TP-19 |

Aucune donnée personnelle des documents de test ne doit figurer dans le compte rendu : noter seulement « conforme » ou l'écart constaté.

### Commandes adb utiles

```bash
PKG=io.github.mgdx.sceau

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n $PKG/.MainActivity                   # lancer l'application

adb shell svc nfc disable                                   # couper le NFC
adb shell svc nfc enable                                    # rétablir le NFC

adb shell settings put system accelerometer_rotation 0      # rotation manuelle
adb shell settings put system user_rotation 1               # paysage
adb shell settings put system user_rotation 0               # portrait
adb shell settings put system accelerometer_rotation 1      # rétablir la rotation auto

adb shell input keyevent KEYCODE_HOME                       # mise en arrière-plan
adb shell input keyevent KEYCODE_APP_SWITCH                 # écran multitâche
adb shell input keyevent KEYCODE_BACK                       # retour arrière
adb shell am kill $PKG                                      # tuer le processus (application en arrière-plan)

adb exec-out screencap -p > capture.png                     # capture d'écran
adb push master-list.ml /sdcard/Download/                   # déposer une Master List à importer
adb logcat --pid=$(adb shell pidof $PKG)                    # journaux de l'application
adb shell dumpsys package $PKG | grep -A5 "permission"      # permissions demandées et accordées
```

Un seul agent ou testeur utilise le téléphone à la fois.

### Modèle de compte rendu

Pour chaque cas : cocher OK ou KO, et en cas de KO noter l'écart, les étapes exactes et, si pertinent, les journaux (après avoir vérifié qu'ils ne contiennent aucune donnée personnelle, ce qui serait en soi un KO de TP-21).

---

## A. Installation et environnement

### TP-01 Installation et permissions

- **Préconditions** : application non installée.
- **Étapes** :
  1. `adb install -r app-debug.apk`, puis lancer l'application.
  2. `adb shell dumpsys package io.github.mgdx.sceau | grep -A5 permission`.
- **Attendu** : l'application démarre sur l'introduction (premier lancement, TP-30), sans demande de permission à l'exécution. Seules `android.permission.NFC` et la permission interne `io.github.mgdx.sceau.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (décision D7) apparaissent ; aucune permission `INTERNET` ni `ACCESS_NETWORK_STATE`.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-02 NFC désactivé, puis réactivé

- **Préconditions** : application sur l'accueil.
- **Étapes** :
  1. `adb shell svc nfc disable`.
  2. Revenir à l'application ; observer l'accueil.
  3. Toucher le bouton du bandeau : les réglages NFC du système s'ouvrent.
  4. `adb shell svc nfc enable`, revenir à l'application.
  5. Saisir un CAN valide et lire une CNIe.
- **Attendu** : bandeau « NFC désactivé » avec bouton vers les réglages (étapes 2 et 3) ; le bandeau disparaît après réactivation sans redémarrer l'application (étape 4) ; la lecture aboutit (étape 5).
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-03 Appareil sans NFC (si un tel appareil ou émulateur est disponible)

- **Préconditions** : appareil ou émulateur sans matériel NFC.
- **Étapes** : installer et lancer l'application.
- **Attendu** : installation possible (`android.hardware.nfc` non obligatoire), l'accueil explique l'absence de NFC et le bouton « Lire » est désactivé.
- **Résultat** : ☐ OK ☐ KO ☐ Non testé — Notes :

## B. Lectures nominales

### TP-04 Cas nominal : carte d'identité (CNIe)

- **Préconditions** : NFC activé, application sur l'accueil, onglet « Carte d'identité ».
- **Étapes** :
  1. Saisir le CAN à 6 chiffres imprimé au recto ; vérifier le clavier numérique et la validation à 6 chiffres.
  2. Toucher « Lire », poser la carte contre le dos du téléphone.
  3. Observer les 5 étapes, puis l'écran de résultat.
- **Attendu** : les étapes se cochent dans l'ordre ; verdict **Authentique** (vert) ; photo affichée et agrandissable ; identité DG1 complète (drapeau et nom du pays) ; liste de contrôle : canal « PACE », signature du SOD OK, chaîne vers le CSCA e-ID de l'ANTS (source ANTS), validité du DS OK, empreintes OK avec liste des DG, CA et/ou AA OK. Données complémentaires affichées seulement si DG11/DG12 présents.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-05 Cas nominal : passeport

- **Préconditions** : onglet « Passeport » (sélectionné à l'ouverture, D29).
- **Étapes** :
  1. Saisir le numéro de document (vérifier les majuscules forcées), puis la date de naissance et la date d'expiration au clavier numérique (décision D10 ; le clavier se retire au huitième chiffre de l'expiration, D29) : les `/` apparaissent au fil de la frappe, dans l'ordre JJ/MM/AAAA en français (MM/JJ/AAAA en anglais américain). Essayer une date inexistante (31/02), une naissance future et une expiration avant 1990 : message sous le champ, « Lire » inactif.
  2. Toucher « Lire », poser le passeport ouvert sur la page de la puce (ou la couverture selon le modèle).
- **Attendu** : lecture complète ; canal PACE si annoncé, sinon BAC (ou BAC après repli, décision D13) ; verdict **Authentique** pour un passeport français récent (chaîne vers un CSCA passeport de l'ANTS, certificats de lien le cas échéant) ; même contenu que TP-04.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-25 Carte d'identité lue par la MRZ

- **Préconditions** : NFC activé, application sur l'accueil.
- **Étapes** :
  1. Onglet « Carte d'identité » : le sélecteur CAN / MRZ est sur **CAN** et seul le champ CAN est affiché (décision D31). Saisir deux chiffres du CAN.
  2. Toucher « MRZ » : les trois champs numéro du document, date de naissance et date d'expiration s'affichent ; l'aide sous le numéro dit « Tel qu'imprimé sur la carte, 9 caractères au plus ». Revenir sur « CAN » : les deux chiffres saisis sont toujours là. Repasser sur « MRZ ».
  3. Saisir le numéro du document, la date de naissance et la date d'expiration lus dans la MRZ au verso de la CNIe (le clavier se retire au huitième chiffre de l'expiration, comme en TP-05).
  4. Toucher « Lire », poser la carte.
  5. Revenir à l'accueil depuis le résultat (retour arrière ou « Effacer »).
  6. Si une carte d'identité d'un autre pays de l'UE est disponible : la lire d'abord avec son CAN, puis avec sa MRZ.
- **Attendu** : étape 4 : lecture complète, canal « PACE » (clé MRZ), même résultat que TP-04 (verdict **Authentique**, chaîne vers le CSCA e-ID de l'ANTS). Étape 5 : l'accueil est revenu à son état initial, comme après toute lecture (TP-15) : onglet Passeport, aucun champ rempli, et l'onglet Carte d'identité de nouveau sur **CAN**. Étape 6 : les deux clés ouvrent la puce (canal PACE, ou BAC par la MRZ si la carte n'annonce pas PACE) ; verdict **Authentique**, ou **Émetteur inconnu** si son CSCA manque au magasin embarqué (TP-19). Si le CAN imprimé n'a pas 6 chiffres, le noter : seule la MRZ est alors utilisable.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-26 CIE italienne à DG12 erroné (anomalie connue de l'émetteur)

- **Préconditions** : une carte d'identité italienne CIE 3.0 délivrée entre octobre 2017 et février 2018 (numéro `CA…`), figurant si possible dans la Deviation List italienne (décision D34, `docs/trust-sources.md` §2.4). Cas rare : à mener seulement si une telle carte est disponible ; sinon, il est couvert par `KnownDeviationsTest` et `KnownDeviationEndToEndTest` sur la puce simulée.
- **Étapes** : onglet « Carte d'identité », CAN (ou MRZ), lire la carte, déplier la ligne « Empreintes des groupes de données ».
- **Attendu** : la ligne « Empreintes » est cochée et indique « DG12 : anomalie connue publiée par l'émetteur, données écartées » ; aucune donnée de DG12 n'est affichée (autorité, date de délivrance, observations) ; la ligne « Validité du DS » prend la date de signature du SOD ; verdict **Authentique** si la Chip Authentication ou l'Active Authentication réussit. Une CIE plus récente (DG12 conforme) ne montre aucune anomalie et affiche ses données DG12.
- **Résultat** : ☐ OK ☐ KO — Notes :

## C. Erreurs de lecture

Pour toutes les erreurs, l'écran affiche sous le message le code technique complet en petit (décision D11), par exemple `TIMEOUT-SECURE_CHANNEL-INS86-L10` : le noter tel quel dans le compte rendu, il ne contient aucune donnée personnelle.

### TP-06 CAN faux

- **Étapes** : saisir un CAN à 6 chiffres erroné, lire la CNIe.
- **Attendu** : message « CAN ou MRZ incorrects » (code `ACCESS_DENIED` affiché) et bouton « Réessayer » ; aucune donnée affichée. Revenir à l'accueil, corriger le CAN : la lecture aboutit. Ne pas enchaîner plus de deux essais faux : la puce peut ensuite imposer un délai (TP-23), voire bloquer le CAN.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-07 MRZ fausse

- **Étapes** : saisir une date de naissance erronée, lire le passeport.
- **Attendu** : même comportement que TP-06.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-08 Retrait du document pendant la lecture

- **Étapes** : lancer une lecture et retirer le document pendant « Lecture des données » (la photo DG2 est la lecture la plus longue).
- **Attendu** : message « Document retiré trop tôt » (code `CONNECTION_LOST-READ_DATA-INS…-L…`) et « Réessayer » ; reposer le document puis « Réessayer » : la lecture aboutit sans ressaisir la clé. Aucun plantage, aucun gel de l'interface en quittant l'écran.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-09 Document expiré

- **Étapes** : lire un document dont la date d'expiration est passée.
- **Attendu** : bandeau « Document expiré » ; le verdict d'authenticité n'est pas modifié par l'expiration (Authentique si la puce est vérifiée).
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-20 Carte non ICAO

- **Étapes** : saisir un CAN quelconque, poser une carte bancaire ou de transport sans contact.
- **Attendu** : message « document non conforme à la norme ICAO 9303 » (code `NOT_ICAO`), aucune autre tentative, bouton « Réessayer ».
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-23 Passeport après des tentatives ratées : attente jusqu'à 60 s

- **Préconditions** : passeport (de préférence français) ; accepter que la puce impose ensuite un délai pendant quelque temps.
- **Étapes** :
  1. Faire deux lectures avec une date de naissance erronée (TP-07) : noter le code affiché.
  2. Corriger la MRZ, toucher « Lire », poser le passeport et le garder immobile.
  3. Chronométrer l'étape « Ouverture du canal sécurisé ».
- **Attendu** : si la puce fait patienter, un message d'attente (« La puce du document fait patienter… jusqu'à une minute ») apparaît après 5 s dans cette étape ; la lecture aboutit sans intervention si la puce répond en moins de 60 s (23,3 s constatées sur un passeport français, décision D12). Si elle ne répond pas en 60 s : message « Délai dépassé » avec un code `TIMEOUT-SECURE_CHANNEL-INS…-L…`, sans nouvel essai automatique par BAC. Noter la durée observée et le code.
- **Résultat** : ☐ OK ☐ KO ☐ Non testé — Notes :

## D. Cycle de vie et effacement

### TP-10 Rotation pendant la lecture

- **Étapes** : lancer une lecture ; pendant « Lecture des données », `adb shell settings put system accelerometer_rotation 0` puis `adb shell settings put system user_rotation 1`.
- **Attendu** : l'écran se réoriente, la lecture continue sans repartir de zéro ni perdre l'état des étapes, et aboutit au résultat.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-11 Rotation sur l'écran de résultat

- **Étapes** : sur le résultat, basculer paysage puis portrait (`user_rotation 1` puis `0`).
- **Attendu** : le résultat reste affiché à l'identique (la rotation n'est pas une mise en arrière-plan) ; pas de nouvelle lecture.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-12 Mise en arrière-plan pendant la lecture

- **Étapes** : lancer une lecture ; pendant la lecture, `adb shell input keyevent KEYCODE_HOME` ; revenir à l'application.
- **Attendu** : la lecture est interrompue ; au retour, l'application est sur l'accueil ou sur une erreur avec « Réessayer », sans donnée lue affichée. Aucun plantage.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-13 Effacement après mise en arrière-plan sur le résultat

- **Étapes** : sur le résultat, `adb shell input keyevent KEYCODE_HOME`, attendre 2 s, revenir à l'application par l'icône ou le multitâche.
- **Attendu** : l'application affiche l'accueil, vide : ni verdict, ni photo, ni identité ; les champs CAN/MRZ sont vides. Pour relire, il faut ressaisir la clé.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-14 Mort du processus

- **Étapes** : sur le résultat, `adb shell input keyevent KEYCODE_HOME`, puis `adb shell am kill io.github.mgdx.sceau`, puis rouvrir l'application depuis le multitâche.
- **Attendu** : l'application redémarre sur l'accueil, vide ; aucune donnée restaurée, aucun plantage.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-15 Bouton « Effacer » et retour arrière

- **Étapes** :
  1. Sur le résultat, toucher « Effacer » (bas de l'écran) : retour à l'accueil.
  2. Refaire une lecture, puis utiliser « Effacer » de la barre d'action.
  3. Refaire une lecture, puis `adb shell input keyevent KEYCODE_BACK`.
- **Attendu** : dans les trois cas, retour immédiat à l'accueil avec les champs vides ; le retour arrière depuis l'accueil ne ramène jamais au résultat.
- **Résultat** : ☐ OK ☐ KO — Notes :

## E. Vie privée

### TP-16 `FLAG_SECURE` : capture refusée

- **Étapes** :
  1. Sur l'accueil (champs MRZ remplis), sur l'écran de lecture puis sur le résultat, `adb exec-out screencap -p > capture.png` et ouvrir l'image ; tenter aussi la capture système (touches volume bas + marche).
  2. Sur l'accueil et sur le résultat, `adb shell input keyevent KEYCODE_APP_SWITCH`.
  3. Revenir du résultat à l'accueil, puis ouvrir le magasin de confiance et « À propos », et refaire la capture.
- **Attendu** : captures noires ou refusées par le système sur tous les écrans, accueil, magasin de confiance et « À propos » compris, y compris après être revenu d'un écran de lecture ou de résultat : `FLAG_SECURE` est posé sur toute l'activité dès sa création (SPEC §2 et §8, audit V7). Aperçu masqué dans le multitâche sur tous les écrans.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-21 Aucune donnée dans les journaux ni sur le disque

- **Préconditions** : build debug.
- **Étapes** :
  1. `adb logcat -c`, puis une lecture complète d'une CNIe et d'un passeport, puis `adb logcat -d > log.txt`.
  2. Chercher dans `log.txt` le nom, le numéro de document, le CAN, la date de naissance (recherche locale, sans partager le fichier).
  3. `adb shell run-as io.github.mgdx.sceau ls -laR` : inventaire des fichiers de l'application.
- **Attendu** : aucune donnée personnelle ni clé dans les journaux ; les seuls fichiers de l'application sont ceux du système, `shared_prefs/sceau_preferences.xml` qui ne contient que l'indicateur `intro_seen` (D35) et, le cas échéant, les Master Lists importées dans `files/trust/`. Aucun fichier dans `cache/`.
- **Résultat** : ☐ OK ☐ KO — Notes :

## F. Magasin de confiance

### TP-17 Import d'une Master List valide

- **Préconditions** : Master List italienne ou suédoise copiée sur le téléphone (`adb push … /sdcard/Download/`).
- **Étapes** :
  1. Menu → « Magasin de confiance » : la liste s'affiche en moins de quelques secondes (magasin préchargé au démarrage, décision D16) ; noter le nombre de CSCA par pays (ANTS, Master List embarquée du BSI).
  2. « Importer une Master List ou un certificat », choisir le fichier dans le sélecteur du système.
  3. Comparer l'empreinte SHA-256 du signataire affichée (avec son sujet, la date de signature et le nombre de certificats) à celle publiée par l'autorité émettrice, puis confirmer.
  4. Réimporter le même fichier.
  5. Tuer l'application (`adb shell am kill io.github.mgdx.sceau` après `KEYCODE_HOME`), la relancer, rouvrir le magasin.
- **Attendu** : l'empreinte du signataire est affichée avant tout import, et « Annuler » n'importe rien ; après confirmation, message « Master List importée : N certificats », la liste apparaît dans la section « Éléments importés » (signataire, date de signature, nombre de certificats), les nouveaux CSCA apparaissent, marqués « Importé » ; les CSCA embarqués sont inchangés (un certificat déjà présent garde sa source ANTS ou Master List). Le second import ne crée pas de doublon. Les CSCA importés sont toujours là après redémarrage (un fichier dans `files/trust/`).
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-18 Import d'une Master List invalide

- **Étapes** :
  1. Créer une copie altérée : `cp it.ml bad.ml && printf '\x00' | dd of=bad.ml bs=1 seek=2000 conv=notrunc`, la pousser sur le téléphone et tenter l'import.
  2. Tenter l'import d'un fichier qui n'est pas une Master List (une image ou un texte renommé en `.ml`).
  3. Tenter l'import d'un fichier de plus de 20 Mo.
- **Attendu** : étapes 1 et 2 : message « Ce fichier n'est pas une Master List valide… » suivi d'un code de rejet (`BAD_SIGNATURE`, `NOT_CMS`…, voir `docs/trust-store.md`), aucune demande de confirmation ; étape 3 : « Fichier refusé : il dépasse 20 Mo ». Dans tous les cas, rien n'est importé, la liste des CSCA est inchangée, aucun plantage.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-19 Document étranger avant et après import

- **Préconditions** : aucune Master List importée (« Supprimer les certificats importés ») ; document d'un émetteur absent du magasin embarqué (voir Matériel).
- **Étapes** :
  1. Lire le document.
  2. Importer la Master List qui contient son CSCA (TP-17), puis relire le document.
  3. « Supprimer les certificats importés », puis relire une troisième fois.
- **Attendu** : étape 1 : **Émetteur inconnu** (gris), données affichées, ligne « Chaîne de certification » non disponible ; étape 2 : **Authentique** (ou « Signature valide, puce non vérifiée » si le document n'a ni DG14 ni DG15), chaîne vers un CSCA de source « importé » ; étape 3 : de nouveau **Émetteur inconnu**.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-27 Import d'un certificat CSCA seul (D33)

- **Préconditions** : aucun élément importé ; le CSCA de l'émetteur d'un document absent du magasin embarqué (voir Matériel), téléchargé depuis le site de l'État émetteur, copié sur le téléphone en DER (`.der`, `.cer` ou `.crt`) et, si possible, en PEM (`.pem`) ; son empreinte SHA-256 publiée par l'État, ou calculée sur le poste (`openssl x509 -in csca.pem -outform DER | sha256sum`).
- **Étapes** :
  1. Lire le document : **Émetteur inconnu**.
  2. Magasin de confiance → « Importer une Master List ou un certificat », choisir le fichier DER.
  3. Lire le dialogue, puis « Annuler ». Recommencer et confirmer.
  4. Réimporter le même certificat, en PEM cette fois.
  5. Relire le document.
  6. Tuer l'application, la relancer, rouvrir le magasin.
- **Attendu** : étape 3 : dialogue « Importer ce certificat ? » avec l'avertissement (aucune signature d'État ne garantit ce fichier, l'utilisateur lui accorde sa confiance, comparer l'empreinte), le type (« CSCA auto-signé » ou « Certificat de lien »), le sujet, le pays, la validité et l'empreinte SHA-256, identique à celle publiée ; « Annuler » n'importe rien. Après confirmation : message « Certificat importé. », section « Éléments importés » avec le sujet, le pays, la validité et l'empreinte ; dans la liste du pays, le certificat marqué « Certificat importé ». Étape 4 : message « Ce certificat est déjà importé. » sans dialogue, aucun doublon (même empreinte du DER, voir TP-34). Étape 5 : **Authentique** (ou « Signature valide, puce non vérifiée »), détail de la chaîne avec la source « Certificat importé ». Étape 6 : le certificat est toujours là (un fichier `<empreinte>.der` dans `files/trust/`).
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-28 Certificats refusés (D33)

- **Étapes** : tenter l'import de :
  1. un certificat de DS ou un certificat de site web (non CA) ;
  2. un fichier PEM contenant deux certificats (par exemple une chaîne `fullchain.pem`) ;
  3. un CSCA dont un octet de la signature est altéré (`cp csca.der bad.der && printf '\xff' | dd of=bad.der bs=1 seek=$(( $(stat -c %s csca.der) - 10 )) conv=notrunc`) ;
  4. un texte quelconque renommé en `.pem`.
- **Attendu** : aucun dialogue de confirmation, rien n'est importé, aucun plantage. Étape 1 : « Ce certificat ne peut pas être importé… (NOT_CA) » ; étape 3 : même message avec `BAD_SIGNATURE` ; étapes 2 et 4 : « Ce fichier n'est ni une Master List, ni un certificat DER ou PEM unique. »
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-29 Suppression à l'unité (D33)

- **Préconditions** : une Master List (TP-17) et un certificat (TP-27) importés.
- **Étapes** :
  1. Dans « Éléments importés », toucher l'icône de suppression du certificat, puis « Annuler ».
  2. Recommencer et confirmer.
  3. Relire le document de TP-27.
  4. Supprimer la Master List de la même manière, puis vérifier le bouton « Supprimer les certificats importés ».
- **Attendu** : étape 1 : dialogue « Supprimer cet élément importé ? », rien n'est supprimé. Étape 2 : « Élément importé supprimé. », le certificat disparaît de la section et de la liste du pays ; la Master List reste. Étape 3 : **Émetteur inconnu** (sauf si la Master List importée contient aussi ce CSCA). Étape 4 : la section disparaît une fois vide, et le bouton « Supprimer les certificats importés » est désactivé.
- **Résultat** : ☐ OK ☐ KO — Notes :

## G. Langue

### TP-22 Interface en anglais

- **Étapes** : passer le téléphone en anglais (Paramètres → Langues), parcourir l'accueil, une lecture avec erreur (TP-06), un résultat, le magasin de confiance et « À propos ».
- **Attendu** : tous les textes en anglais, aucun reste de français, aucune troncature. Dans « À propos », section « Limitations » : l'entrée sur les cartes d'identité allemandes (« German identity cards: only cards issued since 2 August 2021… ») est présente, en anglais, après celle sur les listes de révocation.
- **Résultat** : ☐ OK ☐ KO — Notes :

## H. Mode démo (APK de debug)

### TP-24 CNIe simulée

- **Préconditions** : APK de **debug** installé (`app-debug.apk`), aucun document sur le téléphone.
- **Étapes** :
  1. Accueil → menu → « Simuler une CNIe (démo) ».
  2. Observer l'écran de lecture puis le résultat.
  3. Toucher « Effacer ». Puis relancer la démo et passer en arrière-plan (`KEYCODE_HOME`) sur le résultat, revenir.
  4. Menu → « Magasin de confiance ».
  5. Installer l'APK **release** (`./gradlew :app:assembleRelease`, APK signé par le testeur) et ouvrir le menu de l'accueil.
- **Attendu** : étape 2 : les 5 étapes se cochent sans document ; résultat avec le bandeau « Document simulé — démonstration », verdict **Authentique**, portrait synthétique (silhouette, aucune photo de personne), identité SPECIMEN / MARIANNE, canal PACE, chaîne vers `CSCA-TEST-FRANCE`. Étape 3 : mêmes effacements qu'une lecture réelle (TP-13, TP-15). Étape 4 : aucun certificat de test dans le magasin réel. Étape 5 : l'entrée « Simuler une CNIe (démo) » n'existe pas dans l'APK release (décision D17).
- **Résultat** : ☐ OK ☐ KO — Notes :

## I. Introduction et pédagogie

Pour revenir à un premier lancement : `adb shell pm clear $PKG` (efface aussi les Master Lists importées).

### TP-30 Premier lancement : introduction, « Passer l'introduction » et retour arrière

- **Préconditions** : application installée sans données (`pm clear`).
- **Étapes** :
  1. Lancer l'application : l'introduction s'affiche (4 pages : la puce, la clé d'accès, ce que vérifie Sceau, la confidentialité). Balayer d'une page à l'autre dans les deux sens, puis utiliser « Suivant » et « Précédent » (absent de la page 1) ; vérifier l'indicateur de page et, avec TalkBack, l'annonce « Page 2 sur 4 ».
  2. Sur chacune des pages 1 à 3, le bouton « Passer l'introduction » est visible ; sur la page 4, il est remplacé par « Commencer ».
  3. Toucher « Passer l'introduction » sur la page 2 : l'accueil s'affiche. Retour arrière : l'application se ferme (l'introduction n'est pas dans la pile). Relancer : l'accueil s'affiche directement.
  4. `pm clear`, relancer, aller à la page 3, appuyer sur le retour arrière du système.
  5. Relancer l'application.
  6. `pm clear`, relancer, aller à la page 4, toucher « Commencer ».
- **Attendu** : étape 4 : l'accueil s'affiche (le retour arrière fait comme « Passer l'introduction », jamais d'écran vide ni de sortie de l'application). Étape 5 : l'accueil, sans introduction. Étape 6 : l'accueil, et l'introduction ne revient plus aux lancements suivants. Mode sombre et police système agrandie (Réglages > Affichage > Taille de police au maximum) : textes lisibles, pages défilables, boutons passés à la ligne plutôt que tronqués.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-31 Introduction rejouée depuis À propos

- **Étapes** :
  1. Menu → « À propos » → section « Comprendre Sceau » → « Revoir l'introduction ».
  2. Toucher « Passer l'introduction ».
  3. Rejouer l'introduction, aller à la page 4, toucher « Commencer ».
  4. Rejouer l'introduction, appuyer sur le retour arrière.
- **Attendu** : l'introduction repart de la page 1 ; aux étapes 2, 3 et 4, retour sur « À propos » (pas sur l'accueil), puis le retour arrière ramène à l'accueil.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-32 Écran « Ce que contient la puce »

- **Étapes** :
  1. « À propos » → « Ce que contient la puce » ; faire défiler tout l'écran ; retour arrière.
  2. Rejouer l'introduction, page 4 → « Ce que contient la puce » ; retour arrière.
- **Attendu** : quatre sections (Lu et affiché : DG1, DG2, DG11, DG12 ; Lu pour la vérification seulement : EF.CardAccess, EF.COM, EF.SOD, DG14, DG15, EF.CardSecurity ; Jamais lu : DG3, DG4, autres groupes ; Ce que Sceau ne fait pas). Étape 1 : retour sur « À propos ». Étape 2 : retour sur la page 4 de l'introduction, toujours affichée.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-33 Rotation et mort du processus pendant l'introduction

- **Préconditions** : `pm clear`, application lancée sur l'introduction.
- **Étapes** :
  1. Aller à la page 3, tourner l'écran en paysage puis en portrait.
  2. Sur la page 3, `KEYCODE_HOME`, puis `adb shell am kill $PKG`, puis rouvrir l'application depuis le multitâche.
  3. Toucher « Passer l'introduction », tourner l'écran sur l'accueil.
  4. `KEYCODE_HOME`, `am kill`, rouvrir depuis le multitâche.
- **Attendu** : étape 1 : la page 3 reste affichée, contenu défilable en paysage, boutons visibles. Étape 2 : l'introduction réapparaît (page 3 ou page 1), sans plantage. Étapes 3 et 4 : l'accueil reste affiché, l'introduction ne revient pas.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-34 Doublons refusés à l'import (D33)

- **Préconditions** : aucun élément importé ; copiés sur le téléphone : `core/src/main/resources/trust/ants-csca-2025.der`, `ge-csca-6.der` et `de-bsi-master-list.ml` du dépôt, un CSCA absent du magasin en DER et en PEM (celui de TP-27), et une Master List différente de la liste embarquée (par exemple une version plus récente publiée par le BSI, ou une autre Master List nationale).
- **Étapes** :
  1. Magasin de confiance → « Importer une Master List ou un certificat », choisir `ants-csca-2025.der`.
  2. Choisir `ge-csca-6.der`.
  3. Choisir `de-bsi-master-list.ml`.
  4. Importer le CSCA de TP-27 en DER et confirmer ; puis le choisir de nouveau, en DER puis en PEM.
  5. Choisir la Master List différente : lire le dialogue, confirmer. Puis la choisir de nouveau.
  6. Choisir un certificat contenu dans cette Master List (s'il en existe un téléchargeable seul).
- **Attendu** : aucun dialogue de confirmation aux étapes 1, 2, 3, à la reprise de l'étape 4, à la reprise de l'étape 5 et à l'étape 6, seulement un message. Étape 1 : « Ce certificat figure déjà dans le magasin de confiance (source : ANTS) : il n'y a rien à importer. » Étape 2 : même message, source « publication nationale ». Étape 3 : « Cette Master List est identique à celle embarquée dans l'application : il n'y a rien à importer. » Étape 4 : premier import normal, puis « Ce certificat est déjà importé. » en DER comme en PEM. Étape 5 : le dialogue indique « N nouveaux certificats sur M » sous « Nombre de certificats » (N peut valoir 0 : l'import reste possible) ; à la reprise, « Cette Master List est déjà importée. » Étape 6 : source « Master List importée » (ou « Master List embarquée » si le certificat y figure aussi). Dans « Éléments importés », seuls le CSCA de l'étape 4 et la Master List de l'étape 5, chacun une fois ; `files/trust/` ne contient que ces deux fichiers.

### TP-35 Carte d'identité allemande délivrée avant août 2021 : puce réservée aux autorités

- **Préconditions** : une carte d'identité allemande (*Personalausweis*) délivrée avant le 2021-08-02, avec son CAN (6 chiffres au recto). Cas rare : à mener seulement si une telle carte est disponible ; sinon, il est couvert par `GermanCardsEndToEndTest` sur la puce simulée (décision D36, `docs/deviations.md` §3.1).
- **Étapes** : onglet « Carte d'identité », saisir le CAN, lire la carte.
- **Attendu** : l'ouverture du canal sécurisé aboutit, puis la lecture s'interrompt sur le message « Ce document réserve sa puce aux autorités habilitées… cartes d'identité allemandes délivrées avant août 2021 », avec un code `ACCESS_RESTRICTED-SECURE_CHANNEL-SELECT_APPLET` ou `ACCESS_RESTRICTED-READ_DATA-SOD` (noter lequel) ; **pas de bouton « Réessayer »**, seulement « Annuler », qui ramène à l'accueil. Avec un CAN faux, en revanche : « CAN ou MRZ incorrects » (`ACCESS_DENIED`) et « Réessayer ». Si un autre code s'affiche (`UNEXPECTED-…`), le noter tel quel : le SW `6982` n'est qu'une hypothèse tirée de TR-03127.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-36 Carte eID allemande pour citoyens de l'Union (eID-UB)

- **Préconditions** : une carte eID allemande pour citoyens de l'Union (*eID-Karte für Unionsbürger*, délivrée depuis le 2020-11-01), avec son CAN. Cas rare : à mener seulement si une telle carte est disponible ; sinon, couvert par `GermanCardsEndToEndTest` et par la scène « eID-UB » de `TextOverflowTest`.
- **Étapes** : onglet « Carte d'identité », saisir le CAN, lire la carte, faire défiler le résultat et déplier les contrôles.
- **Attendu** : aucune erreur de lecture ; verdict **Authentique** si la Chip Authentication réussit (pas d'Active Authentication sur ces cartes). Sous le verdict, ni photo ni nom : la section « Identité » affiche le bandeau « La puce de cette carte ne contient aucune donnée d'identité : seule son authenticité peut être vérifiée… », puis seulement le type de document (code « UB ») et l'État émetteur (Allemagne). Aucun champ vide, « Inconnu » ou fait de « < ». Ligne « Validité du DS » : non disponible si ni le SOD ni DG1 ne portent de date (jamais en échec pour cette seule raison). Noter le verdict et le statut de chaque ligne.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-37 Signature du titulaire (DG7)

- **Préconditions** : APK de debug pour la partie démo ; si possible, un document réel dont la puce porte DG7 (signature manuscrite : certains passeports et cartes d'identité, à repérer dans la liste « Empreintes » des contrôles, qui cite DG7 s'il est lu). Décision D37.
- **Étapes** :
  1. Menu de l'accueil → « Simuler une CNIe (démo) », puis faire défiler le résultat, en thème clair puis en thème sombre.
  2. Déplier la ligne « Empreintes ».
  3. « Effacer », puis relancer la démo et mettre l'application en arrière-plan sur le résultat.
  4. Avec un document réel qui porte DG7 : le lire et faire défiler le résultat. Avec un document sans DG7 (une CNIe française, par exemple), vérifier l'absence de la section.
  5. À propos → « Ce que contient la puce ».
- **Attendu** : étape 1 : sous « Identité », une carte « Signature du titulaire » montre le paraphe synthétique (traits noirs) sur fond blanc, lisible dans les deux thèmes, sans déformation ; pas de « Image illisible ». Verdict inchangé (**Authentique**). Étape 2 : DG7 figure parmi les DG contrôlés. Étape 3 : retour à l'accueil et effacement comme pour la photo (TP-13, TP-15). Étape 4 : la signature réelle s'affiche (JPEG ou JPEG 2000) ; sans DG7, aucune section ni emplacement vide. Étape 5 : DG7 figure sous « Lu et affiché » ; la liste des DG non lus cite « DG5, DG6, DG8 à DG10, DG13 et DG16 ». Capture d'écran bloquée sur le résultat (`FLAG_SECURE`, TP-16).
- **Résultat** : ☐ OK ☐ KO — Notes :

## J. APK release et matériel réel

### TP-38 Lecture réelle d'un passeport avec l'APK release signé

- **Préconditions** : APK release universel signé par la procédure de `docs/release.md` (`sceau-<version>-universal.apk`, R8 actif, D24), installé à la place de l'APK de debug (`adb uninstall io.github.mgdx.sceau`, puis `adb install sceau-<version>-universal.apk`) ; passeport biométrique français.
- **Étapes** :
  1. `apksigner verify --print-certs` sur l'APK installé : noter l'empreinte du certificat.
  2. Lancer l'application : introduction, puis accueil ; vérifier que le menu ne propose pas « Simuler une CNIe (démo) ».
  3. Lire le passeport comme en TP-05, puis déplier chaque ligne de la liste de contrôle.
  4. « Effacer », puis À propos : noter la version affichée.
- **Attendu** : empreinte égale à `AllowedAPKSigningKeys` de la recette F-Droid ; pas d'entrée démo ; lecture complète, même résultat qu'en TP-05 (verdict **Authentique**, photo JPEG 2000 décodée, chaîne vers un CSCA de l'ANTS), aucun plantage ni code `UNEXPECTED-…` dû à une classe retirée par R8 ; version « 0.9 » (ou celle de la release). Toute différence avec l'APK de debug est un KO bloquant pour la release.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-39 Lecture réelle d'une CNIe avec l'APK release signé

- **Préconditions** : celles de TP-38 ; CNIe française.
- **Étapes** : onglet « Carte d'identité », saisir le CAN, lire la carte, faire défiler le résultat et déplier la liste de contrôle.
- **Attendu** : même résultat qu'en TP-04 : canal PACE, chaîne vers le CSCA e-ID de l'ANTS, verdict **Authentique**, photo affichée ; aucun plantage ni code `UNEXPECTED-…`.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-40 NFC coupé puis rallumé depuis le volet rapide, sans quitter l'application

- **Préconditions** : NFC activé ; application au premier plan sur l'accueil. Le volet rapide (glisser depuis le haut de l'écran) ne met pas l'activité en pause : c'est le cas que `adb shell svc nfc` et TP-02 (retour depuis les réglages) ne couvrent pas (commit `f6cd14b`).
- **Étapes** :
  1. Sur l'accueil, couper le NFC depuis le volet rapide, refermer le volet.
  2. Rallumer le NFC depuis le volet rapide, refermer le volet ; saisir un CAN valide, toucher « Lire » et poser une CNIe.
  3. Sur l'écran de lecture (document pas encore posé), couper le NFC depuis le volet rapide, refermer le volet.
  4. Rallumer le NFC depuis le volet rapide, refermer le volet, poser la CNIe.
- **Attendu** : étape 1 : le bandeau « NFC désactivé » apparaît en une seconde environ. Étape 2 : il disparaît en une seconde environ, et la carte est détectée et lue jusqu'au résultat, sans quitter ni relancer l'application. Étape 3 : le bandeau apparaît aussi sur l'écran de lecture. Étape 4 : il disparaît, la carte est détectée et la lecture aboutit. Jamais de bandeau figé ni de carte ignorée après réactivation.
- **Résultat** : ☐ OK ☐ KO — Notes :

### TP-41 PACE-CAM sur CNIe : Chip Authentication faite par PACE-CAM

- **Préconditions** : CNIe française (ou toute carte qui annonce le mapping PACE-CAM dans EF.CardAccess), décision D21.
- **Étapes** : lire la carte par le CAN ; sur le résultat, déplier les lignes « Canal sécurisé établi » et « Chip Authentication (DG14) » (libellé de la ligne inchangé quelle que soit la méthode).
- **Attendu** : si la carte annonce PACE-CAM : ligne « Chip Authentication » OK, avec « Méthode : PACE-CAM, pendant l'ouverture du canal, avec la clé de EF.CardSecurity » ; la CA via DG14 n'est pas refaite ; verdict **Authentique**. Si la méthode affichée est « Chip Authentication avec la clé de DG14 », la carte n'annonce pas PACE-CAM (ou son mapping n'a pas été retenu) : le noter, ce n'est pas un KO, mais l'absence de PACE-CAM sur une CNIe qui l'annonce en est un. Toute ligne « Chip Authentication » en échec (code `VERIFY_CHIP-CAM-…` dans le détail) est un KO.
- **Résultat** : ☐ OK ☐ KO — Notes :

---

## Synthèse

| Cas | Titre | Résultat |
|---|---|---|
| TP-01 | Installation et permissions | |
| TP-02 | NFC désactivé puis réactivé | |
| TP-03 | Appareil sans NFC | |
| TP-04 | Nominal carte d'identité | |
| TP-05 | Nominal passeport | |
| TP-06 | CAN faux | |
| TP-07 | MRZ fausse | |
| TP-08 | Retrait pendant la lecture | |
| TP-09 | Document expiré | |
| TP-10 | Rotation pendant la lecture | |
| TP-11 | Rotation sur le résultat | |
| TP-12 | Arrière-plan pendant la lecture | |
| TP-13 | Effacement après arrière-plan | |
| TP-14 | Mort du processus | |
| TP-15 | « Effacer » et retour arrière | |
| TP-16 | `FLAG_SECURE` | |
| TP-17 | Import Master List valide | |
| TP-18 | Import Master List invalide | |
| TP-19 | Document étranger avant et après import | |
| TP-20 | Carte non ICAO | |
| TP-21 | Journaux et disque | |
| TP-22 | Interface en anglais | |
| TP-23 | Passeport après tentatives ratées (attente jusqu'à 60 s) | |
| TP-24 | Mode démo (debug) et absence en release | |
| TP-25 | Carte d'identité lue par la MRZ | |
| TP-26 | CIE italienne à DG12 erroné (anomalie connue) | |
| TP-27 | Import d'un certificat CSCA seul | |
| TP-28 | Certificats refusés | |
| TP-29 | Suppression à l'unité | |
| TP-30 | Premier lancement : introduction, « Passer l'introduction », retour arrière | |
| TP-31 | Introduction rejouée depuis À propos | |
| TP-32 | Écran « Ce que contient la puce » | |
| TP-33 | Rotation et mort du processus pendant l'introduction | |
| TP-34 | Doublons refusés à l'import | |
| TP-35 | Carte d'identité allemande antérieure à août 2021 (accès réservé) | |
| TP-36 | Carte eID-UB allemande (aucune donnée d'identité) | |
| TP-37 | Signature du titulaire (DG7) | |
| TP-38 | Passeport avec l'APK release signé | |
| TP-39 | CNIe avec l'APK release signé | |
| TP-40 | NFC coupé puis rallumé depuis le volet rapide | |
| TP-41 | PACE-CAM sur CNIe | |

Appareil : ………… Android : ………… Version de Sceau : ………… Date : ………… Testeur : …………
