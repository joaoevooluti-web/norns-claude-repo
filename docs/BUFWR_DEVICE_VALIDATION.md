# Validação física dos dois BufWr — Norns Mobile 0.4.5

## Pré-requisitos

- APK NornsMobile-0.4.5-debug-226 instalado em dispositivo ARM64.
- Backend nativo iniciado em ready_muted.
- Parar outros scripts Softcut antes do teste.
- Alimentar o microfone com tom/ruído durante a gravação.
- O teste sobrescreve aproximadamente 29,99–31,5 s dos buffers Softcut 1 e 2. Faça backup antes.

O roteiro Lua fornecido separadamente como BufWrDeviceValidation.lua é executado como um script Dust/Norns.

## Cenário

| Canal | Voz | Buffer | Rate | rec_offset |
|---|---:|---:|---:|---:|
| CH1 | 1 | 1 | +1 | +4 ms |
| CH2 | 2 | 2 | -1 | -4 ms |

Ambos usam janela de loop 30,0–31,5 s e gravam simultaneamente durante 4 s. Depois são parados juntos. O worker deve confirmar record=0 por /sync, ler a janela expandida de cada buffer e transferir os blocos para o buffer Java correspondente.

## Critérios de aprovação

1. Durante a gravação, ambos os canais aparecem com fresh=true, recording=true, amostras aumentando e cursores avançando em sentidos opostos.
2. O erro Java↔BufWr permanece dentro do limiar publicado (threshold_seconds) depois dos transientes iniciais, inclusive atravessando wrap.
3. Depois do stop, nenhum canal permanece eternamente ativo/pending; a leitura de volta termina para os dois.
4. softcut.render_buffer chama softcut.event_render para CH1 e CH2. Com entrada presente, os dois buffers apresentam amostras não nulas.
5. A recuperação só pode ser enfileirada quando a gravação estiver parada e o worker tiver confirmado o stop. Nunca reposicionar um BufWr ativo.
6. O gate nativo permanece fechado. PolyPerc/fallback Java continuam funcionando e não há reprodução duplicada.

## Diagnósticos

O roteiro imprime linhas [BufWr validation] com os campos de
softcut.native_recorder_diagnostics(channel): frescor, gravação, número de
amostras, posição Java base, cursor Java com offset, posição nativa, erro,
máximo, RMS, limiar e estado da recuperação. Também imprime métricas de peak/RMS
dos callbacks de readback Java.

## Limite desta validação

O CI aprovou o build, o SynthDef, os validadores e os testes JUnit. A aprovação
de hardware depende de executar este roteiro no dispositivo real e guardar
o console [BufWr validation]. Os testes unitários não simulam fisicamente
o Oboe, o microfone ou a escrita em tempo real do scsynth.


## Correção do gate de escrita — 0.4.5

