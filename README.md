# Replay30 — captura contínua (protótipo)

Esta versão substitui a gravação em segmentos de 5 segundos por uma captura contínua com Camera2 + MediaCodec:

- Camera2 mantém a câmera aberta e entrega frames continuamente ao encoder H.264.
- O app mantém amostras codificadas em um buffer circular limitado por tempo e memória.
- Ao pedir replay de 30 ou 60 segundos, cria um MP4 a partir do buffer sem parar a captura.
- Mantém preview, orientação vertical, botões de replay, QR Code e controle remoto por Wi-Fi, além de publicar o MP4 em `Movies/Replay30`.

## Limitações desta versão

- O encoder de áudio ainda não foi integrado nesta implementação; os MP4 gerados contêm vídeo sem áudio. A versão antiga usava áudio nos segmentos, portanto isso é uma limitação conhecida deste protótipo contínuo.
- A escolha de resolução e rotação varia conforme o aparelho. Compile pelo GitHub Actions e teste no celular antes de usar em uma gravação importante.
- Não foi possível compilar nem testar em aparelho real neste ambiente. O arquivo ZIP contém o código-fonte, não um APK validado.

## Build

Envie os arquivos do projeto ao repositório GitHub e execute o workflow **Build Android APK** em Actions. O artefato esperado é `Replay30-debug-apk`.
