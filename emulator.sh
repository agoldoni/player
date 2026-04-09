#!/bin/bash
# Lancia l'emulatore Android, compila e installa l'app
# Uso: ./emulator.sh [nome_avd]
# Se non specificato, usa il primo AVD disponibile

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

# Seleziona AVD
if [ -n "$1" ]; then
    AVD_NAME="$1"
else
    AVD_NAME=$("$EMULATOR" -list-avds 2>/dev/null | head -1)
fi

if [ -z "$AVD_NAME" ]; then
    echo "Nessun AVD trovato. Crea un emulatore con Android Studio."
    exit 1
fi

echo "=== AVD: $AVD_NAME ==="

# Avvia emulatore in background (se non già in esecuzione)
if ! "$ADB" devices 2>/dev/null | grep -q "emulator-"; then
    echo "Avvio emulatore..."
    "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-load &
    EMULATOR_PID=$!

    echo "Attesa avvio emulatore..."
    "$ADB" wait-for-device
    # Attende che il boot sia completato
    while [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        sleep 2
    done
    echo "Emulatore pronto."
else
    echo "Emulatore già in esecuzione."
fi

# Build
echo "=== Build debug ==="
cd "$PROJECT_DIR" && ./gradlew assembleDebug
if [ $? -ne 0 ]; then
    echo "Build fallita!"
    exit 1
fi

# Installa
echo "=== Installazione APK ==="
"$ADB" install -r "$APK"

# Avvia app
echo "=== Avvio app ==="
"$ADB" shell am start -n com.example.player.debug/com.example.player.MainActivity

echo "=== Done ==="
