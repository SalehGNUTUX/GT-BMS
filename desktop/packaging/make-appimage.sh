#!/bin/sh
# Builds GT-BMS-desktop-<version>-x86_64.AppImage from the app image made by
#   ./gradlew :desktop:packageAppImage
# appimagetool is downloaded once (pinned release, checked against its SHA-256) into
# desktop/build/tools. Usage: desktop/packaging/make-appimage.sh [version]
set -eu
cd "$(dirname "$0")/../.."
VERSION="${1:-$(sed -n "s/^version = '\(.*\)'/\1/p" desktop/build.gradle)}"
TOOL_VERSION=1.9.0
# No checksum is published upstream: this is the one first downloaded (2026-11-26)
TOOL_SHA256=46fdd785094c7f6e545b61afcfb0f3d98d8eab243f644b4b17698c01d06083d1
APP=desktop/build/compose/binaries/main/app/gt-bms-desktop
TOOLS=desktop/build/tools
OUT=desktop/build/appimage
[ -x "$APP/bin/gt-bms-desktop" ] || { echo "Run ./gradlew :desktop:packageAppImage first" >&2; exit 1; }

mkdir -p "$TOOLS"
TOOL="$TOOLS/appimagetool-$TOOL_VERSION"
if [ ! -f "$TOOL" ]; then
    curl -fsSL -o "$TOOL.part" "https://github.com/AppImage/appimagetool/releases/download/$TOOL_VERSION/appimagetool-x86_64.AppImage"
    mv "$TOOL.part" "$TOOL"
fi
echo "$TOOL_SHA256  $TOOL" | sha256sum -c - >/dev/null || { echo "appimagetool checksum mismatch" >&2; rm -f "$TOOL"; exit 1; }
chmod +x "$TOOL"

DIR="$OUT/GT-BMS.AppDir"
rm -rf "$DIR"; mkdir -p "$DIR"
cp -a "$APP" "$DIR/gt-bms-desktop"
cp desktop/src/main/resources/icon.png "$DIR/gt-bms-desktop.png"
cp desktop/packaging/gt-bms-desktop.desktop "$DIR/gt-bms-desktop.desktop"
cat > "$DIR/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/gt-bms-desktop/bin/gt-bms-desktop" "$@"
RUN
chmod +x "$DIR/AppRun"

FILE="$OUT/GT-BMS-desktop-$VERSION-x86_64.AppImage"
ARCH=x86_64 "$TOOL" --appimage-extract-and-run --no-appstream "$DIR" "$FILE" >/dev/null
echo "$FILE"
