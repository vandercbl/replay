# Replay30 — captura contínua com áudio

Aplicativo Android de replay esportivo com captura contínua, buffer circular, áudio AAC, vídeo H.264, salvamento de replay de 30/60 segundos e controle remoto por navegador na rede local.

## Correção do QR Code / endereço IP

A tela de QR Code agora prioriza o IPv4 anunciado nas propriedades da conexão Wi-Fi ativa. Se isso não estiver disponível, tenta interfaces de rede locais com nomes típicos de Wi-Fi/hotspot e, por último, o endereço DHCP do Wi-Fi. O servidor HTTP escuta explicitamente em `0.0.0.0:8765` para aceitar conexões IPv4 na rede local.

Para controlar o app, conecte os dois celulares à mesma rede Wi-Fi e abra o QR Code no Replay30. Algumas redes de convidados, redes corporativas ou hotspots podem bloquear comunicação entre dispositivos; nesse caso, use uma rede que permita conexões locais entre os celulares.

## Build

Abra o projeto no Android Studio ou envie o conteúdo para o repositório GitHub configurado com o workflow de build. Compile e teste no dispositivo real antes de usar em atividade esportiva.

Permissões necessárias: câmera e microfone. O controle remoto funciona localmente pela porta TCP `8765` e não usa nuvem.


## Detecção opcional de gestos (palma aberta, paz e amor ou polegar para cima)

A versão inclui um botão **GESTO ✋ / ✌️ / 👍: DESATIVADO/ATIVADO**. Quando ativada, a detecção analisa uma cópia reduzida do preview localmente no aparelho, preservando a proporção real da imagem, usando MediaPipe Gesture Recognizer. Aceita `Open_Palm` (palma aberta, mais fácil de fazer à distância), `Victory` (paz e amor) ou `Thumb_Up` (polegar para cima). Para lidar com diferenças de orientação da prévia em aparelhos distintos, tenta a imagem original e, se necessário, versões giradas em 90° e 270°; essa análise extra só acontece quando a primeira tentativa não reconhece o gesto. Ao reconhecer uma dessas poses em três análises consecutivas, toca um som de confirmação e dispara o salvamento dos últimos 30 segundos, se o buffer já tiver pelo menos 30 segundos. Para evitar repetições, é necessário retirar a mão da pose antes de acionar novamente; há também um intervalo mínimo de 8 segundos entre disparos. A opção fica salva nas preferências do app e pode ser desligada a qualquer momento.

O modelo de reconhecimento é baixado durante a compilação pelo Gradle, incluído no APK e funciona offline depois da instalação. É necessário ter internet disponível no primeiro build. A análise é feita em imagens com o lado maior limitado a 320 pixels, mantendo a proporção do preview, aproximadamente a cada 300 ms, para limitar o uso de recursos. Como qualquer reconhecimento visual, o resultado depende da iluminação, distância, enquadramento e visibilidade dos dedos; teste a funcionalidade antes de usar em uma atividade esportiva. O gesto é processado no próprio celular e os frames analisados não são enviados para a rede.
