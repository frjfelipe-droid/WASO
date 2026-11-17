# ✂️ WASO — Cortador Automático de Vídeos (para CapCut)

Ele olha seus **vídeos brutos**, você define **o que tem que fazer** e ele **corta sozinho**.
O resultado sai pronto para abrir no **CapCut do PC ou do celular** (MP4 H.264 + AAC, quadros constantes).

Funciona no seu PC (Windows, Mac ou Linux). A interface abre no navegador, e você pode usar também **pelo celular** no mesmo Wi-Fi: enviar vídeos, mandar cortar e baixar o resultado direto na galeria.

---

## O que ele faz

| Regra | Para que serve |
|---|---|
| **Remover silêncios / pausas** | Faz jump cut automático: tira as partes sem fala |
| Sensibilidade (dB), pausa mínima, respiro | Ajusta o quanto ele corta |
| Descartar trechos curtos | Remove estalos, tosses, ruídos rápidos |
| Cortar início / final | Tira os segundos de “ligar/desligar a câmera” |
| Velocidade | 1.1x, 1.25x… (o áudio não fica fino) |
| **Formato 9:16, 16:9, 1:1, 4:5** | Vertical para TikTok/Reels/Shorts, com fundo desfocado, zoom ou barras |
| CapCut celular / PC | Celular = arquivo mais leve; PC = qualidade máxima (até 4K) |
| Igualar volume | Padrão de redes sociais (-14 LUFS) |
| Juntar tudo em um vídeo | Une vários brutos em um só |
| Dividir em partes de N segundos | Ex.: 60 s para Shorts — divide **só nas pausas**, nunca no meio da frase |
| Salvar cada trecho separado | `clip_001.mp4`, `clip_002.mp4`… para montar na timeline do CapCut |
| **Receitas** | Salve suas regras e reutilize com um clique |

Antes de cortar você pode clicar em **Analisar**: aparece uma barra (verde = mantém, vermelho = corta). **Clique em qualquer trecho para trocar** e use **▶ Prévia** para assistir como vai ficar.

---

## Instalação (uma vez só)

### Windows
1. Instale o **Python**: https://www.python.org/downloads/ — **marque “Add Python to PATH”**.
2. Baixe esta pasta (botão verde *Code → Download ZIP* no GitHub) e extraia.
3. Dê dois cliques em **`INICIAR-WINDOWS.bat`**.
   Na primeira vez ele instala o FFmpeg sozinho (precisa de internet).

> Se preferir instalar o FFmpeg manualmente: baixe em https://www.gyan.dev/ffmpeg/builds/ e coloque a pasta extraída como `ffmpeg\bin\ffmpeg.exe` dentro desta pasta.

### Mac / Linux
```bash
./iniciar-mac-linux.sh
```
(precisa de Python 3; o FFmpeg pode ser `brew install ffmpeg` ou `sudo apt install ffmpeg`)

---

## Como usar

1. Coloque os vídeos brutos na pasta **`entrada`** (ou escolha outra pasta na tela).
2. Selecione os vídeos.
3. Escolha uma **receita** ou ajuste as regras.
4. (Opcional) **Analisar** para conferir e ajustar os cortes.
5. **Cortar automaticamente**. Os vídeos aparecem na pasta **`saida`**.

### Levar para o CapCut PC
Abra a pasta de saída e arraste o `..._cortado.mp4` (ou a pasta `..._clipes`) para o CapCut.

### Usar pelo celular / levar para o CapCut celular
1. No PC, abra **`INICIAR-COM-CELULAR-WINDOWS.bat`** (Mac/Linux: `./iniciar-mac-linux.sh --celular`).
2. A janela mostra um link tipo `http://192.168.0.10:8765/?t=código`. Abra no navegador do celular (mesmo Wi-Fi).
   O código protege o acesso: sem ele ninguém da rede consegue entrar.
3. Pelo celular você pode **enviar vídeos** da galeria, cortar e tocar em **Baixar** no resultado.
4. No CapCut: *Novo projeto* → escolha o vídeo baixado.

> Se o Windows perguntar sobre o Firewall, permita em **Redes privadas**.

---

## Dicas de ajuste

- **Cortou fala demais?** Arraste a *Sensibilidade* para a esquerda (ex.: -45 dB) ou aumente o *Respiro*.
- **Sobrou muita pausa?** Arraste a *Sensibilidade* para a direita (ex.: -28 dB) ou diminua a *Pausa mínima*.
- Ambiente com ruído de fundo (ventilador, ar-condicionado): use -30 a -25 dB.
- Ritmo de TikTok: pausa mínima 0.3–0.4 s, respiro 0.05–0.08 s, velocidade 1.1x.

## Próximos passos
- Exportar projeto para **DaVinci Resolve** (XML/EDL, com os cortes editáveis na timeline).

## Arquivos
- `cortador.py` — o programa (só biblioteca padrão do Python + FFmpeg)
- `ui.html` — a interface
- `entrada/`, `saida/` — pastas padrão
- `receitas.json`, `config.json` — criados automaticamente com suas preferências
