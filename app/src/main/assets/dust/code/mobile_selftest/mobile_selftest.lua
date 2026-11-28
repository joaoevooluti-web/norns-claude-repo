-- mobile_selftest
-- confere a compatibilidade do
-- Norns Mobile no aparelho: string.format,
-- cjson, shell POSIX, pastas /home/we,
-- engine, params, clock e metro.
-- O resultado vai para a tela e para o
-- log (linhas "selftest ...").
--
-- E2: rolar a lista
-- K2: tocar uma nota de teste
-- K3: repetir as checagens

engine.name = "PolyPerc"

local selftest = require "nornsmobile_selftest"

local TAG = {ok = "ok", fail = "FALHA", limit = "limite", skip = "pulou"}

local results = {}
local view = {}
local running = false
local top = 1

local function rebuild()
  view = selftest.sorted(results)
  top = util.clamp(top, 1, math.max(1, #view))
end

local function start()
  if running then return end
  running = true
  results = {}
  view = {}
  top = 1
  local suite = selftest.new(_G)
  print("selftest: iniciando " .. #suite.checks .. " checagens")
  local started = suite:run({
    on_start = function(check)
      if check.async then print("selftest: aguardando " .. check.id) end
    end,
    on_result = function(record)
      results[#results + 1] = record
      rebuild()
      redraw()
    end,
    on_done = function(all)
      running = false
      rebuild()
      local lines = selftest.report(all)
      for i = 1, #lines do print(lines[i]) end
      redraw()
    end,
  })
  if not started then running = false end
end

function init()
  start()
end

function key(n, z)
  if z ~= 1 then return end
  if n == 2 then
    local ok, err = pcall(engine.hz, 440)
    print("selftest: nota de teste " .. (ok and "enviada (engine.hz(440))" or ("falhou: " .. tostring(err))))
  elseif n == 3 then
    start()
  end
  redraw()
end

function enc(n, d)
  if n == 2 then
    top = util.clamp(top + d, 1, math.max(1, #view))
    redraw()
  end
end

local function line(y, level, text)
  screen.level(level)
  screen.move(2, y)
  screen.text(util.trim_string_to_width(text, 124))
end

function redraw()
  screen.clear()
  screen.aa(0)
  screen.font_size(8)
  local counts = selftest.summary(results)
  line(8, 15, "selftest  ok " .. counts.ok .. "  falha " .. counts.fail)
  line(17, 10, "limite " .. counts.limit .. "  pulou " .. counts.skip .. (running and "  rodando..." or ""))
  for row = 1, 3 do
    local r = view[top + row - 1]
    if r then
      line(17 + row * 9, row == 1 and 15 or 6, (row == 1 and "> " or "  ") .. TAG[r.status] .. " " .. r.id)
    end
  end
  local selected = view[top]
  if selected then line(53, 8, selected.detail or selected.name) end
  line(62, 3, "E2 rola  K2 nota  K3 repete")
  screen.update()
end
