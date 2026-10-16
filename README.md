# Replay30 — captura contínua com áudio

Aplicativo Android de replay esportivo com captura contínua, buffer circular, áudio AAC, vídeo H.264, salvamento de replay de 30/60 segundos e controle remoto por navegador na rede local.

## Correção do QR Code / endereço IP

A tela de QR Code agora prioriza o IPv4 anunciado nas propriedades da conexão Wi-Fi ativa. Se isso não estiver disponível, tenta interfaces de rede locais com nomes típicos de Wi-Fi/hotspot e, por último, o endereço DHCP do Wi-Fi. O servidor HTTP escuta explicitamente em `0.0.0.0:8765` para aceitar conexões IPv4 na rede local.

Para controlar o app, conecte os dois celulares à mesma rede Wi-Fi e abra o QR Code no Replay30. Algumas redes de convidados, redes corporativas ou hotspots podem bloquear comunicação entre dispositivos; nesse caso, use uma rede que permita conexões locais entre os celulares.

## Build

Abra o projeto no Android Studio ou envie o conteúdo para o repositório GitHub configurado com o workflow de build. Compile e teste no dispositivo real antes de usar em atividade esportiva.

Permissões necessárias: câmera e microfone. O controle remoto funciona localmente pela porta TCP `8765` e não usa nuvem.
