# Magasin de confiance embarqué

Ce document recense les fichiers de `core/src/main/resources/trust/`, leur provenance et leurs
empreintes. Il fait foi : le test `TrustStoreFingerprintTest` recalcule la SHA-256 de chaque
fichier embarqué et la compare au tableau ci-dessous. Il vérifie aussi que le tableau,
`trust/index.txt` et le contenu du répertoire listent exactement les mêmes fichiers. Une
substitution, un ajout ou un retrait non documenté fait échouer la build.

## Fichiers embarqués

Format stable, lu par le test : une ligne par fichier, nom du fichier entre accents graves en
première colonne, SHA-256 constatée (hexadécimal minuscule) entre accents graves en dernière
colonne. Ne change ni l'ordre ni le nombre de colonnes sans adapter le test.

| Fichier | Rôle | Sujet | Validité (UTC) | Empreinte publiée par la source | SHA-256 constatée |
|---|---|---|---|---|---|
| `ants-csca-2010.der` | Passeport (CSCA) | `C=FR, O=Gouv, CN=CSCA-FRANCE` | 2010-12-09 → 2026-03-09 | SHA-1 `07dfdbe60862e9ef79b470b8e9f2be1541059c34` | `8d4d8939b9b31c0b45907bf0622f791fe90cf20e85cbbc2d368ee143b4a453ab` |
| `ants-csca-2015.der` | Passeport (CSCA) | `C=FR, O=Gouv, CN=CSCA-FRANCE` | 2015-09-04 → 2030-12-04 | SHA-1 `3720e6f7c248922568bba78661cde2a51c6e22df` | `5ba9a2069f34cd93fab9dcb39c6a7a7be62141f8bcb608aff815167c813ecf92` |
| `ants-csca-2020.der` | Passeport (CSCA) | `C=FR, O=Gouv, CN=CSCA-FRANCE` | 2020-05-26 → 2035-08-26 | SHA-1 `32b98cdbd85255c07503bfd96049038b241bfffe` | `28df42a7a0ed1b20f994cc96999060619e095b09764159703438ec60b88ee856` |
| `ants-csca-2025.der` | Passeport (CSCA) | `C=FR, O=Gouv, CN=CSCA-FRANCE` | 2025-02-19 → 2040-05-19 | SHA-1 `59c8be053778295654ed32672a8045ef41405b44` | `d628b5100ddcbed8f3e5fa05e53b6b80bebb1e6264a12583319ef955c91d9349` |
| `ants-csca-eid-2021.der` | e-ID, CNIe (CSCA) | `C=FR, O=Gouv, CN=eID-FRANCE` | 2021-02-15 → 2036-05-15 | SHA-256 `b33ea63b9be01082d98071a29111757c72257eba80d7205d21fa35436c29fe7c` | `b33ea63b9be01082d98071a29111757c72257eba80d7205d21fa35436c29fe7c` |
| `de-bsi-master-list.ml` | Master List CSCA (BSI, Allemagne) | signataire `C=DE, O=bund, OU=bsi, serialNumber=0039, CN=CSCA Master List Signer` | signée le 2026-05-28 ; signataire valide 2024-10-17 → 2028-10-17 | aucune pour le fichier (voir « Master List embarquée ») | `e036f8c989193b38cf19493bb2c957bfa2385b35a680bf03300515cad7526dd0` |
| `gb-csca-2026.der` | Passeport GB (CSCA GBR_2026_Root) | `C=GB, O=UKKPA, CN=Country Signing Authority` | 2026-08-24 → 2042-12-24 | aucune | `737ce248b62d8b6dc1c9e3aaa8b937334406bac17bb37235c1c50281e6ad7edb` |
| `gb-csca-link-2021-2026.der` | Passeport GB (lien GBR_2021-2026, signé par GBR_2021) | `C=GB, O=UKKPA, CN=Country Signing Authority` | 2026-08-24 → 2038-01-17 | aucune | `81d9f6bf3026a04e6e3847808cf094e34f9473a92ff66eb86a975f4c42055b5d` |
| `gr-csca-erp-003.der` | Titre de séjour GR (CSCA eRP 003) | `C=GR, O=Hellenic Republic, serialNumber=003, CN=CSCAeRP-HELLAS` | 2026-05-27 → 2041-05-26 | SHA-1 `3dd148e21a8c0d6d31d4269e9123f4bed9c7742b` | `8f01ced4c95d9ee5cc6915e1151432e18fa1615ad6d8e61bb6ea1af025368e3e` |
| `gr-csca-erp-link-002-003.der` | Titre de séjour GR (lien 002 → 003) | `C=GR, O=Hellenic Republic, serialNumber=003, CN=CSCAeRP-HELLAS` | 2026-05-27 → 2036-09-20 | SHA-1 `7a15cc652f7a13afc7f0862ae2c0ae205e871dbb` | `5bbdb09b12bc25f9373142409d9b52da806b764294baf4f028a2c13da15bdfc5` |
| `ge-csca-5.der` | Documents de voyage GE (CSCA n° 5) | `C=GE, O=Ministry of Justice of Georgia, OU=Public Service Development Agency, CN=GEO Country Signing CA` | 2025-09-09 → 2040-12-04 | SHA-256 `2ef9e14c6155c315b8d7681168774a063e6fd2639f51f7d5c904ef862933c844` | `2ef9e14c6155c315b8d7681168774a063e6fd2639f51f7d5c904ef862933c844` |
| `ge-csca-6.der` | Documents de voyage GE (CSCA n° 6, « G2 », actif) | `C=GE, O=Ministry of Justice of Georgia, OU=Public Service Development Agency, CN=GEO Country Signing CA G2` | 2025-09-10 → 2040-12-05 | SHA-256 `277fcd17b53b3e2f20cfee967db499189bf23e0ef0a9e1040c5a24c8b09cde3c` | `277fcd17b53b3e2f20cfee967db499189bf23e0ef0a9e1040c5a24c8b09cde3c` |
| `lu-csca-etravel-link.der` | Documents de voyage LU (lien CSCA ePassport → CSCA eTravel Documents) | `C=LU, O=Grand-Duchy of Luxembourg Ministry of Foreign Affairs, CN=Grand-Duchy of Luxembourg CSCA eTravel Documents` | 2018-11-02 → 2029-01-12 | aucune | `5acb0c0264ad18912ad80139b318ab507d00dc77a86ac4eb832d3510ff6ef95c` |

