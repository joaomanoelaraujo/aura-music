# AURA — Premium Offline Music Player

Player de músicas para Android que funciona offline, com identidade visual própria (*Hybrid
Signature*) e **Adaptive Atmosphere**: as cores do player se adaptam com sutileza à capa de cada álbum.

Sem conta, sem assinatura, sem rastreamento. A internet é opcional e só usada por recursos que
você pode desligar: letras sincronizadas (LRCLIB), capas e correção de informações (MusicBrainz)
e jams fora da rede local (AURA Together).

## Build

Requisitos: JDK 17+, Android SDK com `platforms;android-37` e `build-tools;37.0.0`.

```bash
./gradlew :app:assembleDebug        # APK de debug (applicationId com.aura.music.debug)
./gradlew :app:assembleStaging      # código de release (R8) assinado com a chave de debug: rápido,
                                    # instala por cima do debug mantendo os dados
./gradlew :app:testDebugUnitTest    # testes unitários (JVM)
./gradlew :app:assembleRelease      # APK de release com R8 (precisa de assinatura própria)
```

`compileSdk 37` é exigido pelas versões atuais do AndroidX; `targetSdk 36`, `minSdk 29` (Android 10).

## Stack

Kotlin 2.4 · AGP 9.4 (Kotlin embutido) · Jetpack Compose (BOM 2026.09) · Material 3 apenas como
base técnica · Media3 1.11 (`MediaLibraryService` + ExoPlayer) · Room 2.8 · DataStore · Hilt 2.60 ·
Navigation Compose 2.10 (rotas tipadas) · Coil 3 · Palette · Jetpack Glance 1.2 (widgets).

Nenhuma biblioteca além dessas. Ícones, reordenação por arraste, seek bar, mini-player e
transições são implementações próprias.

## Arquitetura

Módulo único (`:app`) com camadas por pacote — para um MVP, módulos Gradle separados só
adicionariam custo de configuração.

```
com.aura.music
├── core
│   ├── common         formatação, escopo de aplicação
│   ├── model          Track, Album, Artist, Playlist, ordenação
│   ├── image          fetcher Coil para capas via MediaStore (sem rede)
│   └── designsystem   tokens (cor, tipo, espaço, raio, movimento), ícones, componentes,
│                      atmosphere (extração de paleta + matemática de contraste)
├── data
│   ├── media          MediaStoreSource, LibraryIndex (agrupamentos), LibrarySearch
│   ├── local          Room (playlists, favoritas, histórico), DataStore (ajustes, fila)
│   └── repository     Library / Playlist / Favorites / History
├── playback
│   ├── service        AuraPlaybackService, sessão, persistência da fila, bitmap loader
│   ├── controller     PlayerConnection (MediaController → StateFlow)
│   └── queue          mapeamento Track ⇄ MediaItem
├── feature            home, library, search, album, artist, collection, playlist,
│                      player, actions, settings, onboarding
├── navigation         rotas, Navigator, NavHost, barra inferior
└── ui                 AuraApp (scaffold raiz), MainViewModel, CompositionLocals
```

Decisões principais:

- **Reprodução independente da UI.** O player vive no `MediaLibraryService`; a interface só fala
  com ele por `MediaController`. Notificação, tela de bloqueio, botões Bluetooth, audio focus e
  pausa ao desconectar fones vêm do Media3.
- **Biblioteca em memória, reativa.** Uma consulta projetada ao MediaStore gera um `LibraryIndex`
  imutável; um `ContentObserver` (com debounce) reconstrói o índice quando arquivos mudam.
  Busca, agrupamentos e ordenação acontecem fora da thread principal.
- **Capas offline, na resolução certa.** Listas e grades usam miniaturas do MediaStore (rápidas,
  em cache do sistema). Tamanhos grandes (Now Playing, hero do álbum) usam a **imagem original
  embutida no arquivo**, decodificada uma única vez pelo Coil no tamanho do componente, com a
  proporção preservada. Containers animados fixam o tamanho do pedido (`requestSize`) para não
  herdar a miniatura do estado mini. `AdaptiveCover` decide a apresentação pela resolução real:
  alta/média preenche o slot; baixa é exibida menor (no máximo 2×) sobre um fundo atmosférico;
  capas não quadradas são encaixadas sem corte nem deformação.
- **Adaptive Atmosphere.** Palette extrai amostras de uma miniatura de 128 px; `AtmosphereColorMath`
  (Kotlin puro, testado) escolhe a cor-semente, limita a saturação e garante contraste WCAG AA
  (≥ 4,5:1) entre o accent e o fundo. As transições de cor duram 750 ms e são instantâneas com
  movimento reduzido.
