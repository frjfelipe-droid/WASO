#!/usr/bin/env bash
# Teste automático no emulador: instala, abre a Ana em modo de teste e procura falhas.
set -x
PKG=com.ana.assistente
adb install -r -g Ana.apk || exit 1
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +$PKG
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez selftest true
for i in $(seq 1 50); do
  sleep 3
  if adb logcat -d | grep -q "ANA_SELFTEST_DONE"; then break; fi
done
sleep 3
adb logcat -d > logcat.txt
echo "===== mensagens da Ana ====="
grep -E " Ana *:|AndroidRuntime|chromium.*Uncaught|DEBUG *:" logcat.txt | tail -n 150
if grep -E "FATAL EXCEPTION|Fatal signal|ANR in $PKG" logcat.txt; then
  echo "===== FALHA ENCONTRADA ====="
  grep -E "FATAL EXCEPTION|Fatal signal|ANR in" -A 60 logcat.txt | head -n 200
  exit 1
fi
grep -q "ANA_SELFTEST_DONE" logcat.txt || { echo "O teste não terminou"; exit 1; }
adb shell pidof $PKG || { echo "O app não está mais rodando"; exit 1; }
echo "TESTE OK"
