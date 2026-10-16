# Replay30 — captura contínua com áudio (protótipo)

Esta versão adiciona captura contínua de áudio à arquitetura Camera2 + MediaCodec:

- Camera2 mantém a câmera aberta e entrega frames continuamente ao encoder H.264.
- O microfone é capturado continuamente com `AudioRecord` e codificado em AAC a 48 kHz mono.
- Áudio e vídeo são mantidos em buffers circulares limitados por tempo e memória.
- Ao solicitar replay de 30 ou 60 segundos, o app muxa as amostras de áudio e vídeo em um MP4 sem reiniciar a câmera.
- Mantém preview, orientação vertical, botões de replay, QR Code e controle remoto por Wi-Fi.
- Os MP4 são publicados em `Movies/Replay30`.

## Permissões

O aplicativo precisa de permissão para câmera e microfone. Se o acesso ao microfone for negado, a captura não inicia, pois esta versão foi preparada para gerar replay com áudio.

## Importante

Este código-fonte foi alterado para incluir áudio, mas não foi possível compilar nem testar em um aparelho Android real neste ambiente. A sincronização, o encoder AAC disponível no aparelho e o comportamento do buffer precisam ser validados no dispositivo. Faça um teste curto falando perto do celular e reproduzindo o MP4 antes de usar em uma gravação importante.

## Build pelo GitHub Actions

Envie o conteúdo da pasta `Replay30` para a raiz do repositório e execute o workflow **Build Android APK** em Actions. O artefato esperado é `Replay30-debug-apk`.
