#!/usr/bin/env bash
# Update of the embedded trust store (core/src/main/resources/trust/).
#
# Developer tool, run at each release: the app itself never downloads anything.
# Automates the “Update procedure” of docs/trust-store.md:
#   - ANTS: CSCA (passports) and CSCA e-ID (identity cards) archives, fingerprint recomputed
#     and compared with the one published in the archive;
#   - BSI: German Master List, CMS signature verified and signer issued by a certificate
#     pinned in TrustStoreLoader.EMBEDDED_MASTER_LIST_ANCHORS.
# By default, report only: no file is written. With --apply, new or modified files are
# copied into trust/ and index.txt is extended; the tables of docs/trust-store.md still
# have to be updated by hand with the printed lines.
#
# Dependencies: bash, curl, openssl (3.x), sha256sum, sha1sum, unzip, coreutils.

set -euo pipefail

# --- Sources (see docs/trust-store.md, “Provenance”) ----------------------------------------

# ANTS pages. The site filters robots (“Connexion bloquée” page): the archives are downloaded
# by hand from these pages, then passed to the script with --ants-dir.
ANTS_CSCA_PAGE="https://ants.gouv.fr/csca"
ANTS_EID_PAGE="https://ants.gouv.fr/home/csca-e-id"
# Direct links to the ANTS archives, to fill in if the ANTS publishes stable ones that can be
# reached without a browser. Empty: the archives come from --ants-dir.
ANTS_ARCHIVE_URLS=()

# BSI German Master List (the page's v= parameter changes with each publication; without
# it, the BSI serves the current version).
BSI_ML_PAGE="https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.html"
BSI_ML_URL="https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.zip?__blob=publicationFile"

# --- Paths -----------------------------------------------------------------------------------

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
Usage: scripts/update-trust-store.sh [--apply] [--ants-dir DIR] [--bsi-zip FILE]

  --apply              writes new or modified files into core/src/main/resources/trust/
                       (without this option: report only, nothing is written)
  --ants-dir DIR       directory containing the ANTS .zip archives downloaded by hand
                       from $ANTS_CSCA_PAGE
                       and $ANTS_EID_PAGE
  --bsi-zip FILE       local GermanMasterList.zip archive instead of downloading it
  -h, --help           shows this help
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
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

# --- Tools -----------------------------------------------------------------------------------

die() {
    echo "FAILED: $*" >&2
    echo "No file was written." >&2
    exit 1
}

info() { echo "$*"; }

for tool in curl openssl sha256sum sha1sum unzip; do
    command -v "$tool" >/dev/null 2>&1 || die "missing tool: $tool"
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }
sha1_of() { sha1sum "$1" | cut -d' ' -f1; }

# Fields of a DER certificate.
x509_field() { openssl x509 -inform DER -in "$1" -noout "${@:2}"; }
cert_subject() { x509_field "$1" -subject | sed 's/^subject=//'; }
cert_issuer() { x509_field "$1" -issuer | sed 's/^issuer=//'; }
cert_start() { x509_field "$1" -dateopt iso_8601 -startdate | sed 's/^notBefore=//; s/ .*//'; }
cert_end() { x509_field "$1" -dateopt iso_8601 -enddate | sed 's/^notAfter=//; s/ .*//'; }

# Files to write in --apply mode: “source|name in trust/”.
PLANNED=()
# New names to add to index.txt.
NEW_INDEX=()
# Lines to copy into docs/trust-store.md.
DOC_FILES_ROWS=()
DOC_PROVENANCE_ROWS=()
TODAY="$(date +%Y-%m-%d)"

