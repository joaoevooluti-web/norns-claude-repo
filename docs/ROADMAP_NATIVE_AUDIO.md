# Plano de áudio nativo — Norns Mobile

## Estado consolidado atual — 2026-11-08 (prevalece sobre notas históricas abaixo)

- Branch: `feature/0.4.5-android-audio-driver`; HEAD verificado nesta auditoria: `21d6cb6fd2a66c4f8cd69dfe79ee9431439eac0a`; `main` preservada em `82fcedd60f76707ada4d25185837894a2e6664cd`.
- Base funcional da auditoria: build #495, commit `f5a8c6f2b952747542c16926c7edf6a99953c925`. A comparação confirma 132 commits até `07b22b1be93cf95fa9f3d39b01c64d9d63dc6a57` e mais 5 commits até o HEAD atual (137 no total).
- CI do HEAD antigo `3fdde6b`: probe `42476059280`, integração `42476059246` e APK `42476059258` encerraram com jobs `failure`, `runner_id=0`/sem steps e sem logs recuperáveis; isso não demonstra falha de compilação e não valida o HEAD atual. JUnit/SynthDef/APK do HEAD `21d6cb6` permanecem pendentes.
- Os 5 commits adicionais após `07b22b1` introduzem Grid/Arc via serialosc e correções de semântica/contrato; estão fora do lote original de 132 e serão auditados em separado.
- PolyPerc e fallback Java permanecem preservados. Keyboard/Gamepad Android não alteram áudio.
- 32Harmony recebeu SynthDef estéreo pré-compilado de 32 vozes e bridge de comandos SC; compilação e comportamento ainda sem CI/hardware devido à falha de alocação dos runners.
- Poll de saída consulta picos PCM finais do Oboe para amp_out_l/r; `cpu_avg`/`cpu_peak` agora são medidos no callback Oboe/scsynth e lidos via snapshots atômicos. Entrada/pitch ainda permanecem mono/Java.
- scsynth 3.14.1 ARM64/Oboe e plugins foram empacotados em builds anteriores, mas não há prova acústica de ownership/handoff no dispositivo para este HEAD.
- Buses engine→tape e engine/tape→Softcut continuam pendentes.
- Softcut: `native_gate_open=false`; playback nativo/seis vozes bloqueados até prova independente de BufWr CH1/CH2, leitura independente, roteamento PCM estéreo e ausência de duplicação.
- Não solicitar testes físicos no SM-G973F até concluir implementações e validações automatizadas restantes.

## Fase A — MIDI (0.3.0)
- API Android `android.media.midi` para USB, BLE-MIDI e virtual MIDI.
- Enumeração por sessão, abertura/fechamento de dispositivo, envio e callback de entrada.
- Não requer servidor externo. A presença de BLE/USB depende do hardware Android e dos periféricos.

## Fase B — OSC (0.3.x)
- UDP IPv4/IPv6, encoder/decoder OSC 1.0 (strings padded, int32, float32, blobs).
- Socket de recepção em worker dedicado; eventos Lua serializados para a thread Lua.
- Restringir bind a interfaces configuradas pelo usuário e encerrar sockets no cleanup.

## Fase C — SuperCollider embutido (0.4.x)
- Portar `scsynth`/`libscsynth` com Android NDK, CMake e toolchain por ABI (`arm64-v8a` primeiro).
- Integrar áudio via Oboe/AAudio; manter o motor PolyPerc como fallback de baixo consumo.
- Expor boot/shutdown, SynthDef loading, node create/set/free e notificações de status por JNI.
- Tratar `sclang` como segundo projeto: runtime, biblioteca padrão, parser/compiler e carregamento de classes/Quarks. Não assumir que incluir apenas `scsynth` fornece a linguagem SuperCollider.
- Licenças e dependências upstream devem ser preservadas; builds reproduzíveis e atribuições incluídas.

## Fase D — Crow e Just Friends (0.5.x)
- [x] Backend Crow CDC-ACM experimental integrado à API serial, reconhecendo VID:PID 0483:5740 e encaminhando comandos/eventos Lua.
- [ ] Validar Crow físico e completar public/ii; isso não altera o gate de áudio.
- Implementar transporte serial, framing/protocolo, reconnect, watchdog e API Lua `crow.input/output`.
- Just Friends é um módulo Eurorack da Make Noise; suporte depende do protocolo/firmware usado (por exemplo, conexão ii através do Crow). Implementar apenas após validar hardware e protocolo documentado.

## Critérios de aceite
- Build release reproduzível no CI.
- Testes de parsing MIDI/OSC sem hardware.
- Teste real de envio e recepção MIDI USB e BLE em aparelho físico.
- Áudio sem underruns prolongados; ciclo de vida e limpeza de threads/ports/sockets.
- Nenhum recurso marcado como completo enquanto não tiver teste no Android.


## Atualização 0.4.4
- [x] Incorporar `libscsynth.so` ARM64 do probe 20.
- [x] Substituir assets pelos 23 plugins UGen ARM64 compilados.
- [x] Vincular `libscsynth.so` à ponte JNI.
- [x] Configurar runtime `c++_shared` no build Android.
- [ ] Implementar e validar driver Oboe/AAudio ligado ao callback do scsynth.
- [ ] Boot/shutdown real do servidor e teste de áudio no aparelho.


## Atualização 0.4.5 — telemetria de fase (em validação)

- [x] Separar o rastreamento de fase do caminho audível: `phase_play` e
  `phase_enable` movimentam somente o testemunho de fase; `play` nativo
  permanece em zero.
- [x] Publicar posição das seis vozes em buses internos 11–16, sem copiá-los
  para a saída Oboe.
- [x] Expor fotografia de diagnóstico com as seis posições e picos dos buses
  master 0/1, Softcut 9/10 e saída Oboe após o gate.
- [x] Medir erro Java↔nativo por voz, usar diferença circular durante loop e
  acumular último erro, máximo absoluto e RMS.
- [x] Exigir que o artefato da probe ARM64 usado pelo CI tenha o mesmo
  `headSha` do commit que disparou a integração.
- [ ] Executar ensaios no SM-G973F com seis vozes, rates positivos/negativos,
  loop ligado/desligado e passagens pelas bordas do loop.
- [ ] Confirmar continuidade acústica e ausência de duplicação com captura
  externa. Picos de buses digitais não substituem medição acústica.
- [ ] Não liberar `play` nativo até aprovação explícita desses ensaios.

O supervisor agenda uma leitura a cada 33 ms para as seis vozes,
independentemente de chamadas a `softcut.get_position(v)`. O acumulado por
voz fica em `softcut.native_phase_diagnostics(v)`; frescor, reancoragens e
erros ficam em `softcut.native_supervisor_diagnostics()`; os picos de rota
ficam em `softcut.native_routing_diagnostics()`.

A meta inicial para investigação é RMS até dois frames e máximo até um bloco
SC, calculados com a taxa real reportada. Isso não é aprovação automática:
cada voz deve ser medida por pelo menos 60 s em rate 1, -1, 2 e -2, com
mudanças de rate e transições nas bordas do loop. A correção periódica do
tracker não prova, por si só, continuidade acústica nem ausência de áudio
duplicado.


### Correção de telemetria — snapshot coerente e pico pós-gate

- [x] Publicação dos 16 valores de diagnóstico por snapshot versionado com
  sequência atômica; leitores rejeitam snapshots parciais ou concorrentes.
- [x] Medição do pico Oboe diretamente no buffer PCM final entregue pelo
  callback, após o gate, em vez de inferir a saída pelos buses de origem.
- [x] Teste C++ concorrente para garantir que os leitores nunca aceitem
  combinações de campos de duas publicações distintas.
- [ ] Confirmar no SM-G973F que o pico PCM zera após a conclusão de um callback
  com o gate fechado e que os taps de fase permanecem válidos nas seis vozes.


### Correção do contrato do SynthDef

- [x] Atualizar o validador para incluir `position_bus`, `phase_play` e
  `phase_enable` na interface pública do `SoftcutVoice`.
- [x] Validar os defaults: saída 9, tap inicial em 11, phase tracker parado e
  phase enable habilitado.
- [x] Verificar no grafo UGen que `phase_play` e `phase_enable` chegam ao
  passo do Phasor e `position_bus` chega a um `Out` independente.
- [x] Reexecutar a integração com probe ARM64 do mesmo SHA e obter novo APK.


### CI — checkout e prova de SHA

- [x] Integração faz checkout do SHA exato do evento, sem resolver novamente
  o nome móvel da branch.
- [x] Probe selecionada é validada por `headSha` mesmo quando o run ID é
  fornecido manualmente.
- [x] Alterações no workflow de integração também disparam a probe necessária
  para manter a correspondência exata de SHA.
- [ ] Confirmar a execução completa de probe, validação SynthDef e APK no
  mesmo SHA da branch.


### Supervisor periódico Softcut — implementação inicial

- [x] Publicar contador de geração no callback Oboe e recusar snapshots
  antes da primeira publicação.
- [x] Marcar snapshot stale quando a geração não avança por 180 ms; dados
  stale não alimentam métricas nem reancoragens.
- [x] Amostrar automaticamente as seis posições a cada 33 ms,
  independentemente de `get_position()`.
- [x] Reancorar apenas o Synth node do testemunho de fase; sem tocar no
  SoftcutRecorder, gate Oboe, AudioTrack ou controle `play`.
- [x] Limitar a correção a erros acima de um bloco SC (ou dois frames) e
  aplicar cooldown de 200 ms por voz.
- [x] Bloquear reancoragens quando o gate nativo estiver aberto.
- [x] Expor estado por voz e estado geral em
  `softcut.native_supervisor_diagnostics()`.
- [x] Adicionar testes de frescor, timeout, reset e limiar de correção.
- [x] Passar validadores, testes unitários e gerar novo APK no CI.
- [ ] Validar seis vozes, rates, loops, gravação e recuperação no SM-G973F.


### Instrumentação independente dos dois cursores BufWr — implementação inicial

- [x] Adicionar testemunho de posição em segundos ao SynthDef `SoftcutRecorder`;
  buses 17/18 são telemetria e nunca entram na cópia estéreo para Oboe.
- [x] Amostrar separadamente os dois cursores no callback Oboe e transportá-los
  no snapshot versionado de 16 campos.
- [x] Expor por canal a posição nativa, o estado confirmado/pending de gravação
  e o estado de reancoragem pendente.
- [x] Medir divergência Java↔BufWr por canal, com erro, máximo absoluto, RMS
  e limiar de um bloco SC ou dois frames.
