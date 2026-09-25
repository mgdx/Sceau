# Sources officielles des certificats CSCA

Ce document recense, pays par pays, les sources officielles des certificats CSCA (Country Signing
Certificate Authority, ICAO 9303 partie 12) : les Master Lists publiées par des États, et les
publications par chaque État de ses propres CSCA et certificats de lien. Pour chaque source, il
indique si Sceau peut l'embarquer dans un APK libre (GPLv3, F-Droid).

- **Date de consultation** : 2026-09-25, pour toutes les pages et tous les fichiers cités.
- **Magasin de référence** : magasin embarqué de Sceau au 2026-09-25, soit les 5 CSCA de l'ANTS et
  la German Master List du BSI signée le 2026-05-28. Cela fait 590 certificats distincts (SHA-256)
  pour 112 émetteurs. Il est appelé « magasin actuel » ci-dessous (voir `docs/trust-store.md`).
- **Méthode** : téléchargement uniquement depuis des domaines gouvernementaux ou institutionnels.
  Des agrégateurs tiers ont servi seulement à trouver des URL. Aucun captcha, aucun pare-feu
  applicatif et aucune condition d'utilisation n'ont été contournés.
  - Signatures CMS des Master Lists : `openssl cms -verify -noverify`, puis vérification que le
    signataire est émis par le CSCA du pays. Ce CSCA est lui-même comparé à l'empreinte publiée par
    l'État.
  - Certificats isolés : auto-signature ou lien vérifié par la clé de l'émetteur, attribut `C`,
    validité, empreintes comparées à celles publiées.

## Statuts de réutilisation

| Statut | Signification |
|---|---|
| **A1** | Licence ouverte explicite qui couvre le site ou le fichier (OGL v3, CC0, CC BY, OGDL). La redistribution à l'identique est permise, avec attribution si la licence l'exige. |
| **A2** | Publication officielle, par un État, de **ses propres** CSCA, présentée comme destinée à la vérification. Aucune restriction de réutilisation n'a été trouvée, ni sur la page ni dans les mentions légales du site. L'absence de licence explicite laisse une incertitude résiduelle. |
| **A\*** | Autorisation explicite assortie de conditions que Sceau respecte (cas du BSI). |
| **B** | Aucune licence et aucune autorisation explicite : mention générique « tous droits réservés », ou fichier qui regroupe les CSCA d'**autres** États (Master List) sans conditions propres. Il faut demander une autorisation écrite (voir « Contacts pour autorisation »). |
| **C** | Reproduction ou redistribution interdite, réservée à l'usage privé, ou soumise à un accord préalable. |

A1, A2 et A\* correspondent au statut « A » (utilisable) ; B à « probablement utilisable, à
confirmer » ; C à « non utilisable » sans accord écrit.

Un certificat CSCA ne contient qu'une clé publique et un nom d'autorité. Il est publié pour être
redistribué aux États et aux inspecteurs, et son caractère protégeable reste incertain. Ce document
ne tranche pas ce point : il cite les textes et qualifie l'incertitude. Pour une Master List, le
**fichier** est une compilation signée par un État tiers, qui peut avoir ses propres conditions.
C'est pourquoi une Master List sans aucune mention est classée B, et non A2.

## 1. Synthèse

- **Pays et organisations documentés** : 93, dont 45 avec une publication officielle identifiée.
  - 36 publications téléchargées et vérifiées : 34 pays, plus le laissez-passer de l'UE, et la France
    (ANTS) déjà embarquée.
  - 8 publications repérées mais inaccessibles : BG, LI, MC, MT (pare-feu applicatif), JP, AU, IL
    (Akamai ou Cloudflare), NG (liens morts).
  - La page du Portugal ne propose aucun fichier.
- **Master Lists publiques signées par un État** : 7 téléchargées et vérifiées, dans l'ordre DE, IT,
  SE, NL, CH, EE et ES. Une est annoncée mais inaccessible (NG). La Master List de l'ICAO est exclue
  (statut C).
- **Statuts des sources** (hors ICAO) :
  - A1 : GB, BE (passeport), PL, TW, EU ;
  - A2 : EE, RO, GE, NO, DK, IS, VA, CY, LU, FI, BE (eID), GR (titres de séjour), NL (certificats
    nationaux), IT (certificats nationaux) ;
  - A\* : DE (Master List et certificats) ;
  - B : Master Lists IT, SE, NL et EE ; certificats nationaux de MY, KW, LV, LT, CZ, SK, HU, SI, RS,
    BA, MK, AT et SE ;
  - C : Master Lists CH, ES et ICAO ; certificats nationaux de CH, AD, ES et GR (passeport).
- **Gain possible par rapport au magasin actuel** : certificats absents du magasin et encore valides
  au 2026-09-25. Les chiffres sont produits par `tools/inventory.py` (détail en section 4).

| Sources retenues | Nouveaux certificats | dont valides | dont valides UE/EEE/CH/GB | Nouveaux émetteurs |
|---|---|---|---|---|
| A1 seulement | 10 | 2 | 2 | 0 |
| A (A1 + A2 + A\*) | 40 | 7 | 5 | 0 |
| A + B | 176 | 111 | 34 | 24 |
| Toutes (A + B + C) | 254 | 127 | 42 | 26 |

## 2. Master Lists

### 2.1 Tableau récapitulatif

