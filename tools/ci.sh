#!/usr/bin/env bash
# Norns Mobile — passo de build executado pelo GitHub Actions.
# 1) guarda o projeto descompactado no repositório  2) compila o APK  3) publica (Release + Artifact)
set -uo pipefail
cd "$(dirname "$0")/.."

SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"
RUN="${GITHUB_RUN_NUMBER:-0}"
VER="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -n1)"
VER="${VER:-0.0.0}"
echo "== Norns Mobile ${VER} (build ${RUN}) =="

# ---- 1) commit do projeto descompactado (o ZIP enviado é removido do repositório)
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A
if ! git diff --cached --quiet; then
  git commit -q -m "Norns Mobile ${VER}: projeto atualizado (build ${RUN})"
  git push -q origin "HEAD:${GITHUB_REF_NAME:-main}" || echo "aviso: não consegui enviar o commit; seguindo com a compilação."
fi

# ---- 2) compilação
mkdir -p dist
gradle --no-daemon --console=plain assembleRelease 2>&1 | tee build.log
STATUS=${PIPESTATUS[0]}

if [ "$STATUS" -ne 0 ]; then
  ERRS="$(grep -E -A4 -e ': error:' -e '^e: ' -e 'What went wrong' build.log | head -n 90)"
  [ -n "$ERRS" ] || ERRS="$(tail -n 40 build.log)"
  {
    echo "## ❌ A compilação falhou"
    echo
    echo "Copie o bloco abaixo e envie ao Claude para corrigir:"
    echo
    echo '```'
    echo "$ERRS"
    echo '```'
  } >> "$SUMMARY"
  echo "$ERRS"
  exit "$STATUS"
fi

# ---- 3) APK
APK_SRC="$(find app/build/outputs/apk/release -name '*.apk' | head -n1)"
if [ -z "$APK_SRC" ]; then
  echo "APK não encontrado após a compilação."
  echo "## ❌ APK não encontrado após a compilação" >> "$SUMMARY"
  exit 1
fi
APK="dist/NornsMobile-${VER}-b${RUN}.apk"
cp "$APK_SRC" "$APK"
echo "APK gerado: $APK ($(du -h "$APK" | cut -f1))"

# ---- 4) Release (download direto do .apk pelo navegador do celular)
REL=""
if command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}" ]; then
  TAG="build-${RUN}"
  TITLE="Norns Mobile ${VER} (build ${RUN})"
  NOTES="APK assinado. Baixe o arquivo .apk abaixo e instale no Android."
  SHA="$(git rev-parse HEAD)"
  if gh release create "$TAG" "$APK" --title "$TITLE" --notes "$NOTES" --prerelease --target "$SHA" \
     || gh release create "$TAG" "$APK" --title "$TITLE" --notes "$NOTES" --prerelease; then
    REL="https://github.com/${GITHUB_REPOSITORY}/releases/tag/${TAG}"
  else
    echo "aviso: não consegui criar a Release; use o Artifact desta execução."
  fi
fi

{
  echo "## ✅ APK pronto: NornsMobile-${VER}-b${RUN}.apk"
  echo
  if [ -n "$REL" ]; then
    echo "**Baixar no celular:** ${REL}"
    echo
  fi
  echo "Também está em **Artifacts**, no fim desta página (vem dentro de um ZIP)."
} >> "$SUMMARY"
