# AURA Desktop

Player de música para computador, da mesma família do AURA para Android. Versão 0.9.0, Windows.
Funciona sem conta, sem celular e sem internet: lê as músicas das pastas que você escolher.

Decisões, auditoria e riscos: [ARCHITECTURE.md](ARCHITECTURE.md).

## O que já funciona

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
- **Letras** (painel “Letra” e tela “Tocando agora”): sincronizadas acompanham a música e um clique
  na linha pula para ela. Ordem das fontes: letra escolhida/importada por você → `.lrc` ao lado da
  música → letra dentro do arquivo → letra do LRCLIB já salva. A busca automática no LRCLIB vem
  **desligada** (envia título, artista, álbum e duração) e se ativa em Configurações; “Procurar
  letra” faz uma busca manual e você escolhe o resultado. Letras ficam salvas para uso offline.
- **Favoritas e playlists**: criar, renomear, excluir, adicionar (menu do botão direito), mover e
  remover. Guardam o caminho do arquivo: músicas cujo arquivo sumiu continuam na lista, marcadas.
- **Configurações**: letras online, retomar de onde parou, personalizar a busca, dados do app.
- **Retomar de onde parou**: a fila volta pausada na mesma posição ao abrir.
- **AURA Link — Dispositivos** (lado do computador): vincular um celular por QR Code ou código de
  8 dígitos com confirmação nos dois aparelhos, lista de aparelhos com estado, permissões por
  aparelho, renomear, desconectar por agora e remover vínculo; o celular vinculado controla este
  computador e este computador controla o celular (tela “Controle remoto”), dentro das permissões
  que cada um concede. Veja a seção AURA Link em [ARCHITECTURE.md](ARCHITECTURE.md).

Atalhos: `Espaço` tocar/pausar · `Ctrl+F` buscar · `Ctrl+→`/`Ctrl+←` próxima/anterior ·
`Ctrl+↑`/`Ctrl+↓` volume · `Alt+←` voltar · `Esc` limpar a busca.
Menu do botão direito nas músicas e na fila.

- **Enviar e receber arquivos** com o celular vinculado (menu “Enviar para…”), com pergunta antes
  de receber, retomada após queda e conferência de integridade.
- **Descobrir**: Internet Archive (com a licença declarada) e links diretos HTTPS; baixar e,
  se quiser, enviar ao celular. YouTube/Spotify só abrem no app oficial.
- **Together**: criar ou entrar numa jam com celulares Android (rede local ou internet), aprovar,
  expulsar, invalidar convites; músicas que faltam podem vir do anfitrião (opcional).
- **Windows**: teclas de mídia do teclado com a janela sem foco, ícone na área de notificação,
  barra de título escura.

Ainda não existe nesta versão (e por isso não aparece na interface): efeitos de letra, equalizador,
painel de mídia do Windows (SMTC), atualização automática.

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
