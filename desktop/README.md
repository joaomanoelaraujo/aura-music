# AURA Desktop

Player de música para computador, da mesma família do AURA para Android. Começa pelo Windows.
Funciona sem conta, sem celular e sem internet: lê as músicas das pastas que você escolher.

Decisões, auditoria e riscos: [ARCHITECTURE.md](ARCHITECTURE.md).

## O que já funciona (primeira entrega)

- Escolher pastas de músicas (botão ou arrastar a pasta para a janela). A biblioteca abre na hora
  a partir da última leitura e se atualiza em segundo plano; pastas de disco desconectado ficam
  marcadas como indisponíveis e as músicas voltam quando o disco voltar.
- Formatos: MP3, FLAC, OGG/Vorbis, Opus, M4A/AAC/ALAC, WAV, AIFF, WMA e outros que o FFmpeg lê.
- Músicas, Álbuns, Artistas e Pastas, com capas embutidas ou `cover.jpg`/`folder.jpg` da pasta.
- Busca inteligente offline (a mesma do Android): ignora acentos e aceita pequenos erros.
- Tocar, pausar, próxima, anterior, seek, aleatório, repetir (fila/uma), volume do app.
- Fila: tocar a seguir, adicionar, remover, mover, tocar agora, limpar as próximas.
- Arquivos de áudio soltos arrastados para a janela vão para a fila.
- Atmosfera Adaptativa: o destaque e a luz de fundo seguem a capa que está tocando.

Atalhos: `Espaço` tocar/pausar · `Ctrl+F` buscar · `Ctrl+→`/`Ctrl+←` próxima/anterior ·
`Ctrl+↑`/`Ctrl+↓` volume · `Alt+←` voltar · `Esc` limpar a busca.
Menu do botão direito nas músicas e na fila.

Ainda não existe nesta versão (e por isso não aparece na interface): letras, playlists e favoritos,
configurações, Discover, Dispositivos/AURA Link, Together, teclas de mídia do sistema.

## Rodar e compilar

Requisitos: JDK 21 (o Gradle usa toolchain). A primeira compilação baixa o FFmpeg nativo (~30 MB).

```bash
./gradlew :desktop:run                  # abre o app
./gradlew :desktop:test                 # testes (silenciosos; um deles só abre a saída de áudio com silêncio)
./gradlew :desktop:createDistributable  # pasta com AURA.exe e Java embutido
./gradlew :desktop:packageMsi           # instalador Windows
```

Saídas em `desktop/build/compose/binaries/main/`. Para conferir uma cópia instalada sem abrir a
janela: `AURA.exe --self-test`.

As capturas da interface geradas pelos testes ficam em `desktop/build/screenshots/`.

Dados do app: `%APPDATA%\AURA` (configurações, cache da biblioteca e capas reduzidas). As pastas de
música nunca são alteradas.
