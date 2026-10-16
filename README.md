# Replay30 — Android Kotlin + GitHub Actions

Projeto inicial de replay esportivo instantâneo. O app mantém segmentos MP4 temporários da câmera traseira e oferece comandos para tentar montar um único MP4 de 30 ou 60 segundos. Os replays finalizados são publicados na galeria do Android, na pasta `Movies/Replay30`; os segmentos temporários ficam no armazenamento específico do aplicativo.

## Gerar APK no GitHub Actions
1. Extraia este ZIP e envie o conteúdo da pasta `Replay30` para a raiz de um repositório GitHub.
2. Na aba **Actions**, execute **Build Android APK** (ou faça push para `main`/`master`).
3. Ao concluir, baixe o artefato `Replay30-debug-apk` e extraia `app-debug.apk`.
4. Instale no Android permitindo a instalação de apps desta origem.

## Uso
- Abra o app e permita câmera/microfone. Mantenha a tela ligada e o preview visível.
- Aguarde o buffer acumular cerca de 30 ou 60 segundos.
- Use os botões de 30s/60s. O arquivo resultante é gravado localmente.
- Para acionar de outro celular, conecte ambos à mesma rede Wi-Fi. No celular que grava, toque em **CONTROLE REMOTO — QR CODE**; no segundo, leia o código com a câmera e abra o endereço no navegador. Use os botões para salvar 30 ou 60 segundos. O servidor local usa a porta `8765`.
- Alguns controles Bluetooth que simulam teclas de volume/câmera podem acionar replay de 30s enquanto o app está em primeiro plano. A compatibilidade varia conforme o controle.

## Estado e limitações
Este é um protótipo inicial e ainda precisa de build e teste em aparelho real. A captura é feita em segmentos curtos e a junção de MP4 pode falhar se o aparelho produzir formatos de áudio/vídeo incompatíveis; a troca entre segmentos também pode gerar pequenas lacunas. O controle remoto por QR Code depende de ambos os celulares estarem na mesma rede Wi-Fi (ou numa rede local que permita comunicação entre dispositivos). O QR Code apenas abre o endereço local; não cria a conexão Wi-Fi. Algumas redes de convidados bloqueiam a comunicação entre aparelhos. A tela do QR Code só funciona quando o Android consegue identificar o IP da rede. O servidor HTTP local não tem autenticação: use apenas em uma rede confiável. A gravação depende de manter o app aberto em primeiro plano.


## Ajustes de continuidade do vídeo

Esta versão inclui correções na montagem do replay: seleciona os segmentos mais recentes (6 para 30 s e 12 para 60 s, com segmentos de 5 s), normaliza os timestamps de cada segmento ao concatenar e remove a espera artificial de 120 ms entre segmentos. Isso corrige erros de seleção de trechos antigos e de sobreposição de timestamps na montagem. Como a captura ainda reinicia a gravação a cada segmento, pequenos intervalos físicos podem continuar dependendo do aparelho/CameraX.
