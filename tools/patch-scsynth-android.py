#!/usr/bin/env python3
"""Patch reproducible SuperCollider 3.14.1 Android/Oboe backend build."""
import re
import shutil
import sys
from pathlib import Path

project = Path(__file__).resolve().parents[1]
root = Path(sys.argv[1]).resolve()
driver_source = project / "tools" / "SC_AndroidAudio.cpp"
if not driver_source.is_file():
    raise SystemExit(f"driver Android ausente: {driver_source}")

# Android's Bionic libc provides pthread_*; there is no separate libpthread.
root_cmake = root / "CMakeLists.txt"
root_text = root_cmake.read_text(encoding="utf-8")
needle = "find_package(Pthreads)"
if "Android uses Bionic" not in root_text:
    if needle not in root_text:
        raise SystemExit("find_package(Pthreads) não encontrado")
    root_text = root_text.replace(
        needle,
        needle + """

# Android uses Bionic: pthread_* is provided by libc, not a separate libpthread.
if(ANDROID)
    set(PTHREADS_FOUND TRUE)
    set(PTHREADS_INCLUDE_DIR "")
    set(PTHREADS_LIBRARY "")
    set(PTHREADS_LIBRARIES "")
endif()
""",
        1,
    )

# Fetch Oboe before server/scsynth is configured, then link it into libscsynth.
if "Norns Mobile: Oboe dependency" not in root_text:
    oboe_block = """
# Norns Mobile: Oboe dependency (Apache-2.0), pinned for reproducible Android builds.
if(ANDROID)
    include(FetchContent)
    set(OBOE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(OBOE_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(
        oboe
        GIT_REPOSITORY https://github.com/google/oboe.git
        GIT_TAG 1.9.3
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(oboe)
endif()

"""
    anchor = "add_subdirectory(server)"
    if anchor not in root_text:
        raise SystemExit("add_subdirectory(server) não encontrado no CMake principal")
    root_text = root_text.replace(anchor, oboe_block + anchor, 1)
root_cmake.write_text(root_text, encoding="utf-8")

# Permit AUDIOAPI=android in the upstream server and language CMake validation.
for rel in ("lang/CMakeLists.txt", "server/scsynth/CMakeLists.txt"):
    path = root / rel
    if not path.is_file():
        raise SystemExit(f"arquivo upstream ausente: {rel}")
    source = path.read_text(encoding="utf-8")
    updated, count = re.subn(
        r'(?m)(MATCHES\s+"\^\()([^"]*)(\)\$")',
        lambda match: match.group(1)
        + (match.group(2) if "android" in match.group(2).split("|") else match.group(2) + "|android")
        + match.group(3),
        source,
        count=1,
    )
    if count != 1:
        raise SystemExit(f"validação AUDIOAPI não encontrada em {rel}")
    path.write_text(updated, encoding="utf-8")

# Select the Android implementation inside the upstream scsynth target.
scsynth_cmake = root / "server/scsynth/CMakeLists.txt"
source = scsynth_cmake.read_text(encoding="utf-8")
if "SC_AndroidAudio.cpp" not in source:
    source_anchor = "if (AUDIOAPI STREQUAL jack)\n\tlist(APPEND scsynth_sources SC_Jack.cpp)"
    if source_anchor not in source:
        raise SystemExit("bloco de seleção do driver JACK não encontrado")
    source = source.replace(
        source_anchor,
        """if(AUDIOAPI STREQUAL android)
    list(APPEND scsynth_sources SC_AndroidAudio.cpp)
    add_definitions("-DSC_AUDIO_API=SC_AUDIO_API_ANDROID")
elseif (AUDIOAPI STREQUAL jack)
    list(APPEND scsynth_sources SC_Jack.cpp)""",
        1,
    )

link_anchor = "if (AUDIOAPI STREQUAL jack)\n\ttarget_link_libraries(libscsynth ${JACK_LINK_LIBRARIES})"
if "target_link_libraries(libscsynth oboe)" not in source:
    if link_anchor not in source:
        raise SystemExit("bloco de link dos drivers não encontrado")
    source = source.replace(
        link_anchor,
        """if(AUDIOAPI STREQUAL android)
    target_link_libraries(libscsynth oboe)
elseif (AUDIOAPI STREQUAL jack)
\ttarget_link_libraries(libscsynth ${JACK_LINK_LIBRARIES})""",
        1,
    )
scsynth_cmake.write_text(source, encoding="utf-8")

# Declare the Android backend enum used by SC_CoreAudio.h.
header = root / "server/scsynth/SC_CoreAudio.h"
header_text = header.read_text(encoding="utf-8")
if "SC_AUDIO_API_ANDROID" not in header_text:
    anchor = "#define SC_AUDIO_API_BELA 6"
    if anchor not in header_text:
        raise SystemExit("enum SC_AUDIO_API_BELA não encontrado")
    header_text = header_text.replace(anchor, anchor + "\n#define SC_AUDIO_API_ANDROID 7", 1)
    header.write_text(header_text, encoding="utf-8")

