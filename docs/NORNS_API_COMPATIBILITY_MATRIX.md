# Matriz de compatibilidade da API norns — Norns Mobile

Auditoria estática inicial da branch `migration/reconcile-jo1ngf-2026-11-12`, HEAD de referência
`20f85543034dda678d5fb472895a3e0a3bab12d2` (2026-11-13).

Esta matriz separa presença de código, cobertura automatizada e compatibilidade ainda não demonstrada. Um teste de contrato por busca textual não equivale a execução comportamental; testes LuaJ com fixtures também não comprovam transporte Android ou hardware físico.

## Legenda

- **Implementado / testes automatizados**: API ou caminho principal existe e tem testes comportamentais/contratuais identificáveis.
- **Parcial / restrito**: há implementação, mas parte do contrato upstream está ausente, bloqueada ou deliberadamente restrita.
- **Não validado em integração**: não há evidência suficiente de execução ponta a ponta com Android/serviço real.
- **Ausente no inventário embarcado**: módulo oficial não aparece entre os assets Lua embarcados listados nesta auditoria; scripts podem ainda fornecer seu próprio módulo.

## Módulos principais

| Módulo upstream | Estado encontrado | Evidência / lacuna |
|---|---|---|
| `engine` | Parcial / testes automatizados | Registro, comandos, load callback, engines Java e nativos específicos; engines upstream arbitrárias ainda dependem de SynthDefs/UGens e integração SuperCollider. |
| `audio` | **Parcial — APIs enumeradas, capacidades explícitas** | Implementados no backend Java os controles ADC/monitor/DAC, Tape playback/recording e estado upstream, VU, pitch opt-in, metadados WAV via `file_info`, diskfree (reserva padrão upstream de 250 s), reverb/compressor Faust e sends monitor/engine/Tape. `tape_is_recording()` e snapshots aninhados `play`/`rec` seguem os enums/campos upstream. `set_audio_level` só persiste `norns.state.out` quando o backend aceita o DAC; o helper LuaJ testa rejeição em modo nativo, arquivo/diskfree, snapshots, callbacks e unsubscribe. `level_cut_rev`, `headphone_gain`, `restart`, `add_params` (controles geridos pela UI Android) e roteamento JACK/Crone não têm equivalente Android; retornam indisponibilidade explicitamente ou listas vazias. `monitor_stereo` e efeitos no Oboe permanecem bloqueados. Execução JUnit do HEAD atual ainda sem status/artefato acessível nesta sessão. |
| `clock` | Parcial / testes automatizados | Scheduler, coroutines, tempo, sync interno e MIDI e callbacks de transport. `clock.set_source` aceita somente `internal` e `midi`; Ableton Link, peers e integração Link permanecem ausentes. A auditoria de lifecycle Stratum separa loops persistentes (canceláveis) de corrotinas one-shot (conclusão natural). |
| `metro` | Implementado / testes automatizados | `metro.new/init/free/free_all`, start/stop e scheduler; conferir ainda diferenças finas de semântica frente ao upstream. |
| `params` / `paramset` | Implementado no núcleo Lua / integração parcial | Tipos Number, Option, Control, Taper, Binary, Trigger, Text, File, Separator e Group; add genérico, lookup, range/options/default, hide/show, save/pmap, bang, reset, pset read/write/delete e slot persistido. Cobertura LuaJ em `ParamSetCompatibilityTest`. A apresentação/edição Android de cada widget e equivalência completa dos casos avançados ainda precisam de comparação visual/funcional. |
| `screen` | Parcial / testes automatizados | Superfície Android, operações de texto, fontes, imagens/regions, blend modes e exportação PNG têm contratos em `ScreenApiContractTest`. `screen.ping()` retorna true; `screen.update_low_battery()` retorna false intencionalmente porque o indicador de bateria pertence à barra de status do hardware Norns. Não há equivalência para saver/indicador low-battery do hardware; validar diferenças de rasterização e semântica de buffer em dispositivos continua pendente. |
| `midi` | Implementado em Lua / integração pendente | 16 vports, virtual, conexão, mensagens e relógio MIDI; testes de parser, runtime, hotplug e IDs. Dispositivos e roteamento MIDI externos ainda precisam de integração Android real. |
| `osc` | Implementado em Lua / integração pendente | Mensagens, bundles, tipos estendidos, listen/send e roteamento de parâmetros; rede real ainda não validada. |
| `grid` / `arc` | Implementado em Lua / integração pendente | Vports, LED/ring, callbacks, serialosc discovery, hotplug e remoção têm testes LuaJ; hardware/rede real pendentes. |
| `hid` | Parcial / testes automatizados | HID de entrada Android, códigos, vports e callbacks; saída HID não é suportada. |
| `keyboard` / `gamepad` | Implementado em Lua / integração pendente | Mapeamento de teclado Android e botões/eixos gamepad, estados agregados e releases; dependem de validação Android. |
| `crow` | Parcial / integração pendente | Protocolo serial CDC, parsing, upload, eventos e public API; CDC-ACM apenas, sem drivers UART proprietários. |
| `serial` | Parcial / integração pendente | Handlers, match/configure, callbacks, escrita, close e CDC-ACM; FTDI/CP210x/CH340 não cobertos por este backend. |
| `poll` | Parcial | Polls implementados para métricas expostas pelo app; catálogo e precisão devem ser comparados ao sistema de áudio norns. |
| `norns` / `script` | Parcial | Paths, require/include, load/clear, cleanup e lifecycle existem; funções de sistema dependentes de hardware/OS norns não são equivalentes no Android. |

## Bibliotecas Lua embarcadas

Inventário encontrado em `app/src/main/assets/lua/lib` após esta etapa:
`util`, `controlspec`, `tabutil`, `formatters`, `paramset`, `filters`,
`lattice`, `lfo`, `musicutil`, `sequins`, `ui`, `beatclock`, `pattern_time`, `voice`, `fileselect`.

`LuaLibraryCompatibilityTest` verifica carregamento das bibliotecas listadas acima e resolução de alias `lib/formatters`. A auditoria inicial não encontrou assets para `beatclock`, `pattern_time` e `voice`; as três foram incorporadas nesta etapa a partir do upstream monome/norns e receberam testes de execução LuaJ. `fileselect` foi integrado e recebeu testes comportamentais. Continuam ausentes várias outras bibliotecas de UI/containers listadas na documentação upstream. Classificar como ausentes do pacote, não como APIs impossíveis: scripts podem trazer cópias locais, mas isso precisa ser exercitado com fixtures reais.

## Atualização — 2026-11-14 (compatibilidade da API audio/Tape)

