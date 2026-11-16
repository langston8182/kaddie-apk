#!/usr/bin/env bash
# Construit la version PROD signée et l'installe par-dessus l'app installée (données et connexion conservées).
# Usage : scripts/install-prod.sh [numéro-de-série-adb]
set -euo pipefail

cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
APK="app/build/outputs/apk/prod/release/app-prod-release.apk"

# adb n'est pas toujours dans le PATH d'un terminal : repli sur le SDK Android.
if ! command -v adb >/dev/null 2>&1; then
  export PATH="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools:$PATH"
fi

./gradlew :app:assembleProdRelease

if [[ ! -f "$APK" ]]; then
  echo "APK signé introuvable ($APK) : vérifier kaddie.release.* dans local.properties." >&2
  exit 1
fi

SERIAL="${1:-}"
if [[ -z "$SERIAL" ]]; then
  DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  COUNT=$(printf '%s\n' "$DEVICES" | grep -c . || true)
  if [[ "$COUNT" -eq 0 ]]; then echo "Aucun appareil connecté (adb devices)." >&2; exit 1; fi
  if [[ "$COUNT" -gt 1 ]]; then echo "Plusieurs appareils : relancer avec le numéro de série en argument." >&2; echo "$DEVICES" >&2; exit 1; fi
  SERIAL="$DEVICES"
fi

adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell am start -n com.kaddie.app/.MainActivity >/dev/null
echo "Kaddie (prod) installé et lancé sur $SERIAL."
