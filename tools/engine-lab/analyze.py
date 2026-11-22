#!/usr/bin/env python3
"""Lê o relatório do lab (osc.log, synthdefs/*.scsyndef, sclang.log) e resume por engine."""
import json, os, re, struct, sys

def pstr(b, o):
    n = b[o]; return b[o + 1:o + 1 + n].decode("latin1"), o + 1 + n

def parse_def(path):
    b = open(path, "rb").read()
    assert b[:4] == b"SCgf"
    ver, ndefs = struct.unpack(">ih", b[4:10]); o = 10
    out = []
    for _ in range(ndefs):
        name, o = pstr(b, o)
        nc = struct.unpack(">i", b[o:o + 4])[0]; o += 4 + 4 * nc
        npar = struct.unpack(">i", b[o:o + 4])[0]; o += 4 + 4 * npar
        npn = struct.unpack(">i", b[o:o + 4])[0]; o += 4
        params = []
        for _ in range(npn):
            pn, o = pstr(b, o); o += 4; params.append(pn)
        nu = struct.unpack(">i", b[o:o + 4])[0]; o += 4
        ugens = []
        for _ in range(nu):
            cn, o = pstr(b, o)
            o += 1
            ni, no = struct.unpack(">ii", b[o:o + 8]); o += 8
            o += 2 + 8 * ni + no
            ugens.append(cn)
        out.append({"name": name, "params": params, "ugens": sorted(set(ugens))})
        o += 0
        nv = struct.unpack(">h", b[o:o + 2])[0]; o += 2
        for _ in range(nv):
            _, o = pstr(b, o); o += 4 * npar
    return out

CORE = {"Control", "AudioControl", "TrigControl", "LagControl", "OutputProxy"}

def plugin_names(plugin_dir):
    blobs = []
    for f in os.listdir(plugin_dir):
        if f.endswith(".so"):
            blobs.append(open(os.path.join(plugin_dir, f), "rb").read())
    return blobs

def main(report_dir, plugin_dir):
    blobs = plugin_names(plugin_dir) if plugin_dir and os.path.isdir(plugin_dir) else []
    summary = {}
    for eng in sorted(os.listdir(report_dir)):
        d = os.path.join(report_dir, eng)
        if not os.path.isdir(d): continue
        log = open(os.path.join(d, "osc.log"), errors="replace").read() if os.path.exists(os.path.join(d, "osc.log")) else ""
        sc = open(os.path.join(d, "sclang.log"), errors="replace").read() if os.path.exists(os.path.join(d, "sclang.log")) else ""
        defs = []
        sd = os.path.join(d, "synthdefs")
        if os.path.isdir(sd):
            for f in sorted(os.listdir(sd)):
                try: defs += parse_def(os.path.join(sd, f))
                except Exception as e: defs.append({"name": f, "error": str(e)})
        engine_defs = [x for x in defs if x.get("name") not in ("adc", "patch_stereo", "patch_pan", "amp_env", "pitch", "monitor_stereo", "monitor_pan") ]
        ugens = sorted({u for x in engine_defs for u in x.get("ugens", [])} - CORE)
        missing = [u for u in ugens if blobs and not any(u.encode() in bl for bl in blobs)]
        cmds = re.findall(r"^### CMD (\S+) (\S*) args=", log, re.M)
        loaded = "ENGINE_LOAD_OK" in log
        compile_err = re.findall(r"^(ERROR: .*|Class not defined.*)$", sc, re.M)[:5]
        # mensagens OSC enviadas ao servidor entre CMD e o próximo marcador
        per_cmd = {}
        cur = None
        for line in log.splitlines():
            m = re.match(r"### CMD (\S+) ", line)
            if m: cur = m.group(1); per_cmd[cur] = []; continue
            if line.startswith("###"): cur = None; continue
            if cur: per_cmd[cur].append(line)
        silent = [c for c, _ in cmds if not per_cmd.get(c)]
        summary[eng] = {
            "loaded": loaded, "compile_errors": compile_err,
            "synthdefs": [x["name"] for x in engine_defs],
            "ugens": ugens, "ugens_missing_in_app": missing,
            "commands": len(cmds), "commands_sending_osc": len(cmds) - len(silent),
            "commands_without_osc": silent,
            "polls": (re.findall(r"^### POLLS (.*)$", log, re.M) or [""])[0],
        }
        with open(os.path.join(d, "commands.json"), "w") as fh:
            json.dump(per_cmd, fh, indent=1)
    with open(os.path.join(report_dir, "summary.json"), "w") as fh:
        json.dump(summary, fh, indent=1, ensure_ascii=False)
    lines = ["# Engine lab\n"]
    for k, v in summary.items():
        lines.append(f"## {k}: {'carregou' if v['loaded'] else 'NÃO carregou'}")
        lines.append(f"- SynthDefs: {len(v['synthdefs'])}; comandos: {v['commands']} ({v['commands_sending_osc']} geram OSC)")
        lines.append(f"- UGens usados: {', '.join(v['ugens']) or '-'}")
        lines.append(f"- UGens sem plugin no app: {', '.join(v['ugens_missing_in_app']) or 'nenhum'}")
        if v["compile_errors"]: lines.append("- erros: " + " | ".join(v["compile_errors"]))
        lines.append("")
    open(os.path.join(report_dir, "SUMMARY.md"), "w").write("\n".join(lines))
    print("\n".join(lines))

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
