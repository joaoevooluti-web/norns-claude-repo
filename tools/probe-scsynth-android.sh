#!/usr/bin/env bash
# Probe v2 — SuperCollider (scsynth) para Android ARM64.
#
# O que este script FAZ:   clona o SuperCollider, configura com o NDK, compila libscsynth e
#                          tenta compilar cada pacote de UGens, listando o que passa e o que falha.
# O que ele FAZ agora:      integra um driver Oboe/AAudio real ao target libscsynth.
# O que ele NÃO valida:     não comprova ainda boot em aparelho nem áudio audível.
#
# Todo o diagnóstico vai para o RESUMO da execução no GitHub Actions (aba Summary), em blocos
# curtos que dá para copiar do celular.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${SC_PROBE_DIR:-$ROOT/.sc-probe}"
SRC="$WORK/supercollider"
BUILD="$WORK/build-arm64"
LOGS="$WORK/logs"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
TAG="${SC_TAG:-Version-3.14.1}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"
JOBS="${CMAKE_BUILD_PARALLEL_LEVEL:-$(nproc 2>/dev/null || echo 2)}"

mkdir -p "$WORK" "$LOGS"
: > "$LOGS/summary-body.md"
STAGE="início"
STAGE_LOG="none.log"

say()   { echo; echo "##### $* #####"; }
stage() { STAGE="$1"; say "ETAPA: $1"; }
md()    { printf '%s\n' "$*" >> "$LOGS/summary-body.md"; }

# Resume erros de um log em poucas linhas (mensagens únicas com contagem).
summarize_log() {
  local f="$1"
  [[ -s "$f" ]] || { echo "(log vazio)"; return 0; }
  echo "--- CMake Error ---"
  grep -n -A7 -E "CMake Error" "$f" | head -n 50 || true
  echo "--- cabeçalhos ausentes ---"
  grep -h -o -E "fatal error: '[^']+' file not found|fatal error: [^ ]+: No such file or directory" "$f" \
    | sort | uniq -c | sort -rn | head -n 15 || true
  echo "--- erros do compilador (únicos) ---"
  grep -h -E "(^|[ :])error:" "$f" | grep -v -E "fatal error: .*not found" \
    | sed -E 's#^[^ ]*/(server|common|lang|include|external_libraries)/#\1/#' \
    | sort | uniq -c | sort -rn | head -n 25 || true
  echo "--- símbolos indefinidos (únicos) ---"
  grep -h -o -E "undefined (reference|symbol)[^']*'?[^ ]*" "$f" | sort | uniq -c | sort -rn | head -n 20 || true
  return 0
}

write_summary() {   # $1 = título
  {
    echo "## $1"
    echo
    echo "**Etapa:** $STAGE"
    echo
    cat "$LOGS/summary-body.md"
  } >> "$SUMMARY"
}

on_error() {
  local code="$1"
  trap - ERR
  echo "::error::Falha na etapa: $STAGE (código $code)"
  md "### Falha na etapa: $STAGE"
  md "Copie o bloco abaixo e envie na conversa:"
  md '```'
  {
    summarize_log "$LOGS/$STAGE_LOG"
    echo "--- últimas linhas do log ($STAGE_LOG) ---"
    tail -n 25 "$LOGS/$STAGE_LOG" 2>/dev/null || true
  } >> "$LOGS/summary-body.md" 2>&1 || true
  md '```'
  write_summary "❌ Probe do scsynth falhou"
  exit "$code"
}
trap 'on_error $?' ERR

# ------------------------------------------------------------------ 1) ambiente
stage "1/7 ambiente (NDK, CMake, Ninja)"
STAGE_LOG="env.log"
if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo "ANDROID_NDK_HOME/ANDROID_NDK_ROOT não aponta para um NDK válido: '$NDK'" | tee "$LOGS/env.log"
  false
fi
{
  echo "NDK: $NDK"; grep -E "Pkg.Revision" "$NDK/source.properties" || true
  cmake --version | head -n1
  ninja --version
  git --version
  python3 --version
} 2>&1 | tee "$LOGS/env.log"

# ------------------------------------------------------------------ 2) clone
stage "2/7 clonar SuperCollider $TAG"
STAGE_LOG="clone.log"
if [[ ! -f "$SRC/CMakeLists.txt" ]]; then
  rm -rf "$SRC"
  ok=0
  for attempt in 1 2 3; do
    echo "tentativa $attempt..."
    if git clone --filter=blob:none --recurse-submodules --shallow-submodules \
         --branch "$TAG" https://github.com/supercollider/supercollider.git "$SRC" 2>&1 | tee "$LOGS/clone.log"; then
      ok=1; break
    fi
    rm -rf "$SRC"; sleep 5
  done
  [[ "$ok" == 1 ]]
fi
echo "commit: $(git -C "$SRC" rev-parse HEAD)" | tee -a "$LOGS/clone.log"
md "- SuperCollider \`$TAG\` @ \`$(git -C "$SRC" rev-parse --short HEAD)\`"

# ------------------------------------------------------------------ 3) patch tolerante (helper Python separado; evita heredoc dentro de pipeline)
stage "3/7 integrar driver Android e Oboe"
STAGE_LOG="patch.log"
{
  echo "--- referências AUDIOAPI nos arquivos CMake ---"
  (cd "$SRC" && grep -rn --include=CMakeLists.txt --include='*.cmake' "AUDIOAPI" lang server | head -n 80) || true
  python3 "$ROOT/tools/patch-scsynth-android.py" "$SRC"
} 2>&1 | tee "$LOGS/patch.log"