Les cinq certificats de l'ANTS sont des CSCA racines auto-signés (RSA 4096, `sha256WithRSAEncryption`).
Les archives de l'ANTS ne contiennent aucun certificat de lien. Le CSCA passeport 2010 est
expiré (mars 2026) mais reste nécessaire : il a signé des DS dont les passeports, valables
dix ans, sont encore en circulation. La période de validité est vérifiée à la date de
délivrance du document, pas à la date de lecture.

Toutes les empreintes constatées sont égales aux empreintes publiées, recalculées sur les
fichiers extraits des archives. Les fichiers `.crt` des archives étaient déjà encodés en DER ;
ils sont copiés sans conversion et seulement renommés.

## Provenance

| Fichier | Archive d'origine | Fichier dans l'archive | Page d'origine | Téléchargé le |
|---|---|---|---|---|
| `ants-csca-2010.der` | `CSCA-FRANCE_2010.zip` | `CSCA-FRANCE_2010.crt`, empreinte dans `CSCSA-FRANCE_2010_fingerprint.txt` | page CSCA de l'ANTS | 2026-09-25 |
| `ants-csca-2015.der` | `CSCA-FRANCE_2015.zip` | `CSCA-FRANCE_2015.crt`, empreinte dans `CSCA-FRANCE_2015_fingerprint.txt` | page CSCA de l'ANTS | 2026-09-25 |
| `ants-csca-2020.der` | `CSCA-FRANCE_2020.zip` | `CSCA-FRANCE_2020.crt`, empreinte dans `CSCA-FRANCE_2020_fingerprint.txt` | page CSCA de l'ANTS | 2026-09-25 |
| `ants-csca-2025.der` | `csca-france_2025.zip` | `CSCA-FRANCE_2025.crt`, empreinte dans `CSCA-FRANCE_2025_fingerprint.txt` | page CSCA de l'ANTS | 2026-09-25 |
| `ants-csca-eid-2021.der` | `Certificat_CSCA_PROD_EID.zip` | `eID-FRANCE.crt` (un `eID-FRANCE.pem` identique l'accompagne), empreinte dans `Fingerprint_SHA256.txt` | page CSCA e-ID de l'ANTS | 2026-09-25 |
| `de-bsi-master-list.ml` | `GermanMasterList.zip` | `DE_ML_2026-05-28-08-28-45.ml`, redistribué inchangé | <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.html> | 2026-09-25 |
| `gb-csca-2026.der` | aucune (fichier DER direct) | <https://hmpo.gov.uk/csca/certificate/12fd5015-cf50-47b0-a1fb-ca1c3cc0f0ee> | <https://hmpo.gov.uk/csca> | 2026-09-29 |
| `gb-csca-link-2021-2026.der` | aucune (fichier DER direct) | <https://hmpo.gov.uk/csca/certificate/3a083ccd-5e5d-4e7c-be90-bd4218e335af> | <https://hmpo.gov.uk/csca> | 2026-09-29 |
| `gr-csca-erp-003.der` | aucune (fichier DER direct) | `http://spoc.immigration.gov.gr/csca/CSCAeRP-HELLAS003.cer` | <http://spoc.immigration.gov.gr/csca/> | 2026-09-29 |
| `gr-csca-erp-link-002-003.der` | aucune (fichier PEM, converti en DER) | `http://spoc.immigration.gov.gr/csca/CSCAeRP-HELLAS003_link.cer` | <http://spoc.immigration.gov.gr/csca/> | 2026-09-29 |
| `ge-csca-5.der` | aucune (fichier DER direct) | <https://id.ge/en/pki/GEO-CSCA-5.crt> | <https://id.ge/en/tsp/csca/> | 2026-09-29 |
| `ge-csca-6.der` | aucune (fichier DER direct) | <https://id.ge/en/pki/GEO-CSCA-6.crt> | <https://id.ge/en/tsp/csca/> | 2026-09-29 |
| `lu-csca-etravel-link.der` | aucune (fichier DER direct) | <https://repository.incert.lu/CSCAeTravel_link_from_CSCAePass.crt> | <https://repository.incert.lu/> | 2026-09-29 |

Les archives ont été téléchargées par le mainteneur depuis les pages officielles de l'ANTS
(URL confirmées par le mainteneur le 2026-09-25) :

- page CSCA (passeports) : <https://ants.gouv.fr/csca> ;
- page CSCA e-ID (cartes d'identité) : <https://ants.gouv.fr/home/csca-e-id>.

Le site de l'ANTS filtre les robots : les archives se téléchargent à la main depuis ces pages,
puis `scripts/update-trust-store.sh --ants-dir` compare les empreintes publiées dans chaque
archive à celles recalculées (voir « Procédure de mise à jour »).

## Publications nationales

Sept certificats publiés par un État pour ses **propres** documents complètent la Master List
embarquée (décision D26). Ils sont absents de la liste du BSI, encore valides, et leur source a
le statut A de `docs/trust-sources.md` : licence ouverte (A1) ou publication officielle sans
restriction de réutilisation (A2). Le chargeur les reconnaît à leur nom : un fichier `.der` qui
ne commence pas par `ants-` porte la source `NATIONAL`, affichée « Publication nationale ».

| Pays | Autorité | Statut | Conditions et attribution |
|---|---|---|---|
| GB | HM Passport Office (UKKPA) | A1 | Open Government Licence v3.0. Attribution affichée dans « À propos » : « Contains public sector information licensed under the Open Government Licence v3.0 ». |
| GR | Ministère des Migrations et de l'Asile (titres de séjour) | A2 | Aucune mention de réutilisation sur le site. |
| GE | Public Service Development Agency, ministère de la Justice | A2 | Aucune mention de réutilisation sur le site. |
| LU | INCERT | A2 | Aucune mention de réutilisation sur le site. |

Contrôles faits le 2026-09-29 sur chaque fichier, avant de l'embarquer :

- téléchargement depuis la page officielle de l'État, par HTTPS sauf pour la Grèce, qui ne sert
  qu'en HTTP. L'intégrité repose alors sur les empreintes SHA-1 publiées sur la même page, sur
  le lien 002 → 003 signé par le CSCA 002 (dont l'empreinte SHA-1 publiée,
  `277fa92b9908cddb20899bb14e682de224e213c7`, concorde) et sur la signature du lien ;
- empreinte publiée, quand elle existe, égale à l'empreinte recalculée (GR : SHA-1, GE :
  SHA-256). Le Royaume-Uni et le Luxembourg n'en publient pas. Leur intégrité repose sur TLS et
  sur la signature des liens ;
- signature vérifiée : auto-signature des quatre CSCA racines ; lien GB signé par GBR_2021, lien
  GR par CSCAeRP-HELLAS 002 et lien LU par « Grand-Duchy of Luxembourg CSCA ePassport »
  (`CSCA_ePassport_card_1.crt` du même dépôt). La clé GB est une clé EC P-384 à paramètres de
  courbe explicites, qu'OpenSSL 3 refuse de vérifier en mode `verify` : la vérification est
  faite avec la bibliothèque Python `cryptography`, et refaite par `TrustStoreTest`
  (`nationalLinksAreSignedByAnEmbeddedAnchor`), qui vérifie chaque lien avec la clé d'un CSCA
  du magasin ;
- extensions : `basicConstraints` cA=TRUE et `keyUsage` keyCertSign présents sur les sept.

Les deux CSCA géorgiens n° 5 et n° 6 partagent la même clé (même SKI) sous deux noms. Le n° 6 est
aussi dans les Master Lists italienne, suédoise et néerlandaise, qui ne sont pas embarquées.

## Master List

La SPEC (§7.1) prévoit une Master List embarquée. Le code la prend en charge : un fichier `.ml`
listé dans `trust/index.txt` est parsé au chargement. Sa signature CMS est vérifiée, et son
signataire doit être émis par un certificat dont l'empreinte est épinglée dans le code (voir
« Ancrage du signataire »). Sinon le chargement échoue (`InvalidMasterListException`). Ses
certificats portent la source `EMBEDDED_MASTER_LIST`. En cas de doublon, l'ordre de priorité
est ANTS, puis publications nationales, puis Master List embarquée, puis Master List importée.

