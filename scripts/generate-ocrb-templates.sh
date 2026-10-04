#!/usr/bin/env bash
# Sceau - régénère les modèles OCR-B de :mrz depuis la police versionnée (D32, lot B).
#
# Usage : scripts/generate-ocrb-templates.sh
# Prérequis : un JDK 17 ou ultérieur (lancement d'un programme Java en un seul fichier).
# Vérifie l'empreinte de la police, écrit mrz/src/main/resources/.../ocrb-templates.bin et
# affiche son empreinte SHA-256, à reporter dans mrz/README.md (OcrbTemplatesFingerprintTest).
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
font="$repo/mrz/fonts/ocrb10.otf"
font_sha256=6270b592af1cb319fe4122777d14fdbfc4a2edd111c6f87af3f4ac04c82ba48a
output="$repo/mrz/src/main/resources/io/github/mgdx/sceau/mrz/ocrb-templates.bin"

actual=$(sha256sum "$font" | cut -d' ' -f1)
if [[ "$actual" != "$font_sha256" ]]; then
    echo "Empreinte inattendue pour $font : $actual" >&2
    exit 1
fi
mkdir -p "$(dirname "$output")"
java -Djava.awt.headless=true "$repo/scripts/GenerateOcrbTemplates.java" "$font" "$output"
