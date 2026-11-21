# Roteiro de verificação no Galaxy S10 (SM-G973F)

Escopo: caminho Java (`AudioTrack`/PolyPerc), seguro e sem abrir `native_gate_open`.
Registre resultado (OK/FALHA + observação) em cada item. Nenhum item abaixo
valida áudio nativo, Softcut nativo ou periféricos.

1. Instalar o APK e abrir o app (sem crash, biblioteca lista hello_norns,
   polyperc_seq, midi_test_seq, device_check).
2. `device_check`: tela renderiza; K2 toca nota; K3 liga/desliga metro 1 Hz
   (contador "metro" avança ~1/s); E1/E2/E3 alteram Hz/amp/cutoff.
3. Contador "clock" avança no ritmo do `clock_tempo` (60 bpm => 1/s).
4. `polyperc_seq`: K2 inicia/para; tempo (E1) muda o andamento sem travar.
5. PARAMS: valores alteram o som; PSET salvar/carregar/excluir.
6. LOG: sem exceções Lua durante os itens acima.
7. Recarregar o script 5 vezes seguidas: sem acúmulo de som/threads, sem crash.
8. Sair do app e voltar: áudio retoma ou fica mudo de forma controlada.
9. Anotar: latência percebida, estalos/underruns, consumo de CPU/temperatura.

Próxima etapa (só após este roteiro passar): provar `scsynth`/Oboe com TestSine.