- `audio.tape_is_recording()` agora consulta `rec.state == TAPE_REC_STATE_RECORDING`, com constantes upstream `EMPTY=0`, `READY=1`, `RECORDING/PLAYING=2` e `PAUSED=3` (playback: `EMPTY=0`, `READY=1`, `PLAYING=2`, `PAUSED=3`).
- `audio.tape.get_state()` agora retorna snapshots aninhados `play={state,pos,len,loop,file}` e `rec={state,pos,file}`; cópias isoladas, subscribe/unsubscribe e callbacks `_on_status`, `_on_play_file`, `_on_rec_file` são expostos. TapeCore reporta `READY` antes de iniciar e mantém metadados de arquivo.
- `audio.tape_compute_diskfree()` usa a reserva upstream padrão de 250 segundos, 48 kHz, estéreo PCM16.
- A auditoria função-a-função adicionou helpers de nível de saída e APIs de roteamento. As operações sem equivalente seguro no Android (JACK/Crone ports, headphone gain, restart, Softcut→reverb e `add_params` gerenciado pela UI) falham explicitamente, sem declarar uma rota inexistente.
- Cobertura adicionada em `TapeCoreTest`, `BootLuaSyntaxTest` e `AudioRoutingApiContractTest`. O commit `[run-ci]` foi enviado à branch, mas não há relatório JUnit nem logs nativos confirmados nesta sessão: o conector disponível não lista execuções por número de workflow; `498` não é um `run_id`/`job_id` válido. A validação automatizada permanece pendente até obter o ID real e o artefato.

## Atualização incremental — 2026-11-14 (helpers audio e testes LuaJ)

- `audio.set_audio_level()` e `adjust_output_level()` agora mantêm o estado persistido coerente com o backend: a saída recusada no modo SC nativo não altera `norns.state.out`.
- Teste LuaJ isolado cobre `file_info` (retorno channels/samples/rate), `tape_compute_diskfree` (reserva default 250 s e reserva customizada), DAC aceito/recusado, clamp, inventário de portas vazio e rejeição de conexões inexistentes.
- Teste Tape ampliado para `_on_status`, cópias isoladas entregues aos subscribers e `unsubscribe`; `tape_is_recording()` é revalidado depois das transições.
- Os commits com `[run-ci]` acionam o filtro do workflow `unit-tests.yml` por `app/**`. O conector de Actions disponível nesta sessão não expõe listagem de execuções push; `get_commit_combined_status` ainda não apresenta checks e o endpoint de runs por commit só retorna execuções de PR. Portanto, não há confirmação de JUnit para este HEAD, apesar dos relatórios verdes anteriores de outros commits.

## Próximas lacunas prioritárias

1. **Audio API**: mapear cada método upstream para uma capacidade real existente no host. Implementar apenas quando houver controle efetivo do mixer/áudio; caso contrário, manter retorno explícito de indisponibilidade. Adicionar testes comportamentais para clamp, estado, roteamento e callbacks.
2. **Clock Link**: avaliar uma integração Android compatível com Ableton Link; não aceitar `link` em `set_source` até existir sincronização real, peers e testes de tempo/transport.
3. **Bibliotecas oficiais ausentes**: fixar versões upstream e adicionar módulos por prioridade de dependência dos scripts reais, prosseguir com as bibliotecas de UI/containers restantes; `beatclock`, `pattern_time`, `voice` e `fileselect` agora estão embarcadas e cobertas por testes LuaJ. Validar require, execução e cleanup.
4. **Params / screen**: comparar assinaturas, retornos, eventos e persistência com a documentação oficial; completar diferenças identificadas por testes funcionais.
5. **Ciclo completo**: para cada fixture de script, executar load → init → callbacks → redraw → cleanup → reload e verificar ausência de timers, dispositivos e tarefas órfãs.

## Fontes e evidências

- API oficial: https://monome.org/docs/norns/api/
- Referência de módulos: https://monome.org/docs/norns/reference/
- Audio API: https://monome.org/docs/norns/api/modules/audio.html
- Clock API: https://monome.org/docs/norns/api/modules/clock.html
- Screen API: https://monome.org/docs/norns/api/modules/screen.html
- Testes locais relevantes: `AudioRoutingApiContractTest`, `ClockApiContractTest`, `ClockMetroRuntimeTest`, `EngineApiContractTest`, `ParamSetCompatibilityTest`, `ScreenApiContractTest`, `MidiApiContractTest`, `OscApiContractTest`, `MonomeLuaRuntimeTest`, `CrowApiContractTest`, `UsbSerialLuaRuntimeTest`, `LuaLibraryCompatibilityTest`, `ExternalNornsScriptCompatibilityTest`.

## Limites desta auditoria

Esta é uma matriz de lacunas baseada no código e nos testes existentes, não uma declaração de compatibilidade total. O JUnit verde confirma apenas os cenários automatizados. Não foram executados APK, probe ARM64, integração Oboe ou testes físicos; PolyPerc e fallback Java devem permanecer preservados, e as seis vozes Softcut continuam bloqueadas até prova independente de roteamento estéreo e ausência de áudio duplicado.


## Audio file metadata / fileselect prerequisite — 2026-11-13

- **Implemented:** `audio.file_info(path)` returns `channels, samples, sample_rate` for supported RIFF/WAVE files. The host parser reads only chunk metadata and seeks over sample data; it does not allocate or map the audio payload.
- **Invalid/unsupported files:** return `0, 0, 0`, matching the upstream `fileselect.getlist()` check (`samples > 0` and `rate > 0`).
- **Automated coverage:** metadata for stereo PCM, sample-frame count, sample rate and bit depth; malformed/truncated WAV rejection; Lua return tuple contract. Full JUnit report: 569 tests, 0 failures, 0 ignored (run 43491489112, commit `2a681efa43d87e2fdeeec70a1d810702f824b932`).
- **Integrated:** upstream `lib/fileselect.lua` is now bundled with the `norns.menu` callback contract and lifecycle restoration. Navigation, audio filtering, preview, debounce, cancellation and cleanup are covered by `FileselectRuntimeTest`.


## Menu e seletor de arquivos — 2026-11-13

- **Implementado:** `norns.menu.status/init/set/get_*/toggle/set_mode`, com handlers independentes para key, enc, redraw e refresh. O dispatcher encaminha key/enc ao conjunto de callbacks correspondente ao modo ativo.
- **Implementado:** biblioteca upstream `lib/fileselect.lua`, usando `audio.file_info` para reconhecer áudio sem carregar o payload WAV.
- **Comportamento coberto:** filtro por áudio/extensão, navegação por diretórios e retorno, preview Tape, janela de debounce, cancelamento e restauração dos callbacks.
- **Robustez:** seleção usa índices da lista visível para recuperar a entrada original; exit é idempotente, cancela timers/preview pendente e interrompe preview ativo. `_norns.cleanup()` também encerra um seletor carregado antes de destruir clocks e estado.
- **JUnit:** run 43497721371, 571 testes, 0 falhas, 0 ignorados; relatório HTML completo `norns-mobile-junit-reports-361` inspecionado.
- **Limites:** os testes são LuaJ/host mocks; não demonstram renderização ou latência de preview em dispositivo Android. APK, probe ARM64 e Oboe não foram executados.


### Audio API — controle de saída DAC Java

