# Norns Mobile

Aplicativo Android experimental para executar scripts Lua do ecossistema monome norns localmente, com tela 128×64, parâmetros, MIDI, OSC e áudio integrado.

> **Estado atual:** linha de desenvolvimento 0.4.5 na branch `feature/0.4.5-android-audio-driver`. O projeto está em implementação e ainda não declara compatibilidade integral com o norns original. O sucesso de CI valida compilação e testes automatizados, não substitui validação física de áudio, MIDI ou periféricos.

## Objetivo

Executar no Android scripts norns com o mínimo possível de adaptações, preservando o comportamento do áudio Java existente enquanto a integração SuperCollider/Oboe e o Softcut são validados com segurança.

## Estado técnico atual

| Área | Estado |
|---|---|
| Aplicativo Android | versão 0.4.5; minSdk 28 (Android 9), ABI ARM64 |
| Runtime Lua | LuaJ 3.0.1; boot, bibliotecas norns e armazenamento Dust implementados, com diferenças de Lua 5.2 documentadas no roadmap |
| Interface | Biblioteca/editor, tela Norns 128×64, controles touch K1–K3/E1–E3, PARAMS e LOG |
| Screen | Primitivas, transformações, PNG/imagens, regiões, draw_to, peek/poke, blend modes e orçamento agregado de imagens de 32 MiB; auditoria runtime pendente |
| Engines | PolyPerc, TestSine, None e 32Harmony com SynthDef Android pré-compilado (CI ainda pendente); engines SC arbitrárias ainda dependem de sclang/SCClassLibrary/Quarks |
| Áudio | AudioTrack Java preservado; núcleo scsynth 3.14.1 ARM64 e integração Oboe compilados em CI; exclusividade e handoff runtime ainda não certificados |
| MIDI | Ponte Android MIDI, portas, mensagens, clock/transporte e SysEx parcial; USB/BLE-MIDI ainda requerem validação física |
| USB Serial | Backend experimental Android USB Host CDC-ACM 8-N-1, permissão, hotplug, leitura/escrita binária; sem FTDI/CP210x/CH340 |
| Crow | Integração experimental com crow USB CDC-ACM (0483:5740), comandos Lua, eventos `^^`, namespaces input/output/ii e upload de scripts; `public` parcial e validação física pendente |
| Grid/Arc | Backend experimental serialosc OSC/UDP: descoberta, portas virtuais, LEDs Grid/Arc e callbacks de teclas/encoders; requer serialosc na mesma rede e validação com hardware real |
| OSC | UDP, mensagens tipadas, bundles e agendamento NTP implementados; validação em rede real pendente |
| Poll | Entrada permanece mono; CPU médio/pico e picos PCM de saída vêm do callback Oboe quando scsynth está ativo; fallback Java mantido |
| Softcut | API e gravadores em desenvolvimento; playback nativo bloqueado até prova independente CH1/CH2, estéreo e ausência de duplicação |
| Keyboard/Gamepad | Teclado físico e gamepad Android genérico, incluindo hotplug básico; validação com hardware real pendente |

## Segurança de áudio

As seguintes restrições são intencionais:

- PolyPerc e fallback AudioTrack Java devem continuar disponíveis.
- `native_gate_open=false` permanece obrigatório até as provas de áudio exigidas.
- Nenhum playback Softcut nativo ou seis vozes será habilitado antes de comprovar gravação/leitura independente de CH1 e CH2, roteamento PCM estéreo e ausência de duplicação.
- O handoff Oboe/scsynth ↔ AudioTrack Java exige ownership exclusivo, mute coordenado e testes de transição.
- CI de compilação não comprova comportamento acústico no aparelho.

## Build e CI

A compilação é executada pelo GitHub Actions, sem exigir Android Studio local.

Workflows principais:

- **Experimental scsynth Android probe**: compila/investiga o núcleo SuperCollider para Android ARM64.
- **Norns Mobile 0.4.5 — Integrate scsynth Oboe**: importa o payload nativo, executa testes e monta o APK.
- **Norns Mobile APK**: build Android independente.

O artefato de APK é um build de depuração. Consulte a aba Actions do repositório para o SHA exato, logs e validade do artefato. Um probe verde não comprova sozinho que o servidor nativo funciona em hardware.

## Verificação no aparelho

Roteiro para o Galaxy S10 em [docs/DEVICE_TEST_S10.md](docs/DEVICE_TEST_S10.md); script de apoio `device_check` incluído na biblioteca.

## Compatibilidade e limitações

A compatibilidade é incremental e deve ser avaliada por API, engine e script. LuaJ não é o runtime Lua do norns Linux; classes SuperCollider e Quarks não se tornam executáveis apenas porque `libscsynth` está empacotada. APIs de hardware externo exigem backend e periférico correspondente.

A matriz detalhada, evidências de CI, pendências e critérios de aceite ficam em:

- [Roadmap global](docs/ROADMAP_MOBILE.md)
- [Roadmap de áudio nativo](docs/ROADMAP_NATIVE_AUDIO.md)

## Política de desenvolvimento

- Desenvolver somente em `feature/0.4.5-android-audio-driver`; preservar `main`.
- Não substituir silenciosamente engines indisponíveis por PolyPerc.
- Não declarar suporte quando existir apenas stub, contrato ou compilação.
- Atualizar os roadmaps conforme cada implementação e validação.
- Fazer a auditoria global e a bateria final de dispositivo somente depois de concluir as implementações e testes automatizáveis restantes.
