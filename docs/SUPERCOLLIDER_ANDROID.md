# SuperCollider no Android — estado da integração

## Estado atual — servidor nativo e controle OSC

Este marco incorpora ao aplicativo o artefato validado do workflow `Experimental scsynth Android probe`:
- `libscsynth.so` do SuperCollider 3.14.1, Android ARM64 / API 28;
- 23 bibliotecas UGen ARM64 em `assets/scsynth/plugins`;
- ponte JNI vinculada explicitamente a `libscsynth.so`;
- entrada de controle nativa para pacotes OSC via `World_SendPacketWithContext`, exposta em Java por `SuperColliderBridge.sendPacket(byte[])`;
- serializador OSC 1.0 para mensagens, argumentos `int32`, `float32`, strings, booleanos e blobs;
- API Lua experimental `sc.is_running()`, `sc.send(...)`, `sc.int(n)` e `sc.load_synthdef(path)` para SynthDefs armazenadas dentro de `dust`;
- `ANDROID_STL=c++_shared` para que o Android Gradle Plugin empacote o runtime C++ compartilhado do NDK (`libc++_shared.so`).

O aplicativo passa a ser ARM64 neste marco, pois o artefato disponível contém somente `arm64-v8a`.
O workflow `.github/workflows/android.yml` que gera o APK estável foi preservado sem alterações.

**Limitações atuais:** o controle OSC nativo e o envio de SynthDef estão disponíveis como API experimental, mas os engines norns existentes ainda NÃO foram migrados para o `scsynth`. `engine.load()` e `engine_cmd()` continuam roteados para `AudioEngine`/`SynthCore` em Java; Softcut também continua no `SoftcutCore` Java. O envio de SynthDef usa `/d_recv` em um único datagrama UDP e, por isso, está limitado ao tamanho máximo de pacote; as respostas `/done`, `/fail` e `/synced` são capturadas em uma fila nativa limitada e podem ser consultadas por `SuperColliderBridge.pollReply()`; ainda não há despacho automático dessas respostas para scripts Lua, bundles OSC, nem confirmação integrada ao fluxo de `engine.load()`.

O servidor nativo inicializa no ciclo de vida do script quando a ponte está disponível. Isso comprova a inicialização do processo de servidor, não a saída audível validada em hardware. O aceite de áudio continua dependendo de teste real em dispositivo ARM64.

## Próxima etapa — engines nativos

1. Definir e incluir SynthDefs compatíveis com os engines suportados (começando por `TestSine` e `PolyPerc`), sem substituir o fallback Java antes de haver confirmação de carga.
2. Implementar confirmação OSC (`/done`, `/fail`, `/synced`) e gestão de IDs de nodes.
3. Conectar o polling de respostas ao ciclo Lua, adicionar gestão de IDs de nodes e encaminhar `engine.load()` / `engine_cmd()` para nodes nativos somente após confirmação do SynthDef; manter o caminho Java em caso de falha.
4. Depois disso, planejar a migração de Softcut como subsistema DSP independente, com buffers, leitura/gravação, sincronização e testes de áudio.

`sc.int(n)` deve ser usado para argumentos OSC que precisam ser `int32` (por exemplo, IDs de nodes). Números Lua comuns são serializados como `float32`, inclusive quando têm valor matematicamente inteiro, para preservar parâmetros de síntese como `440.0`.

`dust.resolve()` restringe os caminhos dos SynthDefs à pasta privada `dust` do aplicativo.

`scsynth` empacotado ou iniciado não deve ser considerado prova de áudio funcional. A validação final exige confirmação de SynthDef, criação de nodes e áudio audível no dispositivo.
## Driver Oboe — blocos de áudio variáveis

O callback do Oboe não é tratado como se sua quantidade de frames fosse sempre múltipla do bloco fixo do `scsynth`. O driver mantém um bloco de saída pré-alocado e consome seus frames entre callbacks; só executa `World_Run` quando o bloco anterior foi totalmente entregue. Isso evita descartar a cauda de callbacks que não coincidem com o tamanho de bloco do servidor. A cópia usa armazenamento alocado durante `DriverSetup`, sem alocação dinâmica no callback. A compilação valida o código nativo, mas a estabilidade em diferentes tamanhos reais de callback ainda precisa de validação em dispositivo.

## Primeiro engine com roteamento nativo: TestSine

O `TestSine` agora possui uma primeira rota experimental integrada ao fluxo `engine.load()` do Lua:

