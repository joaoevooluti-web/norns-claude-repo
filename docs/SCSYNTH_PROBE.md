# Probe do scsynth no Android (v2)

## Por que o probe antigo sempre falhava

1. **Patch rígido.** O script exigia que `server/scsynth/CMakeLists.txt` **e** `lang/CMakeLists.txt`
   tivessem o texto `MATCHES "^(jack|coreaudio|portaudio|bela)$"`. Se o `lang` não tiver, o script
   abortava *antes do CMake*. A v2 ajusta qualquer arquivo que tenha o padrão e só exige um.
2. **`AUDIOAPI=android` não existe no SuperCollider.** Mesmo passando na validação, o CMake do
   `scsynth` só adiciona driver de áudio para `jack`, `portaudio`, `coreaudio` e `bela`.
   Para `android` nenhum `.cpp` de driver entra e `SC_AUDIO_API` fica indefinida. Resultado esperado:
   erros de compilação/ligação em símbolos do driver (por exemplo `SC_NewAudioDriver`,
   `server_timeseed`, `oscTimeNow`, `initializeScheduler`). A v2 usa `SC_AUDIO_API=-1` e permite
   símbolos indefinidos **só para o probe**, para ver o que mais quebra.
3. **O log parava no primeiro erro.** A v2 compila com `-k 0` (continua após erro) e resume tudo.

## Como ler o resultado

Abra a execução no Actions → **Summary**. O bloco em ``` ``` ``` é curto e copiável.
Ele lista: etapa, erros do CMake, cabeçalhos ausentes, erros únicos do compilador, símbolos
indefinidos, e quais pacotes de UGens compilam.

## O que vem depois (de verdade)

- **Driver de áudio**: um `SC_AudioDriver` externo, acionado pelo callback do Oboe/AAudio
  (mesmo padrão do crate `scsynth-sys`, que usa `AUDIOAPI=external`).
- **sclang**: os engines do norns são classes da *linguagem* SuperCollider (`Engine_*.sc`).
  `scsynth` sozinho só toca SynthDefs já compiladas. Sem `sclang`, os engines `.sc` não carregam.
  Caminho prático em paralelo: reimplementar os engines mais usados nativamente (como já feito
  com PolyPerc).


## Plataforma Android mínima

O probe usa `ANDROID_PLATFORM=android-28` e o aplicativo declara `minSdk = 28`.
A partir dessa versão, o requisito mínimo do Norns Mobile é Android 9 (API 28).
A mudança permite usar `glob()` e `globfree()` no build Android sem manter uma
implementação alternativa dessas funções para APIs antigas.