### Master List embarquée : German Master List du BSI

`de-bsi-master-list.ml` est la German Master List publiée par le BSI (Bundesamt für Sicherheit
in der Informationstechnik). Ce n'est pas la Master List de l'ICAO que cite la SPEC (§7.1),
d'où le nom du fichier.

- Page : <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.html>
- Archive : <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.zip?__blob=publicationFile&v=108>,
  SHA-256 constatée de l'archive `ac294f59876129ce682350bead0f7025373bf2d833bbd879bb536201226b612c`.
- Fichier : `DE_ML_2026-05-28-08-28-45.ml` extrait de l'archive, 902 359 octets, signé le
  2026-05-28, redistribué **inchangé** et seulement renommé. Le BSI ne publie pas d'empreinte
  pour le fichier : l'intégrité repose sur la signature CMS et sur l'ancrage du signataire
  décrit ci-dessous.
- Contenu : 588 certificats CSCA et certificats de lien de 112 émetteurs, tous lisibles par
  BouncyCastle, dont 160 clés EC à paramètres de courbe explicites et un numéro de série
  négatif. Les 3 certificats français déjà fournis par l'ANTS gardent la source `ANTS`.
- Téléchargé le 2026-09-25.

Conditions de réutilisation fixées par le BSI : usage, y compris commercial, autorisé ; pas
d'usage publicitaire ; ne rien laisser croire d'une coopération avec le BSI ou d'une
approbation de sa part. Sceau redistribue le fichier sans modification, le cite comme source
et ne revendique aucun lien avec le BSI.

