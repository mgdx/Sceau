#!/usr/bin/env bash
# Mise à jour du magasin de confiance embarqué (core/src/main/resources/trust/).
#
# Outil du développeur, lancé à chaque release : l'application, elle, ne télécharge jamais rien.
# Automatise la « Procédure de mise à jour » de docs/trust-store.md :
#   - ANTS : archives CSCA (passeports) et CSCA e-ID (cartes d'identité), empreinte recalculée
#     et comparée à celle publiée dans l'archive ;
#   - BSI : German Master List, signature CMS vérifiée et signataire émis par un certificat
#     épinglé dans TrustStoreLoader.EMBEDDED_MASTER_LIST_ANCHORS.
# Par défaut, rapport seulement : aucun fichier n'est écrit. Avec --apply, les fichiers
# nouveaux ou modifiés sont copiés dans trust/ et index.txt est complété ; les tableaux de
# docs/trust-store.md restent à mettre à jour à la main avec les lignes affichées.
#
# Dépendances : bash, curl, openssl (3.x), sha256sum, sha1sum, unzip, coreutils.

set -euo pipefail

# --- Sources (voir docs/trust-store.md, « Provenance ») -------------------------------------

# Pages de l'ANTS. Le site filtre les robots (page « Connexion bloquée ») : les archives se
# téléchargent à la main depuis ces pages, puis se passent au script avec --ants-dir.
ANTS_CSCA_PAGE="https://ants.gouv.fr/csca"
ANTS_EID_PAGE="https://ants.gouv.fr/home/csca-e-id"
# Liens directs vers les archives de l'ANTS, à renseigner si l'ANTS en publie de stables et
# accessibles sans navigateur. Vide : les archives viennent de --ants-dir.
ANTS_ARCHIVE_URLS=()

# German Master List du BSI (le paramètre v= de la page change à chaque publication ; sans
# lui, le BSI sert la version courante).
BSI_ML_PAGE="https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.html"
BSI_ML_URL="https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.zip?__blob=publicationFile"

# --- Chemins ---------------------------------------------------------------------------------

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TRUST_DIR="$ROOT/core/src/main/resources/trust"
INDEX="$TRUST_DIR/index.txt"
LOADER="$ROOT/core/src/main/kotlin/io/github/mgdx/sceau/core/trust/TrustStoreLoader.kt"
ML_NAME="de-bsi-master-list.ml"
ICAO_MASTER_LIST_OID="2.23.136.1.1.2"

# --- Options ---------------------------------------------------------------------------------

APPLY=0
ANTS_DIR=""
BSI_ZIP=""