- **Mini-player → Now Playing contínuo.** Uma única superfície interpolada por `progress` (0..1):
  o cartão flutuante cresce até a tela cheia, a capa viaja da posição mini até a posição final e a
  atmosfera entra gradualmente. A geometria é pré-calculada (`NowPlayingGeometry`) para caber em
  telas pequenas e em escalas de fonte grandes.

- **Gestos no Now Playing.** A capa é um carrossel: segue o dedo, a capa vizinha real (segundo o
  Media3, respeitando shuffle/repeat) entra pelo lado oposto e, passado o limite (distância ou
  velocidade), o player troca de faixa. Arrastes curtos ou flings contrários voltam suavemente.
- **Notificação e tela de bloqueio.** Só mecanismos oficiais: `MediaSession` + preferências de
  botões do Media3 com Favoritar e Aleatório. No Android 13+ a System UI controla o layout
  (capa, título, artista, progresso e até dois botões extras); layouts customizados de
  notificação de mídia não são permitidos e não foram usados.
- **Widgets (Glance).** Minimal (2×1), Compact (4×1), Standard (4×2) e Immersive (4×4).
  O serviço de reprodução escreve um estado compartilhado (DataStore) e atualiza os widgets
  apenas em eventos do player — e, enquanto toca, a cada 30 s só se houver widget na tela.
  Os botões usam um `MediaController` temporário, funcionando com a interface fechada.

## Funcionalidades

Home (continue ouvindo, recentes, álbuns recentes, artistas mais ouvidos, atalhos) · Biblioteca
(músicas, álbuns, artistas, playlists, pastas, gêneros; lista/grade; ordenação persistida) · Busca
local sem acentos · Álbum e Artista com header que se compacta · Now Playing · Fila (reordenar,
remover, adicionar, salvar como playlist) · Playlists (criar, renomear, adicionar, remover,
reordenar, excluir) · Favoritas · Detalhes da faixa · Ajustes reais (retomar sessão, pular
silêncio, pausar ao desconectar, atmosfera, reduzir movimento, filtro de áudios curtos,
reescanear, gesto no mini-player) · Licenças.

### Recursos adicionados no AURA 3.0

- **AURA Together** — ouvir junto em tempo real: o celular do anfitrião é o servidor (rede local
  ou retransmissor na internet, cifrado de ponta a ponta com AES-GCM), sincronia por relógio
  estimado e correção de deriva; quem não tem a música recebe uma cópia temporária enquanto toca;
  convidados podem tocar e adicionar as próprias músicas; reações.
- **Letras** — sincronizadas (LRC), embutidas, de pasta, importadas ou editadas; busca online
  automática no LRCLIB com limpeza de títulos ("[Official Video]", "Banda - Música"…) e conferência
  pela duração; efeitos na linha cantada conforme a intensidade da música; modo karaokê.
- **Análise de áudio** — energia, volume e batidas de cada música, no aparelho: volume uniforme,
  transição suave, mixes por clima e efeitos nas letras.
- **Equalizador próprio** de 10 bandas (funciona em qualquer aparelho) com predefinições.
- **Mixes automáticos** (Para relaxar, Para animar, Mais tocadas, Redescobrir) e **Retrospectiva**.
- **Backup e restauração** num arquivo JSON, reconhecendo as músicas por tags e duração.
- **Capas e informações** de álbuns sem capa via MusicBrainz / Cover Art Archive.

## Acessibilidade

Alvos de toque de 48 dp, descrições em todos os controles, ações de acessibilidade para
reordenar sem arrastar, estado de shuffle/repeat indicado também por forma (não só cor),
respeito ao ajuste de animações do sistema e à escala de fonte.

## Testes

`app/src/test` (JVM): matemática de cor/contraste da atmosfera (2.000 paletas aleatórias),
agrupamentos do `LibraryIndex`, busca e ranking, ordenação e formatação, geometria do Now Playing
em telas pequenas e fonte grande, política de apresentação de capas por resolução, decisão do
gesto de troca de faixa, progresso estimado dos widgets e retomada de sessão.

## Limitações conhecidas

- Widgets usam fontes do sistema (RemoteViews não aceita fontes empacotadas) e o progresso é
  atualizado em eventos e a cada 30 s, não continuamente.
- No Android 10–11 os widgets não exibem o brilho atmosférico (não há recorte arredondado de
  camadas nessas versões); fundo, capa e controles são iguais.