# Embedded file whose SHA-256 is $1, or empty string.
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
        curl -fsSL -A "Mozilla/5.0" -o "$out" "$url" || die "download failed: $url"
        unzip -tq "$out" >/dev/null 2>&1 ||
            die "$url is not a zip archive (anti-robot blocking page?): download it by hand and use --ants-dir"
        ANTS_ZIPS+=("$out")
    done
    if [[ -n "$ANTS_DIR" ]]; then
        [[ -d "$ANTS_DIR" ]] || die "directory not found: $ANTS_DIR"
        local z
        for z in "$ANTS_DIR"/*.zip "$ANTS_DIR"/*.ZIP; do
            [[ -e "$z" ]] && ANTS_ZIPS+=("$z")
        done
        ((${#ANTS_ZIPS[@]} > 0)) || die "no .zip archive in $ANTS_DIR"
    fi
}

# Checks a certificate extracted from an ANTS archive and prepares its copy, if any.
#   $1: DER certificate, $2: normalized published fingerprints, $3: archive, $4: original file
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
        die "$archive: the fingerprint of $member (SHA-1 $sha1, SHA-256 $sha256) is not in the archive's fingerprint file"
    fi

    local subject issuer start end kind role name
    subject="$(cert_subject "$der")"
    issuer="$(cert_issuer "$der")"
    start="$(cert_start "$der")"
    end="$(cert_end "$der")"
    case "$subject" in
        *"CN=eID-FRANCE"*) kind="ants-csca-eid" role="e-ID, CNIe (CSCA)" ;;
        *"CN=CSCA-FRANCE"*) kind="ants-csca" role="Passport (CSCA)" ;;
        *) die "$archive: unexpected subject for $member: $subject" ;;
    esac
    name="$kind-${start:0:4}.der"

    info "  $member"
    info "    subject   : $subject"
    info "    issuer    : $issuer"
    info "    validity  : $start → $end"
    info "    published $algo fingerprint verified: $digest"
    info "    SHA-256   : $sha256"

    local same
    same="$(embedded_with_sha256 "$sha256")"
    if [[ -n "$same" ]]; then
        info "    => identical to embedded file $same"
        return
    fi
    if [[ -e "$TRUST_DIR/$name" ]]; then
        info "    => DIFFERENT from embedded file $name (same name, different content): review before --apply"
    else
        info "    => NEW: $name"
        NEW_INDEX+=("$name")
    fi
    PLANNED+=("$der|$name")
    DOC_FILES_ROWS+=("| \`$name\` | $role | \`$subject\` | $start → $end | $algo \`$digest\` | \`$sha256\` |")
    DOC_PROVENANCE_ROWS+=("| \`$name\` | \`$(basename "$archive")\` | \`$member\`, fingerprint in the archive | ANTS CSCA$([[ $kind == ants-csca-eid ]] && echo " e-ID") page | $TODAY |")
}

check_ants_archive() {
    local zip="$1" dir
    dir="$(mktemp -d "$WORK/ants.XXXXXX")"
    unzip -qo "$zip" -d "$dir" || die "unreadable archive: $zip"
    info ""
    info "ANTS archive $(basename "$zip")"

    # Published fingerprints: lowercase text, without byte separators.
    local published="" f
    while IFS= read -r -d '' f; do
        published+="$(tr -d ' :\t\r' <"$f" | tr 'A-F' 'a-f')"$'\n'
    done < <(find "$dir" -type f -iname '*finger*' -print0)
    [[ -n "$published" ]] || die "$(basename "$zip"): no fingerprint file (*fingerprint*) in the archive"

    local seen="" count=0 der sha
    while IFS= read -r -d '' f; do
        der="$WORK/cert-$(sha1_of "$f").der"
        if openssl x509 -inform DER -in "$f" -noout 2>/dev/null; then
            cp "$f" "$der"
        elif openssl x509 -inform PEM -in "$f" -noout 2>/dev/null; then
            openssl x509 -inform PEM -in "$f" -outform DER -out "$der"
        else
            die "$(basename "$zip"): ${f#"$dir"/} is not a readable X.509 certificate"
        fi
        sha="$(sha256_of "$der")"
        # The same certificate may be provided in DER and in PEM: checked only once.
        if grep -qF "$sha" <<<"$seen"; then
            info "  ${f#"$dir"/}: same certificate as above"
            continue
        fi
        seen+="$sha"$'\n'
        count=$((count + 1))
        check_ants_cert "$der" "$published" "$zip" "${f#"$dir"/}"
    done < <(find "$dir" -type f \( -iname '*.crt' -o -iname '*.cer' -o -iname '*.der' -o -iname '*.pem' \) -print0 | sort -z)
    ((count > 0)) || die "$(basename "$zip"): no certificate in the archive"
}

# --- BSI -------------------------------------------------------------------------------------

# Pinned fingerprints, read from the Kotlin code so as not to duplicate them.
read_anchors() {
    [[ -f "$LOADER" ]] || die "file not found: $LOADER"
    local block
    block="$(sed -n '/val EMBEDDED_MASTER_LIST_ANCHORS/,/^ *)/p' "$LOADER")"
    [[ -n "$block" ]] || die "EMBEDDED_MASTER_LIST_ANCHORS not found in TrustStoreLoader.kt: format changed, adapt the script"
    grep -oE '"[0-9a-f]{64}"' <<<"$block" | tr -d '"'
}

# Hash algorithm of a certificate's signature, for openssl dgst.
signature_digest() {
    local text alg
    text="$(x509_field "$1" -text)"
    alg="$(sed -n '/^ *Signature Algorithm: /{s///p;q;}' <<<"$text")"
    case "$alg" in
        ecdsa-with-SHA224 | sha224WithRSAEncryption) echo sha224 ;;
        ecdsa-with-SHA256 | sha256WithRSAEncryption) echo sha256 ;;
        ecdsa-with-SHA384 | sha384WithRSAEncryption) echo sha384 ;;
        ecdsa-with-SHA512 | sha512WithRSAEncryption) echo sha512 ;;
        *) die "signer signature algorithm not supported by the script: $alg" ;;
    esac
}

# Checks that certificate $1 (DER) is signed by the key of certificate $2 (DER).
# openssl verify rejects EC keys with explicit parameters, which the BSI uses: the
# signature is therefore verified directly on the TBSCertificate.
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
        [[ -f "$BSI_ZIP" ]] || die "file not found: $BSI_ZIP"
        zip="$BSI_ZIP"
        info "BSI German Master List (local archive $BSI_ZIP)"
    else
        zip="$WORK/GermanMasterList.zip"
        info "BSI German Master List: downloading $BSI_ML_URL"
        curl -fsSL -o "$zip" "$BSI_ML_URL" || die "download failed: $BSI_ML_URL (page: $BSI_ML_PAGE)"
    fi
    info "  SHA-256 of the archive: $(sha256_of "$zip")"
    unzip -qo "$zip" -d "$dir/zip" || die "unreadable archive: $zip"
    local mls=()
    while IFS= read -r -d '' f; do mls+=("$f"); done < <(find "$dir/zip" -type f -iname '*.ml' -print0)
    ((${#mls[@]} == 1)) || die "the BSI archive must contain exactly one .ml file (found: ${#mls[@]})"
    local ml="${mls[0]}" member
    member="$(basename "$ml")"
    info "  file: $member, $(wc -c <"$ml") bytes"

    # 1. CMS signature, verified with the signer certificate included in the file.
    openssl cms -verify -inform DER -in "$ml" -noverify \
        -signer "$dir/signer.pem" -certsout "$dir/cms-certs.pem" -out "$dir/content.der" 2>"$dir/cms.log" ||
        die "invalid CMS signature for $member: $(head -n 1 "$dir/cms.log")"
    info "  CMS signature: valid"
    openssl x509 -in "$dir/signer.pem" -outform DER -out "$dir/signer.der"

    local printed
    printed="$(openssl cms -cmsout -print -inform DER -in "$ml" -noout)"
    grep -q "eContentType: .*($ICAO_MASTER_LIST_OID)" <<<"$printed" ||
        die "$member: the signed content is not an ICAO Master List ($ICAO_MASTER_LIST_OID)"
    local signing_time
    signing_time="$(sed -n '/signingTime/,${/^ *\(UTCTIME\|GENERALIZEDTIME\):/{s///p;q;}}' <<<"$printed")"
    [[ -n "$signing_time" ]] || die "$member: signing time missing"
    local signed_epoch start_epoch end_epoch signed_day
    signed_epoch="$(date -u -d "$signing_time" +%s)"
    signed_day="$(date -u -d "$signing_time" +%Y-%m-%d)"
    start_epoch="$(date -u -d "$(x509_field "$dir/signer.der" -startdate | sed 's/^notBefore=//')" +%s)"
    end_epoch="$(date -u -d "$(x509_field "$dir/signer.der" -enddate | sed 's/^notAfter=//')" +%s)"
    ((signed_epoch >= start_epoch && signed_epoch <= end_epoch)) ||
        die "$member: the signer was not valid at the signing time ($signed_day)"

    local signer_subject signer_start signer_end
    signer_subject="$(cert_subject "$dir/signer.der")"
    signer_start="$(cert_start "$dir/signer.der")"
    signer_end="$(cert_end "$dir/signer.der")"
    info "  signed on: $signed_day"
    info "  signer: $signer_subject"
    info "    issuer: $(cert_issuer "$dir/signer.der")"
    info "    validity: $signer_start → $signer_end"

    # 2. Certificates: certificates block of the CMS and CscaMasterList content
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
    ((count > 0)) || die "$member: no certificate in the Master List"
    info "  certificates in the list: $count"

    # 3. Anchoring: the signer must be signed by a pinned certificate.
    local anchors
    anchors="$(read_anchors)"
    [[ -n "$anchors" ]] || die "no fingerprint read from EMBEDDED_MASTER_LIST_ANCHORS: format changed, adapt the script"
    info "  pinned certificates (TrustStoreLoader.kt): $(wc -l <<<"$anchors")"
    local c sha anchored=""
    for c in "$dir"/certs/*.der; do
        sha="$(sha256_of "$c")"
        grep -qxF "$sha" <<<"$anchors" || continue
        if signed_by "$dir/signer.der" "$c"; then
            anchored="$sha"
            info "  anchoring: signer issued by $(cert_subject "$c") (pinned SHA-256 $sha)"
            break
        fi
    done
    [[ -n "$anchored" ]] || die "$member: signer not issued by a pinned certificate (UNTRUSTED_SIGNER)"

    # 4. Comparison with the embedded file.
    local new_sha
    new_sha="$(sha256_of "$ml")"
    info "  SHA-256 : $new_sha"
    if [[ -f "$TRUST_DIR/$ML_NAME" && "$(sha256_of "$TRUST_DIR/$ML_NAME")" == "$new_sha" ]]; then
        info "  => identical to embedded file $ML_NAME"
        return
    fi
    if [[ -f "$TRUST_DIR/$ML_NAME" ]]; then
        info "  => DIFFERENT from embedded file $ML_NAME: new BSI publication"
    else
        info "  => NEW: $ML_NAME"
        NEW_INDEX+=("$ML_NAME")
    fi
    cp "$ml" "$dir/$ML_NAME"
    PLANNED+=("$dir/$ML_NAME|$ML_NAME")
    DOC_FILES_ROWS+=("| \`$ML_NAME\` | CSCA Master List (BSI, Germany) | signer \`$signer_subject\` | signed on $signed_day; signer valid $signer_start → $signer_end | none for the file (see “Embedded Master List”) | \`$new_sha\` |")
    DOC_PROVENANCE_ROWS+=("| \`$ML_NAME\` | \`GermanMasterList.zip\` | \`$member\`, redistributed unchanged | <$BSI_ML_PAGE> | $TODAY |")
    ML_SUMMARY="file \`$member\`, $(wc -c <"$ml") bytes, signed on $signed_day, $count certificates; SHA-256 of the archive $(sha256_of "$zip")"
}

# --- Run -------------------------------------------------------------------------------------

ML_SUMMARY=""
info "Embedded trust store: $TRUST_DIR"
if ((APPLY)); then info "Mode: --apply (write on difference)"; else info "Mode: report (no write)"; fi

fetch_ants
ANTS_CHECKED=0
if ((${#ANTS_ZIPS[@]} > 0)); then
    for zip in "${ANTS_ZIPS[@]}"; do check_ants_archive "$zip"; done
    ANTS_CHECKED=1
fi
check_bsi

info ""
if ((!ANTS_CHECKED)); then
    info "WARNING: ANTS certificates not checked. The ANTS site blocks automated downloads:"
    info "download the archives from $ANTS_CSCA_PAGE"
    info "and $ANTS_EID_PAGE, then run again with --ants-dir DIR."
fi

if ((${#PLANNED[@]} == 0)); then
    if ((ANTS_CHECKED)); then
        info "Store up to date: no difference with the checked sources."
    else
        info "Master List up to date; ANTS certificates not checked (see above)."
    fi
    exit 0
fi

info "Differences: ${#PLANNED[@]} file(s)."
for entry in "${PLANNED[@]}"; do info "  - ${entry#*|}"; done

if ((APPLY)); then
    for entry in "${PLANNED[@]}"; do
        cp "${entry%%|*}" "$TRUST_DIR/${entry#*|}"
    done
    if ((${#NEW_INDEX[@]} > 0)); then
        # New ANTS certificates inserted before the first file that is not one.
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
        info "index.txt extended: ${NEW_INDEX[*]}"
    fi
    info "Files written to $TRUST_DIR."
else
    info "Report only: run again with --apply to write these files."
fi

info ""
info "Lines to copy into docs/trust-store.md, “Embedded files” table:"
printf '%s\n' "${DOC_FILES_ROWS[@]}"
info ""
info "“Provenance” table:"
printf '%s\n' "${DOC_PROVENANCE_ROWS[@]}"
if [[ -n "$ML_SUMMARY" ]]; then
    info ""
    info "“Embedded Master List” section: $ML_SUMMARY."
    info "Recount the issuers and update the description of the contents."
fi
info ""
info "Then: ./gradlew :core:test (TrustStoreFingerprintTest, TrustStoreTest)."
