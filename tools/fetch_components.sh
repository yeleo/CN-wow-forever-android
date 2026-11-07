#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_DIR/app/src/main/assets/bundled_components"
RELEASE_URL="https://github.com/jaredgei/wow-forever-android/releases/download/components-v1"
DXVK_X64_URL="https://downloads.gamenative.app/dxwrapper/dxvk-2.4.1.tzst"
DXVK_X64_SHA256="f827bf18ef42d90be4637a987226453e392386a1a6ff160c13d3df52a62f2522"

FILES=(
    proton-11.0-90624-arm64ec.wcp
    dxvk-2.4.1-wow-aarch64-test.wcp
    turnip-wow-scheduler-test.zip
    turnip-V32-RP6sched.zip
)

mkdir -p "$DEST"
for f in "${FILES[@]}"; do
    if [ -s "$DEST/$f" ]; then
        echo "have $f"
    elif [ -n "${1:-}" ] && [ -s "$1/$f" ]; then
        cp "$1/$f" "$DEST/$f"
        echo "copied $f"
    else
        if [ "$f" = "turnip-V32-RP6sched.zip" ]; then
            curl -fL --retry 3 -o "$DEST/$f.part" "https://raw.githubusercontent.com/arusiasotto/wow-forever-a840/main/driver/turnip-V32-RP6sched.zip"
        else
            curl -fL --retry 3 -o "$DEST/$f.part" "$RELEASE_URL/$f"
        fi
        mv "$DEST/$f.part" "$DEST/$f"
        echo "downloaded $f"
    fi
done

if command -v sha256sum >/dev/null 2>&1; then
    (cd "$DEST" && sha256sum -c "$REPO_DIR/tools/components.sha256")
else
    (cd "$DEST" && shasum -a 256 -c "$REPO_DIR/tools/components.sha256")
fi

# Fetch and package x64 DXVK (dxvk-2.4.1-x64.wcp)
if [ -s "$DEST/dxvk-2.4.1-x64.wcp" ]; then
    echo "have dxvk-2.4.1-x64.wcp"
else
    echo "Fetching x64 DXVK 2.4.1..."
    TMP_DIR=$(mktemp -d)
    curl -fL --retry 3 -o "$TMP_DIR/dxvk-2.4.1.tzst" "$DXVK_X64_URL"
    if command -v sha256sum >/dev/null 2>&1; then
        echo "$DXVK_X64_SHA256  $TMP_DIR/dxvk-2.4.1.tzst" | sha256sum -c -
    else
        echo "$DXVK_X64_SHA256  $TMP_DIR/dxvk-2.4.1.tzst" | shasum -a 256 -c -
    fi

    mkdir -p "$TMP_DIR/staging/system32"
    tar -I zstd -xf "$TMP_DIR/dxvk-2.4.1.tzst" -C "$TMP_DIR/staging" ./system32/d3d11.dll ./system32/dxgi.dll

    cat << 'EOF' > "$TMP_DIR/staging/profile.json"
{
  "type": "DXVK",
  "versionName": "2.4.1-x64",
  "versionCode": 1,
  "desc": "DXVK 2.4.1 x86_64 for WoW",
  "fileList": [
    {
      "source": "system32/d3d11.dll",
      "target": "${system32}/d3d11.dll"
    },
    {
      "source": "system32/dxgi.dll",
      "target": "${system32}/dxgi.dll"
    }
  ]
}
EOF
    (cd "$TMP_DIR/staging" && tar -I zstd -cf "$DEST/dxvk-2.4.1-x64.wcp" profile.json system32/d3d11.dll system32/dxgi.dll)
    rm -rf "$TMP_DIR"
    echo "Created dxvk-2.4.1-x64.wcp successfully"
fi
