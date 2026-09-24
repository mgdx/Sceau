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

Les cinq certificats sont des CSCA racines auto-signés (RSA 4096, `sha256WithRSAEncryption`).
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

Les archives ont été téléchargées par le mainteneur depuis les pages officielles de l'ANTS
(URL confirmées par le mainteneur le 2026-09-25) :

- page CSCA (passeports) : <https://ants.gouv.fr/csca> ;
- page CSCA e-ID (cartes d'identité) : <https://ants.gouv.fr/home/csca-e-id>.

Le site de l'ANTS filtre les robots : la mise à jour se fait à la main depuis ces pages, en
comparant les empreintes publiées dans chaque archive à celles recalculées.

## Master List

La SPEC (§7.1) prévoit une Master List embarquée. Le code la prend en charge : un fichier `.ml`
listé dans `trust/index.txt` est parsé au chargement. Sa signature CMS est vérifiée, et son
signataire doit être émis par un certificat dont l'empreinte est épinglée dans le code (voir
« Ancrage du signataire »). Sinon le chargement échoue (`InvalidMasterListException`). Ses
certificats portent la source `EMBEDDED_MASTER_LIST`. En cas de doublon, l'ordre de priorité
est ANTS, puis Master List embarquée, puis Master List importée.

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

## Procédure de mise à jour (à chaque release)

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
8. Lancer `./gradlew :core:test` : `TrustStoreFingerprintTest` et `TrustStoreTest` doivent
   passer.
