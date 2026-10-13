# PlusTV Player — versão para a Google Play (flavor "loja", app 3.3)

Não foi possível compilar nem testar aqui. Compile, teste numa TV/box e no celular, e rode o "Pré-lançamento" do Play Console antes de publicar.

## O que é diferente da versão "site"
| | site (APK fora da loja) | loja (Google Play) |
|---|---|---|
| Player | ExoPlayer + VLC | **só ExoPlayer** (a libVLC 3.6 ainda não tem as bibliotecas alinhadas em 16 KB, exigência do Google) |
| Licença / ativação | mostra site, MAC, Device Key, QR e preço | **nada de pagamento, preço, QR ou link de ativação/renovação** dentro do app |
| Renovação do provedor | botão Renovar / WhatsApp | removido (mostra só "Fale com o seu provedor") |
| Após os 7 dias de teste | aviso com site + QR | aviso só pedindo o **código de um provedor parceiro** |
| Assinatura | chave inclusa no projeto | **sua chave de upload** (variáveis LOJA_*) |
| API | 36 | 36 |

Limitações da versão loja: canais H.265/HEVC dependem do decodificador do aparelho (sem VLC de apoio); formatos avi/wmv/flv/mpeg podem não tocar.
A licença anual comprada no site vale por aparelho (ID de hardware). Na loja o app tem outra assinatura, então o ANDROID_ID muda: licença da versão "site" **não** vale na versão "loja".

## Gerar o AAB
1. Crie sua chave de upload (guarde fora do projeto, NÃO perca):
   `keytool -genkeypair -v -keystore loja.keystore -alias plustv -keyalg RSA -keysize 2048 -validity 10000`
2. No GitHub: Settings > Secrets > Actions: `LOJA_KEYSTORE_B64` (`base64 -w0 loja.keystore`), `LOJA_STORE_PASS`, `LOJA_KEY_ALIAS`, `LOJA_KEY_PASS`.
3. Actions > "Gerar AAB para a Google Play" > Run workflow. Baixe `PlusTV-loja-aab` e envie o `.aab` no Play Console (ative o Play App Signing).
4. Local: `LOJA_KEYSTORE=... LOJA_STORE_PASS=... LOJA_KEY_ALIAS=... LOJA_KEY_PASS=... gradle bundleLojaRelease` (JDK 17, Gradle 8.11.1+).
Atenção: o repositório tem `plustv.keystore` com senha (usado só pela versão site). Mantenha o repositório **privado**.

## Antes de enviar (checklist)
- [ ] Testar em Android TV/Fire TV e celular: tela cheia (Android 15+ força borda a borda), controle remoto, reprodução de canal/filme/série.
- [ ] Console > Qualidade do app > conferir "alinhamento de 16 KB" no pré-lançamento (não pode haver aviso).
- [ ] Política de privacidade: use `https://SEU-PAINEL/plustv-player/privacidade` (já criada no painel, v1054).
- [ ] Segurança dos dados (ver abaixo), classificação indicativa, público-alvo 13+ (não crianças), declaração de anúncios: **não tem anúncios**.
- [ ] Declaração do ID de publicidade: **não usa**.
- [ ] Android TV: banner 320x180 e capturas de tela da TV.
- [ ] Nome e ícone sem marcas de terceiros (TV, canais, "IPTV Smarters", etc.).

## Texto da loja (sugestão)
Título: PlusTV Player
Descrição curta: Player de mídia para a sua conta de provedor. Não inclui conteúdo.
Descrição: O PlusTV Player é apenas um reprodutor. Ele não fornece, não vende nem hospeda canais, filmes, séries ou listas. Entre com o acesso (usuário e senha, código ou link) que o seu provedor forneceu e assista no seu aparelho. Recursos: perfis, favoritos, controle dos pais com PIN, visual clássico ou estilo Netflix, controle remoto. Você é responsável pelo conteúdo que adiciona e deve ter os direitos para usá-lo.
**Não** cite nomes de canais, esportes, marcas ou "IPTV grátis" na descrição, nas imagens ou no nome.

## Segurança dos dados (Play Console) — respostas sugeridas
Coleta: sim. Compartilha com terceiros: sim, só com o servidor do provedor escolhido pelo usuário (para autenticar) — marque como compartilhamento necessário para a função do app.
- Informações pessoais: nome/usuário e senha da conta do provedor (funcionalidade do app). 
- IDs de dispositivo ou outros: ANDROID_ID (hash) e MAC gerado pelo app (funcionalidade do app, prevenção de fraude).
- Atividade no app/diagnóstico: IP e logs de acesso (segurança/fraude).
- Dados criptografados em trânsito: sim (painel em https). Pedido de exclusão: sim — menu Listas do app/site e e-mail/WhatsApp da página de privacidade; informe a URL da página de privacidade também no campo de exclusão de dados.
Sem localização, contatos, fotos, microfone, SMS.

## Riscos que continuam (não dá para eliminar)
- Apps de IPTV já foram removidos da loja por denúncia de direitos autorais; mantenha o app sem conteúdo próprio e atenda a denúncias rápido.
- O Google pode reprovar se encontrar no app, no painel carregado ou na ficha da loja qualquer menção a cobrança por fora (Pix, site de ativação, WhatsApp para renovar) para liberar recursos. A versão loja esconde isso no player; confira também as telas de login do painel e o texto do dono (Gerenciador) antes de enviar.
- `usesCleartextTraffic="true"` continua (muitos servidores são http). É permitido, mas pode gerar pergunta na revisão.