- [x] Bloquear reancoragem enquanto start, gravação ou stop ainda não foi
  confirmado pelo worker Java.
- [x] Em caso de divergência durante escrita contínua, marcar recovery pending;
  não reposicionar o cursor em gravação.
- [x] Após confirmação de stop por `/sync`, permitir reancoragem serializada
  para a posição Java capturada no instante da parada.
- [x] Re-seedar a posição a partir do cursor Java atual em cada nova sessão.
- [x] Manter estado de gravação ativo/incerto quando o stop falha; retry stop
  é solicitado pelo supervisor e o cursor não é reancorado nesse estado.
- [x] Adicionar testes unitários para gravação contínua, rate negativo/loop,
  divergência durante gravação, recovery em idle e convergência.
- [x] Passar CI ARM64, validador SynthDef, testes unitários e APK 0.4.5.
- [ ] Confirmar captura de seis vozes + dois gravadores em gravação simultânea
  e readback real no SM-G973F.
- [ ] Validar cursor BufWr, continuidade de gravação, parada/reinício e
  readback por captura externa; não liberar `play` nativo.


### CI e refinamento dos cursores BufWr — nova rodada

- [x] Probe ARM64 aprovada para o commit `0b23e1a3`.
- [x] Identificada falha no validador: asserção obsoleta de 14 campos após
  a expansão do snapshot para 16.
- [x] Corrigir o testemunho para publicar `writePhase`, incluindo o
  `rec_offset` efetivamente aplicado ao `BufWr`.
- [x] Comparar `Java position + rec_offset` com o cursor nativo de escrita,
  mas reancorar com a cabeça Java base para não aplicar offset duas vezes.
- [x] Corrigir a ordem start→stop quando um stop chega enquanto o start está pendente.
- [x] Adicionar testes de avanço contínuo em rate positivo/negativo e
  preservação da região lida de volta.
- [x] Nova integração no mesmo SHA: validação SynthDef, JUnit, APK e artefatos.
- [ ] Ensaios físicos dos dois buffers no SM-G973F: gravação simultânea,
  parada, readback e recuperação.


### Robustez do supervisor e readback dos dois buffers

- [x] Marcar cursor BufWr como stale se o bus 17/18 estiver ausente durante
  gravação ou recuperação pendente; não preservar métricas antigas como frescas.
- [x] Testar readback independente dos buffers Java 1 e 2.
- [x] Testar que recuperação é recusada quando o servidor nativo não está pronto.
- [x] Validar compilação ARM64, stop/readback por contrato de software, SynthDef, JUnit e APK via CI.
- [ ] Confirmar gravação simultânea real dos dois buffers e continuidade no aparelho.


### Correção do gate CI — validação estática do cursor BufWr

- [x] Probe SuperCollider 3.14.1 Android ARM64 aprovada no commit `821b2dd`.
- [x] Corrigir NameError do validador: a leitura de `SuperColliderSoftcutBuffers.java`
  agora é inicializada antes da asserção da ordem start/stop.
- [ ] Reexecutar o validador, compilar o SynthDef e executar JUnit/APK.
- [ ] Confirmar as rotinas de gravação contínua, parada, readback e recuperação
  em scsynth e no dispositivo com os dois BufWr.


### Alinhamento do wrap de gravação e janela de readback

- [x] Separar limites base do Phasor (idênticos a `loop_start/loop_end` do Java)
  da janela expandida de readback (que inclui `rec_offset`).
- [x] Manter reserva de capacidade até o fim expandido para offset positivo.
- [x] Ao parar, ler de volta a janela estendida para capturar amostras gravadas fora
  do intervalo base do loop.
- [x] Confirmar SynthDef, JUnit e APK no CI após as correções mais recentes.
- [ ] Validar gravação simultânea, parada, readback e recuperação nos dois BufWr
  em aparelho físico.


### CI aprovado — APK 0.4.5 e validação pré-dispositivo

- [x] Probe ARM64 SuperCollider 3.14.1: workflow 42267355835 aprovado.
- [x] Integração scsynth/Oboe: workflow 42267355663 aprovado no mesmo SHA
  c6273ecbb843016458979d0c9a23234e8c4749df.
- [x] Validador de roteamento/gate aprovado.
- [x] Compilação do SynthDef SoftcutRecorder e validação do bundle aprovadas;
  witness conectado à fase efetiva de escrita do BufWr.
- [x] testDebugUnitTest executado com sucesso.
- [x] APK debug 0.4.5 (version code 226) construído e bibliotecas ARM64 verificadas.
- [x] Artefato APK publicado: NornsMobile-0.4.5-debug-226.
  SHA-256 do APK: 869441641a33e4459eb6a6452e6d6dcbab05cc91957f54f9b975fedc10ca1843.
- [ ] No SM-G973F: validar simultaneamente CH1 (+rate/+offset) e CH2
  (-rate/-offset), frescor dos dois testemunhos, estabilidade do erro e wraps.
- [ ] Confirmar que stop de ambos é confirmado, que o readback de cada
  região chega ao buffer Java correto e que a leitura posterior mostra dados.
- [ ] Validar recuperação pós-stop no dispositivo; nenhuma reancoragem durante
  gravação, nenhuma duplicação acústica e gate nativo permanentemente fechado.
- [ ] Manter play nativo bloqueado até finalizar os ensaios reais.


### BufWr — gate de escrita e readback serializado (correção em CI)

- [x] Identificar que `SoftcutRecorder` declara `enabled=0` e multiplica
  `record * enabled`; a chamada anterior só enviava `record=1`, mantendo
  o `BufWr` efetivamente bloqueado.
- [x] No start, enviar `enabled=1` e `record=1` no mesmo `/n_set`.
- [x] No stop, enviar `record=0` e `enabled=0` antes do `/sync`.
- [x] Manter o readback no mesmo worker que bombeia respostas OSC, evitando
  consumidores concorrentes de `/synced` e `/b_setn`.
- [x] Adicionar teste de contrato de fonte `tools/test-softcut-recorder-gate.py`
  e incluí-lo no workflow de integração.
- [x] Fazer o workflow disparar também quando o próprio teste de contrato mudar.
- [x] Em timeout de `/sync` após start, fechar `record=0` e
  `enabled=0`; manter o estado conservador como ativo/incerto para impedir
  reancoragem até stop confirmado.
- [x] Cobrir no teste estático o mapeamento Java dos dois buffers nativos
  (20001 e 20002), sem duplicar o SynthDef parametrizado.
- [ ] Executar CI no SHA desta correção e confirmar compilação SynthDef/JUnit/APK.
  A consulta de runs disponível nesta sessão só expõe execuções associadas a
  pull requests; não há evidência suficiente para declarar o push workflow aprovado.
- [ ] Executar o teste de contrato e confirmar o resultado real; a alteração
  do teste não equivale à sua execução local ou no CI.
- [ ] Repetir gravação simultânea de CH1 e CH2 no SM-G973F; exigir readback
  com peak/RMS não nulos e compatíveis com a entrada.
- [ ] Manter `native_gate_open=false`; PolyPerc e fallback Java preservados.
- [ ] Não iniciar handoff nem habilitar reprodução Softcut nativa antes da
  aprovação física dos dois buffers.


### Acompanhamento CI — correção do filtro da probe (2026-11-07)

- [x] Commit `1517277d58c6cef6a7c3c04c0ef14fd88741f93c`: incluir
  `tools/test-softcut-recorder-gate.py` nos filtros `push.paths` e
  `pull_request.paths` de `.github/workflows/scsynth-android-probe.yml`.
- [x] Disparar probe e integração para o mesmo SHA
  `1517277d58c6cef6a7c3c04c0ef14fd88741f93c`.
- [x] Confirmar checkout da integração no SHA exato do evento.
- [x] Confirmar setup inicial da probe e da integração concluído com sucesso.
- [ ] Probe ARM64: execução `42328923463`, job
  `128270668374` — em andamento na etapa "Install Android NDK and CMake"
  na última consulta; ainda sem resultado final.
- [ ] Integração: execução `42328923624`, job
  `128270668745` — em andamento na etapa "Install Android build components"
  na última consulta; ainda sem resultado final.
- [ ] Aguardar artefato aprovado da probe para o mesmo SHA; validar gate,
  SynthDefs, JUnit e APK na integração. Nenhuma dessas etapas foi concluída
  nesta consulta.
- [ ] Não liberar handoff, não alterar `native_gate_open=false`, preservar
  PolyPerc e fallback Java até aprovação física dos dois buffers no SM-G973F.

### Resultado CI e correção do teste de contrato (2026-11-07)

- [x] Probe ARM64 do SHA `1517277d58c6cef6a7c3c04c0ef14fd88741f93c`
  concluída com sucesso (workflow `42328923463`, job `128270668374`).
- [x] Artefato `scsynth-android-probe-69` publicado; a integração o baixou
  e importou com sucesso. Foram identificados `libscsynth.so` ELF64/AArch64,
  23 plugins UGen e os oito símbolos NornsSC verificados no log.
- [x] Testes C++ do ring Oboe e snapshots diagnósticos aprovados; validadores
  do gate de saída nativa e do roteamento Softcut aprovados.
- [x] Integração do mesmo SHA chegou ao teste `SoftcutRecorder write gate`
  (workflow `42328923624`, job `128270668745`).
- [x] Falha identificada no teste estático: o recorte `start_block` terminava
  no primeiro `if (!started)`, antes da atribuição conservadora
  `recordingActive[index] = true;`, embora a implementação Java contenha
  essa atribuição depois da confirmação de start.
- [x] Correção mínima no teste: verificar a atribuição no trecho entre o start
  e o tratamento de `if (!confirmed)`, sem alterar o comportamento Java.
  Commit `8ff9387167a25228f5325af605180e53410a8a3d`.
- [ ] Reexecutar probe ARM64 e integração para o novo SHA do commit de teste;
  SynthDef, JUnit e APK ainda não foram executados nesta rodada, pois a
  integração anterior parou no teste estático.
- [ ] Repetir gravação e readback reais dos buffers 1 e 2 no SM-G973F.
- [ ] Manter `native_gate_open=false`; preservar PolyPerc e fallback Java.
- [ ] Não iniciar handoff nem habilitar reprodução Softcut nativa antes da
  aprovação física dos dois buffers.

### CI aprovado após correção do teste SoftcutRecorder (2026-11-07)

- [x] Probe ARM64 aprovada no SHA
  `8ff9387167a25228f5325af605180e53410a8a3d`: workflow
  `42329333245`, job `128271985384`; compilação de scsynth e pacote
  de UGens concluída, artefato `scsynth-android-probe-70` publicado
  (artifact ID `13556816863`).
