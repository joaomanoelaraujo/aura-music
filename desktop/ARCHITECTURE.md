# AURA Desktop — auditoria e decisão de arquitetura

Registro da Fase 1 (auditoria e escolha da stack) e do que foi construído nas Fases 2 a 9.
Datas: 28–29/10/2026.

## 1. O que foi auditado no projeto Android (confirmado no código)

| Área | Estado real |
|---|---|
| Estrutura | Um módulo `:app` (Kotlin 2.4.21, AGP 9.4, Compose BOM 2026.09, Hilt, Room v6, Media3 1.11). 159 arquivos Kotlin; 33 não dependem de Android. |
| Identidade das faixas | No aparelho: id do MediaStore (`Long`), válido só no próprio celular. Entre aparelhos: `TrackRef` (título, artista, álbum, duração, tamanho) + `TrackMatcher` com texto normalizado. Não há hash de conteúdo. |
| Together | `PROTOCOL = 5`. Mensagens JSON + quadros binários (trechos de música e capas). Anfitrião é autoritativo (`SessionState.version`), relógio por `ClockSync` (janela de 8 amostras) e `DriftCorrector`. Transporte: TCP na rede local (quadros com prefixo de tamanho) ou WebSocket pelo relay. |
| Criptografia | AES-256-GCM por quadro, IV aleatório de 12 bytes, `sessionId` como dado associado. A chave viaja só no fragmento (`#…`) do link de convite. |
| Relay (Cloudflare) | `github-pages/src/worker.js`: Durable Object `Room`, rotas `/relay/v1/room/<id>/host|guest`, quadro máx. 64 KiB, 16 convidados, limite de mensagens por segundo, token do anfitrião (hash guardado na sala). |
| Discover | Internet Archive (busca + metadados + rótulo de licença a partir de `licenseurl`), links diretos HTTPS, YouTube/Spotify só por oEmbed e "abrir no app oficial" (sem download). Partes de análise/parsing são Kotlin puro. |
| Persistência | Room v6 (migrações automáticas 1→6) e DataStore. |
| Design | Paleta e tokens em `core/designsystem/theme`, ícones próprios em `core/designsystem/icons/AuraIcons.kt` (só APIs de vetor do Compose), Atmosfera Adaptativa em `core/designsystem/atmosphere` (matemática de cor pura + extração com `androidx.palette`). |

### Achados de segurança do Together (situação após a Fase 7: `PROTOCOL = 6`)

1. **Tomada de identidade de participante.** `Message.Hello` aceita um `participantId` para reconexão
   (`HostSession.kt`, `handle`), e os ids de todos vão no `SessionState.participants` para todos.
   Um convidado que conhece o id de outro pode entrar como ele: pula a aprovação, herda as
   permissões e derruba a conexão original. **Corrigido:** cada participante recebe um
   `resumeToken` só seu no `Welcome`; sem ele, o mesmo id não é aceito.
2. **Sem proteção contra replay.** Os quadros GCM não têm número de sequência e o anfitrião não
   descarta `requestId` repetido. Quem estiver no caminho (o relay, ou alguém capaz de injetar na
   conexão local) pode reenviar um comando capturado. **Corrigido:** `SecureLink.open` troca
   nonces e deriva um id de conexão; o dado associado do GCM leva id + sentido + contador, então
   quadro repetido, reordenado ou de outra conexão é rejeitado.
3. **Chave única da sala.** Todos os participantes têm a mesma chave; ela não prova *quem* enviou.
   A topologia (convidados só falam com o anfitrião) impede hoje um convidado de se passar pelo
   anfitrião para os outros, **exceto** pelo item 4.
4. ~~Sala do relay reivindicável.~~ **Achado errado, corrigido na revisão:** o Worker só apaga o
   dono (`hostHash`) por alarme, `IDLE_ROOM_MS` = 15 min depois que o último socket do anfitrião
   fecha; dentro desse prazo `/host` com outro token recebe 403. Além disso o token do anfitrião
   não está no convite, então um convidado não o conhece. Nenhuma mudança no Worker foi necessária
   (e nenhuma foi implantada).
5. Convites não expiravam nem podiam ser revogados. **Corrigido:** cada convite tem um `inviteId`;
   “Invalidar convites” (Android e Desktop) gera um novo e recusa os antigos com
   `RejectReason.InviteRevoked`, sem derrubar quem já está na jam.

## 2. Decisão de stack: Kotlin/JVM + Compose for Desktop + FFmpeg

Alternativas comparadas:

