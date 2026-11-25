#!/usr/bin/env bash
# Downloads the Tesseract OCR models used by the app (tesseract-ocr/tessdata_fast, Apache-2.0)
# into ocr/prebuilt/assets/, which the app packages as assets. Pinned commit and checksums.
set -euo pipefail

COMMIT="87416418657359cb625c412a48b6e1d6d41c29bd"
declare -A SHA256=(
  [eng]="7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
  [por]="c4932b937207a9514b7514d518b931a99938c02a28a5a5a553f8599ed58b7deb"
)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/ocr/prebuilt/assets"
BASE="https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$COMMIT"

rm -rf "$ROOT/ocr/prebuilt"
mkdir -p "$OUT/tessdata" "$OUT/licenses"
for lang in eng por; do
  curl -fsSL -o "$OUT/tessdata/$lang.traineddata" "$BASE/$lang.traineddata"
  echo "${SHA256[$lang]}  $OUT/tessdata/$lang.traineddata" | sha256sum -c --quiet -
done
curl -fsSL -o "$OUT/licenses/tessdata_fast.txt" "$BASE/LICENSE"
echo "Tesseract models ($COMMIT) ready in $OUT"
