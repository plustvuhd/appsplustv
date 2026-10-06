# PlusTV Player — Android TV, Fire TV / Fire Stick e TV box

App Android (APK) que abre o Web Player em modo TV (controle remoto, tela cheia, teclas de mídia).
Usa o mesmo endereço de descoberta do GitHub das outras TVs: se mudar o domínio do painel, edite só o plustv.json.

## 1) Configurar (opcional)
Abra `app/src/main/java/com/plustv/player/MainActivity.java` e confira PANEL, DISCOVERY e OWNER (já vêm com seu painel e o GitHub plustvuhd/plustv-config).

## 2) Gerar o APK (sem instalar nada no PC) — pelo GitHub
1. No GitHub crie um repositório **privado** (ex.: plustv-android) e envie TODO o conteúdo desta pasta (inclusive a pasta oculta `.github`). Pelo site: Add file > Upload files, arraste tudo.
2. Aba **Actions** > "Gerar APK" > **Run workflow**. Espere uns 3–5 minutos.
3. Abra a execução concluída e baixe **PlusTV-apk** (zip). Dentro está o `app-release.apk`.

(Alternativa: Android Studio > abrir esta pasta > Build > Build APK.)

## 3) Instalar
- **Fire Stick / Fire TV**: instale o app "Downloader" (loja da Amazon), ative Configurações > Meu Fire TV > Opções do desenvolvedor > "Instalar apps desconhecidos" para o Downloader, e baixe o APK por um link (suba o apk no Google Drive/seu site e use o link direto).
- **Android TV / TV box**: copie o APK num pendrive e abra com um gerenciador de arquivos (ou use "Send Files to TV"), permitindo "fontes desconhecidas".
- O app aparece na tela inicial / lista de apps com o ícone PlusTV.

## 4) Colocar nas lojas das TVs
- **Amazon Appstore (Fire TV)**: developer.amazon.com > Appstore > novo app > Fire TV; envie o APK. Costuma ser a aprovação mais simples.
- **Google Play (Android TV)**: precisa de conta de desenvolvedor Google (taxa única). Troque a chave de assinatura (em `app/build.gradle`, crie a sua com `keytool` e NÃO perca) e gere um AAB (`gradle bundleRelease`). Marque o app como compatível com Android TV (já tem o banner e o launcher de TV). Em app de IPTV descreva como "player do cliente, usa o acesso do provedor dele"; não cite canais/conteúdo.
- Domínio mudou? Não precisa atualizar o app: só o arquivo do GitHub.

## Cobrança
Quem usa pelo app conta como ativo de TV do mês (junto com Roku/LG/Samsung/VIDAA). Computador e celular (navegador) continuam grátis.

## Observações
- Atualizações do visual/funções do player acontecem no painel; o APK só muda se você trocar o endereço padrão ou o ícone.
- A chave de assinatura inclusa é para instalar direto; use sua própria para as lojas.
- Não foi possível testar em TV/box real nem compilar neste ambiente.

## Players embutidos (v1.1)
O app agora traz **ExoPlayer** e **VLC** dentro dele (nada de abrir outro aplicativo). Em Configurações > Mudar player do Web Player:
Automático (tenta um e troca sozinho para o outro se falhar), ExoPlayer, VLC ou Player integrado (navegador).
No player nativo: OK pausa/continua, setas esquerda/direita pulam 10 s (segurando, 30 s), Voltar fecha e salva onde parou.
O APK fica maior (uns 60–100 MB) por causa do VLC. Ao atualizar, instale por cima do APK anterior (mesma assinatura).

## Logo do app (v1.2)
- Ao abrir, o app mostra a logo do Web Player na tela de carregamento (a logo do Web Player, sobre um fundo escuro com as cores do player). Ela é guardada no aparelho para aparecer na hora nas próximas aberturas.
- O ícone na lista de apps e o banner da Android TV ficam dentro do APK. Para trocar: no admin do Web Player (seção "Ícones prontos para os apps das TVs") baixe "Android / geral 512" e "Android TV banner", substitua no projeto
  `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` e `app/src/main/res/drawable/banner.png` (mesmos nomes) e gere o APK de novo.

## Ícone do app antes de abrir (v1.3)
O workflow "Gerar APK" agora baixa sozinho o ícone e o banner do painel (a logo do Web Player, sobre um fundo escuro com as cores do player) e coloca no APK.
Para trocar o ícone: mude a logo do Web Player no admin, salve, e rode Actions > Gerar APK de novo. Instale o APK novo por cima.
(Se o ícone antigo continuar aparecendo, reinicie a tela inicial da TV ou desinstale e instale de novo: alguns launchers guardam o ícone.)
