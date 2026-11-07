#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_DIR/app/src/main/assets/bundled_components"
RELEASE_URL="https://github.com/jaredgei/wow-forever-android/releases/download/components-v1"
DXVK_X64_URL="https://downloads.gamenative.app/dxwrapper/dxvk-2.4.1.tzst"
DXVK_X64_SHA256="f827bf18ef42d90be4637a987226453e392386a1a6ff160c13d3df52a62f2522"
FEXCORE_URL="https://github.com/The412Banner/winlator-contents/releases/download/fexcore-v1/FEXCore-2608-stable.wcp"
FEXCORE_SHA256="baa16434da07fe824e2eb6da458aa7aef786d109cf3408c84d73ec008a9484f4"

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

# Fetch and package x64 DXVK (dxvk-2.4.1-x64.wcp & assets/dxvk_x64)
DXVK_ASSETS="$REPO_DIR/app/src/main/assets/dxvk_x64"
mkdir -p "$DXVK_ASSETS"
if [ -s "$DEST/dxvk-2.4.1-x64.wcp" ] && [ -s "$DXVK_ASSETS/d3d11.dll" ] && [ -s "$DXVK_ASSETS/dxgi.dll" ]; then
    echo "have dxvk-2.4.1-x64.wcp and extracted x64 DXVK assets"
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

    # Copy x64 DXVK directly to assets for runtime fallback injection
    cp "$TMP_DIR/staging/system32/d3d11.dll" "$DXVK_ASSETS/d3d11.dll"
    cp "$TMP_DIR/staging/system32/dxgi.dll" "$DXVK_ASSETS/dxgi.dll"

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
    echo "Created dxvk-2.4.1-x64.wcp and extracted assets successfully"
fi

# Fetch and package FEXCore (fexcore-2608.wcp & assets/fexcore)
FEX_ASSETS="$REPO_DIR/app/src/main/assets/fexcore"
mkdir -p "$FEX_ASSETS"
if [ -s "$DEST/fexcore-2608.wcp" ] && [ -s "$FEX_ASSETS/libarm64ecfex.dll" ] && [ -s "$FEX_ASSETS/libwow64fex.dll" ]; then
    echo "have fexcore-2608.wcp and extracted fexcore assets"
else
    echo "Fetching FEXCore 2608..."
    TMP_DIR=$(mktemp -d)
    curl -fL --retry 3 -o "$TMP_DIR/fexcore.wcp" "$FEXCORE_URL"
    if command -v sha256sum >/dev/null 2>&1; then
        echo "$FEXCORE_SHA256  $TMP_DIR/fexcore.wcp" | sha256sum -c -
    else
        echo "$FEXCORE_SHA256  $TMP_DIR/fexcore.wcp" | shasum -a 256 -c -
    fi

    # Unpack FEXCore DLLs to assets for direct injection into system32
    tar -I zstd -xf "$TMP_DIR/fexcore.wcp" -C "$TMP_DIR" ./system32/libarm64ecfex.dll ./system32/libwow64fex.dll
    cp "$TMP_DIR/system32/libarm64ecfex.dll" "$FEX_ASSETS/libarm64ecfex.dll"
    cp "$TMP_DIR/system32/libwow64fex.dll" "$FEX_ASSETS/libwow64fex.dll"

    cp "$TMP_DIR/fexcore.wcp" "$DEST/fexcore-2608.wcp"
    cp "$TMP_DIR/fexcore.wcp" "$DEST/FEXCore-2608-stable.wcp"
    rm -rf "$TMP_DIR"
    echo "Created fexcore-2608.wcp and extracted assets successfully"
fi