O SynthDef `SoftcutRecorder` declara `enabled=0` por padrão e calcula
`recordGate = record * enabled`. Portanto, enviar somente `record=1`
não habilita o `BufWr`. O worker Java agora envia `enabled=1` e
`record=1) no mesmo `/n_set`; no stop envia ambos como zero antes de
aguardar `/sync`. O readback permanece serializado no mesmo worker OSC.

A CI executa `tools/test-softcut-recorder-gate.py` para verificar esse
contrato de fonte. Isso não substitui a validação física: repetir o roteiro
no SM-G973F e confirmar peak/RMS não nulos nos dois buffers.


## Resultado físico no SM-G973F — 2026-11-07 (reteste 0.4.5)

O console fornecido pelo usuário confirma Android API 31 / Samsung SM-G973F,
scsynth 3.14.1 ARM64 iniciado a 48 kHz / bloco 64, 23 UGens instalados,
quatro SynthDefs instalados e PolyPerc selecionado como fallback porque
`BufWr Dual Validation` não existe como engine Norns Mobile.

O roteiro observou ambos os canais em gravação durante quatro segundos,
com entrada presente e métricas de cursor atualizadas. Contudo, o resultado
final **não atende aos critérios de aprovação**:

| Métrica | CH1 | CH2 |
|---|---:|---:|
| recording_sampled | true | true |
| readback_callback | true | true |
| count | 24.000 | 24.000 |
| peak | 0.0 | 1.0 |
| RMS | 0.0 | 0.358658549 |

O CH1 reportou max/RMS do monitor durante a gravação próximos de 0,5,
mas o readback integral do buffer retornou peak=0 e RMS=0. Isso demonstra
divergência entre o sinal observado no monitor/cursor e o conteúdo efetivamente
recuperado do buffer 20001; não prova que a gravação foi persistida.

O CH2 retornou conteúdo não nulo, porém suas amostras de stop/readback ainda
mostravam `recording=true` e `status=waiting_for_confirmed_stop` até t=3 s.
A leitura não deve ser interpretada como confirmação do stop; é necessário
determinar se a confirmação chegou depois da janela registrada ou se o estado
do supervisor ficou inconsistente.

Durante a gravação, os erros Java↔nativo ficaram muito acima do limiar
(0,001333 s): CH1 aproximadamente 0,46–0,50 s; CH2 oscilou de 0,005 a
0,545 s, com posições nativas saltando entre regiões. Ambos reportaram
`recovery=true` e `status=seeded_from_java_on_start`. A hipótese de
reancoragem/seed inicial e alinhamento do cursor precisa ser isolada do
problema de escrita/readback.

### Próxima sequência de diagnóstico (sem abrir o gate)

1. Não alterar `native_gate_open=false`; não habilitar playback Softcut nativo
   nem handoff Oboe/AudioTrack. Preservar PolyPerc e fallback Java.
2. Fazer um ensaio isolado CH1, depois CH2, com rate=+1 e posição/cursor
   determinísticos, mantendo o mesmo sinal de entrada. Ler de volta a região
   imediatamente após um stop explicitamente confirmado por /synced.
3. Repetir o ensaio com rate=-1 apenas depois de ambos os canais passarem
   isoladamente em rate=+1. Isso separa escrita básica de wrap/reverse.
4. Registrar os valores do buffer ID, node ID, start/end frame, position,
   rate, rec_level, pre_level, input_level, rec_offset, confirmação /synced
   e resultado de cada bloco /b_getn. O log atual não identifica o conteúdo
   de cada chunk nem prova qual caminho de sinal alimentou CH1.
5. Verificar o mapeamento de entrada e os parâmetros do SynthDef Recorder,
   especialmente o in_bus e os buses de mixagem, sem inferir que o nível
   observado no monitor corresponde ao mesmo sinal gravado por BufWr.
6. Só considerar a validação aprovada quando os dois buffers tiverem
   readback não nulo, stops confirmados, cursor dentro do limiar após
   transientes e teste repetido sem divergência. Até lá, gate fechado.

Esta execução é evidência de gravação/readback parcial, não de validação física
concluída.


## Instrumentação e APK corrigido — 2026-11-07

A primeira compilação da instrumentação falhou porque a chamada de log `AppLog.w()` não existe. Foi substituída por `AppLog.i()` no commit `6fd43b9f75e2297de840f1a57c4ffae926fd2090`. A probe ARM64 e a integração CI desse commit passaram; `:app:testDebugUnitTest` e `:app:assembleDebug` terminaram com sucesso. APK gerado: artefato `NornsMobile-0.4.5-debug-264`, ID `13574930451`.

A instrumentação registra parâmetros de start/stop do BufWr e métricas peak/RMS por bloco de readback. Ela não altera o roteamento de áudio nem abre o gate nativo. O próximo teste físico deve usar esse APK, testar CH1 e CH2 isoladamente em rate=+1, verificar confirmação explícita de stop/sync e comparar os chunks lidos. Não aprovar os buffers até ambos apresentarem readback não-zero, stop confirmado e cursor dentro do limite estabelecido.

## Reteste físico com leitura por canal — 2026-11-07

Novo console do Samsung SM-G973F / Android API 31, scsynth 3.14.1 ARM64,
48 kHz / bloco 64, Build 264:

- O start dos dois nodes foi enviado e recebeu `/synced`.
- A entrada monitorada pelo caminho Java apresentou nível significativo nos
  dois canais.
- O stop CH1 foi confirmado em 7 ms; o stop CH2 foi confirmado em 10 ms,
  porém somente depois de o worker concluir a longa leitura do CH1.
- O roteiro Lua reportou `READBACK CH1 peak=0/RMS=0` e
  `READBACK CH2 peak=1/RMS=0,412657`. Esses números pertencem ao
  `SoftcutCore` Java (`softcut.render_buffer`), não ao buffer nativo.
- O readback real do scsynth está nas linhas
  `Softcut BufWr readback chunk`: todos os chunks apresentados para os
  buffers 20001 e 20002 têm `peak=0` e `RMS=0`.
- Portanto, nenhum dos dois buffers nativos foi aprovado. O nível no monitor
  Java não prova que o SynthDef `SoftcutRecorder` recebeu o mesmo sinal.

### Achados adicionais de coordenação

O worker OSC é serial e o readback anterior processava a janela inteira de
72.000 frames de um canal numa única tarefa (96 requisições de 750 frames).
Enquanto CH1 ocupava o worker, o stop de CH2 aguardava na fila. O supervisor
continuava observando CH2 como ativo e solicitava stop repetidamente.
O log mostra diversas chamadas duplicadas para o mesmo canal/node.

Foi aplicada na branch de feature uma correção restrita:
- marcar stop como pendente na primeira solicitação e ignorar stops duplicados;
- ler apenas um chunk confirmado por tarefa e enfileirar a continuação no fim
  do worker, cedendo espaço para stop/sync de outro canal;
- acrescentar testes unitários para deduplicação de stop e progressão dos
  chunks de readback.

Essa correção trata contenção/duplicação no controle, mas não explica nem
corrige ainda o sinal zero dentro dos buffers nativos. A validação física
deve ser repetida com o novo APK após CI. O próximo diagnóstico de sinal deve
isolar CH1 e CH2 separadamente, ambos em rate=+1, e instrumentar o SynthDef
para confirmar `in_bus`, número de canais e amplitude na entrada imediatamente
antes do `BufWr`. O caminho de monitor Java e o buffer nativo precisam ser
medidos separadamente.

Restrições mantidas: `native_gate_open=false`, PolyPerc e fallback Java
preservados, sem handoff Oboe/AudioTrack e sem playback Softcut nativo.


## Reteste físico adicional — Build 264 / SM-G973F (2026-11-07)

O console mais recente identifica package version 0.4.5, versionCode 9, Android API 31 e Samsung SM-G973F. O scsynth 3.14.1 ARM64 iniciou a 48 kHz / bloco 64; Oboe abriu entrada mono. Os dois BufWr receberam start com `sent=true synced=true`.

Readback direto do scsynth:
- Buffer 20001 / CH1: chunks reportados com peak=0 e RMS=0; resumo final peak=0/RMS=0.
- Buffer 20002 / CH2: chunks reportados com peak=0 e RMS=0; resumo final peak=0/RMS=0.
- Stop CH1 confirmado em 5 ms e stop CH2 em 10 ms.

Os níveis não nulos do monitor Java não representam o sinal dentro do SynthDef: o driver duplica a captura Oboe em rings SPSC separados, um lido pelo fallback Java e outro lido por `fillInputBuses` para alimentar o World SuperCollider. O default `in_bus=2` do `SoftcutRecorder` coincide estaticamente com o primeiro bus de entrada, considerando dois buses de saída. Ainda falta comprovar amplitude efetiva nesse bus e no sinal `incoming` imediatamente antes do `BufWr`.

### Próximo diagnóstico

1. Instrumentar pico/RMS do ring nativo e do bus de entrada do World no callback de áudio.
2. Instrumentar pico/RMS de `incoming`, estado de `recordGate` e validade de `writePhase` dentro do SynthDef.
3. Executar CH1 isolado em rate=+1; parar, aguardar /synced e ler diretamente o buffer 20001.
4. Repetir CH2 isolado em rate=+1 e ler diretamente 20002.
5. Somente após ambos aprovarem, testar gravação simultânea e depois rate=-1.

A correção de fila (stop deduplicado e readback cooperativo) não altera a rota do sinal e não pode ser considerada correção da gravação. A CI do HEAD posterior ainda não foi confirmada; o Build 264 não contém essas alterações mais recentes.

Critério continua sendo readback nativo não nulo, stop confirmado, cursor dentro do limite e repetição consistente. Manter `native_gate_open=false`, PolyPerc/fallback Java e saída exclusiva; não iniciar handoff Oboe/AudioTrack nem playback Softcut nativo.


## Instrumentação de sinal por estágio — 2026-11-07

A branch adiciona uma cadeia de probes para separar o sinal que antes era observado apenas no monitor Java:

1. **Ring nativo → bus de entrada do World:** `input.bus_peak`, `input.bus_rms`, frames drenados e contadores dropped/skipped/underrun.
2. **Dentro de cada SoftcutRecorder:** quatro canais de diagnóstico dedicados, em ordem: sinal bruto `In.ar(in_bus)`, `incoming` após mixagem/níveis, `recordGate` e `writeValid`. Cada sinal fornece peak e RMS por bloco.
3. **Separação dos canais:** CH1 usa diagnostic_bus 19 (19–22); CH2 usa 23 (23–26). Os cursores continuam em 17/18. Os buses de diagnóstico não são enviados para Oboe nem para os buses audíveis do Softcut.
4. **Snapshot:** 40 floats via C++ → JNI → Java → Lua. Os índices legados 0–15 permanecem iguais; 16–23 descrevem a entrada/ring; 24–31 CH1; 32–39 CH2.
5. **Console:** o worker registra `Softcut BufWr signal probe` em start-confirmed e stop-confirmed. A função Lua `softcut.native_diagnostics()` também expõe `input` e `recorder_signal`.

Interpretação:
- input bus peak/RMS = 0: o ring nativo não está fornecendo sinal útil ao bloco do World (ver também frames e contadores).
- input bus não zero, physical_input zero: o bus indicado em `in_bus` não está chegando ao UGen `In` esperado.
- physical_input não zero, incoming zero: falha na composição/níveis do sinal de gravação.
- incoming não zero, gate zero: parâmetros `record/enabled` ou node incorreto.
- incoming/gate/writeValid não zero, mas readback nativo zero: concentrar no `BufWr`, fase, buffer ID, bounds e sincronização.

A instrumentação não abre o gate de saída nativa e não muda a rota audível. O readback físico dos dois buffers segue reprovado até nova execução no Samsung. O novo APK só deve ser usado após CI do HEAD aprovada.


## Acumuladores de sinal por sessão — implementação em andamento (2026-11-07)

A instrumentação anterior publicava somente peak/RMS do último bloco processado pelo World. Como o worker consultava o snapshot depois de /synced no start/stop, um bloco silencioso poderia substituir o sinal que realmente ocorreu durante a gravação. O log não representava necessariamente a sessão inteira.

Correção implementada na branch feature:
- Cada canal possui quatro acumuladores independentes: entrada física, incoming, recordGate e writeValid.
- Peak é o máximo absoluto observado desde o reset; RMS é calculado sobre todas as amostras finitas acumuladas na sessão.
- O reset é solicitado por canal através da API nativa/JNI/Java somente após confirmação de start por /synced. O callback Oboe aplica o reset de forma atômica, sem mutex, alocação ou espera no thread de áudio.
- Após stop confirmado por /synced, o worker lê o snapshot acumulado antes de iniciar o readback. O log start não apresenta mais valores antigos de uma sessão anterior.
- Adicionado teste C++ isolado para peak, RMS agregado, reset e descarte de NaN/Inf; CI compila com -Wall -Wextra -Werror.

Isso melhora a qualidade da evidência, mas não corrige por si só a gravação BufWr. O readback direto dos buffers 20001/20002 continua sendo o critério determinante. O reset e a acumulação não alteram o sinal gravado, o roteamento audível ou o gate de saída.

Validação pendente: probe ARM64, integração/APK, teste unitário novo e revisão dos logs no dispositivo. Até então, não considerar a instrumentação aprovada e manter `native_gate_open=false`.


## Snapshot diagnóstico de cinco estágios (build seguinte)

O próximo APK registra por canal: `physicalInput`, `scaledInput` (ganhos `input_level` × `adc_level`), `incoming` (mix de entrada mais buses cut), `recordGate` e `writeValid`. CH1 usa buses 19–23; CH2 usa 24–28. O snapshot JNI/Java/Lua passa a 44 floats, preservando os campos 0–23. Os acumuladores do bus de entrada/ring são por sessão, com peak/RMS, frames lidos e contadores dropped/skipped/underrun relativos ao reset no início confirmado.

No teste, comparar a sequência: entrada física > entrada escalada > incoming > readback do buffer. Se `physicalInput` for não-zero e `scaledInput` zero, investigar controles de ganho (`input_level`/`adc_level`); se `scaledInput` for não-zero e `incoming` zero, investigar o somatório/matriz cut; se `incoming`, gate e validade forem não-zero e readback continuar zero, concentrar a investigação em BufWr/fase/buffer.


## Diagnóstico de cancelamento na matriz de corte — Build seguinte

O teste da Build 296 mostrou physicalInput e scaledInput não-zero, mas incoming zerado nos dois canais. A próxima instrumentação separa cutMix (soma dos seis buses de corte após cut_from_*) de incoming (scaledInput + cutMix). Cada recorder expõe seis canais: physicalInput, scaledInput, cutMix, incoming, recordGate e writeValid. CH1 ocupa buses 19–24 e CH2 25–30; snapshot nativo/JNI/Java/Lua passa a 48 floats. Se cutMix for aproximadamente o negativo de scaledInput, há cancelamento na matriz; se cutMix for zero e incoming continuar zero, investigar a soma/UGen/roteamento de diagnóstico. O readback direto dos buffers segue sendo o único critério de escrita nativa. Gate fechado e fallback Java preservados.
