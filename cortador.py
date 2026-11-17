#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WASO - Cortador Automatico de Videos (CapCut celular e PC)

Olha seus videos brutos, aplica as regras que voce definir (remover silencios,
cortar inicio/fim, formato vertical 9:16, velocidade, dividir em partes...) e
corta tudo sozinho usando o FFmpeg. O resultado sai pronto para abrir no CapCut
(celular ou PC): MP4 H.264 + AAC, taxa de quadros constante, faststart.

Uso:
    python cortador.py              -> abre a interface no navegador
    python cortador.py --celular    -> libera acesso pelo celular na mesma rede Wi-Fi
    python cortador.py --porta 9000 --sem-navegador

So usa a biblioteca padrao do Python. Precisa do FFmpeg (no PATH, numa pasta
"ffmpeg" ao lado deste arquivo, ou via `pip install imageio-ffmpeg`).
"""

import argparse
import json
import math
import mimetypes
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

APP_DIR = Path(__file__).resolve().parent
UI_FILE = APP_DIR / "ui.html"
CONFIG_FILE = APP_DIR / "config.json"
RECIPES_FILE = APP_DIR / "receitas.json"
VIDEO_EXTS = {".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm", ".mts", ".m2ts", ".3gp", ".wmv", ".flv"}
IS_WINDOWS = os.name == "nt"
NO_WINDOW = 0x08000000 if IS_WINDOWS else 0  # CREATE_NO_WINDOW

# --------------------------------------------------------------------------
# Regras padrao e receitas prontas
# --------------------------------------------------------------------------

DEFAULT_RULES = {
    "remover_silencio": True,
    "limiar_db": -35,          # abaixo disso e considerado silencio
    "silencio_min": 0.6,       # silencio precisa durar pelo menos isso (s)
    "margem": 0.15,            # respiro mantido antes/depois da fala (s)
    "trecho_min": 0.4,         # trechos falados menores que isso sao descartados (s)
    "cortar_inicio": 0.0,      # remove X segundos do comeco de cada video
    "cortar_fim": 0.0,         # remove X segundos do final de cada video
    "formato": "original",     # original | 9x16 | 16x9 | 1x1 | 4x5
    "ajuste": "desfoque",      # cortar | desfoque | barras
    "velocidade": 1.0,
    "destino": "celular",      # celular | pc
    "resolucao": 1080,         # lado menor: 720 | 1080 | 1440 | 2160
    "fps": 30,                 # 24 | 25 | 30 | 50 | 60
    "normalizar_audio": True,
    "modo_saida": "por_video", # por_video | juntar_tudo
    "dividir_cada": 0,         # 0 = nao dividir; senao divide em partes de ate N s (corta nos silencios)
    "exportar_clipes": False,  # tambem salva cada trecho como arquivo separado
    "prefixo": "",
}

BUILTIN_RECIPES = {
    "Vlog / YouTube - remover silencios": {
        **DEFAULT_RULES,
    },
    "TikTok / Reels / Shorts 9:16": {
        **DEFAULT_RULES,
        "formato": "9x16", "ajuste": "cortar", "silencio_min": 0.4,
        "margem": 0.08, "velocidade": 1.1, "dividir_cada": 0,
    },
    "Cortes de podcast 60s (9:16)": {
        **DEFAULT_RULES,
        "formato": "9x16", "ajuste": "desfoque", "dividir_cada": 60,
        "silencio_min": 0.5, "margem": 0.1,
    },
    "Clipes separados p/ montar no CapCut": {
        **DEFAULT_RULES,
        "exportar_clipes": True, "normalizar_audio": False,
    },
}

ASPECTS = {"9x16": (9, 16), "16x9": (16, 9), "1x1": (1, 1), "4x5": (4, 5)}

# --------------------------------------------------------------------------
# FFmpeg
# --------------------------------------------------------------------------


def find_ffmpeg():
    env = os.environ.get("FFMPEG")
    if env and Path(env).exists():
        return env
    exe = "ffmpeg.exe" if IS_WINDOWS else "ffmpeg"
    for cand in (APP_DIR / "ffmpeg" / "bin" / exe, APP_DIR / "ffmpeg" / exe, APP_DIR / exe):
        if cand.exists():
            return str(cand)
    found = shutil.which("ffmpeg")
    if found:
        return found
    try:
        import imageio_ffmpeg  # type: ignore
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


FFMPEG = find_ffmpeg()


class Cancelled(Exception):
    pass


def run_ffmpeg(args, job=None, on_line=None):
    """Roda o ffmpeg; chama on_line para cada linha do stderr. Retorna stderr completo."""
    if not FFMPEG:
        raise RuntimeError("FFmpeg nao encontrado. Veja o README (instalar FFmpeg).")
    cmd = [FFMPEG, "-hide_banner", "-nostdin"] + args
    proc = subprocess.Popen(
        cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
        creationflags=NO_WINDOW,
    )
    if job is not None:
        job.proc = proc
    lines = []
    buf = b""
    while True:
        chunk = proc.stderr.read(4096)
        if not chunk:
            break
        buf += chunk
        parts = re.split(rb"[\r\n]", buf)
        buf = parts.pop()
        for p in parts:
            line = p.decode("utf-8", "replace")
            lines.append(line)
            if on_line:
                on_line(line)
        if job is not None and job.cancel:
            proc.kill()
    if buf:
        lines.append(buf.decode("utf-8", "replace"))
    proc.wait()
    if job is not None:
        job.proc = None
        if job.cancel:
            raise Cancelled()
    out = "\n".join(lines)
    if proc.returncode != 0:
        tail = "\n".join([l for l in lines if l.strip()][-12:])
        raise RuntimeError("FFmpeg falhou:\n" + tail)
    return out


_probe_cache = {}


def probe(path):
    """Le duracao, resolucao (ja considerando rotacao), fps e se tem audio."""
    path = Path(path)
    st = path.stat()
    key = (str(path), st.st_mtime, st.st_size)
    if key in _probe_cache:
        return _probe_cache[key]
    if not FFMPEG:
        raise RuntimeError("FFmpeg nao encontrado.")
    p = subprocess.run([FFMPEG, "-hide_banner", "-nostdin", "-i", str(path)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                       creationflags=NO_WINDOW)
    txt = p.stderr.decode("utf-8", "replace")
    info = {"duracao": 0.0, "largura": 0, "altura": 0, "fps": 30.0, "audio": False, "video": False}
    m = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", txt)
    if m:
        info["duracao"] = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))
    for line in txt.splitlines():
        if "Video:" in line and "attached pic" not in line and not info["video"]:
            info["video"] = True
            mm = re.search(r"[ ,](\d{2,5})x(\d{2,5})[ ,\[]", line)
            if mm:
                info["largura"], info["altura"] = int(mm.group(1)), int(mm.group(2))
            mf = re.search(r"(\d+(?:\.\d+)?) fps", line) or re.search(r"(\d+(?:\.\d+)?) tbr", line)
            if mf:
                info["fps"] = float(mf.group(1))
        if "Audio:" in line:
            info["audio"] = True
    rot = re.search(r"rotation of (-?\d+(?:\.\d+)?) degrees", txt) or re.search(r"rotate\s*:\s*(-?\d+)", txt)
    if rot and abs(round(float(rot.group(1)))) % 180 == 90:
        info["largura"], info["altura"] = info["altura"], info["largura"]
    if not info["video"]:
        raise RuntimeError(f"'{path.name}' nao parece ser um video.")
    _probe_cache[key] = info
    return info


def parse_time(s):
    m = re.search(r"time=(\d+):(\d+):(\d+(?:\.\d+)?)", s)
    if not m:
        return None
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))


# --------------------------------------------------------------------------
# Analise: detectar silencios e montar os trechos
# --------------------------------------------------------------------------


def detect_silences(path, rules, info, job=None, on_progress=None):
    if not info["audio"]:
        return []
    th = float(rules.get("limiar_db", -35))
    dmin = max(0.05, float(rules.get("silencio_min", 0.6)))
    silences = []
    cur = {}

    def on_line(line):
        m = re.search(r"silence_start:\s*(-?\d+(?:\.\d+)?)", line)
        if m:
            cur["s"] = max(0.0, float(m.group(1)))
        m = re.search(r"silence_end:\s*(-?\d+(?:\.\d+)?)", line)
        if m:
            silences.append((cur.pop("s", 0.0), float(m.group(1))))
        t = parse_time(line)
        if t is not None and on_progress and info["duracao"] > 0:
            on_progress(min(1.0, t / info["duracao"]))

    run_ffmpeg(["-i", str(path), "-map", "0:a:0", "-vn", "-sn", "-dn",
                "-af", f"silencedetect=noise={th}dB:d={dmin}", "-f", "null", "-"],
               job=job, on_line=on_line)
    if "s" in cur:  # silencio ate o fim do arquivo
        silences.append((cur["s"], info["duracao"]))
    return silences


def build_intervals(duration, silences, rules):
    """Retorna lista de intervalos {s, e, keep} cobrindo o video inteiro."""
    start = max(0.0, float(rules.get("cortar_inicio", 0) or 0))
    end = duration - max(0.0, float(rules.get("cortar_fim", 0) or 0))
    if end - start < 0.1:
        return [{"s": 0.0, "e": round(duration, 3), "keep": False}]
    pad = max(0.0, float(rules.get("margem", 0.15)))
    min_seg = max(0.0, float(rules.get("trecho_min", 0.4)))

    keeps = []
    if rules.get("remover_silencio", True) and silences:
        cursor = start
        for (s0, s1) in sorted(silences):
            # encolhe o silencio pela margem (mantem respiro em volta da fala)
            cs, ce = s0 + pad, s1 - pad
            if s0 <= start + 0.01:
                cs = start
            if s1 >= duration - 0.01:
                ce = end
            if ce <= cs:
                continue
            if cs > cursor:
                keeps.append([cursor, min(cs, end)])
            cursor = max(cursor, ce)
            if cursor >= end:
                break
        if cursor < end:
            keeps.append([cursor, end])
    else:
        keeps = [[start, end]]

    # junta sobreposicoes e descarta trechos muito curtos
    merged = []
    for s, e in keeps:
        s, e = max(s, start), min(e, end)
        if e <= s:
            continue
        if merged and s <= merged[-1][1] + 0.02:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])
    merged = [k for k in merged if k[1] - k[0] >= min_seg]

    out, cursor = [], 0.0
    for s, e in merged:
        if s > cursor + 0.001:
            out.append({"s": round(cursor, 3), "e": round(s, 3), "keep": False})
        out.append({"s": round(s, 3), "e": round(e, 3), "keep": True})
        cursor = e
    if cursor < duration - 0.001:
        out.append({"s": round(cursor, 3), "e": round(duration, 3), "keep": False})
    return out


def analyze_file(path, rules, job=None, on_progress=None):
    info = probe(path)
    sil = detect_silences(path, rules, info, job, on_progress) if rules.get("remover_silencio", True) else []
    iv = build_intervals(info["duracao"], sil, rules)
    kept = sum(i["e"] - i["s"] for i in iv if i["keep"])
    return {"info": info, "intervalos": iv, "mantido": round(kept, 2)}


# --------------------------------------------------------------------------
# Renderizacao
# --------------------------------------------------------------------------


def even(x):
    x = int(round(x))
    return max(2, x - (x % 2))


def target_size(rules, info):
    res = int(rules.get("resolucao", 1080))
    fmt = rules.get("formato", "original")
    if fmt in ASPECTS:
        aw, ah = ASPECTS[fmt]
        if aw <= ah:
            return even(res), even(res * ah / aw)
        return even(res * aw / ah), even(res)
    w, h = info["largura"] or 1920, info["altura"] or 1080
    short = min(w, h)
    if short > res:
        f = res / short
        w, h = w * f, h * f
    return even(w), even(h)


def atempo_chain(speed):
    parts, s = [], speed
    while s > 2.0:
        parts.append("atempo=2.0")
        s /= 2.0
    while s < 0.5:
        parts.append("atempo=0.5")
        s /= 0.5
    parts.append(f"atempo={s:.4f}")
    return ",".join(parts)


def encode_settings(rules):
    if rules.get("destino", "celular") == "pc":
        return ["-c:v", "libx264", "-preset", "medium", "-crf", "18"]
    # celular: arquivos menores e leves para o CapCut mobile
    return ["-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
            "-maxrate", "14M", "-bufsize", "28M"]


def render_segment(src, info, seg_s, seg_e, rules, W, H, fps, out_path, job):
    dur = seg_e - seg_s
    speed = min(3.0, max(0.5, float(rules.get("velocidade", 1.0) or 1.0)))
    # duracao exata em quadros inteiros (evita sobras ao juntar os trechos)
    out_dur = max(1, round(dur / speed * fps)) / fps
    fit = rules.get("ajuste", "desfoque")
    fmt = rules.get("formato", "original")

    v = "[0:v:0]setpts=PTS-STARTPTS"
    if abs(speed - 1.0) > 1e-3:
        v += f",setpts=PTS/{speed:.4f}"
    v += f",fps={fps}"
    if fmt != "original" and fit == "desfoque":
        bw, bh = even(W / 4), even(H / 4)
        v += (f",split=2[b][f];[b]scale={bw}:{bh}:force_original_aspect_ratio=increase,"
              f"crop={bw}:{bh},boxblur=10:2,scale={W}:{H}[bg];"
              f"[f]scale={W}:{H}:force_original_aspect_ratio=decrease[fg];"
              f"[bg][fg]overlay=(W-w)/2:(H-h)/2")
    elif fmt != "original" and fit == "cortar":
        v += f",scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H}"
    else:
        v += (f",scale={W}:{H}:force_original_aspect_ratio=decrease,"
              f"pad={W}:{H}:(ow-iw)/2:(oh-ih)/2:black")
    v += ",setsar=1,format=yuv420p[v]"

    fade = 0.015 if out_dur > 0.1 else 0.0
    a_in = "[0:a:0]" if info["audio"] else "[1:a]"
    a = f"{a_in}asetpts=PTS-STARTPTS"
    if abs(speed - 1.0) > 1e-3:
        a += "," + atempo_chain(speed)
    a += ",aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo"
    if fade:
        a += f",afade=t=in:d={fade},afade=t=out:st={max(0, out_dur - fade):.3f}:d={fade}"
    a += "[a]"

    args = ["-y", "-ss", f"{seg_s:.3f}", "-t", f"{dur:.3f}", "-i", str(src)]
    if not info["audio"]:
        args += ["-f", "lavfi", "-t", f"{dur:.3f}", "-i", "anullsrc=r=48000:cl=stereo"]
    args += ["-filter_complex", v + ";" + a, "-map", "[v]", "-map", "[a]",
             "-t", f"{out_dur:.3f}"]
    args += encode_settings(rules)
    args += ["-pix_fmt", "yuv420p", "-profile:v", "high", "-r", str(fps),
             "-g", str(int(fps * 2)), "-c:a", "aac", "-b:a", "192k", "-ar", "48000",
             "-ac", "2", "-movflags", "+faststart", str(out_path)]
    run_ffmpeg(args, job=job)
    return out_dur


def concat_clips(clips, out_path, rules, job):
    lst = out_path.with_suffix(".lista.txt")
    with open(lst, "w", encoding="utf-8") as f:
        for c in clips:
            p = str(Path(c).resolve()).replace("\\", "/").replace("'", "'\\''")
            f.write(f"file '{p}'\n")
    args = ["-y", "-f", "concat", "-safe", "0", "-i", str(lst), "-map", "0:v", "-map", "0:a",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-ar", "48000"]
    if rules.get("normalizar_audio", True):
        args += ["-af", "loudnorm=I=-14:TP=-1.5:LRA=11,aresample=48000"]
    args += ["-movflags", "+faststart", str(out_path)]
    try:
        run_ffmpeg(args, job=job)
    finally:
        try:
            lst.unlink()
        except OSError:
            pass


def split_into_parts(items, max_len, speed):
    """Agrupa trechos em partes de ate max_len segundos (cortando so entre trechos)."""
    if not max_len or max_len <= 0:
        return [items]
    parts, cur, acc = [], [], 0.0
    for it in items:
        d = (it[3] - it[2]) / speed  # it = (clip, nome, s, e)
        if cur and acc + d > max_len:
            parts.append(cur)
            cur, acc = [], 0.0
        cur.append(it)
        acc += d
    if cur:
        parts.append(cur)
    return parts


def safe_name(s):
    s = re.sub(r"[^\w\-. ]+", "_", s, flags=re.UNICODE).strip()
    return s or "video"


def render_job(job, files, rules, analyses, in_dir, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    tmp = out_dir / f".tmp_{job.id}"
    tmp.mkdir(parents=True, exist_ok=True)
    speed = min(3.0, max(0.5, float(rules.get("velocidade", 1.0) or 1.0)))
    prefix = safe_name(rules.get("prefixo", "")) if rules.get("prefixo") else ""
    results = []
    try:
        # 1) analisa o que ainda nao foi analisado
        plan = []  # (nome, path, info, [(nome, s, e), ...])
        for i, name in enumerate(files):
            src = in_dir / name
            an = analyses.get(name)
            if not an:
                job.set(f"Analisando {name}...", i / max(1, len(files)) * 0.2)
                an = analyze_file(src, rules, job)
            info = probe(src)
            segs = [(name, iv["s"], iv["e"]) for iv in an["intervalos"] if iv["keep"] and iv["e"] - iv["s"] > 0.05]
            if not segs:
                job.log(f"Aviso: nada para manter em {name} (tudo foi considerado silencio?)")
                continue
            plan.append((name, src, info, segs))
        if not plan:
            raise RuntimeError("Nenhum trecho para renderizar. Ajuste as regras (ex.: limiar de silencio).")

        # tamanho e fps unicos (necessario para juntar sem recodificar)
        W, H = target_size(rules, plan[0][2])
        fps = int(rules.get("fps", 30) or 30)
        infos = {p[0]: (p[1], p[2]) for p in plan}

        # 2) define os "grupos" de saida
        groups = []  # (nome_base, [ (nome, s, e) ])
        if rules.get("modo_saida") == "juntar_tudo":
            allsegs = [s for p in plan for s in p[3]]
            groups.append(((prefix or "") + "video_unico", allsegs))
        else:
            for name, src, info, segs in plan:
                groups.append(((prefix or "") + safe_name(Path(name).stem) + "_cortado", segs))
            if rules.get("formato", "original") == "original" and len(plan) > 1:
                pass  # cada video mantem sua propria resolucao
        total = sum(len(g[1]) for g in groups)
        done = 0
        export_clips = bool(rules.get("exportar_clipes"))

        for base, segs in groups:
            if rules.get("modo_saida") != "juntar_tudo" and rules.get("formato", "original") == "original":
                W, H = target_size(rules, infos[segs[0][0]][1])
            clip_paths = []
            clip_dir = None
            if export_clips:
                clip_dir = out_dir / (base + "_clipes")
                clip_dir.mkdir(parents=True, exist_ok=True)
            for k, (name, s, e) in enumerate(segs, 1):
                src, info = infos[name]
                job.set(f"{base}: trecho {k}/{len(segs)}", 0.2 + 0.75 * done / max(1, total))
                cp = (clip_dir / f"clip_{k:03d}.mp4") if clip_dir else (tmp / f"{base}_{k:04d}.mp4")
                render_segment(src, info, s, e, rules, W, H, fps, cp, job)
                clip_paths.append((cp, name, s, e))
                done += 1

            parts = split_into_parts(clip_paths, float(rules.get("dividir_cada", 0) or 0), speed)
            for pi, part in enumerate(parts, 1):
                fname = base + (f"_parte{pi:02d}" if len(parts) > 1 else "") + ".mp4"
                outp = out_dir / fname
                job.set(f"Juntando {fname}...", 0.2 + 0.75 * done / max(1, total))
                concat_clips([c[0] for c in part], outp, rules, job)
                dur = sum((c[3] - c[2]) / speed for c in part)
                results.append({"arquivo": fname, "duracao": round(dur, 2), "trechos": len(part),
                                "largura": W, "altura": H})
                job.log(f"OK: {fname} ({fmt_time(dur)}, {W}x{H})")
            if clip_dir:
                results.append({"arquivo": clip_dir.name + "/", "duracao": None,
                                "trechos": len(clip_paths), "pasta": True})
        job.results = results
        job.set("Pronto!", 1.0)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def fmt_time(sec):
    sec = max(0, sec)
    m, s = divmod(int(round(sec)), 60)
    h, m = divmod(m, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


# --------------------------------------------------------------------------
# Jobs em segundo plano
# --------------------------------------------------------------------------


class Job:
    def __init__(self, kind):
        self.id = uuid.uuid4().hex[:10]
        self.kind = kind
        self.status = "rodando"
        self.msg = "Iniciando..."
        self.progress = 0.0
        self.logs = []
        self.results = None
        self.error = None
        self.cancel = False
        self.proc = None
        self.started = time.time()

    def set(self, msg, progress=None):
        self.msg = msg
        if progress is not None:
            self.progress = max(0.0, min(1.0, progress))

    def log(self, msg):
        self.logs.append(msg)

    def to_dict(self):
        return {"id": self.id, "tipo": self.kind, "status": self.status, "msg": self.msg,
                "progresso": round(self.progress, 4), "logs": self.logs[-50:],
                "resultados": self.results, "erro": self.error,
                "tempo": round(time.time() - self.started, 1)}


JOBS = {}
JOB_LOCK = threading.Lock()


def start_job(kind, fn):
    job = Job(kind)
    with JOB_LOCK:
        JOBS[job.id] = job

    def runner():
        try:
            fn(job)
            job.status = "concluido"
        except Cancelled:
            job.status = "cancelado"
            job.msg = "Cancelado."
        except Exception as e:  # noqa
            job.status = "erro"
            job.error = str(e)
            job.msg = "Erro"
            traceback.print_exc()

    threading.Thread(target=runner, daemon=True).start()
    return job


# --------------------------------------------------------------------------
# Configuracao e receitas
# --------------------------------------------------------------------------


def load_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def save_json(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


CONFIG = {"entrada": str(APP_DIR / "entrada"), "saida": str(APP_DIR / "saida")}
CONFIG.update(load_json(CONFIG_FILE, {}))


def in_dir():
    return Path(CONFIG["entrada"]).expanduser()


def out_dir():
    return Path(CONFIG["saida"]).expanduser()


def list_videos(d):
    if not d.is_dir():
        return []
    items = []
    for p in sorted(d.iterdir(), key=lambda x: x.name.lower()):
        if p.is_file() and p.suffix.lower() in VIDEO_EXTS and not p.name.startswith("."):
            item = {"nome": p.name, "tamanho": p.stat().st_size}
            try:
                inf = probe(p)
                item.update({"duracao": inf["duracao"], "largura": inf["largura"],
                             "altura": inf["altura"], "audio": inf["audio"]})
            except Exception as e:  # noqa
                item["erro"] = str(e)
            items.append(item)
    return items


def list_outputs(d):
    if not d.is_dir():
        return []
    out = []
    for p in sorted(d.rglob("*.mp4"), key=lambda x: x.stat().st_mtime, reverse=True):
        rel = p.relative_to(d)
        if any(part.startswith(".") for part in rel.parts):
            continue
        out.append({"nome": rel.as_posix(), "tamanho": p.stat().st_size,
                    "modificado": p.stat().st_mtime})
    return out[:300]


# --------------------------------------------------------------------------
# Servidor HTTP (interface no navegador, PC e celular)
# --------------------------------------------------------------------------

TOKEN = secrets.token_urlsafe(12)


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # nao envia nada, so descobre a interface
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return None


def resolve_inside(base, rel):
    base = base.resolve()
    p = (base / rel).resolve()
    if base != p and base not in p.parents:
        return None
    return p


class Handler(BaseHTTPRequestHandler):
    server_version = "WASO/1.0"

    def log_message(self, fmt, *args):  # silencia o log padrao
        pass

    # --- utilitarios ---
    def is_local(self):
        return self.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")

    def authorized(self, qs):
        if self.is_local():
            return True
        if qs.get("t", [""])[0] == TOKEN:
            return True
        cookie = self.headers.get("Cookie", "")
        return f"waso_t={TOKEN}" in cookie

    def send_json(self, data, code=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        if n <= 0:
            return {}
        return json.loads(self.rfile.read(n).decode("utf-8"))

    def send_file(self, path, download=False):
        if not path or not path.is_file():
            return self.send_json({"erro": "arquivo nao encontrado"}, 404)
        size = path.stat().st_size
        ctype = mimetypes.guess_type(str(path))[0] or "application/octet-stream"
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        code = 200
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                    if m.group(2):
                        end = min(int(m.group(2)), size - 1)
                elif m.group(2):
                    start = max(0, size - int(m.group(2)))
                code = 206
        if start > end:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.end_headers()
            return
        length = end - start + 1
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        if download:
            fn = urllib.parse.quote(path.name)
            self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{fn}")
        self.end_headers()
        try:
            with open(path, "rb") as f:
                f.seek(start)
                left = length
                while left > 0:
                    chunk = f.read(min(1024 * 512, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    # --- rotas ---
    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(u.query)
        if not self.authorized(qs):
            return self.send_json({"erro": "acesso negado - use o link com o codigo mostrado no PC"}, 403)
        path = urllib.parse.unquote(u.path)

        if path in ("/", "/index.html"):
            body = UI_FILE.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            if not self.is_local():
                self.send_header("Set-Cookie", f"waso_t={TOKEN}; Path=/; SameSite=Strict; Max-Age=86400")
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/estado":
            ip = lan_ip()
            port = self.server.server_address[1]
            return self.send_json({
                "ffmpeg": FFMPEG, "entrada": str(in_dir()), "saida": str(out_dir()),
                "videos": list_videos(in_dir()), "resultados": list_outputs(out_dir()),
                "celular": self.server.lan_mode,
                "link_celular": f"http://{ip}:{port}/?t={TOKEN}" if (ip and self.server.lan_mode) else None,
                "local": self.is_local(),
                "regras_padrao": DEFAULT_RULES,
            })
        if path == "/api/receitas":
            user = load_json(RECIPES_FILE, {})
            return self.send_json({"prontas": BUILTIN_RECIPES, "minhas": user})
        if path.startswith("/api/job/"):
            job = JOBS.get(path.rsplit("/", 1)[-1])
            if not job:
                return self.send_json({"erro": "job nao encontrado"}, 404)
            return self.send_json(job.to_dict())
        if path.startswith("/media/in/"):
            return self.send_file(resolve_inside(in_dir(), path[len("/media/in/"):]))
        if path.startswith("/media/out/"):
            return self.send_file(resolve_inside(out_dir(), path[len("/media/out/"):]),
                                  download="dl" in qs)
        self.send_json({"erro": "rota desconhecida"}, 404)

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(u.query)
        if not self.authorized(qs):
            return self.send_json({"erro": "acesso negado"}, 403)
        path = u.path
        try:
            if path == "/api/upload":
                name = Path(urllib.parse.unquote(self.headers.get("X-Filename", "video.mp4"))).name
                if Path(name).suffix.lower() not in VIDEO_EXTS:
                    return self.send_json({"erro": "tipo de arquivo nao suportado"}, 400)
                d = in_dir()
                d.mkdir(parents=True, exist_ok=True)
                dest = d / safe_name(name)
                n = int(self.headers.get("Content-Length", 0) or 0)
                with open(dest, "wb") as f:
                    left = n
                    while left > 0:
                        chunk = self.rfile.read(min(1024 * 1024, left))
                        if not chunk:
                            break
                        f.write(chunk)
                        left -= len(chunk)
                return self.send_json({"ok": True, "nome": dest.name})

            data = self.read_json()
            if path == "/api/pastas":
                if not self.is_local():
                    return self.send_json({"erro": "mude as pastas pelo PC"}, 403)
                for k in ("entrada", "saida"):
                    if data.get(k):
                        CONFIG[k] = str(Path(data[k]).expanduser())
                Path(CONFIG["entrada"]).mkdir(parents=True, exist_ok=True)
                Path(CONFIG["saida"]).mkdir(parents=True, exist_ok=True)
                save_json(CONFIG_FILE, CONFIG)
                return self.send_json({"ok": True})
            if path == "/api/abrir_pasta":
                if not self.is_local():
                    return self.send_json({"erro": "so no PC"}, 403)
                d = out_dir() if data.get("qual") == "saida" else in_dir()
                d.mkdir(parents=True, exist_ok=True)
                open_folder(d)
                return self.send_json({"ok": True})
            if path == "/api/receitas":
                user = load_json(RECIPES_FILE, {})
                nome = str(data.get("nome", "")).strip()[:80]
                if not nome:
                    return self.send_json({"erro": "de um nome para a receita"}, 400)
                if data.get("apagar"):
                    user.pop(nome, None)
                else:
                    user[nome] = {**DEFAULT_RULES, **(data.get("regras") or {})}
                save_json(RECIPES_FILE, user)
                return self.send_json({"ok": True})
            if path == "/api/analisar":
                files = [Path(f).name for f in data.get("arquivos", [])]
                rules = {**DEFAULT_RULES, **(data.get("regras") or {})}
                d = in_dir()

                def work(job):
                    res = {}
                    for i, name in enumerate(files):
                        base = i / max(1, len(files))
                        job.set(f"Analisando {name}...", base)
                        res[name] = analyze_file(
                            d / name, rules, job,
                            lambda p, b=base: job.set(job.msg, b + p / max(1, len(files))))
                        job.log(f"{name}: mantem {fmt_time(res[name]['mantido'])} de "
                                f"{fmt_time(res[name]['info']['duracao'])}")
                    job.results = res
                    job.set("Analise pronta", 1.0)
                return self.send_json(start_job("analise", work).to_dict())
            if path == "/api/renderizar":
                files = [Path(f).name for f in data.get("arquivos", [])]
                rules = {**DEFAULT_RULES, **(data.get("regras") or {})}
                analyses = data.get("analises") or {}
                if not files:
                    return self.send_json({"erro": "selecione pelo menos um video"}, 400)
                job = start_job("render", lambda j: render_job(j, files, rules, analyses, in_dir(), out_dir()))
                return self.send_json(job.to_dict())
            if path.startswith("/api/cancelar/"):
                job = JOBS.get(path.rsplit("/", 1)[-1])
                if job:
                    job.cancel = True
                    if job.proc:
                        try:
                            job.proc.kill()
                        except Exception:
                            pass
                return self.send_json({"ok": True})
            self.send_json({"erro": "rota desconhecida"}, 404)
        except Exception as e:  # noqa
            traceback.print_exc()
            self.send_json({"erro": str(e)}, 500)


def open_folder(d):
    try:
        if IS_WINDOWS:
            os.startfile(str(d))  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            subprocess.Popen(["open", str(d)])
        else:
            subprocess.Popen(["xdg-open", str(d)])
    except Exception:
        pass


def main():
    ap = argparse.ArgumentParser(description="WASO - cortador automatico de videos para CapCut")
    ap.add_argument("--porta", type=int, default=8765)
    ap.add_argument("--celular", action="store_true", help="permite abrir pelo celular na mesma rede Wi-Fi")
    ap.add_argument("--sem-navegador", action="store_true")
    a = ap.parse_args()

    in_dir().mkdir(parents=True, exist_ok=True)
    out_dir().mkdir(parents=True, exist_ok=True)

    host = "0.0.0.0" if a.celular else "127.0.0.1"
    srv = None
    for port in range(a.porta, a.porta + 20):
        try:
            srv = ThreadingHTTPServer((host, port), Handler)
            break
        except OSError:
            continue
    if not srv:
        print("Nao foi possivel abrir a porta.")
        sys.exit(1)
    srv.lan_mode = a.celular
    port = srv.server_address[1]
    url = f"http://127.0.0.1:{port}/"

    print("=" * 60)
    print("  WASO - Cortador Automatico (CapCut celular e PC)")
    print("=" * 60)
    print(f"  FFmpeg : {FFMPEG or 'NAO ENCONTRADO - veja o README'}")
    print(f"  Entrada: {in_dir()}")
    print(f"  Saida  : {out_dir()}")
    print(f"  Abra no PC: {url}")
    if a.celular:
        ip = lan_ip()
        print(f"  Abra no CELULAR (mesmo Wi-Fi): http://{ip}:{port}/?t={TOKEN}")
    print("  Feche esta janela (ou Ctrl+C) para encerrar.")
    print("=" * 60)
    if not a.sem_navegador:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
