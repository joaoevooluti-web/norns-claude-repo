# Norns Mobile — reconciliação entre repositórios

Data da auditoria: 2026-11-12

## Destino oficial

- Repositório de continuidade: `joaoevooluti-web/nornsmobileevoo`.
- Branch de integração segura: `migration/reconcile-jo1ngf-2026-11-12`.
- Branch funcional de destino: `feature/0.4.5-android-audio-driver`.
- `main` deve permanecer intacta.

## Origem que recebeu desenvolvimento por engano

- Repositório: `jo1nGF/nornsmobile`.
- Branch: `feature/0.4.5-android-audio-driver`.
- HEAD observado: `0a878a1f8402e35657410ef080144105f9f3bf6e`.

## Estado observado na auditoria

- HEAD da branch funcional no destino: `2317f15b7b795a10b501fdd20def1741d91aec6a`.
- A branch funcional do destino diverge da sua branch estável: 233 commits de um lado e 8 do outro.
- A branch funcional da origem diverge da sua branch estável: 651 commits de um lado e 8 do outro.
- As bases estáveis dos dois repositórios não são o mesmo commit. Portanto, não fazer merge cego nem atualizar refs do destino diretamente para SHAs da origem.
- Não há status checks registrados nos dois HEADs consultados.
- Nenhum workflow, JUnit ou APK foi iniciado durante esta auditoria.

## Diferenças de arquivos confirmadas

Os seguintes arquivos têm blobs diferentes entre as duas branches funcionais:

- `app/src/main/assets/lua/boot.lua`
- `app/src/main/java/org/nornsmobile/app/LuaHost.java`
- `app/src/main/java/org/nornsmobile/app/SoftcutOutputOwnership.java`
- `app/src/main/java/org/nornsmobile/app/SynthCore.java`
- `app/src/test/java/org/nornsmobile/app/HardwareApiAvailabilityTest.java`
- `app/src/test/java/org/nornsmobile/app/EngineApiContractTest.java`
- `docs/ROADMAP_MOBILE.md`
- `docs/ROADMAP_NATIVE_AUDIO.md`

Também foi identificado que a origem contém `.github/workflows/unit-tests.yml`, ausente na branch funcional do destino.

## Regras para a reconciliação

1. Usar a branch `migration/reconcile-jo1ngf-2026-11-12` no repositório de destino para integrar as mudanças.
2. Comparar e portar por grupos funcionais, preservando alterações exclusivas já existentes no destino.
3. Não copiar cegamente `app/build.gradle.kts` nem workflows: o destino usa assinatura debug gerada pelo Android Gradle Plugin, enquanto a origem mantém credenciais fixas no arquivo Gradle; não transportar credenciais.
4. Revisar os gatilhos de Actions para evitar builds caros a cada commit de migração. Executar CI somente após fechar a reconciliação e os testes estáticos.
5. Preservar PolyPerc, fallback Java, `main` e `native_gate_open=false`; não liberar playback Softcut de seis vozes sem prova estéreo e de ausência de duplicação.
6. Após a reconciliação, atualizar a branch funcional do destino a partir da branch de migração e continuar exclusivamente em `joaoevooluti-web/nornsmobileevoo`.

## Próximos grupos de integração

- APIs Lua, gamepad, HID e ciclo de vida de dispositivos.
- Engines, parâmetros/PSET e compatibilidade de scripts.
- Softcut: ownership, mute/handoff, buffers e validação de roteamento.
- Testes JUnit e workflows, sem marcar como aprovados até execução real.


## Proteção de minutos de Actions aplicada na branch de migração

- [x] O workflow de APK agora só executa em `workflow_dispatch` ou em push cujo commit contenha `[run-ci]`.
- [x] O workflow de integração scsynth/Oboe recebeu a mesma proteção.
- Commits: build `7ef09e6463e15df70284c051ed6ea4f1e3d091f2`; integração `e5caf88dd2c7f7b518ed788e5555153460447dae`.
- A branch `migration/reconcile-jo1ngf-2026-11-12` não está incluída nos filtros de push desses workflows; estas alterações documentais e de proteção não iniciaram builds.
- A branch funcional do destino ainda deve receber a proteção somente depois da reconciliação, por PR/integração controlada.
- Não copiar a assinatura fixa/credenciais do Gradle da origem. O destino mantém a configuração de debug sem keystore privada fixa.


