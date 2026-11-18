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

## App para Android (recomendado): ouve "Ei, Ana" com o app fechado
Baixe pelo celular: **https://github.com/frjfelipe-droid/WASO/releases/download/ana-latest/Ana.apk**

1. Abra o link no Chrome do Android e baixe o `Ana.apk`.
2. Toque no arquivo e em **Instalar** (se pedir, permita "instalar apps desconhecidos" para o Chrome).
3. Abra a Ana, vá em **Personalizar** e ligue **Ouvir quando eu chamar**.
4. Em **Permissões do celular**, permita tudo: microfone, notificações, *aparecer sobre outros apps* (para ela abrir sozinha) e *sem economia de bateria* (para o Android não desligá-la).

O que muda no app Android:
- **"Ei, Ana" em segundo plano**: com a tela desligada ou em outro app. A detecção do nome é feita **no próprio celular, sem internet** (Vosk). Só depois de ouvir "Ana" é que ela entende o pedido.
- **Voz feminina mais calma**: usa as vozes do Google do celular e escolhe sozinha uma feminina e natural. As vozes marcadas "mais natural (internet)" soam bem menos robóticas. Ajuste velocidade e tom em Personalizar.
- **Lembretes, bom dia e boa noite com o app fechado**, e ela fala em voz alta.
- **Calendário do celular**: ela lê seus compromissos (Google Agenda etc.) e salva os novos lá.
- Uma notificação discreta "Ana está por perto" fica fixa: é a regra do Android para qualquer app que usa o microfone em segundo plano.

Depois de reiniciar o celular, abra a Ana uma vez para reativar a escuta (exigência do Android 14+).

## Versão web (sem instalar)
https://frjfelipe-droid.github.io/WASO/ana/ funciona no navegador, mas só escuta com o app aberto na tela.

Para testar no computador: `cd ana && python3 -m http.server 8000` e abra `http://localhost:8000`.

## Limites (sinceros)
- **iPhone**: a Apple não permite que nenhum app (além da Siri) fique ouvindo em segundo plano. No iPhone, use a versão web.
- **WhatsApp**: ela *prepara e abre* mensagens, mas não consegue *ler* suas conversas (o WhatsApp não permite isso a nenhum app de terceiros).
- Os dados ficam salvos só no seu aparelho (use "Fazer backup" para guardar).