### Ancrage du signataire

Vérifier une Master List avec le seul certificat signataire qu'elle transporte prouve son
intégrité, pas son origine. Pour une liste **embarquée**, `:core` exige en plus que le
signataire soit signé par l'un des certificats épinglés
(`TrustStoreLoader.EMBEDDED_MASTER_LIST_ANCHORS`). Ce certificat épinglé est recherché parmi les
certificats de la liste et dans le bloc `certificates` du CMS :

- CSCA Allemagne (`C=DE, O=bund, OU=bsi, CN=csca-germany`), empreinte SHA-256 publiée par le
  BSI : `2084aed7a991b3158e63ad750d3dc38bc6c9dcf5958f28d1162f49884e91ada8` ;
- son certificat de lien, empreinte SHA-256 publiée par le BSI :
  `5f28cb692fb346ba2d23674c8778f2810c24a35869e5b517de3abd67d5db20bd`.

Échec de l'ancrage : `InvalidMasterListException("UNTRUSTED_SIGNER")`.

### Import par l'utilisateur

Entre deux releases, l'utilisateur peut importer une Master List plus récente depuis l'écran
« Magasin de confiance » (bouton « Importer une Master List », SPEC §5.4). Sont acceptés les
fichiers CMS `.ml`, `.der` et `.p7b`, par exemple la German Master List publiée par le BSI ou
une Master List obtenue auprès d'une autorité nationale. La signature CMS est vérifiée avec le
certificat signataire contenu dans le fichier, dont l'empreinte SHA-256 est présentée à
l'utilisateur avant l'import. Il n'y a pas d'ancrage épinglé pour les imports : c'est
l'utilisateur qui décide de faire confiance au signataire. Une liste invalide est rejetée. Les
certificats importés portent la source `IMPORTED_MASTER_LIST` et s'affichent « importé ».