| ML | Page officielle | Fichier | Signée le | Certificats / émetteurs | Nouveaux valides | Signataire → CSCA du pays (empreinte publiée par l'État) | Empreinte publiée du fichier | Statut |
|---|---|---|---|---|---|---|---|---|
| **DE** (BSI) | <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.html> | <https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.zip?__blob=publicationFile&v=108> (ZIP → `.ml` CMS) | 2026-05-28 | 588 / 112 | 0 (déjà embarquée) | `CN=CSCA Master List Signer`, serialNumber=0039 → csca-germany, SHA-256 `2084aed7…ada8` (publiée, concordante) | aucune | A\* |
| **IT** (ministère de l'Intérieur) | <https://csca-ita.interno.gov.it/html/csca.html> | <https://csca-ita.interno.gov.it/certificatiCSCA/IT_MasterListCSCA.zip> (ZIP → `.ml`) | 2026-09-07 | 670 / 136 | **96** | `CN=MasterListSigner`, serialNumber=004 → CSCA05 « Italian Country Signer CA », SHA-1 `e8e99576…d263` (publiée, concordante) | aucune | B |
| **SE** (Polismyndigheten) | <http://cert.polisen.se/CSCA/> | <http://cert.polisen.se/CSCA/SWE.ml> (**HTTP seulement**) | 2026-06-02 | 646 / 134 | 84 | `CN=Swedish Master List Signer` → « Swedish Country Signing CA v2 » (2024), SHA-1 `f7fd7b82…7fdb` (publiée, concordante) | SHA-1 `a2057b07…da55`, concordante | B |
| **NL** (RvIG, NPKD) | <https://www.npkd.nl/masterlist.html> | <https://www.npkd.nl/files/ml/NL_MASTERLIST.mls> | 2026-07-23 | 411 / 129 (auto-signés seulement) | 39 | `CN=Masterlist Signer NL`, serialNumber=8 → CSCA NL serialNumber=7, SHA-256 `411356d2…1a24` (publiée, concordante) | SHA-1, SHA-256 et SHA-512 concordantes | B |
| **CH** (fedpol, OFIT) | <https://www.bit.admin.ch/de/sg-pki-csca-production-overview-de> | <https://www.pki.admin.ch/MasterList/Current-Swiss-MasterList.ml> | 2026-07-16 | 567 / 100 | 45 | `CN=ML, OU=MLS` → CSCA-CHE 2023, SHA-256 `a77cdb0e…8e9e` (publiée, concordante) | aucune | C |
| **EE** (PPA) | <https://pki.politsei.ee/> | <https://pki.politsei.ee/CSCA_masterlist.ml> | 2026-06-08 | 374 / 111 | 2 | `CN=CSCA_MasterListSigner` → CSCA_Estonia 2023, SHA-256 `f22e447e…fafc` (publiée, concordante) | aucune | B |
| **ES** (Dirección General de la Policía) | <https://www.dnie.es/PortalDNIe/PRF1_Cons02.action?pag=REF_1093> | <http://pki.policia.es/csca/MasterList/SpanishMasterList.ml> (**HTTP**) | **2022-01-25** | 277 / 86, dont 86 expirés | 10 | `CN=NPKD, OU=PASSPORT` → CSCA SPAIN 3, SHA-1 `6aeb147e…f6c7` (publiée, concordante) | aucune | C |
| ICAO | <https://www.icao.int/icao-pkd/icao-master-list> | via <https://download.pkd.icao.int/> (captcha) | 2026-07-15 | 579 (annoncé) | non téléchargée | — | — | C |
| NG (NIS) | <https://immigration.gov.ng/pki-pkd/> | `files/MasterList.ml` annoncé, 404 | — | — | — | — | — | B/C (inaccessible) |

Toutes les signatures CMS téléchargées se vérifient. Tous les signataires ont été vérifiés
cryptographiquement comme émis par le CSCA du pays éditeur.

Recherchées sans succès (aucune Master List publique trouvée) : FR, BE, AT, NO, FI, DK, LU, LV, LT,
PL, CZ, HU, PT, IE, GB, CA, AU, NZ, JP (site en 403), SG, US et KR. Les résultats renvoient au PKD de
l'ICAO.

### 2.2 Conditions de réutilisation des Master Lists (textes cités)

**DE, BSI (A\*)**
- Page de la ML : « *Eine kommerzielle Nutzung der deutschen CSCA Masterliste darf erfolgen, sofern
  folgende Vorgaben beachtet werden: Mit der deutschen CSCA Masterliste darf nicht geworben werden.
  Die Nutzung der deutschen CSCA Masterliste darf nicht den Anschein einer Kooperation mit dem BSI
  erwecken.* » La page publie aussi la version anglaise : « Commercial use may be permitted provided
  that […] must not be used for advertising […] must not give the appearance of cooperation with the
  BSI. »
- Conditions générales du site, <https://www.bsi.bund.de/EN/Service/Nutzungsbedingungen/nutzungsbedingungen_node.html> :
  - « *Any further use, in particular commercial or journalistic use, requires the prior consent of
    the BSI […] The other BSI contents made available for download may only be used unchanged within
    the scope of the permitted use.* »
  - « *VII. Mirroring the website — Any mirroring of BSI web content requires the prior written
    consent of the BSI.* »
- Lecture : la page propre à la ML autorise l'usage sous deux conditions. Les conditions générales
  exigent un usage inchangé, et un accord écrit pour la « Spiegelung » (mirroring). Qu'un APK soit
  ou non un « mirroring » n'est pas établi.

**IT, ministère de l'Intérieur (B)**
- Le site csca-ita.interno.gov.it ne comporte aucune mention légale ni licence.
- Les note legali du ministère, <https://www.interno.gov.it/it/note-legali>, prévoient la CC BY 4.0
  pour www.interno.gov.it : « *sono disponibili con licenza d'uso Creative Commons CC BY 4.0 […]
  liberamente scaricabili, distribuibili e riutilizzabili a condizione che sia citata la fonte* ».
  Elles renvoient les sites thématiques à leurs propres conditions. csca-ita ne figure pas dans la
  liste <https://www.interno.gov.it/it/siti-tematici>.
- Le Codice dell'amministrazione digitale, art. 52 c. 2, ouvre par défaut les données publiées :
  « *I dati e i documenti che i soggetti […] pubblicano, con qualsiasi modalità, senza l'espressa
  adozione di una licenza […] si intendono rilasciati come dati di tipo aperto* »
  (<https://docs.italia.it/italia/piano-triennale-ict/codice-amministrazione-digitale-docs/it/stabile/_rst/capo_V-sezione_I-articolo_52.html>).
  C'est une base favorable, mais que l'autorité doit confirmer.

**SE, Polismyndigheten (B)**
- Politique de la ML,
  <https://polisen.se/contentassets/359ae9679d4949cc869baaf6733fb29c/se-ml-policy.pdf> : la liste est
  « *signed by the Swedish eMRTD Master List Signer (MLS) and made publicly available* ». Elle demande
  de présenter clairement cet extrait lors de toute diffusion : « *a receiving State making use of
  Master Lists MUST still determine its own policies for establishing trust in the certificates
  contained on that list* ». Aucune licence.
- Site, <https://polisen.se/om-polisen/om-webbplatsen/bild-och-text-upphovsratt/> : « *I regel går
  det bra att använda delar av informationsmaterialet […] Innan eventuellt textmaterial ska användas
  vill vi ändå att du kontaktar Polismyndigheten* ». Autrement dit, l'usage est en général possible,
  mais il faut contacter la police avant d'utiliser des textes. Rien ne vise les certificats.

**NL, RvIG (B)**
- npkd.nl ne comporte aucune mention.
- La CC0 de l'opérateur ne vise que rvig.nl (<https://www.rvig.nl/copyright>) : « *Tenzij anders
  vermeld, is op de inhoud van deze website de Creative Commons zero verklaring (CC0) van
  toepassing.* »

**EE, PPA (B)** : aucune mention sur pki.politsei.ee, et aucune page de conditions trouvée sur
politsei.ee.

**CH, fedpol (C)** : <https://www.admin.ch/gov/en/start/terms-and-conditions.html> indique
« *Downloading or copying of texts, illustrations, photos or any other data does not entail any
transfer of rights on the content […] Any reproduction requires the prior written consent of the
copyright holder.* »

**ES, Policía (C)** : l'aviso legal,
<https://www.dnie.es/PortalDNIe/PRF1_Cons02.action?pag=REF_750&id_menu=0>, prévoit que « *Su uso,
reproducción, distribución, comunicación pública […] queda totalmente prohibida salvo que medie
expresa autorización de la Dirección General de la Policía. La licencia de uso […] se limita a la
descarga por parte del usuario de dicho contenido y el uso privado del mismo* ».

**ICAO (C)** : « *You may not copy, reproduce, republish, download, post, broadcast, transmit, make
available to the public, or otherwise use ICAO PKD content in any way except for your own
personal/organizational non-commercial use* ». Le téléchargement passe par un captcha.

### 2.3 Particularités techniques

- **Ancrage** : chaque ML contient le CSCA qui a signé son propre signataire. Il faut donc épingler,
  hors du fichier, l'empreinte du CSCA du pays éditeur, prise sur sa page officielle :
  - DE : `2084aed7a991b3158e63ad750d3dc38bc6c9dcf5958f28d1162f49884e91ada8` ;
  - IT (CSCA05) : `615bd40cd7bc63aeb572cf168ccb78162003b31cd98130dd71f7d2d69c83fa3a`. L'État ne publie
    que la SHA-1 `e8e99576f6e770250b47c663d86e0a0285c9d263`.
  - SE (v2 2024) : `353bd3863bb526a3f8a0a36eade08365eccba03ff13a5f7ac8fac539a7db17ff` ; SHA-1 publiée
    `f7fd7b82d07b369f9732a636d727152ceb427fdb` ;
  - NL : `411356d206b99da15efadf4b84149f8600ee6260c6727cd6bc230d6b6e151a24` ;
  - EE : `f22e447ebb4b363d0fc863447dac867b2d4f4f896492292fb6e6720cdbfffafc` ;
  - CH (2023) : `a77cdb0e304a0733777f521eb4f4ae36db7e8732626da7307933bc32ba0f8e9e`.
- **Algorithmes** : IT et EE signent en RSASSA-PSS avec SHA-512, DE et SE en ECDSA-SHA256, NL en
  RSA-SHA256.
- **Contenu** : NL et EE ne contiennent que des certificats auto-signés, sans liens. IT est la seule
  ML qui recouvre presque tout le magasin actuel : il lui manque 16 certificats de la ML BSI, contre
  28 pour SE, 71 pour CH et 218 pour NL et EE.
- **Transport** : SE et ES ne sont servies qu'en HTTP. EE présente une chaîne TLS incomplète (il
  manque l'intermédiaire Sectigo). Dans les trois cas, l'intégrité repose sur la signature CMS et sur
  l'ancrage épinglé.

### 2.4 Deviation Lists publiques

| Émetteur | Page | Fichier | Contenu |
|---|---|---|---|
| IT | <https://csca-ita.interno.gov.it/> (rubrique TDDL) | <https://csca-ita.interno.gov.it/certificatiCSCA/IT_CIE_DeviationList.zip> → `IT/IT_CIE_DeviationList/TDDL-CIE-Signed-20180530.der` | CMS `id-icao-DeviationList` (2.23.136.1.1.7) signé le 2018-05-30. Le signataire `ITDeviationListSigner` est émis par CSCA03 (SHA-1 publiée `33dfc79d…`, concordante). **346 275 cartes d'identité italiennes (CIE 3.0) portent un DG12 erroné** : leur empreinte DG12 ne correspondra pas au SOD. Notes : <https://csca-ita.interno.gov.it/certificatiCSCA/CIE3.0-NotaAnomaliaDG12ITA.pdf>. |
| EU | <https://eu-csca.jrc.ec.europa.eu/> | texte de la page | Laissez-passer délivrés à des agents allemands avant le 2022-05-05 : champ nationalité « DEU » dans la MRZ. |
| EE | <https://pki.politsei.ee/> | — | « No DLs available ». |

## 3. Publications nationales, pays par pays

Chaque fiche donne : l'autorité, la page officielle, les fichiers, les empreintes publiées et leur
concordance, les certificats absents du magasin actuel (« nouveaux »), les conditions de
réutilisation, le statut et le contact. « Actuel » désigne le CSCA auto-signé le plus récent. Les
fichiers sont rangés dans `trust-catalog/<code pays>/`.

### 3.1 Union européenne, EEE, Suisse, Royaume-Uni

**AT — Autriche, BMI**
- Page : <https://www.bmi.gv.at/downloads/csca.html>
- Fichiers : `https://www.bmi.gv.at/csca/crt/cscaaustriacacert00{3,4,5}.cer`, les liens
  `cscaaustriacacertlink00{3,4,5}.cer`, `c_csca_001.cer`, et
  `https://www.bmi.gv.at/csca/der/cscaaustria.cacert.gen2(.link).cer` ; DER ou PEM.
- Empreintes : SHA-1 publiées, 9 sur 9 concordantes.
- Certificats : actuel CSCA-AUSTRIA 005 (2024-08-19 → 2039-11-23),
  `1763ae21ba75dc3e830f4c5cde7cfcdfae560981762fb0acd67936a10910aff3`. Un seul nouveau, expiré.
- Conditions, <https://www.bmi.gv.at/impressum/start.html> : « *Die Übernahme von Beiträgen ist –
  unter Quellenangabe – gestattet (außer für kommerzielle Zwecke).* » La reprise est permise avec la
  source, sauf à des fins commerciales. La clause vise des « Beiträge » (contributions) ;
  s'applique-t-elle aux certificats ? Incertain.
- Statut : **B**. La clause non commerciale est incompatible avec la liberté d'usage de la GPL si
  elle s'applique. Sans enjeu pratique : tous les CSCA autrichiens valides sont déjà dans le magasin.
- Contact : csca@bmi.gv.at

**BE — Belgique, passeport (SPF Affaires étrangères)**
- Page : <https://diplomatie.belgium.be/en/belgian-passport-country-signing-certificate-authority>,
  avec le PDF d'empreintes <https://diplomatie.belgium.be/sites/default/files/2026-08/CSCA-FA-BE-2024.pdf>.
- Fichiers (HTTP) : `http://www.diplomatie.be/csca/CSCA_FA_BE_2024.crt` et `CSCA_FA_BE_2024_link.crt`,
  `CSCA.2021.crt`, `CSCA.2021.link.crt`, `CSCA_FA_BE.crt`, `CSCAEC_BE_3.crt`, `CSCAPKI_BE_02.crt`.
- Empreintes : les SHA-1 concordent toutes. Deux SHA-256 du PDF ne correspondent pas au DER : celle
  de 2024 est la SHA-256 du fichier PEM, celle du lien 2024 ne correspond à aucun fichier. C'est une
  anomalie de la page.
- Certificats : actuel CSCA_FA_BE 2024 (2024-11-29 → 2035-11-27),
  `ac4a33500784302cfba2d4203cae38748a56dcab63dbefdad275a55a320a7877`, déjà dans le magasin. Un seul
  nouveau, expiré.
- Conditions, <https://diplomatie.belgium.be/en/legal-notice> : « *Conditions for re-use (Creative
  Commons-0 licence) Unless otherwise stated, the information on this website is free of rights and
  may be used for private, association, scientific and commercial purposes.* » Réserve : les fichiers
  sont servis par www.diplomatie.be, un autre nom d'hôte.
- Statut : **A1**. Contact : csca-pass@diplobel.fed.be

**BE — Belgique, carte eID et titres de séjour**
- Page : <http://certs.eid.belgium.be/>
- Fichiers : `csca-0{1..4}.crt` et `csca-0{2,3,4}-link.crt`.
- Aucune empreinte publiée.
- Certificats : Belgium Country Signing CA 02, 03 et 04 et leurs liens, déjà dans le magasin. CA 01
  (expiré en mars 2026) et un lien expiré sont nouveaux.
- Conditions : aucune mention sur le site. Statut : **A2**. Contact : non trouvé.

**BG — Bulgarie, MVR**
- Page :
  <https://www.mvr.bg/en/ministry/about-ministry-of-internals/usefull-for-you/certificates-for-verification-of-e-documents>
- Défi Cloudflare, rien n'a été téléchargé. D'après l'extrait du moteur de recherche, la page publie
  un CSCA de 02/2024 et un lien 2019-2024.
- Statut : non déterminé (**B** par défaut). À consulter à la main. Le magasin actuel contient 8
  certificats bulgares valides, dont un de 2026.

**CY — Chypre, CRMD**
- Pages : <https://csca.crmd.moi.gov.cy/PKI/CSCA/Root_Certificate> et `/Previous_Certificates`.
- Fichiers : `https://csca.crmd.moi.gov.cy/csca/csca-cy_{03_2024,07_2020,11_2018,11_2014}_{self_signed,link}.zip`.
- Empreintes : SHA-256 publiées, 8 sur 8 concordantes.
- Certificats : tous déjà dans le magasin. Actuel : CSCA-CYPRUS 03/2024.
- Remarque : le certificat TLS du site a expiré le 2026-02-09. L'intégrité est assurée par les
  SHA-256 publiées.
- Conditions : aucune mention. Statut : **A2**. Contact : cyp-csca@crmd.moi.gov.cy

**CZ — Tchéquie, MV ČR**
- Page : <https://mv.gov.cz/clanek/certifikaty-csca-cvca.aspx>, avec les sous-pages 2026, 2021, 2016,
  2011 et eID. Le site est affiché comme « archivní verze webu ».
- Fichiers : `https://mv.gov.cz/soubor/<nom>-cer.aspx`, pour cze-csca-{20260319, 20210323, 20160324,
  20110325}, les liens correspondants, cze-eid-csca-20180618, cze-eid-csca-20230523 et
  cze-eid-csca-link-20230523.
- Empreintes : SHA-1 publiées, 10 sur 11 concordantes. Pour le lien eID 2023, la page recopie par
  erreur l'empreinte de la racine 2018. Le lien a été vérifié cryptographiquement.
- Certificats : actuel CSCA_CZ 2026 (2026-03-19 → 2041-06-19), déjà dans le magasin. **Nouveaux et
  valides : les 3 eID_CSCA des cartes d'identité** :
  - `996fdda02cfb4b585fe710c7e08eae377cfd7c0e13b60bb23858893c3e6f5a0d` (2018 → 2058) ;
  - `b52e3ad60e35703e5824861c9c3ea4dea5a270246c7b8050086df74c7945e9dd` (2023 → 2063) ;
  - le lien `f4729d706be1528a61a7963a0205bfa267fbdf4e684adbaef7bd43fe37c49cbd`.

  Ils ne figurent dans **aucune** Master List. Sans eux, les cartes d'identité tchèques donneraient
  probablement « Émetteur inconnu » (non vérifié sur une carte réelle).
- Conditions : « *© 2026 Ministerstvo vnitra České republiky, všechna práva vyhrazena* » (tous droits
  réservés).
- Statut : **B**. Contact : webmaster@mv.gov.cz (seule adresse de la page).

**DE — Allemagne, BSI**
- Page :
  <https://www.bsi.bund.de/EN/Themen/Oeffentliche-Verwaltung/Elektronische-Identitaeten/Public-Key-Infrastrukturen/CSCA/Root_Cert_Germany/Zertifikate_der_CSCA-PKI.html>
- Fichiers : `csca-germany_10-2024_self_signed.zip` et `csca-germany_10-2024_link.zip`.
- Empreintes : SHA-256 publiées, concordantes (`2084aed7…ada8` et `5f28cb69…20bd`). Les deux
  certificats sont déjà dans le magasin.
- Conditions : celles du BSI (section 2.2). Statut : **A\***.
- Contact : csca-germany@bsi.bund.de, poststelle@bsi.bund.de

**DK — Danemark, Rigspolitiet**
- Page : <https://politi.dk/en/law-and-information/the-danish-country-signing-ca-csca>. Les
  certificats sont publiés en PEM dans le HTML.
- Empreintes : SHA-256 publiées, 9 sur 9 concordantes.
- Certificats : actuel CSCA 2023, `84fbb2a1…8892`, déjà dans le magasin. Deux nouveaux, expirés
  (2006 et 2009).
- Une bascule est annoncée pour septembre 2026 ; rien n'est publié au 2026-09-25.
- Conditions : aucune mention trouvée. Statut : **A2**. Contact : POL-forretningsejerskaber@politi.dk
  (adresse relevée dans le certificat).

**EE — Estonie, PPA**
- Page : <https://pki.politsei.ee/>
- Fichiers : `https://pki.politsei.ee/csca_Estonia_<année>.{cer,crt}` et les liens
  `csca_Estonia_<a>-<b>-link.*`, 15 fichiers, plus la Master List (section 2).
- Empreintes : SHA-256 publiées, 15 sur 15 concordantes. Pour trois fichiers, l'empreinte publiée est
  celle du PEM brut.
- Certificats : actuel CSCA_Estonia 2023, `f22e447e…fafc`, déjà dans le magasin. Les 8 nouveaux sont
  expirés.
- Conditions : aucune. Statut : **A2** pour les certificats nationaux, **B** pour la ML.
- Contact : pki@politsei.ee

**ES — Espagne, Dirección General de la Policía**
- Page : <https://www.dnielectronico.es/PortalDNIe/PRF1_Cons02.action?pag=REF_1093&id_menu=55>
- Fichiers : `https://www.dnielectronico.es/descargas/mrtd/CSCA_4_RAIZ.zip`, `CSCA_3_RAIZ.zip` et
  `CSCA_3_LINK.zip`. Les liens CSCA 2 renvoient 404.
- Empreintes : SHA-1 publiées, concordantes.
- Certificats : actuel CSCA SPAIN 4 (2022-05-18 → 2037-08-18),
  `1c2c6ff0…7209`, déjà dans le magasin.
- Conditions : aviso legal, usage privé seulement (section 2.2). Statut : **C**. Contact : aucune
  adresse CSCA publiée.

**FI — Finlande, Poliisihallitus**
- Page : <https://poliisi.fi/en/csca>
- Fichiers (HTTP) : `http://proxy.fineid.fi/ca/cscafin{3..6}.crt` et `cslifin{3..6}.crt`.
- Empreintes : SHA-256 (fin3 à fin6) et SHA-1 publiées, concordantes.
- Certificats : actuel cscafin6 (2025-05-14 → 2035-08-12), `b321524d…ab52`, déjà dans le magasin.
  Trois nouveaux, expirés.
- Conditions, <https://poliisi.fi/en/information-about-the-website> : « *The use of the text materials
  found on this site, and providing links to it, is allowed in the context corresponding to generally
  accepted practice. […] The use of the material or images is prohibited for commercial, political
  […] or religious purposes in an inappropriate or immoral manner.* » Le texte vise le site
  poliisi.fi, pas explicitement les fichiers de proxy.fineid.fi.
- Statut : **A2**. Contact : CSCA.Finland@govsec.fi

**FR — France, ANTS**
- Pages : <https://ants.gouv.fr/csca> (passeport) et <https://ants.gouv.fr/home/csca-e-id> (CNIe).
- Fichiers : archives ZIP qui contiennent un `.crt` DER et un fichier d'empreinte.
- Empreintes : SHA-1 publiées (passeport), SHA-256 publiée (e-ID), concordantes. Les 5 certificats
  sont déjà embarqués (voir `docs/trust-store.md`).
- Aucune Master List ANTS n'a été trouvée. Le site filtre les robots ; la mise à jour se fait à la
  main.
- eID-FRANCE (`b33ea63b…fe7c`) ne figure dans aucune Master List.
- Statut : source déjà retenue par la décision D1 (A2).

**GB — Royaume-Uni, HM Passport Office (UKKPA)**
- Page : <https://hmpo.gov.uk/csca>
- Fichiers : `https://hmpo.gov.uk/csca/certificate/<uuid>`, 14 certificats DER (GBR et Bermudes) ;
  CRL `https://hmpo.gov.uk/csca/GBR.crl`.
- Empreintes : aucune publiée.
- Certificats : **nouveaux et valides** :
  - GBR_2026_Root (2026-08-24 → 2042-12-24),
    `737ce248b62d8b6dc1c9e3aaa8b937334406bac17bb37235c1c50281e6ad7edb` ;
  - le lien GBR_2021-2026 (2026-08-24 → 2038-01-17),
    `81d9f6bf3026a04e6e3847808cf094e34f9473a92ff66eb86a975f4c42055b5d`, dont la signature par
    GBR_2021 a été vérifiée.

  Ils ne sont dans aucune Master List. **La bascule du CSCA britannique est annoncée pour le
  26 septembre 2026.** Deux autres certificats nouveaux, de 2005, sont expirés.
- Conditions (pied de page) : « *All content is available under the Open Government Licence v3.0,
  except where otherwise stated © Crown copyright* ».
- Statut : **A1**. Attribution à prévoir : « Contains public sector information licensed under the
  Open Government Licence v3.0 ».
- Contact : document.technology@homeoffice.gov.uk
- Remarque : sans empreinte publiée, l'intégrité repose sur TLS et sur le lien signé par le CSCA 2021,
  déjà dans le magasin.

**GR — Grèce, passeport et CNI (Police hellénique)**
- Pages : <https://www.passport.gov.gr/en/e-passport/csca-epassport.html> et `…/csca-epassport-past.html`.
- Fichiers : ZIP servis par `dl.php` (clés principales et de secours, courantes et anciennes).
- Empreintes : SHA-1 publiées, concordantes.
- Certificats : actuel CSCA-HELLAS SN=11 (2026), `038dd011…b190`, déjà dans le magasin. 16 nouveaux,
  dont 8 valides : ce sont les **clés de secours** (backup) des générations 2021, 2022 et 2026, qui ne
  signent de DS qu'après un sinistre. Trois d'entre eux sont aussi dans les ML IT et SE.
- Conditions, <https://www.passport.gov.gr/en/oroi-xrisis.html> : « *Όλες οι πληροφορίες […]
  διατίθενται στους χρήστες μόνον για πληροφοριακούς και σε καμία περίπτωση για εμπορικούς ή άλλους
  σκοπούς. Τροποποίηση του υλικού του διαδικτυακού τόπου ή χρήση για άλλους σκοπούς διώκεται
  ποινικά.* » Les informations sont fournies à titre informatif seulement, pour aucun usage commercial
  ou autre.
- Statut : **C**. La formulation est large, pour des certificats pourtant publiés pour la
  vérification. Contact : csca@passport.gov.gr

**GR — Grèce, titres de séjour (ministère des Migrations)**
- Page : <http://spoc.immigration.gov.gr/csca/> et `/previous/`.
- Fichiers : `CSCAeRP-HELLAS003.cer` et `CSCAeRP-HELLAS003_link.cer`, ainsi que 002, 001 et HELLAS1.
- Empreintes : SHA-1 publiées, concordantes.
- Certificats : **nouveaux et valides** : CSCAeRP-HELLAS 003 (2026-05-27 → 2041-05-26),
  `8f01ced4c95d9ee5cc6915e1151432e18fa1615ad6d8e61bb6ea1af025368e3e`, et son lien
  `5bbdb09b12bc25f9373142409d9b52da806b764294baf4f028a2c13da15bdfc5`, en service depuis le
  2026-06-19. Dans les ML, on ne les trouve que dans la ML CH.
- Conditions : aucune mention. Statut : **A2**. Contact : csca@migration.gov.gr

**HR — Croatie** : aucune page officielle trouvée (recherches sur mup.gov.hr et akd.hr). Le magasin
actuel contient 7 certificats croates. Statut : sans objet.

**HU — Hongrie**
- Pages : <https://www.nyilvantarto.hu/hu/csca_tanusitvany_listak_hu/> (téléchargement) et
  <https://kormany.hu/nyilvantartasok/biometrikus-utlevel/tajekoztato-a-csca-tanusitvanyrol>
  (empreintes).
- Fichiers : `https://www.nyilvantarto.hu/letoltes/CSCA/<nom>`, 33 certificats : CSCA passeport,
  ID-CSCA (eID), BAHCA, BMHCA et OIFCA (titres de séjour).
- Empreintes : SHA-1 publiées, concordantes, sauf la fiche « No. 4 » qui décrit un autre certificat.
- Certificats : actuel CSCA HUNGARY 2025 (OU=MK), `1d250ea2…2e31`, déjà dans le magasin. Nouveaux
  valides : BMHCA-HUNGARY 1 (`c7a6d46b…7557`) et deux liens (`9d1fe758…`, `086e0d70…`).
- **Les deux CSCA hongrois de 2024 ne sont pas publiés par la Hongrie** :
  - `bb528b03f4eaa9d6db983b44882dfae4cc1d9ace91a8137cdeb410e8652f6317`, OU=Ministry of Interior ;
  - `93381cc2d3d52dc5c57f6d361c050467124a53566a628d98f879c3e782f7cd38`, OU=Cabinet Office.

  On ne les trouve que dans les ML IT, SE, NL, EE et CH.
- Conditions, <https://kormany.hu/impresszum-kiado> : « *Minden jog fenntartva ©* » (tous droits
  réservés).
- Statut : **B**. Contact : csca-hungary@em.gov.hu

**IE — Irlande** : aucune page trouvée (dfa.ie, ireland.ie). Statut : sans objet. Contact : formulaire
<https://www.ireland.ie/en/dfa/passports/contact-us/>.

**IS — Islande, Þjóðskrá**
- Page :
  <https://www.skra.is/english/people/passport-and-id-card/passport/various-information-on-passports/security-of-icelandic-passports/>
- Fichier : `https://www.skra.is/library/Samnyttar-skrar-/Vegabref/Iceland%20CSCA%20G1-G3%20all%20certificates%202023.zip`,
  7 certificats.
- Empreintes : aucune publiée.
- Certificats : les certificats valides sont déjà dans le magasin ; deux nouveaux, expirés.
- Conditions : aucune mention. Statut : **A2**. Contact : csca@skra.is

**IT — Italie, ministère de l'Intérieur**
- Page : <https://csca-ita.interno.gov.it/html/csca.html>
- Fichiers : `https://csca-ita.interno.gov.it/certificatiCSCA/CSCA05.cer` (et les CSCA précédents).
- Empreintes : SHA-1 publiée `e8e99576…d263`, concordante.
- Certificats : actuel CSCA05, `615bd40c…fa3a`, déjà dans le magasin.
- Conditions : aucune (section 2.2). Statut : **A2** pour les certificats nationaux, **B** pour la ML.
- Contacts : epass.certauthority@interno.it (passeport), epermits.certauthority@interno.it (titres de
  séjour).

**LI — Liechtenstein, Ausländer- und Passamt**
- Page :
  <https://www.llv.li/de/landesverwaltung/auslaender-und-passamt/reisepass/datenschutz-und-sicherheitsmassnahmen>
- Pare-feu applicatif (403), rien n'a été téléchargé.
- Le CSCA-LIECHTENSTEIN 2024 (`3458c10e…`) manque au magasin. On ne le trouve que dans la ML CH.
- Statut : non déterminé. Contact : info@apa.llv.li (non vérifié).

**LT — Lituanie, ADIC**
- Pages : <https://www.csca.lt/> et <https://www.csca.lt/PreviousCSCA/>
- Fichiers : `https://www.csca.lt/certif/CSCA_Lithuania_00{1..7}.cer` et les liens
  `_00x_link_00y.cer`.
- Empreintes : SHA-1 publiées, 12 sur 12 concordantes. Pour 007, la valeur annoncée comme SHA-256 est
  en fait la SHA-1.
- Certificats : actuel CSCA 007 (2025-01-31 → 2038-07-27), `06c6b8ac…a7f`, déjà dans le magasin.
  Nouveaux valides : trois liens (`65747b23…`, `9a89d2e8…`, `38b3a638…`). Un quatrième lien valide (003, `1978e151…`) ne figure que dans la ML IT.
- Conditions : « *© 2023 Identity Documents Personalisation Centre under the Ministry of the Interior.
  All rights reserved.* »
- Statut : **B**. Contact : adresse e-mail non affichée ; courrier : Žirmūnų g. 1D, LT-09239 Vilnius.

**LU — Luxembourg, INCERT**
- Page : <https://repository.incert.lu/>
- Fichiers : `CSCA_LU_2023.crt`, `CSCA2023_link_from_CSCA2019.crt`, `CSCA_eID_card_2018.crt`,
  `CSCA_eTravelDocuments.crt`, `CSCAeTravel_link_from_CSCAePass.crt`, `CSCA_eResidencePermit_card.crt`,
  etc. (13 fichiers).
- Empreintes : aucune publiée.
- Certificats : actuel « Grand Duchy of Luxembourg CSCA » 2023, `2985657f…9fa2`, déjà dans le
  magasin. Nouveau et valide : le lien eTravel `5acb0c02…f95c`.
- Conditions : aucune. Statut : **A2**. Contact : csca@incert.lu (adresse relevée dans le certificat).

**LV — Lettonie, PMLP**
- Page : <https://www.pmlp.gov.lv/en/latvian-csca>
- Fichiers : `https://www.pmlp.gov.lv/en/media/<id>/download?attachment`, 14 ZIP.
- Empreintes : SHA-256 (010) et SHA-1 publiées, concordantes.
- Certificats : actuel CSCA Latvia 010 (2026-05-06 → 2039-08-06), `d68079b1…5a90`, déjà dans le
  magasin.
- Conditions : « *© 2026 Pilsonības un migrācijas lietu pārvalde, all rights of published content
  reserved.* »
- Statut : **B**. Contact : npkd@pmlp.gov.lv

**MC — Monaco**
- Page :
  <https://monservicepublic.gouv.mc/en/themes/nationality-and-residency/monegasque-nationality/identity-documents/country-signer-certificate-authority-csca>
- Liste MC1 à MC8 visible ; les fichiers sont bloqués par un pare-feu applicatif (403).
- Statut : non déterminé. Contact : Service des passeports, +377 98 98 82 18.

**MT — Malte, Identità / MECS**
- Page : <https://repository.csca.gov.mt/> ; conditions : `/pages/terms-and-conditions`.
- Cloudflare (403), rien n'a été lu. Cinq certificats maltais valides manquent au magasin ; ils sont
  dans les ML IT, SE et CH.
- Statut : non déterminé.

**NL — Pays-Bas, RvIG**
- Page : <https://www.npkd.nl/>
- Fichiers : CSCA NL serialNumber=7, auto-signé et lien.
- Empreintes : SHA-256 publiées, concordantes (`411356d2…1a24`, `987268ed…05b1`).
- Certificats : déjà dans le magasin.
- Conditions : aucune. Statut : **A2** pour les certificats nationaux, **B** pour la ML.
- Contact : info@rvig.nl

**NO — Norvège, Politidirektoratet**
- Page : <https://www.politiet.no/en/english/csca/>
- Fichier : `https://edit.politiet.no/globalassets/03-rad-og-forebygging/csca/csca_no.zip`, 7 DER.
- Empreintes : SHA-1 publiée pour le CSCA 06 seulement, concordante.
- Certificats : actuel CSCA_NO 06 (2022 → 2037), `4f8176db…27ba`, déjà dans le magasin. Trois
  nouveaux, expirés. Prochaine bascule annoncée pour janvier 2027.
- Conditions : aucune trouvée. Statut : **A2**. Contact : pki.eDocuments@politiet.no

**PL — Pologne, ministère du Numérique**
- Pages : <https://www.gov.pl/web/cyfryzacja/certificates-of-the-csca> et
  <https://www.gov.pl/web/cyfryzacja/urzad-certyfikacji-systemow-paszportowych-csca->
- Fichiers : actuel `https://www.gov.pl/attachment/f4ab7e41-198d-4389-87ff-d08f025916b5`, lien
  `https://www.gov.pl/attachment/c293c761-be4c-469c-a0fd-1fba70eab40f`, et historique.
- **La page publie aussi des certificats de TEST** (rangés à part dans `PL/TEST_ne_pas_embarquer/`) :
  ne jamais les embarquer.
- Empreintes : SHA-1 et SHA-256 publiées. Tout concorde, sauf la SHA-256 du CSCA actuel sur la page
  (`55045cf7…`, alors que le fichier donne `adb554e6…c4f8`) ; sa SHA-1 concorde.
- Certificats : actuel CSCA Poland (2025-04-03 → 2039-04-03), déjà dans le magasin.
- Conditions :
  - <https://www.gov.pl/web/cyfryzacja/ponowne-wykorzystywanie> : « *Informacje sektora publicznego
    udostępniane na stronie Ministerstwa Cyfryzacji są udostępniane w celu ponownego wykorzystywania
    na licencji Creative Commons Uznanie Autorstwa 3.0 Polska, o ile nie jest to stwierdzone
    inaczej.* » (CC BY 3.0 PL) ;
  - <https://www.gov.pl/web/gov/prawa-autorskie> : « *Korzystanie przez użytkowników z materiałów
    urzędowych opublikowanych w serwisie Gov.pl nie wymaga zgody Ministra Cyfryzacji.* »
- Statut : **A1**. Contact : kancelaria@cyfra.gov.pl

**PT — Portugal, IRN**
- Page : <https://irn.justica.gov.pt/Documentos-de-Identificacao/Certificados-CSCA>, mise à jour le
  2026-08-04. Aucun fichier en ligne.
- Le magasin actuel contient 6 certificats portugais. Statut : sans objet.

**RO — Roumanie, DGP**
- Page : <https://pasapoarte.mai.gov.ro/csca.html>
- Fichiers : `https://pasapoarte.mai.gov.ro/certificate/<nom>.der`, 11 certificats.
- Empreintes : SHA-1 publiées, 11 sur 11 concordantes.
- Certificats : actuel CSCA Romania 2023 (→ 2039), `859d155c…e1b3`, déjà dans le magasin.
- Conditions : aucune mention. La page précise : « *The information required to verify the
  authenticity of the following certificates is also available in authentic printed form upon
  request.* »
- Statut : **A2**. Contact : npkd.dgp@mai.gov.ro

**SE — Suède, Polismyndigheten**
- Pages :
  <https://polisen.se/en/services-and-permits/passport-and-national-id-card/swedish-certificates-and-crls/country-signing-certificate-authority-csca-sweden/>
  et <http://cert.polisen.se/CSCA/>
- Fichier : <https://polisen.se/contentassets/359ae9679d4949cc869baaf6733fb29c/se_csca.zip>,
  11 certificats.
- Empreintes : SHA-1 publiées, concordantes.
- Certificats : actuel « Swedish Country Signing CA v2 » (2024), déjà dans le magasin.
- Conditions : section 2.2 (contact requis avant réutilisation de textes).
- Statut : **B**. Contacts : csca.sweden@polisen.se, emrtd@polisen.se

**SI — Slovénie, SI-TRUST**
- Page :
  <https://www.si-trust.gov.si/sl/storitve-centra/digitalna-potrdila-za-biometricne-potne-listine/digitalna-potrdila-za-biometricne-potne-listine>
- Fichiers : `cacert*.der` et leurs liens (9 fichiers).
- Empreintes : SHA-256 et SHA-1 publiées pour les certificats auto-signés, concordantes.
- Certificats : actuel CSCA-Slovenia 5 (2024 → 2040), déjà dans le magasin.
- Conditions : « © 2026 SI-TRUST » seulement. Statut : **B**. Contact : csca-slovenia@gov.si

**SK — Slovaquie, MV SR**
- Page : <https://www.minv.sk/?ePassports-certifikaty>
- Fichiers : `https://www.minv.sk/swift_data/source/images/standardy_certifikaty_letectvo/*.rar`
  (6 archives RAR).
- Empreintes : SHA-1 publiées, concordantes.
- Certificats : CSCA Slovakia 5 (2023 → 2038) et son lien, déjà dans le magasin.
- **Le CSCA des cartes d'identité, « The Slovak eTP eID CSCA »
  (`31c15a3aeb6f07e670b9c0e92be422684be992c5086e28f0be9685bf4dd10cca`, 2020 → 2040), n'est pas publié
  par la Slovaquie.** On ne le trouve que dans les ML IT, SE et NL.
- Conditions : « © 2026 Ministerstvo vnútra SR. » Statut : **B**.
- Contact : public@minv.slovensko.sk

**CH — Suisse, fedpol / OFIT**
- Pages : <https://www.bit.admin.ch/en/sg-pki-csca-production-en> et
  <https://www.bit.admin.ch/en/sg-pki-csca-production-previous-en>
- Fichiers : `https://www.pki.admin.ch/certificates/csca/prod/CSCA_Self-Signed_Certificate_<année>.der`
  et `CSCA_Link_Certificate_<a>-<b>.der`, 13 fichiers.
- Empreintes : SHA-256 publiées, 13 sur 13 concordantes.
- Certificats : **nouveaux et valides** : CSCA-CHE 2026 (2026-09-08 → 2040-02-08),
  `843990bd13ba69083ab0afee994c4f7116d764a5be1d47d70c9da039f99587bc`, et son lien 2026-2029,
  `ee4b88a6…ae7f`. Ils ne sont dans aucune Master List, pas même la ML suisse.
- Conditions : admin.ch (section 2.2). Statut : **C**.
- Contacts : eac-spoc.che@fedpol.admin.ch (adresse de la page, écrite en toutes lettres),
  info@bit.admin.ch

**AD — Andorre** (pays tiers intégré ici pour la proximité)
- Page :
  <https://www.govern.ad/ca/ministeris-i-secretaries-d-estat/ministeri-de-justicia-i-interior/passaports>
- Fichiers : `https://www.govern.ad/documents/d/guest/csca-1002?download=true` et
  `…/cscalink-1003?download=true` (PEM).
- Empreintes : aucune publiée.
- Certificats : CSCA-AND 2025 et son lien, nouveaux et valides (`be77d05f…475c`, `e076cb6d…ac69`).
- Conditions, <https://www.govern.ad/ca/avis-legal> : « *No es podran fer actes totals o parcials de
  reproducció, publicació, […] distribució […] dels webs sense el consentiment previ i per escrit del
  Govern.* »
- Statut : **C**. Contact : interior@govern.ad

**SM — Saint-Marin** : aucune page trouvée. Trois certificats SM valides, dont 2026, sont dans la ML
IT.

**VA — Saint-Siège**
- Page : <https://epass.vatican.va/crl/crl.html>
- Fichiers : `https://epass.vatican.va/crl/linked/{VACSCA002.crt, Link_VACSCA001-VACSCA002.cer, VACSCA001.crt}`.
- Empreintes : SHA-1 publiées, concordantes.
- Certificats : tous déjà dans le magasin.
- Conditions : aucune. Statut : **A2**. Contact : uffprot@sds.va

**EU — Laissez-passer de l'Union européenne (Commission, JRC)**
- Page : <https://eu-csca.jrc.ec.europa.eu/>
- Fichiers : `EU-LP-CSCA02_self_signed.pem`, `EU-LP-CSCA_link.pem` et `eu-lp-csca-self_signed_crt.zip`.
  Les fichiers `.crt` de 2025 sont bloqués par le pare-feu applicatif (403).
- Empreintes : SHA-256 publiées, concordantes. Celles de 2025 sont déjà dans le magasin.
- Conditions, <https://commission.europa.eu/legal-notice_en> : « *Unless otherwise indicated […]
  content owned by the EU on this website is licensed under the Creative Commons Attribution 4.0
  International (CC BY 4.0) licence.* »
- Statut : **A1**. Contact : eu-csca@ec.europa.eu

### 3.2 Europe hors UE et Caucase

| Pays | Page officielle | Fichiers | Empreintes | Nouveaux valides | Conditions | Statut | Contact |
|---|---|---|---|---|---|---|---|
| RS Serbie | <https://crl.mup.gov.rs/CSCA.html> | `https://crl.mup.gov.rs/RS_CSCA_{1,4,25,53,78,101}.crt` | SHA-1, concordantes | 0 (actuel n° 101, 2024, déjà présent) | « © Министарство унутрашњих послова Републике Србије » | B | ca@mup.gov.rs |
| BA Bosnie-Herzégovine | <https://www.iddeea.gov.ba/en/pki-system-for-digital-signing-of-travel-documents-and-e-ids/> | `…/wp-content/uploads/WEB/Dokumenti/CSCA_SertifikatBA.der`, `Link_SertifikatBA.der` | aucune | 0 | « Copyright 2026 © IDDEEA all rights reserved. » | B | iddeea@iddeea.gov.ba |
| MK Macédoine du Nord | <https://mvr.gov.mk/mk-MK/uslugi/csca> | `https://mvr.gov.mk/csca/ZIPs/*.zip` | SHA-256 et SHA-1, concordantes | 0 | « Сите права задржани » (tous droits réservés) | B | csca-mk@moi.gov.mk |
| GE Géorgie | <https://id.ge/en/tsp/csca/> | `https://id.ge/en/pki/GEO-CSCA-{1..6}.crt` | SHA-256, concordantes | **2** : n° 6 « G2 », actif (`277fcd17…de3c`, aussi dans les ML IT, SE et NL), et n° 5 (`2ef9e14c…c844`, dans aucune ML) | aucune mention | **A2** | online@sda.gov.ge |
| ME, AL, MD, UA, AM, AZ, TR, XK, BY, RU, KZ, UZ | non trouvées | — | — | via les ML seulement | — | — | — |

### 3.3 Hors d'Europe

| Pays | Page officielle | Fichiers | Empreintes | Nouveaux valides | Conditions | Statut | Contact |
|---|---|---|---|---|---|---|---|
| TW Taïwan | <https://www.boca.gov.tw/cp-243-4449-23912-2.html> | `https://www.boca.gov.tw/dl-4026-3f56bf17866c4ad8a9abee7dff57d84f.html` (CSCA 2024), etc. : 4 auto-signés et 2 liens | SHA-1 et SHA-256, 6 sur 6 concordantes | 0 | <https://www.boca.gov.tw/np-359-2.html> : « *provided under "Open Government Data License, version 1.0 (OGDL-Taiwan-1.0)" […] The users are granted a perpetual, worldwide, license […] the attribution shall be provided* » | **A1** | CSCA-TWN@boca.gov.tw |
| MY Malaisie | <https://www.imi.gov.my/index.php/en/main-services/passport/the-malaysian-country-signing-ca-csca-en/> | `https://www.imi.gov.my/wp-content/uploads/2026/01/CurrentPublicKeyCert.der`, etc. (4) | SHA-256, 4 sur 4 concordantes | 0 | « Copyright © 2021 \| Immigration Department of Malaysia » ; aucune licence | B | pbh@imi.gov.my |
| KW Koweït | <https://epp.moi.gov.kw/csca/> | `CSCAKuwait.cer`, `1May2021Link.cer`, `1April2016.cer` | SHA-1 du CSCA actuel, concordante | 1 lien (`e2ff8c3b…1d36`) | « جميع الحقوق محفوظة » (tous droits réservés) | B | csca-kuwait@moi.gov.kw |
| JP Japon | <https://www.mofa.go.jp/ca/pss/page23e_000598.html> | 403 (Akamai), rien n'a été téléchargé | publiées d'après les extraits | ? | non lues (le Government of Japan Standard Terms of Use v2.0, compatible CC BY 4.0, est probable mais non vérifié) | à déterminer | — |
| AU Australie | <https://www.passports.gov.au/help/australian-country-signing-certificate-authority-csca> | connexion bloquée | SHA-1 du lien d'après les extraits | ? | CC BY 4.0 sur dfat.gov.au, application à passports.gov.au non vérifiée | à déterminer | csca-australia@dfat.gov.au |
| IL Israël | <https://www.gov.il/en/departments/guides/israeli_passport_and_id_certificates> | 403 (Cloudflare) | ? | ? | non lues | à déterminer | — |
| NG Nigéria | <https://immigration.gov.ng/pki-pkd/> | CSCA et ML annoncés, 404 | aucune | ? | « All Rights Reserved » | B/C | kn.nandap@immigration.gov.ng |

Aucune publication officielle n'a été trouvée pour US, CA, NZ, SG, KR, HK, MO, CN, TH, PH, ID, VN,
IN, AE, QA, SA, OM, BH, BR, AR, CL, MX, CO, PE, UY, ZA, KE, MA, DZ, TN, EG et l'ONU (laissez-passer).
Ces émetteurs ne sont accessibles que par les Master Lists.

## 4. Couverture par pays

Le tableau porte sur les pays européens et ceux qui ont une publication nationale. « Base » compte
les certificats valides du magasin actuel. « Nouveaux valides » compte les certificats valides
absents du magasin, avec pour chacun le meilleur statut disponible.

| Pays | Base | Nouveaux valides (statut) | Sources officielles | Meilleure source pour Sceau | Statut |
|---|---|---|---|---|---|
| AT | 5 | 0 | national, ML DE/IT/SE/NL/CH/EE | ML BSI (déjà embarquée) | A\* |
| BE | 16 | 0 | national passeport et eID, ML | ML BSI + national | A\*/A1 |
| BG | 8 | 0 | national (bloqué), ML | ML BSI | A\* |
| CH | 6 | **2 (C)** : CSCA 2026 et lien | national, ML CH | national (fedpol) | C |
| CY | 10 | 0 | national, ML | ML BSI | A\* |
| CZ | 5 | **3 (B)** : eID_CSCA des cartes d'identité | national seulement | national (MV ČR) | B |
| DE | 9 | 2 (B), serial 102 de 2016 | national, ML | ML BSI | A\* |
| DK | 7 | 0 (bascule attendue) | national, ML | ML BSI + national | A\*/A2 |
| EE | 7 | 1 (B), un lien | national, ML EE | ML BSI + national | A\*/A2 |
| ES | 5 | 1 (B) | national, ML ES | ML BSI | A\* |
| FI | 5 | 0 | national, ML | ML BSI | A\* |
| FR | 6 | 0 | ANTS, ML | ANTS (eID-FRANCE dans aucune ML) | A2 |
| GB | 8 | **2 (A1)** : CSCA 2026 et lien | national, ML DE | **national HMPO** | **A1** |
| GR | 12 | 2 (A2, titres de séjour 2026), 3 (B), 5 (C, clés de secours) | national passeport, national titres de séjour, ML | national titres de séjour + ML IT | A2/B |
| HR | 7 | 0 | ML seulement | ML BSI | A\* |
| HU | 21 | **8 (B)**, dont les 2 CSCA 2024 | national, ML IT/SE/NL/EE/CH | ML IT (tout sauf 2 liens) + national | B |
| IE | 6 | 0 | ML seulement | ML BSI | A\* |
| IS | 5 | 0 | national, ML | ML BSI | A\* |
| IT | 8 | 1 (B), CA de 2011 | national, ML IT | ML BSI | A\* |
| LI | 5 | 1 (C), CSCA 2024 | national (bloqué), ML CH | ML CH | C |
| LT | 6 | 4 (B), liens | national, ML IT/CH | national (3) + ML IT (1) | B |
| LU | 12 | 1 (A2), lien eTravel | national, ML | national INCERT | A2 |
| LV | 11 | 0 | national, ML | ML BSI | A\* |
| MT | 10 | 5 (B) | national (bloqué), ML IT/SE/NL/CH | ML IT | B |
| NL | 10 | 0 | national, ML NL | ML BSI | A\* |
| NO | 4 | 0 | national, ML | ML BSI | A\* |
| PL | 9 | 0 | national (A1), ML | ML BSI + national | A\*/A1 |
| PT | 6 | 0 | ML seulement | ML BSI | A\* |
| RO | 9 | 0 | national, ML | ML BSI | A\* |
| SE | 5 | 0 | national, ML SE | ML BSI | A\* |
| SI | 5 | 0 | national, ML | ML BSI | A\* |
| SK | 5 | **1 (B)** : eID CSCA des cartes d'identité | national (passeport), ML IT/SE/NL | ML IT | B |
| AD | 5 | 2 (C), CSCA 2025 | national, ML CH | national | C |
| ME | 6 | 1 (C) | ML CH | ML CH | C |
| SM | 1 | 3 (B), dont 2026 | ML IT/NL/CH | ML IT | B |
| GE | 4 | **2 (A2)** | national, ML IT/SE/NL | national (id.ge) | A2 |
| KW | 3 | 1 (B), lien | national | national | B |
| TW, MY, EU, VA, RS, BA, MK | — | 0 | national, ML | ML BSI | A\* |
| TR | 10 | 2 (B) | ML IT/SE | ML IT | B |
| JP | 8 | 1 (B) | national (bloqué), ML IT/SE/ES | ML IT | B |
| NZ | 5 | 3 (B) | ML IT/SE/NL/CH | ML IT | B |
| CN | 25 | 6 (B, dont Macao) | ML IT/SE/NL | ML IT | B |
| MX | 1 | 2 (C), CSCA 2026 | ML CH | ML CH | C |
| CL | 7 | 1 (B) + 1 (C), 2026 | ML NL/CH | ML NL | B |

**24 émetteurs absents du magasin actuel, présents seulement dans les ML IT, SE et NL (B)** :
Angola, République dominicaine, Égypte, Éthiopie, Ghana, Gambie, Indonésie, Iran, Jordanie,
Kirghizstan, Saint-Christophe-et-Niévès, Corée du Nord, Kazakhstan, Mozambique, Nigéria, Pakistan,
Palestine, Soudan, Sierra Leone, Sénégal, Syrie, Togo, Ordre de Malte (XO) et Yémen. Deux autres,
Maldives et « United Nations CSCA » 2012, sont seulement dans la ML ES et expirés.

**Certificats valides qui ne figurent dans aucune Master List** (source nationale seulement) :
- GB 2026 et son lien (A1) ;
- GE n° 5 (A2) ;
- CZ eID_CSCA, 3 certificats (B) ;
- CH 2026 et son lien (C) ;
- KW, un lien (B) ;
- LT, lien 005→006 (B) ;
- FR eID-FRANCE (déjà embarqué).

L'inventaire complet (un certificat par ligne, avec SHA-256, pays, validité, sources et meilleur
statut) est dans `_notes/inventory.tsv` du catalogue. On le régénère avec `python3 tools/inventory.py`.