1. O host prepara o engine Java como fallback e solicita `TestSine.scsyndef` ao servidor nativo.
2. O runtime Lua consulta as respostas OSC sem bloquear o thread; aguarda `/done` de `/d_recv` (limite de 5 segundos).
3. Após a confirmação, cria um node nativo `TestSine` e encaminha `hz`/`amp` para os controles `freq`/`amp`.
4. Com o node criado, silencia somente o mixer de síntese Java (`engine_load("none")`); o processamento Java de Softcut continua ativo.
5. Se o servidor não estiver disponível, o envio falhar, chegar `/fail` ou ocorrer timeout, os comandos enfileirados são reproduzidos no TestSine Java.

O callback de `engine.load("TestSine", callback)` é assíncrono nesta rota e ocorre após a confirmação e criação do node, ou após a seleção do fallback. Respostas OSC que não pertencem ao carregamento interno são preservadas para `sc.poll_reply()`. O node é liberado no reset/encerramento do script.

**Escopo e validação:** esta é a primeira rota de engine, não uma migração geral. `PolyPerc` e os demais engines continuam no Java; Softcut permanece no `SoftcutCore` Java. A compilação e os testes automatizados não comprovam saída audível; ainda é necessário validar o carregamento e o som no Samsung SM-G973F. O SynthDef de teste é mono no bus 0, portanto esta etapa não deve ser tratada como validação final de imagem estéreo.
## Dependências e runtime

`libscsynth.so` declara dependência de `libc++_shared.so`. O Gradle configura `ANDROID_STL=c++_shared`; o Android Gradle Plugin deve empacotar a biblioteca de runtime do NDK correspondente. O APK deve ser inspecionado para confirmar que `lib/arm64-v8a/libc++_shared.so` está presente.

## Proveniência

- SuperCollider: 3.14.1
- Artefato: `scsynth-android-probe-20.zip`
- ABI: `arm64-v8a`
- Android API: 28
- NDK: 27.2.12479018
- CMake: 3.22.1

## Referências

- SuperCollider upstream: https://github.com/supercollider/supercollider
- Oboe: https://github.com/google/oboe
- Android AAudio: https://developer.android.com/ndk/guides/audio/aaudio



## Respostas OSC — primeira camada

A ponte nativa registra a função de resposta de cada pacote enviado ao World_SendPacketWithContext. O callback copia respostas OSC para uma fila MPMC fixa (128 pacotes de até 4096 bytes), sem alocação dinâmica, JNI ou mutex no callback do servidor. O app consulta a fila com SuperColliderBridge.pollReply(), que decodifica /done, /fail e /synced; synchronize(id) envia /sync com ID OSC int32.

Se a fila estiver cheia ou uma resposta exceder 4096 bytes, a resposta é descartada para não bloquear o processamento de áudio. A fila também é reinicializada ao iniciar uma nova sessão do servidor, descartando respostas pendentes da sessão anterior. O polling ainda é uma API Java interna: o próximo passo é expor o resultado ao runtime Lua e usá-lo para confirmar cargas de SynthDef e operações de nodes.


## API Lua para respostas e sincronização

A tabela global `sc` agora expõe:

- `sc.poll_reply()`: retorna uma tabela com `address` e, conforme a resposta, `command`, `message` ou `sync_id`; retorna `nil` quando não há resposta disponível.
- `sc.sync()`: envia `/sync` com um ID OSC inteiro crescente e retorna esse ID (ou `false` se o envio falhar). O script deve continuar consultando `sc.poll_reply()` até receber `{ address = "/synced", sync_id = id }`.

O carregamento de SynthDef continua assíncrono: `sc.load_synthdef(path)` indica apenas que o pacote foi aceito para envio. A confirmação efetiva chega posteriormente por `sc.poll_reply()` como `/done` ou `/fail` associado a `/d_recv`. A API não bloqueia o thread Lua aguardando o servidor.


## SynthDef inicial: TestSine

O asset `scsynth/synthdefs/TestSine.scsyndef` contém um SynthDef de teste com controles `freq` (440 Hz) e `amp` (0,1), usando `SinOsc` em áudio e saída estéreo/mono compatível via `Out` no bus 0. O app copia esse arquivo para armazenamento privado ao preparar o servidor. Scripts podem carregá-lo com `sc.load_synthdef("TestSine.scsyndef")`; a carga continua assíncrona e deve ser confirmada por `/done` ou `/fail` em `sc.poll_reply()`. Depois de receber `/done` para `/d_recv`, um script pode criar a voz com `sc.new("TestSine", {freq = 440, amp = 0.1})` e pará-la com `sc.free(node_id)`.

