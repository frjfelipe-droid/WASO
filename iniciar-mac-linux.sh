#!/usr/bin/env bash
# Uso: ./iniciar-mac-linux.sh            (so no PC)
#      ./iniciar-mac-linux.sh --celular  (libera acesso pelo celular no mesmo Wi-Fi)
cd "$(dirname "$0")"
PY=$(command -v python3 || command -v python)
if [ -z "$PY" ]; then echo "Instale o Python 3: https://www.python.org/downloads/"; exit 1; fi
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "Instalando o FFmpeg (imageio-ffmpeg)..."
  "$PY" -m pip install --quiet --user imageio-ffmpeg || echo "Instale o ffmpeg: brew install ffmpeg  /  sudo apt install ffmpeg"
fi
exec "$PY" cortador.py "$@"