| Critério | **Compose Desktop (JVM) + FFmpeg** | Tauri (Rust + web) | Electron |
|---|---|---|---|
| Reuso do código existente | Direto: modelos, índice, busca, cores, protocolo do Together (mesmas classes `kotlinx.serialization`, compatibilidade binária garantida) | Nenhum; protocolo e busca reescritos em Rust/TS | Nenhum |
| Áudio | FFmpeg (todos os formatos) + Java Sound | symphonia + cpal (sem Opus/WMA nativos) | Chromium (formatos limitados) |
| Memória (ordem de grandeza) | JVM: ~150–300 MB | ~60–120 MB | ~200–400 MB |
| Interface | Compose, mesma linguagem e padrões do Android | Web | Web |
| Empacotamento | `jpackage` (MSI/EXE com Java embutido) | Instalador pequeno | Grande |

**Escolha:** Kotlin/JVM com Compose for Desktop. O fator decisivo é que Android e Desktop rodam na
JVM: um módulo Kotlin/JVM comum (`:core`) é consumido pelos dois **sem migrar o app para Kotlin
Multiplatform**. Isso vale sobretudo para o protocolo do Together, que precisa ser idêntico byte a
byte. O custo aceito é memória maior que Tauri e instalador de ~90 MB.

**Motor de áudio:** Media3 não roda no Desktop. Usamos **FFmpeg 8.1.2 (build LGPL do bytedeco,
JavaCPP 1.5.14)** para abrir, decodificar e reamostrar (saída PCM 16 bits estéreo 48 kHz), e
**Java Sound** (`SourceDataLine`) para tocar. Volume é do app (ganho nas amostras), nunca o do
sistema. A thread de áudio é dedicada; pausa/seek agem na saída imediatamente.

Licenças: Compose Multiplatform (Apache 2.0), JavaCPP/presets (Apache 2.0), FFmpeg do artefato
`org.bytedeco:ffmpeg` (LGPL; **não** usamos `ffmpeg-gpl`), fontes Manrope e Instrument Serif (OFL).

## 3. Módulos

```
:core     Kotlin/JVM puro, usado por :app e :desktop
          core/model (Track, Album…), core/common (formatação), data/media/LibraryIndex,
          data/search/SmartSearch, core/designsystem/atmosphere/AtmosphereColorMath
:app      Android (inalterado no comportamento; depende de :core)
:desktop  AURA Desktop
          audio/     FfmpegDecoder, AudioEngine, AudioSink (JavaSoundSink)
          library/   TagReader (tags/capa via FFmpeg), LibraryScanner, LibraryMapper, ArtworkStore
          playback/  PlayQueue (fila imutável), PlayerController
          data/      AppPaths, Settings, LibraryRepository (JSON atômico)
          ui/        Compose: barra lateral, listas, busca, fila, player
```

Os arquivos foram movidos com `git mv` mantendo os pacotes, então nenhum import do Android mudou.
`AuraIcons.kt` continua no Android e é compilado também pelo Desktop (`srcDir`), para os dois apps
usarem os mesmos ícones; esse arquivo não pode usar APIs do Android.

Identidade das faixas no Desktop: id = SHA-256 do caminho normalizado (estável entre execuções,
local ao computador). Para Together/AURA Link continuará valendo o `TrackRef` + correspondência por
metadados; hash de conteúdo pode ser adicionado como desempate, nunca como único critério.

## 3b. AURA Link (Fase 4)

Vínculo privado entre os aparelhos da mesma pessoa; separado do Together (um convite de jam não
dá acesso a nada disto). Código do protocolo e da criptografia em `:core` (`com.aura.music.link`),
o mesmo no Android e no Desktop.

- **Pareamento**: o computador abre uma janela de 5 minutos e mostra um QR Code (segredo de 256 bits)
  e um código de 8 dígitos. Os dois lados rodam **SPAKE2 (RFC 9382) sobre P-256**: só quem conhece o
  segredo chega à mesma chave, e um atacante no meio tem um palpite por tentativa, sem como testar
  palpites offline — por isso o código curto é seguro com o limite de **5 tentativas**. Depois, cada
  lado envia sua chave de identidade assinada sobre a transcrição, os dois mostram o mesmo número
  de verificação de 6 dígitos e o vínculo só existe se o usuário aceitar nos dois.
- **Identidade**: par de chaves EC P-256 por aparelho. No Windows a chave privada fica cifrada com
  **DPAPI** (só a conta do Windows consegue abrir); no Android fica no **Keystore** (não exportável).
- **Reconexão**: ECDH efêmero (sigilo futuro) + assinatura de cada lado com a chave de identidade,
  conferida contra a chave guardada no pareamento. Nome, IP ou presença na rede não bastam.
- **Canal**: AES-256-GCM com contador por direção no nonce; quadro repetido, fora de ordem ou
  adulterado derruba a conexão (proteção contra replay). Comandos têm id crescente e duplicados são
  descartados.
- **Permissões por aparelho** (decididas no aparelho que é controlado): controlar a reprodução;
  ver e tocar a biblioteca. Conferidas a cada comando no lado que recebe.
