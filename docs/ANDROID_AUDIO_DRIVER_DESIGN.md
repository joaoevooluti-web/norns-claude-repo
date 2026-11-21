# Driver de áudio Android para SuperCollider 3.14.1

## Objetivo

Adicionar um backend Android nativo ao `libscsynth`, usando AAudio (API 28+),
sem substituir o motor Java `AudioTrack/SynthCore` existente. O primeiro
critério de aceite é executar um SynthDef senoidal pelo `World` do SuperCollider
e ouvir o resultado no dispositivo.

## Descobertas verificadas no upstream 3.14.1

1. `World_New()` chama `SC_NewAudioDriver(world)` sempre que
   `WorldOptions::mRealTime` está ativo. Em seguida chama
   `Setup()` e `Start()` no driver.
2. `SC_AudioDriver` é a classe-base abstrata. O backend precisa implementar
   `DriverSetup()`, `DriverStart()` e `DriverStop()`.
3. `SC_AudioDriver::Setup()` configura taxa de amostragem e tamanho de bloco,
   chama `World_SetSampleRate()` e inicia a thread auxiliar que consome as
   filas NRT. O callback de áudio precisa sinalizar `mAudioSync` ao fim do bloco.
4. O processamento do servidor é executado com `World_Run(world)`; os buffers
   de áudio e filas de comandos são parte das estruturas internas do servidor.
5. Por isso, o driver Android deve ser compilado **dentro do target
   `libscsynth`**, junto com os headers internos do mesmo tag. Uma ponte JNI
   externa não deve tentar reproduzir ou acessar estruturas internas por conta
   própria.
6. O build atual foi feito com `SC_AUDIO_API=-1` e mantém referências
   indefinidas a `SC_NewAudioDriver`, `initializeScheduler`,
   `server_timeseed` e `oscTimeNow`. O artefato atual não é evidência de um
   backend de áudio pronto. A nova build deve resolver esses símbolos e não
   permitir símbolos indefinidos.
7. O driver de referência `SC_WebAudioDriver` no upstream atual demonstra a
   arquitetura host-callback (`SC_AudioDriver` + `World_Run`), mas não deve
   ser copiado: pertence a outro alvo/plataforma e seu arquivo atual tem cabeçalho
   AGPL-3.0. A implementação Android será escrita separadamente e licenciada
   de forma compatível com o projeto.

## Desenho proposto

- Novo arquivo upstream injetado pelo helper do probe:
  `server/scsynth/SC_AndroidAudio.cpp`.
- Definir uma seleção de backend Android dedicada no CMake do SuperCollider
  (sem usar `SC_AUDIO_API=-1`).
- Implementar `SC_NewAudioDriver(World*)`, `initializeScheduler()`,
  `server_timeseed()` e `oscTimeNow()` no backend Android.
- Abrir um stream AAudio PCM float estéreo, solicitar baixa latência e usar
  callback nativo.
- No callback: drenar as filas de comandos, processar eventos agendados,
  executar `World_Run()` por bloco SC e intercalar os buses de saída no buffer
  PCM do AAudio. Não alocar memória, bloquear mutexes, fazer I/O ou chamar JNI
  no callback.
- Solicitar início/parada do stream fora do callback e encerrar o driver antes
  de destruir o `World`.
- Usar `WorldOptions` com dois canais de saída, entrada desativada no primeiro
  marco, sample rate compatível com o stream e caminho de plugins privado.
- A ponte JNI passa a criar o `World`, manter seu ciclo de vida, encaminhar
  comandos OSC e informar disponibilidade somente depois de inicialização real.
- Manter `AudioTrack/SynthCore` como fallback, sem executar os dois motores
  simultaneamente.

## Implementação Oboe/AAudio em andamento (branch 0.4.5)

A branch `feature/0.4.5-android-audio-driver` adiciona:
- driver `SC_AndroidAudio.cpp` dentro do target upstream `libscsynth`;
- Oboe 1.9.3 fixado via CMake FetchContent;
- seleção real `AUDIOAPI=android`, sem `SC_AUDIO_API=-1` e sem permitir símbolos indefinidos;
- wrappers exportados para inicializar e encerrar `World` pela JNI;
- limpeza do `World` se a abertura/inicialização do stream falhar.

**Estado:** código ainda precisa passar pelo workflow de compilação ARM64 e, depois, ser testado em hardware. A JNI só considera o backend disponível quando os símbolos exportados existem na biblioteca empacotada. O teste de SynthDef e áudio audível ainda está pendente.

Para validar: GitHub → Actions → Experimental scsynth Android probe → Run workflow, selecionando esta branch.

## Critérios de aceite

- [ ] Build ARM64 sem símbolos indefinidos de backend.
- [ ] `libscsynth.so` e `libnorns_sc_bridge.so` carregam no Android.
- [ ] AAudio abre com callback e reporta sample rate / frames reais.
- [ ] Boot e shutdown repetidos não deixam threads ou streams ativos.
- [ ] Um SynthDef com `SinOsc` e `Out` produz áudio audível.
- [ ] Underruns, erro do stream e encerramento da Activity são registrados.
- [ ] O fallback Java continua funcional se o backend nativo falhar.

## Referências

- SuperCollider 3.14.1 `SC_CoreAudio.h`:
  https://github.com/supercollider/supercollider/blob/Version-3.14.1/server/scsynth/SC_CoreAudio.h
- SuperCollider 3.14.1 `SC_World.cpp`:
  https://github.com/supercollider/supercollider/blob/Version-3.14.1/server/scsynth/SC_World.cpp
- SuperCollider 3.14.1 `SC_WorldOptions.h`:
  https://github.com/supercollider/supercollider/blob/Version-3.14.1/include/server/SC_WorldOptions.h