## Auditoria de conflitos funcionais — não usar substituição integral

A comparação dos blobs mostrou que os repositórios não são cópias equivalentes e que há melhorias exclusivas nos dois lados:

- `boot.lua`: origem com 4.838 linhas e destino com 3.895; há diferenças de compatibilidade de tela, fontes, leitura de arquivos e APIs. Fazer integração por função/API, não substituir o arquivo inteiro.
- `LuaHost.java`: os dois lados têm alterações exclusivas. A origem inclui rotas adicionais de Tape; o destino contém os auxiliares `KeyboardDeviceState`, `GamepadDpadState` e `HidButtonState`. Preservar ambos os conjuntos e unificar callbacks/lifecycle.
- `SynthCore.java`: a origem contém implementação 32Harmony e controles adicionais; o destino contém versões de FM/Pluck. A integração deve preservar FM, Pluck e 32Harmony, verificando IDs, comandos, fallback e SynthDefs.
- `HardwareApiAvailabilityTest.java` e `EngineApiContractTest.java`: a origem tem cobertura mais ampla para Grid/Arc, HID, engines e Tape; o destino tem contratos adicionais ligados ao estado por dispositivo. Unificar testes sem descartar nenhum cenário.
- `SoftcutOutputOwnership.java`: ambos têm proteção de mute/ownership, mas com estados internos e recuperação diferentes. Não substituir sem comparar transições e testes de falha.
- `ROADMAP_MOBILE.md` e `ROADMAP_NATIVE_AUDIO.md`: a origem registra etapas até 2026-11-12; o destino está em uma revisão anterior. Consolidar o histórico mantendo evidência e distinguindo implementação de teste executado.

Conclusão: a reconciliação é uma integração de funcionalidades, não um merge de histórico nem uma cópia de arquivos. Nenhuma dessas áreas foi declarada concluída ou validada por CI.


## Reconciliação aplicada — 2026-11-12

A branch `migration/reconcile-jo1ngf-2026-11-12` agora contém os seguintes grupos portados da branch de origem, preservando as implementações exclusivas do destino:

- [x] MIDI por porta física: `MidiPortIdRegistry`, bridge por porta, hotplug, mensagens/SysEx, retorno de aceitação, relógio MIDI separado do fluxo bruto dos scripts.
- [x] OSC: validação de UTF-8, NUL, padding, tamanho real do pacote, dados excedentes e rollover NTP de 2036; callbacks continuam fora do lock de transporte.
- [x] USB CDC: ciclo de vida/fechamento e configuração de baud/framing via termios.
- [x] serialosc: bridge Grid/Arc e testes de transporte.
- [x] Tape/WAV: `TapeCore`, leitura estéreo mapeada, escrita validada, reprodução/gravação e handlers Lua.
- [x] Softcut: roteamento de engine e Tape, validação dos buffers e handoff Java/nativo fail-closed; seis vozes continuam bloqueadas.
- [x] Saída de engine nativa: bus mono reservado 31, captura no próximo bloco e SynthDef do recorder com `engine_level=0` por padrão.
- [x] SynthCore: 32Harmony Java integrado sem substituir as implementações FM/Pluck do destino; aliases de damping preservados.
- [x] APIs Lua: `boot.lua` atualizado com filesystem/formatters, clock/metro, HID, gamepad, Crow e engine dispatch; handlers Java adicionais para Tape, MIDI, HID e tela.
- [x] Tela: 11 faces Norns/ALEPH e operações de texto trim/rotate/center-rotate.
- [x] Presets: controles de 16 slots na interface Params e callbacks Java/Lua.
- [x] Testes contratuais: MIDI, OSC, HID/gamepad, engines, 32Harmony, clock/metro, Crow, Tape/WAV, Softcut, USB CDC e APIs Norns.
- [x] Workflow JUnit adicionado com execução manual ou commit marcado `[run-ci]`; inclui contratos nativos C++/Python.

## Ainda pendente antes de promover para a branch funcional