Codes de rejet (`InvalidMasterListException.code`, sans donnée personnelle) :

| Code | Cause |
|---|---|
| `NOT_CMS` | Le fichier n'est pas un CMS SignedData lisible. |
| `BAD_CONTENT_TYPE` | Le contenu signé n'est pas de type `id-icao-cscaMasterList` (2.23.136.1.1.2). |
| `BAD_CONTENT` | Contenu absent (signature détachée) ou structure `CscaMasterList` invalide. |
| `NO_SIGNER` | Aucun signataire, ou certificat signataire absent du CMS. |
| `BAD_SIGNATURE` | La signature CMS ne se vérifie pas. |
| `SIGNER_NOT_VALID` | Le certificat signataire n'était pas valide à la date de signature déclarée. |
| `UNSUPPORTED_ALGORITHM` | Algorithme de signature inconnu de BouncyCastle. |
| `UNTRUSTED_SIGNER` | Liste embarquée seulement : signataire non émis par un certificat épinglé. |

Un certificat illisible à l'intérieur d'une liste valide est ignoré et ne fait pas rejeter la
liste. Les certificats sont lus par BouncyCastle, qui accepte les clés EC à paramètres de
courbe explicites et les numéros de série négatifs, présents dans les listes réelles.

### Import d'un certificat seul (D33)

Le même bouton, « Importer une Master List ou un certificat », accepte aussi un certificat CSCA
auto-signé ou un certificat de lien isolé, tel que certains États le publient sur leur site
(`.der`, `.cer`, `.crt` ou `.pem`, un seul certificat par fichier, 64 Kio au plus). Le type du
fichier est reconnu automatiquement : un fichier de 64 Kio au plus est d'abord lu comme
certificat ; s'il n'en est pas un, il est traité comme une Master List.

Contrairement à une Master List, **aucune signature d'État ne garantit un certificat isolé** :
c'est l'utilisateur qui lui accorde sa confiance. Le dialogue de confirmation le dit, et montre le
sujet, le pays, la validité et l'empreinte SHA-256 du certificat, à comparer avec celle publiée
par l'État. `TrustStores.parseCertificate` (`:core`) contrôle :

