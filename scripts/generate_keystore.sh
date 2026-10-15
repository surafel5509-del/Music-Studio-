#!/bin/bash
# Generates a debug keystore for release builds.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR/.."
if [ -f app/release.keystore ]; then
    echo "app/release.keystore already exists; remove it first to regenerate."
    exit 1
fi
keytool -genkeypair -v \
  -keystore app/release.keystore \
  -alias wavestudio \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass wavestudio \
  -keypass wavestudio \
  -dname "CN=WaveStudio, O=WaveStudio, L=Mobile, ST=CA, C=US"
echo "Keystore created at app/release.keystore (password: wavestudio)"
