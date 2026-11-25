## Atualização incremental — 2026-11-14 (helpers de áudio e contratos LuaJ)

- Continuação no HEAD solicitado `82dfe9f4b7ca0fe83666c405e50a54f6bdf276ac`, exclusivamente na branch `migration/reconcile-jo1ngf-2026-11-12`. Commits adicionais de implementação/teste até `4e1258f2dd5b0f7348de060c052ade003e6d5255`; documentação atualizada em seguida. `main` não foi alterada e a comparação da branch com `main` mostra zero commits atrás.
- Corrigido `audio.set_audio_level()`: agora só persiste `norns.state.out` se `audio.level_dac()` confirmar o ajuste. Em modo nativo, uma rejeição de DAC não modifica o estado lógico.
- Teste LuaJ adicional exercita `audio.file_info()` (três retornos), `audio.tape_compute_diskfree()` com reserva upstream de 250 s e reserva customizada, rejeição/aceitação do DAC, clamp de volume e roteamento Android fail-closed.
- Testes Tape agora cobrem também `_on_status`, callbacks com cópias isoladas e remoção de subscribers. A execução testa que um subscriber não pode corromper o snapshot interno.
- Commits de teste marcados `[run-ci]` incluem os caminhos `app/**`, portanto atendem ao filtro de disparo de `unit-tests.yml`. Nesta sessão, o conector de GitHub Actions não disponibiliza a listagem geral de runs push: os checks do HEAD ainda aparecem vazios e a busca por commit retorna apenas execuções de PR. Não declarar o JUnit atual verde sem o run/artifact verificável.
- Último commit de código/testes nesta etapa: `4e1258f2dd5b0f7348de060c052ade003e6d5255`. A atualização posterior da matriz de compatibilidade está em `5331bc97c21934c62177c2d91009c59503ff6801`.
- PolyPerc, fallback Java, ownership Oboe/AudioTrack, gates de Softcut e bloqueio de seis vozes não foram alterados.

## Atualização incremental — 2026-11-14 (Tape upstream e inventário completo da API audio)