# World_New() in upstream 3.14.1 returned nullptr without cleaning up if the
# audio driver failed Setup/Start. Clean up so failed stream opens are retryable.
world_cpp = root / "server/scsynth/SC_World.cpp"
world_text = world_cpp.read_text(encoding="utf-8")
world_text = world_text.replace(
    """            if (!hw->mAudioDriver->Setup()) {
                scprintf("could not initialize audio.\\n");
                return nullptr;
            }
            if (!hw->mAudioDriver->Start()) {
                scprintf("start audio failed.\\n");
                return nullptr;
            }""",
    """            if (!hw->mAudioDriver->Setup()) {
                scprintf("could not initialize audio.\\n");
                World_Cleanup(world, false);
                return nullptr;
            }
            if (!hw->mAudioDriver->Start()) {
                scprintf("start audio failed.\\n");
                World_Cleanup(world, false);
                return nullptr;
            }""",
    1,
)
if "World_Cleanup(world, false);" not in world_text:
    raise SystemExit("falha ao adicionar cleanup no erro de inicialização do driver")
world_cpp.write_text(world_text, encoding="utf-8")

# Inject project-owned backend source into this exact upstream checkout.
shutil.copy2(driver_source, root / "server/scsynth/SC_AndroidAudio.cpp")
ring_header = project / "tools" / "NornsSpscFloatRing.h"
if not ring_header.is_file():
    raise SystemExit(f"ring SPSC ausente: {ring_header}")
shutil.copy2(ring_header, root / "server/scsynth/NornsSpscFloatRing.h")
diagnostic_header = project / "tools" / "SoftcutDiagnosticSnapshot.h"
if not diagnostic_header.is_file():
    raise SystemExit(f"snapshot de diagnóstico ausente: {diagnostic_header}")
shutil.copy2(diagnostic_header, root / "server/scsynth/SoftcutDiagnosticSnapshot.h")
signal_accumulator_header = project / "tools" / "SoftcutSignalAccumulator.h"
if not signal_accumulator_header.is_file():
    raise SystemExit(f"acumulador de sinal ausente: {signal_accumulator_header}")
shutil.copy2(signal_accumulator_header, root / "server/scsynth/SoftcutSignalAccumulator.h")
output_mixer_header = project / "tools" / "SoftcutOutputMixer.h"
if not output_mixer_header.is_file():
    raise SystemExit(f"mixer de saída ausente: {output_mixer_header}")
shutil.copy2(output_mixer_header, root / "server/scsynth/SoftcutOutputMixer.h")

engine_send_bus_header = project / "tools" / "SoftcutEngineSendBus.h"
if not engine_send_bus_header.is_file():
    raise SystemExit(f"bus de envio do engine ausente: {engine_send_bus_header}")
shutil.copy2(engine_send_bus_header, root / "server/scsynth/SoftcutEngineSendBus.h")

# Fail fast if the patch did not produce a complete CMake selection.
root_text = root_cmake.read_text(encoding="utf-8")
server_text = scsynth_cmake.read_text(encoding="utf-8")
if "FetchContent_MakeAvailable(oboe)" not in root_text:
    raise SystemExit("Oboe FetchContent não foi configurado")
if "target_link_libraries(libscsynth oboe)" not in server_text:
    raise SystemExit("libscsynth não está vinculado ao Oboe")
if "SC_AUDIO_API_ANDROID" not in header.read_text(encoding="utf-8"):
    raise SystemExit("SC_AUDIO_API_ANDROID não foi declarado")
if not (root / "server/scsynth/SC_AndroidAudio.cpp").is_file():
    raise SystemExit("driver não foi injetado no código upstream")
if not (root / "server/scsynth/SoftcutDiagnosticSnapshot.h").is_file():
    raise SystemExit("snapshot de diagnóstico não foi injetado no código upstream")
if not (root / "server/scsynth/SoftcutSignalAccumulator.h").is_file():
    raise SystemExit("acumulador de sinal não foi injetado no código upstream")
if not (root / "server/scsynth/SoftcutEngineSendBus.h").is_file():
    raise SystemExit("bus de envio do engine não foi injetado no código upstream")

print("SuperCollider Android: SC_AndroidAudio.cpp integrado ao target libscsynth")
print("Oboe: versão 1.9.3 (Apache-2.0), FetchContent")
print("scsynth: AUDIOAPI=android; sem símbolos indefinidos tolerados")
print("Bionic pthreads: fornecidos pela libc")
print("World_New: cleanup adicionado quando Setup/Start do driver falha")