## 5. Procédure de mise à jour

À faire avant chaque release, et dès qu'une bascule est annoncée (GB le 2026-09-26, DK en
septembre 2026, NO en janvier 2027, KW annoncée pour mars 2026 mais toujours pas publiée).

1. **Master Lists embarquées** :
   - retélécharger le fichier depuis l'URL officielle de la section 2 ;
   - comparer sa SHA-256 à la version embarquée, et l'empreinte publiée du fichier si elle existe
     (NL : SHA-256 ; SE : SHA-1) ;
   - vérifier la signature : `openssl cms -verify -noverify -inform DER -in f.ml -out /dev/null` ;
   - vérifier l'ancrage : `python3 tools/verify_issued.py signer.der csca.der`, où `csca.der` est le
     CSCA du pays téléchargé depuis sa page officielle, dont l'empreinte est comparée à celle publiée
     par l'État. Ne jamais prendre l'empreinte à épingler dans le fichier lui-même.
2. **Certificats nationaux** : pour chaque source retenue, retélécharger la page, relever les
   nouveaux fichiers, puis lancer `python3 tools/certinfo.py fichiers…`. Contrôler :
   - le pays `C` ;
   - le type : auto-signé, ou lien vérifié par le CSCA précédent déjà présent ;
   - la validité ;
   - l'empreinte publiée par l'État, s'il y en a une ;
   - que le fichier n'est **pas** un certificat de test (cas de la Pologne).