| Code (`InvalidCertificateException.code`) | Cause |
|---|---|
| `UNREADABLE` | Pas exactement un certificat X.509 (DER sans octet superflu, ou PEM avec un seul bloc `CERTIFICATE` et aucun autre bloc), ou clé publique illisible. |
| `NOT_CA` | `basicConstraints` absent ou sans cA=TRUE, ou `keyUsage` présent sans keyCertSign. |
| `BAD_SIGNATURE` | Certificat qui se présente comme auto-signé (sujet égal à l'émetteur, Authority Key Identifier absent ou égal au Subject Key Identifier) dont l'auto-signature ne se vérifie pas. |

La signature d'un certificat de lien n'est pas vérifiable à l'import (elle est faite par
l'ancienne clé du CSCA) : elle l'est à la construction de la chaîne, comme pour tout certificat du
magasin, qui n'ancre une chaîne que sur un CSCA auto-signé. Importer un lien seul n'apporte donc
rien tant que le CSCA qui l'a signé n'est pas connu.

Stockage : `filesDir/trust/<SHA-256 du DER>.der`, en DER (un PEM est converti avant écriture),
à côté des `<SHA-256>.ml` des Master Lists ; écriture atomique, certificat revérifié juste avant
l'écriture. Au chargement, un certificat importé devenu invalide est ignoré. Les certificats
importés portent la source `IMPORTED_CERTIFICATE`, distincte de `IMPORTED_MASTER_LIST`, et
passent après toutes les autres sources : un certificat déjà présent (ANTS, publication
nationale, Master List embarquée ou importée) garde sa source d'origine. Ils s'affichent
« Certificat importé ».

Limites (`ImportLimits`, D22 et D33) : 100 certificats importés au plus, 64 Kio par fichier ; ils
ne comptent pas dans les limites des Master Lists (10 listes, 40 Mo cumulés).

### Doublons à l'import (D33)

Un fichier qui n'apporterait rien est refusé dès l'aperçu, sans dialogue de confirmation et sans
rien écrire (`DuplicateImportException`, logique dans `trust/ImportDuplicates`) :

| Fichier choisi | Détection | Message |
|---|---|---|
| Certificat déjà importé seul, en DER ou en PEM | SHA-256 du DER = nom d'un `<empreinte>.der` importé | « Ce certificat est déjà importé. » |
| Certificat déjà dans le magasin | SHA-256 du DER parmi `TrustStore.anchors` | « Ce certificat figure déjà dans le magasin de confiance (source : …) : il n'y a rien à importer. », avec la source ANTS, publication nationale, Master List embarquée ou Master List importée |
| Master List déjà importée | SHA-256 du fichier = nom d'un `<empreinte>.ml` importé | « Cette Master List est déjà importée. » |
| Master List identique à la Master List embarquée | SHA-256 du fichier = celui de la ressource `.ml` de `:core` | « Cette Master List est identique à celle embarquée dans l'application : il n'y a rien à importer. » |

Une Master List différente reste importable même si tous ses certificats sont déjà connus (elle
peut être plus récente) ; le dialogue de confirmation indique combien de ses certificats sont
absents du magasin actuel, par exemple « 12 nouveaux certificats sur 590 ».

### Éléments importés et suppression

L'écran « Magasin de confiance » liste chaque élément importé : Master List (signataire, date de
signature, nombre de certificats) ou certificat (sujet, pays, validité, empreinte). Chacun se
supprime à l'unité, après confirmation ; le bouton « Supprimer les certificats importés » efface
toujours tout, Master Lists et certificats. Un fichier importé devenu illisible reste listé pour
pouvoir être supprimé. L'identifiant d'un élément est son nom de fichier ; la suppression refuse
tout identifiant qui n'est pas `<64 chiffres hexadécimaux minuscules>.ml` ou `.der`.

## Procédure de mise à jour (à chaque release)

### Script

`scripts/update-trust-store.sh` automatise les vérifications de la procédure ci-dessous. C'est
un outil du développeur : l'application ne télécharge jamais rien. Il demande `bash`, `curl`,
`openssl` 3, `sha256sum`, `sha1sum`, `unzip` et les coreutils GNU. Les URL des sources sont
regroupées en tête du script.

```bash
# 1. Télécharger à la main les archives .zip des deux pages de l'ANTS dans un dossier
#    (le site bloque curl), par exemple ~/ants/.
# 2. Rapport, sans rien écrire (mode par défaut) :
scripts/update-trust-store.sh --ants-dir ~/ants
# 3. S'il signale des différences et que toutes les vérifications passent :
scripts/update-trust-store.sh --ants-dir ~/ants --apply
```