- **Desconectar por agora** recusa reconexões até o usuário permitir; **remover vínculo** avisa o
  outro lado, apaga a chave dele e recusa qualquer reconexão.
- **Rede**: TCP na porta 47820 (ou outra livre), só na rede local nesta fase; anúncio mDNS
  `_auralink._tcp` (JmDNS) com um id curto derivado do id do aparelho. O computador só começa a
  escutar quando há aparelho vinculado ou janela de pareamento aberta. Quem conecta é o celular
  (o Android não mantém servidor em segundo plano); a conexão serve aos dois sentidos.
- **Estado**: o aparelho que toca é a fonte da verdade e envia seu estado quando algo muda; quem
  controla extrapola a posição pelo próprio relógio. Volume é o do app, nunca o do sistema.

Testes: `LinkSecurityTest` (14, em `:core`: SPAKE2, código errado, recusa, impostor com a mesma
identidade, aparelho falso respondendo, replay/reordem/adulteração, duplicatas) e
`LinkServiceTest` (5, no Desktop contra um "celular" simulado com o mesmo código do `:core`).

## 3c. Transferência de arquivos (Fase 5)

- Sobre o mesmo canal cifrado do AURA Link, num segundo socket (`ChannelHello.Data`) para não
  atrasar comandos. Oferta (`TransferOfferMessage`: nomes, tamanhos, SHA-256) → resposta → dados.
- Quem recebe decide: pergunta a cada oferta, a menos que o aparelho tenha a permissão
  “receber arquivos sem perguntar”. Só arquivos de áudio; nomes saneados (`FileNames`), nunca
  sobrescreve (acrescenta “ (2)”).
- Grava em `.part` (`StagingSink`), retoma do ponto onde parou após queda (pedido com deslocamento),
  confere o SHA-256 e só então publica: no Android pelo MediaStore em `Música/AURA`; no Desktop na
  pasta escolhida em Configurações (padrão `Música\AURA`), que entra na biblioteca.
- Cancelar dos dois lados; “Tentar de novo” retoma. Testes: `TransferChannelTest` (7, `:core`) e
  `TransfersTest` (5, Desktop).

## 3d. Descobrir no Desktop (Fase 6)

- O mesmo código do Android, movido para `:core`: Internet Archive (licença como declarada por quem
  publicou, mostrada antes de baixar) e links diretos HTTPS de arquivos de áudio.
- YouTube e Spotify: só título/capa via oEmbed e “Abrir no app oficial”. **Nada de download, nada
  de contornar DRM.**
- Downloads (`DesktopDownloads`): só HTTPS, `.part` com retomada, cancelar, salva na pasta de
  downloads e entra na biblioteca. Opção “Baixar e enviar para o celular” usa a transferência da
  Fase 5. Testes: `DownloadsTest` (6), `DiscoverParsingTest` (`:core`).

## 3e. Together Desktop ↔ Android (Fase 7)

- Protocolo, sessões de anfitrião/convidado, sincronização e transporte movidos para `:core`; o
  Desktop usa exatamente o mesmo código (`DesktopTogether`). `PROTOCOL = 6` com as correções acima;
  aparelhos com a versão 5 recebem “atualize o app”.
- Desktop pode ser anfitrião (rede local e relay) ou convidado. Como convidado, corrige o desvio só
  por seek (limite 150 ms, intervalo mínimo 3 s): o Java Sound não permite ajustar a velocidade sem
  reamostrar.
- Enquanto está numa jam, os comandos locais do player viram pedidos ao anfitrião (`redirect`).

## 3f. Streaming autorizado (Fase 8) — avaliação e decisão

O que se quer: alguém da jam ouve uma música que só o anfitrião tem.

| Opção | Prós | Contras |
|---|---|---|
| **Cópia temporária progressiva pelo Together (atual)** | Já existe e foi testada; mesma criptografia e mesmo controle de acesso da jam; funciona pela rede local e pelo relay; toca antes de terminar de chegar; sem servidor novo. | Passa pelo relay quando não há rede local (quadros de 64 KiB, banda do Worker); usa o plano de dados de quem recebe. |
| WebRTC (dados ou áudio) | Ponto a ponto; baixa latência. | Precisa de STUN e, com CGNAT (comum em celular), de TURN — servidor pago e novo serviço em produção; muito mais código nos dois apps; outro modelo de segurança a auditar. |
| Servidor de streaming (HLS etc.) | Padrão de mercado. | Hospedar música de terceiros num servidor nosso: problema de direitos, custo e privacidade. Descartado. |

**Decisão: manter a cópia temporária progressiva.** Regras que a tornam “autorizada”:
- **Consentimento:** no Desktop, receber músicas que faltam é opcional ao entrar (desligado por
  padrão); no Android é ligado, com aviso na tela da jam quando a rede é medida (dados móveis).