- Android AAudio / baixa latência:
  https://developer.android.com/games/sdk/oboe/low-latency-audio
- Oboe Full Guide:
  https://github.com/google/oboe/blob/main/docs/FullGuide.md


## Snapshot de diagnóstico e medição pós-gate

A telemetria de fase/roteamento usa um snapshot com sequência atômica:
o callback publica os 13 valores como uma única geração; leituras concorrentes
são repetidas ou recusadas se observarem uma geração parcial. A leitura não
bloqueia o callback nem utiliza JNI no caminho de tempo real.

Os picos de saída Oboe são medidos no buffer PCM final recebido pelo callback,
depois da verificação do gate. Assim, representam o bloco efetivamente
entregue ao Oboe, em vez de estimar o resultado a partir dos buses Softcut.
Uma medição ainda é digital; a prova acústica no hardware continua separada.


## Supervisor periódico de fase Softcut

O callback Oboe publica um contador de geração junto ao snapshot de diagnóstico.
O host Java considera a fotografia stale se a geração não avançar por 180 ms;
o supervisor Lua executa a cada 33 ms e só coleta ou reancora com uma geração
fresca. O monitoramento é independente das consultas `softcut.get_position()`.

A correção atua exclusivamente com `sc.set(phaseNode, {position = javaPosition})`
no Synth node nativo silencioso. Não altera o nó de gravação, o motor Java,
ownership, gate Oboe nem o controle de play. O limiar é um bloco SC ou dois
frames, o que for maior, com cooldown de 200 ms por voz. Quando o gate nativo
está aberto, nenhuma reancoragem automática é permitida. O estado pode ser
consultado com `softcut.native_supervisor_diagnostics()`.


## Diagnóstico e recuperação dos cursores BufWr

O SynthDef `SoftcutRecorder` publica o cursor de fase em segundos em buses
dedicados 17/18. O driver lê esses dois buses no fim de cada bloco do scsynth,
mas não os copia para o PCM Oboe; a saída audível segue limitada aos buses 9/10
e permanece sujeita ao gate nativo fechado.

O supervisor compara cada cursor nativo com a posição Java da voz proprietária.
Durante gravação ativa ou estado pendente, divergências são apenas medidas e
marcadas como recuperação pendente. A reancoragem nunca interrompe ou desloca
uma escrita ativa. O worker de buffers é serial: após enviar `record=0` e
confirmar `/sync`, pode aceitar `/n_set position` para alinhar o cursor parado.
Falha de confirmação mantém o canal ativo/incerto e bloqueia a recuperação.
Toda nova gravação é semeada pela posição Java atual, não pela posição explícita
mais antiga.

A telemetria dos buses 17/18 é interna e não é considerada prova de áudio
acústico nem de consistência do buffer. A aprovação final ainda exige testar
gravação contínua, rates positivos/negativos, parada, readback e retomada no
aparelho real.


### Semântica exata do cursor BufWr

O testemunho do `SoftcutRecorder` publica `writePhase / BufSampleRate`,
incluindo o offset de gravação. A comparação Java usa a cabeça base somada
ao `rec_offset), mas a recuperação envia ao Phasor a cabeça base sem offset.
Assim o deslocamento não é aplicado uma segunda vez após a reancoragem.


### Integridade da telemetria e dos buffers

Se um testemunho de posição 17/18 estiver ausente ou inválido enquanto o
respectivo gravador estiver ativo, o estado daquele canal passa para stale;
não mantém a última posição como se ainda fosse atual. Enquanto a recuperação
estiver pendente, a ausência do cursor também mantém o canal stale e impede
reancoragem.

Os testes de referência verificam readback independente dos dois buffers
Java, avanço contínuo em rates positivo e negativo, e recusa de recuperação
quando o servidor nativo ainda não está pronto. A validação final de escrita
e readback nativos continua dependendo do CI e do aparelho físico.


### CI do cursor BufWr — correção de teste estático

A probe ARM64 foi concluída com sucesso. O workflow de integração parou no
validador Python por uma variável de teste (`buffers`) usada antes da
inicialização. O código de produção não foi apontado como causa desse erro;
a leitura explícita do arquivo Java foi movida para antes da asserção. A
confirmação do SynthDef, JUnit e APK aguarda uma nova execução do workflow.


### Phasor versus readback com rec_offset

Os limites do Phasor passam a permanecer em `loop_start/loop_end`, alinhados
com o cursor base Java e com o ponto correto de wrap. O `rec_offset` amplia
somente a reserva de capacidade e o intervalo de readback: no stop, Java copia
de volta desde `max(0, loop_start + min(0, rec_offset))` até
`loop_end + max(0, rec_offset)`. Isso impede que o offset positivo alongue
acidentalmente o loop do cursor nativo.


### Validação CI e passagem para ensaio físico

O commit c6273ecbb843016458979d0c9a23234e8c4749df passou a probe ARM64
SuperCollider 3.14.1 e o workflow de integração: gate/roteamento, compile
SynthDef, validação dos SynthDefs empacotados, testDebugUnitTest,
assembleDebug e inspeção das bibliotecas nativas no APK. O APK 0.4.5
(version code 226) contém os binários arm64-v8a de libscsynth,
libnorns_sc_bridge e libc++_shared.

Esta aprovação cobre compilação, validação estática do grafo UGen, testes
unitários e empacotamento. Não substitui a execução no SM-G973F. Para isso,
foi preparado o roteiro BufWrDeviceValidation.lua: CH1 grava em rate +1
com offset positivo e CH2 em rate -1 com offset negativo; ambos são parados,
lidos de volta ao Java e renderizados para análise. O teste de hardware deve
confirmar frescor, avanço, wrap, alinhamento, readback independente e gate
fechado. O play nativo continua bloqueado.