1. Revisão estática final de todas as dependências e assinaturas entre os arquivos portados.
2. Comparar os demais arquivos alterados nas branches, especialmente os pontos de integração JNI/SC e documentação, para não perder alterações específicas.
3. Executar JUnit, contratos Python/C++, compilação Gradle e validação dos SynthDefs — somente após fechar a reconciliação.
4. Corrigir qualquer falha encontrada e então atualizar `feature/0.4.5-android-audio-driver` no repositório colaborador por integração controlada.
5. Manter `native_gate_open=false`, `engine_level=0), saída Java preservada e seis vozes Softcut bloqueadas até prova de roteamento estéreo e ausência de duplicação.

**Status:** código portado em branch de migração; não testado por CI nesta etapa; não promovido à branch funcional; nenhum APK gerado.


## Fechamento da comparação de arquivos

A comparação final das duas branches funcionais foi revisada arquivo a arquivo. Além dos grupos listados acima, também foram integrados:

- `app/src/main/assets/lua/lib/paramset.lua`: classe `Taper` não linear, preservando a implementação do destino para reset, defaults e leitura/escrita de presets.
- `app/src/main/java/org/nornsmobile/app/SuperColliderOsc.java`: validação de int32/float32, NUL, UTF-8 e padding de blobs.
- `app/src/test/java/org/nornsmobile/app/ScreenApiContractTest.java`, `SoftcutCoreTest.java` e `SuperColliderOscTest.java`: contratos adicionais portados.
- `tools/test-softcut-diagnostic-snapshot.cpp`: preservada a versão do destino, que já contém o cenário concorrente adicional.
- `app/src/main/java/org/nornsmobile/app/KeyboardDeviceState.java`, `HidButtonState.java` e `GamepadDpadState.java`: preservadas as implementações exclusivas do destino; não foram substituídas pelas versões mais antigas da origem.

A branch de migração está **78 commits à frente e 0 atrás** da branch funcional do destino, com base em `2317f15b7b795a10b501fdd20def1741d91aec6a`. Isso confirma que a integração foi construída sem descartar commits da branch funcional.

## Verificações ainda não executadas

- JUnit/Gradle: não executados.
- C++/Python contract checks: adicionados aos workflows, mas não executados.
- Compilação dos SynthDefs: não executada.
- APK: não gerado.
- Teste físico: não realizado.


## Revisão estática adicional — 2026-11-12

- [x] Confirmado que `AudioEngine.java`, `SuperColliderBridge.java`, `SoftcutCore.java`, `SuperColliderSoftcutBuffers.java`, `sc_bridge.cpp`, `AndroidManifest.xml`, `boot.lua`, `ParamsView.java`, `MidiBridge.java`, `ScreenSurface.java` e os principais contratos já correspondem à origem ou contêm melhorias exclusivas do destino.
- [x] Preservados os controles de entrada por dispositivo do destino (`KeyboardDeviceState`, `HidButtonState`, `GamepadDpadState`) e a proteção de callbacks OSC fora do lock.
- [x] Preservada a configuração Gradle do destino: debug usa assinatura temporária do Android Gradle Plugin; a chave fixa permanece reservada para release.
- [x] Confirmado que o destino já contém implementação Java 32Harmony, SynthDef nativo e testes dedicados; não substituir por versões antigas de FM/Pluck.
- [x] Corrigido um teste contraditório em `SynthCoreTest`: o caso que deveria verificar engine desconhecida estava testando `32Harmony`, apesar de o mesmo projeto implementar e testar 32Harmony como engine válida. Agora usa `not-an-engine`; o teste de 32Harmony continua separado.
- [x] Confirmado no validador que o recorder espera oito entradas (entrada física, send de engine e seis buses da matriz), exige bus 31 e nível inicial zero.
- [ ] Ainda não executados JUnit, compilação Gradle, compilação/validação do SynthDef ou testes C++/Python; a revisão estática não equivale a execução.


## Cobertura da comparação entre árvores

Comparação das duas branches em relação à respectiva base `feature/0.4.5-stable-continuation`:

- Origem `jo1nGF/nornsmobile`: 66 caminhos alterados.
- Destino `joaoevooluti-web/nornsmobileevoo`: 76 caminhos alterados.
- Caminhos alterados apenas na origem: **0** — todos os 66 caminhos da origem também estão presentes no conjunto de mudanças do destino.
- Caminhos adicionais no destino: 10, incluindo as três classes de estado de entrada por dispositivo e seus testes, o contrato dedicado de 32Harmony, o teste de ciclo de vida do Poll, o ajuste Gradle e este registro de migração.

Isso comprova cobertura por caminho, não equivalência binária nem aprovação funcional. As diferenças de conteúdo foram avaliadas individualmente nos arquivos críticos; diferenças do destino foram mantidas quando oferecem comportamento mais seguro ou testes adicionais.


- [x] Corrigida a ordem das verificações em `validate-synthdef.py`: os testes de dependência do canal de diagnóstico `incoming` agora só acessam `diagnostic_sources` depois que o vetor é extraído do UGen `Out`. Antes, o validador poderia acessar a variável antes da atribuição e falhar com `NameError`.


- [x] Portada a versão completa de `paramset.lua` da origem, pois a revisão dos contratos revelou que a versão anterior do destino ainda divergia em pontos essenciais: `get_range` retornava dois valores em vez de tabela; `params:add{...}` descartava `allow_pmap` e sobrescrevia `save=false`; `default()` não restaurava o último preset; leitura/escrita não cobria o formato Norns quoted-colon nem persistia o último slot. A versão portada preserva a implementação taper e mantém os callbacks numéricos conectados aos controles de preset do destino.


- [x] Corrigido um defeito adicional encontrado no parser de presets portado: a expressão Lua que reconhece linhas Norns `"id": valor` estava interrompida por uma quebra de linha dentro da string. Agora a expressão está completa, decodifica o ID quoted e mantém fallback para o formato legado `id=valor`.
- [x] Adicionado teste JUnit que compila `paramset.lua` como chunk LuaJ sem executar dependências de runtime, para detectar erros de sintaxe antes da validação no dispositivo.


- [x] Limpeza final do arquivo de parâmetros: a origem continha fragmentos de implementação duplicados após o primeiro `return ParamSet`, incluindo terminadores inválidos `return ParamSet)`. A versão migrada foi reconstruída a partir do primeiro corpo completo, com parser corrigido e apenas um terminador válido. O teste de compilação LuaJ agora cobre o arquivo limpo.

## Reconciliação adicional — Tape, Softcut e OSC (2026-11-12)

- [x] Corrigida uma troca acidental de conteúdo entre app/src/main/java/org/nornsmobile/app/TapeCore.java e app/src/test/java/org/nornsmobile/app/WavWriterTest.java: o arquivo de produção continha a classe de teste WAV e o arquivo de teste continha a implementação Tape. Os dois caminhos foram restaurados com seus conteúdos correspondentes da branch de origem.
- [x] TapeCore.java agora contém novamente a implementação de transporte Tape com playback mapeado, gravação em worker, controle de geração para impedir render antigo após stop/troca de fonte e envio independente para Softcut. WavWriterTest.java contém os testes de cabeçalho/amostras WAV.
- [x] Confirmado que TapeCoreTest.java e AudioEngine.java já correspondem à origem; a integração Tape não exige substituir AudioEngine.java.
- [x] Preservada a versão mais conservadora de SoftcutOutputOwnership.java do destino: ela mantém o Java em silêncio até confirmar o mute nativo, reduzindo o risco de áudio duplicado quando o JNI não confirma a operação.
- [x] Preservada a melhoria de OscBridge.java do destino, que captura o listener sob lock e executa callbacks fora do lock.
- [x] Confirmado que SC_AndroidAudio.cpp, SoftcutEngineSendBus.h, SoftcutCore.java, SuperColliderSoftcutBuffers.java, SuperColliderOsc.java, MidiBridge.java e MidiPortIdRegistry.java já correspondem à origem.
- [ ] JUnit, Gradle, testes C++/Python e workflows continuam pendentes; nenhuma validação de execução foi declarada.

- [x] Conferida a suíte de contratos: todos os métodos de teste da origem estão presentes no destino, exceto dois testes antigos de agregação global de eixos de gamepad. Eles foram substituídos pelo contrato do destino que isola eixos por dispositivo (`gamepadAxisCallbacksAreScopedPerDeviceAndRejectInvalidValues`), alinhado ao estado multi-dispositivo e à limpeza por desconexão; os demais testes exclusivos do destino foram mantidos.
- [x] Confirmado que o método Java `LuaHost.dispatchMidi(...)` é idêntico nas duas branches: mensagens MIDI realtime (Clock Start/Continue/Stop/Tick) chegam ao relógio Lua sem retirar os bytes MIDI originais do callback do script.
- [ ] A presença dos contratos e a comparação estática não substituem a execução; JUnit/Gradle e CI seguem bloqueados até concluir a auditoria restante.

- [x] Revisadas as diferenças restantes de entrada física: o destino substitui a liberação global simples de teclas por liberação por dispositivo (`KeyboardDeviceState`) e também libera botões do mouse; essa mudança é intencional e foi preservada.
- [x] Preservado o estado de eixos de gamepad por dispositivo no destino. Os dois testes antigos que agregavam eixos de controladores diferentes não foram copiados, pois contradizem o contrato atual de isolamento por dispositivo.
- [x] Confirmado que os testes adicionais exclusivos do destino para FM/Pluck, parâmetros, MIDI multiporta, IO Lua e lifecycle de teclado/mouse foram mantidos; os testes da origem comparados continuam cobertos.

## Revisão dos 8 commits da branch estável — 2026-11-12

- [x] Revisadas as alterações presentes em feature/0.4.5-stable-continuation e ausentes na ancestralidade da branch de migração. A diferença de histórico foi analisada por arquivo; não foi feito merge cego para evitar substituir implementações mais completas do destino.
- [x] As funções screen.text_trim, screen.text_rotate e screen.text_center_rotate já são operações nativas registradas por LuaHost e implementadas em ScreenSurface; ScreenApiContractTest cobre a ponte Java/Lua e confirma que boot.lua não as sobrescreve. Não foi copiada a implementação Lua redundante da branch estável.
- [x] A rotina resume_clock do destino já contempla fonte MIDI, waiters de clock.sync e troca entre relógio MIDI/interno; é mais completa que a versão da branch estável.
- [x] LuaHost e MainActivity do destino já contêm os fluxos de teclado/gamepad e o tratamento de perda de foco, pausa e desconexão por dispositivo. Foram acrescentados contratos AndroidGamepadApiContractTest e AndroidKeyboardApiContractTest adaptados ao gerenciamento multi-dispositivo do destino.
- [x] OscBridge do destino já permite broadcast UDP e mantém a melhoria adicional de executar callbacks fora do lock; o teste de OSC existente cobre os contratos de transporte.
- [ ] A branch conserva divergência de ancestralidade em relação à feature/0.4.5-stable-continuation (8 commits); os comportamentos relevantes foram reconciliados manualmente, mas a validação de execução permanece pendente.

- [x] Na reconciliação do SynthDef 32Harmony, mantida a interpolação linear manual do destino (sem SelectX, exigido pela validação portátil) e incorporados limites seguros da origem para steps, root, freqMult, attack e decay. Adicionado contrato JUnit para esses limites.
- [x] Mantido o contrato de recorder do destino com snapshot diagnóstico de 54 valores, compatível com SuperColliderSoftcutBuffers.java; o script da origem que esperava 52 valores estava desatualizado.
- [x] Preservados os testes adicionais do destino para concorrência do SoftcutDiagnosticSnapshot e as verificações mais completas de 32Harmony em validate-synthdef.py.


## Validação automatizada da reconciliação — 2026-11-12

- [x] Workflow **Norns Mobile — Unit Tests** aprovado no commit `7284c03d6eb242aae6a39797ad72187f22e53d35`, execução [43251088518](https://github.com/joaoevooluti-web/nornsmobileevoo/actions/runs/43251088518).
- [x] Verificações nativas executadas sem erro: `test-softcut-recorder-gate.py`, `validate-native-output-gate.py`, `validate-softcut-output-routing.py` e compilação/execução C++ do contrato `test-softcut-engine-send-bus.cpp`.
- [x] `gradle testDebugUnitTest` concluiu com **BUILD SUCCESSFUL**; compilação Java de produção e de testes também passou. A suíte JUnit inteira foi aprovada.
- [x] Corrigidos os dois defeitos de teste identificados na rodada anterior: o teste de `io.lines` agora inclui a implementação real e o contrato HID reconhece os parâmetros opcionais atuais.
- [x] APK, probe ARM64 e integração scsynth/Oboe permaneceram **skipped**, conforme o bloqueio desta fase. Nenhum APK foi gerado e nenhum gate nativo foi aberto.
- [ ] Ainda falta a validação/compilação independente dos SynthDefs e a revisão final das dependências de integração antes de promover a branch de migração para a branch funcional.
- [ ] Softcut continua sem autorização para playback nativo de seis vozes: o CI valida gates e contratos, mas não substitui prova física do caminho estéreo/BufWr nem teste acústico de ausência de duplicação.

**Resultado:** JUnit, compilação Java/Gradle de testes e contratos nativos passaram neste SHA. Isso não equivale a APK validado, integração Oboe aprovada ou teste físico.
