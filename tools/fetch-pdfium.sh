#!/usr/bin/env bash
# Downloads the prebuilt PDFium libraries (bblanchon/pdfium-binaries, MIT packaging,
# PDFium itself BSD-3-Clause / Apache-2.0) into pdfium/prebuilt/.
# Pinned version and checksums: bump them together.
set -euo pipefail

VERSION="chromium/8179"
declare -A SHA256=(
  [arm64]="e74f8d3deb968dcf7e09a15152f19393df932ffc4e519149e61e8e0ee15c9753"
  [arm]="e4270c933ba496681cde6274389b598d97383403acb75ec10fe8b8de1aa51007"
  [x64]="fd7f3afdae094549e77b44a95e88ff00cb59e84ba5eb5fc79470ccf10c85ccf9"
)
declare -A ABI=( [arm64]="arm64-v8a" [arm]="armeabi-v7a" [x64]="x86_64" )

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/pdfium/prebuilt"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

rm -rf "$OUT"
mkdir -p "$OUT/jniLibs"
for arch in arm64 arm x64; do
  file="pdfium-android-$arch.tgz"
  curl -fsSL -o "$TMP/$file" \
    "https://github.com/bblanchon/pdfium-binaries/releases/download/${VERSION/\//%2F}/$file"
  echo "${SHA256[$arch]}  $TMP/$file" | sha256sum -c --quiet -
  mkdir -p "$TMP/$arch"
  tar -xzf "$TMP/$file" -C "$TMP/$arch"
  mkdir -p "$OUT/jniLibs/${ABI[$arch]}"
  cp "$TMP/$arch/lib/libpdfium.so" "$OUT/jniLibs/${ABI[$arch]}/"
done
cp -r "$TMP/arm64/include" "$OUT/include"
mkdir -p "$OUT/assets"
cp -r "$TMP/arm64/licenses" "$OUT/assets/licenses"
cp "$TMP/arm64/LICENSE" "$OUT/assets/licenses/pdfium-binaries.txt"
echo "$VERSION" > "$OUT/VERSION"
echo "PDFium $VERSION ready in $OUT"