- [x] Integração aprovada no mesmo SHA: workflow `42329333427`,
  job `128271986179`; download da probe e importação das bibliotecas ARM64
  concluídos.
- [x] Teste lock-free do Oboe input ring aprovado.
- [x] Validação do gate de segurança de saída nativa aprovada.
- [x] Validação de roteamento exclusivo de saída Softcut aprovada.
- [x] Teste de contrato `SoftcutRecorder write gate` aprovado após a
  correção do recorte de código no commit
  `8ff9387167a25228f5325af605180e53410a8a3d`.
- [x] Compilação do SynthDef Softcut e validação do bundle de SynthDefs
  concluídas com sucesso.
- [x] `testDebugUnitTest` e `assembleDebug` concluídos; Gradle reportou
  `BUILD SUCCESSFUL`.
- [x] Verificação do APK confirmou
  `lib/arm64-v8a/libscsynth.so`,
  `lib/arm64-v8a/libnorns_sc_bridge.so` e
  `lib/arm64-v8a/libc++_shared.so`.
- [x] APK debug 0.4.5 publicado como artefato
  `NornsMobile-0.4.5-debug-249` (artifact ID `13556487809`).
  [Download do APK](https://github.com/jo1nGF/nornsmobile/actions/runs/42329333427/artifacts/13556487809).
- [x] Payload nativo importado publicado como
  `scsynth-android-imported-249` (artifact ID `13556597549`).
- [ ] Instalar e validar o APK no SM-G973F; executar gravação simultânea
  dos buffers 1 e 2, stop confirmado, readback com peak/RMS não nulos,
  continuidade e recuperação pós-stop.
- [ ] Manter `native_gate_open=false`; preservar PolyPerc e fallback Java.
- [ ] Não iniciar handoff nem habilitar reprodução Softcut nativa antes
  da aprovação física dos dois buffers no aparelho.


### Preparação da validação física BufWr no Samsung SM-G973F — APK 0.4.5 (2026-11-07)

- [x] Revisar o roteiro `docs/BUFWR_DEVICE_VALIDATION.md`: CH1 usa voz/buffer 1,
  rate +1 e offset +4 ms; CH2 usa voz/buffer 2, rate -1 e offset -4 ms.
- [x] Confirmar o cenário de gravação simultânea por 4 s na janela 30,0–31,5 s,
  seguido de stop dos dois canais, confirmação por `/sync` e readback serializado.
- [x] Confirmar os instrumentos de diagnóstico documentados: linhas
  `[BufWr validation]`, frescor/estado de gravação, contagem de amostras,
  posições Java e nativas, erro/máximo/RMS/limiar, estado de recuperação e
  peak/RMS dos callbacks de readback.
- [x] Confirmar que o código Java mantém start/stop/sync/readback no worker OSC
  serializado, mapeia buffers nativos 20001/20002 e limita readback a blocos
  de até 750 frames.
- [x] Confirmar que o roteiro Lua `BufWrDeviceValidation.lua` é citado pela
  documentação como fornecido separadamente; ele não aparece versionado na
  árvore desta branch. A execução depende de disponibilizar esse arquivo
  no ambiente Dust/Norns do aparelho.
- [x] Identificar o APK de CI mais recente aprovado: artefato
  `NornsMobile-0.4.5-debug-249`, workflow
  https://github.com/jo1nGF/nornsmobile/actions/runs/42329333427/artifacts/13556487809.
- [ ] No SM-G973F, instalar o APK 0.4.5 e registrar versão instalada, modelo,
  taxa de amostragem e inicialização do backend; não considerar a instalação
  como aprovação funcional.
- [ ] Antes de gravar, fechar outros scripts Softcut e fazer backup dos
  conteúdos que ocupem aproximadamente 29,99–31,5 s dos buffers 1 e 2.
- [ ] Executar o roteiro com entrada de microfone contínua e identificável;
  confirmar CH1 e CH2 simultâneos, `fresh=true`, `recording=true`,
  contagens crescentes, cursores em sentidos opostos e erro dentro do
  `threshold_seconds` após transientes iniciais, inclusive no wrap.
- [ ] Parar os dois canais juntos e guardar o log completo. Exigir stop
  confirmado por `/sync`, ausência de estado ativo/pending e readback
  completo de ambos os canais.
- [ ] Exigir peak e RMS não nulos nos dois readbacks e coerentes com o sinal
  de entrada; verificar que CH1 foi transferido ao buffer Java 1 e CH2 ao
  buffer Java 2. Se qualquer canal retornar zero, muito baixo, incompleto
  ou timeout, registrar falha e não repetir sobre conteúdo sem novo backup.
- [ ] Confirmar recuperação/reancoragem somente após stop confirmado; nenhuma
  reancoragem durante gravação. Verificar PolyPerc/fallback Java audível e
  ausência de reprodução Softcut duplicada.
- [ ] Anexar logs e métricas reais desta sessão antes de alterar qualquer
  estado do gate. Até aprovação explícita dos dois buffers:
  `native_gate_open=false`; PolyPerc e fallback Java preservados; handoff
  Oboe/AudioTrack e reprodução Softcut nativa bloqueados.

**Estado desta preparação:** roteiro e código de diagnóstico revisados; nenhum
teste físico foi executado nesta atualização. Próxima ação é instalar o APK
249 no SM-G973F, disponibilizar/executar o roteiro Lua e devolver o console
completo `[BufWr validation]` e métricas peak/RMS dos dois readbacks.
### Prova runtime de isolamento BufWr CH1/CH2 — diagnóstico e correção incremental (2026-11-07)

- [x] APK anterior executou a prova: `queued=true`; o resultado final foi
  `independent=false`, com fingerprints CH1/CH2 idênticos
  (`51268918787ba09f`). `liveBuffersPreserved=true`.
- [x] Classificar o resultado como **FAIL**, não como evidência de isolamento.
  O teste não autorizou abertura do gate nem reprodução nativa.
- [x] Corrigir a prova para ordenar explicitamente as fontes determinísticas
  antes dos gravadores no grupo padrão: fontes em `addToHead`, gravadores em `addToTail`.
- [x] Inserir barreira `/sync` após criação dos quatro nós e aumentar a janela
  de estabilização para 100 ms.
- [x] Acrescentar peak/RMS por canal ao log, para distinguir buffers vazios,
  sinal comum e sinais independentes na próxima execução.
- [ ] Executar CI (probe ARM64, SynthDef, JUnit e APK) no commit
  `7722574c740a8a19099a064f7dc69fc60555ffa6`; ainda não há confirmação de
  workflows concluídos para este SHA.
- [ ] Gerar novo APK e repetir somente a prova `softcut.native_buffer_isolation_probe()`.
  Exigir `queued=true`, `result=PASS`, fingerprints distintos e níveis coerentes
  com +0,25 (CH1) e -0,375 (CH2).
- [ ] Se falhar novamente, usar peak/RMS para localizar a etapa defeituosa
  (fonte, entrada do recorder, BufWr ou readback) antes de alterar a arquitetura.
- [ ] Preservar `main`, PolyPerc e fallback Java; manter
  `native_gate_open=false`. Não habilitar as seis vozes, não iniciar handoff
  de reprodução e não solicitar teste geral no aparelho até completar as
  provas de isolamento, roteamento estéreo e exclusividade de saída.

**Estado:** prova runtime reprovada no APK anterior; correção de ordenação e
telemetria commitada na branch de feature; nova validação de CI e aparelho pendente.

### Correção crítica — saída master SuperCollider silenciada pelo gate Softcut (2026-11-07)

- [x] Diagnosticar no callback Oboe que o gate `mNativeSoftcutOutputEnabled`
  estava zerando o PCM inteiro, inclusive o master dos instrumentos.
- [x] Separar as rotas: buses master 0/1 são copiados para Oboe mesmo com
  o gate Softcut fechado; buses Softcut 9/10 só são somados após a abertura
  explícita do gate.
- [x] Remover o mute global do callback e manter o gate aplicado apenas ao
  caminho Softcut. A propriedade de saída Java/Oboe continua controlando
  exclusivamente o Softcut.
- [x] Atualizar o validador estático para exigir master audível com gate
  Softcut fechado e impedir regressão do mute global.
- [ ] Executar CI (probe ARM64, validação de roteamento, SynthDef, JUnit e APK)
  no SHA desta correção.
- [ ] No SM-G973F, confirmar áudio audível de PolyPerc/TestSine e observar
  pico PCM master L/R com gate Softcut fechado.
- [ ] Revalidar que Softcut 9/10 permanece silencioso enquanto o gate estiver
  fechado; não habilitar seis vozes nem handoff até aprovação separada.
- [ ] Corrigir o erro Lua de `circles.lua:240` e investigar a abertura MIDI
  após recuperar o áudio.

### Recuperação da saída audível e correção do validador — 2026-11-07

- [x] Usuário confirma que o botão de teste de áudio na aba Norns produz som audível no aparelho.
- [x] Registrar esse resultado como confirmação de que o caminho de teste da interface está audível; ele não substitui a validação específica de PolyPerc pelo scsynth/Oboe nem prova roteamento Softcut.
- [x] Confirmar no callback Oboe que os buses master 0/1 são encaminhados independentemente do gate Softcut, enquanto os buses 9/10 só são somados quando o gate nativo está aberto.
- [x] Identificar a falha CI 42414163173: a asserção do validador dependia de uma frase contínua, mas o comentário C++ estava quebrado em duas linhas.
- [x] Tornar a verificação tolerante a espaços e quebras de linha, mantendo a exigência semântica; as asserções de masterTouched, cópia PCM e mistura condicional dos buses Softcut continuam ativas.
- [ ] Reexecutar a validação de roteamento, probe ARM64, SynthDef, JUnit e build APK no novo SHA.
- [ ] Confirmar PolyPerc pelo motor scsynth/Oboe com gate Softcut fechado e verificar que não há áudio Softcut nos buses 9/10.
- [ ] Manter isolamento BufWr CH1/CH2, handoff e reprodução Softcut bloqueados até provas independentes aprovadas; preservar main, PolyPerc e fallback Java.

### Correção final do validador de roteamento — 2026-11-07

- [x] Analisar o log da integração 42414993514: a falha persistiu na asserção textual do comentário, não no código C++.
- [x] Confirmar que o comentário atual do driver foi reescrito e não contém a frase antiga; normalizar whitespace não poderia corrigir uma frase removida.
- [x] Remover a dependência do comentário e manter uma asserção comportamental sobre a soma condicional: `if (nativeEnabled && softcutTouched) sample += softcut[frame]`.
- [x] Preservar as asserções de cópia dos buses master 0/1, gate inicial fechado, buses Softcut dedicados 9/10 e PCM estéreo.
- [ ] Reexecutar probe, validador, SynthDef, JUnit, APK e verificação dos artefatos no novo SHA.
- [ ] Investigar `circles.lua:240` com o arquivo/versão efetivamente importado e corrigir apenas após confirmar a causa do nil.
- [ ] Investigar falha de abertura MIDI com logs do Android MIDI Manager; não assumir que a enumeração equivale à conexão.
- [ ] Preservar main, PolyPerc e fallback Java; manter Softcut e as seis vozes bloqueados até prova runtime de isolamento BufWr CH1/CH2 e roteamento estéreo/exclusividade de saída.


### Recuperação CI 0.4.5 e abertura assíncrona MIDI — 2026-11-07

- [x] Probe ARM64 concluído com sucesso no commit `abd6886e94927c944497136d8fca4b206c59e230`:
  workflow [42415492561](https://github.com/jo1nGF/nornsmobile/actions/runs/42415492561),
  artefato `scsynth-android-probe-174`.
- [x] Integração scsynth/Oboe concluída com sucesso no mesmo SHA:
  workflow [42415492566](https://github.com/jo1nGF/nornsmobile/actions/runs/42415492566).
- [x] Validações do callback Oboe, gate de saída nativa, roteamento exclusivo,
  gate de escrita do SoftcutRecorder e compilação/estrutura dos SynthDefs aprovadas.
- [x] `testDebugUnitTest` e `assembleDebug` concluídos; Gradle registrou
  `BUILD SUCCESSFUL`. O relatório de execução não foi publicado separadamente,
  portanto não registrar uma contagem de testes sem o XML correspondente.
- [x] APK 0.4.5 debug gerado, com `libscsynth.so` ARM64 verificada no pacote.
  Artefato `NornsMobile-0.4.5-debug-359` (3.554.142 bytes),
  SHA-256 do ZIP do artefato:
  `946cac7727a5877cbd42c155994d5b2b6a3d5463049c2d65738e857361bac9c4`.
- [x] Diagnosticar a corrida MIDI: Lua recebia o estado conectado antes do callback
  assíncrono do Android, e mensagens enviadas nesse intervalo eram descartadas.
- [x] Adicionar fila FIFO temporária limitada a 256 mensagens por porta,
  copiando os bytes e preservando a ordem até a abertura ser concluída.
- [x] Proteger callbacks atrasados com token de abertura; `close`/`closeAll`
  invalidam solicitações e limpam filas, impedindo reabertura acidental após cleanup.
- [x] Descartar e registrar mensagens pendentes quando a abertura falha ou o
  dispositivo não oferece porta de entrada para envio; overflow descarta a
  mensagem mais nova e registra a quantidade.
- [x] Adicionar testes unitários da fila: FIFO, cópia defensiva, limite/overflow,
  limpeza e capacidade inválida.
- [ ] Executar CI da nova correção MIDI e confirmar os testes adicionados.
- [ ] Validar envio MIDI real ao B.A.C.H. e outros destinos no aparelho; a
  enumeração e o teste unitário não substituem a confirmação de hardware.
- [ ] Executar prova runtime BufWr CH1/CH2 com fingerprints e métricas distintos.
- [ ] Demonstrar roteamento estéreo e exclusividade de saída Oboe/AudioTrack
  sem duplicação; até lá manter handoff e Softcut nativo desabilitados.
- [ ] Preservar branch `main`, PolyPerc e fallback Java.

**Estado de segurança:** nenhum gate Softcut foi aberto; as seis vozes continuam
desabilitadas. O sucesso de compilação/SynthDef não comprova isolamento runtime
dos buffers nem roteamento estéreo em aparelho.


### Revisão da fila MIDI assíncrona: segurança de Note Off — 2026-11-07

A auditoria do código já existente encontrou a fila FIFO de 256 mensagens, mas também identificou uma lacuna: overflow descartava sempre a mensagem mais nova, inclusive Note Off. A correção posterior à implementação inicial:
- [x] Mantém FIFO, cópia defensiva e limite de 256 mensagens por dispositivo.
- [x] Em overflow de mensagem comum, descarta a nova mensagem e contabiliza a perda.
- [x] Em overflow de Note Off (0x8n ou Note On com velocity zero), limpa o burst incompleto e agenda Sustain Off + All Notes Off para os 16 canais, preservando o Note Off recebido ao final.
- [x] Testes JUnit cobrem FIFO, cópia, overflow comum, Note Off de emergência, Note On velocity zero e clear/capacidade.
- [x] Exceção síncrona de `openDevice` agora encerra a tentativa via `failOpen`; exceções de runtime e IO no envio são registradas.
- [ ] Executar workflows e confirmar JUnit/APK no HEAD após as três alterações MIDI e a atualização do roadmap. Ainda não há evidência de CI verde nem APK produzido para esta revisão.
- [ ] Testar o callback assíncrono real, falha de abertura, close durante abertura, reconexão e envio efetivo em portas USB/BLE.

A sequência de emergência é deliberadamente conservadora: pode interromper notas sustentadas em outros canais quando a fila satura, mas evita deixar notas presas por perda de Note Off. O comportamento de overflow não representa validação de MIDI físico.

Restrições de áudio seguem inalteradas: `native_gate_open=false`; PolyPerc e fallback Java preservados; buffers 20001/20002 sem prova de escrita; sem seis vozes, playback Softcut nativo ou handoff Oboe/AudioTrack.


### Estado de conexão MIDI exposto ao Lua — 2026-11-07

- [x] Corrigir a semântica de `midi.connect()`: o objeto não declara mais `connected=true` antes do callback de abertura do Android.
- [x] Expor `connecting=true` enquanto a abertura está pendente e continuar aceitando envios nesse estado, alimentando a fila FIFO.
- [x] `MidiBridge.open()` retorna sucesso/falha da solicitação; abertura inválida ou exceção síncrona não é reportada como conexão aceita.
- [x] Callback de sucesso/falha retorna ao LuaHost e atualiza `connecting`, `connected` e `connection_error` na thread Lua serializada.
- [x] Uma chamada repetida para dispositivo já aberto recebe confirmação de estado sem iniciar uma segunda abertura.
- [ ] Testar transições e callback real com fake/instrumentação Android, incluindo close durante abertura e reconexão.
- [ ] Executar JUnit, SynthDef, probe ARM64, integração e APK para o HEAD atualizado; não há evidência de CI concluído nesta revisão.

A lista de dispositivos ainda usa IDs de sessão derivados da ordem de enumeração; hotplug e estabilidade do mapeamento após refresh continuam pendentes. O callback não comprova que um destino recebeu fisicamente as mensagens.


### CI do callback MIDI e regressão circles — 2026-11-07

- [x] Probe ARM64 do SHA `a00731a5833619e098cbd3114d7f3592e8103389` aprovada (workflow `42418273817`).
- [x] Integração passou importação ARM64, testes C++ do ring Oboe, validadores do gate/roteamento/BufWr e compilação dos SynthDefs no workflow `42418273791`.
- [x] Integração revelou erro Java em `LuaHost.dispatchMidiConnection`: chamadas `LuaValue.set(String, boolean)` não são suportadas pelo LuaJ.
- [x] Corrigir campos booleanos usando `LuaValue.FALSE` e `LuaValue.valueOf(connected)` no commit `c0b52ac57595a6434277586989096171d37a00f9`.
- [x] Novo SHA `8002b56246c9a5c36381e7f206986293ae0ffdea` contém também teste LuaJ que reproduz o contrato de `params:default()` sem ID usado por circles para inicializar `scale_notes`.
- [ ] Probe `42418639385` e integração `42418639403` estão em execução para o SHA `8002b562`; aguardar conclusão. JUnit, APK e artefatos não confirmados ainda.
- [ ] Continuar testes MIDI de lifecycle (callback, falha, close durante open, reconexão e flush). Abertura real e recebimento físico USB/BLE seguem pendentes.

Softcut permanece sem autorização de playback: `native_gate_open=false`, buffers nativos 20001/20002 sem prova runtime de isolamento/readback, seis vozes desabilitadas e handoff Oboe/AudioTrack bloqueado. PolyPerc e fallback Java preservados; nenhuma mudança de áudio foi feita nesta rodada.


### CI integral aprovado — probe 185 / APK 370 — 2026-11-07

- [x] Probe ARM64 3.14.1 aprovada no SHA `8002b56246c9a5c36381e7f206986293ae0ffdea`: workflow `42418639385`, artifact `scsynth-android-probe-185` (ID `13592891456`).
- [x] Integração aprovada no mesmo SHA: workflow `42418639403`.
- [x] Importar ELF64/AArch64 `libscsynth.so`, 23 plugins UGen e validar símbolos NornsSC.
- [x] Passar testes do Oboe input ring, gate de saída, roteamento exclusivo e gate de escrita BufWr.
- [x] Compilar e validar SynthDefs `SoftcutVoice` e `SoftcutRecorder`.
- [x] `testDebugUnitTest` e `assembleDebug` aprovados; Gradle `BUILD SUCCESSFUL in 33s`. O relatório XML/contagem JUnit não foi publicado.
- [x] APK debug 0.4.5 `NornsMobile-0.4.5-debug-370`, artifact ID `13592851962`, 3.557.800 bytes; [baixar pelo GitHub Actions](https://github.com/jo1nGF/nornsmobile/actions/runs/42418639403/artifacts/13592851962).
- [x] Payload importado `scsynth-android-imported-370`, artifact ID `13592792165`, 5.280.714 bytes; [artefato](https://github.com/jo1nGF/nornsmobile/actions/runs/42418639403/artifacts/13592792165).
- [x] Correção LuaJ do callback MIDI passou na compilação Java; teste LuaJ para `ParamSet:default()` sem ID e inicialização de `scale_notes` incluído na suíte aprovada.
- [ ] Testar lifecycle MIDI com Android real/fakes de callback: open pendente, flush FIFO, open falho/nulo, close antes do callback, reconexão e hotplug. A CI atual não comprova transporte físico.

**Gate de segurança sem alteração:** `native_gate_open=false`; buses master 0/1 continuam separados dos buses Softcut 9/10; buffers nativos 20001/20002 ainda aguardam prova runtime de escrita/readback e fingerprints independentes. Seis vozes Softcut e handoff Oboe/AudioTrack permanecem bloqueados. PolyPerc e fallback Java preservados.


## MIDI hotplug e IDs estáveis — nova rodada — 2026-11-07

- [x] Identificado que `MidiBridge.refresh()` reconstruía a lista e atribuía IDs pela posição da enumeração Android; uma mudança de ordem podia fazer um script enviar para outro dispositivo após refresh.
- [x] Introduzido `MidiDeviceIdRegistry`: IDs positivos de sessão são associados ao Android device ID, preservados quando a ordem muda e aposentados quando o dispositivo desaparece. ID 0 continua reservado à saída virtual Norns Mobile.
- [x] `MidiBridge.refresh()` agora reconstrói mapa por ID de sessão, ordena a lista sem renumerar dispositivos remanescentes, encerra conexões e filas pendentes de dispositivos removidos e reporta desconexão ao LuaHost.
- [x] Adicionados testes JUnit para ordem de enumeração alterada, remoção seletiva e refresh vazio.
- [ ] CI da nova revisão ainda precisa confirmar compilação, JUnit e APK.
- [ ] Ainda pendente: testes determinísticos do callback Android assíncrono (open success/null/failure, close antes do callback, reabertura com token antigo, flush FIFO) e teste de hotplug real. A enumeração agora é estável por sessão, mas o ID do Android pode ser reutilizado depois que o dispositivo desconecta; o registry aposenta o ID anterior.

O teste físico geral continua reservado para depois de concluir as implementações pendentes. PolyPerc/fallback Java e todas as travas de Softcut permanecem sem alteração.


## Lifecycle MIDI extraído e testado — 2026-11-07

- [x] Extrair a máquina de estados de abertura assíncrona para `MidiOpenRequests`, usada diretamente por `MidiBridge`.
- [x] Token por tentativa: callback atrasado não pode completar nem drenar a fila de uma tentativa posterior.
- [x] `begin` impede abertura duplicada; `complete` drena FIFO uma única vez; `fail` descarta pendências; `cancel` e `cancelAll` invalidam callbacks após close/stop.
- [x] Adicionar testes JUnit da integração de estado: flush FIFO após sucesso, open duplicado, close antes do callback, callback antigo após reconexão, falha de open e closeAll.
- [x] Estabilizar IDs de dispositivo entre refresh/hotplug com `MidiDeviceIdRegistry`; IDs removidos são aposentados e suas conexões/filas são encerradas.
- [ ] Workflows disparados para o SHA de código `8a3cef290c9f2564bdeb470d33770d3f18b605ac`: probe `42419490599` e integração `42419490605`; aguardar conclusão JUnit/SynthDef/APK.
- [ ] Teste de callback Android real e de transporte USB/BLE continua pendente; testes determinísticos cobrem a máquina de estados usada pelo bridge, não a implementação interna do Android.

O teste geral no SM-G973F permanece adiado até terminar a implementação de compatibilidade Norns, MIDI e Softcut seguro. PolyPerc, fallback Java e gates de áudio preservados.


## Compatibilidade screen PNG — 2026-11-07

- [x] Implementar `screen.export_screenshot(filename)`: grava PNG no diretório `dust/data/<script>/` (ou `dust/data/` sem script), normaliza o nome e acrescenta extensão PNG quando necessário.
- [x] Exportar framebuffer 4x (512×256) com borda preta de 64 px, compatível com a convenção descrita na documentação Norns.
- [x] Implementar `screen.display_png(filename, x, y)`: resolve caminhos somente dentro da pasta privada `dust`, permite fallback para `dust/data/<script>/`, desenha na posição lógica solicitada.
- [x] Limitar tamanho do arquivo de entrada a 16 MiB e dimensões decodificadas a 4096×4096 para reduzir risco de consumo excessivo de memória.
- [x] Adicionar testes de contrato para índices de operação, caminho de exportação, limites do PNG e rejeição de traversal para fora da pasta Dust.
- [ ] CI JUnit/SynthDef/APK desta revisão: probe `42419653783` e integração `42419646095` estão pendentes/em fila; aguardar resultado antes de considerar a API validada.
- [ ] Não implementados ainda: `screen.load_png`, `create_image`, `display_image`, `draw_to`, `peek(x,y,w,h)`, `poke(x,y,w,h,s)`, e variantes de texto/rotação ausentes no inventário atual.

A API de PNG foi adicionada sem tocar no pipeline de áudio. O teste físico geral permanece adiado até concluir as APIs pendentes.


## Cobertura adicional de bibliotecas Lua e CI — 2026-11-07

- [x] Auditoria do erro histórico `oooooo`/formatters: `app/src/main/assets/lua/lib/formatters.lua` está presente e usa `controlspec` e `util` do pacote compartilhado; incluído teste LuaJ que carrega o módulo e verifica `format_freq_raw(440) == "440 Hz"`.
- [x] Corrigido helper do teste de contrato de screen para funcionar tanto com Gradle executando na raiz quanto no módulo `app`.
- [x] A máquina `MidiOpenRequests` e o registry de IDs são exercitados por testes determinísticos de FIFO, falha, cancelamento, stale callback, reabertura e hotplug.
- [ ] CI mais recente para SHA `5e6c2e8de70f00394e3a8d1fbba39f05ef131a76`: probe `42419719300` e integração `42419719378`; aguardando execução completa.
- [ ] `32Harmony` continua sem implementação nativa no app: não há fonte Engine32Harmony na árvore atual, e classes SuperCollider `.sc` de terceiros exigem `sclang`/SCClassLibrary, que não são fornecidos por `libscsynth` isoladamente. Não criar um stub que finja suportar o engine.


## Correção de require("lib/…") e cobertura de formatters — 2026-11-07

- [x] A causa provável do erro histórico de `oooooo` foi localizada no carregador: `find_module()` tentava `lua/lib/lib/formatters.lua` para `require("lib/formatters")`, embora o asset esteja em `lua/lib/formatters.lua`.
- [x] Corrigir o carregador para aceitar ambas as convenções Norns: `require("formatters")` e `require("lib/formatters")`, mantendo a resolução de módulos locais do script.
- [x] Acrescentar asserções de contrato para a resolução do prefixo `lib/` e teste LuaJ de carregamento/execução de `format_freq_raw(440)`.
- [ ] Validar no CI do SHA `8a71b11ca3762767ceca205432905ff4c8db993a`; probe e integração aguardam execução.
- [ ] O pacote `oooooo` não está incluído na árvore atual; o contrato do carregador e módulo está testado, mas o script completo ainda não foi reexecutado.

 
### Roteamento PCM estéreo — prova determinística reforçada (2026-11-07)

- [x] Extrair o cálculo por canal para `SoftcutOutputMixer`, função pura,
  sem alocação e usada diretamente pelo callback Oboe.
- [x] Teste C++ para master L/R sempre preservado com gate Softcut fechado,
  mistura condicional dos buses 9/10, ausência de crossfeed, buses não escritos,
  saturação e rejeição de amostras não finitas.
- [x] Injetar o header do mixer no build ARM64 pelo patcher.
- [x] Fazer CI compilar/executar o teste do mixer com `-Werror`.
- [x] Verificar os sete casos atuais de `MidiMessageQueueTest` via JUnit XML
  e publicar relatório/resumo junto ao APK.
- [ ] Prova runtime no Android: capturar PCM Oboe L/R sob estímulos isolados,
  comparar picos/snapshots com buses master 0/1 e Softcut 9/10.
- [ ] Confirmar drenagem/mute Java e exclusividade do callback Oboe sem
  sobreposição de áudio, mantendo o gate Softcut fechado durante a validação.
- [ ] Não abrir o gate Softcut nem habilitar seis vozes antes da aprovação
  conjunta de BufWr CH1/CH2, roteamento estéreo e ausência de duplicação.


## Screen API — buffers de imagem, regiões e blend modes — 2026-11-08

- [x] Implementar `screen.load_png(filename)` com resolução segura dentro de `dust` e fallback para `dust/data/<script>/`; PNG limitado a 16 MiB, dimensões até 4096×4096 e 16.777.216 pixels.
- [x] Implementar `screen.create_image(width,height)` com buffer lógico em pixels, dimensões positivas e limite de 16.777.216 pixels.
- [x] Implementar `screen.display_image(image,x,y)` e `screen.display_image_region(image,left,top,width,height,x,y)`, validando userdata, bitmap, coordenadas e retângulo de origem.
- [x] Implementar `screen.draw_to(image,func)`: redireciona temporariamente o Canvas para o buffer de imagem, restaura canvas/cursor/path mesmo quando o callback gera erro e limita profundidade de aninhamento.
- [x] Implementar `screen.peek(x,y,w,h)` e `screen.poke(x,y,w,h,s)` com strings de bytes de níveis 0–15 e limites máximos da tela 128×64.
- [x] Completar `screen.blend_mode` com valores numéricos Norns e aliases textuais; XOR e Overlay adicionados, modos Android não suportados retornam false.
- [x] Adicionar testes de contrato para operações Lua, limites de alocação/decodificação, regiões de pixels e mapeamento de blend modes.
- [ ] CI do SHA `3e95a4587153e0ab524cd9abe0cb60a2adceef18`: probe `42437408244` e integração `42437408216` estão pendentes; aguardar JUnit, SynthDef, APK e artefatos antes de marcar validado.
- [ ] Teste funcional de imagem/bitmap em runtime e comparação visual ainda não executados; teste geral físico permanece adiado.

O trabalho é restrito à camada de tela/Lua; não altera PolyPerc, fallback Java, ownership de saída nem gate Softcut.


## MIDI: parser de stream e hotplug para scripts — 2026-11-08

- [x] Substituir parser por pacote de mensagens isoladas por um parser stateful por conexão: suporta mensagens fragmentadas entre callbacks Android, running status, Program Change/Channel Pressure, mensagens system-common e realtime intercalado.
- [x] Tratar SysEx fragmentado sem deixar bytes do payload contaminarem mensagens seguintes; realtime continua sendo entregue mesmo durante SysEx.
- [x] Resetar estado do parser quando a conexão fecha ou o dispositivo é removido.
- [x] Registrar `MidiManager.DeviceCallback` no `MidiBridge`; atualizar lista após add/remove/status e notificar scripts por `midi.add(dev)` / `midi.remove(dev)`.
- [x] Encerrar callback Android e conexões no `LuaHost.dispose()` ao destruir a Activity.
- [x] Adicionar testes para running status, pacotes fragmentados, interleaving realtime, SysEx, system-common, reset e ranges inválidos; ampliar teste de IDs estáveis e contrato hotplug.
- [ ] CI desta revisão: probe `42437592953` e integração `42437592938` sobre SHA de código `6072ada49b45b6e20031c5af8f9326959fdc7867`; aguardando confirmação de compilação/JUnit/APK.
- [ ] MIDI clock transmit/receive e mensagens SysEx ainda precisam de cobertura específica; a camada atual de callback entrega mensagens de tamanho fixo ao LuaHost, então SysEx é descartado com segurança, não encaminhado como bloco.
- [ ] Testes de transporte USB/BLE e hotplug físico continuam reservados para o teste geral final.



## OSC: assinatura Norns, callback e transporte UDP — 2026-11-08

- [x] Corrigir `osc.send(to,path,args)` para aceitar o destino Norns `{host,port}` e manter a assinatura mobile antiga como compatibilidade.
- [x] Iniciar listener OSC padrão em UDP 10111 durante o boot; `osc.listen(port)` continua disponível para portas alternativas.
- [x] Corrigir `osc.event(path,args,from)`: `from` agora é tabela `{host,port}`, como a API Norns.
- [x] Normalizar endereço sem barra inicial para endereço OSC canônico com `/`.
- [x] Preservar argumentos int32, float32, string, boolean true/false e nil nos tipos OSC suportados.
- [x] Endurecer parser de strings/padding, rejeitar pacotes truncados e validar portas; isolar cada geração de socket para evitar thread antiga lendo socket novo.
- [x] Adicionar testes de codec round-trip, tipos, caminhos, pacote malformado e contrato Lua Norns.
- [ ] CI da revisão OSC ainda precisa concluir JUnit, SynthDef, APK e artefatos; conferir o último SHA disparado antes de considerar validado.
- [ ] OSC bundles, blob, int64/float64 e tipos de extensão ainda não implementados; transporte atual cobre mensagens individuais com tipos básicos usados pelos scripts comuns.
- [ ] Validar rede real e callbacks bidirecionais no teste geral final; nenhuma validação física de OSC foi presumida.



## MIDI: métodos Norns de envio e transporte — 2026-11-08

- [x] Adicionar `port:send(data)`, Note On/Off, CC, Program Change, Pitch Bend, Key Pressure, Channel Pressure, Start, Continue, Stop, Clock, Song Position, Song Select e All Notes Off para portas físicas e virtual.
- [x] Adicionar `port:clock_receive(enabled)`, filtrando Clock/Start/Continue/Stop recebidos quando desabilitado.
- [x] Implementar `midi.send(data)` e `midi.cleanup()`; o cleanup fecha portas Lua e cancela conexões pendentes.
- [x] Corrigir `midi.to_data` para Program Change, Song Position e Song Select.
- [x] Corrigir serialização Java para tamanho correto de mensagens channel/system-common/realtime: Program/Channel Pressure 2 bytes, Song Position 3, clock/transport 1.
- [x] Adicionar testes JUnit de byte length e contrato das funções MIDI Norns.
- [ ] CI da revisão MIDI ainda precisa concluir; aguardar run mais recente do HEAD de código para confirmar JUnit, SynthDef e APK.
- [ ] Encaminhamento de SysEx completo e relógio MIDI de alta frequência com medição de jitter permanecem pendentes; SysEx ainda é descartado pelo parser de entrada.



## MIDI: SysEx e envio de bytes arbitrários — 2026-11-08

- [x] Parser de entrada agora coleta SysEx fragmentado entre callbacks Android, preserva bytes F0…F7 e continua entregando mensagens realtime intercaladas.
- [x] Limitar SysEx recebido a 65.536 bytes; ao exceder, descartar o bloco até F7 e recuperar o parser sem contaminar mensagens subsequentes.
- [x] Encaminhar SysEx completo para `port.event(data)` ou `midi.event(data)` como tabela Lua de bytes 0–255.
- [x] Implementar transporte bruto `midi_send_bytes` em LuaHost/MidiBridge e usar em `port:send(data)`, permitindo SysEx e mensagens MIDI arbitrárias também para a saída virtual Android.
- [x] Corrigir `midi.to_data` para reconhecer Song Position e Song Select antes da conversão dos bytes.
- [x] Adicionar testes para SysEx fragmentado, realtime intercalado, limite de tamanho e recuperação após descarte.
- [ ] CI do HEAD de código mais recente ainda precisa concluir JUnit, SynthDef, APK e artefatos.
- [ ] Teste real USB/BLE, SysEx com dispositivos externos e jitter de clock continuam pendentes para a fase de teste geral.



## Sistema Norns: encoders, ciclo de script e engines desconhecidas — 2026-11-08

- [x] Implementar `encoders.set_sens`, `encoders.set_accel` e aliases `norns.enc.sens/accel`; escalonamento é aplicado aos deltas dos controles touch antes de `enc(n,d)`.
- [x] Implementar `norns.script.load(filename)`, `norns.script.clear()` e `norns.rerun()`, com caminhos relativos limitados a `_path.code` e cleanup antes de trocar script.
- [x] Não executar shutdown/reboot de sistema Android a partir de scripts; as chamadas retornam false e registram orientação segura no log.
- [x] Corrigir engine desconhecida: o núcleo Java agora seleciona ENGINE_NONE/silêncio em vez de substituir silenciosamente por PolyPerc.
- [x] Preservar PolyPerc e TestSine nativos, incluindo fallback Java quando o SuperCollider nativo não carrega; engines ausentes são reportadas como indisponíveis e não geram áudio enganoso.
- [x] Adicionar JUnit para engine desconhecida silenciosa e testes de contrato para encoder e ciclo de script.
- [ ] O engine 32Harmony ainda depende de classes SuperCollider customizadas e compilador sclang/SCClassLibrary, não disponível no runtime Android atual; implementação real permanece pendente, sem stub falso.
- [ ] CI do SHA de código mais recente deve confirmar compilação Java/Lua, SynthDef, JUnit e APK antes de promover esses itens a validados.



## Auditoria Softcut/audio: entradas e roteamento não disponíveis — 2026-11-08

- [x] Confirmado contra a API oficial Norns: setters de voz, filtros, buffers, gravação, render/query, fase, sync e matrix já têm wrappers no boot.lua.
- [x] `audio.level_adc_cut` continua conectado ao ganho global ADC e atualiza os dois recorders nativos silenciosos.
- [x] Expor `audio.level_eng_cut` e `audio.level_tape_cut` como operações explicitamente não suportadas, retornando false e registrando diagnóstico; o mixer atual não possui esses buses, portanto não simular roteamento.
- [x] Adicionar teste de contrato para ADC suportado e buses engine/tape indisponíveis.
- [ ] Roteamento engine/tape -> Softcut requer implementar buses/matrix dedicados no mixer Java e no scsynth, além de provar exclusividade e ausência de duplicação; continua bloqueado antes de qualquer playback.
- [ ] CI deve validar o conjunto acumulado; nenhuma voz Softcut foi liberada, gate permanece false.



## Inventário histórico de APIs externas Norns — 2026-11-08 (antes do backend Android de keyboard/gamepad)

- [x] Marcar Grid/Arc/HID vports com `available=false` e `connected=false`; operações de LED/segmento/refresh/update retornam false e emitem diagnóstico único, em vez de no-op silencioso.
- [x] Crow mantém um objeto null seguro, mas operações init/reset/clear/send agora informam indisponibilidade; `crow.connect()` não simula conexão física.
- [x] Naquela revisão, gamepad/serial/poll/keyboard eram null objects; o estado atual foi atualizado: keyboard físico e gamepad Android genérico já possuem backend, enquanto serial segue indisponível.
- [x] Adicionar testes de contrato para disponibilidade e ausência de falsa conexão.
- [x] Implementar USB Host CDC-ACM e API serial Norns base; Crow possui handler específico experimental.
- [ ] Implementar drivers monome Grid/Arc e validar Crow com periférico físico.
- [ ] Completar poll real para telemetria de áudio/CPU/entrada; keyboard e gamepad Android já têm integração de eventos, aguardando validação em hardware.
- [ ] Grid/Arc/Crow/HID/serial continuam pendentes; não deixar scripts acreditar que periféricos sem transporte foram conectados.



## Screen blend operators e inventário de API — 2026-11-08

- [x] Implementar os 14 operadores Cairo documentados em Norns: Over, XOR, Add, Multiply, Screen, Overlay, Darken, Lighten, Color Dodge, Color Burn, Hard Light, Soft Light, Difference e Exclusion.
- [x] Mapear índices 0–13 e aliases textuais com underscore; expor `screen.BLEND_MODES` para consulta por scripts.
- [x] Adicionar contrato de teste para os modos avançados e manter `blend_mode` retornando false quando o modo não é suportado.
- [ ] `screen.ping` e `update_low_battery` são funções específicas do gerenciamento de tela/bateria do hardware Norns e não possuem equivalente Android; avaliar retorno explícito sem alterar a tela principal.
- [ ] CI precisa confirmar o enum PorterDuff avançado no Android SDK/runtime suportado; teste geral do display continua adiado.



## Revisão adicional MIDI/Engine — 2026-11-08

- [x] Corrigir tamanho de bytes no callback de entrada MIDI para mensagens System Common F1/F3 (um byte de dados) e F2 (dois bytes de dados).
- [x] Implementar `midi.new(id,name,dev)` e funções estáticas de envio MIDI para o primeiro destino conectado, além dos métodos por porta.
- [x] Completar metadados da API Engine: `register(data,count)`, `register_commands(data,count)`, `add_command(id,name,fmt)`, callback de `load` com lista de comandos e recusa de segunda carga enquanto a primeira está pendente.
- [x] Corrigir testes de contrato de hotplug para refletir os callbacks default reais `midi.add/remove`.
- [ ] Engine metadata não torna classes SuperCollider customizadas executáveis; `32Harmony` ainda requer runtime sclang/SCClassLibrary. Somente PolyPerc/TestSine/None são carregáveis.
- [ ] CI deve validar esta revisão completa; ainda não promover testes de contrato a aprovados sem JUnit verde.



## Compatibilidade Engine/MIDI — revisão final desta etapa — 2026-11-08

- [x] Expor `engine.register`, `register_commands(data,count)`, `add_command(id,name,fmt)`, `list_commands` e callback de load com lista de comandos, mantendo whitelist de engines realmente carregáveis.
- [x] Implementar `midi.new` e funções estáticas de envio (note_on/off, CC, pitchbend, pressures, program change, transport, clock, song position/select).
- [x] Alinhar ordem de `midi.devices` e `midi.vports` ao Norns: porta virtual é a primeira, dispositivos Android externos vêm em seguida.
- [x] Cobrir contrato de API Engine/MIDI e corrigir evento System Common MIDI F1/F2/F3 com contagem correta de bytes.
- [ ] A API Engine ainda não carrega classes SC arbitrárias: apenas PolyPerc/TestSine/None são engines executáveis; metadados não equivalem a runtime sclang.
- [ ] Aguardar CI do SHA `be6e9ebb43cd15b02bd0d00efd6faa219b18b66a`: probe `42438757678` e integração `42438757654`.



## Correções após CI — 2026-11-08

- [x] CI do código anterior `be6e9eb`: probe ARM64 `42438757678` passou e gerou artefato; integração `42438757654` falhou em compilação Java porque `PorterDuff.Mode` não contém DODGE/BURN/HARD_LIGHT/SOFT_LIGHT/DIFFERENCE/EXCLUSION.
- [x] Corrigir os seis operadores com `android.graphics.BlendMode` (Android 10/API 29+) e manter fallback PorterDuff para os modos clássicos em APIs anteriores; os modos avançados retornam false em Android abaixo de API 29.
- [x] Atualizar testes de contrato para os novos operadores BlendMode.
- [x] Corrigir alias oficial `engine.load("None")` e codificação 14-bit de pitchbend em `midi.to_data`; corrigir quoting do teste Java de engine None.
- [ ] Nova validação no SHA `bdde70124db3ec2a1b533b3e946a2d7be5316aed`: probe `42439139069` e integração `42439139072` em execução; aguardar JUnit, SynthDef, APK e artefatos.
- [ ] Não considerar screen blend modes nem MIDI/Engine desta revisão aprovados até a integração atual ficar verde.



## Correção JUnit OSC — 2026-11-08

- [x] Revisão `bdde701`: probe ARM64 `42439139069` passou; compilação Java passou e SynthDefs foram validados, mas `testDebugUnitTest` falhou em 2 de 156 testes.
- [x] Falha 1: contrato esperava `osc.send = function`, enquanto API é método dentro da tabela `osc = { send = function ... }`; corrigir expectativa do teste.
- [x] Falha 2: expressão ternária Java entre `Integer` e `Float` promoveu a representação numérica; decodificador OSC agora usa ramificações explícitas para preservar int32 como Integer e float32 como Float.
- [ ] Nova execução no SHA `781f3695e9d8b9a79a3fb4780ced4c1bdf0b3567`: probe `42439501537` e integração `42439501473` pendentes; confirmar JUnit, APK e artefatos.



## CI verde e APK atualizado — 2026-11-08

- [x] Probe ARM64 `42439501537` concluída com success no SHA `781f3695e9d8b9a79a3fb4780ced4c1bdf0b3567`; artefato `scsynth-android-probe-278`, ID `13600967654`, 5.301.624 bytes.
- [x] Integração `42439501473` concluída com success no mesmo SHA; probe importada, Oboe ring, output safety gate, exclusividade Softcut, gate BufWr e bundle SynthDefs validados.
- [x] `clean testDebugUnitTest assembleDebug`: `BUILD SUCCESSFUL in 42s`; JUnit XML contém 156 testes, zero falhas; todos os 7 testes MidiMessageQueue passaram.
- [x] APK `NornsMobile-0.4.5-debug-463`, ID `13601431116`, 3.590.473 bytes; libs nativas empacotadas verificadas.
- [x] Payload `scsynth-android-imported-463`, ID `13601431122`, 5.280.645 bytes.
- [x] Duas falhas JUnit OSC da revisão anterior foram corrigidas; codec agora preserva Integer/Float e contrato Lua corresponde à tabela OSC.
- [x] `main` segue em `82fcedd60f76707ada4d25185837894a2e6664cd`; feature ahead 690, behind 0. HEAD documental atual `60945934b660943f101fc0043e9e3fccdbe18608`.
- [ ] Nenhum teste físico geral foi feito. A prova runtime BufWr CH1/CH2, roteamento estéreo real, exclusividade/handoff Oboe/AudioTrack e comportamento USB/BLE permanecem para conclusão de implementação e teste geral final.



## OSC bundles e detalhes de Screen — 2026-11-08

- [x] Adicionar decoder OSC para bundles aninhados, com limite de profundidade 8 e 256 mensagens por datagrama; mensagens do bundle são encaminhadas individualmente ao callback Norns.
- [x] Adicionar encoder de bundle usado por testes; timetag NTP é atualmente ignorado e mensagens são entregues imediatamente (agendamento de timetag segue pendente).
- [x] Adicionar `screen.ping()` como sucesso seguro enquanto a Activity mantém a tela ativa e `screen.update_low_battery()` como false explícito (overlay de bateria Norns não existe no Android).
- [x] `midi.to_data(song_position)` aceita tanto `position` quanto `val`.
- [ ] CI do código `c08f21297cbc1c80d09ec2fdcdd08d9a32e69c23`: probe `42440042661` e integração `42440042677` aguardando execução; validar JUnit/SynthDef/APK antes de marcar concluído.



## CI completo aprovado — revisão OSC bundles — 2026-11-08

- [x] Probe ARM64 `42440042661` success no SHA `c08f21297cbc1c80d09ec2fdcdd08d9a32e69c23`; artefato `scsynth-android-probe-286`, ID `13601701004`, 5.301.624 bytes.
- [x] Integração `42440042677` success no mesmo SHA; importação ARM64, Oboe ring, gates de output/Softcut/BufWr e SynthDefs concluídos.
- [x] `clean testDebugUnitTest assembleDebug`: `BUILD SUCCESSFUL in 42s`; 160 testes JUnit, zero falhas; todos os 7 testes MidiMessageQueue aprovados.
- [x] APK `NornsMobile-0.4.5-debug-471`, ID `13601755784`, 3.590.745 bytes; payload `scsynth-android-imported-471`, ID `13600929124`, 5.280.645 bytes.
- [x] Novos testes OSC nested bundle/malformed e screen ping passaram na suíte de 160 testes.
- [x] `main` permanece `82fcedd60f76707ada4d25185837894a2e6664cd`; feature ahead 702, behind 0. HEAD documental atual `a9738b3be350a8dbdc3dc2b46e7f630c49b7add3`.
- [ ] Bundles OSC são entregues imediatamente; agendamento pelo timetag ainda não está implementado.
- [ ] Poll real, backend USB-serial/Grid/Arc/Crow/HID/Gamepad/Keyboard e buses engine/tape seguem pendentes. Softcut permanece fechado até prova runtime independente/estéreo/handoff.



## Implementação do módulo poll — 2026-11-08

- [x] Substituir o null object `poll` por API Norns: `list_names`, `set`, `start`, `stop`, `update`, `clear_all`, callback e intervalo configurável.
- [x] Conectar polls ao loop AudioTrack/Java: amplitude de entrada mono, RMS de saída L/R, carga média/pico do bloco de render e estimativa de pitch de entrada por autocorrelação.
- [x] Como a captura Android atual é mono, `amp_in_l/r` e `pitch_in_l/r` espelham a mesma entrada mono; `amp_out` mede o render Java, não telemetria direta do callback Oboe.
- [x] Adicionar testes unitários para amplitude, pitch 440 Hz, CPU, silêncio e nomes desconhecidos; adicionar teste de contrato Lua/host.
- [ ] CI da revisão `bf1c4a20860520f8403fdb2bf3883afd776918c2` precisa confirmar JUnit, SynthDef e APK.
- [ ] Polls específicos declarados por engines SuperCollider customizadas e telemetria direta do Oboe ainda não são suportados; permanecem fora da whitelist de suporte declarado.



## CI corrigido, OSC completo e gestão de imagens — 2026-11-08

- [x] A probe ARM64 `42441605506` passou e publicou `scsynth-android-probe-307` (5.301.619 bytes).
- [x] A integração `42441605509` passou no SHA `81e1a4c18a3346a62f2db19dfc2e0b5cff1eebac`: `testDebugUnitTest assembleDebug` concluiu com `BUILD SUCCESSFUL in 45s`; 171 testes JUnit, zero falhas; os 7 testes MidiMessageQueue passaram.
- [x] APK `NornsMobile-0.4.5-debug-492`, artefato ID `13601872878`, 3.601.601 bytes; payload `scsynth-android-imported-492`, ID `13601962215`, 5.280.645 bytes.
- [x] As duas falhas de contrato da execução anterior foram corrigidas: o teste de hardware agora reconhece `poll` como implementado e o teste OSC usa os limites nomeados do decoder.
- [x] OSC agora recebe e agenda bundles por timetag NTP, envia bundles por `osc.send_bundle`, preserva `int64/float64/blob` e limita a fila agendada a 1024 mensagens. Callback agendado é serializado com `close/listen` para impedir entrega depois da troca do socket.
- [x] Implementar `screen.free_image(image)`; imagens carregadas/criadas passam a ser rastreadas, liberadas explicitamente e recicladas no reset do script. O reset desenrola contextos `draw_to` antes de descartar imagens.
- [x] CI agora exige e valida `SoftcutIsolationInput.scsyndef`, o SynthDef de fonte DC determinística usado na prova de isolamento BufWr CH1/CH2.
- [ ] Nova execução CI desta revisão de gestão de imagens e validação explícita do SynthDef de isolamento ainda pendente.
- [ ] Prova runtime de isolamento BufWr no scsynth, roteamento estéreo, handoff exclusivo Oboe/AudioTrack e teste geral físico continuam adiados até a conclusão de todas as implementações.
- [ ] Engines SC customizadas, buses engine/tape e backends físicos Grid/Arc/Crow/USB-serial/HID/keyboard/gamepad permanecem pendentes; não declarar suporte sem backend funcional.

A branch feature permanece isolada de `main`; PolyPerc, fallback Java e `native_gate_open=false` continuam preservados.


## Proteção agregada de memória da Screen — 2026-11-08

- [x] Limitar imagens criadas/carregadas por script a 32 MiB agregados, com pré-checagem antes de decodificar/alocar e confirmação pelo tamanho real do bitmap.
- [x] Atualizar a contabilidade em liberação explícita e reset; impedir que a soma de alocações válidas isoladamente esgote a memória do processo.
- [x] Adicionar contrato JUnit para orçamento agregado e contabilidade de ciclo de vida.
- [ ] Confirmar JUnit, SynthDefs, APK e artefatos no CI desta revisão.
- [ ] A proteção de memória não substitui a prova runtime BufWr CH1/CH2, roteamento estéreo e handoff Oboe/AudioTrack; Softcut permanece fechado.


## Continuidade automática e estado CI — 2026-11-08

- [x] README reconciliado com 0.4.5 e com os limites reais da integração Android.
- [x] Branch confirmada como `feature/0.4.5-android-audio-driver`; HEAD documental atual `619df89d1d035fb8ecb8fd30466de4fdd454f726`. `main` permanece intocada.
- [x] Execução probe `42442486742` e integração `42442486700` concluíram com sucesso no commit separado `f5a8c6f2b952747542c16926c7edf6a99953c925`; artefatos gerados: APK `NornsMobile-0.4.5-debug-495` (3.603.363 bytes, artifact `13602475931`) e payload `scsynth-android-imported-495` (5.280.645 bytes, artifact `13602296317`). Esses resultados pertencem ao SHA `f5a8c6f`, não devem ser apresentados como CI do HEAD documental `619df89`.
- [ ] Validar JUnit/SynthDef/APK em um SHA de código que inclua a proteção agregada Screen 32 MiB e o restante das alterações atuais. O commit de documentação não altera o código.
- [ ] A integração Oboe/SC está compilada e empacotada; ainda não existe prova acústica de ownership exclusivo ou handoff em hardware.
- [ ] Manter `native_gate_open=false`; playback Softcut e seis vozes permanecem bloqueados até leitura independente de CH1/CH2, roteamento PCM estéreo e prova de não duplicação.
- [ ] Não solicitar teste no SM-G973F até concluir as implementações e validações automatizadas restantes.

**Próxima sequência técnica:** concluir APIs mobile pendentes e runtime de engines; implementar buses engine/tape somente com testes de isolamento; avançar Softcut em modo muted para provas determinísticas de gravação/readback; somente após os critérios de aceite considerar handoff e playback.


## Revisão de APIs sem alteração dos gates de áudio — 2026-11-08

- [x] A camada Lua/Android ganhou despacho de eventos de teclado físico; não modifica AudioTrack, Oboe, ownership, buffers ou roteamento.
- [ ] Probe 42443236322 e integração 42443236320 foram iniciadas no SHA de código fec688310cc02481caf41566c6ee88a9d0444b80; confirmar conclusão e artefatos.
- [ ] Nenhuma evidência nova de gravação/leitura BufWr CH1/CH2 ou PCM estéreo foi produzida nesta etapa.
- [x] Manter native_gate_open=false, PolyPerc e fallback Java; não habilitar handoff nem playback Softcut.


## Teclado/gamepad Android — sem impacto no áudio — 2026-11-08

- [x] Teclado e gamepad genérico integrados ao dispatcher Lua/Activity; sem alterações em AudioTrack, Oboe, ownership, buffers ou roteamento.
- [ ] CI do commit que inclui o gamepad deve confirmar JUnit, SynthDefs, APK e payload ARM64.
- [x] Permanecem os gates: native_gate_open=false, PolyPerc e fallback Java preservados; Softcut nativo/seis vozes não habilitados.


## Diagnóstico de execução CI — 2026-11-08

- [ ] Probe 42444196041 e integração 42444196021 no SHA d5db696b falharam na alocação do runner (runner_id=0; nenhum step executado; sem log disponível), inclusive após rerun. Nenhuma conclusão de JUnit, SynthDef ou APK pode ser derivada desses runs.
- [x] Workflow independente build.yml passa a executar também na branch feature para fornecer validação Gradle/JUnit/APK enquanto a fila dos workflows nativos é recuperada.
- [x] Gates de áudio permanecem fechados; esta alteração não toca Oboe, AudioTrack, Softcut ou roteamento.


## Engine 32Harmony — impacto e segurança — 2026-11-08

- [x] Implementar SynthDef estéreo pré-compilado de 32Harmony e bridge de parâmetros SC via node nativo; não requer sclang no dispositivo.
- [ ] CI precisa validar compilação do SynthDef e APK para o SHA da implementação.
- [x] O engine escreve apenas na saída estéreo master e não habilita Softcut; `native_gate_open=false` e buses engine/tape continuam fechados.


## Correção dos workflows de SynthDef — 2026-11-08

- [x] Corrigir shell continuation no validador da integração (evita interpretar o path Norns32Harmony como comando separado).
- [x] O workflow de APK independente agora compila o conjunto de SynthDefs com sclang e valida Softcut/32Harmony, não apenas PolyPerc/TestSine.
- [ ] Aguardar runner para confirmar o compile real do SynthDef e o build APK; não marcar como aprovado antes disso.


## HID Android — sem impacto no áudio — 2026-11-08

- [x] Dispatcher HID de entrada Android (EV_KEY/EV_ABS/EV_REL) e vports implementados para teclado, mouse e gamepad.
- [x] Não altera Oboe, AudioTrack, ownership, buffers ou Softcut; HID send/saída permanece indisponível.
- [ ] CI do HEAD 82d4f2a não iniciou steps por falha de alocação de runner (runner_id=0).


## Atualização de hardware USB — 2026-11-08

- [x] USB Serial CDC-ACM experimental foi implementado em uma ponte isolada, sem tocar no pipeline de áudio, no ownership Oboe/AudioTrack ou nos buffers Softcut.
- [ ] CI das alterações USB ainda não executou etapas: jobs dos workflows `42475417644`, `42475417648`, `42475417641`, `42475426882`, `42475426880` e `42475426822` encerraram sem steps/logs úteis.
- [x] Nenhuma mudança no gate nativo: `native_gate_open=false`; PolyPerc e fallback Java preservados.
- [ ] A prioridade de áudio continua: completar buses engine/tape e prova independente de BufWr CH1/CH2, roteamento estéreo e ausência de duplicação antes de handoff/playback Softcut.

- [x] Serial CDC-ACM transporta payload binário pela API LuaString, sem passar por conversão UTF-8; nenhum caminho de áudio foi alterado.


## Telemetria CPU nativa para Poll — 2026-11-08

- [x] Medir duração de cada callback Oboe versus tempo de áudio disponível e atualizar média exponencial/pico por janela.
- [x] Publicar métricas em atomics lock-free, fora do snapshot de 54 campos reservado à telemetria Softcut.
- [x] Expor e validar estruturalmente `NornsSC_ReadCpuMetrics` através de JNI; integração CI exige o símbolo.
- [x] Usar os valores nativos para `cpu_avg` e `cpu_peak` somente quando scsynth está ativo; fallback Java continua preservado.
- [ ] JUnit/probe/integração APK ainda sem execução: runners encerram os jobs sem steps e logs.
- [ ] Validar carga CPU e picos em áudio real na auditoria final.
- [x] Nenhuma alteração no ownership de saída, PolyPerc, fallback Java ou gate Softcut: `native_gate_open=false`.

- [x] Serial CDC-ACM agora encerra requisições de permissão pendentes na limpeza de handler/script e usa o ID de handler esperado nos callbacks de remoção.


## Crow — transporte serial experimental

- [x] O firmware Crow oficial usa CDC-ACM e anuncia VID:PID `0483:5740`, fabricante `monome & whimsical raps` e produto `crow: telephone line`; a implementação reconhece interface `00`.
- [x] Handler Crow usa o backend USB CDC-ACM isolado; nenhuma mudança em áudio, Oboe, ownership, buses ou Softcut.
- [ ] Compatibilidade completa de Crow/ii/public e teste físico permanecem pendentes.
- [x] Gate nativo de Softcut permanece fechado; PolyPerc e fallback Java preservados.

- [x] Crow CDC-ACM e telemetria CPU nativa de Poll foram adicionados sem alterar o pipeline de áudio ou os gates de segurança.
- [ ] CI dos commits atuais segue sem executar steps; manter `native_gate_open=false`, PolyPerc e fallback Java.


## Continuidade e estado verificado — 2026-11-09

- HEAD da branch de desenvolvimento na última verificação: `519db4927985627d17fe5f7cf4811587335fa08b`.
- Workflows APK `42699152488`, integração scsynth/Oboe `42699152213` e probe ARM64 `42699152148` concluíram com sucesso no mesmo SHA.
- Artefato APK da integração: `NornsMobile-0.4.5-debug-14`; artefato de probe: `scsynth-android-probe-12`. A existência desses artefatos não substitui ensaio acústico no dispositivo.
- Gate Softcut permanece fechado. Não habilitar playback nativo nem seis vozes nesta etapa.
- Evidência física anterior continua sendo bloqueadora: readback nativo BufWr reportou zero para CH1 e CH2 no ensaio documentado; o comportamento do cursor também divergiu do limiar. Os testes automatizados de roteamento/ownership não provam escrita e leitura corretas em hardware.
- Próxima investigação: isolar a fonte de sinal entregue a `SoftcutRecorder.in_bus`, confirmar ordem/grupo dos nós, validar o bus de entrada e a janela BufWr, e registrar cada resposta `/b_getn` após `/synced`. Manter buffers de produção intocados durante probes.
- Critério de saída: CH1 e CH2 devem gravar padrões determinísticos distintos em buffers temporários, apresentar readback não nulo e independente, e stop confirmado. Só então avançar para cursor/rate/reverse e, por último, avaliação de handoff. Nenhuma destas provas autoriza automaticamente abrir o gate de playback.


## Integração do send de engine nativo — 2026-11-12

- [x] Reservar o bus mono 31 para o send do engine nativo ao Softcut.
- [x] Capturar a saída master L/R e reduzir para mono após o bloco de SuperCollider, marcando o bus para o próximo buffer counter.
- [x] Adicionar entrada `engine_bus=31` e controle `engine_level=0` ao SynthDef `SoftcutRecorder`.
- [x] Atualizar o validador para exigir bus 31, engine send inicialmente mudo e conexão efetiva ao UGen de entrada.
- [x] Preservar o snapshot diagnóstico de 54 valores já adotado pelo destino.
- [x] Adicionar teste C++ isolado para o bus e incluir a verificação no workflow manual de contratos.
- [ ] Executar os testes C++/Python e validar o SynthDef compilado.
- [ ] Provar sinal independente no recorder antes de habilitar qualquer nível de engine.
- [ ] Manter `engine_level=0` e o gate de saída nativa fechado até a prova de ausência de duplicação e roteamento estéreo.


### Send de engine nativo — reconciliação entre repositórios

O port do bus 31 e do controle `engine_level` foi aplicado somente na branch de migração do repositório colaborador. O valor inicial permanece zero, o gate de saída nativa continua fechado e o send não foi liberado para uso real. Os testes C++/Python e a compilação/validação dos SynthDefs continuam pendentes.
