# Política de privacidade do AURA

Vale para o AURA para Android e o AURA Desktop. Última atualização: 29 de outubro de 2026.

O AURA é um player de músicas que funciona sem conta e sem internet. Não há anúncios, análise de
uso, rastreamento nem servidores que guardem dados seus. Esta página lista **tudo** o que pode sair
do seu aparelho, quando e para onde.

## O que fica só no seu aparelho

- Seus arquivos de música e a biblioteca (pastas, títulos, capas).
- Playlists, favoritas, histórico de reprodução e de busca, estatísticas e retrospectiva.
- Configurações, letras salvas, análise de áudio, equalizador e mixes.
- Backups: o arquivo é criado onde você escolher e o AURA não o envia a lugar nenhum.

## O que pode sair, e só se você usar o recurso

| Recurso | Para onde | O que é enviado | Como controlar |
|---|---|---|---|
| Letras online | [LRCLIB](https://lrclib.net) | Título, artista, álbum e duração de músicas sem letra | Desligado até você permitir; Configurações |
| Capas online e “Corrigir informações” | [MusicBrainz](https://musicbrainz.org) e Cover Art Archive | Nome do álbum e do artista (ou o texto que você digitar) | Desligado até você permitir; Configurações |
| Descobrir | [Internet Archive](https://archive.org) | O que você digitar na busca | Só quando você busca |
| Links colados no Descobrir | O site do link; YouTube/Spotify (oEmbed) | O próprio link, para mostrar título e capa | Só quando você cola um link |
| Downloads | O site de origem do arquivo | O pedido do arquivo | Só quando você baixa |
| AURA Together (jams) | Outros participantes; pela internet, via retransmissor do AURA | Fila, controles, nome que você escolher e, se faltar a alguém, a música que está tocando | Só quando você cria ou entra numa jam |
| AURA Link (celular ↔ computador) | Só os seus aparelhos, na rede local | Controles, estado do player, a biblioteca (se você permitir) e arquivos que você enviar | Só depois de vincular; permissões por aparelho |

Esses serviços externos têm suas próprias políticas de privacidade e, como qualquer site, recebem
o endereço IP de quem faz o pedido.

## Jams (AURA Together)

- Tudo é cifrado de ponta a ponta com uma chave que só existe no link do convite. O retransmissor
  (um Cloudflare Worker) apenas repassa dados cifrados e **não consegue ler** músicas, nomes ou
  comandos.
- O retransmissor vê, como qualquer servidor, o endereço IP de quem conecta, o horário e o volume
  de dados. Ele não guarda histórico: a sala é apagada até 15 minutos depois que o anfitrião sai.
- Quem tem o link pode pedir para entrar; por padrão o anfitrião aprova cada pessoa. O anfitrião
  pode expulsar participantes e invalidar convites.
- Músicas que faltam a um participante são enviadas como cópia temporária, apagada quando ele sai
  da jam.

## Vínculo com o computador (AURA Link)

- Funciona só na rede local, sem passar por servidores.
- O vínculo exige o código ou QR Code mostrado no computador e a confirmação nos dois aparelhos.
  Cada aparelho guarda uma chave própria no cofre do sistema (Android Keystore; DPAPI no Windows).
- Enquanto espera conexões, o computador anuncia na rede local um nome (o nome que você deu a ele)
  para o celular o encontrar.
- Você escolhe por aparelho o que ele pode fazer e pode remover o vínculo a qualquer momento.

## Permissões do Android

- **Arquivos de áudio** (ou armazenamento, até o Android 12): para montar a biblioteca.
- **Câmera**: só na tela de vincular computador, para ler o QR Code. Nenhuma imagem é guardada.
- **Rede**: recursos acima e descoberta de aparelhos na rede local.

## Crianças

O AURA não coleta dados pessoais de ninguém, de nenhuma idade.

## Mudanças e contato

Mudanças nesta política aparecem no histórico deste arquivo no
[repositório do projeto](https://github.com/joaomanoelaraujo/aura-music/blob/main/PRIVACY.md).
Dúvidas: abra uma *issue* no repositório.
