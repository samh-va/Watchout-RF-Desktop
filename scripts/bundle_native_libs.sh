#!/bin/bash
# Copies librtlsdr and libusb from Homebrew into resources/ so the packaged app
# runs on Macs that do not have Homebrew or librtlsdr installed.
# Run automatically by Gradle before packaging (see build.gradle.kts).
set -euo pipefail
cd "$(dirname "$0")/.."

BREW="$(command -v brew || true)"
[ -z "$BREW" ] && [ -x /opt/homebrew/bin/brew ] && BREW=/opt/homebrew/bin/brew
[ -z "$BREW" ] && [ -x /usr/local/bin/brew ] && BREW=/usr/local/bin/brew
if [ -z "$BREW" ]; then
    echo "Homebrew not found. Install it, then: brew install librtlsdr" >&2
    exit 1
fi
PREFIX="$("$BREW" --prefix)"

if [ "$(uname -m)" = "arm64" ]; then DEST="resources/macos-arm64"; else DEST="resources/macos-x64"; fi

RTL_SRC="$PREFIX/opt/librtlsdr/lib/librtlsdr.dylib"
if [ ! -e "$RTL_SRC" ]; then
    echo "librtlsdr not found. Install it with: brew install librtlsdr" >&2
    exit 1
fi
# The libusb path librtlsdr was linked against
USB_REF="$(otool -L "$RTL_SRC" | awk '/libusb/ {print $1}')"

mkdir -p "$DEST"
cp -fL "$RTL_SRC" "$DEST/librtlsdr.dylib"
cp -fL "$USB_REF" "$DEST/libusb-1.0.0.dylib"
chmod u+w "$DEST"/*.dylib

# Make the pair relocatable: librtlsdr finds libusb next to itself
install_name_tool -id "@loader_path/librtlsdr.dylib" "$DEST/librtlsdr.dylib"
install_name_tool -change "$USB_REF" "@loader_path/libusb-1.0.0.dylib" "$DEST/librtlsdr.dylib"
install_name_tool -id "@loader_path/libusb-1.0.0.dylib" "$DEST/libusb-1.0.0.dylib"

# Editing a Mach-O invalidates its signature; Apple Silicon refuses unsigned code
codesign --force --sign - "$DEST/libusb-1.0.0.dylib" "$DEST/librtlsdr.dylib"

echo "Bundled native libraries into $DEST"