## PolyPerc nativo

O PolyPerc existente no SynthCore Java foi preservado como fallback. A rota nativa adiciona PolyPerc.scsyndef com os controles freq, amp, pw, release, cutoff, gain e pan. O grafo usa os UGens Pulse, remoção de DC para larguras de pulso assimétricas, MoogFF, EnvGen percussivo e Pan2 estéreo; o envelope libera o node automaticamente ao terminar.

O carregamento também é iniciado pelo fluxo normal de _norns.load_script, depois de o script declarar engine.name e antes de init(). Assim, comandos disparados por params:bang() durante a inicialização são enfileirados até o servidor confirmar /d_recv. Cada engine.hz(frequency) cria uma voz nativa independente; o gerenciador mantém até 12 vozes, aplica voice stealing e remove do registro os nodes que terminam pelo envelope. Os controles são capturados no instante de cada nota. Se o SynthDef não carregar ou o node não puder ser criado, o engine Java volta a receber os comandos. O processamento Softcut permanece no Java.

A validação automatizada confere a estrutura binária, os parâmetros e o grafo UGen dos SynthDefs TestSine e PolyPerc antes do APK. Isso valida os assets e a compilação, não comprova o timbre, a estabilidade nem a saída estéreo no aparelho. A comparação auditiva e a validação de áudio real no Samsung SM-G973F continuam pendentes.


### Buffers, entrada, gravação e readback nativos do Softcut

A ponte OSC suporta arrays float32 expandidos em argumentos consecutivos para `/b_setn`, com blocos de até 12.000 amostras por datagrama. A API Lua `sc.buffer_alloc`, `sc.buffer_setn`, `sc.buffer_zero` e `sc.buffer_free` permanece disponível.

O Softcut mantém dois buffers nativos SuperCollider (IDs 20001 e 20002) espelhados a partir dos buffers Java. O `SoftcutCore` continua sendo a fonte de verdade para o fallback e a única rota de reprodução audível nesta etapa. Após leitura de WAV, limpeza ou cópia no motor Java, a região modificada é enviada ao buffer nativo. Quando o tamanho necessário excede a capacidade nativa, o app libera e realoca o buffer, aguarda `/sync` e restaura o conteúdo anterior quando necessário. Os uploads são serializados em uma thread de prioridade reduzida, fora da thread de áudio.

O driver Oboe abre uma entrada mono independente da saída estéreo. O callback de captura apenas publica amostras em dois rings SPSC pré-alocados e lock-free: um é consumido no callback de saída antes de `World_Run`, preenchendo o bus de entrada do scsynth que começa no índice 2 (dois buses de saída precedem a entrada); o segundo encaminha o mesmo sinal ao `SoftcutCore` Java pelo JNI, mantendo o fallback funcional sem abrir dois dispositivos de captura. Se a permissão ou o dispositivo de entrada não estiver disponível, o servidor ainda pode iniciar com o bus de entrada zerado e o app mantém o caminho `AudioRecord` Java.

O gerenciamento nativo cria seis nodes `SoftcutVoice` em modo silencioso e dois nodes `SoftcutRecorder`, um escritor por buffer. Os gravadores usam `In`, `BufRd`, `Phasor` e `BufWr`; aplicam `rec_level`, `pre_level`, `input_level` e `recpre_slew_time`. A API Lua arbitra um único proprietário de gravação por buffer, recusando uma segunda voz que tente gravar no mesmo buffer. Antes de ativar `BufWr`, o motor de áudio reserva a região no buffer Java, envia o espelho se for necessário aumentar a capacidade e aguarda a sincronização do servidor. Ao parar, o gravador é desativado, o servidor é sincronizado e a região gravada é recuperada por `/b_getn`; a transferência ocorre em blocos de até 750 amostras com confirmação de aplicação pela thread de áudio antes de solicitar o próximo bloco, limitando memória pendente e evitando uma cópia integral dentro de um único callback. O estado de gravação só é confirmado depois de o servidor aceitar e sincronizar o node.

A ponte JNI usa buffers de resposta de 4096 bytes, compatíveis com cada bloco de readback. O broker correlaciona cada resposta `/b_setn` ao pedido de leitura, sem entregar essas respostas ao polling comum do Lua.

### Filtros, matriz cut→cut e fase compartilhada

