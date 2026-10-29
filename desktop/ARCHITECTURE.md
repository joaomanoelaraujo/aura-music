# AURA Desktop — auditoria e decisão de arquitetura

Registro da Fase 1 (auditoria e escolha da stack) e do que foi construído na primeira entrega
(Fase 2, parte inicial). Datas: 28–29/10/2026.

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

### Achados de segurança do Together (a corrigir na Fase 7, antes de interoperar com o Desktop)

1. **Tomada de identidade de participante.** `Message.Hello` aceita um `participantId` para reconexão
   (`HostSession.kt`, `handle`), e os ids de todos vão no `SessionState.participants` para todos.
   Um convidado que conhece o id de outro pode entrar como ele: pula a aprovação, herda as
   permissões e derruba a conexão original. Correção prevista: segredo de reconexão por participante,
   entregue só a ele no `Welcome`.
2. **Sem proteção contra replay.** Os quadros GCM não têm número de sequência e o anfitrião não
   descarta `requestId` repetido. Quem estiver no caminho (o relay, ou alguém capaz de injetar na
   conexão local) pode reenviar um comando capturado. Correção: contador por direção dentro do
   quadro autenticado.
3. **Chave única da sala.** Todos os participantes têm a mesma chave; ela não prova *quem* enviou.
   A topologia (convidados só falam com o anfitrião) impede hoje um convidado de se passar pelo
   anfitrião para os outros, **exceto** pelo item 4.
4. **Sala do relay reivindicável.** Quando o último socket do anfitrião fecha, o Worker apaga o dono
   da sala (`storage.deleteAll()`); o próximo a conectar em `/host` vira dono. Um convidado (que tem
   o `sessionId` e a chave) poderia assumir o papel de anfitrião durante uma queda. Correção: o
   anfitrião assina com uma chave própria, ou a sala guarda o dono até o fim da sessão.
5. Convites não expiram nem podem ser revogados individualmente (só encerrando a jam).

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

## 4. Riscos técnicos conhecidos

- **Java Sound no Windows** usa o mixer DirectSound; sem modo exclusivo/bit-perfect. Tudo é
  reamostrado para 48 kHz/16 bits. Se for preciso, troca-se só o `AudioSink` (ex.: WASAPI via JNI).
- **Controles de mídia do sistema** (teclas de mídia, SMTC do Windows) exigem código nativo: ainda
  não feitos. Os atalhos funcionam com a janela em foco.
- **Tamanho:** o pacote leva todas as bibliotecas do FFmpeg (~30 MB compactadas). Dá para reduzir
  com um build próprio do FFmpeg só com demuxers/decoders de áudio.
- **Atualizações automáticas:** `jpackage` não oferece. Avaliar na Fase 9.
- **macOS/Linux:** o build escolhe o classificador nativo do sistema que compila, mas nada foi testado
  fora do Windows.
- **Persistência:** a primeira entrega usa JSON (pastas, configurações, cache da varredura). Playlists
  e favoritos pedirão SQLite (Room também roda na JVM) na Fase 3.
- O teste `TogetherSessionTest` do Android falha às vezes no Windows por bloqueio de arquivo
  (1 vez em 5 execuções nesta máquina); já existia e não tem relação com esta mudança.

## 5. O que foi testado nesta entrega (e como)

Tudo nesta máquina Windows 11, JDK 21. Nada em celular.

- `:desktop:test` — 32 testes:
  - decodificação de MP3, FLAC, OGG/Vorbis, M4A/AAC, Opus e WAV (duração ±80 ms), seek com
    precisão de ±25 ms, volume, arquivo inválido rejeitado;
  - varredura: tags com acentos, arquivo sem tags, arquivo que não é áudio ignorado, capa embutida
    salva por álbum, cache reaproveitado, pasta inexistente;
  - fila: aleatório com ordem original restaurada, repetir uma/todas, tocar a seguir, remover,
    mover, mesma música duas vezes;
  - controlador com decodificação real numa saída silenciosa em tempo real (4×): ordem da fila, fim
    da fila, pular arquivo quebrado, fila só de arquivos quebrados não entra em loop, repetir uma,
    pausar/retomar;
  - captura das telas renderizadas fora da tela com a biblioteca real de teste;
  - auto-teste com a **saída de áudio real** desta máquina (só silêncio).
- `:core:test` — 28 testes (inclui os testes do índice, busca e cores movidos do Android).
- `:app:testDebugUnitTest` — 157 testes, 4 ignorados; 1 falha intermitente já conhecida (acima).
- Pacote: `createDistributable` e `packageMsi` gerados; `AURA.exe --self-test` do pacote passou
  (FFmpeg 8.1.2/libavformat 62.12, decodificadores, saída de áudio).

**Não testado ainda:** som audível (precisa de você ouvindo), a janela aberta de verdade (as
capturas foram renderizadas fora da tela), instalação do MSI, bibliotecas grandes, escala de DPI
diferente de 100 %, discos externos desconectados na prática.