usage() {
    cat <<EOF
Usage : scripts/update-trust-store.sh [--apply] [--ants-dir DOSSIER] [--bsi-zip FICHIER]

  --apply              écrit les fichiers nouveaux ou modifiés dans core/src/main/resources/trust/
                       (sans cette option : rapport seulement, rien n'est écrit)
  --ants-dir DOSSIER   dossier contenant les archives .zip de l'ANTS téléchargées à la main
                       depuis $ANTS_CSCA_PAGE
                       et $ANTS_EID_PAGE
  --bsi-zip FICHIER    archive GermanMasterList.zip locale au lieu du téléchargement
  -h, --help           affiche cette aide
EOF
}

while (($# > 0)); do
    case "$1" in
        --apply) APPLY=1 ;;
        --ants-dir)
            (($# >= 2)) || { usage >&2; exit 2; }
            ANTS_DIR="$2"
            shift
            ;;
        --bsi-zip)
            (($# >= 2)) || { usage >&2; exit 2; }
            BSI_ZIP="$2"
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            echo "Option inconnue : $1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

# --- Outils ----------------------------------------------------------------------------------

die() {
    echo "ÉCHEC : $*" >&2
    echo "Aucun fichier n'a été écrit." >&2
    exit 1
}

info() { echo "$*"; }

for tool in curl openssl sha256sum sha1sum unzip; do
    command -v "$tool" >/dev/null 2>&1 || die "outil manquant : $tool"
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }
sha1_of() { sha1sum "$1" | cut -d' ' -f1; }

# Champs d'un certificat DER.
x509_field() { openssl x509 -inform DER -in "$1" -noout "${@:2}"; }
cert_subject() { x509_field "$1" -subject | sed 's/^subject=//'; }
cert_issuer() { x509_field "$1" -issuer | sed 's/^issuer=//'; }
cert_start() { x509_field "$1" -dateopt iso_8601 -startdate | sed 's/^notBefore=//; s/ .*//'; }
cert_end() { x509_field "$1" -dateopt iso_8601 -enddate | sed 's/^notAfter=//; s/ .*//'; }

# Fichiers à écrire en mode --apply : « source|nom dans trust/ ».
PLANNED=()
# Nouveaux noms à ajouter à index.txt.
NEW_INDEX=()
# Lignes à reporter dans docs/trust-store.md.
DOC_FILES_ROWS=()
DOC_PROVENANCE_ROWS=()
TODAY="$(date +%Y-%m-%d)"

# Fichier embarqué dont la SHA-256 vaut $1, ou chaîne vide.
embedded_with_sha256() {
    local f
    for f in "$TRUST_DIR"/*; do
        [[ "$(basename "$f")" == index.txt ]] && continue
        if [[ "$(sha256_of "$f")" == "$1" ]]; then
            basename "$f"
            return
        fi
    done
}

# --- ANTS ------------------------------------------------------------------------------------

ANTS_ZIPS=()

fetch_ants() {
    local url i=0
    for url in "${ANTS_ARCHIVE_URLS[@]+"${ANTS_ARCHIVE_URLS[@]}"}"; do
        i=$((i + 1))
        local out="$WORK/ants-download-$i.zip"
        curl -fsSL -A "Mozilla/5.0" -o "$out" "$url" || die "téléchargement impossible : $url"
        unzip -tq "$out" >/dev/null 2>&1 ||
            die "$url n'est pas une archive zip (page de blocage anti-robot ?) : téléchargez-la à la main et utilisez --ants-dir"
        ANTS_ZIPS+=("$out")
    done
    if [[ -n "$ANTS_DIR" ]]; then
        [[ -d "$ANTS_DIR" ]] || die "dossier introuvable : $ANTS_DIR"
        local z
        for z in "$ANTS_DIR"/*.zip "$ANTS_DIR"/*.ZIP; do
            [[ -e "$z" ]] && ANTS_ZIPS+=("$z")
        done
        ((${#ANTS_ZIPS[@]} > 0)) || die "aucune archive .zip dans $ANTS_DIR"
    fi
}

# Vérifie un certificat extrait d'une archive de l'ANTS et prépare son éventuelle copie.
#   $1 : certificat DER, $2 : empreintes publiées normalisées, $3 : archive, $4 : fichier d'origine
check_ants_cert() {
    local der="$1" published="$2" archive="$3" member="$4"
    local sha1 sha256 algo digest
    sha1="$(sha1_of "$der")"
    sha256="$(sha256_of "$der")"
    if grep -qF "$sha256" <<<"$published"; then
        algo="SHA-256"
        digest="$sha256"
    elif grep -qF "$sha1" <<<"$published"; then
        algo="SHA-1"
        digest="$sha1"
    else
        die "$archive : l'empreinte de $member (SHA-1 $sha1, SHA-256 $sha256) ne figure pas dans le fichier d'empreinte de l'archive"
    fi

    local subject issuer start end kind role name
    subject="$(cert_subject "$der")"
    issuer="$(cert_issuer "$der")"
    start="$(cert_start "$der")"
    end="$(cert_end "$der")"
    case "$subject" in
        *"CN=eID-FRANCE"*) kind="ants-csca-eid" role="e-ID, CNIe (CSCA)" ;;
        *"CN=CSCA-FRANCE"*) kind="ants-csca" role="Passeport (CSCA)" ;;
        *) die "$archive : sujet inattendu pour $member : $subject" ;;
    esac
    name="$kind-${start:0:4}.der"

    info "  $member"
    info "    sujet     : $subject"
    info "    émetteur  : $issuer"
    info "    validité  : $start → $end"
    info "    empreinte publiée $algo vérifiée : $digest"
    info "    SHA-256   : $sha256"

    local same
    same="$(embedded_with_sha256 "$sha256")"
    if [[ -n "$same" ]]; then
        info "    => identique au fichier embarqué $same"
        return
    fi
    if [[ -e "$TRUST_DIR/$name" ]]; then
        info "    => DIFFÉRENT du fichier embarqué $name (même nom, contenu différent) : à examiner avant --apply"
    else
        info "    => NOUVEAU : $name"
        NEW_INDEX+=("$name")
    fi
    PLANNED+=("$der|$name")
    DOC_FILES_ROWS+=("| \`$name\` | $role | \`$subject\` | $start → $end | $algo \`$digest\` | \`$sha256\` |")
    DOC_PROVENANCE_ROWS+=("| \`$name\` | \`$(basename "$archive")\` | \`$member\`, empreinte dans l'archive | page CSCA$([[ $kind == ants-csca-eid ]] && echo " e-ID") de l'ANTS | $TODAY |")
}

check_ants_archive() {
    local zip="$1" dir
    dir="$(mktemp -d "$WORK/ants.XXXXXX")"
    unzip -qo "$zip" -d "$dir" || die "archive illisible : $zip"
    info ""
    info "Archive ANTS $(basename "$zip")"

    # Empreintes publiées : texte en minuscules, sans séparateurs d'octets.
    local published="" f
    while IFS= read -r -d '' f; do
        published+="$(tr -d ' :\t\r' <"$f" | tr 'A-F' 'a-f')"$'\n'
    done < <(find "$dir" -type f -iname '*finger*' -print0)
    [[ -n "$published" ]] || die "$(basename "$zip") : aucun fichier d'empreinte (*fingerprint*) dans l'archive"

    local seen="" count=0 der sha
    while IFS= read -r -d '' f; do
        der="$WORK/cert-$(sha1_of "$f").der"
        if openssl x509 -inform DER -in "$f" -noout 2>/dev/null; then
            cp "$f" "$der"
        elif openssl x509 -inform PEM -in "$f" -noout 2>/dev/null; then
            openssl x509 -inform PEM -in "$f" -outform DER -out "$der"
        else
            die "$(basename "$zip") : ${f#"$dir"/} n'est pas un certificat X.509 lisible"
        fi
        sha="$(sha256_of "$der")"
        # Le même certificat peut être fourni en DER et en PEM : une seule vérification.
        if grep -qF "$sha" <<<"$seen"; then
            info "  ${f#"$dir"/} : même certificat que ci-dessus"
            continue
        fi
        seen+="$sha"$'\n'
        count=$((count + 1))
        check_ants_cert "$der" "$published" "$zip" "${f#"$dir"/}"
    done < <(find "$dir" -type f \( -iname '*.crt' -o -iname '*.cer' -o -iname '*.der' -o -iname '*.pem' \) -print0 | sort -z)
    ((count > 0)) || die "$(basename "$zip") : aucun certificat dans l'archive"
}

# --- BSI -------------------------------------------------------------------------------------

# Empreintes épinglées, lues dans le code Kotlin pour ne pas les dupliquer.
read_anchors() {
    [[ -f "$LOADER" ]] || die "fichier introuvable : $LOADER"
    local block
    block="$(sed -n '/val EMBEDDED_MASTER_LIST_ANCHORS/,/^ *)/p' "$LOADER")"
    [[ -n "$block" ]] || die "EMBEDDED_MASTER_LIST_ANCHORS introuvable dans TrustStoreLoader.kt : format changé, adapter le script"
    grep -oE '"[0-9a-f]{64}"' <<<"$block" | tr -d '"'
}

# Algorithme de hachage de la signature d'un certificat, pour openssl dgst.
signature_digest() {
    local text alg
    text="$(x509_field "$1" -text)"
    alg="$(sed -n '/^ *Signature Algorithm: /{s///p;q;}' <<<"$text")"
    case "$alg" in
        ecdsa-with-SHA224 | sha224WithRSAEncryption) echo sha224 ;;
        ecdsa-with-SHA256 | sha256WithRSAEncryption) echo sha256 ;;
        ecdsa-with-SHA384 | sha384WithRSAEncryption) echo sha384 ;;
        ecdsa-with-SHA512 | sha512WithRSAEncryption) echo sha512 ;;
        *) die "algorithme de signature du signataire non pris en charge par le script : $alg" ;;
    esac
}

# Vérifie que le certificat $1 (DER) est signé par la clé du certificat $2 (DER).
# openssl verify refuse les clés EC à paramètres explicites, présentes chez le BSI : la
# signature est donc vérifiée directement sur le TBSCertificate.
signed_by() {
    local cert="$1" ca="$2" tmp line off hl len sig_off digest
    tmp="$(mktemp -d "$WORK/sig.XXXXXX")"
    [[ "$(cert_issuer "$cert")" == "$(cert_subject "$ca")" ]] || return 1
    local d1=()
    while IFS= read -r line; do
        [[ "$line" == *"d=1 "* ]] && d1+=("$line")
    done < <(openssl asn1parse -inform DER -in "$cert")
    ((${#d1[@]} == 3)) || return 1
    line="${d1[0]}"
    off="${line%%:*}"; off="${off// /}"
    hl="${line#*hl=}"; hl="${hl%% *}"
    len="${line#* l=}"; len="${len#"${len%%[! ]*}"}"; len="${len%% *}"
    head -c $((off + hl + len)) "$cert" | tail -c $((hl + len)) >"$tmp/tbs.der"
    sig_off="${d1[2]%%:*}"; sig_off="${sig_off// /}"
    openssl asn1parse -inform DER -in "$cert" -strparse "$sig_off" -noout -out "$tmp/sig.bin" >/dev/null
    openssl x509 -inform DER -in "$ca" -noout -pubkey >"$tmp/ca.pub"
    digest="$(signature_digest "$cert")"
    openssl dgst "-$digest" -verify "$tmp/ca.pub" -signature "$tmp/sig.bin" "$tmp/tbs.der" >/dev/null 2>&1
}

check_bsi() {
    local dir="$WORK/bsi" zip
    mkdir -p "$dir"
    info ""
    if [[ -n "$BSI_ZIP" ]]; then
        [[ -f "$BSI_ZIP" ]] || die "fichier introuvable : $BSI_ZIP"
        zip="$BSI_ZIP"
        info "German Master List du BSI (archive locale $BSI_ZIP)"
    else
        zip="$WORK/GermanMasterList.zip"
        info "German Master List du BSI : téléchargement de $BSI_ML_URL"
        curl -fsSL -o "$zip" "$BSI_ML_URL" || die "téléchargement impossible : $BSI_ML_URL (page : $BSI_ML_PAGE)"
    fi
    info "  SHA-256 de l'archive : $(sha256_of "$zip")"
    unzip -qo "$zip" -d "$dir/zip" || die "archive illisible : $zip"
    local mls=()
    while IFS= read -r -d '' f; do mls+=("$f"); done < <(find "$dir/zip" -type f -iname '*.ml' -print0)
    ((${#mls[@]} == 1)) || die "l'archive du BSI doit contenir exactement un fichier .ml (trouvés : ${#mls[@]})"
    local ml="${mls[0]}" member
    member="$(basename "$ml")"
    info "  fichier : $member, $(wc -c <"$ml") octets"

    # 1. Signature CMS, vérifiée avec le certificat signataire inclus dans le fichier.
    openssl cms -verify -inform DER -in "$ml" -noverify \
        -signer "$dir/signer.pem" -certsout "$dir/cms-certs.pem" -out "$dir/content.der" 2>"$dir/cms.log" ||
        die "signature CMS invalide pour $member : $(head -n 1 "$dir/cms.log")"
    info "  signature CMS : valide"
    openssl x509 -in "$dir/signer.pem" -outform DER -out "$dir/signer.der"

    local printed
    printed="$(openssl cms -cmsout -print -inform DER -in "$ml" -noout)"
    grep -q "eContentType: .*($ICAO_MASTER_LIST_OID)" <<<"$printed" ||
        die "$member : le contenu signé n'est pas une Master List ICAO ($ICAO_MASTER_LIST_OID)"
    local signing_time
    signing_time="$(sed -n '/signingTime/,${/^ *\(UTCTIME\|GENERALIZEDTIME\):/{s///p;q;}}' <<<"$printed")"
    [[ -n "$signing_time" ]] || die "$member : date de signature absente"
    local signed_epoch start_epoch end_epoch signed_day
    signed_epoch="$(date -u -d "$signing_time" +%s)"
    signed_day="$(date -u -d "$signing_time" +%Y-%m-%d)"
    start_epoch="$(date -u -d "$(x509_field "$dir/signer.der" -startdate | sed 's/^notBefore=//')" +%s)"
    end_epoch="$(date -u -d "$(x509_field "$dir/signer.der" -enddate | sed 's/^notAfter=//')" +%s)"
    ((signed_epoch >= start_epoch && signed_epoch <= end_epoch)) ||
        die "$member : le signataire n'était pas valide à la date de signature ($signed_day)"

    local signer_subject signer_start signer_end
    signer_subject="$(cert_subject "$dir/signer.der")"
    signer_start="$(cert_start "$dir/signer.der")"
    signer_end="$(cert_end "$dir/signer.der")"
    info "  signée le : $signed_day"
    info "  signataire : $signer_subject"
    info "    émetteur : $(cert_issuer "$dir/signer.der")"
    info "    validité : $signer_start → $signer_end"

    # 2. Certificats : bloc certificates du CMS et contenu CscaMasterList
    #    (SEQUENCE { version, SET OF Certificate }).
    mkdir -p "$dir/certs"
    awk -v d="$dir/certs" '/-----BEGIN CERTIFICATE-----/{n++} n{print > (d "/cms-" n ".pem")}' "$dir/cms-certs.pem"
    local p
    for p in "$dir"/certs/cms-*.pem; do
        [[ -e "$p" ]] || continue
        openssl x509 -in "$p" -outform DER -out "${p%.pem}.der"
        rm -f "$p"
    done
    local line off hl len count=0
    while IFS= read -r line; do
        [[ "$line" == *"d=2 "*"cons: SEQUENCE"* ]] || continue
        off="${line%%:*}"; off="${off// /}"
        hl="${line#*hl=}"; hl="${hl%% *}"
        len="${line#* l=}"; len="${len#"${len%%[! ]*}"}"; len="${len%% *}"
        head -c $((off + hl + len)) "$dir/content.der" | tail -c $((hl + len)) >"$dir/certs/ml-$off.der"
        count=$((count + 1))
    done < <(openssl asn1parse -inform DER -in "$dir/content.der")
    ((count > 0)) || die "$member : aucun certificat dans la Master List"
    info "  certificats dans la liste : $count"

    # 3. Ancrage : le signataire doit être signé par un certificat épinglé.
    local anchors
    anchors="$(read_anchors)"
    [[ -n "$anchors" ]] || die "aucune empreinte lue dans EMBEDDED_MASTER_LIST_ANCHORS : format changé, adapter le script"
    info "  certificats épinglés (TrustStoreLoader.kt) : $(wc -l <<<"$anchors")"
    local c sha anchored=""
    for c in "$dir"/certs/*.der; do
        sha="$(sha256_of "$c")"
        grep -qxF "$sha" <<<"$anchors" || continue
        if signed_by "$dir/signer.der" "$c"; then
            anchored="$sha"
            info "  ancrage : signataire émis par $(cert_subject "$c") (SHA-256 épinglée $sha)"
            break
        fi
    done
    [[ -n "$anchored" ]] || die "$member : signataire non émis par un certificat épinglé (UNTRUSTED_SIGNER)"

    # 4. Comparaison avec le fichier embarqué.
    local new_sha
    new_sha="$(sha256_of "$ml")"
    info "  SHA-256 : $new_sha"
    if [[ -f "$TRUST_DIR/$ML_NAME" && "$(sha256_of "$TRUST_DIR/$ML_NAME")" == "$new_sha" ]]; then
        info "  => identique au fichier embarqué $ML_NAME"
        return
    fi
    if [[ -f "$TRUST_DIR/$ML_NAME" ]]; then
        info "  => DIFFÉRENTE du fichier embarqué $ML_NAME : nouvelle publication du BSI"
    else
        info "  => NOUVEAU : $ML_NAME"
        NEW_INDEX+=("$ML_NAME")
    fi
    cp "$ml" "$dir/$ML_NAME"
    PLANNED+=("$dir/$ML_NAME|$ML_NAME")
    DOC_FILES_ROWS+=("| \`$ML_NAME\` | Master List CSCA (BSI, Allemagne) | signataire \`$signer_subject\` | signée le $signed_day ; signataire valide $signer_start → $signer_end | aucune pour le fichier (voir « Master List embarquée ») | \`$new_sha\` |")
    DOC_PROVENANCE_ROWS+=("| \`$ML_NAME\` | \`GermanMasterList.zip\` | \`$member\`, redistribué inchangé | <$BSI_ML_PAGE> | $TODAY |")
    ML_SUMMARY="fichier \`$member\`, $(wc -c <"$ml") octets, signé le $signed_day, $count certificats ; SHA-256 de l'archive $(sha256_of "$zip")"
}

# --- Déroulé ---------------------------------------------------------------------------------

ML_SUMMARY=""
info "Magasin de confiance embarqué : $TRUST_DIR"
if ((APPLY)); then info "Mode : --apply (écriture si différence)"; else info "Mode : rapport (aucune écriture)"; fi

fetch_ants
ANTS_CHECKED=0
if ((${#ANTS_ZIPS[@]} > 0)); then
    for zip in "${ANTS_ZIPS[@]}"; do check_ants_archive "$zip"; done
    ANTS_CHECKED=1
fi
check_bsi

info ""
if ((!ANTS_CHECKED)); then
    info "ATTENTION : certificats ANTS non vérifiés. Le site de l'ANTS bloque les téléchargements"
    info "automatiques : téléchargez les archives depuis $ANTS_CSCA_PAGE"
    info "et $ANTS_EID_PAGE, puis relancez avec --ants-dir DOSSIER."
fi

if ((${#PLANNED[@]} == 0)); then
    if ((ANTS_CHECKED)); then
        info "Magasin à jour : aucune différence avec les sources vérifiées."
    else
        info "Master List à jour ; certificats ANTS non vérifiés (voir ci-dessus)."
    fi
    exit 0
fi

info "Différences : ${#PLANNED[@]} fichier(s)."
for entry in "${PLANNED[@]}"; do info "  - ${entry#*|}"; done

if ((APPLY)); then
    for entry in "${PLANNED[@]}"; do
        cp "${entry%%|*}" "$TRUST_DIR/${entry#*|}"
    done
    if ((${#NEW_INDEX[@]} > 0)); then
        # Nouveaux certificats ANTS insérés avant le premier fichier qui n'en est pas un.
        new_index="$WORK/index.txt"
        ants_new="$(printf '%s\n' "${NEW_INDEX[@]}" | grep '^ants-' | sort || true)"
        other_new="$(printf '%s\n' "${NEW_INDEX[@]}" | grep -v '^ants-' || true)"
        awk -v add="$ants_new" '
            !done && $0 !~ /^#/ && $0 != "" && $0 !~ /^ants-/ { if (add != "") print add; done = 1 }
            { print }
            END { if (!done && add != "") print add }
        ' "$INDEX" >"$new_index"
        [[ -n "$other_new" ]] && printf '%s\n' "$other_new" >>"$new_index"
        cp "$new_index" "$INDEX"
        info "index.txt complété : ${NEW_INDEX[*]}"
    fi
    info "Fichiers écrits dans $TRUST_DIR."
else
    info "Rapport seulement : relancez avec --apply pour écrire ces fichiers."
fi

info ""
info "Lignes à reporter dans docs/trust-store.md, tableau « Fichiers embarqués » :"
printf '%s\n' "${DOC_FILES_ROWS[@]}"
info ""
info "Tableau « Provenance » :"
printf '%s\n' "${DOC_PROVENANCE_ROWS[@]}"
if [[ -n "$ML_SUMMARY" ]]; then
    info ""
    info "Section « Master List embarquée » : $ML_SUMMARY."
    info "Recompter les émetteurs et mettre à jour la description du contenu."
fi
info ""
info "Ensuite : ./gradlew :core:test (TrustStoreFingerprintTest, TrustStoreTest)."
