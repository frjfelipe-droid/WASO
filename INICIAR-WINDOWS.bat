@echo off
chcp 65001 >nul
title WASO - Cortador Automatico
cd /d "%~dp0"

set PY=
where py >nul 2>nul && set PY=py
if "%PY%"=="" ( where python >nul 2>nul && set PY=python )
if "%PY%"=="" (
  echo.
  echo  [!] Python nao encontrado.
  echo      Instale em https://www.python.org/downloads/
  echo      IMPORTANTE: marque "Add Python to PATH" na instalacao.
  echo.
  pause
  exit /b 1
)

where ffmpeg >nul 2>nul
if errorlevel 1 (
  if not exist "ffmpeg\bin\ffmpeg.exe" (
    echo Instalando o FFmpeg pela primeira vez, aguarde...
    %PY% -m pip install --quiet --disable-pip-version-check imageio-ffmpeg
  )
)

%PY% cortador.py %*
pause
