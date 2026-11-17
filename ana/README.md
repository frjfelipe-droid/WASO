# Ana — sua assistente pessoal

App para celular (instala na tela inicial como um aplicativo) com visual colorido, agenda organizada por cores e uma assistente que escuta quando você chama: **“Ana…”**.

## O que ela faz
- **Escuta pelo nome**: ative *Personalizar → Ouvir quando eu chamar* e diga “Ana, marca dentista amanhã às 15h”.
- **Agenda por cores**: Trabalho, Saúde, Pessoal, Estudo, Lazer, Família (dá para criar e mudar as cores). Ela adivinha a cor pelo assunto.
- **Lembretes por notificação**: avisa antes de cada compromisso (10 min, 1 h, 1 dia…).
- **Bom dia, boa tarde, boa noite**: no horário que você escolher, com o resumo do dia (ou do dia seguinte, à noite).
- **Pergunta como você está**: de tempos em tempos, e guarda o seu humor.
- **WhatsApp**: “Ana, manda mensagem pra Maria dizendo que já estou chegando”: abre o WhatsApp com a mensagem pronta.
- **Ligar**: “Ana, liga pra mãe”.
- **Personalização**: nome dela (que também é a palavra que ela escuta), rostinho, cor, personalidade (carinhosa, divertida, objetiva, motivadora), voz, velocidade, tom, tema claro/escuro, horários e horário de silêncio.
- **Inteligência opcional**: com uma chave da API do Claude ela conversa sobre qualquer assunto e entende pedidos livres.

## Exemplos de comandos
- “marca academia sexta às 7 da manhã”
- “agende reunião com cliente hoje às 16:30”
- “me lembra de beber água em 20 minutos”
- “o que eu tenho amanhã?”
- “manda mensagem pro João dizendo que vou atrasar”
- “que horas são?” · “estou cansada” · “bom dia”

## Como colocar no celular
O microfone e as notificações só funcionam em endereço **https**. O jeito mais fácil e grátis:
1. No GitHub, abra o repositório → *Settings → Pages* → publique a branch.
2. Abra `https://SEU-USUARIO.github.io/WASO/ana/` no **Chrome do Android** (ou Safari no iPhone).
3. Menu ⋮ → **Adicionar à tela inicial**. Pronto: a Ana vira um app.
4. Em *Personalizar*: seu nome, ative notificações e “Ouvir quando eu chamar”.

Para testar no computador: `cd ana && python3 -m http.server 8000` e abra `http://localhost:8000`.

## Limites (sinceros)
Um app que roda no navegador não recebe acesso total ao celular, por segurança do Android/iPhone:
- **Escuta “Ana”** só com o app aberto na tela. Ouvir com a tela bloqueada exige app nativo (como o Google Assistente).
- **WhatsApp**: ela *prepara e abre* mensagens, mas não consegue *ler* suas conversas (o WhatsApp não permite isso a nenhum app de terceiros).
- **Calendário do celular**: a Ana tem a agenda dela; o botão “Exportar p/ celular” (ou “Google Agenda”) envia os compromissos com alarme para o calendário do aparelho, e aí o celular avisa mesmo com o app fechado.
- Os dados ficam salvos só no seu aparelho (use “Fazer backup” para guardar).
