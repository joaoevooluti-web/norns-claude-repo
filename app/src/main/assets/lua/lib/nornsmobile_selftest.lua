-- nornsmobile_selftest: checagens de compatibilidade do Norns Mobile.
--
-- Usado pelo script mobile_selftest (tela + log) e pelo JUnit. O módulo não desenha nada e não usa
-- globais: recebe o ambiente (env, normalmente _G) com string, math, table, io, os, util, clock, metro,
-- engine, params, controlspec, screen, _path, load, require e tostring. Assim o mesmo código roda no
-- aparelho e, nos testes, sobre o runtime de produção (io/os do boot.lua, util, cjson, clock, metro).
--
-- Uma checagem devolve true (ok, com um detalhe opcional), false + detalhe (falha) ou "skip" + motivo.
-- As marcadas com known descrevem limitações conhecidas do LuaJ (Lua 5.2): quando falham o resultado é
-- "limite", não "falha". As assíncronas (clock, metro) recebem finish(ok, detalhe) e têm tempo máximo;
-- o cão de guarda usa o mecanismo que NÃO está sob teste, para um clock parado não travar a rodada.
--
-- Nada aqui usa string.format: o relatório precisa sair mesmo quando o string.format está quebrado.

local M = {}

local Suite = {}
Suite.__index = Suite

local DETAIL_MAX = 160

local function oneline(text)
  text = tostring(text)
  text = string.gsub(text, "\r", "\\r")
  text = string.gsub(text, "\n", "\\n")
  if #text > DETAIL_MAX then text = string.sub(text, 1, DETAIL_MAX - 3) .. "..." end
  return text
end

local function show(value)
  if type(value) == "string" then return "'" .. oneline(value) .. "'" end
  return tostring(value)
end

local function expect(actual, expected)
  if actual == expected then return true end
  return false, "obtido " .. show(actual) .. ", esperado " .. show(expected)
end

local function contains(list, value)
  for i = 1, #list do
    if list[i] == value then return true end
  end
  return false
end

local function deep_equal(a, b)
  if a == b then return true end
  if type(a) ~= "table" or type(b) ~= "table" then return false end
  for k, v in pairs(a) do
    if not deep_equal(v, b[k]) then return false end
  end
  for k in pairs(b) do
    if a[k] == nil then return false end
  end
  return true
end

-- Cão de guarda: dispara fn depois de seconds usando metro ("metro") ou clock ("clock").
-- Devolve uma função que cancela; nil quando o mecanismo não existe.
local function watchdog(env, kind, seconds, fn)
  if kind == "metro" and env.metro and env.metro.init then
    local m = env.metro.init(function() fn() end, seconds, 1)
    if m then
      m:start()
      return function()
        if m.free then m:free() else m:stop() end
      end
    end
  end
  if env.clock and env.clock.run then
    local id = env.clock.run(function()
      env.clock.sleep(seconds)
      fn()
    end)
    return function() env.clock.cancel(id) end
  end
  return nil
end

-- ---------------------------------------------------------------- as checagens