# ------------------------------------------------------------------ 4) configurar
stage "4/7 configurar (cmake)"
STAGE_LOG="configure.log"
rm -rf "$BUILD"
cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CXX_STANDARD=17 \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DAUDIOAPI=android \
  -DSC_QT=OFF -DSC_IDE=OFF -DSC_ED=OFF -DSC_EL=OFF -DSC_VIM=OFF \
  -DSUPERNOVA=OFF -DNO_X11=ON -DNO_AVAHI=ON -DSC_HIDAPI=OFF \
  -DSC_ABLETON_LINK=OFF -DSYSTEM_PORTAUDIO=OFF \
  -DSYSTEM_BOOST=OFF -DSYSTEM_YAMLCPP=OFF \
  -DNO_LIBSNDFILE=ON -DFFT_GREEN=ON \
  -DLIBSCSYNTH=ON -DSTATIC_PLUGINS=OFF \
  -DINSTALL_HELP=OFF -DENABLE_TESTSUITE=OFF -DSCLANG_SERVER=OFF \
  2>&1 | tee "$LOGS/configure.log"
md "- Configuração (CMake): **OK**"

# ------------------------------------------------------------------ 5) libscsynth
stage "5/7 compilar libscsynth (continua após erros: -k 0)"
STAGE_LOG="libscsynth.log"
trap - ERR          # falha aqui é esperada e tratada abaixo; não deve abortar o script
set +e
cmake --build "$BUILD" --target libscsynth --parallel "$JOBS" -- -k 0 2>&1 | tee "$LOGS/libscsynth.log"
LIB_STATUS=${PIPESTATUS[0]}
set -e
trap 'on_error $?' ERR
if [[ "$LIB_STATUS" -eq 0 ]]; then
  md "- libscsynth: **compilou**"
  STAGE_LOG="symbols.log"
  LIBRARY="$(find "$BUILD" -type f -name 'libscsynth.so' | head -n 1)"
  LLVM_NM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
  if [[ -z "$LIBRARY" || ! -x "$LLVM_NM" ]]; then
    echo "libscsynth.so ou llvm-nm não encontrado" | tee "$LOGS/symbols.log"
    false
  fi
  "$LLVM_NM" -D --undefined-only "$LIBRARY" > "$LOGS/symbols.log"
  if grep -E 'SC_NewAudioDriver|server_timeseed|oscTimeNow|initializeScheduler' "$LOGS/symbols.log"; then
    echo "Símbolos obrigatórios do driver Android permanecem indefinidos" | tee -a "$LOGS/symbols.log"
    false
  fi
  md "- Símbolos do driver (SC_NewAudioDriver/timing): **resolvidos**"
else
  md "- libscsynth: **falhou** (resumo dos erros abaixo)"
  md '```'
  summarize_log "$LOGS/libscsynth.log" >> "$LOGS/summary-body.md" 2>&1 || true
  md '```'
fi

# ------------------------------------------------------------------ 6) UGens
stage "6/7 compilar cada pacote de UGens"
STAGE_LOG="ugens.log"
: > "$LOGS/ugens.log"
TARGETS="$(ninja -C "$BUILD" -t targets all 2>/dev/null | cut -d: -f1 | grep -E 'UGens' | grep -v -E '/|\.' | sort -u || true)"
OKS=(); BADS=()
for t in $TARGETS; do
  echo "=== $t ===" >> "$LOGS/ugens.log"
  if cmake --build "$BUILD" --target "$t" --parallel "$JOBS" > "$LOGS/ugens-$t.log" 2>&1; then
    OKS+=("$t")
  else
    BADS+=("$t")
    cat "$LOGS/ugens-$t.log" >> "$LOGS/ugens.log"
  fi
done
md "- UGens que compilaram (${#OKS[@]}): ${OKS[*]:-nenhum}"

# ------------------------------------------------------------------ 7) MiRings + MiClouds para o script Stratum
stage "7/7 compilar MiRings e MiClouds (mi-UGens ARM64)"
STAGE_LOG="mi-ugens.log"
bash "$ROOT/tools/build-mi-ugens-android.sh" 2>&1 | tee "$LOGS/mi-ugens.log"
md "- MiRings.so: **ARM64 compilado**"
md "- MiClouds.so: **ARM64 compilado**"
# Escape Markdown backticks so Bash does not execute the pinned commit as a command.
md "- mi-UGens: commit \`10f6824f68ad63475f67dfe3fe5016e2d2634f84\`"

md "- UGens que falharam (${#BADS[@]}): ${BADS[*]:-nenhum}"
if [[ "${#BADS[@]}" -gt 0 ]]; then
  md '```'
  summarize_log "$LOGS/ugens.log" >> "$LOGS/summary-body.md" 2>&1 || true
  md '```'
fi

# ------------------------------------------------------------------ resultado
find "$BUILD" -type f \( -name 'libscsynth.so' -o -name 'libscsynth.a' -o -name '*UGens*.so' -o -name 'MiRings.so' -o -name 'MiClouds.so' \) -print > "$LOGS/artifacts.txt" || true
md "- Bibliotecas geradas: $(wc -l < "$LOGS/artifacts.txt")"
md ""
md "**Importante:** compilar o driver não comprova áudio audível em hardware. A validação final exige boot e teste no Galaxy S10."
trap - ERR
if [[ "$LIB_STATUS" -eq 0 ]]; then
  write_summary "✅ Probe do scsynth: núcleo compilou"
else
  write_summary "⚠️ Probe do scsynth: núcleo NÃO compilou"
  exit 1
fi