O `SoftcutVoice` inclui estágios pré e pós com saídas LP/HP/BP/BR e dry independentes, cutoff, modulação por rate e ressonância. Os filtros SuperCollider RLPF/RHPF/BPF/BRF oferecem equivalência funcional; a resposta não é bit a bit idêntica ao filtro TPT do fallback Java. Cada voz publica seu sinal mono filtrado em um bus interno dedicado (índices 3–8). Os nodes continuam com `play=0`, portanto esses buses não habilitam saída audível.

Os nodes de voz são inseridos no início da cadeia de execução e os gravadores no final, garantindo que os seis buses estejam escritos antes de serem lidos no mesmo bloco. O gravador exclusivo de cada buffer soma a entrada mono física (com ganho global ADC e ganho individual) com os seis buses de voz, aplicando a matriz `level_cut_cut(src,dst,level)`. A matriz é mantida em Lua e atualizada no gravador que pertence à voz de destino, sem criar escritores adicionais no buffer. `level_input_cut` continua controlando a entrada física.

Enquanto o Java é a fonte audível de posição, `get_position`, `query_position`, eventos de fase e `voice_sync` usam o relógio do `SoftcutCore`. A posição é refletida nos Phasors nativos a cada 33 ms, evitando tráfego OSC em cada poll Lua e preparando a troca futura de saída. Isso não habilita playback nativo.

**Segurança de áudio:** `play=0` permanece forçado nas seis vozes nativas. O novo gravador não é uma rota de reprodução; o Java continua sendo a única saída Softcut audível até a validação do roteamento Oboe e a confirmação de ausência de áudio duplicado. PolyPerc e o fallback Java permanecem preservados.

**Limites de validação:** o CI compila o driver e os dois SynthDefs, verifica o grafo `BufWr`/entrada e executa testes unitários de reserva de buffer e readback OSC. Isso não comprova latência, estabilidade do microfone, sincronismo de gravação prolongada nem saída audível no aparelho; o teste geral no Samsung SM-G973F continua reservado para a etapa final.


### Compatibilidade de enable, fade_time e rec_offset

O SynthDef `SoftcutVoice` recebe `enable` e `fade_time`; o gate suavizado controla tanto o avanço do Phasor quanto a saída/bus de envio. O SynthDef `SoftcutRecorder` recebe `enabled` e `rec_offset`: o enable fecha a gravação sem criar outro escritor e o offset desloca somente o endereço de escrita, sem alterar o cabeçote lógico. O BufWr usa modo sem wrap, preservando o comportamento do fallback Java em limites do buffer. Ao parar, o readback automático cobre a região deslocada para offsets positivos e negativos.

### Captura Oboe e testes do ring SPSC

A entrada tenta Float mono, Float estéreo, Int16 mono e Int16 estéreo, mantendo a taxa de amostragem efetiva da saída. Entradas estéreo são convertidas para mono por média. Os dois consumidores (bus de entrada do scsynth e fallback Java) usam rings SPSC independentes; os callbacks não alocam nem bloqueiam. O CI executa teste concorrente de 200 mil amostras, além de verificar overflow, underrun e descarte para alinhamento de bloco. O validador do SynthDef exige que cada uma das sete entradas (ADC + seis sends cut→cut) alcance o único BufWr.

### Saída nativa ainda bloqueada

O Oboe de saída continua em stream separado do AudioTrack Java. Sem um caminho de mixagem/handoff único e sem prova de ausência de áudio duplicado, o parâmetro `play` continua deliberadamente não sendo enviado aos seis nodes nativos. Nenhuma alteração desta etapa habilita reprodução nativa.


### Handoff exclusivo da saída Softcut (preparado, não ativado)

O SynthDef `SoftcutVoice` usa o par estéreo interno 9/10. O driver Oboe ignora os buses master 0/1 e só copia 9/10 quando o gate atômico nativo está ativo; por padrão permanece fechado. Isso separa o Softcut nativo do caminho Java e preserva PolyPerc no `AudioTrack` Java.

A classe `SoftcutOutputOwnership` serializa o handoff: Java é silenciado antes de abrir Oboe; no retorno, Oboe é silenciado antes de reativar Java. Falha ao ativar nativo restaura Java; falha ao silenciar nativo mantém Java silenciado para evitar duplicação. O estado inicial é Java-owned/native-muted.

O latch Lua `softcut._native_output_validated` permanece falso, e a ativação recusa handoff sem prontidão e prova de rota. `play` continua apenas em cache e não é encaminhado aos nodes. A infraestrutura e os testes de ownership estão implementados, mas não liberam reprodução nativa nem substituem a validação acústica/temporal em hardware.