3. Relire les conditions de réutilisation de chaque source (pied de page, mentions légales). Toute
   évolution change le statut A/B/C de ce document.
4. Relancer `python3 tools/inventory.py` pour mesurer le gain de couverture. Mettre à jour
   `docs/trust-store.md` : fichier, rôle, sujet, validité, empreinte publiée, SHA-256 constatée, date.
5. Dans le dépôt : copier les fichiers dans `core/src/main/resources/trust/`, les lister dans
   `trust/index.txt`, puis lancer `./gradlew :core:test` (`TrustStoreFingerprintTest`).
6. Sites qui filtrent les robots (ANTS, BG, LI, MC, MT, JP, AU, IL, fichiers `.crt` du JRC) : mise à
   jour à la main depuis un navigateur, avec les mêmes contrôles.

## 6. Contacts pour autorisation

**Sources B** : l'autorisation est à demander par écrit (voir `RECOMMENDATION.md` pour le modèle de
demande).

| Source | Objet de la demande | Contact |
|---|---|---|
| ML IT (ministère de l'Intérieur) | Redistribution à l'identique de `IT_MasterListCSCA.ml` dans un APK libre | epass.certauthority@interno.it (passeport) ; epermits.certauthority@interno.it (titres de séjour) |
| ML SE (Polismyndigheten) | Idem pour `SWE.ml` | emrtd@polisen.se ; csca.sweden@polisen.se |
| ML NL (RvIG) | Idem pour `NL_MASTERLIST.mls` | info@rvig.nl |
| ML EE (PPA) | Idem pour `CSCA_masterlist.ml` | pki@politsei.ee |
| CZ eID_CSCA (MV ČR) | Redistribution des 3 certificats eID_CSCA ; correction de l'empreinte du lien 2023 | webmaster@mv.gov.cz (à défaut d'une adresse CSCA) |
| HU (ministère de l'Énergie, CSCA-HU) | Redistribution des CSCA ; publication des CSCA 2024 | csca-hungary@em.gov.hu |
| SK (MV SR) | Publication du « Slovak eTP eID CSCA », ou accord pour le reprendre d'une ML | public@minv.slovensko.sk |
| LT (ADIC) | Redistribution des certificats de lien | courrier à ADIC, Žirmūnų g. 1D, Vilnius |
| LV (PMLP) | Redistribution des CSCA | npkd@pmlp.gov.lv |
| SI, RS, BA, MK | Redistribution des CSCA (tous déjà couverts par la ML BSI) | csca-slovenia@gov.si ; ca@mup.gov.rs ; iddeea@iddeea.gov.ba ; csca-mk@moi.gov.mk |
| AT (BMI) | Portée de la clause non commerciale | csca@bmi.gv.at |
| MY, KW | Redistribution des CSCA | pbh@imi.gov.my ; csca-kuwait@moi.gov.kw |
| DE (BSI) | Confirmation que la redistribution à l'identique dans un APK n'est pas une « Spiegelung » | csca-germany@bsi.bund.de ; poststelle@bsi.bund.de |

**Sources C** : il faut un accord écrit préalable.

| Source | Contact |
|---|---|
| CH (CSCA 2026, ML suisse) | eac-spoc.che@fedpol.admin.ch |
| AD (CSCA 2025) | interior@govern.ad |
| GR passeport (clés de secours) | csca@passport.gov.gr |
| ES (ML 2022, CSCA) | Dirección General de la Policía (aucune adresse CSCA publiée) |
| ICAO PKD (Master List ICAO) | icao-pkd@icao.int |

**Sources A** (attribution seulement) :
- GB : mention OGL v3 ;
- PL : CC BY 3.0 PL, « Źródło: Ministerstwo Cyfryzacji » ;
- TW : OGDL-Taiwan-1.0 ;
- EU : CC BY 4.0 ;
- BE passeport : CC0, aucune attribution requise.

Les attributions peuvent figurer dans `docs/trust-store.md` et dans l'écran « À propos ».