- Continuação na branch `migration/reconcile-jo1ngf-2026-11-12`, a partir de `5fa29878f9599b788726ad4d4b3177245b7429f4`; implementação de código até `453c6a668c61bcc175c89216aec302505baba9cc`. `main` continua preservada em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`.
- Corrigidos os enums de Tape para os valores upstream; playback aberto agora aparece como READY, PLAYING e PAUSED usam os valores oficiais, e a gravação tem EMPTY/READY/RECORDING/PAUSED.
- Implementado `audio.tape_is_recording()` e snapshot aninhado `play`/`rec`, com loop default verdadeiro, posições, duração, metadados dos arquivos, cópias isoladas e callbacks/assinaturas compatíveis.
- `audio.tape_compute_diskfree()` agora usa a reserva padrão upstream de 250 segundos.
- Auditoria função-a-função da `audio.lua`: controles implementados são mantidos; APIs dependentes de JACK/Crone, headphone amp, restart de sclang, Softcut→reverb e UI global de parâmetros retornam indisponibilidade explícita em vez de simular sucesso. Listas de portas Android permanecem vazias; nenhuma topologia fictícia é anunciada.
- Testes adicionados/atualizados em `TapeCoreTest`, `BootLuaSyntaxTest` e `AudioRoutingApiContractTest`. O último commit de testes inclui `[run-ci]` e toca caminhos `app/**`, portanto satisfaz o filtro do workflow unit-tests.
- **Validação pendente:** não foi possível recuperar o workflow #498 nem seus logs nativos/artefato JUnit. O conector disponível não expõe listagem de Actions por número; consultar `run_id=498` retornou 404, portanto 498 é apenas um número de execução e não um ID utilizável. Não declarar JUnit verde nem validação nativa concluída até obter o run_id/job_id e inspecionar o relatório completo.
- PolyPerc, fallback Java, ownership Oboe/AudioTrack, gates de Softcut e restrição de seis vozes permaneceram inalterados. Não houve teste físico nem ativação de playback Softcut nativo.

## Atualização incremental — 2026-11-14 (JUnit recuperado e DSP Faust)

- Recuperado o workflow de push `unit-tests.yml` pela listagem geral de Actions: execução #424, run_id `43552974079`, commit `812799b60e44a38cec81f4270b030b1c977056b4`. O artefato `norns-mobile-junit-reports-424` foi baixado e lido integralmente.
- Reexecução completa #467 (`run_id 43554199732`, commit `bf512257a9101c8fab669072411cdb16fe7baa1d`): **611 testes, 0 falhas, 0 ignorados**; artefato `norns-mobile-junit-reports-467` baixado e lido. O passo de testes C++ nativos, compilação dos SynthDefs, validação dos assets, fixtures upstream e `externalNativeBuildDebug` (JNI + Faust ARM64) também terminou com sucesso.
- Resultado do relatório anterior #424: 611 testes, 1 falha. A única falha era a asserção textual obsoleta em `TapeCoreTest.transportGenerationPreventsAnOldRenderFromOverwritingStopOrSourceSwap` (linha 192), que esperava a forma antiga `if (ended) playing = false;`; o código agora também limpa `playPaused` dentro de um bloco. A guarda por geração continuava presente. A asserção foi alinhada à implementação atual. O run subsequente desse commit corretivo foi cancelado pelo grupo de concorrência ao receber commits mais novos; o próximo run completo é necessário para confirmar a correção. O workflow foi ajustado para não cancelar execuções ativas quando chega um push posterior sem `[run-ci]`; também passou a instalar NDK 27.2.12479018 e CMake 3.22.1 antes de compilar o JNI.
- Integrados os processadores gerados pelo Faust 2.7.4 do upstream `monome/norns/crone`: `StereoCompressor_dsp` e `ZitaReverb_dsp`. Os arquivos DSP originais, headers de arquitetura e avisos/licenças foram incluídos para rastreabilidade. A UI de parâmetros mantém a semântica upstream de `APIUI::setParamValue` (escrita direta no zone).
- Criado `FaustEffectsCore`: compressor estéreo e reverb Zita reais, scratch buffers alocados na inicialização, sem alocações ou locks no processamento por bloco. O JNI recebe buffers diretos, e os controles de parâmetros são convertidos para IDs numéricos antes de chegar à thread de áudio.
- Integrada a cadeia no mixer Java: envia o mix estéreo pré-efeitos ao reverb, soma o retorno e aplica o compressor wet/dry antes do ganho DAC e da gravação Tape. Parâmetros iniciais acompanham `MixerClient::setFxDefaults()`; compressor mix e retorno do reverb iniciam em zero, como os níveis de inserção/retorno do mixer upstream. Amostras não finitas na saída processada são substituídas por silêncio antes do AudioTrack, evitando propagar NaN/Inf para o dispositivo.
- Expostas as APIs `audio.rev_on/off`, `audio.rev_param`, `audio.level_rev_dac`, `audio.comp_on/off`, `audio.comp_mix` e `audio.comp_param`. A chamada é recusada quando o engine nativo está ativo ou carregando: efeitos no Oboe ainda não estão roteados.
- Limitação de roteamento declarada: o envio atual do reverb é o mix Java estéreo completo pré-efeitos. Os barramentos upstream independentes ADC monitor / engine / Softcut / Tape e os respectivos níveis `level_monitor_rev`, `level_eng_rev`, `level_tape_rev` ainda não são anunciados como implementados.
- Adicionados testes determinísticos C++ para bypass, impulso e cauda do ZitaReverb, redução de ganho do compressor, wet/dry, valores inválidos e limites de bloco. O workflow compila/roda esses testes e executa `externalNativeBuildDebug` para validar o JNI Android antes dos testes JVM.
- PolyPerc e o fallback Java permanecem preservados. O caminho de FX nativo é explicitamente bloqueado no estado SC nativo; não foi alterado o handoff Oboe/AudioTrack, nem os gates de Softcut. As seis vozes continuam desabilitadas até prova independente de roteamento estéreo e ausência de duplicação. Nenhum teste físico foi solicitado.

## Atualização incremental — 2026-11-14 (audio.vu e auditoria DSP)

- Implementado o callback scriptável `audio.vu(in1, in2, out1, out2)`; o valor é atualizado no loop de áudio e despachado para a thread Lua em cadência de 50 ms, sem executar Lua na thread de áudio.
- O medidor replica a tabela IEC-60268 de `crone/Taper.cpp` e o detector de pico de `PeakMeter.h`: ataque instantâneo, liberação de 300 ms até -60 dB e escala emitida `0..64` (o código C++ upstream multiplica a posição por 64; a documentação Lua descreve 0..63).
- Como o ADC Android atual é mono, `in1` e `in2` espelham o mesmo canal físico. `out1/out2` medem os canais do AudioTrack Java após o ganho principal. O callback é suprimido quando o engine Lua está em modo nativo, pois o snapshot VU de 12 barramentos do crone ainda não foi integrado ao Oboe.
- Testes determinísticos novos cobrem taper, silêncio, ataque, liberação, sobreamplitude, amostras não finitas, equivalência temporal entre tamanhos de bloco e reset entre sessões. O coeficiente exponencial de release é recalculado apenas quando sample rate/tamanho do bloco mudam; teste contratual cobre callback Lua e supressão no modo nativo.
- Auditoria upstream: o reverb do norns é `re.zita_rev1_stereo` (ZitaReverb Faust) e o compressor é `co.compressor_stereo` (StereoCompressor Faust). O MixerClient upstream envia ADC monitor/Softcut/engine/Tape ao bus auxiliar do reverb, retorna o efeito ao insert bus e aplica compressor com wet/dry; parâmetros incluem predelay, RT60, damping, ratio, threshold, attack/release e pre/post gain.
- O AudioEngine Android ainda não possui runtime Faust nem processadores equivalentes. Por isso, `audio.rev_*` e `audio.comp_*` permanecem deliberadamente indisponíveis. Um teste contratual determinístico impede que stubs sejam expostos como se fossem DSP real; não é declarado suporte de efeitos até haver implementação e testes de resposta, estabilidade, bypass e mix.
- PolyPerc, fallback Java, ownership Oboe/AudioTrack e gates de Softcut não foram alterados. As seis vozes permanecem bloqueadas até prova independente de roteamento estéreo e ausência de duplicação. Nenhum teste físico foi solicitado.
- CI/JUnit ainda sem run_id ou artefato recuperável: o endpoint específico disponível filtra para eventos de PR; a consulta REST genérica para listar pushes foi recusada pelo conector privado. Os testes foram adicionados, mas não podem ser declarados executados/aprovados até obter o relatório.

## Atualização incremental — 2026-11-14 (audio.tape_compute_diskfree)

- Implementado `audio.tape_compute_diskfree(reserve_s)` com bytes livres reais da pasta privada Dust via `File.getUsableSpace()`.
- Cálculo alinhado a 48 kHz, estéreo, PCM16 (192.000 bytes/s), subtraindo a reserva solicitada e retornando segundos inteiros não negativos.
- Operação host 75 adicionada sem alterar IDs anteriores; teste de contrato cobre a ligação Lua/Java.
- CI continua sem relatório recuperável pelo conector; não declarar testes aprovados até ler o JUnit.

## Atualização incremental — 2026-11-14 (audio.tape state API)

- Implementados `audio.tape.get_state()`, `subscribe(key, fn)` e `unsubscribe(key)` com snapshot compartilhado e callbacks protegidos.
- Snapshot deriva do `TapeCore`: estados de reprodução/gravação (0 parado, 1 ativo, 2 pausado), posições/duração em segundos e loop.
- Eventos de transporte são publicados nas operações; posição é atualizada em cadência de 100 ms. Lua recebe callbacks na sua executor dedicada, nunca diretamente na thread de áudio.
- `_norns.tape_status` também recebe os seis campos upstream.
- Limitação: os estados e posições representam o transporte Java atual; gravação continua bloqueada durante ownership nativo Softcut e não foi ampliada a captura de mix nativo.
- Testes unitários adicionados para snapshots, pause/resume, loop e posição de gravação; execução CI ainda precisa ser confirmada por relatório JUnit.

## Atualização incremental — 2026-11-14 (API audio.monitor_mono / monitor_stereo)

- Contrato upstream: `audio.monitor_mono()` mistura as entradas para L/R; `audio.monitor_stereo()` encaminha cada entrada ao respectivo canal.
- O backend Android disponível captura apenas um canal ADC. `audio.monitor_mono()` é exposta e usa a mistura mono já implementada; `audio.monitor_stereo()` é exposta, mas retorna `false` e registra indisponibilidade em vez de simular estéreo.
- Adicionada operação host 73 sem renumerar operações existentes e teste de contrato para ambas as decisões.
- A captura estéreo e o monitor nativo scsynth/Oboe continuam pendentes; não solicitar teste físico nem declarar compatibilidade estéreo.

## Atualização incremental — 2026-11-14 (API audio.level_adc)

- Contrato upstream: audio.level_adc(level) define o ganho da entrada ADC, faixa 0..1, distinto do ganho de monitor audio.level_monitor.
- Implementado no caminho Java: ganho aplicado ao bloco capturado imediatamente após a captura ADC e antes da análise de onset, Softcut, monitoramento e métricas; afeta de forma consistente todos os consumidores da entrada.
- Operação Lua/host 72 adicionada sem alterar IDs anteriores. Valor inicial 1.0 preserva o comportamento anterior; valores inválidos são rejeitados e não há clipping silencioso fora da faixa.
- Caminho scsynth/Oboe nativo recusado explicitamente até existir controle de ganho upstream equivalente e validado no servidor nativo.
- Testes de contrato e testes unitários do ganho de entrada cobrem mute, unity, ganho intermediário, limites e buffers vazios.
- PolyPerc, Java fallback, roteamento Softcut e ownership Oboe/AudioTrack não são alterados. Seis vozes continuam bloqueadas até prova de roteamento estéreo e ausência de duplicação.

## Atualização incremental — 2026-11-14 (API audio.level_monitor)

- Contrato upstream conferido na referência oficial monome/norns: audio.level_monitor(level) controla o nível de monitoramento da entrada ADC, em 0..1.
- Implementado no backend Java: captura ADC mono existente é mixada igualmente nos canais L/R da saída AudioTrack, depois dos sends Softcut e antes do clamp/registro Tape; o bloco engine-only continua isolado, portanto o monitor não realimenta o envio engine->Softcut.
- Adicionada operação host 71 sem renumerar operações anteriores, validação de valores finitos e clamp 0..1, com nível inicial zero (monitor desligado).
- O caminho nativo scsynth/Oboe é recusado explicitamente; não se anuncia suporte de monitor nativo nem modo estéreo independente porque o ADC Android disponível é mono. monitor_mono/stereo permanece pendente.
- Testes unitários dedicados para mix mono->estéreo, ganho zero/unity/intermediário, entrada ausente, saída inválida e contrato Lua/host. PolyPerc, saída Java fallback e ownership Softcut permanecem preservados.
- Não executados APK, probe ARM64, integração Oboe nem teste físico; seis vozes Softcut continuam bloqueadas até prova independente de roteamento estéreo e ausência de duplicação.

## Atualização incremental — 2026-11-14 (API de análise de pitch)

- Contrato upstream conferido em `monome/norns/lua/core/audio.lua`: `audio.pitch_on()` habilita análise de pitch da entrada e `audio.pitch_off()` desabilita a análise para economizar CPU.
- Implementadas as duas funções no `audio` Lua mobile e na ponte `LuaHost`, com operações host novas (69/70, sem renumerar IDs existentes) e propagação até `AudioPollMetrics`.
- O estimador Java agora é opt-in: por padrão, não mantém histórico nem calcula autocorrelação; `pitch_on` começa uma janela limpa e `pitch_off` limpa a estimativa/histórico para que polls posteriores retornem `-1` até nova habilitação e coleta suficiente.
- Testes adicionados para default-off, ativação, detecção de 440 Hz e limpeza após desativação, além do contrato Lua/host. A análise continua mono porque o ADC Android disponível é mono; os nomes L/R permanecem aliases do mesmo sinal, não canais físicos independentes.
- Ainda não alegar compatibilidade completa da API de áudio: headphone gain, ADC global, monitor mix, reverb/compressor, restart, Tape state/subscription e demais rotas permanecem pendentes ou explicitamente indisponíveis até implementação com sinal real.
- Sem APK, probe ARM64, Oboe ou teste físico nesta etapa. PolyPerc, fallback Java e restrições de Softcut permanecem inalterados.

## Atualização incremental — 2026-11-13 (SPSC ADC: CI final)

- Branch validada: `migration/reconcile-jo1ngf-2026-11-12`; commit de código testado: `f87de5763874d99646701c48e5cb76c5759d1821`.
- Workflow JUnit `43524903294` concluído com sucesso; job `Android JVM unit tests` passou em todos os passos (contratos nativos, compilação/validação SynthDefs, fixtures upstream e testes JVM).
- Artefato integral `norns-mobile-junit-reports-374` (ID `14084439752`) inspecionado: **587 testes, 0 falhas, 0 ignorados**, duração 2,428 s. `AudioInputRingBufferTest` passou 6/6, incluindo concorrência produtor/consumidor e ordem de 50.000 amostras.
- O ring buffer SPSC mantém a propriedade exclusiva dos índices; overflow descarta amostras novas sem o produtor modificar o cursor do consumidor. O teste de stress usa workers daemon, encerramento limitado e tratamento de interrupção para não prender o processo Gradle em falha.
- `main` continua em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`. APK, probe ARM64, integração Oboe e testes físicos continuam deliberadamente adiados.

## Atualização incremental — 2026-11-13 (corrida SPSC da entrada de áudio)

- `AudioInputRingBuffer` separa o ring buffer mono do wrapper Android e mantém a regra SPSC: a thread de captura é a única escritora de `writeIndex`; a thread de áudio é a única escritora de `readIndex`.
- Corrigida a corrida de overflow anterior, em que o produtor avançava `readIndex` enquanto o consumidor também o atualizava. Agora o produtor só grava em slots livres e descarta as amostras novas excedentes (drop-newest) quando cheio; o consumidor nunca lê slots que o produtor possa sobrescrever.
- `AudioInputRingBufferTest` cobre conversão PCM16, wrap-around, overflow, underflow/zero-fill e lotes vazios. A validação é determinística e não exige microfone físico.
- Isso melhora a segurança do caminho ADC mono Java; não adiciona monitoramento acústico automático, entrada estéreo ou roteamento nativo.

## Atualização incremental — 2026-11-13 (validação dos ganhos de entrada ADC)

- `audio.level_adc_cut(level)` agora rejeita `nil`, NaN e infinitos, limita o ganho global ao intervalo suportado de 0–2 e propaga o mesmo valor validado ao Softcut Java e aos gravadores nativos existentes.
- `softcut.level_input_cut(input, cut, level)` aplica a mesma validação e faixa ao ganho por voz; entradas diferentes do ADC mono atualmente disponível continuam silenciosas, sem criar um falso segundo canal.
- Teste contratual novo em `AudioRoutingApiContractTest` cobre rejeição de valores não finitos, clamp e propagação consistente. Isso valida o contrato Lua/host, não a resposta elétrica ou acústica de uma entrada física.
- `main` deve permanecer em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; nenhuma compilação APK, probe ARM64 ou integração Oboe foi solicitada nesta etapa.

## Atualização incremental — 2026-11-13 (tokens de sessão USB/HID e callbacks Lua)

- Código validado: `88123bac68fdf02411dc5a022106a8b0e7f4391a`; workflow JUnit `43474031282`, artefato integral `norns-mobile-junit-reports-335` (ID `14061239031`): **550 testes, 0 falhas, 0 ignorados**, duração 2,543 s. Relatórios HTML de índice e das suítes USB/HID/Lua foram baixados e inspecionados.
- `LuaDeviceCallbackGate` adiciona tokens por sessão e separa callbacks normais, releases permitidos durante fechamento e callback terminal. Um token de transporte não pode ser reivindicado por outro device ID; uma sessão antiga não invalida a sessão substituta.
- `UsbCdcSerialBridge.Listener` agora transporta token único de sessão em connected/data/disconnected. O anúncio de connected é enfileirado antes do início da thread de leitura; o LuaHost verifica o token no momento de execução do callback. Disconnect antigo é descartado se já existir sessão substituta. O close explícito da API Lua invalida a fila antes de fechar o transporte.
- HID: o LuaHost associa add/event/gamepad/keyboard à sessão do InputManager; eventos Android tardios não criam sessões por conta própria. Eventos normais da sessão fechada são descartados, releases ainda podem drenar, e `hid_device_remove` só é emitido se a sessão antiga ainda não foi substituída. Ordem de release de teclas/botões/eixos permanece antes da remoção.
- Novos testes: `LuaDeviceCallbackGateTest` (10), `LuaHostDeviceCallbackContractTest` (5), `UsbPortCallbackGateTest` (5), além da expansão de `UsbCdcSerialBridgeTest` (30). Cobrem token USB atrasado, troca de ID, close explícito, callback HID tardio, release antes de remove, leitura concorrente e escrita em voo contra close.
- Relatórios intermediários: JUnit `43472447535` (artefato 330, 548 testes/1 falha) apontou apenas um localizador de comentário obsoleto no teste de ordem HID; JUnit `43473547534` (artefato 334, 550 testes/1 falha) apontou um assert de string com escape de newline incorreto. Ambos corrigidos; JUnit `43474031282` ficou verde em seguida.
- Os workflows APK `43474031113`, probe `43474031153` e Oboe `43474031178` tiveram os jobs principais explicitamente `skipped`; nenhuma compilação APK/probe/Oboe foi executada.
- `main` permanece em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`. Nenhuma validação de hardware físico foi realizada ou alegada.

### Próximas prioridades

1. Continuar a matriz de corridas USB CDC de escrita, detach e shutdown com callbacks tardios, incluindo múltiplas operações simultâneas e cancelamento do lado Lua.
2. Ampliar testes de substituição HID com eventos Android já capturados, especialmente dispositivos com descritores semelhantes e remoção/reconexão reentrante.
3. Completar a matriz de contratos Lua/host para keyboard, gamepad e serial após restart/stop da VM.
4. Manter as restrições de áudio: PolyPerc e fallback Java preservados; Softcut sem seis vozes até prova de roteamento estéreo e ausência de duplicação.

## Atualização incremental — 2026-11-13 (callbacks concorrentes USB)

- Commit de testes: `8bd662031810d6138defc515a9a94b956336b056`; adicionado `UsbPortCallbackGateTest` com três cenários determinísticos: serialização de callbacks concorrentes de leitura, barreira de close até drenar o callback ativo, rejeição de callback da porta antiga após substituição com o mesmo ID e recuperação do lock após exceção do listener.
- A nova suíte exercita diretamente o mesmo `UsbPortCallbackGate` usado pelo transporte CDC; não simula nem afirma acesso a hardware Android.
- A auditoria identificou também uma lacuna ainda aberta: `LuaHost` enfileira callbacks USB serial e HID no executor Lua sem token de sessão na fronteira do executor. O transporte USB impede novas leituras após close, mas callbacks já enfileirados precisam de proteção adicional contra execução depois de detach/reconnect com ID reutilizado. No HID, a correção deve preservar os eventos de liberação (key/button/axis zero) antes de `hid_device_remove`; não aplicar invalidação imediata que descarte esses releases.
- Workflow JUnit `43465673698` está em execução no SHA de código `b3794db349ce251652a20fc06896f5a9d681ec5d`. Contratos nativos, compilação/validação dos SynthDefs e fixtures upstream passaram; etapa `testDebugUnitTest` ainda em execução. Não contabilizar os quatro novos testes como aprovados até ler o relatório JUnit integral.
- `main` permanece preservada em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; APK, probe, Oboe e testes físicos permanecem fora do escopo.

# Roadmap global — Norns Mobile

> Documento de acompanhamento do objetivo de produto: executar no Android, localmente e sem depender do hardware Norns original, os principais scripts, APIs e recursos de áudio do ecossistema norns.
>
> Este roadmap é separado de `ROADMAP_NATIVE_AUDIO.md`: concluir o driver de áudio não significa concluir o produto mobile. Itens só são marcados como concluídos quando houver evidência de implementação e teste correspondente.

## Atualização incremental — 2026-11-13 (USB/HID/Lua e permissões)

- Branch de trabalho: `migration/reconcile-jo1ngf-2026-11-12`; HEAD de código validado: `173c5bfaceb48863c2cb2d86a15219dd9fe977a4`. `main` preservada em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`.
- JUnit final mais recente: workflow `43462267162`, concluído com sucesso no SHA exato acima. Relatório integral `norns-mobile-junit-reports-306`: **529 testes, 0 falhas, 0 ignorados**, duração 1,816 s. Relatórios HTML por classe inspecionados.
- Suítes críticas no relatório 306: `UsbCdcSerialBridgeTest` 29/29; `UsbLifecycleGateTest` 10/10; `UsbSerialLuaRuntimeTest` 6/6; `HidLuaRuntimeTest` 8/8; `HidButtonStateTest` 7/7.
- Verificações anteriores ao JUnit também passaram: contratos nativos/roteamento, compilação dos SynthDefs Softcut, validação dos SynthDefs embarcados e download dos fixtures upstream fixados.
- `UsbPermissionRequestGate<T>` correlaciona cada broadcast Android ao token exclusivo enviado no extra do `PendingIntent` e no requestCode. A resposta só é consumida se ID e token coincidirem; grants atrasados, cancelamentos antigos e callbacks duplicados não afetam a solicitação atual.
- `open()` não cria prompts duplicados quando já existe solicitação pendente para o mesmo ID. Se a permissão já estiver concedida, invalida qualquer token antigo antes de abrir diretamente. O teste de contrato verifica a ordem e a idempotência.
- Close cancela a solicitação pendente; falha ao solicitar permissão cancela apenas o token daquela tentativa; shutdown limpa os tokens. Testes cobrem reconexão com mesmo ID, múltiplos dispositivos, detach/shutdown e concorrência de broadcasts duplicados.
- `UsbPortCloseGate` bloqueia reabertura do mesmo ID enquanto a conexão anterior libera interfaces e faz `closeAll()` aguardar fechamentos já iniciados. Teste determinístico cobre duas portas fechando em paralelo.
- Contrato Lua serial ampliado: remoção do handler pode executar `serial.refresh()` reentrante, criar nova solicitação para o mesmo ID e completar reconexão sem a limpeza da sessão antiga apagar a substituta. Teste novo passou em `UsbSerialLuaRuntimeTest`.
- HID Lua permanece coberto por cenários de remoção/replug com mesmo ID, callbacks reentrantes, múltiplos controles, eventos obsoletos e vports estáveis; 8/8 testes aprovados. HID button state: 7/7.
- Histórico de correções JUnit: relatórios 294 (523/1), 301 (527/5), 302 (527/1), 303 (527/0), 305 (528/0) e 306 (529/0). Todas as falhas intermediárias foram lidas, corrigidas e revalidadas.
- Workflows de APK (`43462268380`), probe ARM64 (`43462267241`) e integração Oboe (`43462267066`) ficaram `skipped`. Nenhum APK, probe ou Oboe foi executado; nenhum teste físico foi solicitado.
- Próximas prioridades: ampliar callbacks HID/USB de leitura e escrita em corrida com close, testar eventos HID Android atrasados depois de substituição de dispositivo e completar a matriz de contratos Lua/host ainda parciais. JUnit verde não equivale a validação de hardware real.
- PolyPerc/fallback Java e as restrições de segurança do Softcut permanecem preservados; nenhuma habilitação de seis vozes foi feita.

## Atualização incremental — 2026-11-10

- Branch de implementação: `feature/0.4.5-android-audio-driver`; `main` continua preservada em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`.
- As três verificações da correção FM no commit `988d18b` passaram: APK, integração scsynth/Oboe e probe Android. Isso valida a correção FM, não o conjunto completo de recursos.
- Prioridade 2: FM permanece disponível no fallback Java; Pluck/Karplus-Strong foi acrescentado com controles `hz`, `amp`, `decay`, `damping` e `pan`. Cobertura unitária de som estéreo/pan adicionada; execução CI do novo commit ainda pendente.
- Prioridade 3: MIDI Android mantém portas USB/BLE/virtuais, mensagens MIDI comuns e SysEx; a ponte agora escuta todas as portas de saída de cada dispositivo Android (em vez de somente a porta 0), encaminhando os eventos ao parser Norns. OSC mantém mensagens tipadas, bundles e timetags; o callback é chamado fora do lock do transporte. Testes de rede/dispositivos reais continuam pendentes.
- Prioridade 4: USB CDC-ACM recebeu proteção para não fechar a conexão durante uma escrita ativa e tratamento de falha na leitura; Crow usa esse transporte. FTDI/CP210x/CH340 continuam fora do suporte CDC padrão.
- Prioridade 5: handoff Softcut ficou mais conservador: a saída Java só é liberada depois de confirmar o mute nativo; se a biblioteca JNI não estiver carregada, não há saída nativa possível e o fallback Java permanece liberado. Com JNI presente, falha de confirmação mantém a saída bloqueada para evitar áudio duplicado. As seis vozes continuam desabilitadas até prova estéreo independente e ausência de duplicação.
- Implementações desta rodada consolidadas no commit seguinte: FM e Pluck no fallback Java; recepção MIDI multiporta; callback OSC sem lock; proteção de escrita/leitura USB CDC; handoff Softcut conservador e testes de falha. A conclusão técnica depende agora dos testes e workflows de CI; a validação física no SM-G973F fica para depois.
- Observação operacional: o push do commit anterior acionou automaticamente os workflows configurados para push antes do fechamento desta rodada. Não há ação de cancelamento disponível nesta conexão; não farei outro push/build até consolidar e concluir as implementações restantes.

## Atualização incremental — 2026-11-08

- Build completa de referência: Norns Mobile 0.4.5 #495, commit `f5a8c6f2b952747542c16926c7edf6a99953c925`; APK SHA-256 `1649054765ca94cf1f239929fd59321ac69acc92157a3f80554bd6b0ebded776`. A probe #322 não substitui essa referência de APK completo.
- HEAD de código verificado: `21d6cb6fd2a66c4f8cd69dfe79ee9431439eac0a`, 137 commits à frente da referência, sem commits atrás. Commits documentais posteriores não alteram o código.
- Correção incremental: `grid.tilt_enable(sensor, enabled)` agora usa `monome_flag` também no broadcast para todos os dispositivos; assim, `0` desativa corretamente o sensor em Lua, onde zero é truthy.
- Teste de contrato adicionado para a semântica numérica de `grid.tilt_enable`.
- CI do novo HEAD ainda não foi confirmado; não declarar JUnit, parser Lua, APK ou integração ARM64 aprovados até existir execução correspondente.
- `main` permanece em `82fcedd60f76707ada4d25185837894a2e6664cd`; nenhum recurso de áudio experimental foi habilitado. `native_gate_open=false`, PolyPerc e fallback Java permanecem preservados.
- Próxima prioridade: ampliar testes de contrato executáveis para APIs Lua/host e concluir inventário de stubs/semânticas parciais; depois avançar em engines, MIDI/OSC e ciclo de vida serial. Grid/Arc serialosc segue experimental até validação de rede e hardware.
- Monitoramento automático diário não pôde ser agendado porque a conta atingiu o limite atual de três automações ativas; verificações nesta conversa continuam sob demanda.

## Auditoria regressiva pós-build #495 — 2026-11-08

- [x] Confirmar build de referência #495 em `f5a8c6f2b952747542c16926c7edf6a99953c925`.
- [x] Comparar exatamente os 132 commits até `07b22b1be93cf95fa9f3d39b01c64d9d63dc6a57`.
- [x] Identificar cinco commits adicionais até o HEAD de código `21d6cb6fd2a66c4f8cd69dfe79ee9431439eac0a`; esses commits serão auditados separadamente, sem misturá-los ao lote original.
- [x] Confirmar `main` preservada em `82fcedd60f76707ada4d25185837894a2e6664cd`.
- [x] Corrigir a referência desatualizada do roadmap de áudio; commit documental `9f26a6432ac2c6b743bf1c8ea569e99a24ac5d37`.
- [ ] Auditar por grupos de arquivos os 132 commits: runtime Lua/boot, LuaHost/Activity, áudio/SC/JNI, Softcut/buffers, APIs hardware, testes e workflows; registrar regressões com commit introdutor e evidência.
- [ ] Comparar as APIs/arquivos críticos com a build #495 e recuperar somente trechos funcionais comprovados, sem rollback amplo.
- [ ] Executar JUnit, parser Lua, validação SynthDef, probe ARM64 e APK no SHA final da recuperação.
- [ ] Auditar separadamente os cinco commits posteriores (Grid/Arc serialosc e correções).
- [ ] Manter `native_gate_open=false`, seis vozes Softcut desabilitadas, PolyPerc e fallback Java preservados até provas independentes de roteamento e handoff.
- [ ] Não solicitar teste físico no SM-G973F antes de concluir as validações automatizadas.

## Estado consolidado atual — 2026-11-08 (prevalece sobre notas históricas abaixo)

- Branch ativa: `feature/0.4.5-android-audio-driver`; HEAD de código auditado: `21d6cb6fd2a66c4f8cd69dfe79ee9431439eac0a`. O HEAD documental foi atualizado após a auditoria; `main` permanece preservada.
- `main` preservada em `82fcedd60f76707ada4d25185837894a2e6664cd`; nenhuma alteração foi feita nela.
- A sequência antiga de CI (APK `42476059258`, probe `42476059280`, integração `42476059246`) pertence ao antigo HEAD `3fdde6b` e falhou antes de executar steps; isso não valida nem diagnostica o commit `e61e96d` ou esta implementação. CI para o novo SHA permanece pendente.
- Screen: desenho, transformações, 14 blend modes, PNG/imagens, regiões, `draw_to`, `peek/poke`, liberação e limite agregado de 32 MiB implementados; auditoria runtime/memória pendente.
- Keyboard: eventos físicos Android, layout ativo via KeyCharacterMap (Shift/AltGr), callbacks Norns e liberação ao perder foco/pausar/trocar aba. Encaminhamento apenas na aba NORNS; auditoria de layouts/teclas mortas pendente.
- Gamepad: botões, D-pad, sticks, triggers, callbacks Norns e hotplug/disconnect básico via InputManager. Perfis GUID, combinação de vários controles e validação USB/Bluetooth física pendentes.
- HID: backend de entrada genérico Android para eventos EV_KEY/EV_ABS/EV_REL de teclado, gamepad e mouse, vports, add/remove e callbacks por dispositivo/porta. `hid.send` e relatórios HID de saída continuam indisponíveis; não equivale ao acesso bruto SDL/evdev do norns.
- Engines: PolyPerc, TestSine, None e primeira implementação nativa 32Harmony com SynthDef Android pré-compilado (32 osciladores, estéreo e comandos de parâmetros). 32Harmony sem CI/validação sonora neste HEAD; engines SC arbitrárias dependem de sclang/SCClassLibrary/Quarks.
- MIDI: ponte Android, portas, mensagens, clock/transporte, hotplug e SysEx parcial; testes unitários prévios passaram em SHA anterior, validação física fica para auditoria final.
- OSC: mensagens tipadas, bundles, timetags/agendamento e callbacks implementados; validação em rede real pendente.
- Poll: `cpu_avg`/`cpu_peak` consultam snapshots atômicos do tempo de execução do callback Oboe/scsynth quando o servidor está ativo; `amp_out_l/r` consultam picos PCM finais pós-gate. `amp_in_l/r` e `pitch_in_l/r` ainda usam captura/estimativa mono Java; entrada estéreo e métricas nativas de entrada pendentes.
- USB Serial: backend experimental USB Host CDC-ACM 8-N-1 implementado (permissão, hotplug, handlers Norns, callbacks add/remove/event e payload binário); FTDI/CP210x/CH340, parity/flow-control e teste físico pendentes.
- Crow: integração experimental para firmware oficial USB CDC-ACM 0483:5740 (manufacturer `monome & whimsical raps`, product `crow: telephone line`, interface 00), com comandos, parser `^^` de eventos, proxies `input/output/ii`, `loadscript` e `public` básico. Teste físico e compatibilidade de todos os eventos/public continuam pendentes.
- Grid/Arc: backend experimental serialosc OSC/UDP implementado nesta rodada: descoberta, associação de dispositivos e portas virtuais, LEDs, rotação/tilt Grid, LEDs/rings Arc e callbacks. Requer serialosc na mesma LAN; reconnect e hardware físico ainda pendentes.
- Áudio: PolyPerc e fallback AudioTrack Java preservados. Buses engine/tape e handoff exclusivo Oboe/AudioTrack ainda não aprovados.
- Softcut: `native_gate_open=false`; playback nativo e seis vozes bloqueados até prova independente BufWr CH1/CH2, roteamento PCM estéreo e ausência de duplicação.
- Teste geral no SM-G973F somente após concluir implementações e validações automatizadas restantes.

## Linha de base histórica (2026-11-07)

- Branch de trabalho: `feature/0.4.5-android-audio-driver`.
- Commit auditado: `c29d3955c80b8f3087f1dfb07400b1ed6779ca08`.
- A árvore contém runtime LuaJ, `boot.lua`, bibliotecas Lua, armazenamento Dust, editor/biblioteca de scripts, visualização da tela Norns, painel PARAMS e painel LOG.
- Scripts de exemplo versionados: `hello_norns`, `midi_test_seq` e `polyperc_seq`.
- Código Android inclui pontes Lua/host, áudio Java, MIDI, OSC, Softcut Java, ponte SuperCollider/JNI e instrumentação de fase/gravação.
- A árvore contém testes unitários para sintaxe Lua, Softcut, ownership de saída, supervisor, buffers, ponte SC/OSC e WAV.
- A documentação de áudio registra testes de CI e APK 0.4.5, mas a validação física no SM-G973F permanece pendente.
- A documentação geral `README.md` ainda contém trechos de estado histórico 0.4.1; precisa ser reconciliada com o estado real antes de servir como declaração pública de compatibilidade.
- `MainActivity.java` na árvore auditada declara `VERSION = "0.4.4"`; verificar alinhamento com o versionamento/artefato 0.4.5 antes do próximo release. Não inferir a versão instalada apenas pelo nome do artefato.

## Definição de 100% mobile

Considerar o núcleo mobile concluído quando:
1. O aplicativo instala, inicia, mantém estado e executa scripts localmente em Android suportado.
2. As APIs norns incluídas no escopo de compatibilidade têm comportamento documentado e testes de contrato.
3. Scripts de referência selecionados executam sem adaptações específicas para Android, ou suas diferenças estão explicitamente documentadas.
4. Áudio, Softcut, MIDI e OSC funcionam no dispositivo real, com ciclo de vida e recuperação testados.
5. Editor, biblioteca, tela, parâmetros, logs, importação/exportação e persistência são utilizáveis em tela touch.
6. Há testes de regressão, build reproduzível e matriz de compatibilidade publicada.

Integrações que exigem hardware externo (por exemplo, Crow, Grid e Arc) são extensões opcionais; não bloqueiam o núcleo autônomo mobile, mas não podem ser anunciadas como suportadas sem testes com o hardware correspondente.

## Fase 1 — Confiabilidade do núcleo Android

- [x] Runtime LuaJ e host dedicado para execução de scripts presentes no código.
- [x] Armazenamento Dust com áreas code/data/audio presente no código.
- [x] Interface básica para scripts, editor, tela Norns, parâmetros e logs presente no código.
- [ ] Testar ciclo completo: iniciar/parar/trocar scripts, exceções, timers e retorno após background/foreground.
- [ ] Testar importação/exportação de pacotes de scripts e persistência de dados em instalação/atualização.
- [ ] Verificar compatibilidade da versão declarada no app, Gradle e artefato de release.
- [x] Atualizar README para refletir a branch 0.4.5 e os recursos reais, separando implementação de validação física.

## Fase 2 — Compatibilidade das APIs norns/Lua

- [ ] Inventariar cada API exposta por `boot.lua` e pelo host Java, classificando: real, parcial, stub/no-op, ausente.
- [ ] Criar testes de contrato por API e scripts de referência para cada grupo.
- [ ] Priorizar `screen`, `params`, `clock`, `metro`, `音`/engines, `midi`, `osc`, `softcut`, filesystem e callbacks.
- [ ] Comparar assinaturas, argumentos, defaults, unidades, temporização e semântica com a API norns de referência.
- [ ] Documentar incompatibilidades inevitáveis do LuaJ/Lua 5.2 e fornecer alternativas mobile quando necessário.
- [ ] Validar scripts reais de terceiros selecionados; não declarar compatibilidade geral com base apenas nos exemplos internos.

## Fase 3 — Áudio e engines

- [ ] Validar fisicamente os dois gravadores BufWr no SM-G973F.
- [ ] Completar e validar seis vozes Softcut: playback, loop, rate/reverse, rec, overdub, buffers, filtros, pan e roteamento.
- [ ] Validar latência, underruns, consumo, aquecimento e ciclo de vida do backend Android.
- [ ] Concluir handoff controlado entre scsynth/Oboe e AudioTrack Java somente após os critérios de aceite e testes acústicos.
- [ ] Preservar PolyPerc e fallback Java durante todas as transições.
- [ ] Inventariar engines disponíveis e compatibilidade de SynthDefs/UGens; separar engines nativas já integradas das dependentes de `sclang`.
- [ ] Decidir e documentar o escopo de `sclang`, SCClassLibrary e Quarks; a presença de `libscsynth` não equivale a um ambiente SuperCollider completo.

## Fase 4 — MIDI e OSC

- [x] Infraestrutura Android MIDI e ponte OSC presentes no código.
- [ ] Validar MIDI USB e BLE-MIDI em aparelhos/periféricos reais.
- [ ] Testar Note On/Off, CC, Program Change, Pitch Bend, clock e desconexão/reconexão.
- [ ] Validar OSC UDP em rede local, endereços, portas, payloads, lifecycle e erros.
- [ ] Validar scripts de referência que usam MIDI/OSC sem adaptações.

## Fase 5 — Experiência mobile

- [x] Abas de scripts, editor, Norns, parâmetros e logs presentes no código.
- [ ] Avaliar ergonomia touch em telas pequenas e grandes, orientação e densidade.
- [ ] Melhorar navegação, estados vazios, feedback de erro, acessibilidade e escalabilidade visual.
- [ ] Criar gerenciamento confiável de pacotes/scripts, favoritos, busca e importação.
- [x] Implementar encaminhamento de teclado físico para a aba NORNS e preservar entrada de texto no editor; [ ] auditar edição, autosave e recuperação de alterações.
- [ ] Testar áudio em segundo plano, bloqueio de tela, chamadas/interrupções e permissões.

## Fase 6 — Qualidade, distribuição e compatibilidade

- [ ] Definir matriz de Android/ABI/dispositivos suportados.
- [ ] Automatizar testes Lua/API, unidade, integração e regressão do app.
- [ ] Executar bateria de scripts de referência em pelo menos dois dispositivos físicos.
- [ ] Produzir build release reproduzível, assinatura e notas de versão.
- [ ] Publicar tabela de compatibilidade por API, engine e periférico.
- [ ] Fazer teste prolongado de estabilidade e recuperação antes de declarar 1.0.

## Regras de progresso

- `Concluído`: implementação e evidência de teste disponíveis.
- `Parcial`: código presente, mas cobertura, semântica ou validação real incompleta.
- `Pendente`: não implementado ou sem evidência suficiente.
- Build/CI verde não substitui teste físico de áudio, MIDI, OSC ou periféricos.
- Percentuais globais só devem ser recalculados após inventário dos itens e pesos; estimativas anteriores de 55–60% são provisórias, não métricas auditadas.

## Restrições ativas do trabalho de áudio

- Manter `native_gate_open=false`.
- Não iniciar handoff Oboe/AudioTrack antes da validação física de ambos os BufWr.
- Não habilitar playback Softcut nativo prematuramente.
- Preservar PolyPerc audível e fallback Java.
- Não alterar `main`; trabalhar somente na branch indicada.


## Auditoria inicial histórica de APIs e interface — 2026-11-07

Evidências lidas diretamente no código da branch:

- `boot.lua` contém 149 declarações de função Lua e cria os módulos globais `norns`, `metro`, `clock`, `sc_api`, `midi`, `grid`, `arc`, `hid`, `crow`, `osc`, `softcut` e `audio`. A contagem de funções não representa cobertura de API.
- `LuaHost.java` declara 44 operações host e 38 operações nativas de desenho da tela. O host já conecta logging, filesystem Dust, engines, MIDI, OSC, Softcut e chamadas/diagnósticos SuperCollider.
- A interface Android possui abas SCRIPTS, EDITOR, NORNS, PARAMS e LOG. `LogView` já permite copiar e limpar os registros; `ParamsView` gera controles a partir dos parâmetros recebidos.
- A API `crow` é explicitamente um stub: init/reset/clear/send/connect/add/remove são no-op e input/output/ii/public retornam objetos nulos. Grid, Arc e HID têm estrutura de virtual ports, mas descoberta/remoção/update de dispositivos está como no-op e ainda não há evidência de transporte físico.
- O comentário de `boot.lua` identifica uma seção de stubs ainda não implementados; a auditoria funcional de cada membro dessa seção permanece pendente.
- `MainActivity.java` e `app/build.gradle.kts` declaram versão 0.4.4, enquanto documentos recentes e o artefato citado no roadmap de áudio usam o rótulo 0.4.5. Isso é uma inconsistência de versionamento a resolver; não se presume qual versão está instalada no aparelho.
- O código declara suporte de armazenamento Dust em diretórios privados `code/data/audio`; isso não prova, por si só, compatibilidade completa com formatos/pacotes e permissões do Norns original.

### Próxima prioridade técnica global

1. Fazer inventário completo da seção de stubs de `boot.lua` e classificar cada API como funcional, parcial, no-op ou ausente.
2. Criar uma matriz de compatibilidade por domínio (Lua/runtime, tela, params, clock/metro, engine, softcut, MIDI, OSC, arquivos, grid/arc/HID/crow).
3. Corrigir a divergência 0.4.4/0.4.5 em uma alteração isolada, depois de confirmar qual versionamento corresponde ao APK publicado.
4. Não implementar Crow/Grid/Arc como se fossem periféricos virtuais equivalentes ao hardware real; manter essas integrações explicitamente pendentes até haver transporte e teste físico.

## Auditoria detalhada histórica das APIs Norns — 2026-11-07

Base desta auditoria: árvore da branch `feature/0.4.5-android-audio-driver`, commit `7bbb42a41c86369a057ae432bb4775c01dc51bb1`. Arquivos inspecionados: `app/src/main/assets/lua/boot.lua` (2.490 linhas) e `app/src/main/java/org/nornsmobile/app/LuaHost.java` (1.091 linhas), além das bibliotecas Lua e da documentação já inventariadas. A classificação abaixo descreve o código encontrado; não equivale a certificação de compatibilidade integral com o norns original.

### Legenda

- **Funcional**: há implementação conectada a um backend real no escopo descrito.
- **Parcial**: há implementação, mas com limitações conhecidas, cobertura incompleta ou validação física pendente.
- **No-op**: a chamada existe, mas não executa a operação solicitada.
- **Ausente**: não foi encontrada implementação/ponte para a capacidade indicada no escopo auditado.

### Matriz por domínio

| Domínio/API | Classificação | Evidência no código / limite conhecido |
|---|---|---|
| Runtime LuaJ, `require`, `include`, `dofile`, `loadfile` | Parcial | `boot.lua` implementa carregamento próprio de assets/Dust; ambiente LuaJ/Lua 5.2 não é o runtime Linux do norns e há diferenças de sandbox. |
| `io`, `os`, filesystem Dust | Parcial | `io.open/lines/write`, leitura/escrita, mkdir/list/remove/rename e caminhos Dust existem; `io.read`, `io.popen`, `os.execute`, `getenv` e stdin não oferecem equivalência de sistema. Escrita é bufferizada e não reproduz toda a semântica POSIX. |
| `norns.state`, `norns.try`, logging | Funcional | Estado do script, diretórios e captura de exceções/log estão implementados no boot/host. |
| `norns.shutdown`, `norns.reboot`, `norns.enc`, `norns.encoders.set_accel/set_sens` | No-op | Todos apontam diretamente para `noop` em `boot.lua` (linhas aproximadas 88–93). Não há hardware encoder equivalente implementado. |
| `metro` | Parcial | Agendador ordenado por deadline, start/stop/free/free_all e callbacks implementados em Lua; depende de `host.wake_at`/tick Java e ainda carece de testes de contrato de precisão, reentrância, cancelamento e lifecycle. |
| `clock` | Parcial | Coroutines, sleep/sync, tempo/beat, tempo interno e cancelamento implementados. Não há prova de sincronização externa MIDI clock/Link nem equivalência completa de transportes e quantização do norns. |
| `screen` desenho 128×64 | Parcial | LuaHost conecta clear/update/level/AA, primitivas, texto, extents, fontes, transformações, save/restore, peek/poke e blend mode set. No switch Java, `blend_mode` possui caso dedicado e chama `ScreenSurface.setBlendMode` (modos PorterDuff); `export_screenshot` e `display_png` caem no default sem implementação. Metadados de fontes são quatro famílias Android, não catálogo original. |
| `params` / `paramset` | Parcial | Biblioteca paramset, tipos e serialização UI, callbacks norm/delta/trigger e publicação em ParamsView existem. Necessário validar todos os tipos, opções, ações, grupos, persistência, encoders e semântica de bang/recall contra scripts reais. |
| `engine` | Parcial | Há caminho Java e SuperCollider para PolyPerc/TestSine, fila de comandos durante load e fallback Java. Engines arbitrárias/Quarks e carregamento de SynthDefs não significam suporte automático a SCClassLibrary/sclang. |
| `softcut` | Parcial | API Lua de seis vozes e backend Java amplo presentes; buffers, filtros, roteamento, eventos e render têm ponte host. Extensão SC está em estado silencioso/muted e gate fechado; gravação BufWr nos dois buffers ainda depende de validação física. Não marcar playback nativo como pronto. |
| `audio` | Parcial | Objeto global encontrado expõe `level_adc_cut`; não representa a API completa `audio` do norns. |
| `midi` | Parcial | Android MIDI bridge, refresh/connect/send/close, portas virtuais, conversão de mensagens e dispatch de eventos existem. `midi.add/remove` são funções vazias; hotplug e semântica de direção/portas exigem testes em USB e BLE-MIDI reais. |
| `osc` | Parcial | OSC UDP send/listen/close e callback com origem implementados via OscBridge. Validar payloads tipados, endereçamento, ciclo de vida e compatibilidade com scripts reais. |
| `grid` | No-op (hardware) | Quatro virtual ports e roteadores de callback existem, mas LEDs, refresh, rotação, intensidade, tilt e descoberta/remoção/update de dispositivos não operam hardware; métodos de saída são funções vazias. |
| `arc` | No-op (hardware) | Quatro virtual ports e dispatch delta/key existem; LED/all/refresh/segment/intensity, discovery/removal/update não têm transporte físico. |
| `hid` | No-op (hardware) | Virtual ports e dispatch estrutural existem; `types`, `codes` vazios e `send`, remove/update são no-op; nenhum backend de dispositivos HID está ligado. |
| `crow` / `ii` / `public` | No-op | init/reset/clear/send/connect/add/remove são no-op; input/output/ii/public retornam objeto nulo que apenas avisa e ignora operações. |
| `poll`, `keyboard` | No-op / placeholder | São objetos nulos criados por `null_object`; acessos retornam o próprio objeto e registram aviso, sem integração de entrada. |
| Entrada `key`/`enc` do script | Parcial | `_norns.key` e `_norns.enc` despacham callbacks se fornecidos; o carregamento reinicializa callbacks para noop e não foi encontrada fonte de eventos Android touch/encoder conectada a esses callbacks na auditoria atual. |
| `_norns.cleanup/load_script` | Parcial | Ciclo de cleanup/reset, carregamento, init/redraw, engine e params está implementado; falta teste abrangente de troca de script, falhas durante init e background/foreground. |
| `sc` / OSC SuperCollider auxiliar | Parcial | Helpers de nodes, buffers, SynthDef, send, sync e reply estão presentes; a cobertura é extensão Android e não substitui o ambiente sclang completo. |

### Inventário explícito da seção de stubs de boot.lua

- **No-op explícitos em `norns`**: `enc.accel`, `enc.sens`, `encoders.set_accel`, `encoders.set_sens`, `shutdown`, `reboot`.
- **MIDI**: `midi.add` e `midi.remove` vazios; o restante do caminho principal de portas/eventos possui integração Android, classificada como parcial até teste físico e hotplug.
- **Grid**: métodos de saída de cada virtual port (`led`, `all`, `refresh`, `rotation`, `intensity`, `tilt_enable`) vazios; `remove` e `update_devices` no-op.
- **Arc**: `led`, `all`, `refresh`, `segment`, `intensity` vazios; `remove` e `update_devices` no-op.
- **HID**: sem tipos/códigos, `send`, `remove`, `update_devices` no-op; apenas roteamento estrutural de callbacks.
- **Crow**: métodos públicos principais no-op e objetos nulos para input/output/ii/public.
- **Placeholders adicionais**: `poll` e `keyboard` são objetos nulos. `softcut.event()` é vazio; eventos de fase/posição/render têm caminhos separados e implementados, portanto o domínio Softcut permanece parcial, não totalmente no-op.
- **Limitações de tela identificadas no host**: `export_screenshot` e `display_png` não possuem implementação no switch. Correção desta auditoria: `blend_mode` tem o caso 32 dedicado e chama `ScreenSurface.setBlendMode`; a implementação aceita over/normal/source_over, add/plus, multiply, screen, darken, lighten e replace/src.

### Primeira implementação prioritária proposta

**Criar uma camada de entrada mobile para os callbacks norns `key(n,z)` e `enc(n,d)`, com testes de contrato e integração touch.**

Justificativa:
1. Hoje scripts podem definir `key`/`enc`, mas o boot só os despacha quando algum chamador invoca `_norns.key/_norns.enc`; a auditoria não encontrou uma origem de eventos Android conectada a esse caminho.
2. A ausência de controles físicos equivalentes limita navegação e controle de muitos scripts, mesmo quando engine, params e áudio estão disponíveis.
3. Uma camada de entrada touch configurável é uma extensão Android coerente: zonas/gestos na UI Norns devem emitir eventos Norns padronizados, sem simular hardware Grid/Arc/Crow.
4. Deve incluir mapeamento documentado, debounce/gestos, cancelamento ao trocar script, acessibilidade e testes para pressionar/soltar key, encoder delta, rotação/escala de tela e ausência de eventos após cleanup.

**Critérios de aceite da primeira implementação:**
- evento touch produz exatamente um par key down/up conforme contrato, sem stuck key após interrupção;
- gestos de encoder produzem deltas inteiros previsíveis e configuráveis;
- callbacks são executados na thread Lua serializada pelo host, sem chamada direta pela thread UI;
- cleanup/troca de script remove listeners e não entrega eventos ao script anterior;
- testes unitários e instrumentados cobrem lifecycle e rotação;
- nenhuma alteração no ownership/gate de áudio, no PolyPerc, no fallback Java ou na branch `main`.

Esta é uma proposta de prioridade, não uma implementação concluída. A próxima tarefa de código deve começar com um desenho curto do contrato de gestos e testes, depois ligar a UI touch ao dispatcher já existente em `LuaHost`.

### Atualização de estado após auditoria

- [x] Inspecionar seção de stubs de `boot.lua`.
- [x] Cruzar pontos principais com a ponte `LuaHost.java`.
- [x] Classificar domínios e placeholders com evidências.
- [x] Registrar a primeira prioridade técnica proposta.
- [ ] Implementar entrada mobile `key/enc` e testes de contrato.
- [ ] Continuar validação física Softcut separadamente; as restrições da seção de áudio permanecem inalteradas.

## Implementação da entrada mobile `key(n,z)` / `enc(n,d)` — 2026-11-07

Branch de trabalho: `feature/0.4.5-android-audio-driver`. Alterações feitas exclusivamente nesta branch; `main` não foi modificada.

### Diagnóstico pré-implementação

- `NornsView.java` já apresentava três teclas K1–K3 e três encoders E1–E3, mas chamava os callbacks diretamente e não mantinha estado centralizado das teclas pressionadas.
- `MainActivity.java` encaminhava esses callbacks para `LuaHost.key/enc`.
- `LuaHost.java` já implementava `key/enc` por `post(Runnable)` em um `ScheduledThreadPoolExecutor` de thread única chamado `norns-lua`. Portanto, não era necessário criar uma segunda fila Lua nem alterar a propriedade da VM; o trabalho foi concentrado na normalização e ciclo de vida dos eventos touch.
- Antes da alteração não havia diretório `app/src/androidTest` nem configuração explícita de runner de instrumentação.

### Implementado

- [x] Nova classe `NornsInputDispatcher`: valida índices 1–3, normaliza key down/up para 1/0, suprime eventos duplicados, encaminha deltas inteiros não nulos e mantém estado das teclas ativas.
- [x] `releaseAll()` envia key-up apenas para teclas efetivamente pressionadas, com comportamento idempotente.
- [x] `NornsView` usa o dispatcher para todos os eventos de K1–K3 e E1–E3.
- [x] Teclas liberam estado em `ACTION_UP`, `ACTION_CANCEL` e `ACTION_POINTER_UP` do ponteiro proprietário.
- [x] A view libera todas as teclas em `onDetachedFromWindow()`.
- [x] `MainActivity` libera teclas em `onPause`, `onDestroy`, no botão PARAR e antes de iniciar/substituir scripts, inclusive o caminho sem permissão de microfone.
- [x] A chamada final continua sendo `LuaHost.key/enc`, cuja implementação enfileira o callback Lua na thread serial `norns-lua`. Não houve chamada direta à VM pela thread de UI.
- [x] Testes unitários JUnit adicionados para sequência down/up, duplicação, releaseAll, deltas positivos/negativos/zero e índices inválidos.
- [x] Teste instrumentado Android adicionado para gerar eventos touch reais nos controles da `NornsView`, verificar key down/cancel/up e delta do encoder.
- [x] Runner `android.test.InstrumentationTestRunner` declarado em `app/build.gradle.kts`.

### Evidências de commits

| Commit | Alteração |
|---|---|
| `1a6b3af8f6197c6526a98d64af4758343b365ac4` | Dispatcher de entrada |
| `e0a5fd5cbd21315bd0d139412411c5ae6908c56e` | Testes unitários |
| `f20746440169ad7cc906c6b868699dd31b8b5aed` | Integração em NornsView |
| `52a87c836201ca6fecb60133c23ec7377dcf0d41` | Liberação no lifecycle |
| `b7f6e7a2de618b15f4cffc6012c8c9a9250d9777` | Teste instrumentado |
| `cd57208feea1b344372b0ea70bdbc4ba074600d9` | Configuração do runner |
| `5daa309e94b07b7deb052a2dad44c3c7127f6a3a` | Correção de liberação antes de iniciar scripts |
| `3882b10baed2aef8284777834e245d8ece5888a4` | Gesto do teste independente de densidade |

### Validação e pendências

- [x] Revisão estática dos arquivos alterados e confirmação de que `LuaHost.key/enc` continua usando a fila serial existente.
- [ ] Executar `testDebugUnitTest` no ambiente Gradle.
- [ ] Executar `connectedDebugAndroidTest` em dispositivo/emulador Android.
- [ ] Validar manualmente gestos multi-touch e troca de script no dispositivo físico.
- [ ] Confirmar comportamento de encoder com diferentes densidades e velocidades de arraste.

**Estado:** implementação de código e testes adicionados; execução de build/testes ainda não comprovada nesta sessão. Não marcar como validado até os resultados do Gradle/Android instrumentado serem anexados.

### Restrições de áudio preservadas

Nenhuma alteração foi feita em ownership de saída, PolyPerc, fallback Java, Softcut, buffers BufWr ou handoff Oboe/AudioTrack. `native_gate_open=false` permanece a condição exigida; playback Softcut nativo não foi habilitado.


## Revisão da entrada touch e evidências de CI — 2026-11-07

### Revisão de código após o commit 402447e

- [x] Confirmado que `ScreenSurface` possui construtor sem argumentos; o teste instrumentado podia instanciá-lo.
- [x] Confirmado o layout da `NornsView`: status, tela, ações, linha de encoders e linha de teclas. A implementação anterior dependia do índice fixo `getChildAt(4)` para liberar as teclas.
- [x] Refatorada `NornsView` para manter referência direta à `keyRow`, removendo a dependência do índice estrutural no ciclo de liberação.
- [x] Simplificado o teste instrumentado: cria a view com o contexto-alvo, sem iniciar uma `MainActivity` completa e sem inicializar host/áudio desnecessariamente.
- [x] Eventos `MotionEvent` do teste agora são reciclados em bloco `finally`.
- [x] Removido import não utilizado do teste instrumentado.
- [x] Mantido o runner Android legado já declarado no módulo; a disponibilidade/execução do teste instrumentado ainda depende de um dispositivo/emulador e não foi presumida.

### Commits desta revisão

| Commit | Alteração |
|---|---|
| `7d1f6f9c28dcb4d57ead655d5908bc6685c55edd` | Referência explícita à linha K1–K3 em `NornsView` |
| `dbcff52a53059b25ffe66892c4cab0f62b1f36ab` | Isolamento do teste instrumentado e reciclagem dos eventos |

### Evidências GitHub Actions consultadas

- Branch verificada: `feature/0.4.5-android-audio-driver`.
- `main` consultada e preservada no SHA `82fcedd60f76707ada4d25185837894a2e6664cd`.
- Probe ARM64 no SHA `3882b10baed2aef8284777834e245d8ece5888a4`: run [42365588882](https://github.com/jo1nGF/nornsmobile/actions/runs/42365588882), conclusão `success`. Todas as etapas da probe, inclusive compilação ARM64 e upload de artefatos, concluíram com sucesso.
- Integração/build no SHA `3882b10baed2aef8284777834e245d8ece5888a4`: run [42365588999](https://github.com/jo1nGF/nornsmobile/actions/runs/42365588999) foi cancelado após um novo push substituir o SHA; a etapa `Run unit tests and build debug APK` estava em andamento e não há evidência de conclusão.
- Após esta revisão, os runs [42365919328](https://github.com/jo1nGF/nornsmobile/actions/runs/42365919328) (integração/build) e [42365919395](https://github.com/jo1nGF/nornsmobile/actions/runs/42365919395) (probe) foram criados para o SHA `dbcff52a53059b25ffe66892c4cab0f62b1f36ab`. Na última consulta, ambos estavam em andamento; a etapa Gradle de testes unitários/build ainda não havia começado.
- O commit de documentação `402447e` não possuía check-runs próprios (0 check-runs); o run de CI existente foi disparado pelo commit de código anterior `3882b10`.
- O workflow de integração contém a etapa `gradle --no-daemon --stacktrace clean testDebugUnitTest assembleDebug`. Não contém `connectedDebugAndroidTest`; portanto o teste instrumentado não é executado por esse workflow.

### Estado de validação — sem extrapolar evidências

- [x] Probe ARM64 aprovada no SHA `3882b10`.
- [x] Validações nativas de gate/roteamento e compilação dos SynthDefs passaram no run de integração do SHA `3882b10`, antes de o job chegar à etapa Gradle.
- [ ] `testDebugUnitTest` e `assembleDebug` para o SHA revisado: aguardando conclusão do run `42365919328`.
- [ ] `connectedDebugAndroidTest`: não configurado no workflow consultado; exige runner/dispositivo/emulador.
- [ ] Teste físico touch, multi-touch, lifecycle e troca de script: pendente.
- [ ] Validação física dos dois buffers BufWr: permanece pendente.

### Restrições de áudio

Esta revisão alterou apenas a camada de UI touch e seu teste instrumentado. Não alterou o ownership de saída, PolyPerc, fallback Java, Softcut, buffers BufWr ou handoff. Manter `native_gate_open=false`; não iniciar handoff Oboe/AudioTrack e não habilitar playback Softcut nativo.


## Acompanhamento dos workflows após a revisão — 2026-11-07 (consulta posterior)

Consulta posterior às execuções associadas ao commit de código `dbcff52a53059b25ffe66892c4cab0f62b1f36ab`:

- [ ] Run [42365919395 — Experimental scsynth Android probe](https://github.com/jo1nGF/nornsmobile/actions/runs/42365919395): ainda `in_progress`; etapas de setup, instalação de NDK/CMake/Ninja e validação dos scripts concluídas; compilação/probe SuperCollider ARM64 ainda em andamento.
- [ ] Run [42365919328 — Integrate scsynth Oboe](https://github.com/jo1nGF/nornsmobile/actions/runs/42365919328): ainda `in_progress`; aguardava o artefato da probe do mesmo SHA. Importação ARM64, validações subsequentes e etapa Gradle `testDebugUnitTest assembleDebug` ainda não haviam começado.
- [ ] Nenhum resultado final de sucesso/falha para esses dois runs pode ser registrado nesta consulta.
- [ ] O workflow não possui etapa `connectedDebugAndroidTest`; o teste instrumentado permanece compilado no código-fonte, mas não executado.
- [ ] Não foi identificado runner Android físico/emulador acessível nesta sessão. Não se afirma execução local ou instrumentada.

A investigação do código confirma que `EncoderView` calcula deltas pelo deslocamento combinado horizontal/vertical e converte o acumulador em inteiros usando `Theme.dp(c, 9)`; o teste instrumentado envia um arraste horizontal de 130 px, portanto o gesto usado no teste ultrapassa o limiar de delta em densidades Android usuais. Isso é revisão estática, não resultado de execução.

**Próxima ação objetiva:** aguardar a conclusão da probe ARM64 do mesmo SHA, deixar o workflow de integração importar o artefato correspondente e executar `clean testDebugUnitTest assembleDebug`. Depois, providenciar um dispositivo/emulador ARM64 compatível para `connectedDebugAndroidTest` e validar K1–K3/E1–E3. Não alterar ABI/roteamento de áudio para acomodar um emulador x86_64.

Restrições mantidas: `main` permanece no SHA `82fcedd60f76707ada4d25185837894a2e6664cd`; PolyPerc e fallback Java preservados; `native_gate_open=false`; sem handoff Oboe/AudioTrack e sem playback Softcut nativo.


## Resultado da probe ARM64 e acompanhamento da integração — 2026-11-07

Consulta atualizada dos workflows vinculados ao SHA de código `dbcff52a53059b25ffe66892c4cab0f62b1f36ab`:

- [x] Run [42365919395 — Experimental scsynth Android probe](https://github.com/jo1nGF/nornsmobile/actions/runs/42365919395) terminou com conclusão `success`.
- [x] Job `128383832818`: setup Android SDK/NDK/CMake/Ninja, validação dos scripts, probe/compilação do SuperCollider 3.14.1 para Android ARM64 e upload do artefato concluídos.
- [x] Artefato `scsynth-android-probe-80`, ID `13571392921`, 5.287.092 bytes, não expirado na consulta. O log confirma `Linking CXX shared library server/scsynth/libscsynth.so` e upload de 79 arquivos.
- [x] Run [42365919328 — Integrate scsynth Oboe](https://github.com/jo1nGF/nornsmobile/actions/runs/42365919328) baixou o artefato correspondente ao mesmo SHA e importou as bibliotecas ARM64.
- [x] No job `128383833877`, concluíram com sucesso: importação/verificação das bibliotecas ARM64, teste lock-free Oboe input ring, validação do native output safety gate, roteamento exclusivo de saída Softcut e gate de escrita SoftcutRecorder.
- [ ] Na última consulta, a etapa `Compile native Softcut SynthDef` ainda estava `in_progress`; validação de SynthDefs, `testDebugUnitTest`, `assembleDebug`, verificação do APK e upload ainda pendentes. Não há conclusão de build/teste a registrar.
- [ ] O endpoint de logs do job de integração ainda não disponibilizou arquivo enquanto o job está ativo (resposta 404 do armazenamento temporário); consultar novamente após conclusão para capturar o resultado Gradle.
- [ ] O workflow continua sem `connectedDebugAndroidTest`. O teste instrumentado permanece no código, mas não há execução comprovada nem runner físico/emulador ARM64 disponível nesta sessão.

A probe ARM64 aprovada e o artefato importado validam a compilação do núcleo nativo no CI; não comprovam operação de áudio em dispositivo. Os avisos de compatibilidade/depreciação do Node presentes no log de upload não causaram falha no workflow.

Próxima ação: acompanhar o run 42365919328 até a etapa Gradle terminar; se falhar, obter logs completos, corrigir a causa na branch e repetir CI. Só então atualizar os estados de build/teste/APK. Configurar execução instrumentada somente quando houver runner ARM64 viável, sem alterar `abiFilters` nem a arquitetura nativa para acomodar um emulador incompatível.

Restrições de áudio mantidas: `native_gate_open=false`; PolyPerc audível e fallback Java preservados; não iniciar handoff Oboe/AudioTrack nem habilitar playback Softcut nativo.


## Integração ARM64 + APK 0.4.5 — conclusão CI em 2026-11-07

Run [42365919328 — Integrate scsynth Oboe](https://github.com/jo1nGF/nornsmobile/actions/runs/42365919328), disparado para o commit de código `dbcff52a53059b25ffe66892c4cab0f62b1f36ab`, terminou com conclusão `success`.

Evidências no job `128383833877`:

- [x] Compilação do Softcut SynthDef nativo.
- [x] Validação dos SynthDefs empacotados.
- [x] `gradle --no-daemon --stacktrace clean testDebugUnitTest assembleDebug` terminou com `BUILD SUCCESSFUL in 44s`.
- [x] Etapa `:app:testDebugUnitTest` executada e concluída sem falha.
- [x] Etapa `:app:assembleDebug` concluída.
- [x] Verificação do APK confirmou `lib/arm64-v8a/libc++_shared.so`, `lib/arm64-v8a/libnorns_sc_bridge.so` e `lib/arm64-v8a/libscsynth.so`.
- [x] APK copiado para `dist/NornsMobile-0.4.5-debug.apk`; SHA-256 registrado no arquivo `dist/SHA256SUMS.txt` do artefato.
- [x] Artefato [NornsMobile-0.4.5-debug-259](https://github.com/jo1nGF/nornsmobile/actions/runs/42365919328/artifacts/13571597453), ID `13571597453`, 3.547.391 bytes, enviado com sucesso.
- [x] Artefato `scsynth-android-imported-259`, ID `13571418009`, também enviado com sucesso.
- [x] A probe ARM64 [42365919395](https://github.com/jo1nGF/nornsmobile/actions/runs/42365919395) concluiu com sucesso; artefato nativo importado no workflow de integração.

Avisos de depreciação do Node.js 20 apareceram durante upload/ações auxiliares, sem afetar o resultado. Não houve falhas no job.

Limites da evidência:
- O log confirma a execução de `:app:testDebugUnitTest`, mas não publicou no artefato do APK o XML de resultados para contabilizar a quantidade de testes individualmente.
- `connectedDebugAndroidTest` não faz parte deste workflow; o teste instrumentado de NornsInputDispatcher/NornsView continua pendente de execução em dispositivo ou emulador Android compatível.
- CI verde não substitui validação física do áudio, do touch no Samsung SM-G973F nem do readback Softcut.

Restrições preservadas: `native_gate_open=false`; PolyPerc e fallback Java mantidos; handoff Oboe/AudioTrack e playback Softcut nativo continuam desabilitados.


## Consistência de versão e repetição de CI — 2026-11-07

A auditoria do APK 0.4.5 identificou que o workflow atualizava `versionCode`, `versionName` e a constante de versão apenas no workspace temporário do runner. O APK resultava corretamente em 0.4.5, mas os arquivos versionados ainda indicavam 0.4.4.

Correção aplicada na branch:
- `app/build.gradle.kts`: `versionCode = 9`, `versionName = "0.4.5"`.
- `MainActivity.java`: constante `VERSION = "0.4.5"`.
- Commits: `e158984b20bf97b98ff698d2826b909de3743659` e `ed3142c993a5d907a286ad02eef98bfd294d4d43`.

A alteração elimina a divergência entre metadados do repositório e o APK publicado. Os workflows disparados durante os dois pushes foram cancelados pela política de concorrência; ambos os jobs cancelados foram solicitados para nova execução, agora no SHA final. Runs de repetição: [probe 42370239394](https://github.com/jo1nGF/nornsmobile/actions/runs/42370239394) e [integração 42370239439](https://github.com/jo1nGF/nornsmobile/actions/runs/42370239439). Na última consulta, ambos estavam pendentes, portanto não registrar como aprovados ainda.

A aprovação anterior do SHA `dbcff52a53059b25ffe66892c4cab0f62b1f36ab` continua válida para aquele commit e para o código de touch/áudio que foi testado. A nova execução verificará a versão corrigida no HEAD atualizado.


## Reteste físico dos dois BufWr — 2026-11-07

Novo console recebido do Samsung SM-G973F (Android API 31), com scsynth
3.14.1 ARM64 a 48 kHz / bloco 64. Os dois canais foram observados gravando
e ambos acionaram callback de readback, mas a validação física **continua
reprovada**:

- CH1: readback de 24.000 amostras, peak=0 e RMS=0, apesar de max/RMS
  do monitor durante a gravação próximos de 0,5.
- CH2: readback de 24.000 amostras, peak=1 e RMS=0,35866; contudo o
  diagnóstico ainda indicava `recording=true` / `waiting_for_confirmed_stop`
  durante a janela observada.
- Ambos apresentaram divergências de cursor muito acima do limiar de
  0,001333 s e `recovery=true`.

Classificação atual: **Softcut nativo parcial; gravação física de dois
buffers não validada**. Não abrir `native_gate_open`, não iniciar handoff
Oboe/AudioTrack e não habilitar playback nativo. PolyPerc e fallback Java
permanecem como caminho audível.

Próxima tarefa: isolar CH1 e CH2 individualmente em rate=+1, confirmar
explicitamente /synced antes de ler, registrar parâmetros/buses e métricas
por chunk; só depois testar rate=-1. O relatório detalhado e os critérios
de reteste estão em `docs/BUFWR_DEVICE_VALIDATION.md`.


## Correção da instrumentação BufWr e CI aprovada — 2026-11-07

A execução anterior de integração [42371866929](https://github.com/jo1nGF/nornsmobile/actions/runs/42371866929), no SHA `0abccec2a874052cb00a19f6d41fc29785d9f7d9`, falhou em `:app:compileDebugJavaWithJavac`: chamada a `AppLog.w(String)` inexistente em `SuperColliderSoftcutBuffers.java:377`. A probe ARM64 correspondente passou.

Correção na branch: substituir somente essa chamada por `AppLog.i(String)`, método já existente na classe. Commit corretivo: `6fd43b9f75e2297de840f1a57c4ffae926fd2090`.

Validação do commit corretivo:
- [x] Probe ARM64 [42373132780](https://github.com/jo1nGF/nornsmobile/actions/runs/42373132780): sucesso.
- [x] Integração [42373132782](https://github.com/jo1nGF/nornsmobile/actions/runs/42373132782): sucesso.
- [x] Importação de `libscsynth.so` ARM64 e 23 plugins UGen.
- [x] Validações do Oboe input ring, native output safety gate, roteamento exclusivo Softcut e gate de escrita do SoftcutRecorder.
- [x] Compilação do Softcut SynthDef e validação dos SynthDefs empacotados.
- [x] `:app:testDebugUnitTest` executado sem falha.
- [x] `:app:assembleDebug` concluído; log registra `BUILD SUCCESSFUL in 32s`.
- [x] Verificação do APK confirmou `lib/arm64-v8a/libnorns_sc_bridge.so` e `lib/arm64-v8a/libscsynth.so`.
- [x] Artefato APK [NornsMobile-0.4.5-debug-264](https://github.com/jo1nGF/nornsmobile/actions/runs/42373132782/artifacts/13574930451), ID `13574930451`, 3.549.898 bytes; retenção até 2026-11-21.
- [x] Artefato nativo importado `scsynth-android-imported-264`, ID `13574890834`.

A aprovação é de CI/build/unit tests e empacotamento. Não comprova execução no Samsung, teste instrumentado Android, funcionamento físico do touch ou gravação/readback Softcut. O teste físico mais recente continua reprovado/parcial conforme seção anterior e `docs/BUFWR_DEVICE_VALIDATION.md`.

Estado mantido: `native_gate_open=false`; PolyPerc audível e fallback Java preservados; handoff Oboe/AudioTrack e playback Softcut nativo continuam desabilitados. A branch de trabalho continua `feature/0.4.5-android-audio-driver`; `main` não foi alterada.

## Reteste BufWr e contenção do worker — 2026-11-07

Novo teste físico no SM-G973F com Build 264: o monitor Java detectou entrada,
mas os chunks do readback nativo de ambos os buffers 20001/20002 retornaram
peak=0 e RMS=0. Os valores não nulos do resumo Lua pertencem ao SoftcutCore
Java e não comprovam escrita nativa. CH1 teve stop confirmado em 7 ms; CH2
teve stop confirmado em 10 ms, após aguardar o readback integral de CH1.

Correção aplicada exclusivamente na branch feature:
- [x] Deduplicar solicitações de stop enquanto o canal já está stop-pending.
- [x] Dividir readback pós-stop em tarefas de um chunk, cedendo a fila OSC
  entre chunks para que o stop de outro canal possa ser processado.
- [x] Adicionar testes unitários para a política de stop e progressão de chunks.
- [ ] Executar CI/build e confirmar novo APK com essa correção.
- [ ] Repetir validação física com CH1 e CH2 isolados, ambos rate=+1.
- [ ] Instrumentar a entrada do SoftcutRecorder (`in_bus`, canais e amplitude)
  para localizar por que o sinal do monitor Java não chega ao BufWr nativo.

Estado de aceite: Softcut nativo continua reprovado/parcial. Manter
`native_gate_open=false`, preservar PolyPerc/fallback Java e não iniciar
handoff Oboe/AudioTrack nem playback Softcut nativo. A correção de fila não
é apresentada como correção da gravação em si.


## Reteste físico Build 264 — resultado consolidado mais recente (2026-11-07)

Console adicional do Samsung SM-G973F, Android API 31, versão 0.4.5 / versionCode 9. O teste iniciou scsynth 3.14.1 ARM64 a 48 kHz / bloco 64, abriu a entrada Oboe mono e iniciou os dois nodes BufWr com `/synced=true`.

Resultado do readback nativo por chunks:
- CH1 / buffer 20001: todos os chunks observados peak=0 e RMS=0.
- CH2 / buffer 20002: todos os chunks observados peak=0 e RMS=0.
- Resumo Lua também terminou com peak=0/RMS=0 nos dois canais.
- Stop CH1 confirmado em 5 ms; stop CH2 confirmado em 10 ms.
- O monitor Java mostrou níveis não nulos, mas esse monitor consome o ring Java, enquanto o callback Oboe alimenta também um ring separado destinado ao bus de entrada do scsynth. Portanto, os níveis Java não medem diretamente o conteúdo entregue ao SynthDef.
- `native_gate_open=false`; PolyPerc/fallback Java preservados.

Este console substitui a interpretação anterior de CH2 não nulo: naquela execução, os valores do readback Lua eram do buffer Java e não do buffer nativo. A leitura direta dos chunks do scsynth é a evidência determinante e reprova ambos os buffers.

### Auditoria estática do caminho de entrada

No código da branch, o driver Oboe duplica cada amostra capturada para dois rings SPSC independentes: `mJavaInputRing` (fallback/monitor Java) e `mScInputRing` (entrada do World SuperCollider). Antes de `World_Run`, `fillInputBuses` lê o segundo ring e o coloca no primeiro bus físico de entrada. Como o World é iniciado com dois buses de saída e um de entrada, esse bus corresponde ao índice 2, que é também o default `in_bus=2` do SynthDef `SoftcutRecorder`.

A configuração estática parece coerente, mas ainda não há medição de amplitude no bus 2 nem imediatamente antes do `BufWr`. A causa do sinal zero permanece não localizada. Próxima etapa: instrumentar separadamente o pico/RMS do bus de entrada do World e do sinal `incoming` dentro do SynthDef; registrar também `recordGate` e `writeValid`. Depois testar CH1 e CH2 isoladamente em rate=+1, com stop confirmado e readback direto.

A alteração de deduplicação de stop/readback cooperativo está presente no HEAD da branch, mas o status combinado do commit `adb3d840e61ace61d18bfee6fd33369f46da78bd` ainda não apresenta checks. Não há APK novo comprovado para essa alteração. O Build 264 / versionCode 9 continua sendo o último APK identificado; não atribuir a ele as correções posteriores do worker.

Bloqueios mantidos: não abrir `native_gate_open`, não habilitar playback Softcut nativo e não iniciar handoff Oboe/AudioTrack. 


## Instrumentação da rota Oboe → bus SC → BufWr (2026-11-07)

Implementação adicionada na branch feature:
- O callback Oboe continua alimentando rings SPSC separados para Java e scsynth.
- `fillInputBuses` agora mede peak/RMS e frames efetivamente drenados do ring nativo no bloco que será entregue ao World.
- `SoftcutRecorder` expõe em buses de diagnóstico isolados: entrada física (`In.ar(in_bus)`), sinal misturado `incoming`, `recordGate` e `writeValid`.
- CH1 usa buses 19–22 e CH2 buses 23–26; os buses 17/18 continuam exclusivos para os cursores de gravação. Nenhum bus de diagnóstico é copiado para a saída Oboe.
- Snapshot nativo/JNI ampliado de 16 para 40 floats, mantendo os índices legados 0–15 e acrescentando métricas da entrada, contadores do ring e 8 métricas por canal.
- Lua `softcut.native_diagnostics()` expõe tabelas `input` e `recorder_signal`; o worker também registra `Softcut BufWr signal probe` após start e stop sincronizados.
- O validor de SynthDef exige o controle `diagnostic_bus` e o Out de quatro sinais. Teste de coerência do snapshot atualizado para 40 valores.

Estado: instrumentação de código registrada; CI do HEAD e teste físico ainda precisam confirmar compilação e os valores observados. O teste anterior continua reprovado (buffers 20001/20002 zerados). Não inferir correção de gravação apenas pela presença dos probes.

Próxima decisão com os logs:
- ring/bus de entrada zero: investigar captura Oboe, sincronização dos callbacks ou ring SPSC;
- bus de entrada não zero, rawInput zero: investigar indexação/semântica do bus `In.ar`;
- rawInput não zero, incoming zero: investigar mixagem/níveis do SynthDef;
- incoming e gate/writeValid não zero, readback zero: investigar fase/BufWr/buffer/node e sincronização de escrita.

## Correção do validador de roteamento após snapshot de 40 valores — 2026-11-07

A integração [42380101336](https://github.com/jo1nGF/nornsmobile/actions/runs/42380101336) falhou antes da compilação Java/Gradle, na validação estática `tools/validate-softcut-output-routing.py`. A asserção ainda exigia `count < 16`, `read(values, 16)`, `length >= 16` e `new float[16]`, embora a instrumentação recente tenha ampliado corretamente o snapshot para 40 floats.

Correção aplicada na branch `feature/0.4.5-android-audio-driver`:
- [x] Atualizar as expectativas do validador para 40 valores em driver, JNI, bridge Java e LuaHost, mantendo intactos os índices legados 0–15.
- [x] Preservar a validação dos índices de roteamento/cursor e os controles de saída exclusiva.
- [ ] Reexecutar probe/integracão para o novo HEAD; o build APK ainda não foi executado para esta revisão.

A probe ARM64 do commit anterior concluiu com sucesso no run [42380101321](https://github.com/jo1nGF/nornsmobile/actions/runs/42380101321). A falha de integração ocorreu na asserção obsoleta, antes das etapas de SynthDef e Gradle; não há evidência de falha de compilação C++/Java neste run.

Restrições mantidas: `main` intocada; `native_gate_open=false`; PolyPerc e fallback Java preservados; sem playback Softcut nativo ou handoff Oboe/AudioTrack até validação física dos dois buffers.

## Correção de taxa do sinal de diagnóstico SoftcutRecorder — 2026-11-07

A probe ARM64 [42380767660](https://github.com/jo1nGF/nornsmobile/actions/runs/42380767660) concluiu com sucesso, mas a integração [42380767654](https://github.com/jo1nGF/nornsmobile/actions/runs/42380767654) falhou ao compilar o SynthDef `SoftcutRecorder`: `Out input at index 3 ... is not audio rate`. A falha ocorreu porque `recordGate = record * enabled` é um sinal de controle (control rate), enquanto `Out.ar` exige todos os canais em audio rate.

Correção aplicada na branch feature: o terceiro canal do probe agora usa `K2A.ar(recordGate)`, mantendo a mesma medição lógica do gate e convertendo-a para audio rate apenas na saída diagnóstica. O gate efetivo do BufWr e o roteamento audível não foram alterados.

- [x] Probe ARM64 do commit anterior passou.
- [x] Identificada a origem do erro de taxa do canal de diagnóstico.
- [x] Conversão explícita do probe recordGate para audio rate.
- [ ] Validar novamente SynthDef, testes unitários e APK no novo SHA.
- [ ] Retestar fisicamente CH1/CH2 e analisar métricas; nenhum resultado de gravação nativa é presumido.

Manter `native_gate_open=false`, PolyPerc e fallback Java; sem handoff Oboe/AudioTrack ou playback Softcut nativo.


## Ajuste da asserção do probe e repetição da integração — 2026-11-07

A probe ARM64 [42381369812](https://github.com/jo1nGF/nornsmobile/actions/runs/42381369812) concluiu com sucesso para o código com a instrumentação de diagnóstico. A integração [42381369690](https://github.com/jo1nGF/nornsmobile/actions/runs/42381369690) falhou no teste estático `tools/test-softcut-recorder-gate.py`: a asserção ainda procurava o texto antigo `Out.ar(... recordGate ...)`, embora o SynthDef já convertesse o sinal do canal diagnóstico com `K2A.ar(recordGate)` para satisfazer o requisito de audio rate do `Out.ar`.

Correção aplicada na branch feature:
- [x] Atualizar a asserção estática para exigir `K2A.ar(recordGate)` no canal diagnóstico.
- [x] Manter o gate real de gravação do BufWr inalterado; a conversão ocorre somente na saída de diagnóstico.
- [ ] Reexecutar probe e integração no novo HEAD; ainda não há APK validado para esta revisão.

A falha ocorreu antes da compilação do SynthDef e antes do Gradle; portanto, não representa uma nova falha de compilação nativa/Java. A probe ARM64 aprovada comprova somente a compilação do core SuperCollider, não a gravação física dos buffers.

Restrições mantidas: `main` intocada; `native_gate_open=false`; PolyPerc e fallback Java preservados; sem playback Softcut nativo ou handoff Oboe/AudioTrack até validação física independente de CH1 e CH2.

## Correção da taxa de áudio do sinal writeValid no SynthDef — 2026-11-07

A integração [42380767654](https://github.com/jo1nGF/nornsmobile/actions/runs/42380767654) passou pelas validações de segurança/roteamento e importação ARM64, mas falhou ao compilar `SoftcutRecorder`: `Out input at index 3 ... is not audio rate`. O quarto canal do bus de diagnóstico (`writeValid`) era uma expressão de comparação cujo resultado não estava explicitamente convertido para taxa de áudio.

Correção na branch feature:
- [x] Converter explicitamente `writeValid` com `K2A.ar(writeValid)` ao compor o `Out.ar(diagnostic_bus, ...)`.
- [x] Manter `physicalInput`, `incoming` e `recordGate` como os outros três canais de diagnóstico.
- [ ] Reexecutar compilação do SynthDef, validação dos binários, testes unitários e geração do APK para o novo SHA.

A alteração afeta somente o bus de diagnóstico dedicado; não altera `BufWr`, o sinal gravado, o bus de saída audível ou o ownership Java/Oboe. Não representa ainda correção/validação da gravação nativa.

Estado de segurança mantido: `native_gate_open=false`; PolyPerc e fallback Java preservados; sem playback Softcut nativo ou handoff Oboe/AudioTrack até aprovação física dos dois buffers.


## CI verde e APK 0.4.5 gerado — 2026-11-07

A repetição dos workflows para o commit de código `23be090379fb0341a192c9993f62b44c42563016` concluiu com sucesso:
- [x] Probe SuperCollider 3.14.1 Android ARM64: run [42382047219](https://github.com/jo1nGF/nornsmobile/actions/runs/42382047219), job concluído com sucesso.
- [x] Integração scsynth/Oboe e APK: run [42382047038](https://github.com/jo1nGF/nornsmobile/actions/runs/42382047038), todos os passos concluídos com sucesso.
- [x] Testes de ring lock-free, gate de segurança de saída, roteamento exclusivo Softcut, gate de gravação, compilação do SynthDef e validação dos SynthDefs incluídos.
- [x] Testes unitários, build do APK debug e verificação das bibliotecas nativas empacotadas.

Artefato APK: [NornsMobile-0.4.5-debug-282](https://github.com/jo1nGF/nornsmobile/actions/runs/42382047038/artifacts/13577709910), 3.552.392 bytes, disponível até 2026-11-21. A validação CI não substitui a prova física de escrita/leitura dos buffers CH1 e CH2 no dispositivo.

Estado mantido: `native_gate_open=false`; PolyPerc e fallback Java preservados; sem ativação de playback Softcut nativo ou handoff de saída até a validação física independente dos dois canais. Nenhuma alteração em `main`.


## Diagnóstico Softcut: métricas acumuladas por sessão — 2026-11-07

Revisão da instrumentação identificou que os 16 valores por canal eram substituídos a cada bloco de áudio. Os logs start/stop consultavam somente o snapshot mais recente, o que poderia registrar silêncio e perder o sinal observado durante a gravação.

Implementação adicionada na branch feature:
- [x] Novo `SoftcutSignalAccumulator` para peak-hold e RMS cumulativo, com descarte de amostras não finitas.
- [x] Acumuladores independentes para os quatro estágios de CH1 e CH2 (physicalInput, incoming, recordGate, writeValid).
- [x] Reset por canal via API nativa → JNI → Java, solicitado somente após start confirmado por /synced e aplicado pelo callback sem mutex/alocação.
- [x] Snapshot após stop confirmado passa a representar a sessão desde o reset; removido log start que poderia expor dados antigos.
- [x] Teste C++ dedicado para agregação, RMS, reset e NaN/Inf; adicionado ao workflow de integração com `-Wall -Wextra -Werror`.
- [x] Patch de integração copia o novo header para o checkout upstream do SuperCollider; validação estática e export do novo símbolo incluídos no CI.
- [ ] Executar probe ARM64 e integração/APK no novo HEAD; não há APK validado desta revisão ainda.
- [ ] Depois do CI, analisar métricas acumuladas e readback direto dos buffers 20001/20002 no teste geral de dispositivo.

O ajuste é diagnóstico, não uma correção comprovada de escrita. Não altera BufWr, os níveis do sinal gravado, o roteamento de áudio ou a saída audível. O reset é por canal e usa geração atômica consumida no callback; os acumuladores são escritos somente pelo callback e publicados pelo snapshot lock-free.

Restrições mantidas: `main` intocada; `native_gate_open=false`; PolyPerc e fallback Java preservados; sem playback Softcut nativo ou handoff Oboe/AudioTrack até a validação física de ambos os buffers.


## Ajuste da asserção do gate após CI — 2026-11-07

A probe ARM64 do commit `af77b8e9bd1e894de8c5b5044c9d6b57f4175852` passou, e as validações de ring, gate de saída e roteamento exclusivo também passaram. A integração falhou no teste estático `tools/test-softcut-recorder-gate.py` porque a string esperada ainda descrevia o quarto canal diagnóstico como `writeValid` sem conversão de taxa. O SynthDef atual já usa `K2A.ar(writeValid)`, além de `K2A.ar(recordGate)`, corretamente para que todos os canais de `Out.ar` sejam audio-rate.

- [x] Corrigir a asserção para exigir `K2A.ar(writeValid)` no quarto canal diagnóstico.
- [ ] Reexecutar probe e integração para o novo commit; o build APK foi interrompido antes da compilação do SynthDef/Gradle.

A falha é do teste estático desatualizado, não da compilação C++ nem do SynthDef. O teste C++ de acumuladores passou no workflow; a API nativa de reset foi encontrada no ELF ARM64. Nenhum APK novo foi produzido. Manter `native_gate_open=false`, PolyPerc/fallback Java e `main` intacta.


## Reexecução após correção da asserção writeValid — 2026-11-07

A integração [42382478798](https://github.com/jo1nGF/nornsmobile/actions/runs/42382478798) falhou antes de compilar o SynthDef: o teste estático ainda exigia a string antiga sem `K2A.ar(writeValid)`. A probe ARM64 [42382478906](https://github.com/jo1nGF/nornsmobile/actions/runs/42382478906) passou. A versão atual de `tools/test-softcut-recorder-gate.py` já exige a expressão correta com `K2A.ar(recordGate)` e `K2A.ar(writeValid)`; esta revisão será submetida novamente ao CI completo.

Até a conclusão: nenhum APK desta revisão é considerado validado. Permanecem `native_gate_open=false`, PolyPerc e fallback Java preservados, e playback Softcut nativo/handoff desativados.


## Diagnóstico ampliado da entrada Softcut — próxima build

A instrumentação foi ampliada para distinguir cinco pontos por recorder: `physicalInput`, `scaledInput` (após `input_level` e `adc_level`), `incoming` (mix final incluindo os seis buses de corte), `recordGate` e `writeValid`. Os buses diagnósticos agora ocupam CH1 19–23 e CH2 24–28, sem conexão com a saída audível. O snapshot passa a 44 floats e mantém os índices legados 0–23.

Os acumuladores de entrada/ring agora são reiniciados por solicitação de início de gravação e agregam peak/RMS, frames lidos e deltas de dropped/skipped/underrun durante a sessão, em vez de expor somente o último bloco SC de 64 frames. Isso permite correlacionar o sinal capturado, o sinal no bus do World e os estágios do SynthDef no mesmo intervalo de gravação.

Esta alteração é somente diagnóstica: não muda a propriedade de saída, não abre `native_gate_open`, não habilita playback Softcut e não altera o fallback Java. Critério de aprovação continua sendo readback não-zero dos buffers nativos, primeiro isoladamente em CH1 e CH2.


## Build 296 — diagnóstico físico e próxima instrumentação (2026-11-07)

No Samsung SM-G973F / Android API 31, a Build 296 confirmou entrada Oboe/World e ganho: inputBusPeak=rawInputPeak=scaledInputPeak=0.118835 nos dois canais. Porém incomingPeak/RMS e todos os readbacks nativos dos buffers 20001/20002 foram zero; recordGate e writeValid permaneceram em 1. O monitor/recuperação Java não é prova de escrita nativa. A próxima revisão separa cutMix dos seis buses de corte e incoming, para detectar cancelamento ou erro na soma. O gate nativo continua fechado; PolyPerc e fallback Java preservados; sem playback Softcut nativo, seis vozes ou handoff Oboe/AudioTrack até prova de escrita e roteamento.


## Proteção da abertura assíncrona MIDI — 2026-11-07

Implementação na branch `feature/0.4.5-android-audio-driver` (não confundir código commitado com CI validado):
- [x] Confirmar que a fila FIFO limitada já existia em `MidiBridge` e era acionada quando mensagens chegavam durante `MidiManager.openDevice`.
- [x] Preservar a ordem FIFO e cópia defensiva das mensagens enfileiradas.
- [x] Tratar overflow comum descartando a mensagem mais nova e contabilizando perdas.
- [x] Proteger Note Off (incluindo Note On com velocity zero): se um Note Off encontra a fila cheia, substituir o burst incompleto por Sustain Off + All Notes Off nos 16 canais e então preservar o Note Off recebido. A fila permanece limitada.
- [x] Adicionar testes unitários para FIFO, cópia, overflow, limpeza, Note Off e recuperação de emergência.
- [x] Capturar falha síncrona de `MidiManager.openDevice` e liberar token/fila pendente por `failOpen`.
- [x] Tratar `RuntimeException` e `IOException` no envio para uma porta que deixou de aceitar mensagens.
- [ ] Executar JUnit/Gradle, workflows ARM64 e integração/APK no novo HEAD; nenhum resultado de CI ou APK é atribuído a esta revisão ainda.
- [ ] Acrescentar testes instrumentados/fakes para callback nulo, falha de abertura, close durante abertura, reconexão e flush real na porta Android.
- [ ] Validar Note On/Off, CC, Program Change, Pitch Bend, clock e hotplug em USB/BLE-MIDI quando a fase de implementação estiver concluída.

Nota: a recuperação de overflow envia controles de emergência para todos os canais, o que pode interromper notas legítimas, mas é preferível a deixar notas presas. A política é contingência para uma fila saturada, não substitui validação de transporte real. A abertura continua assíncrona.

Estado de segurança de áudio inalterado: `native_gate_open=false`; PolyPerc e fallback Java preservados; sem handoff Oboe/AudioTrack ou playback Softcut nativo; CH1/CH2 continuam aguardando prova de escrita/readback não-zero. `main` permanece intocada.


## Estado MIDI refletido no Lua — 2026-11-07

A auditoria da API `midi.connect()` encontrou que o objeto Lua era marcado como `connected=true` imediatamente, antes do callback assíncrono Android. Isso mascarava falhas de abertura e tornava o estado exposto ao script incorreto.
- [x] Introduzir estados distintos `connecting` e `connected` no objeto de porta Lua.
- [x] Permitir envio enquanto `connecting=true`, para que a fila temporária receba as mensagens iniciais do script.
- [x] Fazer `MidiBridge.open()` retornar se a solicitação assíncrona foi aceita; IDs inválidos e falhas síncronas retornam falso.
- [x] Notificar conclusão/falha por callback e atualizar `connected/connecting/connection_error` na thread serializada do LuaHost.
- [x] Sincronizar chamadas repetidas a `midi.connect()` com uma conexão que já está aberta.
- [ ] Adicionar testes de contrato para a transição connecting → connected/failed e testar callback real Android; CI ainda pendente.
- [ ] Testar hotplug e estabilidade dos IDs após atualização da lista de dispositivos.

A fila e os callbacks são implementados, mas não há evidência ainda de JUnit/Gradle verde nem de envio físico USB/BLE para este HEAD.


## Correção da auditoria de screen.blend_mode — 2026-11-07

A releitura do switch `LuaHost.screenOp` e de `ScreenSurface.setBlendMode` corrigiu uma conclusão anterior incorreta:
- [x] `screen.blend_mode` está implementado no caso 32 e encaminha para `ScreenSurface.setBlendMode`.
- [x] A superfície Android oferece modos PorterDuff: over/normal/source_over, add/plus, multiply, screen, darken, lighten e replace/src.
- [x] Corrigir comentário obsoleto no default do switch para não listar `blend_mode` como ausente.
- [ ] `screen.export_screenshot` e `screen.display_png` continuam sem implementação e exigem desenho de contrato/testes antes de codificar.

Esta correção é de auditoria/documentação; não altera a renderização, a compatibilidade de scripts ou o comportamento de áudio.


## CI HEAD 8002b562 e regressões de compatibilidade — 2026-11-07

### CI observado
- [x] Probe ARM64 do commit `a00731a5833619e098cbd3114d7f3592e8103389` concluída com sucesso (workflow `42418273817`).
- [x] Importação ARM64, Oboe input ring, gate de saída, roteamento exclusivo Softcut, gate BufWr e compilação/validação dos SynthDefs passaram na integração `42418273791`.
- [x] Identificar falha de compilação Java no callback MIDI: LuaJ não oferece `LuaValue.set(String, boolean)`; falhas em `LuaHost.dispatchMidiConnection` para os campos `connecting` e `connected`.
- [x] Corrigir com `LuaValue.FALSE` e `LuaValue.valueOf(connected)` no commit `c0b52ac57595a6434277586989096171d37a00f9`.
- [x] Acrescentar teste de regressão LuaJ para `ParamSet:default()` sem ID e execução do callback que inicializa `scale_notes` (commit `8002b56246c9a5c36381e7f206986293ae0ffdea`).
- [ ] Workflows atuais no SHA `8002b56246c9a5c36381e7f206986293ae0ffdea`: probe `42418639385` e integração `42418639403`; ambos em execução na última consulta. Aguardar JUnit, APK, verificação de bibliotecas e artefatos antes de declarar aprovação.

### circles.lua:240 — causa e regressão
- [x] Recuperar a mensagem original: `circles.lua:240 attempt to get length of nil`, expressão `math.floor(math_helpers.scale(circle.x, 0, 128, 1, #scale_notes))` dentro de `noteForCircle()`.
- [x] Confirmar que a causa era `params:default()` não aceitar chamada sem ID; sem callbacks de inicialização, `scale_notes` permanecia nil.
- [x] Confirmar correção já incorporada desde `0eed2d73667432fffff9b34bb6086186280e863a`: default sem ID restaura todos os parâmetros e dispara seus callbacks, mesmo quando o valor já está no default.
- [x] Usuário já confirmou que `circles` v1.6.3 funcionou após a correção.
- [x] Adicionar teste JUnit/LuaJ para impedir regressão na inicialização de `scale_notes`.
- [ ] Reexecutar teste no CI do SHA `8002b562`; o pacote original `circles` não está versionado na árvore atual, portanto não declarar teste de integração do script completo nesta rodada.

### MIDI — testes de integração em evolução
- [x] Testes unitários da fila FIFO, cópia defensiva, overflow, preservação Note Off, velocity-zero e limpeza.
- [x] Estado Lua separado entre `connecting`, `connected` e `connection_error`, atualizado pelo callback Android.
- [x] Corrigir tipos LuaJ no callback Java para que o código compile.
- [ ] Validar callback assíncrono real, falha de abertura, close durante abertura, reconexão e flush na porta real; não confundir enumeração com conexão nem JUnit com teste físico USB/BLE.

**Restrições preservadas:** branch `feature/0.4.5-android-audio-driver`; `main` não alterada (SHA observado: `82fcedd60f76707ada4d25185837894a2e6664cd`); PolyPerc e fallback Java preservados; `native_gate_open=false`; seis vozes Softcut e handoff Oboe/AudioTrack continuam bloqueados.


## CI integral aprovado — build 370 — 2026-11-07

**SHA de código validado:** `8002b56246c9a5c36381e7f206986293ae0ffdea`.

- [x] Probe SuperCollider 3.14.1 ARM64: workflow `42418639385`, conclusão `success`; artefato `scsynth-android-probe-185`, ID `13592891456`.
- [x] Integração scsynth/Oboe: workflow `42418639403`, conclusão `success`, checkout do SHA exato.
- [x] Importar `libscsynth.so` ELF64/AArch64 e 23 plugins UGen; verificar símbolos de controle NornsSC.
- [x] Passar teste C++ lock-free do Oboe input ring, validador de gate nativo, roteamento exclusivo Softcut e gate de escrita do SoftcutRecorder.
- [x] Compilar `SoftcutVoice` e `SoftcutRecorder`; validar bundle de SynthDefs.
- [x] Executar `testDebugUnitTest` e `assembleDebug`; etapa Gradle terminou com sucesso e log reportou `BUILD SUCCESSFUL in 33s`.
- [x] Teste de regressão LuaJ `paramDefaultWithoutIdRunsActionsNeededByCircles` foi incluído no conjunto JUnit que passou; a tarefa não publicou XML/contagem detalhada, portanto não inventar número de testes.
- [x] Verificar no APK as bibliotecas `lib/arm64-v8a/libscsynth.so`, `libnorns_sc_bridge.so` e `libc++_shared.so`.
- [x] Publicar APK debug 0.4.5: `NornsMobile-0.4.5-debug-370`, artifact ID `13592851962`, 3.557.800 bytes; [artefato no GitHub Actions](https://github.com/jo1nGF/nornsmobile/actions/runs/42418639403/artifacts/13592851962).
- [x] Publicar payload nativo importado: `scsynth-android-imported-370`, artifact ID `13592792165`, 5.280.714 bytes; [artefato no GitHub Actions](https://github.com/jo1nGF/nornsmobile/actions/runs/42418639403/artifacts/13592792165).
- [x] Corrigir falha anterior de compilação Java no callback MIDI com os tipos `LuaValue.FALSE` e `LuaValue.valueOf(connected)`; o build 370 aprovou.
- [x] Regressão circles: a causa de `#scale_notes` nil já estava corrigida por `ParamSet:default()` sem ID (commit histórico `0eed2d7`); o teste de contrato novo passou no conjunto JUnit. O script original circles não está versionado nesta branch e não foi reexecutado como pacote completo nesta CI.
- [ ] Teste MIDI Android real: abertura assíncrona, callback, flush, falha, close/reconexão e transporte USB/BLE; CI JUnit não substitui validação física.
- [ ] Instalar/validar o APK no SM-G973F quando as implementações principais estiverem concluídas; não solicitar teste geral antecipadamente.

**Restrições:** `main` continua intocada; PolyPerc e fallback Java preservados; `native_gate_open=false`; nenhuma liberação de seis vozes, playback Softcut nativo ou handoff Oboe/AudioTrack. A aprovação CI não é prova de isolamento físico BufWr CH1/CH2 nem de áudio real no aparelho.


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

 
### Áudio nativo — atualização de provas e MIDI (2026-11-07)

- [x] Fila MIDI assíncrona reforçada contra overflow de Note Off: preserva a
  liberação e emite sustain-off/all-notes-off por canal quando necessário.
- [x] Sete casos unitários da fila MIDI (FIFO, cópia, overflow comum, panic,
  Note On velocity zero, limpeza e capacidade mínima).
- [x] Mixer PCM estéreo extraído e coberto por teste C++ determinístico.
- [ ] Prova runtime BufWr CH1/CH2 com fingerprints do servidor no Android.
- [ ] Prova runtime PCM Oboe L/R e handoff exclusivo sem duplicação.
- [ ] Softcut nativo e seis vozes permanecem bloqueados até todas as provas.


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



## Inventário de APIs externas Norns — 2026-11-08

- [x] Marcar Grid/Arc/HID vports com `available=false` e `connected=false`; operações de LED/segmento/refresh/update retornam false e emitem diagnóstico único, em vez de no-op silencioso.
- [x] Crow mantém um objeto null seguro, mas operações init/reset/clear/send agora informam indisponibilidade; `crow.connect()` não simula conexão física.
- [x] Expor gamepad, serial, poll e keyboard como null objects com `available=false`; não inventar suporte a USB-serial/HID sem backend real.
- [x] Adicionar testes de contrato para disponibilidade e ausência de falsa conexão.
- [x] Backend Android USB Host CDC-ACM e API serial Norns base implementados; Crow tem handler específico experimental para VID:PID 0483:5740.
- [ ] Implementar backend monome Grid/Arc (libmonome/FTDI/HID) e validar Crow CDC-ACM em hardware; integrar HID de saída somente após contrato/protocolo.
- [ ] Implementar poll real para dados de áudio/CPU/entrada, keyboard e integração de gamepad.
- [ ] Esses módulos permanecem pendentes de implementação funcional; o objetivo é não deixar scripts acreditar que o hardware foi conectado quando não foi.



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
- [ ] Poll de entrada estéreo/nativa, Grid/Arc, HID de saída e buses engine/tape seguem pendentes; USB serial CDC-ACM e Crow são experimentais e aguardam CI e hardware. Softcut permanece fechado até prova runtime independente/estéreo/handoff.



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
- [ ] Engines SC customizadas, buses engine/tape e backends físicos Grid/Arc/Crow/USB-serial/HID permanecem pendentes; não declarar suporte sem backend funcional.

A branch feature permanece isolada de `main`; PolyPerc, fallback Java e `native_gate_open=false` continuam preservados.


## Limite agregado de memória para imagens da Screen — 2026-11-08

- [x] Adicionar orçamento agregado de 32 MiB para imagens pertencentes ao script, além dos limites individuais de dimensão/área já existentes.
- [x] Verificar o custo estimado antes de decodificar PNG ou alocar bitmap; confirmar a alocação real com `Bitmap.getByteCount()` antes de registrar a imagem.
- [x] Atualizar a contabilidade em `free_image` e zerá-la no reset, preservando a recusa de liberar imagens usadas por `draw_to`.
- [x] Adicionar teste de contrato para orçamento, registro, liberação e reset.
- [ ] Executar JUnit completo, validação dos SynthDefs, APK e artefatos nesta revisão antes de marcar CI aprovado.
- [ ] Permanecem pendentes os testes runtime de imagem no Android e todas as provas físicas de áudio/Softcut; `native_gate_open=false` e as seis vozes continuam desabilitadas.


## Continuidade automática — 2026-11-08

- [x] Reconciliar o README com o estado real da linha 0.4.5: retirar declarações obsoletas de 0.4.1, separar compilação ARM64 de validação runtime e explicitar os gates de áudio.
- [x] Confirmar a branch ativa em `feature/0.4.5-android-audio-driver`; HEAD desta atualização documental: `977c9a60ad036c21e373175b9acaa7a39f87bb9d`. `main` não foi alterada.
- [ ] Executar CI do HEAD documental quando houver alteração de código elegível aos workflows; builds verdes de commits diferentes não serão atribuídos a este SHA.
- [x] Implementar o módulo oficial keyboard para teclado físico Android: callbacks code(key,value) e char(ch), estados SHIFT/ALT/CTRL/META, code_to_char, mapeamento de teclas e eventos de repetição.
- [x] Implementar keyboard físico e gamepad genérico suportado pelo Android; [ ] manter Crow/Grid/Arc/HID/Serial como integrações dependentes de transporte e hardware real.
- [ ] Engines SC customizadas/32Harmony seguem bloqueadas até disponibilizar e integrar sclang + SCClassLibrary + dependências Quarks compatíveis com Android ARM64.
- [ ] Continuar API por API, com teste de contrato e evidência de implementação; reservar a auditoria global e a bateria no dispositivo para o fim, conforme solicitado.

**Regra de execução:** nenhuma etapa exige confirmação intermediária. O trabalho segue na branch feature; não abrir o gate nativo, não habilitar playback Softcut e não substituir PolyPerc/fallback Java.


## Teclado físico Android — 2026-11-08

- [x] Substituir o placeholder keyboard pela API compatível com o módulo norns: code(key,value), char(ch), shift(), alt(), ctrl(), meta(), code_to_char(code) e tabela state.
- [x] Encaminhar eventos Android pela Activity e serializar a entrega na thread Lua; mapear letras, números, navegação, modificadores, funções e teclas de pontuação/numpad.
- [x] Preservar valores de evento norns: 0 liberada, 1 pressionada, 2 repetição/held; caracteres vêm do layout ativo do Android.
- [x] Adicionar teste de contrato para módulo Lua, ponte Java e despacho da Activity.
- [ ] CI da revisão de teclado no SHA fec688310cc02481caf41566c6ee88a9d0444b80: probe 42443236322 e integração 42443236320 estavam em execução na última consulta; não declarar aprovado até conclusão.
- [ ] Completar liberação de teclas presas ao perder foco/trocar script e testar teclado USB/Bluetooth físico na bateria final.
- [ ] code_to_char atualmente reflete o último caractere observado para o código; confirmar equivalência completa com layouts nacionais e teclas mortas durante auditoria.

Estado do ciclo: HEAD de código fec688310cc02481caf41566c6ee88a9d0444b80; comparação main...feature: 747 commits à frente, 0 atrás. O README já foi reconciliado. Nenhuma alteração de áudio/gates nesta etapa.


## Teclado e gamepad Android — continuação 2026-11-08

- [x] Teclado físico Android: eventos press/release/repeat, estados booleanos Norns, callbacks code/char, modificadores SHIFT/ALT/ALTGR/CTRL/META e mapeamento por KeyCharacterMap para layout ativo, incluindo Shift e AltGr.
- [x] Segurança de foco: encaminhar teclas apenas na aba NORNS; liberar teclas retidas ao trocar de aba, perder foco ou pausar Activity; limpar estado ao parar script.
- [x] Gamepad Android genérico: botões A/B/X/Y, shoulder, start/select, botões adicionais, D-pad, eixos analógicos esquerdo/direito, triggers e estados direcionais; callbacks button/dpad/axis/analog.
- [ ] CI para o commit de teclado/gamepad e APK: aguardando workflow após esta revisão; resultados de commits anteriores não são herdados.
- [ ] Gamepad ainda requer validação com controles físicos USB/Bluetooth e implementação de hotplug/disconnect confiável e perfis por GUID para equivalência completa ao norns.
- [ ] Serial/Crow/Grid/Arc/HID continuam dependentes de transporte e hardware específicos; não marcar como implementados por stubs.


## CI de validação do teclado/gamepad — 2026-11-08

- [x] Corrigido o contrato antigo que marcava gamepad como null object; agora o teste exige a API Android genérica.
- [x] Ativado o workflow APK independente também na branch feature para validar JUnit/Gradle/APK sem depender da importação do payload scsynth.
- [ ] Os runs probe 42444196041 e integração 42444196021 falharam antes de iniciar qualquer step: API reportou runner_id=0, steps vazios e sem logs; rerun também falhou no mesmo estágio. Isso não constitui falha de compilação/teste do código.
- [ ] Aguardar o novo build APK na branch e só então atribuir aprovação ao SHA correspondente.


## Engine 32Harmony — implementação inicial Android 2026-11-08

- [x] Adicionar SynthDef nativo pré-compilado `Norns32Harmony.scsyndef`, sem exigir `sclang` ou CroneEngine no aparelho.
- [x] Implementar 16 vozes independentes por canal (32 total), clocks aleatórios, quantização por cinco steps, range de oitavas, attack/decay, HPF/LPF e saturação.
- [x] Integrar `engine.load("32Harmony")`, criação do node nativo, fila de comandos durante carregamento e parâmetros `rate1In/rate2In/rootIn/range1In/range2In/attackIn/decayIn/lpfCutoffIn/hpfCutoffIn/quantAmtIn/step0In…step4In/freqMultIn/saturationAmtIn`.
- [ ] CI deve compilar e validar o SynthDef, JUnit e APK; nenhuma aprovação é atribuída antes de execução real dos workflows.
- [ ] Auditar fidelidade sonora/CPU e testar o script original apenas na bateria final; o SynthDef Android é uma reimplementação funcional da arquitetura, não execução da classe SC original.


## Validação do SynthDef 32Harmony e APK independente — 2026-11-08

- [x] Adicionar Norns32Harmony ao compilador e validador de SynthDefs; verificar 32 vozes e saída estéreo master.
- [x] Corrigir continuação de linha no comando de validação do workflow de integração (uma única barra invertida).
- [x] Fazer o workflow APK independente instalar sclang, compilar Softcut/32Harmony e validar todos os SynthDefs antes do Gradle/APK.
- [ ] CI permanece sem runner (runner_id=0) nos últimos SHA; aguardar alocação para validar a compilação real.


## HID Android — implementação inicial 2026-11-08

- [x] Implementar inventário de dispositivos de entrada, quatro vports, seleção/atualização de dispositivos, callbacks add/remove e eventos Linux EV_KEY/EV_ABS/EV_REL.
- [x] Mapear teclado físico, botões/axes de gamepad e movimento/scroll/botões de mouse para callbacks HID.
- [ ] HID output reports/raw send ainda indisponíveis; GUIDs não são SDL GUIDs e a classificação de dispositivos não replica integralmente hid_device_class do norns.
- [ ] Validar com teclado, mouse e gamepad USB/Bluetooth na bateria final; CI do HEAD atual está bloqueado por runner_id=0.


## USB Serial Android — 2026-11-08

- [x] Adicionar backend USB Host CDC-ACM com detecção de interfaces CDC Data, endpoints bulk IN/OUT e identificação de fabricante/produto/serial.
- [x] Solicitar permissão Android por dispositivo, abrir/fechar conexão, configurar baud rate via CDC SET_LINE_CODING (8-N-1), controlar DTR/RTS, ler em worker dedicado e serializar callbacks para a thread Lua.
- [x] Implementar o contrato Lua Norns `serial.add_handler`, `serial.send`, `serial.remove_handler`, `serial.refresh`, `serial.cleanup`, callback `add/remove/event` e dados recebidos em blocos de até 255 bytes.
- [x] Preservar bytes arbitrários no caminho Lua com `LuaValue.valueOf(byte[])` e `LuaString.copyInto`, sem conversão textual UTF-8.
- [x] Declarar `android.hardware.usb.host` como recurso opcional; não impedir instalação em aparelhos sem USB Host.
- [x] Adicionar testes unitários para conversão de velocidades termios e contrato de disponibilidade.
- [ ] CI/JUnit/SynthDef/APK: os workflows automáticos de commits `df74ee6` e `21ec105` falharam antes de executar steps (jobs sem steps e logs indisponíveis); não há aprovação de compilação para esta implementação.
- [ ] Validar CDC-ACM real em aparelho Android com adaptador/cabo OTG; nenhum teste físico foi solicitado ou executado.
- [ ] Adicionar drivers USB-serial de fornecedores (FTDI, CP210x, CH340) apenas com protocolos e dependências validados; atualmente não são suportados.
- [ ] O backend fixa 8-N-1: parity, stop bits, flow control e todas as flags termios ainda não são aplicadas. O caminho não deve ser anunciado como serial universal.
- [ ] Revisar desconexão simultânea, múltiplos dispositivos e callback de handler com hardware real na auditoria final.

**Estado do ciclo:** branch `feature/0.4.5-android-audio-driver`; serial CDC-ACM está implementado experimentalmente, não validado em CI nem em aparelho. `main` permanece preservada.

- [x] Corrigir a ponte serial para transportar strings Lua como bytes binários brutos; o callback Lua divide cada leitura em fragmentos de até 255 bytes.


## Telemetria CPU nativa do Poll — 2026-11-08

- [x] O callback Oboe calcula carga de CPU por bloco em relação à duração do bloco de áudio e mantém média exponencial e pico por janela.
- [x] Publicar média/pico por atomics lock-free, sem leitura concorrente de campos não atômicos do driver.
- [x] Expor `NornsSC_ReadCpuMetrics` na libscsynth Android e atravessar JNI até `AudioEngine.pollValue`.
- [x] `poll.set("cpu_avg")` e `poll.set("cpu_peak")` preferem telemetria Oboe quando scsynth está ativo e usam fallback Java caso contrário.
- [x] Atualizar validação de símbolos da integração e adicionar teste de contrato para a cadeia Java/JNI/C++.
- [ ] Executar CI JUnit, probe ARM64 e integração/APK: runners continuam encerrando jobs antes de executar steps; nenhum resultado verde para este SHA.
- [ ] Validar valores, janelas de pico e carga real em dispositivo somente na auditoria final.
- [ ] Entrada mono, pitch e RMS ainda não foram movidos para uma telemetria nativa completa.

- [x] Ajustar limpeza do USB Serial para cancelar também solicitações de permissão pendentes e passar o ID correto do handler em callbacks remove.


## Crow via USB CDC-ACM — 2026-11-08

- [x] Confirmar no firmware oficial Crow a identidade USB: VID:PID `0483:5740`, fabricante `monome & whimsical raps`, produto `crow: telephone line`, CDC ACM.
- [x] Adaptar os atributos USB serial ao contrato Norns `serial._norns.match/configure/add/remove/event`; o número de interface é exposto em hexadecimal (Crow usa `00`).
- [x] Registrar handler Crow específico por fabricante/produto/interface; configurar 115200 baud, 8-N-1 e DTR/RTS pelo backend CDC.
- [x] Implementar `crow.send`, `reset/clear/kill/version/identity`, proxy dinâmico para `input/output/ii/public`, registro de callbacks e parsing de eventos `^^name(...)`.
- [x] Implementar `norns.crow.loadscript/findscript` e callbacks de conexão/remoção; atraso inicial de reset para respeitar o startup do firmware.
- [ ] CI/JUnit/SynthDef/APK da sequência Crow: jobs do SHA `4ec762d` falharam antes de executar steps; jobs do SHA `cdb4091` estão em fila na última consulta.
- [ ] Verificar paridade exata com a API `public` completa, respostas de query, eventos `ii`, modos de entrada e atualização de firmware.
- [ ] Testar com Crow físico/OTG na auditoria final; nenhuma conexão de hardware foi presumida.
- [ ] Grid e Arc continuam fora do escopo CDC-ACM; não tentar abrir como Crow.

**Limite explícito:** a integração Crow é experimental. O transporte suporta CDC-ACM 8-N-1 e o parser de eventos básico; não é prova de compatibilidade total com todas as funções de Crow/ii.


**Atualização de execução — 2026-11-08:** o serial handler foi alinhado ao contrato `serial._norns.match/configure/add/remove/event`; Crow só é associado ao dispositivo oficial `0483:5740`, não a qualquer CDC genérico. A sequência inclui também telemetria CPU nativa do callback Oboe. Os workflows do SHA `3fdde6b` falharam sem executar steps; não há build verde desta revisão.


## Implementação experimental Grid/Arc via serialosc — 2026-11-08

- [x] Criar transporte UDP OSC independente do listener OSC dos scripts; consultar `/serialosc/list` e assinar `/serialosc/notify`.
- [x] Configurar endpoint do aplicativo com `/sys/host`, `/sys/port`, `/sys/prefix` e solicitar `/sys/info`.
- [x] Mapear Grid: LEDs em nível 0–15, all, intensity, rotation e tilt; converter coordenadas OSC zero-based para callbacks Norns one-based.
- [x] Mapear Arc: LEDs/rings, all, segment/range e callbacks de delta/key.
- [x] Adicionar lifecycle UDP e callbacks de descoberta/remoção sem encerrar o backend a cada troca de script.
- [ ] Executar JUnit, build APK e validadores de contrato no CI para o SHA desta implementação.
- [ ] Validar descoberta, notificações/reconexão, Grid e Arc numa rede com serialosc e hardware físico.
- [ ] Implementar batching de LEDs e equivalência antialias de `arc.segment` após confirmar o transporte básico.

Limites conhecidos: cliente serialosc por rede local, não um driver USB/libmonome embutido. As alterações de LED são enviadas imediatamente; `refresh()` não faz batching. `arc.segment` converte ângulos em índices inteiros e usa `/ring/range`; não reproduz o antialiasing fracionário completo.


## Revisão de contrato Grid/Arc — 2026-11-08

- [x] Alinhar o evento de tilt serialosc ao callback Norns `grid.tilt(x, y, z)`: descartar o índice do sensor na fronteira Lua/Java, mantendo o sensor apenas no comando `tilt_enable`.
- [x] Corrigir a semântica booleana Android/Lua para valores `0/1`; em Lua, o número zero é truthy, portanto `enabled and 1 or 0` não é válido para flags numéricas.
- [x] Implementar níveis relativos de Grid/Arc com cache de estado local e níveis absolutos via protocolo serialosc.
- [x] Implementar `arc.segment` com distribuição fracionária entre 64 LEDs, preservando a semântica antialias do norns; o transporte ainda envia atualizações individuais.
- [x] Limpar LEDs e handlers na saída do script; preservar o nome da porta virtual para reconexões.
- [x] Alinhar seleção de portas virtuais e callbacks de remoção com o contrato norns.
- [ ] JUnit, parser Lua, APK e integração ARM64 ainda precisam de CI executado num runner válido.
- [ ] Testes físicos e validação de reconnect/rede serialosc ainda pendentes.


## Atualização de continuidade — 2026-11-09

- Branch de desenvolvimento: `feature/0.4.5-android-audio-driver`.
- HEAD verificado: `519db4927985627d17fe5f7cf4811587335fa08b`.
- `main` permanece em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; não foi alterada.
- Os três workflows do HEAD atual concluíram com sucesso: APK run `42699152488`, integração scsynth/Oboe run `42699152213` e probe ARM64 run `42699152148`. Os três usam exatamente o SHA acima.
- Artefatos não expirados confirmados: `NornsMobile-debug-11`, `NornsMobile-0.4.5-debug-14`, `scsynth-android-imported-14` e `scsynth-android-probe-12`. O artefato da integração é o APK associado ao scsynth importado; não confundir com o APK genérico do workflow separado.
- A aprovação CI confirma compilação/steps automatizados deste SHA, não validação acústica no Samsung.
- Inventário inicial de `boot.lua` encontrou explicitamente `hid.send` não suportado, `io.popen` não suportado, `io.stdin` sem entrada interativa e `os.getenv/os.execute` sem implementação. Tratar como limitações/stubs documentados; não substituir por sucesso simulado.
- Próxima prioridade de implementação: auditoria por contrato das APIs Lua/host, começando por stubs e semânticas parciais em `boot.lua`; implementar somente recursos viáveis no Android e adicionar testes de contrato. Depois avançar nos gaps de engines e ciclo de vida.
- Softcut continua em modo seguro: `native_gate_open=false`, seis vozes nativas bloqueadas, PolyPerc e fallback Java preservados. O roteiro físico anterior encontrou readback nativo zerado/inconsistente em CH1/CH2; isso continua pendente e não pode ser tratado como resolvido pelo CI.
- Nenhum teste físico será solicitado até a conclusão das implementações e validações automatizadas previstas.

### Próximas entregas

1. Classificar APIs expostas como implementadas, parciais, stub/no-op ou ausentes.
2. Corrigir os contratos prioritários e adicionar testes JUnit/Lua para cada alteração.
3. Investigar separadamente o caminho de entrada do SynthDef `SoftcutRecorder` e o readback BufWr, sem abrir o gate.
4. Atualizar este roadmap com SHA, evidência e limitações após cada etapa.


## Compatibilidade Lua — fechamento e ciclo de vida de arquivos — 2026-11-09

- [x] Corrigir `FileR:lines(fmt)`: fechar o handle ao chegar ao EOF e manter o iterador esgotado.
- [x] Corrigir `io.lines(path, fmt)`: fechar o arquivo aberto internamente ao chegar ao EOF e manter o iterador esgotado.
- [x] Cobrir leituras numéricas sequenciais, preservação do buffer quando a gravação falha, repetição segura de `close()`, rejeição de operações após fechar, `io.type` e validação de `seek`.
- [x] Revisar os dois testes de iteradores após falha de CI: o teste de `FileR:lines` agora fornece uma quebra de linha escapada para o LuaJ; o teste de `io.lines` agora inclui a implementação real de `io.lines`, em vez de testar apenas o trecho de `FileR`.
- [ ] Reexecutar JUnit, APK e integração scsynth/Oboe após a correção dos testes.
- [x] O probe ARM64 do commit `5adf47c294d94811448c2f66511f52853c49a1dd` passou (run `42718058311`).
- [ ] APK (run `42718057936`) e integração (run `42718057994`) falharam porque os dois testes de iteradores acima lançaram `LuaError`; ambos pararam na etapa de testes.
- [ ] A cobertura atual é LuaJ/JUnit no runner; ainda não comprova o comportamento do host Android real nem substitui validação física.

`main` permanece inalterada. PolyPerc, fallback Java e `native_gate_open=false` permanecem preservados; nenhuma voz Softcut foi habilitada.


## Compatibilidade Lua — resolução de bibliotecas Norns — 2026-11-09

- [x] Confirmar que `lib/formatters.lua` está incluída nos recursos Lua do aplicativo e que o resolvedor do boot procura `lua/lib/<nome>.lua` para chamadas `require("formatters")`.
- [x] Adicionar teste LuaJ que executa o resolvedor real do boot com o recurso embarcado, em vez de carregar o arquivo de formatters diretamente.
- [x] Cobrir funções de formatação de frequência, duração, percentagem, milissegundos, estados booleanos, pan, filtro multimodo e arredondamento.
- [ ] Confirmar JUnit, APK, integração scsynth/Oboe e probe ARM64 no novo HEAD.
- [ ] A biblioteca `formatters` está coberta; a falha histórica de importação de `oooooo` só será considerada resolvida quando o script e suas demais dependências forem testados em conjunto. O script não está incluído no pacote atual da branch.
- [ ] Continuam pendentes os testes em dispositivo após a conclusão das implementações e validações automáticas.

Preservados: `main`, PolyPerc, fallback Java e `native_gate_open=false`. Nenhuma voz Softcut foi habilitada.


## Contratos explícitos para APIs de processo — 2026-11-09

- [x] Manter `io.popen` explicitamente indisponível no Android, retornando `nil, erro` em vez de simular um pipe.
- [x] Ajustar `os.execute` para retornar `nil` acompanhado de uma mensagem clara de indisponibilidade; não executar comandos de shell no processo do aplicativo.
- [x] Preservar `os.getenv` como `nil` quando a variável não está disponível no ambiente Android; não inventar valores como HOME/PATH.
- [x] Adicionar teste LuaJ/JUnit para os contratos de `io.popen`, `os.execute`, `os.getenv` e EOF de `io.stdin`.
- [ ] Executar JUnit, APK, integração scsynth/Oboe e probe ARM64 no HEAD `c291bae7642a274b64ac6ffc55197cbc5c645ed1` e confirmar o resultado após a alteração documental.
- [ ] `io.stdin` continua sendo um fluxo sem entrada interativa: EOF é intencional até existir um canal de entrada de console real.
- [ ] `hid.send` continua indisponível: o backend Android atual lê eventos HID, mas não implementa relatórios de saída específicos do dispositivo. Não declarar suporte de escrita HID.
- [ ] `os.execute` e `io.popen` não serão habilitados por shell genérico; eventual substituto deverá ser uma API Android restrita, com contrato explícito e testes.



## Ciclo de vida de dispositivos Grid e Arc — 2026-11-10

- [x] Adicionar teste LuaJ/JUnit que executa as funções reais de remoção do boot para Grid e Arc.
- [x] Verificar que a remoção notifica o callback da porta virtual e o callback global do módulo.
- [x] Verificar que a porta perde `device`, `available` e `connected`, e que o dispositivo é removido da coleção.
- [x] Verificar que uma segunda remoção do mesmo ID retorna `false`, sem notificação duplicada.
- [ ] Executar JUnit, APK, integração scsynth/Oboe e probe ARM64 no novo HEAD e registrar os resultados.
- [ ] Cobrir em seguida o ciclo de vida HID (remoção de dispositivo e reatribuição estável de portas) e gamepad (limpeza de estados ao desconectar um dispositivo, inclusive quando há mais de um).
- [ ] A auditoria confirmou que os `remove = noop` são callbacks padrão substituíveis, não a implementação de remoção: os métodos reais `_norns.grid_device_remove` e `_norns.arc_device_remove` já fazem a limpeza. O teste agora protege esse contrato.



## Ciclo de vida HID — 2026-11-10

- [x] Corrigir `hid.update_devices()` para detectar quando uma porta virtual perde seu dispositivo anterior.
- [x] Disparar o callback `port.remove` uma única vez quando o dispositivo associado é removido ou substituído.
- [x] Preservar a atribuição por nome e a ordem determinística por ID ao reconstruir as portas disponíveis.
- [x] Adicionar teste LuaJ/JUnit cobrindo callbacks de módulo, dispositivo e porta, liberação do estado, remoção repetida e reutilização determinística da porta.
- [ ] Executar JUnit, APK, integração scsynth/Oboe e probe ARM64 no HEAD atual; o resultado ainda não foi consultado.
- [ ] Auditar desconexão de gamepad com múltiplos dispositivos: o estado compartilhado não deve ser zerado enquanto outro controle ainda mantém o mesmo botão/eixo pressionado.
- [ ] Testes LuaJ/JUnit verificam a lógica de boot isolada; ainda não substituem instrumentação Android nem validação física de periféricos.



## Estado agregado de múltiplos gamepads — 2026-11-10

- [x] Separar botões, eixos e sinais direcionais por ID de controle Android.
- [x] Recalcular o estado Norns compartilhado pela união dos controles ainda conectados, em vez de sobrescrever o estado global com o último evento recebido.
- [x] Ao desconectar um controle, remover apenas suas contribuições; manter pressionados os botões/eixos que outro controle ainda reporta.
- [x] Tornar a detecção de mudança de direção do eixo individual por dispositivo, evitando que dois controles com o mesmo eixo suprimam eventos um do outro.
- [x] Adicionar teste LuaJ/JUnit para dois controles pressionando o mesmo botão, eixos em direções opostas e desconexões independentes.
- [ ] Executar JUnit, APK, integração scsynth/Oboe e probe ARM64 no HEAD atualizado.
- [ ] Revisar mapeamento Android de botões/eixos para os nomes esperados pela API Norns e ampliar a cobertura com os códigos reais entregues pelo dispatcher Android.



## Gamepad — preservação de teclas direcionais simultâneas — 2026-11-10

- [x] Corrigir o tratamento de D-pad digital: cada direção pressionada é rastreada por dispositivo e eixo, em vez de enviar zero incondicional ao soltar qualquer tecla.
- [x] Ao soltar uma direção, manter a direção oposta ativa se ela ainda estiver pressionada; duas direções opostas simultâneas resultam em eixo neutro até uma delas ser solta.
- [x] Limpar o estado digital do dispositivo ao desconectar o controle.
- [x] Adicionar testes JUnit para pressionamentos simultâneos, repetição idempotente, isolamento entre controles e limpeza por desconexão.
- [ ] Executar JUnit, APK, integração scsynth/Oboe e probe ARM64 no HEAD desta alteração.
- [ ] Confirmar o mapeamento com eventos reais de controles Android em instrumentação; o teste unitário cobre a máquina de estados, não a variedade de firmwares/controladores.


## Gamepad — liberação de entradas ao perder foco — 2026-11-10

- [x] Rastrear botões digitais pressionados por ID de dispositivo para permitir o envio explícito de key-up.
- [x] Rastrear também eixos analógicos e D-pad digital no cache por dispositivo/eixo.
- [x] Adicionar releaseGamepadInputs() para enviar valores neutros aos callbacks Norns e limpar o estado local.
- [x] Acionar a liberação ao sair da aba Norns, pausar a Activity ou perder foco da janela, evitando botões/eixos presos quando o Android não entrega o evento de soltura.
- [x] Adicionar reset completo à máquina de estados do D-pad e teste unitário correspondente.
- [ ] Executar compilação Java/Android, JUnit e workflows no HEAD atual; ainda não há resultado de CI consultado para esta revisão.
- [ ] Revisar o ciclo de vida HID associado ao foco: a neutralização dos callbacks Norns está coberta no código, mas relatórios HID e periféricos físicos ainda precisam de validação de integração.


### Complemento — desconexão física do gamepad

- [x] Remover também o registro local de botões pressionados quando o Android notifica a remoção do dispositivo, evitando que uma liberação posterior recrie acidentalmente um controle já desconectado.
- [ ] Validar em CI a compilação da Activity/host e os testes JUnit adicionados.

## Teclado físico — liberação HID e múltiplos dispositivos — 2026-11-10

- [x] Rastrear quais dispositivos físicos mantêm cada tecla pressionada, sem tratar teclados independentes como uma única origem.
- [x] Agregar os eventos Norns: a soltura de uma tecla em um teclado não libera a mesma tecla enquanto outro teclado ainda a mantém pressionada.
- [x] Ao perder foco ou sair da superfície Norns, enviar EV_KEY com valor zero para cada tecla/dispositivo rastreado, além do key-up Norns.
- [x] Ao desconectar um teclado, remover somente as teclas pertencentes a esse dispositivo e liberar no Norns as teclas que não possuem outra origem ativa.
- [ ] Adicionar testes unitários dedicados para agregação por dispositivo e liberação HID; a alteração ainda não foi compilada nem validada em CI.
- [ ] Confirmar o ciclo completo com teclado físico e dispositivo HID real na auditoria final; nenhum teste físico foi executado.
- [x] Revisão adicional: na desconexão, enviar key-up HID para todas as teclas que aquele dispositivo mantinha, mesmo se outro teclado ainda mantiver a mesma tecla no estado agregado Norns. Correção registrada no commit `d08dae7`; ainda sem compilação/CI.



## Teclado físico — estado isolado e testes de propriedade — 2026-11-10

- [x] Extrair a propriedade de teclas por teclado físico para `KeyboardDeviceState`, uma máquina de estados Java independente do host Lua.
- [x] Manter repetição de tecla do mesmo dispositivo, suprimir pressionamento duplicado de outro teclado e só emitir key-up lógico quando o último proprietário soltar a tecla.
- [x] Na desconexão, produzir separadamente os key-ups HID do dispositivo removido e os key-ups lógicos Norns que realmente ficaram sem proprietário.
- [x] Na perda de foco, gerar um snapshot de liberação com todas as teclas lógicas e todos os pares dispositivo/tecla a neutralizar.
- [x] Adicionar seis testes JUnit para repetição, agregação, desconexão com outro teclado ainda pressionando, desconexão do último proprietário, perda de foco e entradas inválidas.
- [ ] Executar os testes JUnit reais, compilação Gradle/Android e workflows no HEAD atual; ainda não há resultado de CI.
- [ ] Validar o despacho e a ordem dos eventos HID no Android real após concluir as implementações; nenhum teste físico foi executado.
- [x] Ajustar também o encerramento do script: o host agora envia os key-ups HID e Norns na thread Lua antes de `cleanup`, em vez de descartar silenciosamente o snapshot de teclas ainda pressionadas.
- [x] Ordenar a perda de foco para enfileirar primeiro os key-ups HID por dispositivo e depois a liberação lógica Norns.


## Mouse HID — liberação de botões presos — 2026-11-10

- [x] Rastrear botões primário, secundário, terciário, voltar e avançar por ID de dispositivo.
- [x] Evitar duplicar pressionamentos/liberações repetidos e gerar key-up quando o Android perde o evento de soltura.
- [x] Liberar os botões ao perder foco, pausar a Activity, sair da aba Norns, desconectar o mouse e encerrar o script.
- [x] Preservar a ordem de despacho: os eventos HID de soltura são enfileirados antes da remoção do dispositivo ou do cleanup.
- [x] Adicionar testes JUnit para idempotência, isolamento entre dispositivos, desconexão e perda de foco.
- [ ] Executar a suíte JUnit oficial, compilação Gradle/Android e confirmar os workflows no HEAD após estas alterações.
- [ ] Validar o comportamento com mouse físico durante a auditoria final; nenhum teste físico foi executado.


## Migração de desenvolvimento entre repositórios — 2026-11-12

- [x] Estabelecer `joaoevooluti-web/nornsmobileevoo` como destino de continuidade.
- [x] Criar branch isolada `migration/reconcile-jo1ngf-2026-11-12`; não alterar `main` nem a branch funcional durante a reconciliação.
- [x] Portar os ajustes compatíveis de MIDI, OSC, serialosc/Grid/Arc, USB CDC, HID/gamepad, Tape/WAV, Softcut, 32Harmony, APIs Lua, tela e presets.
- [x] Preservar recursos exclusivos do destino, incluindo estado de teclado/mouse/gamepad por dispositivo, fallback FM/Pluck e configuração de assinatura.
- [x] Adicionar workflow JUnit com gate manual/`[run-ci]`, incluindo contratos nativos de roteamento.
- [ ] Fazer revisão de integração, executar JUnit/Gradle e validar SynthDefs após o término do port.
- [ ] Promover a reconciliação para a branch funcional do repositório colaborador.
- [ ] Não solicitar teste físico nem gerar APK antes de concluir as verificações automatizadas.


### Reconcilição entre repositórios — estado da integração

A branch `migration/reconcile-jo1ngf-2026-11-12` do repositório colaborador contém o port funcional de Tape/WAV, MIDI por porta, OSC robusto, serialosc, USB CDC, 32Harmony, parâmetros/presets, tela, APIs Lua e roteamento Softcut/engine. Ela está 78 commits à frente e 0 atrás da branch funcional de destino. A integração ainda não foi promovida porque os testes automatizados e a compilação não foram executados; nenhum workflow foi iniciado.


## Corridas USB CDC, identidade HID e ciclo de vida Lua — 2026-11-13

- [x] Serializar o snapshot de portas e a sequência completa de closeAll() com abertura/permissão USB; impedir que uma porta aberta concorrentemente sobreviva ao stop da VM.
- [x] Manter escrita e fechamento sob o mesmo lock por porta; ampliar o teste determinístico para três escritas concorrentes, concorrência com close e rejeição de qualquer escrita que só alcance o lock após a desativação.
- [x] Associar sessões HID ao descritor Android além do ID reutilizável; uma substituição com descritor diferente fecha a sessão anterior e anuncia a nova na mesma fila Lua.
- [x] Ao detectar mudança de descritor, enfileirar liberação de teclado/mouse/gamepad, remoção HID antiga e anúncio da substituta nessa ordem.
- [x] Comparar o descritor do InputDevice associado ao evento Android com a sessão capturada antes de aceitar teclado, mouse ou gamepad.
- [x] Vincular callbacks enfileirados à geração da VM e ao executor que os recebeu; stop/restart e falha de boot invalidam trabalho pendente do executor anterior.
- [x] JUnit `43479131638` (commit de código `2547f6bfc5874b620e4375f02e951422865f77d9`) concluído com sucesso: **557 testes, 0 falhas, 0 ignorados**, 1,952 s; artefato integral `norns-mobile-junit-reports-346` (ID `14063673511`). Índice e as 61 páginas de classe foram lidos; nenhuma linha de teste falhou.
- [ ] O descritor Android é uma identidade observável do dispositivo/endpoint, não uma prova criptográfica de hardware; descritores indistinguíveis continuam limitados pelo que a API InputDevice fornece.
- [ ] Sem APK, probe, Oboe ou validação física nesta etapa.


### Validação automatizada — 2026-11-13 (corridas USB/HID/Lua)

- [x] Workflow JUnit `43479131638`, run `346`, commit testado `2547f6bfc5874b620e4375f02e951422865f77d9`: 557/557 testes, zero falhas e zero ignorados, duração 1,952 s.
- [x] Artefato `norns-mobile-junit-reports-346`, ID `14063673511`, SHA-256 `2f1dab3795b1664b1792b750294014904480cd36ab5f59db12414a97f741e275`; índice, pacote e 61 relatórios HTML de classes inspecionados integralmente.
- [x] Resultados focados: `LuaDeviceCallbackGateTest` 12/12; `LuaHostDeviceCallbackContractTest` 8/8; `UsbPortCallbackGateTest` 6/6; `UsbCdcSerialBridgeTest` 31/31; `UsbLifecycleGateTest` 10/10; `HidLuaRuntimeTest` 8/8; `UsbSerialLuaRuntimeTest` 6/6.
- [x] Contratos nativos de saída/Softcut, compilação dos SynthDefs e validação dos SynthDefs embarcados concluídos no workflow JUnit.
- [x] Workflows para o mesmo SHA: APK `43479131798` skipped; probe `43479131617` skipped; integração Oboe `43479131732` skipped. Nenhum APK, probe ou Oboe foi executado.
- [x] `main` confirmado sem alteração em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; nenhuma validação física foi feita ou inferida.
- [ ] Próxima matriz: callbacks de remoção Android atrasados após reatribuição do mesmo ID, gamepad/teclado/mouse sob stop/restart, e corrida de close USB com fila de escritas em backpressure.


### Continuação — callbacks HID atrasados e backpressure CDC (2026-11-13)

- [x] Matriz determinística de reuso do mesmo ID HID com descritores distintos: callback capturado pela sessão anterior é rejeitado, a sessão substituta permanece válida após a finalização tardia da antiga.
- [x] Contrato de stop/restart da VM: callbacks pendentes de teclado, gamepad e mouse capturados antes de `LuaHost.stopInternal()` não atravessam `hidCallbackGate.clear()` e não são aceitos pela nova sessão.
- [x] Extraído `UsbCdcWritePump`, usado pelo caminho de escrita real do CDC: preserva offsets e ordem em transferências parciais, limita chunks a 16 KiB, limita a três respostas consecutivas sem progresso e aborta ao fechar a porta.
- [x] Novos testes unitários determinísticos para backpressure transitório/permanente, falha de transporte, payload inválido, limite de tamanho, escrita parcial e close entre chunks.
- [ ] Executar workflow JUnit para este commit, ler o relatório integral e corrigir qualquer falha antes de marcar a etapa como concluída.
- [ ] Limitação restante: corridas com InputDevice Android real e USB CDC físico continuam não verificadas; não executar testes físicos nesta etapa.


### Correção de asserção JUnit — 2026-11-13

- [x] Run 43481135621 executou 565 testes e identificou uma única falha em UsbCdcSerialBridgeTest linha 16: a asserção esperava a formatação sem espaços da expressão de chunk, enquanto o helper usa constante nomeada e estilo Java formatado.
- [x] Relatório HTML do artefato norns-mobile-junit-reports-347 (ID 14064633870) inspecionado; a falha é de localizador textual do teste, não do comportamento do writer. Os 6 testes novos do UsbCdcWritePumpTest e os 2 cenários novos do LuaDeviceCallbackGateTest passaram.
- [ ] Atualizar a asserção para verificar o contrato sem depender de espaçamento, repetir JUnit e inspecionar o novo relatório integral.


### Fechamento da matriz HID/USB — 2026-11-13

- [x] JUnit verde no HEAD `d5067b6a5fe82085d4695788ab75b2d562cf2e5e`: 565/565, sem falhas ou ignorados.
- [x] Suítes focadas: `LuaDeviceCallbackGateTest` 14/14; `UsbCdcSerialBridgeTest` 31/31; `UsbCdcWritePumpTest` 6/6.
- [x] Workflows APK `43481949780`, probe `43481949802` e Oboe `43481949408` concluídos como `skipped`; nenhum deles foi executado.
- [x] `main` preservada em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`.
- [ ] Próxima etapa: exercitar concorrência real entre close/shutdown e rajadas de múltiplas escritas com uma fila bounded, incluindo cancelamento e callbacks tardios do lado Lua; manter testes de hardware fora desta etapa.


## Bibliotecas upstream de padrões, relógio e alocação de vozes — 2026-11-13

- [x] Incorporar as bibliotecas oficiais `lib/beatclock.lua`, `lib/pattern_time.lua` e `lib/voice.lua` do repositório monome/norns, mantendo suas APIs públicas.
- [x] Corrigir `util.time()` para usar `_host.now()`, o relógio monotônico de alta resolução em segundos. O uso anterior de `os.time()` tinha resolução de um segundo e distorcia a gravação/reprodução de intervalos de padrões.
- [x] Ampliar `LuaLibraryCompatibilityTest` para carregar as novas bibliotecas e executar contratos de alocação rotate/release, gravação/limpeza de pattern_time e ticks/start/stop de BeatClock.
- [x] JUnit/Gradle aprovado no commit `2a681efa43d87e2fdeeec70a1d810702f824b932`: 569 testes, 0 falhas, 0 ignorados; relatório integral `norns-mobile-junit-reports-355` (artefato 14069358983) inspecionado.
- [ ] Próxima dependência: completar bibliotecas de UI/seleção somente depois de implementar as APIs host requeridas por elas (por exemplo, file_info, menu e callbacks de navegação); não incluir módulos com stubs que aparentem funcionar mas não entreguem comportamento real.
- [ ] Compatibilidade MIDI clock externa do BeatClock depende de validar a entrega de mensagens MIDI clock e seleção de portas no runtime; teste de ticks locais não comprova sincronização com hardware.


### Audio API — metadados WAV para fileselect (2026-11-13)

- [x] Adicionado `WavReader.inspect(File)`: percorre apenas os cabeçalhos RIFF/WAVE, valida formato/canais/taxa de amostragem/alinhamento e pula o payload com seek; não mapeia nem aloca o áudio.
- [x] Exposto `_host.audio_file_info` (operação 67) e `audio.file_info(path)` em Lua, retornando `channels, samples, sample_rate`; caminhos inválidos e arquivos não suportados retornam três zeros para que o seletor os trate como não-áudio.
- [x] Testes para metadados estéreo, quantidade de frames, taxa, profundidade, WAV truncado e contrato de retorno Lua.
- [x] JUnit no commit `2a681efa43d87e2fdeeec70a1d810702f824b932`: 569/569, 0 falhas, 0 ignorados. Compilação nativa, validação SynthDef e fixtures upstream também passaram.
- [x] Implementado o contrato funcional de `norns.menu`: status/toggle, init sem alternância implícita, set/get dos callbacks key/enc/redraw/refresh e despacho de key/enc conforme o modo ativo.
- [x] Embarcado `lib/fileselect.lua` upstream. Corrigido o índice entre a lista filtrada e a lista real de arquivos para seleção e preview; saída idempotente, cancelamento de preview pendente e parada do Tape ao sair.
- [x] `_norns.cleanup()` encerra um fileselect carregado antes de cancelar clocks e reiniciar estado, restaura os callbacks e desativa o modo de menu.
- [x] Testes comportamentais LuaJ cobrem navegação em diretórios, filtro de áudio, preview, debounce, cancelamento, restauração de callbacks e cleanup com preview ativo.
- [x] JUnit run 43497721371 / artefato norns-mobile-junit-reports-361: 571 testes, 0 falhas, 0 ignorados; relatório HTML integral inspecionado.
- [x] `main` preservada em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; nenhum teste físico executado. APK, probe ARM64 e integração Oboe permaneceram skipped.


### Menu e seletor de arquivos — 2026-11-13

- [x] Contrato `norns.menu` funcional com quatro callbacks separados e modo de menu explícito; eventos key/enc são encaminhados ao callback ativo.
- [x] `lib/fileselect.lua` upstream integrado com `audio.file_info`, listagem e filtros, navegação hierárquica, preview Tape e cancelamento.
- [x] Corrigido mapeamento entre `display_list` filtrada e `list` original para evitar selecionar/preview do arquivo errado quando filtros ocultam entradas.
- [x] Cleanup idempotente: cancela timers, impede preview tardio, interrompe preview ativo, restaura callbacks e encerra o seletor no cleanup do script.
- [x] JUnit run 43497721371: 571/571, 0 falhas, 0 ignorados; relatório `norns-mobile-junit-reports-361` inspecionado. A compilação e validação SynthDef e verificações nativas do workflow também passaram.
- [x] APK, probe ARM64 e Oboe permaneceram skipped; nenhum teste físico executado.


## Audio API — controle de saída DAC Java — 2026-11-13

- [x] Implementar `audio.level_dac(level)` no caminho de saída Java: ganho mestre entre 0 e 1, aplicado ao buffer estéreo após o ponto de gravação Tape, sem alterar o material gravado.
- [x] Manter explícita a limitação do backend: quando o engine SuperCollider nativo está ativo, a API retorna `false` e registra que o controle DAC nativo ainda não está disponível; não simular controle do Oboe.
- [x] Adicionar cobertura JUnit para ganho unity, escala estéreo, mute, clamp e valores não finitos.
- [ ] Executar JUnit/Gradle no commit desta etapa e inspecionar o relatório integral.
- [ ] Permanecem ausentes/sem implementação real: headphone gain, monitor/ADC/DAC routing, reverb/compressor e pitch; não anunciar compatibilidade antes de existir mixer/roteamento correspondente.


## Entrada ADC — ciclo de vida da captura Android (2026-11-13)

- [x] Extraído `AudioInputLifecycleGate` para identificar gerações de captura. Uma leitura ou finalização atrasada de uma sessão antiga não pode publicar dados nem desativar uma sessão substituta.
- [x] `AudioInput.start()/stop()` sincronizam a transição de sessão; uma nova captura não começa enquanto a thread anterior ainda estiver viva.
- [x] O encerramento solicita `AudioRecord.stop()` para desbloquear `read()`, mas a própria thread de captura é responsável por `release()` no `finally`. Isso evita liberar o recorder enquanto a leitura nativa ainda está em andamento.
- [x] Revalidação da geração após `read()` impede que um bloco retornado tardiamente seja escrito no ring buffer depois de stop/detach lógico.
- [x] JUnit `43522795854` no commit `53a3898bc2e22e641a11a973cb451dc8a6ebc8ca`: 586 testes, 0 falhas, 0 ignorados; relatórios HTML de todas as 71 entradas do artefato `norns-mobile-junit-reports-370` inspecionados. `AudioInputLifecycleGateTest` 4/4 e `AudioInputRingBufferTest` 5/5.
- [ ] Teste de `AudioRecord` real permanece fora do escopo até a fase de validação física.


- [x] Ajustado o overflow do ring ADC SPSC: o produtor agora escreve somente em slots livres e descarta o excedente do lote quando o buffer está cheio. Isso evita sobrescrever memória que o consumidor possa estar lendo simultaneamente; a política de overflow é explícita (drop-newest) e coberta por teste.


### Reforço SPSC e tratamento de erros ADC — 2026-11-13

- [x] Adicionado teste concorrente producer/consumer com 50.000 amostras, verificando ordem integral e ausência de corrupção sob wrap-around e pressão contínua.
- [x] A captura ADC agora encerra a sessão diante de qualquer código de erro negativo retornado por `AudioRecord.read()`, evitando loop ocupado para códigos negativos não previstos explicitamente.
- [ ] JUnit pendente para esta revisão incremental; manter o teste real de microfone fora do escopo até a etapa de validação física.


## JUnit #518 — fixture correction — 2026-11-14

- [x] Inspecionado o job completo e o artefato HTML de JUnit do run `43557800813`: as verificações nativas, compilação/validação dos SynthDefs e compilação JNI/Faust passaram; a suíte JVM concluiu 623 testes com uma única falha.
- [x] Causa isolada: `BootLuaSyntaxTest.fileLinesClosesItsHandleWhenIteratorReachesEof` passava uma quebra de linha literal para uma string Lua delimitada por aspas simples, gerando `unfinished string` antes do teste real de EOF.
- [x] Corrigido o escape no fixture em `b78f38b223b2f49951775dee485d84a6d430b68a`.
- [ ] Reexecutar JUnit contra o SHA corrigido e inspecionar o relatório integral; a execução do run #518 não valida o commit corretivo.
- [ ] Continuar a auditoria de `audio.add_params`: a UI ParamsView já consome ParamSet, mas os parâmetros globais upstream incluem rotas e controles que não existem ou são deliberadamente fail-closed no backend Android. Não expor controles que aparentem funcionar sem confirmação do backend.


## JUnit #519 e avanço Audio API — 2026-11-14

- [x] JUnit #519 / run `43613752792` no commit `b78f38b223b2f49951775dee485d84a6d430b68a`: **623 testes, 0 falhas, 0 ignorados**. Artefato HTML completo baixado e lido; `BootLuaSyntaxTest` 20/20, `TapeCoreTest` 11/11 e `AudioRoutingApiContractTest` 15/15.
- [x] Verificações nativas de áudio/hardware, compilação e validação SynthDef, fixtures upstream e compilação JNI/Faust passaram nesse run.
- [x] Implementado `audio.level_cut_rev(level)` como quarto send Faust independente para Softcut Java. A captura é pós-pan/pré-master e isolada do buffer master; o send não altera o ganho de saída nem a matriz de gravação voice-to-voice.
- [x] O caminho nativo scsynth permanece fail-closed para esse send; não foi criada rota duplicada para Oboe.
- [x] JNI valida a capacidade estéreo do buffer Softcut e o Faust soma L/R separadamente ao bus de reverb.
- [x] Teste novo em `SoftcutCoreTest` verifica o tap isolado e a independência do ganho master; contrato atualizado em `AudioRoutingApiContractTest`.
- [ ] JUnit/CI contra o commit desta implementação ainda pendente; validar antes de avançar para novas APIs.
- [ ] Permanecem indisponíveis com retorno explícito: headphone gain físico, restart/recompilação sclang e JACK/Crone port routing. `audio.add_params` continua gerenciado pela UI Android; não criar controles que representem capacidades sem suporte real.
- [ ] PolyPerc e fallback Java preservados; as seis vozes Softcut não são habilitadas como caminho nativo audível sem prova independente de estéreo e ausência de duplicação.


### Softcut reverb send — CI integration follow-up — 2026-11-14

- [x] Atualizado `tools/validate-softcut-output-routing.py` para validar a chamada nova com buffer Softcut estéreo separado e zerado a cada bloco. Os runs #531 e #532 tinham parado apenas no assert antigo do verificador.
- [x] Atualizado `tools/test-faust-audio-effects.cpp` para a nova assinatura JNI/DSP e para exercitar os quatro envios de reverb, com teste de isolamento para fonte não selecionada. O run #535 confirmou que os validadores Python e o bus engine->Softcut passaram, mas encontrou o fixture C++ antigo; o teste foi corrigido em `cd7905d`.
- [x] Reforçado `audio.level_cut_rev`: retorna `false` se scsynth estiver native/loading ou se Softcut tiver ownership nativo/incerto/fallback pendente. Isso evita aceitar um send Java quando o bloco auxiliar está silenciado pelo handoff.
- [ ] Aguardar run #537 (`43615277857`, SHA `cd7905d90583fff666a396707e4fd00facc29d9d`), ler logs nativos e relatório JUnit integral; corrigir e validar qualquer nova falha.


## Audio API — mixer ParamSet e compatibilidade restante — 2026-11-14

- [x] Confirmado que `audio.add_params()` já registra LEVELS, REVERB, COMPRESSOR e SOFTCUT no ParamSet único renderizado por `ParamsView`; não há UI duplicada.
- [x] Ampliada a cobertura LuaJ de todos os controles e callbacks de mixer: níveis, reverb, compressor, entradas Softcut, estado salvo e rollback quando o backend recusa uma ação.
- [x] Mantido MONO como única opção exposta enquanto o AudioRecord Android entrega um canal ADC. `audio.monitor_stereo` continua retornando indisponibilidade no backend; nenhuma falsa rota estéreo foi criada.
- [x] Corrigidas as mensagens de `audio.headphone_gain` e `audio.restart` para explicar a limitação Android correta. Os IDs continuam disponíveis quando aplicável, sem expor controles funcionais fictícios.
- [x] Auditoria restante: mixer Java, pitch, Tape, Softcut, reverb/compressor, file metadata e helpers de nível já possuem implementação real; JACK/Crone routing, headphone gain e restart seguem explicitamente indisponíveis por ausência de equivalente seguro.
- [x] JUnit #539 / run `43615876183`, SHA `5394e517e725a2d353f274a3768a820446a5e09d`: **624/624**, 0 falhas e 0 ignorados. Relatório HTML completo (70 classes) e logs nativos/JNI/SynthDef lidos.
- [ ] Aguardar e inspecionar a execução JUnit da revisão de cobertura expandida e mensagens de APIs indisponíveis; não considerar essa revisão validada antes do artefato.
- [x] Preservados main, PolyPerc, fallback Java, exclusividade de saída e bloqueio de seis vozes Softcut até prova de roteamento estéreo e ausência de duplicação.


## Stratum / MiRings / MiClouds — início da compatibilidade (2026-11-15)

- [x] Identificado e fixado o upstream de Stratum em `mrmattallison/stratum`, commit `8c7ca83e0b4ff8f3b20f170a68d7b782cf98ba74`.
- [x] Identificado e fixado o upstream de mi-UGens em `v7b1/mi-UGens`, commit `10f6824f68ad63475f67dfe3fe5016e2d2634f84`.
- [x] Adicionada a primeira camada de fixtures CI: script Lua, módulos e `Engine_Stratum.sc`; testes verificam parsing LuaJ, requisitos simultâneos MiRings/MiClouds, mapeamento de comandos e ausência de fallback silencioso para PolyPerc.
- [x] Implementado o dispatcher Lua Android para `Stratum`, com node nativo `NornsStratum`, os 25 comandos Rings/Clouds e estado fail-closed (sem PolyPerc/Java como substituto).
- [x] Implementada a verificação dos plugins no diretório privado do app; o script só seleciona `engine.name = "Stratum"` quando ambos os .so estão presentes e o scsynth está ativo.
- [x] Criada a fonte do SynthDef Android estéreo serial Rings → Clouds e a etapa CI que instala as classes MiRings/MiClouds para compilá-lo.
- [x] Os arquivos upstream Stratum não são empacotados no APK por ausência de licença explícita no repositório; CI baixa fixtures pinadas, e o usuário importa o script original.
- [x] MiRings.so e MiClouds.so compilados para Android ARM64, validados como ELF AArch64 e empacotados no APK de integração #768.
- [x] `NornsStratum.scsyndef` compilado com as classes Mi-UGens e incluído no APK; integração #768 validou os payloads nativos, o gate de saída e o roteamento Softcut exclusivo.
- [ ] Ainda falta comprovar em runtime Android o carregamento efetivo dos dois UGens, criação do node Stratum e alteração audível dos 25 controles. Compilação/empacotamento não equivale a smoke test de áudio.
- [x] APK de integração #768 gerado e artefato com SHA-256 conferido; a validação sonora real continua pendente.


## Continuação — ciclo de vida de scripts Norns após falha de inicialização (2026-11-16)

- A auditoria do caminho `LuaHost.boot()` → `_norns.load_script()` encontrou uma lacuna: quando o chunk Lua falhava depois de executar parte do código de topo, `failStart()` encerrava o executor e os transportes Java, mas descartava a VM sem chamar `_norns.cleanup()`. Timers/polls/clocks e recursos registrados por módulos Lua podiam não receber a rotina normal de aposentadoria.
- Implementado `cleanupFailedLuaStart()`: se a VM e `_norns.cleanup` existirem, a rotina normal de cleanup é executada após invalidar callbacks pendentes e antes de `globals = null`. A limpeza Java de MIDI, USB serial, OSC, SuperCollider e áudio permanece como backstop.
- O caminho de parada normal não foi alterado. PolyPerc, fallback Java, Oboe/AudioTrack e Softcut não foram modificados; as seis vozes continuam bloqueadas.
- Adicionado `LuaHostStartupFailureCleanupTest` para verificar a ordem de invalidação → cleanup Lua → descarte da VM e preservar a ordem de parada normal.
- Commit de implementação: `39edbf3c32a510ab4118bcb09b1b18b6796a811d`; teste: `1898fc07e523262e0dce2ecdcc2b1922b6fc3692`. JUnit completo disparado pelo marcador `[run-ci]`; aguardar e ler o relatório integral antes de marcar a etapa como validada.


## Compatibilidade Norns — cleanup de script one-shot (2026-11-16)

- A revisão do ciclo de vida encontrou uma diferença em relação ao upstream: `_norns.cleanup()` invocava o callback global `cleanup` sem removê-lo. Chamadas repetidas ou reentrantes poderiam executar a finalização do script mais de uma vez.
- O callback agora é capturado e substituído por `noop` antes da invocação protegida. Isso preserva a limpeza de módulos/transportes e impede duplicação do cleanup do script.
- Próxima validação: teste LuaJ comportamental chamando `_norns.cleanup()` repetidamente e confirmando que o callback do script é executado uma única vez; executar JUnit completo e inspecionar o artefato.
- Áudio, PolyPerc, fallback Java, Oboe/AudioTrack e roteamento Softcut não foram alterados. As seis vozes permanecem bloqueadas.


## Ciclo integral de scripts Norns — load/init/callback/cleanup/reload (2026-11-16)

- [x] JUnit #618 concluído com sucesso: 645 testes, 0 falhas, 0 ignorados; relatório HTML completo e logs do job inspecionados. Compilação JNI/nativa, SynthDefs Softcut, fixtures upstream e testes JVM passaram.
- [x] Expandido `NornsScriptLifecycleTest` para executar trechos reais do `boot.lua`: `norns.script.load()`, `_norns.load_script()`, `init()`, `redraw()`, dispatch `key/enc`, `cleanup()`, `norns.rerun()` e `norns.script.clear()`.
- [x] O cenário alterna dois scripts e verifica que o script anterior recebe cleanup uma vez, callbacks após reload apontam apenas para o novo script, rerun limpa/reinicializa e clear aposenta callbacks e identidade do script.
- [ ] Aguardar JUnit completo desta expansão; ler o artefato integral e corrigir falhas antes de marcar o ciclo como validado.
- [x] Preservados `main`, PolyPerc, fallback Java, ownership de saída e gate de seis vozes Softcut; nenhuma alteração de roteamento ou ativação de reprodução foi feita.


### Registro da validação do ciclo completo

- JUnit #619 (commit intermediário `fab1869`) falhou na compilação Java do teste: aspas da string Lua de fixture não estavam escapadas. Não foi falha do runtime Norns.
- Corrigido o fixture Java e confirmado o trecho versionado em `NornsScriptLifecycleTest.java` no commit `b0eda784c200eb27f2046729763aff2becf75933`.
- JUnit #620 foi ignorado pelo filtro de execução; não é validação.
- JUnit #621 está executando para o commit corrigido `b0eda784c200eb27f2046729763aff2becf75933`. A etapa observada mais recentemente é “Install Android build components”; o resultado final e o relatório permanecem pendentes.


## JUnit #622 — ciclo de vida de scripts Norns (2026-11-16)

- [x] Run `43884435213` / JUnit #622 concluído no branch `migration/reconcile-jo1ngf-2026-11-12`, commit `df90d6dc404cd5d0d9d8a24d64dd614de8eef073`: **646 testes, 1 falha, 0 ignorados**. Verificações nativas, JNI/Faust e compilação/validação dos SynthDefs passaram; a falha ocorreu apenas na suíte JVM.
- [x] Artefato `norns-mobile-junit-reports-622` (ID `14232299953`) baixado e relatório HTML integral inspecionado. Única falha: `NornsScriptLifecycleTest.loadDispatchCleanupAndReloadFollowTheCompleteScriptLifecycle`, erro LuaJ por `encoder_last_time` nil. Diagnóstico: o teste dividia o runtime de produção em chunks Lua separados e perdia os upvalues lexicais `menu_mode`/`encoder_last_time`; não era regressão do dispatch de produção.
- [x] Corrigido o harness para compilar menu, callbacks, cleanup, load e API script no mesmo chunk, mantendo `strip_bom` global no fixture.
- [x] Reforçado o runtime: callbacks `key/enc/redraw/init` são invalidados no início do cleanup; falha em `init()` ou `redraw()` executa cleanup dos recursos parciais e faz o load retornar `false`.
- [x] Adicionados cenários comportamentais para falha durante `init()`, callbacks tardios após cleanup e cinco recargas sucessivas com aposentadoria one-shot por geração.
- [ ] Aguardar a nova execução JUnit no commit corretivo e inspecionar o relatório HTML completo antes de marcar a validação como verde.
- [x] `main` preservada em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; alterações limitadas ao branch `migration/reconcile-jo1ngf-2026-11-12`.


## JUnit #623 — diagnóstico e correção do harness (2026-11-16)

- [x] Run `43886191609` concluído no commit `2cb9d3ff515befadab75de4ce7a2e6e16421e27f`: **649 testes, 4 falhas**; todos os checks nativos, JNI/Faust, compilação e validação de SynthDefs passaram. Falhas isoladas em `NornsScriptLifecycleTest`.
- [x] Relatório HTML completo do artefato `norns-mobile-junit-reports-623` (ID `14233698840`) e log integral do job inspecionados.
- [x] Causa comum das quatro falhas identificada no harness: o stub de `safe()` devolvia o valor de retorno do callback (normalmente `nil`) em vez do booleano `ok` retornado pela implementação real de `boot.lua`. Como o loader usa `not safe(init)` para detectar falha, callbacks de init bem-sucedidos eram interpretados como falha; a exceção do caso negativo também era propagada pelo stub, em vez de ser registrada e convertida em `false`.
- [x] Corrigido o stub para reproduzir o contrato real: `pcall`, log de erro via `host.log` e retorno booleano de sucesso.
- [ ] Executar novamente a suíte completa no novo commit e inspecionar o relatório integral. Só marcar o ciclo como aprovado após zero falhas.
- [x] `main` permanece em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; alterações somente na branch `migration/reconcile-jo1ngf-2026-11-12`.


## JUnit #624 — ajuste da expectativa de eventos de tecla (2026-11-16)

- [x] Run #624 / 43887253260, commit fe54673af10904d34253d7bc75bf51623f768cf9: 649 testes, 1 falha, 0 ignorados. Verificações nativas, SynthDefs, fixtures upstream e JNI/Faust passaram.
- [x] Artefato HTML norns-mobile-junit-reports-624 (ID 14234653006) baixado e inspecionado; todas as 75 classes foram conferidas. Quatro dos cinco testes de NornsScriptLifecycleTest passaram.
- [x] Falha isolada em repeatedReloadsRetireEachScriptGenerationExactlyOnce: a fixture de tecla incrementa one_key por n + z e envia cinco eventos (1,1); o resultado correto é 10, não 5. Não era callback duplicado nem falha no runtime.
- [x] Corrigida a expectativa antes e depois de norns.script.clear() para one_key == 10, mantendo one_enc == 5 e a verificação de que eventos posteriores ao clear não alteram os contadores.
- [ ] Executar JUnit completo no commit corrigido e inspecionar o próximo relatório HTML; só marcar o ciclo integral como aprovado após zero falhas.
- [x] main confirmada em a9b027fc1d87d815fd62760fd4fac9d49abcda7d; alterações restritas à branch migration/reconcile-jo1ngf-2026-11-12.


## JUnit #625 — ciclo de vida Norns aprovado (2026-11-16)

- [x] Run #625 / 43888145115, commit testado `d84b40a8e57251eee0f0b8de011d5ea9579fe036`: conclusão **success** em todas as etapas do workflow.
- [x] Relatório HTML `norns-mobile-junit-reports-625` (artefato 14234559746) baixado e inspecionado integralmente: **649 testes, 74 classes, 0 falhas, 0 ignorados, 100% de sucesso**. Soma das classes confere com o total do relatório.
- [x] `NornsScriptLifecycleTest`: 5 testes, 0 falhas, 0 ignorados; cenários de init com falha, callbacks tardios, ciclo load/dispatch/cleanup/reload e cinco recargas repetidas aprovados.
- [x] Todas as etapas auxiliares passaram: contratos nativos de áudio/hardware, compilação e validação dos SynthDefs Softcut/Stratum, fixtures upstream e compilação JNI/Faust.
- [x] Sem falhas pendentes neste ciclo. O commit `87e4e6d67efe5d1a0ea66eee5cb26b0c2be9995f` contém apenas o ajuste do workflow para executar a suíte integral; o código de testes/runtime validado está no ancestral `d84b40a8e57251eee0f0b8de011d5ea9579fe036`.
- [x] `main` preservada em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; trabalho restrito à branch `migration/reconcile-jo1ngf-2026-11-12`.


## Stratum — encerramento determinístico de recursos (2026-11-16)

- Revalidado o ponto de partida 69a5f73f31523ce53efbcd682d92b9f90e98668e; main permanece em a9b027fc1d87d815fd62760fd4fac9d49abcda7d.
- Relatório JUnit #626 / run 43888238678 do commit 87e4e6d67efe5d1a0ea66eee5cb26b0c2be9995f baixado e inspecionado: 649 testes, 74 classes, 0 falhas, 0 ignorados, 100%. NornsScriptLifecycleTest (5/5) e StratumCompatibilityTest (7/7) passaram.
- Ampliado o teste comportamental do script upstream Stratum: além de inicializar módulos reais, exercitar MIDI/Rings/Clouds e chamar cleanup(), agora verifica que cada tarefa criada pelo scheduler foi cancelada no teardown.
- JUnit #627 / run 43911136539 foi ignorado por falta do marcador [run-ci]. JUnit #628 / run 43911262922 executou 649 testes: 648 passaram e 1 falhou no novo fixture de teardown Stratum. O relatório HTML foi lido; causa: escape duplicado de newline gerou barra invertida literal no código Lua. Corrigido no commit subsequente e revalidar integralmente.
- Próxima auditoria: ciclo Stratum load → init → callbacks → cleanup → reload no mesmo estado Lua, verificando que callbacks/tarefas da geração anterior não atingem a geração nova; depois ampliar a matriz às engines nativas e Java.
- Continuar a comparação função-a-função de audio, clock, params e screen; APIs sem equivalente Android devem continuar falhando explicitamente, sem simular roteamento JACK/Crone.
- PolyPerc, fallback Java, ownership Oboe/AudioTrack e bloqueio de seis vozes Softcut mantidos. Nenhum teste físico solicitado.


## Ciclo integrado Stratum (2026-11-16)

- JUnit #628 (run 43911262922): relatório HTML integral, 649 testes, 1 falha em StratumCompatibilityTest causada por newline com escape duplicado no fixture Lua. Corrigido em ee7f1279af4f6defd0af499cb7797af8daf262de; aguardar JUnit #629.
- Ampliado pinnedStratumInitRunsWithRealModulesAndRoutesRingsCommands para executar duas gerações no mesmo estado Lua: load/init/callbacks/cleanup/reload/init/callbacks/cleanup, verificando seleção Stratum, engine-ready, reset Crow por geração e cancelamento de todas as tarefas do scheduler.
- Esta validação cobre script/módulos Lua upstream com host mockado. Não afirma prova de plugin Mi-UGens em ARM64, saída de áudio Oboe nem integração física Crow/MIDI.
- Próximo: endurecer isolamento de callbacks tardios entre gerações (não apenas invocação segura do callback retido), repetir matriz nas engines Java/PolyPerc e engines nativas, e concluir comparação upstream de audio/clock/params/screen.


## JUnit #629/#630 — scheduler Stratum e isolamento entre gerações (2026-11-16)

- [x] Runs #629 (43912102613, commit ee7f1279af4f6defd0af499cb7797af8daf262de) e #630 (43912238473, commit a4cd024bc87c493875b2d501993aaa68ffdee1b6) concluídos com falha na etapa JVM. Ambos os relatórios HTML foram baixados e inspecionados: 649 testes, 1 falha, 0 ignorados em cada execução; a falha única foi StratumCompatibilityTest.pinnedStratumInitRunsWithRealModulesAndRoutesRingsCommands.
- [x] Causa real identificada no fixture: a asserção exigia cancelar todas as tarefas de clock.run. O Stratum upstream agenda também trabalho finito (retry MIDI após 250 ms e gates com duração); essas corrotinas devem terminar naturalmente e não são recursos persistentes que cleanup() tenha de cancelar. O teste confundia tarefas one-shot com os loops persistentes.
- [x] Ajustado o contrato do teste para exigir o cancelamento explícito dos dois loops persistentes por geração (t_section e redraw), sem impor cancelamento às corrotinas temporizadas. Os artefatos anteriores demonstram que as outras 648 classes/testes passaram; isso não substitui a nova execução após a correção.
- [x] Fortalecido NornsScriptLifecycleTest: retém funções key/enc da geração 1, invoca-as depois do load da geração 2 e exige que os contadores da geração 2 permaneçam inalterados. Também verifica reset/start do adaptador de engine nas recargas.
- [ ] Reexecutar JUnit integral com [run-ci], baixar e conferir o HTML completo; não marcar como verde até 0 falhas.
- [ ] Ampliar testes comportamentais específicos para callbacks assíncronos de engines Java/native, incluindo callback de load nativo que chega após troca/cleanup; os atuais contadores de engine no harness são stubs e não provam execução de DSP/hardware.
- [ ] Continuar auditoria função-a-função de audio, clock, params e screen; registrar cada API como implementada, parcial ou indisponível, com testes comportamentais onde possível.
- [x] main conferida em a9b027fc1d87d815fd62760fd4fac9d49abcda7d; alterações somente na branch migration/reconcile-jo1ngf-2026-11-12. Nenhum teste físico Samsung solicitado.


## JUnit #631 — correção do scheduler Stratum validada (2026-11-16)

- [x] Run #631 / 43925784455, commit testado 9e113e28e9b6720e0420c491e6bd8ed0ed8001dc: **success**. Relatório HTML do artefato norns-mobile-junit-reports-631 (ID 14256416683) baixado e lido: **649 testes, 0 falhas, 0 ignorados, 100% de sucesso**; pacote org.nornsmobile.app, duração 2.541 s. O workflow também passou contratos nativos, SynthDef, fixtures upstream e compilação JNI/Faust.
- [x] A distinção entre loops persistentes e corrotinas temporizadas corrigiu a única falha dos runs #629/#630.
- [x] JUnit #633 / run 43925869112 no commit 355601f13c3031a2294bca4490c63307e22b3fcd concluído com sucesso. Relatório HTML do artefato 14256536984 inspecionado integralmente: 649 testes, 74 classes, 0 falhas, 0 ignorados, 100% (2.713 s). A cobertura de callback retido entre gerações e reset/start do adaptador de engine passou.
- [ ] JUnit #634 / run 43926971310 testa uma prova mais específica para Stratum: callbacks de input atrasados passam pelos dispatchers de produção extraídos de boot.lua e devem resolver a geração ativa, sem chamar referências key/enc retidas da geração anterior.


## JUnit #634 — ajuste de ordenação do fixture Stratum (2026-11-16)

- [x] Run #634 / 43926971310, commit bbfb974551660320e12f4e77216397213a450b73: falhou com 648/649 testes aprovados. HTML baixado e inspecionado; falha única em StratumCompatibilityTest (LuaError: attempt to call nil, linha 296). Diagnóstico: o primeiro patch inseriu o exercício do dispatcher atrasado antes de reter os callbacks da geração 1 e antes de carregar a geração 2; portanto stale_key_dispatch ainda era nil.
- [x] Corrigida a ordem no commit 996da1faec17e0f23fec1f452701f6ac02e2f750: executar callbacks da geração 1, reter old key/enc e dispatchers, fazer cleanup/reload/init da geração 2, então invocar os dispatchers retidos e exigir que somente os wrappers da geração atual sejam chamados.
- [ ] JUnit #635 / run 43927009936 está em andamento para validar a correção; ler o relatório HTML integral e corrigir qualquer falha remanescente.


## JUnit #635 — isolamento de dispatch Stratum entre gerações aprovado (2026-11-16)

- [x] Run #635 / 43927009936 no commit 996da1faec17e0f23fec1f452701f6ac02e2f750 concluído com sucesso. Artefato norns-mobile-junit-reports-635 (ID 14255664616) baixado e inspecionado: **649 testes, 74 classes, 0 falhas, 0 ignorados, 100% de sucesso** (2.200 s). Todas as 74 páginas de classe foram analisadas; soma conferida.
- [x] StratumCompatibilityTest: 7/7 aprovados. A prova carrega o script upstream em duas gerações, retém os dispatchers de input de produção extraídos de boot.lua e callbacks key/enc da primeira geração; depois do reload, chama os dispatchers retidos e confirma que só os wrappers da geração ativa recebem os eventos, enquanto os callbacks antigos permanecem em zero chamadas.
- [x] NornsScriptLifecycleTest: 5/5 aprovados, incluindo callbacks retidos entre gerações e reset/start do adaptador de engine em reload. Limite explícito: o adaptador continua mockado; ainda não comprova callbacks assíncronos reais de scsynth nem DSP/hardware Android.
- [x] Verificações nativas de áudio/hardware, compilação e validação dos SynthDefs Softcut/Stratum, fixtures upstream e JNI/Faust passaram.
- [ ] Próxima etapa: adicionar teste comportamental específico para callback de conclusão de load nativo chegando depois de engine._reset_native()/troca de script, e exercitar o caminho Java de PolyPerc/FM/Pluck versus o caminho nativo de TestSine/32Harmony/Stratum sem afirmar integração física.


## Engine lifecycle hardening + remaining Norns API audit (2026-11-16)

- [x] A partir do HEAD solicitado `8552d43c325c80a88f6b8f5fe3b3e397863d6585`, a conclusão das engines nativas passou a usar barreira `/sync` com `sync_id` por geração. O `/done /d_recv` não tem identificador e deixou de ser aceito como prova isolada de conclusão; replies `/synced` de gerações aposentadas não concluem a carga atual.
- [x] O polling retém o estado de falha `/fail /d_recv` até a barreira `/synced` correspondente; callbacks de conclusão recebem também o token de geração e não executam se a geração mudou.
- [x] Teste comportamental LuaJ novo em `EngineNativeLifecycleTest`: reset durante load TestSine, troca para FM Java, novo load PolyPerc, `/done` antigo e `/synced` antigo, prova de que a geração nova permanece `loading`, e conclusão somente pelo `sync_id` atual. Outro cenário cobre falha nativa seguida da barreira e callback de fallback.
- [x] Matriz Java ampliada: FM, Pluck e PolyPerc quando scsynth não está disponível chamam callback de load sincronamente no estado Java, sem agendar polling nativo. PolyPerc e fallback Java permanecem intactos.
- [x] `norns.system_cmd(command)` agora existe como API explicitamente restrita: retorna `false` no Android e registra a razão; nenhum comando de shell é executado. `shutdown` e `reboot` também continuam fail-closed. Teste LuaJ de runtime adicionado em `NornsSystemApiContractTest`.
- [ ] JUnit #645 / run `43936980914`, commit `9e1d806e2a49027994c91e7ef6d4a956a28179d6`, está pendente. Não declarar a cobertura nova aprovada até baixar e inspecionar o relatório HTML integral.
- [ ] Próximas lacunas prioritárias: engine load/fallback para SimpleDelayKolor e Stratum sob replies atrasadas; clock Link (API upstream expõe `clock.link.get_number_of_peers()`, ainda sem backend Link Android); screen clipping/blend e equivalência visual; widgets params File/Text e módulos Lua upstream ainda ausentes (containers, elca, envgraph, filtergraph, graph, intonation, listselect, reflection, textentry, timeline).
- [x] `main` verificada em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; alterações somente na branch de migração. Nenhum teste físico Samsung solicitado; Softcut six-voice playback permanece bloqueado.


## Retired native replies + SimpleDelayKolor lifecycle (2026-11-16)

- [x] A fila de syncs aposentados agora retém os IDs das barreiras nativas que ainda podem responder depois de `engine._reset_native()` ou timeout. Replies `/synced` antigos removem seu ID; um `/fail /d_recv` recebido antes de uma barreira aposentada não contamina o load da geração atual.
- [x] O fluxo SimpleDelayKolor foi alinhado ao mesmo contrato: cada uma das duas fases de SynthDef espera seu próprio `/synced`; falhas são mantidas até a barreira da fase, e resets/fallbacks aposentam o sync pendente.
- [x] `EngineNativeLifecycleTest` agora inclui cenário de carga nativa Java/PolyPerc com `/fail`, `/done` e `/synced` antigos e cenário SimpleDelayKolor com as duas barreiras de fase.
- [ ] JUnit #652 / run `43937568476` no commit `8fc592958744295f9526082b14353b806690efbf` está pendente. O run anterior #645 / `43936980914` ainda aparece em andamento no passo de compilação dos SynthDefs Softcut, sem relatório publicado; não contar como validação.


## JUnit #645–#661: diagnóstico do harness e revalidação (2026-11-16)

- [x] JUnit #645 / run `43936980914`, commit `9e1d806e2a49027994c91e7ef6d4a956a28179d6`: relatório integral (77 HTML, 75 classes) conferido; 653 testes, 2 falhas, 0 ignorados. Uma falha era o harness não carregar `engine.list_commands()` da implementação real; a outra era uma asserção de tarefas/fallback incidental, removida após confirmar callbacks Java síncronos.
- [x] JUnit #654 / run `43937672289`, commit `a7dc45ed0d31fd66eec46a5be57c323f49ac9eb9`: relatório integral conferido; 655 testes, 6 falhas, 0 ignorados. A causa comum das 5 falhas de lifecycle foi o metatable de `engine` transformar campos privados ausentes (`_retired_native_sync_ids`, `_native_sync_id`, etc.) em closures de comando. A falha restante era a asserção estática de KOLOR ainda usando o parser antigo de `sync_id`.
- [x] JUnit #657 / run `43938054564`, commit `062824a4cddb765c60c451b34aaab7179cc6dbdeb`: HTML integral conferido (77 HTML, 75 classes); repetiu 655 testes e as mesmas 6 falhas, pois ainda precedia a correção do metatable.
- [x] Correções no código: o `__index` do engine retorna nil para campos iniciados por `_` e mantém proxies apenas para comandos públicos. O harness agora carrega `engine.list_commands()` real e testa que campos privados ausentes continuam nil. A asserção KOLOR foi alinhada ao parser atual. A contagem Stratum foi corrigida para 25 comandos.
- [x] JUnit #661 / run `43938573408` concluiu com 656 testes e uma falha residual em `/fail /d_recv` de geração aposentada; a correção e a revalidação estão registradas na seção seguinte.


## JUnit #661: último caso de resposta /fail aposentada (2026-11-16)

- [x] Run #661 / `43938573408`, commit `f84ce53c4fb07baf87d8804020dc5479cc6dbdeb`: relatório integral (77 HTML, 75 classes) conferido; 656 testes, 1 falha, 0 ignorados. Cinco dos seis testes de `EngineNativeLifecycleTest` passaram; o caso restante mostrou que o polling genérico ainda marcava `/fail /d_recv` de uma carga aposentada como falha da engine atual.
- [x] Correção no commit `22180cb3dcf13e625148defd16937f94649681ff`: o polling genérico agora, como o caminho SimpleDelayKolor, descarta `/fail /d_recv` enquanto existir uma barreira `/sync` aposentada pendente; o `/synced` antigo remove o ID aposentado.
- [ ] JUnit #662 / run `43939389970` está em andamento para revalidar o isolamento de `/fail`, `/done` e `/synced` antigos, além de toda a suíte.


## JUnit #662 — barreiras nativas por geração aprovadas (2026-11-16)

- [x] Run #662 / 43939389970, commit testado 22180cb3dcf13e625148defd16937f94649681ff: workflow concluído com sucesso; verificações nativas de áudio/hardware, instalação Mi-UGens, compilação/validação dos SynthDefs Softcut, fixtures upstream, compilação JNI/Faust e JUnit passaram.
- [x] Artefato HTML norns-mobile-junit-reports-662 (ID 14261832920, SHA-256 3a7d7f2453d0ef998aafb44eab6ecb004598c57b85e6510ba231cddb24490bbb) baixado e conferido: 77 páginas HTML, 75 páginas de classe, 656 testes, 0 falhas, 0 ignorados, 100% de sucesso (3.305 s). A soma das classes confere com o resumo; nenhuma página/linha de falha encontrada.
- [x] EngineNativeLifecycleTest: 6/6; inclui reset/troca durante load nativo, replies antigos /fail, /done, /synced, falha aguardando barreira, duas fases SimpleDelayKolor, Harmony/Stratum e fallback Java.
- [x] Logs completos do job conferidos: externalNativeBuildDebug e testDebugUnitTest concluíram com BUILD SUCCESSFUL; artefato foi publicado. Avisos de depreciação do Gradle/Node e API Java não causaram falhas.
- [x] A correção do run #661 foi validada: /fail /d_recv anterior é ignorado enquanto a barreira aposentada estiver pendente.
- [x] main permanece em a9b027fc1d87d815fd62760fd4fac9d49abcda7d; trabalho segue somente na branch migration/reconcile-jo1ngf-2026-11-12.
- [ ] Próxima suíte acrescenta teste de fase SimpleDelayKolor aposentada durante reset/troca e idempotência de callbacks diante de /synced duplicado; aguardar JUnit integral antes de marcar essa nova cobertura como validada.


## API audit follow-up — clock/script/screen (2026-11-16)

- Confirmado na referência upstream: clock.set_source também prevê Link; o guia de clocks documenta Crow como fonte. Android continua apenas internal/MIDI; Link, peers e Crow clock input seguem explicitamente pendentes.
- O módulo script upstream documenta clear/load/run/metadata; o runtime expõe load/clear/rerun. Implementar run/metadata somente após definir semântica e persistência compatíveis.
- screen tem ampla cobertura de desenho, buffers e blend; falta validar equivalência visual de clipping/rasterização e comportamento de low-battery (restrito por hardware). Próxima etapa de API audit: testes de contrato comportamentais para essas lacunas e para params File/Text, sem transformar APIs indisponíveis em stubs de sucesso.
- Referência: https://monome.org/docs/norns/api/ e módulos clock, script e screen; guia https://monome.org/docs/norns/clocks/ .


## JUnit #663 — fixture de lifecycle e correção de escape (2026-11-16)

- Run 43943360511, commit 86a33b2a6e8115609b7392a102bda3aebf0c5154: 658 testes, 2 falhas, 0 ignorados. Relatório HTML do artefato 14264031505 baixado; 77 páginas HTML e 75 classes verificadas. As duas falhas ficaram nos novos cenários EngineNativeLifecycleTest e apresentaram LuaJ 'unexpected symbol 92 (\\)' na primeira linha do fixture.
- Causa: os literais Java dos novos fixtures tinham \u005c\u005c+n (barra invertida duplicada), emitindo uma barra invertida literal antes do código Lua. Não era falha de runtime de engine.
- Corrigido no commit 5ed76db87ba1000ea13ae3652074180a9fb8e416: os literais agora usam o escape Java de newline correto. Aguardar JUnit seguinte e inspecionar relatório HTML integral antes de considerar os dois novos cenários aprovados.


## JUnit #664 — correção da expectativa PolyPerc (2026-11-16)

- Run 43943913109 / JUnit #664, commit testado 5ed76db87ba1000ea13ae3652074180a9fb8e416: **658 testes, 1 falha, 0 ignorados**; relatório HTML baixado e conferido: 77 páginas, 75 classes. As verificações nativas, SynthDefs, fixtures upstream e JNI/Faust passaram.
- O cenário de KOLOR aposentado falhou por uma expectativa incorreta do próprio teste: o caminho nativo PolyPerc representa as vozes em engine._native_voices e não cria engine._native_node. O teste tentava ler .name de um node inexistente; não era falha do lifecycle nem do runtime.
- Corrigido no commit 50437ab180f29097e480e2dc12d1976c6f31eca7: agora a asserção exige engine.name == PolyPerc, estado loading e engine._native_node == nil, refletindo o contrato real. O cenário de /synced duplicado passou em #664.
- Aguardar JUnit seguinte e relatório HTML integral para validar a expectativa corrigida.


## JUnit #665 — lifecycle engine ampliado aprovado (2026-11-16)

- [x] Run 43944690853 / JUnit #665, commit testado 50437ab180f29097e480e2dc12d1976c6f31eca7: workflow concluído com sucesso. Contratos nativos de áudio/hardware, Mi-UGens, compilação/validação dos SynthDefs Softcut, fixtures upstream, JNI/Faust e JUnit passaram.
- [x] Artefato norns-mobile-junit-reports-665 (ID 14265271549, SHA-256 e963adcf15fd4280f240b0ee9191d62cfdded57cce387d16259b9d1211077caf) baixado e inspecionado integralmente: 77 HTML, 75 páginas de classe, **658 testes, 0 falhas, 0 ignorados, 100%** (2.045 s). A soma das classes confere com o resumo; nenhuma falha encontrada.
- [x] EngineNativeLifecycleTest: **8/8**. Passaram os cenários de reset/troca com fase SimpleDelayKolor aposentada, callback idempotente diante de /synced duplicado, duas fases KOLOR, falha com sync fence, callbacks de 32Harmony/Stratum, stale load nativo e fallback Java/PolyPerc.
- [x] A correção da expectativa PolyPerc foi validada: o estado loading é verificado sem presumir um _native_node inexistente; o backend representa PolyPerc por _native_voices.
- [x] main permanece em a9b027fc1d87d815fd62760fd4fac9d49abcda7d; trabalho restrito à branch migration/reconcile-jo1ngf-2026-11-12. PolyPerc/fallback Java preservados; seis vozes Softcut continuam bloqueadas até prova independente de estéreo e ausência de duplicação. Nenhum teste físico solicitado.


## Script lifecycle generation fence — 2026-11-16

- Corrigido um caminho assíncrono no loader: a conclusão tardia de `engine._start_native` podia chamar `softcut._start_native` depois de cleanup ou reload do script que originou o callback.
- `_norns._script_generation` agora avança no início de cleanup e de cada load; o callback captura a geração e só inicia Softcut se ainda pertencer ao script ativo.
- Novo teste comportamental mantém a conclusão nativa pendente, faz cleanup, dispara o callback aposentado e verifica que Softcut não inicia; repete com reload e confirma que somente a conclusão da geração atual pode iniciar Softcut, sem duplicar a inicialização após cleanup.
- Validação CI/JUnit pendente para este commit; não inferir aprovação antes do relatório HTML integral.
- PolyPerc, fallback Java, ownership Oboe/AudioTrack e gates Softcut não foram alterados. Seis vozes continuam bloqueadas até prova independente de estéreo e ausência de duplicação. Nenhum teste físico solicitado.


## JUnit #666–#668 — callback fence e correção do fixture (2026-11-16)

- [x] JUnit #666 / run `43945954465`, commit `f85aa6451d1ace95f5394206a5ee224f64d2369e`: relatório integral (80 entradas ZIP, 75 relatórios de classe) conferido; 659 testes, 1 falha, 0 ignorados. A única falha foi `NornsScriptLifecycleTest.delayedEngineCompletionCannotStartSoftcutAfterScriptCleanupOrReload`: LuaJ encontrou `unexpected symbol 92 (\\)` na primeira linha do fixture, porque o literal Java emitiu uma barra invertida literal em vez de uma quebra de linha Lua.
- [x] Correção do fixture nos commits `3c9c0cc` e `8b4aa45`: normalizado para o escape Java de newline. A falha era do teste, não da barreira de geração em produção.
- [x] JUnit #668 / run `43946197029`, commit `8b4aa45b07d47a897dc80fb909c22aab07f8aeca`: workflow concluído com sucesso; contratos nativos, SynthDefs, fixtures upstream, JNI/Faust e JUnit passaram. Relatório `norns-mobile-junit-reports-668` (ID `14263929209`, SHA-256 `3c41bfa252dd7df747bcff95540ed249221d7cca409d09a4512a4e3c658b12c9`) baixado e conferido integralmente: 80 entradas, 75 classes, 659 testes, 0 falhas, 0 ignorados, 100% (2.448 s). `NornsScriptLifecycleTest` 6/6; `EngineNativeLifecycleTest` 8/8; `SoftcutOutputOwnershipTest` 27/27.
- [x] Logs do job conferidos: `externalNativeBuildDebug` e `testDebugUnitTest` finalizaram com `BUILD SUCCESSFUL`; o artefato foi publicado. Avisos de depreciação Node/Gradle não causaram falhas.
- [ ] Próxima implementação: equivalência comportamental de `norns.script.metadata` e `norns.script.load(nil/"")`; `norns.script.run` permanece pendente até separar a fase de load do engine/init sem duplicar init ou iniciar Softcut duas vezes.


## JUnit #669 e script.run — 2026-11-16

- [x] JUnit #669 / run `43947207781`, commit `ab6baef1643b958a7bace613c16ab680fa0fe447`: relatório HTML integral inspecionado. ZIP com 84 entradas; 77 páginas HTML (índice, pacote e 75 classes); 661 testes, 0 falhas, 0 ignorados, 100% (3.130 s). A soma das 75 classes confere com o índice.
- [x] `NornsScriptLifecycleTest` 8/8, `ScreenApiContractTest` 14/14, `SoftcutOutputOwnershipTest` 27/27, `EngineNativeLifecycleTest` 8/8. Os logs confirmam `externalNativeBuildDebug` e `testDebugUnitTest` com `BUILD SUCCESSFUL`; artefato JUnit publicado.
- [x] `norns.script.metadata(filename)` e `norns.script.load(nil/"")` implementados no commit testado: leitura restrita ao Dust privado, parsing do bloco inicial de comentários (CRLF incluído), sem executar o arquivo; reload do script ativo, e false quando não há script ativo.
- [ ] `norns.script.run()` separado da fase de leitura/execução do chunk. Implementação em andamento: uma geração carregada só pode executar engine/init/redraw uma vez; callback nativo só inicia Softcut se a geração e a fase run ainda forem atuais. Aguardar JUnit do HEAD posterior e relatório integral.
- [ ] Auditoria params File/Text: conferir seleção/valor persistido/ação e limites do caminho; Screen: manter cobertura de buffers/regions/peek/poke/blend/fontes e identificar lacunas visuais que exigem teste de imagem. Não afirmar equivalência visual completa por testes estáticos.
- PolyPerc e fallback Java preservados. Ownership Oboe/AudioTrack e gates Softcut inalterados; seis vozes permanecem bloqueadas até prova independente de roteamento estéreo e ausência de duplicação. `main` preservada; sem testes físicos.



## Params File/Text e Screen — auditoria atualizada (2026-11-16)

- [x] Separado o diretório permitido do valor selecionado em `params:add_file(id, name, path)`: File inicia com valor vazio, mantém `path` independente e persiste somente o arquivo selecionado. Commit `dacb269d9290ac2ba981c96d94b165648ea52a38`.
- [x] Acrescentados testes LuaJ para File/Text: tipo upstream, caminho File, basename em `params:string`, callback de alteração, valores e round-trip de persistência. Aguardando JUnit #674 para validar.
- [ ] A UI Android ainda precisa de controles interativos de File e Text. O ramo genérico atual é somente leitura; implementar editor de texto e seletor de arquivos com raiz Dust confinada, ação Lua e cancelamento sem sobrescrever o valor.
- [ ] Screen: contratos #669 14/14 aprovados; falta golden-image coverage para clipping, transforms, composição e rasterização. `update_low_battery` permanece false por ser função específica de hardware Norns.
- JUnit #674 / run `43948484251`, commit `0c1bd6dc1f9fa218c40288ce789331b61378114a`: em execução; aguardar conclusão e relatório HTML integral antes de validar `script.run` e File/Text.
- HEAD de trabalho: `0c1bd6dc1f9fa218c40288ce789331b61378114a`; branch `migration/reconcile-jo1ngf-2026-11-12`. `main` confirmado em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`. PolyPerc, fallback Java e ownership Oboe/AudioTrack preservados; Softcut de seis vozes continua bloqueado até prova estéreo e anti-duplicação. Nenhum teste físico solicitado.



## JUnit #702 — AndroidX e Screen golden tests (2026-11-16)

- [x] Continuação no HEAD `bc35c0f46d68b1b684a86ed083b8813ca0e5a055`, exclusivamente na branch `migration/reconcile-jo1ngf-2026-11-12`.
- [x] JUnit #702 / run `43953135174`: job `Android JVM unit tests` concluído com sucesso. Logs integrais conferidos: verificações nativas de áudio/hardware, instalação Mi-UGens, compilação e validação dos SynthDefs Softcut, fixtures upstream, compilação JNI/Faust ARM64, `testDebugUnitTest` e `compileDebugAndroidTestJavaWithJavac` concluíram sem erro.
- [x] Artefato `norns-mobile-junit-reports-702` (ID `14271525600`) baixado e inspecionado integralmente: 80 entradas ZIP, 77 páginas HTML (índice, pacote e 75 classes), **667 testes, 0 falhas, 0 ignorados, 100%** (2.052 s). A soma dos relatórios de classe confere com o índice.
- [x] Suítes relevantes: `NornsScriptLifecycleTest` 9/9 (inclui `norns.script.run()` público/idempotente por geração); `ParamSetCompatibilityTest` 29/29 (File/Text e UI); `ScreenApiContractTest` 15/15; `SoftcutOutputOwnershipTest` 27/27.
- [x] Migração AndroidX validada em compilação: runner `AndroidJUnitRunner`, classes instrumentadas com `AndroidJUnit4`/ `InstrumentationRegistry`, tarefa `compileDebugAndroidTestJavaWithJavac` com `BUILD SUCCESSFUL`.
- [ ] **Execução visual Screen ainda pendente:** #702 compilou os testes instrumentados de pixel/golden, mas não executou `connectedAndroidTest` nem rodou em emulador/dispositivo. A equivalência visual em runtime não está aprovada; não solicitar teste Samsung nesta fase.
- [x] File/Text: editor multiline de Text e seletor File interativo integrado a `param_set_value`; seletor confinado ao diretório resolvido em Dust, com validação canônica de raiz/filhos e sem importação arbitrária por SAF. Serialização preserva newline, tab, CR e barras escapadas.
- [x] Screen: golden tests instrumentados adicionados para níveis de cinza/peek, retângulo preenchido + translação e escrita de região com clipping de borda; compilação AndroidX confirmada, execução visual runtime pendente.
- [x] `main` permanece preservada no SHA esperado `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; o trabalho permanece na branch de migração.
- [x] PolyPerc e fallback Java preservados. Nenhuma alteração de produção no caminho de áudio/Softcut nesta etapa; ownership Oboe/AudioTrack exclusivo, mute/drain e gates de Softcut mantidos. As seis vozes continuam bloqueadas até prova independente de roteamento estéreo e ausência de duplicação.
- [ ] Próximo: executar os testes AndroidX instrumentados Screen em ambiente Android compatível; ampliar golden coverage para clipping/transforms/composição; continuar auditoria das APIs Norns ainda ausentes, sem relaxar os gates de áudio.


## Stratum — correção do menu LFO no runtime Android (2026-11-16)

- [x] Diagnóstico do log do Samsung: a falha em `stratum_lfo.lua:144` era `_menu.rebuild_params()`; o objeto privado `_menu` não existia no `boot.lua` Android.
- [x] Corrigido `boot.lua` com ponte compatível para `params.on_change` / `host.params_changed()`, preservando qualquer implementação `_menu.rebuild_params` já existente.
- [x] Removido o mock artificial de `_menu` do teste de inicialização Stratum. O fixture carrega o trecho de produção, exige `_menu` inicialmente ausente e verifica que a atualização de parâmetros chega ao host Android.
- [x] JUnit #709 / run `43980596446`, tentativa 1: relatório HTML integral inspecionado; 667 testes JVM, 0 falhas, 0 ignorados. StratumCompatibilityTest 7/7, EngineStratumCommandTest 7/7 e NornsScriptLifecycleTest 9/9.
- [x] A compilação Screen instrumentada terminou, mas o teste no emulador não iniciou: ADB perdeu `emulator-5554` e terminou em `Timeout waiting for emulator to boot`. É falha de infraestrutura do runner, não uma falha de asserção Screen.
- [x] APK de integração #768 gerado no HEAD `6711d96eef1b10e35146ac06dff789577982a868`; contém a ponte _menu, MiRings.so, MiClouds.so e NornsStratum.scsyndef. SHA-256: `92f85e1b9075d25015b3cae68e733946963f676dc90c94ced417615d2da4e282`.
- [ ] Reexecução da etapa Android do JUnit #709, tentativa 2, em andamento; após concluir, inspecionar o novo resultado Screen e manter pendente a validação sonora real do Stratum.
- PolyPerc, fallback Java, roteamento Oboe/AudioTrack e gates Softcut não foram alterados; Stratum continua sem substituição por PolyPerc quando não disponível.


## JUnit #709 — tentativa 2 e correção do runner Android (2026-11-17)

- Run `43980596446`, tentativa 2, commit testado `dc4ba06837874f93e12a29b6cb4a65482a0c4aea`: relatório HTML integral baixado e conferido (80 arquivos; 75 relatórios de classe). **667 testes JVM, 0 falhas, 0 ignorados**; `StratumCompatibilityTest` 7/7, `EngineStratumCommandTest` 7/7 e `ScreenApiContractTest` 15/15.
- `compileDebugAndroidTestJavaWithJavac` passou. `ScreenSurfaceGoldenTest` **não executou**: o emulador Android 35 encerrou antes do boot. Log fatal do QEMU: `Not enough space to create userdata partition. Available: 6437.03 MB ... need 7372.80 MB`; ADB depois reportou `emulator-5554 not found`. Não é falha de pixels nem aprovação de golden.
- Correção de CI na branch: `tools/configure-ci-emulator.sh` limita a partição de dados do AVD para 2048 MB, suficiente para o app e o sandbox dos testes. `.github/workflows/unit-tests.yml` executa o script via `pre-emulator-launch-script` antes de iniciar o emulador. Novo workflow [run-ci] disparado pelo commit de workflow; aguardar e inspecionar o relatório instrumentado.
- Diagnóstico de Stratum: fluxo de produção confirmado estaticamente: seleção exige os dois plugins presentes no diretório privado e scsynth iniciado; carrega `NornsStratum.scsyndef`, aguarda a barreira OSC `/sync`, cria `NornsStratum` e só então descarrega o engine Java. Falha de load/timeout encerra Stratum e não seleciona PolyPerc. O SynthDef liga MiRings serialmente a MiClouds e emite estéreo para bus 0.
- Limite importante: os testes JVM exercitam LuaJ, dispatcher e contratos/mock de `sc_api`; ainda não comprovam que os dois UGens foram efetivamente registrados pelo scsynth Android, que `/s_new` resultou em node ativo, nem resposta audível/paramétrica. Próximo passo: instrumentar confirmação de `/n_go` e falhas `/fail /s_new` para o node Stratum, validar load real de MiRings/MiClouds e manter estado fail-closed até a confirmação.
- main conferida e preservada em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`. PolyPerc/fallback Java, ownership Oboe/AudioTrack e gates Softcut não foram modificados; seis vozes permanecem bloqueadas.


## Stratum — separação explícita do renderer Java (2026-11-17)

- A mensagem Android `Engine 'Stratum' não está implementada; saída silenciada...` era emitida por `AudioEngine.drain()` quando `SynthCore.setEngine("Stratum")` rejeitava um engine que pertence ao grafo nativo scsynth. Ela não indicava, por si só, falha do carregamento nativo; era um aviso incorreto/ambíguo da camada Java.
- Corrigido `LuaHost.java`: `Stratum`, `32Harmony` e `SimpleDelayKolor` são mapeados explicitamente para `none` no SynthCore Java. O engine nativo continua sendo carregado pelo caminho scsynth; até a confirmação, o renderer Java permanece silencioso. PolyPerc e engines Java suportadas mantêm sua seleção normal.
- Novo teste `EngineStratumCommandTest.nativeOnlyEnginesStaySilentInJavaSynthCoreWithoutReplacingPolyPerc` cobre os três engines nativos e garante que PolyPerc/TestSine/FM não sejam reescritos.
- Limite runtime restante: `sc_api.new` hoje confirma que o pacote OSC `/s_new` foi enviado, não que o servidor criou o node. O fluxo aguarda `/sync` da SynthDef, mas não correlaciona uma eventual resposta `/fail /s_new`; adicionar confirmação explícita de node/erro é a próxima proteção antes de declarar Stratum áudio pronto.
- JUnit #715 está pendente para validar a mudança. A correção de espaço do AVD também está em validação nas execuções #711/#712; ScreenSurfaceGoldenTest segue sem execução confirmada.


## JUnit #715 — falha de permissão do script de AVD e correção (2026-11-17)

- Run `44109911760`, commit `73492fa24b43af16dbab3033255b03b97da2ee12`: 668 testes JVM passaram, 0 falhas, 0 ignorados; os testes de contrato nativo, SynthDefs, JNI/Faust e compilação dos testes AndroidX também passaram.
- O passo instrumentado não executou os golden pixels. Log exato: `tools/configure-ci-emulator.sh: Permission denied`; o runner invocava o arquivo diretamente, pois o commit de criação não carregou bit executável. Como o script não alterou o AVD, QEMU repetiu a falha por partição de dados de 7372.80 MB contra 6436.95 MB disponíveis.
- Corrigido o workflow: `pre-emulator-launch-script` agora chama explicitamente `bash tools/configure-ci-emulator.sh`, sem depender de permissão Unix do arquivo. JUnit #716 foi disparado para validar a execução real e o ScreenSurfaceGoldenTest.
- Estado de Screen: API contracts JVM 15/15 e instrumentação compilada; golden pixel runtime continua pendente.


## JUnit #716 — userdata AVD ainda não respeitou config.ini (2026-11-17)

- Run `44112928711`, commit `de12e0e1faaa2ed32c5ab980ca21fe4ce138c45c`: 668 testes JVM passaram, 0 falhas; compilação AndroidX passou. `ScreenSurfaceGoldenTest` não iniciou.
- O script pré-boot executou corretamente via Bash e confirmou `disk.dataPartition.size=2048M`, mas o QEMU ainda pediu 7372.80 MB com apenas 6436.80 MB disponíveis. Portanto, a propriedade do AVD não alterou o tamanho efetivo da userdata image derivada do system image.
- Correção adicional: opção explícita `-partition-size 2048` adicionada ao comando do emulador (documentação oficial Android define essa opção para o tamanho da partição de dados do sistema). O run #717 validará o override; não considerar Screen aprovado até execução instrumentada completa.


## JUnit #717 — emulador inicia; processo de instrumentação cai (2026-11-17)

- Run `44115981768`, commit testado `8c853d795841a6f5f20b4cf29e384399eb9320f4`: relatório HTML integral do JVM baixado e conferido (80 arquivos; 668 testes, 0 falhas, 0 ignorados, 100%). StratumCompatibilityTest 7/7, EngineStratumCommandTest 8/8, NornsScriptLifecycleTest 9/9, ScreenApiContractTest 15/15 e SoftcutOutputOwnershipTest 27/27.
- A correção de userdata funcionou o suficiente para iniciar o Android Emulator 35 e manter `emulator-5554` até o encerramento. Portanto, o erro anterior de QEMU por partição de 7372.80 MB e a perda de ADB foram superados nesta tentativa.
- `connectedDebugAndroidTest` falhou com `Test run failed to complete. Instrumentation run failed due to Process crashed`; o relatório da tarefa não indica início/resultado de nenhum teste (0 testes concluídos). Logo, `ScreenSurfaceGoldenTest` segue **não executado**, sem aprovação nem falha de assertions.
- Causa exata do crash ainda não comprovada: o log de job não contém `FATAL EXCEPTION`/stacktrace do processo Android e o workflow anterior só publicava os relatórios JVM. Adicionado ao workflow, na branch, dump de `adb logcat -d -v threadtime`, propriedades, processos e pacote instalado, além de publicar `reports/androidTests` e `outputs/androidTest-results` mesmo em falha. O próximo run [run-ci] deverá revelar o crash real antes de qualquer correção de produção.
- Diagnóstico atual: o runner chegou ao passo Android e executou o Gradle por 5m44s; não atribuir o crash a Stratum, ScreenSurface ou JNI sem evidência de logcat. Não foram feitas alterações de produção de áudio nesta etapa.
- `main` permanece preservada; PolyPerc, fallback Java, ownership Oboe/AudioTrack e gates Softcut permanecem intactos. Seis vozes continuam bloqueadas.


## JUnit #709 — tentativa 2 e auditoria adicional do runtime Stratum (2026-11-17)

- Run `43980596446`, job `133736857103`: tentativa 2 terminou por falha do runner/emulador (`emulator-5554` ausente durante o boot; ADB não conseguiu localizar o dispositivo). Não houve execução instrumentada; `ScreenSurfaceGoldenTest` continua **não executado** nesta tentativa.
- Artefato `junit-709-reports.zip` (80 arquivos HTML) inspecionado: 667 testes JVM, 0 falhas, 0 ignorados (100%). StratumCompatibilityTest 7/7; EngineStratumCommandTest 7/7; NornsScriptLifecycleTest 9/9; ScreenApiContractTest 15/15. Esse relatório não substitui o resultado instrumentado.
- Separadamente, o run #717 (`44115981768`) iniciou Android Emulator 35 e manteve ADB, mas `connectedDebugAndroidTest` terminou com `Instrumentation run failed due to Process crashed`; nenhum teste instrumentado foi concluído. Workflow alterado para capturar logcat, getprop, processos e package state após a tarefa, e publicar relatórios Android mesmo em falha; run #719 está validando essa coleta.
- Auditoria do caminho nativo Stratum: `sc_api.new()` retorna o node ID após aceitar o envio OSC de `/s_new`; a inicialização atual usa `/sync` para confirmar `/d_recv`, mas não verifica explicitamente `/fail /s_new` antes de marcar o engine como iniciado. Isso deixa uma lacuna de observabilidade/validação de criação do node. Próxima alteração deve adicionar uma barreira de sincronização após `/s_new`, consumir e classificar `/fail /s_new`, e manter Stratum silencioso/em erro explícito se a criação falhar — nunca selecionar PolyPerc.
- Sem mudanças de produção de áudio nesta auditoria. PolyPerc e fallback Java preservados; ownership Oboe/AudioTrack e gates Softcut preservados; seis vozes Softcut permanecem bloqueadas até prova de roteamento estéreo e ausência de duplicação.
- `main` permanece em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; trabalho somente na branch autorizada.


## circles — a escala não afetava o synth interno (2026-11-22)

- Sintoma (S10): com saída MIDI o circles enviava notas quantizadas pela escala; com a saída de áudio (PolyPerc do app) trocar escala/raiz quase não mudava o som.
- Causa: o circles fixado (`JakeCarter/circles@a711dcd`, `circles.lua`, ramo `circles_output == audio`) chama `engine.hz(note)` com `note` = número **MIDI** vindo de `scale_notes` (o mesmo valor enviado a MIDI e crow). `engine.hz` do PolyPerc espera **Hz** (o `Engine_PolyPerc.sc` do norns usa o valor como `freq` do `Pulse.ar`; o `SynthCore` do app também), então o synth tocava 59–70 Hz: todas as escalas caíam em ~3 semitons de grave. Não era falha do app: o `musicutil.lua` do app é idêntico byte a byte ao de `monome/norns` (main), a quantização estava correta, e o `engine.hz` do app é fiel ao do norns real.
- Medição com LuaJ e as bibliotecas Lua reais do app (Major, `x`=5/30/64/90/125): antes `engine.hz` recebia `59, 61, 64, 66, 70` (soa como notas MIDI 34–37); depois `246,9 / 277,2 / 329,6 / 370,0 / 466,2` Hz, exatamente as frequências das notas `59, 61, 64, 66, 70` que a saída MIDI envia.
- Correção (`boot.lua`, bloco `compat-patches`): `_norns.load_script` passa o código-fonte por `_norns.patch_script_source`, que troca em memória `engine.hz(note)` por `engine.hz(require("musicutil").note_num_to_freq(note))`. A regra só age quando a assinatura do circles está presente (`handleCircleBurst`, `engine.name = 'PolyPerc'`, `local note = ...(`) e a chamada ocorre exatamente uma vez; se o autor corrigir o script, a regra deixa de casar e nada é alterado. O arquivo do usuário não é modificado, a numeração de linhas é preservada e o log mostra `Compat: circles: engine.hz recebia nota MIDI [...]` quando o patch é aplicado. Nenhum `local` novo no nível do arquivo (regras em campos de `_norns`).
- Testes: `ScriptSourcePatchTest` (3) — conversão, idempotência/exclusões e o circles fixado com `util`/`controlspec`/`paramset`/`musicutil` reais comparando a frequência do áudio com a nota enviada ao MIDI em 4 escalas × 5 posições; `NornsScriptLifecycleTest` (+1) — o loader de produção aplica o patch antes de executar. Teste de mutação local: com o gancho desligado, a regra neutra ou a conversão errada (nota+1), os testes novos falham.
- Observação do script (upstream, MIDI e áudio): o circles gera a escala a partir de `root_note - 1`, então a nota exibida como raiz soa um semitom abaixo.
- Pendente: confirmar no S10 que a escala altera o timbre/altura da saída de áudio; o som ficará bem mais audível que antes (~250–470 Hz em vez de ~60 Hz).


## Ideias do Verdandi: Engine Lab, /home/we, shell POSIX e cjson (2026-11-25)

- Origem: análise do projeto Verdandi (`ha1dcre/verdandi`, GPL-3.0) pedida em 2026-11-23. Foram aproveitadas as ideias, não o código (o Norns Mobile não tem LICENSE na raiz; as fontes de engines do Engine Lab mantêm as licenças originais). Itens 1–4 da lista priorizada implementados nesta branch.
- **1. Engine Lab com 7 engines só de UGens do núcleo** (commits `feba8c5` e `276aecf`; Engine Lab verde, run `45293172869`). MollyThePoly, Passersby e Thebangs foram copiadas sem alteração para `tools/engine-lab/sources/` com `LICENSE` e `SOURCE.txt` (commit fixado). Glut e awake-rings (KarplusRings), cujos repositórios não trazem licença, são clonados na CI num SHA fixo e não entram no repositório. PolySub e TestSine vêm do próprio norns e rodam isoladas (PolySub registra o SynthDef no `StartUp`). O `analyze.py` agora também lista os SynthDefs temporários (`.play`) e os comandos que não geram OSC. Resultado no sclang/scsynth reais:

  | Engine | SynthDefs | Comandos (geram OSC) | UGens sem plugin no app |
  |---|---|---|---|
  | MollyThePoly | 3 | 46 (43) | nenhum |
  | Passersby | 2 | 36 (35) | nenhum |
  | Thebangs | 0 (+3 temporários) | 18 (8) | nenhum |
  | PolySub | 1 | 25 (11) | nenhum |
  | TestSine | 0 (+2 temporários) | 2 (2) | nenhum |
  | Glut | 2 | 15 (14) | nenhum |
  | KarplusRings | 1 | 9 (4) | nenhum |

  Já presentes antes: Autumn (falta `Decimator`), Bline_Synth (faltam `RLPFD` e `SawDPW`), BandSaw, Pixelbangs, Sawed, Terrarium e TimeRhythm (nenhum faltando).
- Limite: carregar e compilar não é tocar. Os comandos "sem OSC" (escrita síncrona em buses de controle, estado só no cliente, como em PolySub e Sawed) exigem uma camada de execução que mapeie voz/node para OSC, que ainda não existe; nenhuma dessas engines foi habilitada no app. Pendências de plugin: `Decimator`, `RLPFD`, `SawDPW`.
- **2. `/home/we/...` na pasta dust** (`Dust.resolve`): caminhos do norns real (`/home/we/dust/audio/x.wav`, `/home/we/dust/data/...`, `/home/we/x`) apontam para dentro da pasta dust do app em todas as funções de arquivo do host (ler, gravar, listar, criar, remover, renomear, softcut, tape, samples). `/home/wexyz` não é traduzido; `..` e links simbólicos para fora continuam recusados. Testes: `DustNornsPathTest` (9).
- **3. `os.execute` e `io.popen` com subconjunto POSIX** (`lib/nornsmobile_shell.lua`, ligado em `boot.lua`): `mkdir`, `rm`, `cp`, `mv`, `touch`, `cat`, `ls`, `cd`, `echo`, `true`/`false`, com `;`, `&&`, `||`, `&` no fim, aspas, curingas no último componente do caminho e redirecionamentos para arquivo ou `/dev/null`. Nada executa de verdade: tudo passa pelas funções do host (pasta dust); a própria pasta dust e `code/`, `data/` e `audio/` nunca são removidas. Pipes, `$VAR`, crase e outros programas continuam devolvendo `nil, "não é suportado"`, com um aviso por comando no log. Funções novas do host: `copy` (80) e `remove_all` (81). `util.os_capture(cmd)` passa a funcionar para esses comandos. Testes: `PosixShellTest` (30), sobre um sistema de arquivos falso que imita as regras do host; no teste de mutação local, a única mutação que sobreviveu (`rm` aceitando `.` e `..`, mascarada pela proteção do host) ganhou casos próprios.
- **4. `cjson`** (`lib/cjson.lua`, `lib/cjson/safe.lua`): API do lua-cjson 2.1.0 em Lua puro (`encode`, `decode`, `null`, `new`, `cjson.safe` e as opções `encode_sparse_array`, `encode_max_depth`, `decode_max_depth`, `encode_number_precision`, `encode_keep_buffer`, `encode_invalid_numbers`, `decode_invalid_numbers`), com as mesmas regras (vetor ou objeto, `{}` vazio, `/` escapado, pares substitutos UTF-16, decode leniente por padrão) e as mesmas mensagens e posições de erro (ex.: `Expected comma or array end but found T_END at character 5`). Diferenças deliberadas: `cjson.null` é uma tabela única (o LuaJ não cria lightuserdata; compare com `==`) e as chaves de objeto saem em ordem, para o mesmo estado gerar sempre o mesmo arquivo. `package.cpath` passou a existir (`""` por padrão), como no norns real. Testes: `CjsonModuleTest` (8 testes, 41 casos) com esperados tirados das regras do lua-cjson e não da saída do módulo; 44 mutações plantadas no módulo, todas detectadas.
- **Achado (LuaJ 3.0.1, não corrigido aqui):** `tostring` de número não inteiro usa precisão de float (`1/3` vira `0.33333334`); `string.format` ignora largura e precisão em `%f`, `%e` e `%g` e imprime o `Double.toString` (`string.format("%.2f", 1/3)` dá `0.3333333333333333`, `1e20` dá `1.0E20`); `%d` trunca em 32 bits (`2^40` dá `0`). O `cjson` não depende disso: formata os números sozinho, como `%.14g`. Já `Control:string`, `tidy_numbers` e `tidy_value_text` (`%.2f` e `%.3f`) e os scripts do norns que fazem `string.format("%.2f", x)` mostram números longos na tela (os exemplos dos comentários, `-8.999999999999998` e `3.4999999999999996 s`, são exatamente essa saída). Confirmado no fonte do LuaJ 3.0.1 e em execução local; falta confirmar no S10. Correção sugerida: substituir `string.format` por uma versão com `%f`, `%e`, `%g` e `%d` completos (precisão, largura, flags, 64 bits).
- Verificação: suítes locais afetadas verdes (453 testes; os 13 restantes dependem dos scripts do norns baixados pela CI). `LuaHost.java` só compila na CI (sem Android SDK no ambiente local).