function M.new(env)
  env = env or _G
  local suite = setmetatable({env = env, checks = {}, running = false}, Suite)

  local function group_of(id) return string.match(id, "^([^/]+)/") or "geral" end

  local function sync(id, name, run, known)
    suite.checks[#suite.checks + 1] = {
      id = id, group = group_of(id), name = name, run = run, known = known and true or false,
    }
  end

  local function async(id, name, start, options)
    options = options or {}
    suite.checks[#suite.checks + 1] = {
      id = id, group = group_of(id), name = name, start = start, async = true, known = false,
      timeout = options.timeout or 3, watchdog = options.watchdog or "metro",
    }
  end

  local function cases(list)
    return function()
      for i = 1, #list do
        local c = list[i]
        local ok, got = pcall(env.string.format, c.f, c.v)
        if not ok then return false, c.f .. " lançou erro: " .. oneline(got) end
        if got ~= c.e then return false, c.f .. " deu " .. show(got) .. ", esperado " .. show(c.e) end
      end
      return true, #list .. " casos"
    end
  end

  -- ----- format: string.format e tostring seguem o printf do C (o LuaJ puro ignora largura e precisão)

  sync("format/float", "%f com largura e precisão", cases({
    {f = "%.2f", v = 1 / 3, e = "0.33"},
    {f = "%5.1f|", v = 3.14159, e = "  3.1|"},
    {f = "%-8.3f|", v = 2.5, e = "2.500   |"},
    {f = "%08.3f", v = -3.14159, e = "-003.142"},
    {f = "%.0f", v = 0.5, e = "0"},
    {f = "%.0f", v = 1.5, e = "2"},
    {f = "%10.4f|", v = 3.141592653589793, e = "    3.1416|"},
    {f = "%+.1f", v = 2.25, e = "+2.2"},
    {f = "%.3f", v = 2 / 3, e = "0.667"},
    {f = "%f", v = 1.5, e = "1.500000"},
  }))

  sync("format/int", "%d de 64 bits e flags", cases({
    {f = "%d", v = 2 ^ 40, e = "1099511627776"},
    {f = "%d", v = -(2 ^ 40), e = "-1099511627776"},
    {f = "%d", v = 2 ^ 53, e = "9007199254740992"},
    {f = "%5d|", v = 42, e = "   42|"},
    {f = "%-5d|", v = 42, e = "42   |"},
    {f = "%05d", v = -42, e = "-0042"},
    {f = "%+d", v = 5, e = "+5"},
    {f = "%i", v = 7, e = "7"},
    {f = "%d", v = 3.0, e = "3"},
    {f = "%03d", v = 7, e = "007"},
  }))

  sync("format/hex", "%x %X %o", cases({
    {f = "%x", v = 255, e = "ff"},
    {f = "%X", v = 255, e = "FF"},
    {f = "%x", v = -1, e = "ffffffffffffffff"},
    {f = "%#x", v = 255, e = "0xff"},
    {f = "%o", v = 8, e = "10"},
    {f = "%08x", v = 48879, e = "0000beef"},
  }))

  sync("format/expo", "%g e %e", cases({
    {f = "%g", v = 1e20, e = "1e+20"},
    {f = "%g", v = 0.0001, e = "0.0001"},
    {f = "%g", v = 1e-5, e = "1e-05"},
    {f = "%g", v = 100000, e = "100000"},
    {f = "%g", v = 1e6, e = "1e+06"},
    {f = "%g", v = 0.1, e = "0.1"},
    {f = "%.3e", v = 12345.678, e = "1.235e+04"},
    {f = "%G", v = 1e-10, e = "1E-10"},
    {f = "%.14g", v = 1 / 3, e = "0.33333333333333"},
  }))

  sync("format/str", "%s de qualquer tipo", function()
    local ok, detail = cases({
      {f = "%s", v = nil, e = "nil"},
      {f = "%s", v = true, e = "true"},
      {f = "%s", v = false, e = "false"},
      {f = "%5s|", v = "ab", e = "   ab|"},
      {f = "%-5s|", v = "ab", e = "ab   |"},
      {f = "%.2s", v = "abcdef", e = "ab"},
      {f = "%s", v = 1 / 3, e = "0.33333333333333"},
      {f = "%s", v = 10, e = "10"},
    })()
    if not ok then return ok, detail end
    local fine, text = pcall(env.string.format, "%s", {})
    if not fine or string.sub(tostring(text), 1, 7) ~= "table: " then
      return false, "%s de tabela deu " .. show(text)
    end
    return true, detail
  end)

  sync("format/misc", "%c e %%", cases({
    {f = "%c", v = 65, e = "A"},
    {f = "%5.1f%%", v = 99.44, e = " 99.4%"},
    {f = "%%", v = nil, e = "%"},
  }))

  sync("format/erros", "argumentos inválidos", function()
    local list = {
      {"%d", "x"}, {"%d", nil}, {"%y", 1}, {"%", 1},
    }
    for i = 1, #list do
      local ok = pcall(env.string.format, list[i][1], list[i][2])
      if ok then return false, "format('" .. list[i][1] .. "', " .. show(list[i][2]) .. ") deveria falhar" end
    end
    if pcall(env.string.format, "%d") then return false, "format('%d') sem argumento deveria falhar" end
    return true
  end)

  sync("format/tostring", "tostring de números", function()
    local huge = env.math.huge
    local list = {
      {1 / 3, "0.33333333333333"}, {100, "100"}, {0.1, "0.1"}, {-1.5, "-1.5"}, {1e15, "1e+15"},
      {2 ^ 53, "9.007199254741e+15"}, {huge, "inf"}, {-huge, "-inf"}, {123456.789, "123456.789"},
    }
    for i = 1, #list do
      local got = env.tostring(list[i][1])
      if got ~= list[i][2] then
        return false, "tostring deu " .. show(got) .. ", esperado " .. show(list[i][2])
      end
    end
    if env.tostring(nil) ~= "nil" or env.tostring(true) ~= "true" then
      return false, "tostring de nil/boolean"
    end
    return true, #list .. " casos"
  end)

  -- ----- limites conhecidos do LuaJ (Lua 5.2): "limite" quando faltam, "ok" se um dia passarem

  sync("limite/concat", "float com ..", function()
    local value = 1 / 3
    local text = value .. ""
    if text == "0.33333333333333" then return true end
    return false, show(text) .. " em vez de '0.33333333333333': o .. usa 7 dígitos; use string.format"
  end, true)

  sync("limite/math.type", "math.type (Lua 5.3)", function()
    if type(env.math.type) == "function" then return true end
    return false, "math.type não existe; o LuaJ é Lua 5.2"
  end, true)

  sync("limite/tointeger", "math.tointeger (Lua 5.3)", function()
    if type(env.math.tointeger) == "function" then return true end
    return false, "math.tointeger não existe; o LuaJ é Lua 5.2"
  end, true)

  sync("limite/table.move", "table.move (Lua 5.3)", function()
    if type(env.table.move) == "function" then return true end
    return false, "table.move não existe; o LuaJ é Lua 5.2"
  end, true)

  sync("limite/utf8", "biblioteca utf8 (Lua 5.3)", function()
    if type(env.utf8) == "table" then return true end
    return false, "utf8 não existe; o LuaJ é Lua 5.2"
  end, true)

  sync("limite/pack", "string.pack (Lua 5.3)", function()
    if type(env.string.pack) == "function" then return true end
    return false, "string.pack não existe; o LuaJ é Lua 5.2"
  end, true)

  sync("limite/operadores", "// e operadores bit a bit", function()
    if env.load("return 7 // 2") and env.load("return 6 & 3") then return true end
    return false, "// e & não compilam; o LuaJ é Lua 5.2 (use math.floor e bit32)"
  end, true)

  -- ----- cjson

  sync("cjson/encode", "encode", function()
    local cjson = env.require("cjson")
    local list = {
      {{1, 2, 3}, "[1,2,3]"},
      {{a = 1}, '{"a":1}'},
      {"a\"b\n", '"a\\"b\\n"'},
      {true, "true"},
      {cjson.null, "null"},
      {{x = {1, 2}}, '{"x":[1,2]}'},
      {{1, {2, 3}}, "[1,[2,3]]"},
    }
    for i = 1, #list do
      local ok, got = pcall(cjson.encode, list[i][1])
      if not ok then return false, "encode lançou erro: " .. oneline(got) end
      if got ~= list[i][2] then return false, "encode deu " .. show(got) .. ", esperado " .. show(list[i][2]) end
    end
    return true, #list .. " casos"
  end)

  sync("cjson/float", "encode de números", function()
    local cjson = env.require("cjson")
    local list = {
      {0.5, "0.5"}, {1 / 3, "0.33333333333333"}, {100, "100"}, {-0.25, "-0.25"}, {1e20, "1e+20"},
    }
    for i = 1, #list do
      local ok, got = pcall(cjson.encode, list[i][1])
      if not ok then return false, "encode lançou erro: " .. oneline(got) end
      if got ~= list[i][2] then return false, "encode deu " .. show(got) .. ", esperado " .. show(list[i][2]) end
    end
    return true, #list .. " casos"
  end)

  sync("cjson/decode", "decode", function()
    local cjson = env.require("cjson")
    local ok, t = pcall(cjson.decode, '{"a":{"b":[true,false,null]},"n":1e3,"s":"\\u00e9"}')
    if not ok then return false, "decode lançou erro: " .. oneline(t) end
    if t.a.b[1] ~= true or t.a.b[2] ~= false then return false, "booleanos mal lidos" end
    if t.a.b[3] ~= cjson.null then return false, "null não virou cjson.null" end
    if t.n ~= 1000 then return false, "1e3 virou " .. show(t.n) end
    if t.s ~= "\195\169" then return false, "\\u00e9 virou " .. show(t.s) end
    return true
  end)

  sync("cjson/erros", "erros e cjson.safe", function()
    local cjson = env.require("cjson")
    if pcall(cjson.decode, "{") then return false, "decode('{') deveria lançar erro" end
    local safe = env.require("cjson.safe")
    local value, err = safe.decode("{")
    if value ~= nil or type(err) ~= "string" then
      return false, "safe.decode devolveu " .. show(value) .. ", " .. show(err)
    end
    local value2, err2 = safe.encode(function() end)
    if value2 ~= nil or type(err2) ~= "string" then
      return false, "safe.encode devolveu " .. show(value2) .. ", " .. show(err2)
    end
    return true
  end)

  sync("cjson/volta", "encode e decode de ida e volta", function()
    local cjson = env.require("cjson")
    local source = {name = "x", list = {1, 2, 3}, nested = {a = true}, f = 0.25}
    local ok, text = pcall(cjson.encode, source)
    if not ok then return false, "encode lançou erro: " .. oneline(text) end
    local ok2, back = pcall(cjson.decode, text)
    if not ok2 then return false, "decode lançou erro: " .. oneline(back) end
    if not deep_equal(source, back) then return false, "o decode não devolveu a tabela original: " .. text end
    return true
  end)

  -- ----- arquivos, pastas e o shell POSIX do dust

  local function root()
    local dir = env._path.data .. "mobile_selftest/"
    env.util.make_dir(dir)
    return dir
  end

  local function put(path, text)
    local f = env.io.open(path, "w")
    if not f then return false end
    f:write(text)
    f:close()
    return true
  end

  local function get(path)
    local f = env.io.open(path, "r")
    if not f then return nil end
    local text = f:read("*a")
    f:close()
    return text
  end

  local function wipe(path)
    pcall(env.os.execute, "rm -rf " .. path)
  end

  sync("shell/mkdir", "mkdir -p", function()
    local base = root() .. "sh_mkdir"
    wipe(base)
    local ok = env.os.execute("mkdir -p " .. base .. "/a/b")
    local made = env.util.file_exists(base .. "/a/b")
    wipe(base)
    if ok ~= true then return false, "os.execute devolveu " .. show(ok) end
    return expect(made, true)
  end)

  sync("shell/echo", "echo > arquivo", function()
    local base = root() .. "sh_echo"
    wipe(base)
    env.util.make_dir(base)
    local ok = env.os.execute("echo hello > " .. base .. "/h.txt")
    local text = get(base .. "/h.txt")
    wipe(base)
    if ok ~= true then return false, "os.execute devolveu " .. show(ok) end
    return expect(text, "hello\n")
  end)

  sync("shell/popen", "io.popen('cat arquivo')", function()
    local base = root() .. "sh_popen"
    wipe(base)
    env.util.make_dir(base)
    put(base .. "/c.txt", "um\ndois\n")
    local f = env.io.popen("cat " .. base .. "/c.txt")
    local text = f and f:read("*a") or nil
    if f then f:close() end
    wipe(base)
    if not f then return false, "io.popen devolveu nil" end
    return expect(text, "um\ndois\n")
  end)

  sync("shell/capture", "util.os_capture('ls pasta')", function()
    local base = root() .. "sh_capture"
    wipe(base)
    env.util.make_dir(base)
    put(base .. "/b.txt", "b")
    put(base .. "/a.txt", "a")
    local text = env.util.os_capture("ls " .. base)
    wipe(base)
    return expect(text, "a.txt b.txt")
  end)

  sync("shell/cp-mv-rm", "cp, mv e rm", function()
    local base = root() .. "sh_cp"
    wipe(base)
    env.util.make_dir(base)
    put(base .. "/f.txt", "dados")
    local ok1 = env.os.execute("cp " .. base .. "/f.txt " .. base .. "/g.txt")
    local ok2 = env.os.execute("mv " .. base .. "/g.txt " .. base .. "/h.txt")
    local ok3 = env.os.execute("rm " .. base .. "/f.txt")
    local h = get(base .. "/h.txt")
    local f = get(base .. "/f.txt")
    local g = get(base .. "/g.txt")
    wipe(base)
    if ok1 ~= true or ok2 ~= true or ok3 ~= true then
      return false, "cp=" .. tostring(ok1) .. " mv=" .. tostring(ok2) .. " rm=" .. tostring(ok3)
    end
    if h ~= "dados" then return false, "h.txt = " .. show(h) end
    if f ~= nil or g ~= nil then return false, "f.txt ou g.txt ainda existem" end
    return true
  end)

  sync("shell/rm-rf", "rm -rf", function()
    local base = root() .. "sh_rm"
    wipe(base)
    env.os.execute("mkdir -p " .. base .. "/x/y")
    put(base .. "/x/y/z.txt", "z")
    local ok = env.os.execute("rm -rf " .. base)
    local still = env.util.file_exists(base)
    if ok ~= true then return false, "os.execute devolveu " .. show(ok) end
    return expect(still, false)
  end)

  sync("shell/desconhecido", "comando fora do subconjunto", function()
    local ok = env.os.execute("curl http://example.com")
    if ok == true then return false, "curl não deveria rodar" end
    local f = env.io.popen("curl http://example.com")
    if f then
      f:close()
      return false, "io.popen devolveu um handle para um comando desconhecido"
    end
    return true
  end)

  sync("shell/sandbox", "cat fora da pasta dust", function()
    local f = env.io.popen("cat /proc/version")
    local text = ""
    if f then
      text = f:read("*a") or ""
      f:close()
    end
    if text ~= "" then return false, "leu fora do dust: " .. show(text) end
    return true
  end)

  sync("paths/_path", "_path.dust/code/data/audio", function()
    local p = env._path
    if type(p) ~= "table" then return false, "_path ausente" end
    local names = {"dust", "code", "data", "audio"}
    for i = 1, #names do
      local value = p[names[i]]
      if type(value) ~= "string" or string.sub(value, -1) ~= "/" then
        return false, "_path." .. names[i] .. " = " .. show(value)
      end
    end
    if p.code ~= p.dust .. "code/" or p.data ~= p.dust .. "data/" or p.audio ~= p.dust .. "audio/" then
      return false, "code, data e audio devem ficar dentro de _path.dust"
    end
    return true, p.dust
  end)

  sync("paths/home-we", "/home/we/dust vira a pasta dust", function()
    local native = root() .. "probe.txt"
    local literal = "/home/we/dust/data/mobile_selftest/probe.txt"
    pcall(env.os.remove, native)
    if not put(literal, "probe") then return false, "não gravou " .. literal end
    local text = get(native)
    pcall(env.os.remove, native)
    return expect(text, "probe")
  end)

  sync("paths/lines", "io.lines e modo append", function()
    local path = root() .. "lines.txt"
    put(path, "a\nb\n")
    local f = env.io.open(path, "a")
    if not f then return false, "io.open(a) devolveu nil" end
    f:write("c\n")
    f:close()
    local joined, count = "", 0
    for line in env.io.lines(path) do
      joined = joined .. line
      count = count + 1
    end
    pcall(env.os.remove, path)
    if count ~= 3 then return false, count .. " linhas, esperado 3" end
    return expect(joined, "abc")
  end)

  sync("paths/rename", "os.rename e os.remove", function()
    local a, b = root() .. "ren_a.txt", root() .. "ren_b.txt"
    pcall(env.os.remove, a)
    pcall(env.os.remove, b)
    put(a, "r")
    local ok1 = env.os.rename(a, b)
    local moved = get(b)
    local ok2 = env.os.remove(b)
    local gone = get(b)
    if ok1 ~= true then return false, "os.rename devolveu " .. show(ok1) end
    if moved ~= "r" then return false, "depois do rename o conteúdo é " .. show(moved) end
    if ok2 ~= true then return false, "os.remove devolveu " .. show(ok2) end
    return expect(gone, nil)
  end)

  sync("paths/ausente", "abrir arquivo inexistente", function()
    local f, err = env.io.open(root() .. "nao_existe.txt", "r")
    if f then
      f:close()
      return false, "io.open devolveu um handle"
    end
    if type(err) ~= "string" then return false, "sem mensagem de erro: " .. show(err) end
    return true
  end)

  sync("paths/scandir", "util.scandir", function()
    local base = root() .. "scan"
    wipe(base)
    env.util.make_dir(base)
    put(base .. "/um.txt", "1")
    local names = env.util.scandir(base)
    wipe(base)
    if type(names) ~= "table" then return false, "scandir devolveu " .. show(names) end
    if not contains(names, "um.txt") then return false, "um.txt não está na lista" end
    return true
  end)

  -- ----- engine

  sync("engine/api", "engine.name e comandos", function()
    local engine = env.engine
    if type(engine) ~= "table" then return false, "engine ausente" end
    if type(engine.name) ~= "string" or engine.name == "" then
      return false, "engine.name = " .. show(engine.name)
    end
    if type(engine.list_commands) ~= "function" then return false, "engine.list_commands ausente" end
    local ok, list = pcall(engine.list_commands)
    if not ok or type(list) ~= "table" then return false, "list_commands falhou: " .. oneline(list) end
    return true, engine.name .. ": " .. #list .. " comandos"
  end)

  sync("engine/polyperc", "comandos do PolyPerc", function()
    local engine = env.engine
    if type(engine) ~= "table" or engine.name ~= "PolyPerc" then return "skip", "a engine não é PolyPerc" end
    local list = engine.list_commands()
    local wanted = {"hz", "amp", "pw", "release", "cutoff", "gain", "pan"}
    for i = 1, #wanted do
      if not contains(list, wanted[i]) then return false, "falta o comando " .. wanted[i] end
      if type(engine[wanted[i]]) ~= "function" then return false, "engine." .. wanted[i] .. " não é função" end
    end
    local ok, err = pcall(engine.amp, 0.5)
    if not ok then return false, "engine.amp(0.5) lançou erro: " .. oneline(err) end
    return true
  end)

  -- ----- params

  local function ensure(id, add)
    if env.params.lookup and env.params.lookup[id] then return end
    add()
  end

  sync("params/control", "add_control, get, set, string, action", function()
    local params = env.params
    ensure("selftest_ctl", function()
      params:add_control("selftest_ctl", "teste", env.controlspec.new(0, 10, "lin", 0.01, 5, "u"))
    end)
    params:set_action("selftest_ctl", function() end)
    params:set("selftest_ctl", 5)
    local first = params:get("selftest_ctl")
    if first ~= 5 then return false, "depois de set(5) o get deu " .. show(first) end
    local fired
    params:set_action("selftest_ctl", function(x) fired = x end)
    params:set("selftest_ctl", 7.25)
    local value = params:get("selftest_ctl")
    if type(value) ~= "number" or value < 7.249 or value > 7.251 then
      return false, "depois de set(7.25) o get deu " .. show(value)
    end
    if type(fired) ~= "number" or fired < 7.249 or fired > 7.251 then
      return false, "a action recebeu " .. show(fired)
    end
    local text = params:string("selftest_ctl")
    if type(text) ~= "string" or not string.find(text, "7.25", 1, true) then
      return false, "params:string deu " .. show(text)
    end
    params:set_action("selftest_ctl", function() end)
    return true, text
  end)

  sync("params/option", "add_option", function()
    local params = env.params
    ensure("selftest_opt", function()
      params:add_option("selftest_opt", "opção", {"a", "b", "c"}, 2)
    end)
    params:set("selftest_opt", 2)
    local first = params:string("selftest_opt")
    if first ~= "b" then return false, "string da opção 2 deu " .. show(first) end
    params:set("selftest_opt", 3)
    return expect(params:string("selftest_opt"), "c")
  end)

  sync("params/number", "add_number e delta", function()
    local params = env.params
    ensure("selftest_num", function()
      params:add_number("selftest_num", "número", 1, 10, 3)
    end)
    params:set("selftest_num", 3)
    params:delta("selftest_num", 2)
    return expect(params:get("selftest_num"), 5)
  end)

  sync("params/tempo", "clock_tempo", function()
    local tempo = env.params:get("clock_tempo")
    if type(tempo) ~= "number" or tempo < 1 or tempo > 300 then
      return false, "clock_tempo = " .. show(tempo)
    end
    return true, tempo .. " bpm"
  end)

  -- ----- linguagem, bibliotecas e tela

  sync("lua/date", "os.date e os.time", function()
    local year = env.os.date("%Y")
    if type(year) ~= "string" or #year ~= 4 then return false, "os.date('%Y') deu " .. show(year) end
    local now = env.os.time()
    if type(now) ~= "number" or now < 1600000000 then return false, "os.time() deu " .. show(now) end
    if type(env.os.clock()) ~= "number" then return false, "os.clock() não devolveu número" end
    return true, year
  end)

  sync("lua/yield-pcall", "yield dentro de pcall", function()
    -- clock.sleep dentro de pcall é comum nos scripts; o Lua 5.2 permite e o LuaJ precisa permitir.
    local gen = coroutine.wrap(function()
      local ok = pcall(function() coroutine.yield(1) end)
      coroutine.yield(ok and 2 or 0)
    end)
    local first, second = gen(), gen()
    if first ~= 1 or second ~= 2 then return false, "obtido " .. show(first) .. ", " .. show(second) end
    return true
  end)

  sync("lua/erro-tabela", "error com tabela em pcall", function()
    local ok, err = pcall(error, {code = 7})
    if ok or type(err) ~= "table" or err.code ~= 7 then return false, "a tabela de erro se perdeu" end
    return true
  end)

  sync("lua/libs", "bibliotecas do norns", function()
    local names = {
      "util", "tabutil", "controlspec", "paramset", "formatters", "musicutil", "lattice", "lfo",
      "sequins", "ui", "beatclock", "pattern_time", "voice", "filters",
    }
    local failed = {}
    for i = 1, #names do
      local ok, err = pcall(env.require, names[i])
      if not ok then failed[#failed + 1] = names[i] .. ": " .. oneline(err) end
    end
    if #failed > 0 then return false, table.concat(failed, "; ") end
    return true, #names .. " bibliotecas"
  end)

  sync("screen/extents", "screen.text_extents", function()
    if type(env.screen) ~= "table" or type(env.screen.text_extents) ~= "function" then
      return "skip", "screen.text_extents ausente"
    end
    local width = env.screen.text_extents("selftest")
    if type(width) ~= "number" or width <= 0 then return false, "text_extents deu " .. show(width) end
    return true, tostring(width) .. " px"
  end)

  -- ----- tempo (assíncronas)

  async("tempo/sleep", "clock.sleep(0.05)", function(finish)
    local t0 = env.util.time()
    env.clock.run(function()
      env.clock.sleep(0.05)
      local dt = env.util.time() - t0
      if dt >= 0.04 and dt < 1.5 then
        finish(true, "pediu 0.05 s, mediu " .. env.util.round(dt, 0.001) .. " s")
      else
        finish(false, "esperava cerca de 0.05 s, mediu " .. tostring(dt) .. " s")
      end
    end)
  end, {watchdog = "metro"})

  async("tempo/sync", "clock.sync(1/4)", function(finish)
    if env.clock.get_source and env.clock.get_source() ~= "internal" then
      finish("skip", "a fonte de clock é " .. tostring(env.clock.get_source()))
      return
    end
    local b0 = env.clock.get_beats()
    env.clock.run(function()
      env.clock.sync(1 / 4)
      local advanced = env.clock.get_beats() - b0
      if advanced > 0 and advanced < 0.5 then
        finish(true, "avançou " .. env.util.round(advanced, 0.001) .. " batida")
      else
        finish(false, "avançou " .. tostring(advanced) .. " batidas, esperava até 0.25")
      end
    end)
  end, {watchdog = "metro"})

  async("tempo/cancel", "clock.cancel", function(finish)
    local fired = false
    local id = env.clock.run(function()
      env.clock.sleep(0.1)
      fired = true
    end)
    env.clock.cancel(id)
    env.clock.run(function()
      env.clock.sleep(0.25)
      if fired then
        finish(false, "o clock.cancel não impediu a execução")
      else
        finish(true)
      end
    end)
  end, {watchdog = "metro"})

  async("tempo/metro", "metro 3 x 0.05 s", function(finish)
    local stages = {}
    local t0 = env.util.time()
    local m
    m = env.metro.init(function(stage)
      stages[#stages + 1] = stage
      if #stages == 3 then
        local dt = env.util.time() - t0
        if m and m.free then m:free() end
        if stages[1] == 1 and stages[2] == 2 and stages[3] == 3 and dt >= 0.1 then
          finish(true, "3 disparos em " .. env.util.round(dt, 0.001) .. " s")
        else
          finish(false, "estágios " .. table.concat(stages, ",") .. " em " .. tostring(dt) .. " s")
        end
      end
    end, 0.05, 3)
    if not m then
      finish(false, "metro.init devolveu nil (limite de 30 metros?)")
      return
    end
    m:start()
  end, {watchdog = "clock"})

  -- A espera usa um segundo metro (e não o clock): com o clock parado a checagem ainda termina, e o cão
  -- de guarda é o clock porque o metro é o que está sob teste.
  async("tempo/metro-stop", "metro:stop", function(finish)
    local count = 0
    local m, waiter
    m = env.metro.init(function()
      count = count + 1
      if m then m:stop() end
    end, 0.05, -1)
    if not m then
      finish(false, "metro.init devolveu nil (limite de 30 metros?)")
      return
    end
    waiter = env.metro.init(function()
      if waiter and waiter.free then waiter:free() end
      if m.free then m:free() end
      if count == 1 then
        finish(true)
      else
        finish(false, count .. " disparos depois do stop, esperava 1")
      end
    end, 0.3, 1)
    if not waiter then
      if m.free then m:free() end
      finish(false, "metro.init devolveu nil (limite de 30 metros?)")
      return
    end
    m:start()
    waiter:start()
  end, {watchdog = "clock"})

  return suite
end

-- ---------------------------------------------------------------- execução

-- Roda todas as checagens em ordem. As síncronas rodam na hora; cada assíncrona espera o finish ou o tempo
-- máximo. hooks: on_start(check), on_result(resultado), on_done(resultados). Devolve false se já havia uma
-- rodada em andamento.
function Suite:run(hooks)
  if self.running then return false end
  hooks = hooks or {}
  self.running = true
  local env = self.env
  local results = {}
  self.results = results
  local index = 0

  local function emit(check, outcome, detail)
    local status
    if outcome == true then
      status = "ok"
    elseif outcome == "skip" then
      status = "skip"
    elseif check.known then
      status = "limit"
    else
      status = "fail"
    end
    if outcome ~= true and outcome ~= false and outcome ~= "skip" then
      detail = "sem resultado (" .. tostring(outcome) .. ")"
    end
    local record = {id = check.id, group = check.group, name = check.name, status = status,
      detail = detail ~= nil and oneline(detail) or nil}
    results[#results + 1] = record
    if hooks.on_result then pcall(hooks.on_result, record) end
  end

  local step
  step = function()
    while true do
      index = index + 1
      local check = self.checks[index]
      if not check then
        self.running = false
        if hooks.on_done then pcall(hooks.on_done, results) end
        return
      end
      if hooks.on_start then pcall(hooks.on_start, check) end
      if check.async then
        local settled = false
        local cancel
        local function finish(outcome, detail)
          if settled then return end
          settled = true
          if cancel then pcall(cancel) end
          emit(check, outcome, detail)
          step()
        end
        local started, err = pcall(check.start, finish)
        if not started then
          finish(false, "erro: " .. oneline(err))
          return
        end
        if not settled then
          cancel = watchdog(env, check.watchdog, check.timeout, function()
            finish(false, "sem resposta em " .. tostring(check.timeout) .. " s")
          end)
          if settled and cancel then pcall(cancel) end
        end
        return
      end
      local ok, outcome, detail = pcall(check.run)
      if not ok then
        emit(check, false, "erro: " .. oneline(outcome))
      else
        emit(check, outcome, detail)
      end
    end
  end

  step()
  return true
end

-- ---------------------------------------------------------------- resultados

local RANK = {fail = 1, limit = 2, skip = 3, ok = 4}
local LABEL = {ok = "ok", fail = "FALHA", limit = "limite", skip = "pulado"}

M.label = LABEL

function M.summary(results)
  local counts = {ok = 0, fail = 0, limit = 0, skip = 0, total = 0}
  for i = 1, #results do
    local status = results[i].status
    counts[status] = (counts[status] or 0) + 1
    counts.total = counts.total + 1
  end
  return counts
end

-- Falhas primeiro, depois limites, pulados e ok; dentro de cada grupo a ordem de execução.
function M.sorted(results)
  local order = {}
  for i = 1, #results do order[i] = {results[i], i} end
  table.sort(order, function(a, b)
    local ra, rb = RANK[a[1].status] or 9, RANK[b[1].status] or 9
    if ra ~= rb then return ra < rb end
    return a[2] < b[2]
  end)
  local out = {}
  for i = 1, #order do out[i] = order[i][1] end
  return out
end

-- Linhas para o log: um resumo e uma linha por checagem (falhas primeiro).
function M.report(results)
  local counts = M.summary(results)
  local lines = {
    "selftest: ok=" .. counts.ok .. " falha=" .. counts.fail .. " limite=" .. counts.limit
      .. " pulado=" .. counts.skip,
  }
  local sorted = M.sorted(results)
  for i = 1, #sorted do
    local r = sorted[i]
    local line = "selftest " .. LABEL[r.status] .. " " .. r.id
    if r.detail and r.detail ~= "" then line = line .. ": " .. r.detail end
    lines[#lines + 1] = line
  end
  return lines
end

return M