- **Acesso:** o anfitrião só envia músicas que estão na fila da jam, só a participantes aprovados;
  nada da biblioteca é listado.
- **Revogação e limpeza:** expulsar alguém ou encerrar a jam corta o envio; o cache temporário é
  apagado ao sair. Nenhum arquivo fica na biblioteca de quem recebeu.
- YouTube/Spotify continuam fora: não há streaming de serviços de terceiros.

Revisitar WebRTC só se o custo de banda do relay se tornar um problema medido.

## 3g. Integração com o Windows e pacote (Fase 9)

- Teclas de mídia do teclado funcionam com a janela sem foco (`RegisterHotKey`; teclas que outro
  app já reservou são ignoradas). Ícone na área de notificação com tocar/pausar, próxima, anterior.
  Barra de título escura (`DwmSetWindowAttribute`).
- MSI por usuário (`AURA-0.9.0.msi`, ~105 MB, Java 21 embutido), sem pedir administrador.

## 4. Riscos técnicos e limitações conhecidas

- **Java Sound no Windows** usa o mixer DirectSound; sem modo exclusivo/bit-perfect. Tudo é
  reamostrado para 48 kHz/16 bits. Se for preciso, troca-se só o `AudioSink` (ex.: WASAPI via JNI).
- **SMTC** (painel de mídia do Windows com capa) não foi feito; as teclas de mídia funcionam por
  `RegisterHotKey`.
- **Together como convidado no Desktop** corrige desvio só por seek (sem ajuste fino de velocidade).
- **AURA Link**: só na rede local; o celular precisa estar com o app aberto para conectar (o
  Android não mantém conexão em segundo plano nesta versão).
- **Sem atualização automática** (`jpackage` não oferece); nova versão = instalar o novo MSI por cima.
- **Instalador sem assinatura de código**: o Windows SmartScreen pode avisar.
- **Tamanho:** o pacote leva todas as bibliotecas do FFmpeg. Dá para reduzir com um build próprio só
  com demuxers/decoders de áudio.
- **macOS/Linux:** o build escolhe o classificador nativo do sistema que compila, mas nada foi testado
  fora do Windows.
- `TogetherSessionTest` tem testes sensíveis a tempo; sob carga (várias suítes em paralelo) uma
  espera de 5 s estourou uma vez e foi ampliada para 10 s.

## 5. O que foi testado (e como)

Máquina Windows 11, JDK 21. **Testes com Android foram feitos no emulador (`nex_e2e`), não em
celular físico.** O teste em aparelho físico continua pendente.

Testes automáticos (última execução, 29/10/2026):
- `:core:test` — 123 testes, 3 ignorados, 0 falhas (AURA Link: SPAKE2, impostor, replay,
  adulteração; transferências; Discover; Together com as correções de segurança).
- `:desktop:test` — 61 testes, 0 falhas (áudio real decodificado, biblioteca, fila, dados do
  usuário, letras, AURA Link contra um celular simulado, transferências, downloads, Together
  Desktop↔Desktop, 18 telas renderizadas).
- `:app:testDebugUnitTest` — 89 testes, 1 ignorado, 0 falhas (os testes do Together foram para `:core`).
- `:app:assembleStaging` gerado.

Ponta a ponta com o emulador Android (harness `LinkHarness` no Desktop):
- Pareamento por código com os mesmos números de verificação; reconexão depois de reiniciar o
  Desktop; Desktop→celular tocar e volume; celular→Desktop tocar, pausar e retomar; permissão
  negada quando desligada; remover vínculo chega ao celular.
- Arquivo Desktop→celular (MP3, 720 502 bytes, publicado em `Música/AURA` e visto na biblioteca) e
  celular→Desktop (FLAC, 61 905 bytes, idêntico).
- Jam com Desktop anfitrião e Android convidado pela rede local (aprovação, sincronia ±90 ms, cópia
  temporária de música que faltava, pausa do celular pausou o Desktop) e com Android anfitrião e
  Desktop convidado **pelo relay de produção** (RTT 333 ms, desvio 7–10 ms).

Pacote:
- `AURA.exe --self-test` do `createDistributable` passou (FFmpeg, decodificadores, saída de áudio).
- MSI 0.9.0: instalação silenciosa por usuário (`msiexec /qn`, código 0) em
  `%LOCALAPPDATA%\AURA`, `--self-test` da cópia instalada passou, desinstalação (código 0) removeu
  os arquivos.
- A janela do app empacotado abriu com a barra de título escura (captura de tela).

**Não testado:** celular físico; som audível com alguém ouvindo; teclas de mídia num teclado real
(registro feito, tecla não pressionada); bibliotecas grandes; DPI diferente de 100 %; outros
computadores e redes (Wi-Fi com isolamento de clientes bloqueia o AURA Link); macOS/Linux.
