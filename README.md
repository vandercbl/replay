# Replay30 — Android Kotlin + GitHub Actions

Projeto inicial de replay esportivo instantâneo. O app mantém segmentos MP4 temporários da câmera traseira e oferece comandos para tentar montar um único MP4 de 30 ou 60 segundos. Os arquivos são salvos localmente no armazenamento específico do aplicativo (`Android/data/.../files/replays`).

## Gerar APK no GitHub Actions
1. Extraia este ZIP e envie o conteúdo da pasta `Replay30` para a raiz de um repositório GitHub.
2. Na aba **Actions**, execute **Build Android APK** (ou faça push para `main`/`master`).
3. Ao concluir, baixe o artefato `Replay30-debug-apk` e extraia `app-debug.apk`.
4. Instale no Android permitindo a instalação de apps desta origem.

## Uso
- Abra o app e permita câmera/microfone. Mantenha a tela ligada e o preview visível.
- Aguarde o buffer acumular cerca de 30 ou 60 segundos.
- Use os botões de 30s/60s. O arquivo resultante é gravado localmente.
- Para acionar de outro celular, conecte ambos à mesma rede Wi-Fi e abra o endereço local mostrado na tela, na porta `8765`.
- Alguns controles Bluetooth que simulam teclas de volume/câmera podem acionar replay de 30s enquanto o app está em primeiro plano. A compatibilidade varia conforme o controle.

## Estado e limitações
Este é um protótipo inicial e ainda precisa de build e teste em aparelho real. A captura é feita em segmentos curtos e a junção de MP4 pode falhar se o aparelho produzir formatos de áudio/vídeo incompatíveis; a troca entre segmentos também pode gerar pequenas lacunas. O controle Bluetooth depende do perfil do dispositivo. O servidor HTTP local não tem autenticação: use apenas em uma rede confiável. A gravação depende de manter o app aberto em primeiro plano.