- **ANTS** : pour chaque certificat de chaque archive, empreinte recalculée et comparée à
  celle du fichier d'empreinte de l'archive (SHA-1 pour les passeports, SHA-256 pour l'e-ID),
  conversion PEM vers DER si besoin, affichage du sujet, de l'émetteur et des dates. Le sujet
  doit être `CN=CSCA-FRANCE` ou `CN=eID-FRANCE`, qui fixe le nom `ants-csca-<année>.der` ou
  `ants-csca-eid-<année>.der`. Sans `--ants-dir`, cette partie est sautée et le script le
  signale.
- **BSI** : téléchargement de la German Master List (ou `--bsi-zip` pour une archive locale),
  signature CMS vérifiée avec le signataire inclus, contenu de type `id-icao-cscaMasterList`,
  signataire valide à la date de signature et signé par un certificat dont la SHA-256 figure
  dans `EMBEDDED_MASTER_LIST_ANCHORS` (empreintes lues dans `TrustStoreLoader.kt` ; le script
  échoue si le format de cette constante change). Affiche la date de signature et le nombre
  de certificats.
- **Comparaison** : rien n'est écrit si les fichiers sont identiques aux fichiers embarqués.
  Avec `--apply` seulement, les fichiers nouveaux ou modifiés sont copiés dans
  `core/src/main/resources/trust/` et les nouveaux noms ajoutés à `trust/index.txt`. Aucun
  fichier n'est jamais retiré, en particulier aucun CSCA expiré.
- Le script affiche les lignes à reporter dans les tableaux « Fichiers embarqués » et
  « Provenance » ; ce document reste à mettre à jour à la main (étapes 6 et 7), puis
  `./gradlew :core:test` (étape 8) confirme la cohérence.
- Toute vérification ratée donne une sortie non nulle, et aucun fichier n'est écrit.

### Étapes

La procédure manuelle reste la référence de ce que fait le script.

1. Télécharger les archives CSCA et CSCA e-ID depuis les pages de l'ANTS citées plus haut.
2. Pour chaque archive, extraire le certificat et le fichier d'empreinte, puis vérifier que
   l'empreinte recalculée correspond à l'empreinte publiée :
   `sha1sum fichier.crt` (passeports, SHA-1 publiée) ou `sha256sum eID-FRANCE.crt` (e-ID).
3. Vérifier le format et le contenu :
   `openssl x509 -inform DER -in fichier.crt -noout -subject -issuer -dates`. Un fichier PEM
   est converti avec `openssl x509 -in fichier.pem -outform DER -out fichier.der`.
4. Copier le certificat dans `core/src/main/resources/trust/` sous le nom
   `ants-csca-<année>.der` (passeport) ou `ants-csca-eid-<année>.der` (e-ID), l'année étant
   celle du début de validité. Ne jamais retirer un CSCA expiré tant que des documents qu'il
   a signés sont en circulation.
5. Ajouter le nom du fichier à `trust/index.txt`.
6. Ajouter la ligne du fichier dans les deux tableaux ci-dessus : sujet, validité, empreinte
   publiée avec son algorithme, SHA-256 constatée (`sha256sum`), date de téléchargement.
7. Pour une Master List embarquée : retélécharger le fichier, vérifier la signature CMS et
   l'ancrage du signataire (le test de chargement échoue sinon), vérifier les empreintes
   publiées des certificats épinglés, puis mettre à jour les empreintes de ce document. Si le
   pays émetteur change de CSCA, mettre à jour `EMBEDDED_MASTER_LIST_ANCHORS` à partir des
   empreintes publiées par cette autorité, jamais à partir du fichier lui-même.
8. Publications nationales (section du même nom) : le script ne les couvre pas. Pour chaque
   source, suivre la procédure de `docs/trust-sources.md` §5 : retélécharger la page, relever
   les nouveaux certificats, contrôler pays, auto-signature ou lien signé par un CSCA déjà
   présent, validité et empreinte publiée. Nommer le fichier `<pays>-csca-<désignation>.der`,
   jamais avec le préfixe `ants-`. Ne l'ajouter que si la source garde un statut A, et mettre à
   jour la liste attendue de `TrustStoreTest.nationalSha256`. Surveiller les bascules
   annoncées (DK en septembre 2026, NO en janvier 2027).
9. Lancer `./gradlew :core:test` : `TrustStoreFingerprintTest` et `TrustStoreTest` doivent
   passer.