- `audio.level_dac(level)` controla o ganho mestre da saída Java entre 0 e 1, após o ponto de captura do Tape; os testes cobrem escala estéreo, mute e saturação.
- Com scsynth nativo ativo, a função retorna `false`: o caminho Oboe não é controlado por este ganho. Isso é uma limitação explícita, não compatibilidade integral com o DAC do norns.


## Incremental CI finding — Tape/LuaJ fixture — 2026-11-14

- JUnit run **43557800813** (run #518), commit `4e1258f2dd5b0f7348de060c052ade003e6d5255`: native audio/hardware checks, SynthDef compilation/validation, fixture downloads and JNI/Faust compilation passed; the JVM suite reported **623 tests, 1 failure**.
- The sole failure was `BootLuaSyntaxTest.fileLinesClosesItsHandleWhenIteratorReachesEof`: the Java fixture embedded a literal newline inside a Lua single-quoted string, producing LuaJ `unfinished string` before exercising `FileR:lines()`.
- Corrected the fixture to emit the escaped Lua sequence (`\\n`) in commit `b78f38b223b2f49951775dee485d84a6d430b68a`. This is a test-fixture correction, not a runtime Tape change.
- **Validation:** run **43613752792** (#519), commit `b78f38b223b2f49951775dee485d84a6d430b68a`, concluído com sucesso: **623 testes, 0 falhas, 0 ignorados**. Artefato HTML `norns-mobile-junit-reports-519` baixado e inspecionado; todas as 20 entradas de `BootLuaSyntaxTest`, 11 de `TapeCoreTest` e 15 de `AudioRoutingApiContractTest` passaram.


## Audio API — envio Softcut isolado para reverb — 2026-11-14

- **Implementado no mixer Java:** `audio.level_cut_rev(level)` agora controla um quarto envio independente do Faust reverb, ao lado de monitor, engine e Tape.
- `SoftcutCore` expõe um buffer estéreo auxiliar com o sinal pós-pan e pré-master. O buffer é separado da saída principal; o ganho de envio não altera o volume principal nem a matriz voice-to-voice.
- O caminho é habilitado somente quando Softcut Java é o proprietário da saída. Com scsynth nativo em estado `native/loading`, a API retorna `false`; nenhum tap no Oboe nem rota paralela foi presumida.
- JNI valida capacidade estéreo do buffer auxiliar e passa o quarto canal de envio ao Faust sem alocação por bloco no DSP nativo.
- Teste comportamental `SoftcutCoreTest.isolatedReverbSendTracksSoftcutWithoutChangingMasterOwnership` verifica que o send pré-master segue o sinal Softcut sem alterar o master. `AudioRoutingApiContractTest` verifica a ligação do quarto send e o bloqueio no modo nativo.
- **JUnit desta alteração pendente**; executar workflow completo e inspecionar JUnit/JNI/SynthDef antes de considerar a API validada.


## Softcut reverb send — CI integration follow-up — 2026-11-14

- O primeiro validador do caminho Softcut ainda exigia a chamada antiga de `SoftcutCore.render`; os runs #531 (`c150b616`) e #532 (`88d00031`) pararam nesse assert antes da compilação JNI/JUnit. Atualizado em `2142c677` para exigir explicitamente o buffer auxiliar e seu clear por bloco.
- O run #535 (`2142c677`) passou os três validadores Python e o teste do bus engine->Softcut, mas o compilador C++ encontrou chamadas antigas de cinco argumentos em `tools/test-faust-audio-effects.cpp`. Atualizado o teste para a assinatura estéreo de seis argumentos e para cobrir os quatro envios independentes (monitor, engine, Tape e Softcut), incluindo isolamento da fonte não selecionada.
- O guard Lua de `audio.level_cut_rev` foi reforçado para recusar também quando `softcut._native_output_owner`, `softcut._native_output_uncertain` ou `fallback_pending` indicam que a saída Java não está confirmada. O teste LuaJ cobre native scsynth, ownership nativo e ownership incerto.
- **Validação atual:** run #537 (`43615277857`) no commit `cd7905d90583fff666a396707e4fd00facc29d9d` em andamento; ainda não há resultado JUnit nem confirmação de compilação JNI para esta revisão.


## Audio API — mixer ParamSet e auditoria restante — 2026-11-14

- **Estado real de `audio.add_params()`:** a integração já existia em `boot.lua` e registra os grupos LEVELS, REVERB, COMPRESSOR e SOFTCUT no mesmo ParamSet consumido por `ParamsView`. Não foi criada uma segunda UI nem um mixer paralelo.
- Os controles suportados incluem níveis de saída/entrada/monitor/engine/Softcut/Tape, modo de monitoramento mono, envios e parâmetros do reverb/compressor e níveis de entrada Softcut. Ações rejeitadas pelo backend restauram o valor anterior do parâmetro e não persistem estado falso.
- O parâmetro upstream `headphone_gain` mantém o ID para compatibilidade de scripts/presets, mas permanece oculto e retorna `false`. `audio.restart` também retorna `false`, pois reiniciar sclang/scsynth não é uma operação segura do ciclo de vida Android. As mensagens dessas duas APIs agora explicam a limitação específica, sem classificá-las incorretamente como roteamento de portas.
- `monitor_stereo` permanece fail-closed: a captura Android atual fornece um único canal ADC. O controle de ParamSet oferece somente MONO. JACK/Crone port routing continua sem equivalente e retorna listas vazias/false.
- Auditoria upstream: as demais funções de mixer, pitch, Tape, Softcut, efeitos, estado Tape, file metadata e helpers de nível possuem implementação Java real ou bloqueio explícito por ownership/backend. Não foi encontrada outra API de áudio que possa ser ativada com segurança sem adicionar uma capacidade de hardware ou rota que ainda não existe.
- **JUnit #539:** run `43615876183`, commit `5394e517e725a2d353f274a3768a820446a5e09d`: **624 testes, 0 falhas, 0 ignorados**; os 70 relatórios de classe foram inspecionados. Verificações de mute/ownership Softcut, isolamento dos quatro sends Faust, compilação JNI/Faust e SynthDefs passaram.
- Uma suíte subsequente adiciona cobertura comportamental para todos os grupos e ações de `audio.add_params`, além de mensagens específicas para as APIs Android indisponíveis; aguarda o workflow de validação dessa revisão.
- Limites mantidos: nenhuma afirmação de compatibilidade de hardware, nenhum teste físico, PolyPerc e fallback Java preservados; as seis vozes Softcut permanecem bloqueadas até prova independente de estéreo e ausência de duplicação.


## Stratum / Rings

- Upstream fixture pinned: `mrmattallison/stratum@8c7ca83e0b4ff8f3b20f170a68d7b782cf98ba74`.
- CI fixture parsing, command-contract tests and fail-closed behavior tests have been added. The upstream Lua source remains CI-only because its repository has no explicit license file; it is not bundled in the APK.
- Runtime implementation: Stratum dispatcher, 25 native controls, plugin detection and serial stereo SynthDef source are present. MiRings.so and MiClouds.so were compiled as Android ARM64 ELF binaries, packaged in APK #768, and NornsStratum.scsyndef was compiled and packaged.
- JUnit #709 JVM report: 667/667 tests passed; StratumCompatibilityTest 7/7 and EngineStratumCommandTest 7/7. The emulator/Screen instrumentation phase failed before test execution because ADB lost the emulator and timed out.
- **Compatibility remains partial / audio unverified:** automated Lua tests and artifact inspection do not prove runtime UGen loading, native node creation, parameter response or audible output on Android. Do not mark Rings/Stratum as fully compatible until a controlled audio smoke test passes. MIDI/Crow-only mode is a separate partial capability, not Rings audio support.


## Atualização — 2026-11-16 (Stratum lifecycle e validação JUnit #626)

- O run #626 (43888238678) no commit 87e4e6d67efe5d1a0ea66eee5cb26b0c2be9995f concluiu com sucesso: 649 testes, 0 falhas e 0 ignorados; relatório HTML integral conferido.
- StratumCompatibilityTest executa o script e módulos Lua upstream com mocks comportamentais para engine, clock, Crow, MIDI e params. A expansão seguinte exige que cleanup cancele cada tarefa agendada, não apenas que o contador de cancelamentos seja maior que zero.
- Limite preservado: testes LuaJ comprovam contrato de script e gerenciamento de recursos simulado; não comprovam carregamento real de MiRings/MiClouds em ARM64 nem saída Oboe/hardware.


## Stratum lifecycle reload — 2026-11-16

- Cobertura integrada em andamento: duas execuções de Stratum no mesmo Globals Lua, callbacks de key/enc/redraw em cada geração, cleanup por geração, seleção explícita da engine e auditoria do cancelamento de todas as tarefas registradas pelo scheduler.
- JUnit #628 identificou e isolou falha de sintaxe Lua causada por escape duplicado no fixture de auditoria; corrigida antes da validação seguinte.
- Não confundir execução LuaJ com carregamento nativo de MiRings/MiClouds em ARM64 ou áudio físico.


## Stratum scheduler e lifecycle — diagnóstico #629/#630 (2026-11-16)

- Runs #629 (43912102613) e #630 (43912238473) executaram os 649 testes e terminaram com 1 falha cada, 0 ignorados. Os relatórios HTML integrais foram baixados; em ambos a única falha foi pinnedStratumInitRunsWithRealModulesAndRoutesRingsCommands.
- O teste inicialmente classificava toda chamada clock.run como recurso persistente e exigia clock.cancel para todas. O upstream agenda corrotinas one-shot de retry MIDI (250 ms) e de gate com duração; estas são tarefas finitas e devem concluir naturalmente. O teardown cancela os loops persistentes t_section e redraw. O teste foi corrigido para validar precisamente estes dois loops em cada geração, sem declarar vazamento para tarefas temporizadas.
- A matriz de lifecycle Norns retém callbacks key/enc da primeira geração, carrega uma segunda geração e invoca as referências antigas; os contadores da segunda geração permanecem intactos. O teste também verifica reset/start do adaptador de engine no reload. JUnit #633 confirmou a suíte: 649 testes, 0 falhas, 0 ignorados.
- JUnit #634 adiciona um caso específico com o script upstream Stratum e os dispatchers key/enc de produção extraídos de boot.lua: uma referência tardia ao dispatcher deve atingir a função da geração atual, não a callback antiga. Validação ainda em andamento.
- Limite: o harness usa adaptadores de engine mockados. A prova de callback nativo tardio no carregamento de SynthDef e o ciclo DSP real Java/native ainda precisam de cenários comportamentais dedicados; isso não comprova áudio ARM64/Oboe.
- Próxima validação: executar novamente JUnit integral, ler todos os relatórios e acrescentar testes de callbacks assíncronos de engines, mantendo PolyPerc/fallback Java e sem habilitar as seis vozes Softcut.


## Auditoria incremental — clock, params, screen e limites do host Android (2026-11-16)

- Clock: `internal` e `midi` são as únicas fontes aceitas; Link/Ableton Link e contagem de peers permanecem indisponíveis. O scheduler interno usa fila de timers e cancelamento por handle; no Stratum upstream, loops persistentes devem ser cancelados no cleanup, enquanto retries/gates finitos terminam naturalmente.
- Params/ParamSet: inventário do módulo confirma Number, Option, Control, Taper, Binary, Trigger, Text, File, Separator e Group, além de add genérico, lookup/get/set/raw/delta, action, hide/show, range/options/default, save/pmap, bang, reset, pset read/write/delete e persistência do último slot. A cobertura LuaJ verifica serialização, callbacks, flags de persistência e grupos; ainda não equivale à validação de todos os widgets no display Android.
- Screen: a superfície inclui export PNG, decode/load, create/free/display de imagens, regiões, draw-to, peek/poke, fontes, trim/rotate e blend modes. `screen.ping()` retorna true; `screen.update_low_battery()` retorna false deliberadamente, pois o indicador é específico da barra de status Norns. Não converter esse stub em sucesso fictício.
- Limites do host: `norns.shutdown()` e `norns.reboot()` retornam false com mensagem explicativa; `os.execute` não executa comandos de shell e `os.exit` é desativado para proteger o processo Android. São diferenças deliberadas de plataforma, não falhas silenciosas.
- Próxima auditoria: comparar assinatura/retorno e comportamento de widgets `File`/`Text`, operações screen com clipping e blend em versões Android suportadas, e a semântica de cancelamento do clock frente ao upstream. Não declarar equivalência de rasterização sem testes de imagem.


## Validação do scheduler Stratum — JUnit #631 (2026-11-16)

- Run #631 / 43925784455 no commit 9e113e28e9b6720e0420c491e6bd8ed0ed8001dc: sucesso. Relatório HTML norns-mobile-junit-reports-631 (artefato 14256416683) conferido: 649 testes, 0 falhas, 0 ignorados, 100% de sucesso (2.541 s). Contratos nativos, SynthDefs, fixtures upstream e compilação JNI/Faust também passaram.
- A correção de contrato distingue os loops persistentes t_section/redraw de corrotinas temporizadas de retry/gate. O run #633 está pendente para validar a cobertura adicional de callbacks entre gerações e reset/start de engine.


## Stratum stale-dispatch validation — JUnit #634/#635 (2026-11-16)

- JUnit #634 / run 43926971310 falhou em um único teste (StratumCompatibilityTest, 648/649 aprovados). A asserção chamou stale_key_dispatch antes de inicializar a segunda geração e antes de capturar os dispatchers; o valor era nil. O problema era a ordem do fixture, não uma falha de produção.
- Reordenado no commit 996da1faec17e0f23fec1f452701f6ac02e2f750: reter key/enc e dispatchers após a geração 1, recarregar/init Stratum e então disparar os dispatchers antigos, verificando que os callbacks atuais recebem os eventos e os callbacks retidos não são chamados.
- JUnit #635 / run 43927009936 está em andamento; a prova específica ainda não deve ser considerada aprovada.


## Stratum stale-dispatch — JUnit #635 aprovado (2026-11-16)

- Run #635 / 43927009936, commit 996da1faec17e0f23fec1f452701f6ac02e2f750: sucesso. Relatório HTML do artefato 14255664616 conferido integralmente: 649 testes, 74 classes, 0 falhas, 0 ignorados, 100% (2.200 s). Todas as 74 páginas de classe somam 649 testes.
- StratumCompatibilityTest 7/7: callbacks de key/enc e dispatchers de input retidos na geração 1; após reload/init, dispatchers antigos resolvem os handlers da geração 2 e não invocam os callbacks antigos.
- NornsScriptLifecycleTest 5/5: lifecycle de load/cleanup/reload, callbacks retidos e contadores reset/start do adaptador de engine.
- Contratos nativos de áudio/hardware, SynthDefs, fixtures upstream e JNI/Faust passaram. O adaptador de engine do lifecycle segue mockado; a próxima lacuna é callback assíncrono de conclusão nativa após reset/troca e cobertura comportamental específica Java/native.


## Engine lifecycle and remaining API audit — 2026-11-16

- Native engine completion now requires the generation-specific `/sync` barrier. `/done /d_recv` is uncorrelated and is no longer accepted alone; stale `/synced` replies cannot complete a newer engine generation.
- `/fail /d_recv` is retained until the matching sync barrier arrives, then the active generation follows the documented Java fallback / unavailable behavior. `engine_finish_load(ok, generation)` refuses to invoke a callback if its generation token no longer matches.
- New behavioral LuaJ coverage in `EngineNativeLifecycleTest`: reset during TestSine native load, switch to FM Java, start PolyPerc native load, inject retired `/done` and `/synced` replies, assert current load stays pending, then complete only with the current sync ID. Also exercises delayed native failure through its sync barrier.
- Java lifecycle coverage now includes FM, Pluck and PolyPerc with scsynth unavailable; callbacks complete synchronously in Java state and no native poll is scheduled. PolyPerc and Java fallback are preserved.
- `norns.system_cmd(command)` is now explicitly exposed but returns `false` on Android with a diagnostic; no shell command is executed. `shutdown` and `reboot` remain fail-closed. A LuaJ runtime test was added.
- JUnit #645 / run `43936980914` on commit `9e1d806e2a49027994c91e7ef6d4a956a28179d6` is pending; do not mark this coverage green until its complete HTML report is inspected.
- Upstream API comparison: engine.load callback receives the command list and returns false if a load is already pending; upstream clock API also exposes Link peer count and accepts `link` as a source. Norns Mobile still restricts clock sources to internal/MIDI and does not claim Link support. The upstream system API includes `norns.system_cmd`, but Android intentionally blocks shell execution.
- Remaining priorities: delayed completion scenarios for SimpleDelayKolor and Stratum; actual Link backend evaluation; screen clipping/blend visual equivalence; File/Text widget behavior; missing upstream Lua libraries (containers, elca, envgraph, filtergraph, graph, intonation, listselect, reflection, textentry, timeline).
- `main` remains `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; all work remains on the migration branch. No physical Samsung testing was requested; six-voice Softcut playback remains blocked.


## Retired native replies and SimpleDelayKolor lifecycle — 2026-11-16

- A engine mantém IDs de sync aposentados após reset/timeout. Um `/fail /d_recv` que chega antes da barreira aposentada correspondente é descartado como resposta antiga; o `/synced` aposentado remove o ID da fila. Isso evita que uma falha de carga antiga derrube a geração atual.
- SimpleDelayKolor usa a mesma política nas duas fases de SynthDef. Cada fase só avança após o próprio `/synced`; falhas aguardam a barreira da fase. Reset/fallback aposenta a barreira ainda pendente.
- `EngineNativeLifecycleTest` acrescenta teste de resposta antiga `/fail` + `/done` + `/synced` após troca de script, teste de falha nativa aguardando sua barreira e teste de sucesso das duas fases SimpleDelayKolor.
- JUnit #652 / run `43937568476` foi cancelado por uma execução mais recente. JUnit #645 / `43936980914` concluiu com falha; o HTML integral mostrou 653 testes e 2 falhas, diagnosticadas abaixo. A validação das correções está em #661.


## JUnit failure analysis and current validation — 2026-11-16

- JUnit #645 / run `43936980914`, commit `9e1d806e2a49027994c91e7ef6d4a956a28179d6`: all HTML pages inspected (77 HTML, 75 class reports); 653 tests, 2 failures, 0 ignored. The lifecycle harness had omitted production `engine.list_commands()`; an incidental tasks/fallback assertion was also removed in favor of direct synchronous Java callback assertions.
- JUnit #654 / run `43937672289`, commit `a7dc45ed0d31fd66eec46a5be57c323f49ac9eb9`: full report inspected; 655 tests, 6 failures, 0 ignored. Five lifecycle failures shared one production defect: engine's metatable converted missing private fields (`_retired_native_sync_ids`, `_native_sync_id`, etc.) into command closures. The remaining failure was an obsolete static assertion for the refactored KOLOR sync reply parser.
- JUnit #657 / run `43938054564`, commit `062824a4cddb765c60c451b34aaab7179cc6dbdeb`: all 77 HTML files / 75 class pages inspected; 655 tests, the same 6 failures, 0 ignored. This commit still predated the private-field metatable correction.
- Fixes: engine `__index` now returns nil for absent underscore-prefixed internal fields and synthesizes closures only for public commands; lifecycle harness imports the production `engine.list_commands()` implementation and asserts absent private fields remain nil; KOLOR static assertion now matches the `reply_sync_id` parser; Stratum command-list expectation corrected to 25.
- JUnit #661 / run `43938573408` completed with 656 tests and one remaining stale-generation `/fail /d_recv` failure; its fix and rerun are recorded in the following section.


## JUnit #661: retired native /fail correction — 2026-11-16

- Run #661 / `43938573408`, commit `f84ce53c4fb07baf87d8804020dc5479cc6dbdeb`: all 77 HTML files and 75 class pages inspected; 656 tests, 1 failure, 0 ignored. Five of six `EngineNativeLifecycleTest` cases passed. The remaining stale-generation test exposed that the generic native poller still treated a retired `/fail /d_recv` as a failure of the current engine generation.
- Commit `22180cb3dcf13e625148defd16937f94649681ff` fixes this: generic polling now ignores `/fail /d_recv` while a retired sync barrier remains pending, just like SimpleDelayKolor; the retired `/synced` reply removes the old ID.
- JUnit #662 / run `43939389970` is in progress to revalidate stale `/fail`, `/done`, and `/synced` isolation and the full suite.


## JUnit #662 — lifecycle nativo aprovado e próximos casos (2026-11-16)

- Run 43939389970 / JUnit #662, commit testado 22180cb3dcf13e625148defd16937f94649681ff: 656 testes, 0 falhas, 0 ignorados, 100%; artefato norns-mobile-junit-reports-662 (ID 14261832920) inspecionado integralmente: 77 HTML, 75 classes. EngineNativeLifecycleTest 6/6.
- Os checks nativos de áudio/hardware, SynthDefs Softcut, fixtures upstream e compilação JNI/Faust também passaram. Isso confirma os contratos compilados/testados no runner, não execução DSP/UGens em hardware Android.
- A barreira /sync por geração agora isola também respostas /fail /d_recv de cargas aposentadas; /done /d_recv não é suficiente para concluir load e somente o /synced correspondente completa o callback.
- Nova cobertura em andamento: aposentadoria da fase 1 SimpleDelayKolor após reset/troca para PolyPerc e idempotência diante de respostas /synced duplicadas. Aguardar relatório do próximo JUnit.
- API clock: internal e midi suportados; Ableton Link, peers e transport Link continuam ausentes e devem permanecer rejeitados até integração real.
- APIs deliberadamente restritas continuam fail-closed: norns.system_cmd, audio.restart, JACK/Crone routing e recursos sem backend Android não devem simular sucesso. Bibliotecas upstream ausentes (containers, elca, envgraph, filtergraph, graph, intonation, listselect, reflection, textentry, timeline) permanecem lacunas do pacote.
- PolyPerc, fallback Java e ownership atual de áudio permanecem preservados; seis vozes Softcut seguem bloqueadas até prova independente de estéreo e ausência de duplicação. Nenhum teste físico solicitado.


## Auditoria complementar — clock e script API upstream (2026-11-16)

- Comparação com a referência oficial norns API: clock.set_source upstream documenta internal, midi e link; o guia de clocks também documenta crow como fonte de sincronismo. Este backend implementa somente internal e MIDI. Link e Crow clock source permanecem ausentes; clock.link (peer count, start/stop e parâmetros Link) não existe no runtime. set_source deve continuar retornando false para essas fontes, sem aceitar uma seleção sem relógio/transport real.
- A implementação atual cobre clock.run/cancel/sleep/sync, tempo, beats, MIDI clock 24 PPQN e callbacks de transport MIDI start/continue/stop. O suporte a clock.transport não implica transport Link/Crow.
- O módulo upstream script documenta clear, load, run e metadata. O runtime Android oferece norns.script.load, clear e rerun; script.run e script.metadata ainda não foram encontrados como APIs públicas equivalentes. Tratar como lacunas explícitas, não inferir equivalência somente porque load_script executa init.
- screen API upstream enumera update_default, ping, update_low_battery, primitivas Cairo, buffers/imagens, peek/poke, transformações e blend_mode. A implementação Android cobre grande parte da superfície; update_low_battery permanece no-op/false por ser indicador de hardware Norns, e a equivalência de clipping/rasterização/fontes/peeks deve ser verificada por testes de imagem, não só por presença de função.
- Fontes upstream consultadas: https://monome.org/docs/norns/api/ ; https://monome.org/docs/norns/api/modules/clock.html ; https://monome.org/docs/norns/api/modules/script.html ; https://monome.org/docs/norns/api/modules/screen.html ; https://monome.org/docs/norns/clocks/ . A documentação upstream tem data de atualização de 2025-11-08 na página da API estática; guias de clocks foram consultados para o comportamento de Link/Crow.


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


## Script lifecycle — invalidação de callbacks assíncronos (2026-11-16)

- A geração de script `_norns._script_generation` é incrementada em cleanup e em cada load.
- A conclusão assíncrona de `engine._start_native` só pode iniciar `softcut._start_native` quando a geração capturada ainda é a atual. Callbacks tardios após cleanup/reload são ignorados.
- Cobertura comportamental em `NornsScriptLifecycleTest.delayedEngineCompletionCannotStartSoftcutAfterScriptCleanupOrReload`: callback aposentado após cleanup, callback da geração atual após reload e idempotência após cleanup.
- Ainda não comprova inicialização real de Softcut em Oboe/ARM64 nem roteamento de seis vozes; o gate de playback permanece fechado.


## JUnit #666–#668 — validação do lifecycle assíncrono (2026-11-16)

- JUnit #666 / run `43945954465`, commit `f85aa6451d1ace95f5394206a5ee224f64d2369e`: 659 testes, 1 falha. O único erro foi sintaxe do fixture Lua por escape Java duplicado (`unexpected symbol 92`); os demais 658 testes passaram.
- JUnit #668 / run `43946197029`, commit `8b4aa45b07d47a897dc80fb909c22aab07f8aeca`: relatório integral de 75 classes, 659 testes, 0 falhas, 0 ignorados. `NornsScriptLifecycleTest` 6/6, `EngineNativeLifecycleTest` 8/8, `SoftcutOutputOwnershipTest` 27/27. Compilação nativa e JNI/Faust também passaram.
- A barreira `_norns._script_generation` passou o teste de callback atrasado após cleanup/reload: callbacks de gerações aposentadas não iniciam Softcut; a geração ativa pode iniciar uma vez. Isso não prova ainda DSP/roteamento real em Android.
- Próximo passo de equivalência: implementar `norns.script.metadata(filename)` com leitura restrita via `host.read_file` e parsing do bloco inicial de comentários; alinhar `norns.script.load(nil/"")` ao reload do script ativo. `norns.script.run()` permanece pendente: hoje `_norns.load_script` já inicia engine e chama init no mesmo fluxo, então expor um run ingênuo duplicaria init/Softcut.
- `clock.set_source`: somente internal/MIDI; Link/Crow continuam ausentes e fail-closed. `screen.update_low_battery` continua restrita por falta de hardware Norns. APIs de sistema sem backend continuam fail-closed.
- Preservar PolyPerc, fallback Java, ownership Oboe/AudioTrack e gates Softcut; seis vozes continuam bloqueadas até prova independente de estéreo e ausência de duplicação.


## JUnit #669 e implementação de script.run — 2026-11-16

- JUnit #669 / run `43947207781`, commit `ab6baef1643b958a7bace613c16ab680fa0fe447`: relatório HTML completo: 84 entradas ZIP, 77 HTML, 75 relatórios de classe, 661 testes, 0 falhas, 0 ignorados, 100% (3.130 s). Soma das classes confere com o índice.
- `NornsScriptLifecycleTest` 8/8; `ScreenApiContractTest` 14/14; `SoftcutOutputOwnershipTest` 27/27; `EngineNativeLifecycleTest` 8/8. Logs: compilação nativa e JUnit `BUILD SUCCESSFUL`.
- Status de API após #669: `norns.script.load(nil/"")` e `norns.script.metadata(filename)` implementados e cobertos; `norns.script.run()` adicionado depois de #669, ainda aguardando CI. A fase de run usa `_loaded_generation`/ `_run_generation`; engine, init e redraw não podem executar duas vezes na mesma geração, e callbacks assíncronos de engine/Softcut são invalidados por cleanup/reload.
- Params File/Text ainda exigem auditoria comportamental adicional além da cobertura genérica de paramset; Screen possui 14 contratos aprovados (fontes, blend, buffers, regiões, peek/poke, PNG, export), mas clipping/rasterização e equivalência visual continuam sem prova por golden images.
- Restrições mantidas: PolyPerc e fallback Java; ownership exclusivo Oboe/AudioTrack; seis vozes Softcut bloqueadas até prova independente de estéreo e ausência de duplicação; APIs sem backend Android continuam fail-closed.



## Params File/Text e Screen — auditoria de implementação (2026-11-16)

- A classe Lua `paramset.lua` registra `file` e `text` como tipos distintos (`tFILE=4`, `tTEXT=8`) e ambos usam a implementação compartilhada `Text`: `set`, `string`, persistência `_dump/_load`; File mostra basename em `params:string(id)`.
- Lacuna funcional identificada no Android: `ParamsView.buildRow()` não possui branches interativos para `file` ou `text`; ambos caem no ramo genérico de texto somente leitura. O parâmetro File também recebe o argumento `path` como valor inicial da classe Text, sem campo `path` independente. Portanto, não anunciar File/Text como equivalentes funcionais: scripts podem declarar/serializar os tipos, mas a UI ainda não oferece editor de texto nem file selector conectado ao parâmetro.
- Próxima implementação necessária: contrato Lua para manter `path` e `value` separados em File; callbacks seguros do host para alterar string sem coerção numérica; editor de texto e file selector que respeitem raiz Dust e diretório permitido; testes de ação, cancelamento, reload e persistência. Até isso, a matriz marca File/Text como parcial.
- Screen: JUnit #669 aprovou 14 contratos para fontes, blend modes, PNG, buffers limitados, draw_to, regiões, peek/poke, export e segurança de caminhos. Ainda não há golden-image tests de clipping/rasterização/transformações; `update_low_battery` permanece não implementável no hardware Android e retorna false.
- JUnit #672 e #671 foram ignorados pelo próprio workflow porque as mensagens dos commits não continham `[run-ci]`; nenhum runner foi consumido. O próximo commit de validação usa o marcador correto. O resultado #669 continua válido para o commit `ab6baef`, não para a nova implementação `script.run`.



## Estado validado — JUnit #702 (2026-11-16)

| Área/API | Estado atual | Evidência / limite |
|---|---|---|
| `norns.script.run()` | Implementado | Público e idempotente por geração carregada; engine/init/redraw não duplicam execução. `NornsScriptLifecycleTest` 9/9. |
| `norns.script.load(nil/"")`, `norns.script.metadata(filename)` | Implementado | Reload do script ativo e leitura do bloco inicial de comentários, sem executar o script; cobertos no lifecycle. |
| Params File | Implementado na UI | File mantém diretório permitido separado do valor selecionado; seletor navega somente dentro da raiz Dust canônica e grava o caminho selecionado pelo callback Lua/host. `ParamSetCompatibilityTest` inclui teste de raiz e valor inicial. |
| Params Text | Implementado na UI | Editor multiline conectado ao parâmetro; serialização preserva newline/CR/tab e escapes. `ParamSetCompatibilityTest` 29/29. |
| Screen API — contratos | Implementado/parcial por backend | `ScreenApiContractTest` 15/15; operações sem hardware Norns permanecem limitadas/fail-closed. |
| Screen — golden pixel tests | Testes instrumentados compilados; runtime pendente | AndroidX `compileDebugAndroidTestJavaWithJavac` passou. Golden cobre grayscale/peek, rect+translate e region clipping; #702 não executou `connectedAndroidTest`, portanto não declarar equivalência visual em runtime. |
| Softcut ownership | Proteções aprovadas em JVM | `SoftcutOutputOwnershipTest` 27/27; isso não autoriza seis vozes. Roteamento estéreo independente e ausência de duplicação ainda são gates obrigatórios. |

### Validação JUnit #702

- Run `43953135174`, HEAD `bc35c0f46d68b1b684a86ed083b8813ca0e5a055`: **667 testes, 0 falhas, 0 ignorados, 100%**; 75 relatórios HTML de classe inspecionados (77 páginas HTML no total).
- Verificações nativas de áudio/hardware, Mi-UGens, SynthDefs Softcut, fixtures upstream e JNI/Faust ARM64 passaram. `testDebugUnitTest` e compilação dos testes instrumentados AndroidX passaram.
- Limite: compilação dos testes instrumentados não é execução em emulador/dispositivo. Sem resultado de `connectedAndroidTest`, os golden Screen permanecem compilados, mas não executados.
- Segurança de áudio: PolyPerc e fallback Java preservados; não houve mudança no caminho de produção de áudio nesta etapa. Exclusividade Oboe/AudioTrack, mute/drain e restrições Softcut permanecem. Seis vozes bloqueadas até prova de estéreo e anti-duplicação.
- Repositório: trabalho somente em `migration/reconcile-jo1ngf-2026-11-12`; `main` preservada no SHA `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`.


## Stratum LFO menu bridge — diagnóstico do runtime Android (2026-11-16)

- O log do aparelho apontou `lib/stratum_lfo.lua:144` ao executar `_menu.rebuild_params()`. A linha corresponde ao upstream fixado `mrmattallison/stratum@8c7ca83e0b4ff8f3b20f170a68d7b782cf98ba74`; o Android `boot.lua` não expunha o objeto interno `_menu`.
- O teste anterior mascarava a ausência ao injetar `_menu = {rebuild_params=function() end}` no mock. Esse mock foi removido; o teste agora extrai a ponte do `boot.lua` de produção e exige que a chamada atualize os parâmetros Android por `host.params_changed()`.
- Correção em `boot.lua`: cria `_menu` somente se ausente e fornece `rebuild_params()` quando o host não oferece a implementação norns. A ponte encaminha a atualização para `params.on_change`, preservando uma implementação existente e sem alterar o script upstream.
- JUnit #709 / run `43980596446`: relatório HTML integral inspecionado — 667 testes, 0 falhas, 0 ignorados. StratumCompatibilityTest 7/7; EngineStratumCommandTest 7/7. O job global falhou somente porque o emulador não apareceu no ADB (emulator-5554 not found, timeout); Screen instrumentation não chegou a executar.
- APK de integração #768 no HEAD `6711d96eef1b10e35146ac06dff789577982a868` contém a ponte de menu, MiRings.so, MiClouds.so e NornsStratum.scsyndef. SHA-256 APK: `92f85e1b9075d25015b3cae68e733946963f676dc90c94ced417615d2da4e282`.
- Tentativa 2 do job Android de #709 foi solicitada e está em execução; resultado do emulador ainda pendente.
- **Status:** a correção `_menu.rebuild_params()` está coberta por teste JVM com módulos upstream reais. Ainda não declarar Stratum validado fisicamente nem compatibilidade áudio completa. Sem fallback PolyPerc.


## Stratum — runtime nativo e estado de validação (2026-11-17)

| Item | Estado | Evidência / pendência |
|---|---|---|
| Presença dos plugins | Implementado | Verifica MiRings.so + MiClouds.so no diretório privado, somente com scsynth ativo. Presença do arquivo não prova registro do UGen. |
| SynthDef Stratum | Compilado/empacotado | `NornsStratum.scsyndef` com MiRings → MiClouds → saída estéreo; build e validador passaram. |
| Seleção do engine | Implementado, fail-closed | Stratum só é selecionado com os dois plugins; falha/timeout não substitui por PolyPerc. |
| Node nativo e controles | Parcial | Criação via `sc_api.new` após `/sync`; contratos Lua/JVM cobrem os 25 comandos, mas falta confirmação runtime `/n_go`, erro `/fail /s_new` e resposta dos controles. |
| Áudio Stratum | Não validado | Ainda falta smoke test audível e confirmação de parâmetros no Android. |
| Screen golden | Compilado; execução pendente | #709 tentativa 2 não iniciou o emulador por falta de espaço na partição userdata; não houve execução de `ScreenSurfaceGoldenTest`. |

### JUnit #709 — tentativa 2

- Run `43980596446`, commit `dc4ba06837874f93e12a29b6cb4a65482a0c4aea`: 667 testes JVM, 0 falhas, 0 ignorados; StratumCompatibilityTest 7/7, EngineStratumCommandTest 7/7, ScreenApiContractTest 15/15.
- Compilação dos testes AndroidX passou. ScreenSurfaceGoldenTest não executou: QEMU reportou 6437.03 MB livres para uma partição de 7372.80 MB; o processo terminou e ADB perdeu `emulator-5554`.
- Correção CI: AVD com userdata de 2048 MB via `tools/configure-ci-emulator.sh` e `pre-emulator-launch-script`; nova execução marcada [run-ci] pendente.


## Renderer Java vs. engine nativo — Stratum (2026-11-17)

- `LuaHost.javaEngineSelection()` mapeia Stratum, 32Harmony e SimpleDelayKolor para `none` no SynthCore Java. Isso elimina o aviso falso de engine Java não implementada e impede substituição acidental; a rota scsynth permanece independente. PolyPerc/TestSine/FM não são afetados.
- Teste de regressão adicionado em `EngineStratumCommandTest`; JUnit #715 pendente.
- A criação do node Stratum ainda precisa de confirmação server-side: `sc_api.new` retorna sucesso de envio OSC, enquanto `/fail /s_new` não é correlacionado no estado atual. Não considerar o áudio validado até esse handshake, confirmação de plugin/UGen e smoke test paramétrico/audível.


## JUnit #715 — Screen golden e correção do runner (2026-11-17)

- 668 testes JVM passaram, sem falhas; compilação AndroidX passou. `ScreenSurfaceGoldenTest` não executou porque o script pré-boot do AVD falhou com `Permission denied`; QEMU então repetiu o erro de partição de 7372.80 MB versus 6436.95 MB livres.
- Workflow corrigido para executar `bash tools/configure-ci-emulator.sh`; run #716 pendente. Golden pixels permanecem sem validação runtime até conclusão desse run.


## JUnit #716 — limite de userdata via AVD config não aplicado (2026-11-17)

- 668 testes JVM passaram; Screen instrumentado compilou, mas não executou. O script pré-boot foi chamado com Bash e gravou `disk.dataPartition.size=2048M`; ainda assim o QEMU pediu 7372.80 MB, indicando que o tamanho inicial da imagem de userdata prevaleceu.
- Workflow atualizado com `-partition-size 2048` para impor o tamanho da partição na linha de comando; run #717 pendente. Screen golden permanece pendente.


## JUnit #717 — resultado e coleta de diagnóstico instrumentado (2026-11-17)

- Run `44115981768`, SHA `8c853d795841a6f5f20b4cf29e384399eb9320f4`: **668 testes JVM, 0 falhas, 0 ignorados**. StratumCompatibilityTest 7/7; EngineStratumCommandTest 8/8; NornsScriptLifecycleTest 9/9; ScreenApiContractTest 15/15; SoftcutOutputOwnershipTest 27/27.
- Android Emulator 35 iniciou; o job manteve ADB e executou `connectedDebugAndroidTest`. A tarefa falhou porque o processo de instrumentação caiu antes de qualquer resultado: `Test run failed to complete. Instrumentation run failed due to Process crashed`. Zero testes instrumentados foram concluídos; `ScreenSurfaceGoldenTest` permanece não executado.
- Não há stacktrace de `AndroidRuntime` nem `FATAL EXCEPTION` no log do job. Causa ainda indeterminada; não classificar como falha de pixels nem atribuir a engine/audio sem logcat.
- Workflow agora captura logcat, getprop, processos e estado do pacote após o Gradle, e publica relatórios Android instrumentados mesmo quando o comando falha. Próxima execução deverá fornecer evidência suficiente para corrigir a causa e reexecutar.
- Nenhuma alteração de produção de áudio/Softcut nesta rodada; PolyPerc e fallback Java preservados, sem ativar seis vozes.


## JUnit #709 — tentativa 2 e auditoria adicional do runtime Stratum (2026-11-17)

- Run `43980596446`, job `133736857103`: tentativa 2 terminou por falha do runner/emulador (`emulator-5554` ausente durante o boot; ADB não conseguiu localizar o dispositivo). Não houve execução instrumentada; `ScreenSurfaceGoldenTest` continua **não executado** nesta tentativa.
- Artefato `junit-709-reports.zip` (80 arquivos HTML) inspecionado: 667 testes JVM, 0 falhas, 0 ignorados (100%). StratumCompatibilityTest 7/7; EngineStratumCommandTest 7/7; NornsScriptLifecycleTest 9/9; ScreenApiContractTest 15/15. Esse relatório não substitui o resultado instrumentado.
- Separadamente, o run #717 (`44115981768`) iniciou Android Emulator 35 e manteve ADB, mas `connectedDebugAndroidTest` terminou com `Instrumentation run failed due to Process crashed`; nenhum teste instrumentado foi concluído. Workflow alterado para capturar logcat, getprop, processos e package state após a tarefa, e publicar relatórios Android mesmo em falha; run #719 está validando essa coleta.
- Auditoria do caminho nativo Stratum: `sc_api.new()` retorna o node ID após aceitar o envio OSC de `/s_new`; a inicialização atual usa `/sync` para confirmar `/d_recv`, mas não verifica explicitamente `/fail /s_new` antes de marcar o engine como iniciado. Isso deixa uma lacuna de observabilidade/validação de criação do node. Próxima alteração deve adicionar uma barreira de sincronização após `/s_new`, consumir e classificar `/fail /s_new`, e manter Stratum silencioso/em erro explícito se a criação falhar — nunca selecionar PolyPerc.
- Sem mudanças de produção de áudio nesta auditoria. PolyPerc e fallback Java preservados; ownership Oboe/AudioTrack e gates Softcut preservados; seis vozes Softcut permanecem bloqueadas até prova de roteamento estéreo e ausência de duplicação.
- `main` permanece em `a9b027fc1d87d815fd62760fd4fac9d49abcda7d`; trabalho somente na branch autorizada.
