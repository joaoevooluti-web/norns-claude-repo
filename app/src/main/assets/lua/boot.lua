-- boot.lua — Norns Mobile
-- Implementa um subconjunto da API de scripts do norns sobre o host Java (_host).
-- Mantenha este arquivo compatível com Lua 5.2 (LuaJ): sem '//', sem operadores bit a bit, sem goto.

local host = _host

-- Metadados de tela alinhados aos índices documentados pelo norns.
-- ALEPH usa uma aproximação Android sans-serif-condensed; as demais faces
-- usam as famílias Roboto/sans-serif disponíveis no sistema.
screen.W = 128
screen.H = 64
screen.font_face_count = 11
screen.font_face_names = {
  "norns", "ALEPH", "Roboto Thin", "Roboto Light", "Roboto Regular",
  "Roboto Medium", "Roboto Bold", "Roboto Black", "Roboto Thin Italic",
  "Roboto Light Italic", "Roboto Italic"
}
-- The Android Activity keeps the display awake while the app is foregrounded.
screen.ping = function() return true end
-- Norns low-battery icon is specific to the original hardware status bar.
screen.update_low_battery = function() return false end
screen.BLEND_MODES = {
  "over", "xor", "add", "multiply", "screen", "overlay", "darken", "lighten",
  "color_dodge", "color_burn", "hard_light", "soft_light", "difference", "exclusion"
}

-- text_trim/text_rotate/text_center_rotate are native screen operations
-- registered by LuaHost; do not shadow them with Lua fallbacks.

-- ===================================================================== log / print
function print(...)
  local n = select('#', ...)
  local t = {}
  for i = 1, n do t[i] = tostring((select(i, ...))) end
  host.log(table.concat(t, "\t"), false)
end

local function safe(f, ...)
  local ok, err = pcall(f, ...)
  if not ok then host.log(tostring(err), true) end
  return ok
end

-- ===================================================================== caminhos e estado
local DUST = host.dust
_path = {
  dust = DUST,
  home = DUST,
  code = DUST .. "code/",
  data = DUST .. "data/",
  audio = DUST .. "audio/",
  tape = DUST .. "audio/tape/",
}

local function noop() end

norns = {
  version = {major = 2, minor = 9, patch = 0, update = 250101, norns = "mobile-0.2.0"},
  platform = "android",
  state = {
    name = "", shortname = "", path = "", lib = "", data = "", out = 64,
    -- Monitor da entrada começa MUDO no Android: o microfone fica perto do alto-falante e
    -- o monitor em 0 dB (padrão do norns de hardware) causa realimentação acústica.
    mix = {
      output = 0, input = 0, monitor = -math.huge, engine = 0, cut = 0, tape = 0,
      monitor_mode = 1, headphone_gain = 40, aux = 2, ins = 1,
      rev_eng_input = -9, rev_cut_input = -9, rev_monitor_input = -math.huge,
      rev_tape_input = -math.huge, rev_return_level = 0, rev_pre_delay = 60,
      rev_lf_fc = 200, rev_low_time = 6, rev_mid_time = 6, rev_hf_damping = 6000,
      comp_mix = 1, comp_ratio = 4, comp_threshold = -18, comp_attack = 5,
      comp_release = 50, comp_pre_gain = 0, comp_post_gain = 9,
      cut_input_adc = 0, cut_input_eng = 0, cut_input_tape = -math.huge
    }
  },
  script = {},
  enc = {},
  encoders = {},
  shutdown = function()
    host.log("norns.shutdown não desliga o Android; pare o script pelo app.", false)
    return false
  end,
  reboot = function()
    host.log("norns.reboot não reinicia o Android; reinicie o app pelo sistema.", false)
    return false
  end,
  system_cmd = function(command)
    if type(command) ~= "string" or command == "" then return false end
    host.log("norns.system_cmd não executa comandos do sistema no Android; operação bloqueada.", true)
    return false
  end,
  try = function(f, msg)
    local ok, err = pcall(f)
    if not ok then print(msg or "erro", err) end
  end,
}

-- Menu callback ownership mirrors norns: scripts own global callbacks while
-- the menu is inactive; menu clients temporarily install a separate handler set.
norns.none = noop
norns.blank = noop
local menu_mode = false
local menu_callbacks = {enc = noop, key = noop, redraw = noop, refresh = noop}
norns.menu = {
  init = function()
    -- Upstream init re-applies the current mode; it does not force a toggle.
    return true
  end,
  status = function() return menu_mode end,
  set = function(new_enc, new_key, new_redraw, new_refresh)
    menu_callbacks.enc = type(new_enc) == "function" and new_enc or noop
    menu_callbacks.key = type(new_key) == "function" and new_key or noop
    menu_callbacks.redraw = type(new_redraw) == "function" and new_redraw or noop
    menu_callbacks.refresh = type(new_refresh) == "function" and new_refresh or noop
    return true
  end,
  get_enc = function() return menu_callbacks.enc end,
  get_key = function() return menu_callbacks.key end,
  get_redraw = function() return menu_callbacks.redraw end,
  get_refresh = function() return menu_callbacks.refresh end,
  toggle = function(status)
    if status == nil then menu_mode = not menu_mode else menu_mode = status == true end
    return menu_mode
  end,
  set_mode = function(status)
    if status == nil then return menu_mode end
    menu_mode = status == true
    return menu_mode
  end,
}

-- Encoder sensitivity/acceleration are applied to the mobile touch deltas
-- before dispatching the standard norns enc(n,d) callback.
local encoder_sensitivity = 1.0
local encoder_acceleration = 0.0
local encoder_last_time = {}
local function encoder_set_sens(value)
  encoder_sensitivity = math.max(0.1, math.min(10, tonumber(value) or 1))
  return encoder_sensitivity
end
local function encoder_set_accel(value)
  encoder_acceleration = math.max(0, math.min(10, tonumber(value) or 0))
  return encoder_acceleration
end
norns.enc.sens = encoder_set_sens
norns.enc.accel = encoder_set_accel
norns.encoders.set_sens = encoder_set_sens
norns.encoders.set_accel = encoder_set_accel
encoders = norns.encoders

-- ===================================================================== require / include
local loaded = package.loaded

local function strip_bom(src)
  if string.sub(src, 1, 3) == "\239\187\191" then return string.sub(src, 4) end
  return src
end

local function find_module(name)
  local rel = name
  if not string.find(rel, "/", 1, true) then rel = (string.gsub(rel, "%.", "/")) end
  -- Accept both norns spellings: require("formatters") and require("lib/formatters").
  local src
  if string.sub(rel, 1, 4) == "lib/" then
    src = host.read_asset("lua/" .. rel .. ".lua")
    if src then return src, "@" .. rel .. ".lua" end
  end
  src = host.read_asset("lua/lib/" .. rel .. ".lua")
  if src then return src, "@" .. rel .. ".lua" end
  local st = norns.state
  local cands = {}
  -- Primeiro procura junto ao script e na biblioteca local do próprio pacote.
  if st.path ~= "" then
    cands[#cands + 1] = st.path .. rel .. ".lua"
    cands[#cands + 1] = st.path .. "lib/" .. rel .. ".lua"
  end
  -- Bibliotecas compartilhadas instaladas em dust/code/lib e módulos na raiz de dust/code.
  cands[#cands + 1] = _path.code .. "lib/" .. rel .. ".lua"
  cands[#cands + 1] = _path.code .. rel .. ".lua"
  for i = 1, #cands do
    local s = host.read_file(cands[i])
    if s then return strip_bom(s), "@" .. cands[i] end
  end
  return nil
end

function require(name)
  local v = loaded[name]
  if v ~= nil then return v end
  local src, chunk = find_module(name)
  if not src then error("módulo '" .. tostring(name) .. "' não encontrado", 2) end
  local fn, err = load(src, chunk)
  if not fn then error(err, 2) end
  -- Cache provisionally to support circular requires, but roll back if the
  -- module throws so a later require can retry instead of returning true.
  loaded[name] = true
  local ok, ret = pcall(fn, name)
  if not ok then
    loaded[name] = nil
    error(ret, 2)
  end
  if ret ~= nil then
    loaded[name] = ret
  elseif loaded[name] == nil then
    loaded[name] = true
  end
  return loaded[name]
end

local included = {}
function include(file)
  local rel = (string.gsub(file, "%.lua$", ""))
  local st = norns.state
  local cands = {st.path .. rel .. ".lua", st.path .. "lib/" .. rel .. ".lua", _path.code .. rel .. ".lua"}
  for i = 1, #cands do
    local s = host.read_file(cands[i])
    if s then
      if included[cands[i]] ~= nil then return included[cands[i]] end
      local fn, err = load(strip_bom(s), "@" .. cands[i])
      if not fn then error(err, 2) end
      local ret = fn()
      included[cands[i]] = (ret == nil) and true or ret
      return included[cands[i]]
    end
  end
  error("include: arquivo '" .. tostring(file) .. "' não encontrado", 2)
end

function dofile(path)
  local s = host.read_file(path)
  if not s then error("dofile: não foi possível abrir '" .. tostring(path) .. "'", 2) end
  local fn, err = load(strip_bom(s), "@" .. path)
  if not fn then error(err, 2) end
  return fn()
end

function loadfile(path)
  local s = host.read_file(path)
  if not s then return nil, "não foi possível abrir '" .. tostring(path) .. "'" end
  return load(strip_bom(s), "@" .. path)
end

-- ===================================================================== io / os (sandbox)
local FileR = {}
FileR.__index = FileR

function FileR:read(fmt)
  if self.closed then return nil end
  fmt = fmt or "l"
  if type(fmt) == "string" then fmt = (string.gsub(fmt, "^%*", "")) end
  -- LuaJ follows Lua 5.2 and accepts "*a"; upstream norns libraries also use "*all".
  if fmt == "all" then fmt = "a" end
  local data = self.data
  if fmt == "a" then
    local r = string.sub(data, self.pos)
    self.pos = #data + 1
    return r
  elseif fmt == "l" or fmt == "L" then
    if self.pos > #data then return nil end
    local e = string.find(data, "\n", self.pos, true)
    local line
    if e then
      line = string.sub(data, self.pos, (fmt == "L") and e or (e - 1))
      self.pos = e + 1
    else
      line = string.sub(data, self.pos)
      self.pos = #data + 1
    end
    return line
  elseif fmt == "n" then
    -- string.find("^...", init) remains anchored to the start of the full
    -- string in Lua; parse a substring so consecutive numeric reads work.
    local remaining = string.sub(data, self.pos)
    local whitespace = string.match(remaining, "^(%s*)") or ""
    local tail = string.sub(remaining, #whitespace + 1)
    local token = string.match(tail, "^([+-]?%d+%.?%d*[eE][+-]?%d+)")
        or string.match(tail, "^([+-]?%d+%.?%d*)")
        or string.match(tail, "^([+-]?%.%d+)")
    if not token then return nil end
    local num = tonumber(token)
    if not num then return nil end
    self.pos = self.pos + #whitespace + #token
    return num
  elseif type(fmt) == "number" then
    if self.pos > #data and fmt > 0 then return nil end
    local r = string.sub(data, self.pos, self.pos + fmt - 1)
    self.pos = self.pos + #r
    return r
  end
  return nil
end

function FileR:lines(fmt)
  local exhausted = false
  return function()
    if exhausted or self.closed then return nil end
    local line = self:read(fmt or "l")
    if line == nil then
      self:close()
      exhausted = true
      return nil
    end
    return line
  end
end

function FileR:seek(whence, off)
  if self.closed then return nil, "arquivo fechado", 9 end
  whence = whence or "cur"
  off = off or 0
  if whence ~= "set" and whence ~= "cur" and whence ~= "end" then
    return nil, "origem de seek inválida", 22
  end
  off = tonumber(off)
  if not off or off ~= off or off == math.huge or off == -math.huge
      or off ~= math.floor(off) then
    return nil, "deslocamento de seek inválido", 22
  end
  local base = whence == "set" and 0
      or (whence == "end" and #self.data or (self.pos - 1))
  local target = base + off
  if target < 0 or target > 2147483647 then
    return nil, "posição de seek fora do limite", 22
  end
  self.pos = target + 1
  return target
end

function FileR:write() return nil, "arquivo aberto somente para leitura" end
function FileR:flush() return self end
function FileR:close() self.closed = true return true end

local FileW = {}
FileW.__index = FileW

function FileW:write(...)
  if self.closed then return nil, "arquivo fechado", 9 end
  self.data = self.data or ""
  self.pos = self.pos or (#self.data + 1)
  for i = 1, select('#', ...) do
    local chunk = tostring((select(i, ...)))
    if self.append then self.pos = #self.data + 1 end
    local prefix = string.sub(self.data, 1, self.pos - 1)
    local suffix_start = self.pos + #chunk
    local suffix = suffix_start <= #self.data and string.sub(self.data, suffix_start) or ""
    local gap = self.pos > #self.data + 1 and string.rep("\0", self.pos - #self.data - 1) or ""
    self.data = prefix .. gap .. chunk .. suffix
    self.pos = self.pos + #chunk
  end
  return self
end

function FileW:flush()
  if self.closed then return nil, "arquivo fechado", 9 end
  if not host.write_file(self.path, self.data, false) then
    return nil, tostring(self.path) .. ": não foi possível gravar", 13
  end
  return self
end

function FileW:close()
  if self.closed then return true end
  local ok, err, code = self:flush()
  if not ok then return nil, err, code end
  self.closed = true
  return true
end

function FileW:read() return nil, "arquivo aberto somente para escrita", 13 end
function FileW:lines() error("arquivo aberto somente para escrita", 2) end
function FileW:seek(whence, off)
  if self.closed then return nil, "arquivo fechado", 9 end
  whence = whence or "cur"
  off = off or 0
  if whence ~= "set" and whence ~= "cur" and whence ~= "end" then
    return nil, "origem de seek inválida", 22
  end
  off = tonumber(off)
  if not off or off ~= off or off == math.huge or off == -math.huge
      or off ~= math.floor(off) then
    return nil, "deslocamento de seek inválido", 22
  end
  local base = whence == "set" and 0
      or (whence == "end" and #self.data or (self.pos - 1))
  local target = base + off
  if target < 0 or target > 2147483647 then
    return nil, "posição de seek fora do limite", 22
  end
  self.pos = target + 1
  return target
end

local real_tostring = tostring

io = {}

function io.open(path, mode)
  mode = mode or "r"
  if type(path) ~= "string" or path == "" then
    return nil, "caminho de arquivo inválido", 22
  end
  if type(mode) ~= "string"
      or (mode ~= "r" and mode ~= "rb" and mode ~= "w" and mode ~= "wb"
          and mode ~= "a" and mode ~= "ab") then
    return nil, "modo de arquivo inválido ou não suportado", 22
  end
  local m = string.sub(mode, 1, 1)
  if m == "r" then
    local data = host.read_file(path)
    if not data then return nil, tostring(path) .. ": arquivo não encontrado", 2 end
    return setmetatable({data = data, pos = 1, closed = false}, FileR)
  end
  local initial = ""
  if m == "a" then
    initial = host.read_file(path) or ""
  elseif not host.write_file(path, "", false) then
    return nil, tostring(path) .. ": não foi possível abrir para escrita", 13
  end
  return setmetatable({path = path, data = initial, pos = #initial + 1,
    append = m == "a", closed = false}, FileW)
end

io.stdout = {
  write = function(self, ...)
    local n = select('#', ...)
    local t = {}
    for i = 1, n do t[i] = tostring((select(i, ...))) end
    host.log(table.concat(t), false)
    return self
  end,
  flush = function(self) return self end,
  close = function(self) return true end,
}
io.stderr = io.stdout
io.stdin = {
  read = function() return nil end,
  lines = function() return function() return nil end end,
  close = function() return true end,
}
local default_input = io.stdin
local default_output = io.stdout

function io.input(file)
  if file == nil then return default_input end
  if type(file) == "string" then
    local opened, err = io.open(file, "r")
    if not opened then error(err, 2) end
    default_input = opened
  elseif type(file) == "table" and type(file.read) == "function" then
    default_input = file
  else
    error("io.input espera um nome ou handle de arquivo", 2)
  end
  return default_input
end

function io.output(file)
  if file == nil then return default_output end
  if type(file) == "string" then
    local opened, err = io.open(file, "w")
    if not opened then error(err, 2) end
    default_output = opened
  elseif type(file) == "table" and type(file.write) == "function" then
    default_output = file
  else
    error("io.output espera um nome ou handle de arquivo", 2)
  end
  return default_output
end

function io.read(fmt)
  if not default_input or type(default_input.read) ~= "function" then return nil end
  return default_input:read(fmt or "l")
end

function io.write(...)
  if not default_output or type(default_output.write) ~= "function" then
    return nil, "saída padrão indisponível"
  end
  return default_output:write(...)
end

function io.flush()
  if default_output and type(default_output.flush) == "function" then
    return default_output:flush()
  end
  return true
end

function io.close(file)
  local target = file or default_output
  if target and type(target.close) == "function" then return target:close() end
  return nil, "handle de arquivo inválido"
end

function io.type(file)
  if type(file) ~= "table" or type(file.close) ~= "function" then return nil end
  return file.closed and "closed file" or "file"
end

function io.lines(path, fmt)
  local f, err
  local owns_file = path ~= nil
  if not owns_file then
    f = default_input
  else
    f, err = io.open(path, "r")
    if not f then error(err, 2) end
  end
  local closed = false
  return function()
    if closed or not f then return nil end
    local line = f:read(fmt or "l")
    if line == nil then
      if owns_file and type(f.close) == "function" then f:close() end
      closed = true
      f = nil
      return nil
    end
    return line
  end
end

function io.popen() return nil, "io.popen não é suportado" end

local real_os = os
os = {
  time = real_os.time,
  clock = real_os.clock,
  date = real_os.date,
  difftime = real_os.difftime,
  getenv = function() return nil end,
  execute = function() return nil, "os.execute não é suportado" end,
  exit = function() error("os.exit está desativado no Norns Mobile", 2) end,
  remove = function(p)
    if host.remove(p) then return true end
    return nil, tostring(p) .. ": não foi possível remover"
  end,
  rename = function(a, b)
    if host.rename(a, b) then return true end
    return nil, "não foi possível renomear"
  end,
  tmpname = function() return _path.data .. "tmp_" .. real_tostring(math.random(100000, 999999)) end,
}

luajava = nil

-- ===================================================================== módulos base
util = require 'util'
tab = require 'tabutil'
controlspec = require 'controlspec'
paramset = require 'paramset'
-- Norns scripts often access this formatter library as a global table.
formatters = require 'formatters'

-- Upstream Stratum probes Linux Extension paths. On Android, resolve only
-- the two required UGen basenames against the app-private plugin directory;
-- the host returns true only when scsynth is running and the .so is installed.
local util_file_exists = util.file_exists
function util.file_exists(path)
  if type(path) == "string" then
    local basename = string.match(path, "([^/]+)$")
    if basename == "MiRings.so" or basename == "MiClouds.so" then
      if type(engine) == "table" and engine.name == "Stratum"
          and engine._native_state == "unavailable" then
        return false
      end
      if type(host.sc_plugin_exists) == "function" then
        return host.sc_plugin_exists(basename)
      end
      return false
    end
  end
  return util_file_exists(path)
end

-- ===================================================================== agendador (metro e clock)
local timers = {}
local in_tick = false

local function rearm()
  if timers[1] then host.wake_at(timers[1].t) else host.wake_at(-1) end
end

local function sched_add(t, fn)
  local entry = {t = t, fn = fn}
  local i = #timers
  while i >= 1 and timers[i].t > t do
    timers[i + 1] = timers[i]
    i = i - 1
  end
  timers[i + 1] = entry
  if i == 0 and not in_tick then rearm() end
  return entry
end

local function sched_remove(entry)
  if not entry then return false end
  for i = #timers, 1, -1 do
    if timers[i] == entry then
      table.remove(timers, i)
      if not in_tick then rearm() end
      return true
    end
  end
  return false
end

local function finite_number(value, fallback)
  value = tonumber(value)
  if not value or value ~= value or value == math.huge or value == -math.huge then
    return fallback
  end
  return value
end

-- ---- metro
local Metro = {}
Metro.__index = Metro

metro = {num_metros = 36, num_script_metros = 30}
local metro_list = {}
setmetatable(metro, {
  __index = function(_, key)
    if type(key) == "number" then return metro_list[key] end
  end
})

-- Construtor público compatível com metro.new(id). Instâncias criadas assim
-- podem ser iniciadas diretamente; metro.init reserva os 30 slots de script.
function metro.new(id)
  id = tonumber(id)
  if not id or id ~= math.floor(id) or id < 1 or id > metro.num_metros then
    return nil
  end
  return setmetatable({
    id = id, time = 1, count = -1, init_stage = 1,
    stage = 1, is_running = false, gen = 0, event = nil
  }, Metro)
end

local function metro_arm(m)
  m.gen = (m.gen or 0) + 1
  local gen = m.gen
  m.next_t = host.now() + m.time
  local entry
  local function fire()
    if not m.is_running or m.gen ~= gen then return end
    if m.scheduled_event == entry then m.scheduled_event = nil end
    local stage = m.stage
    local cnt = m.count
    if cnt ~= nil and cnt >= 0 and stage >= math.max(1, cnt) then
      m.is_running = false
    else
      m.next_t = m.next_t + m.time
      local nowt = host.now()
      if m.next_t < nowt - 0.25 then m.next_t = nowt + m.time end
      entry = sched_add(m.next_t, fire)
      m.scheduled_event = entry
    end
    m.stage = stage + 1
    if m.event then safe(m.event, stage) end
  end
  entry = sched_add(m.next_t, fire)
  m.scheduled_event = entry
end

function Metro:start(time, count, stage)
  if self.freed then return false end
  if type(time) == "table" then
    local a = time
    time, count, stage = a.time, a.count, a.stage
  end
  local nextTime, nextCount = self.time, self.count
  if time ~= nil then
    nextTime = finite_number(time, nil)
    if not nextTime then return false end
    nextTime = math.max(0.001, math.min(86400, nextTime))
  end
  if count ~= nil then
    nextCount = finite_number(count, nil)
    if not nextCount then return false end
    nextCount = math.max(-1, math.min(1000000, math.floor(nextCount)))
  end
  local initial = finite_number(stage or self.init_stage or 1, nil)
  if not initial then return false end
  self.time, self.count = nextTime, nextCount
  self.stage = math.max(1, math.min(1000000, math.floor(initial)))
  sched_remove(self.scheduled_event)
  self.scheduled_event = nil
  self.is_running = true
  metro_arm(self)
  return true
end

function Metro:stop()
  self.is_running = false
  self.gen = (self.gen or 0) + 1
  sched_remove(self.scheduled_event)
  self.scheduled_event = nil
end

function Metro:free()
  self:stop()
  if self.id and metro_list[self.id] == self then
    metro_list[self.id] = nil
  end
  self.event = nil
  self.freed = true
end

function metro.init(arg, time, count)
  local m = setmetatable({time = 1, count = -1, init_stage = 1, stage = 1, is_running = false, gen = 0}, Metro)
  if type(arg) == "table" then
    m.event = arg.event
    if arg.time ~= nil then m.time = arg.time end
    if arg.count ~= nil then m.count = arg.count end
    if arg.init_stage ~= nil then m.init_stage = arg.init_stage end
  else
    m.event = arg
    if time ~= nil then m.time = time end
    if count ~= nil then m.count = count end
  end
  -- O Norns reserva 30 slots para metros criados por scripts.
  local id = 1
  while id <= metro.num_script_metros and metro_list[id] ~= nil do id = id + 1 end
  if id > metro.num_script_metros then
    host.log("metro.init: limite de 30 temporizadores de script atingido", false)
    return nil
  end
  m.id = id
  metro_list[id] = m
  return m
end

function metro.free(metro_or_id)
  local m = metro_or_id
  if type(metro_or_id) == "number" then m = metro_list[metro_or_id] end
  if type(m) == "table" and getmetatable(m) == Metro then
    m:free()
  end
end

function metro.free_all()
  local pending = {}
  for _, m in pairs(metro_list) do pending[#pending + 1] = m end
  for i = 1, #pending do pending[i]:free() end
  metro_list = {}
end

-- ---- clock
clock = {}
local clocks = {}
local next_clock_id = 1
local tempo = 120
local ref_beat = 0
local ref_time = host.now()
local clock_source = "internal"
local midi_beat = 0
local midi_last_pulse = nil
local midi_pulse_intervals = {}
local midi_sync_waiters = {}
local resume_clock

function clock.get_tempo() return tempo end
function clock.get_beat_sec() return 60 / tempo end
function clock.get_beats()
  if clock_source == "midi" then
    if not midi_last_pulse then return midi_beat end
    local elapsed = math.max(0, host.now() - midi_last_pulse)
    return midi_beat + math.min(elapsed * tempo / 60, 1 / 24)
  end
  return ref_beat + (host.now() - ref_time) * tempo / 60
end

local function set_tempo(bpm)
  bpm = util.clamp(finite_number(bpm, tempo), 1, 300)
  ref_beat = clock.get_beats()
  ref_time = host.now()
  tempo = bpm
  if clock.tempo_change_handler then safe(clock.tempo_change_handler, bpm) end
end

local transport_running = false
clock.transport = {
  start = noop,
  stop = noop,
  is_running = function() return transport_running end,
}
clock.internal = {
  set_tempo = set_tempo,
  start = function()
    clock_source = "internal"
    transport_running = true
    ref_beat, ref_time = 0, host.now()
    if type(clock.transport.start) == "function" then safe(clock.transport.start) end
  end,
  stop = function()
    transport_running = false
    if type(clock.transport.stop) == "function" then safe(clock.transport.stop) end
  end,
}
clock.set_tempo = set_tempo
clock.tempo_change_handler = nil

function clock.set_source(source)
  if source ~= "internal" and source ~= "midi" then
    host.log("clock.set_source: fonte '" .. tostring(source) .. "' ainda não suportada; use internal ou midi", false)
    return false
  end
  local current_beat = clock.get_beats()
  clock_source = source
  if source == "internal" then
    ref_beat, ref_time = current_beat, host.now()
    for i = #midi_sync_waiters, 1, -1 do
      local waiter = table.remove(midi_sync_waiters, i)
      local dt = math.max(0, (waiter.target - current_beat) * 60 / tempo)
      sched_add(host.now() + dt, function()
        if clocks[waiter.id] == waiter.co then resume_clock(waiter.id, waiter.target) end
      end)
    end
  else
    midi_beat, midi_last_pulse = current_beat, nil
    midi_pulse_intervals = {}
  end
  if midi and type(midi.update_clock_receive) == "function" then
    midi.update_clock_receive()
  end
  return true
end

function clock.get_source() return clock_source end

local function sync_time(beat, offset)
  beat = finite_number(beat, 1)
  offset = finite_number(offset, 0)
  if beat <= 0 then return host.now(), clock.get_beats() end
  beat = math.min(1000000, beat)
  offset = math.max(-1000000, math.min(1000000, offset))
  local b = clock.get_beats()
  local target = math.ceil((b + 1e-6 - offset) / beat) * beat + offset
  local dt = (target - b) * 60 / tempo
  if dt < 0 then dt = 0 end
  return host.now() + dt, target
end

resume_clock = function(id, ...)
  local co = clocks[id]
  if not co then return end
  local ok, kind, a, b = coroutine.resume(co, ...)
  if not ok then
    clocks[id] = nil
    host.log(tostring(kind), true)
    return
  end
  if coroutine.status(co) == "dead" then
    clocks[id] = nil
    return
  end
  local wake
  if kind == "sleep" then
    local s = finite_number(a, 0)
    s = math.max(0, math.min(86400, s))
    wake = host.now() + s
  elseif kind == "sync" then
    if clock_source == "midi" then
      local beat = finite_number(a, 1)
      local offset = finite_number(b, 0)
      beat = math.min(1000000, beat)
      offset = math.max(-1000000, math.min(1000000, offset))
      local current = clock.get_beats()
      if beat <= 0 then
        sched_add(host.now(), function()
          if clocks[id] == co then resume_clock(id, current) end
        end)
        return
      end
      local target = math.ceil((current + 1e-6 - offset) / beat) * beat + offset
      if target <= current + 1e-6 then target = target + beat end
      midi_sync_waiters[#midi_sync_waiters + 1] = {id = id, co = co, target = target}
      return
    end
    local target
    wake, target = sync_time(a, b)
    sched_add(wake, function()
      if clocks[id] == co then resume_clock(id, target) end
    end)
    return
  else
    wake = host.now()
  end
  sched_add(wake, function()
    if clocks[id] == co then resume_clock(id) end
  end)
end

function clock.run(f, ...)
  assert(type(f) == "function", "clock.run espera uma função")
  local id = next_clock_id
  next_clock_id = id + 1
  clocks[id] = coroutine.create(f)
  resume_clock(id, ...)
  return id
end

function clock.cancel(id)
  if id == nil then return end
  clocks[id] = nil
  for i = #midi_sync_waiters, 1, -1 do
    if midi_sync_waiters[i].id == id then table.remove(midi_sync_waiters, i) end
  end
end

function clock.cancel_all()
  for id in pairs(clocks) do clocks[id] = nil end
  midi_sync_waiters = {}
end

function clock.get_time()
  return host.now()
end

-- Recebe os bytes de transporte/clock antes do callback MIDI do script.
-- MIDI Clock envia 24 pulsos por semínima; sync externo só acorda no pulso real.
function clock._midi_event(status)
  if clock_source ~= "midi" then return end
  if status == 0xfa then
    midi_beat, midi_last_pulse = 0, nil
    midi_pulse_intervals = {}
    transport_running = true
    if type(clock.transport.start) == "function" then safe(clock.transport.start) end
  elseif status == 0xfb then
    transport_running = true
    if type(clock.transport.start) == "function" then safe(clock.transport.start) end
  elseif status == 0xfc then
    transport_running = false
    if type(clock.transport.stop) == "function" then safe(clock.transport.stop) end
  elseif status == 0xf8 then
    local now = host.now()
    if midi_last_pulse then
      local interval = now - midi_last_pulse
      if interval > 0.001 and interval < 1 then
        midi_pulse_intervals[#midi_pulse_intervals + 1] = interval
        if #midi_pulse_intervals > 24 then table.remove(midi_pulse_intervals, 1) end
        local total = 0
        for i = 1, #midi_pulse_intervals do total = total + midi_pulse_intervals[i] end
        local measured = 60 / ((total / #midi_pulse_intervals) * 24)
        local updated = tempo * 0.75 + util.clamp(measured, 1, 300) * 0.25
        if math.abs(updated - tempo) > 0.01 then
          tempo = updated
          if clock.tempo_change_handler then safe(clock.tempo_change_handler, tempo) end
        end
      end
    end
    midi_beat = midi_beat + 1 / 24
    midi_last_pulse = now
    local current = midi_beat
    for i = #midi_sync_waiters, 1, -1 do
      local waiter = midi_sync_waiters[i]
      if current + 1e-6 >= waiter.target then
        table.remove(midi_sync_waiters, i)
        if clocks[waiter.id] == waiter.co then resume_clock(waiter.id, waiter.target) end
      end
    end
  end
end

function clock.sleep(s)
  return coroutine.yield("sleep", s)
end

function clock.sync(beat, offset)
  return coroutine.yield("sync", beat or 1, offset or 0)
end

function clock.cleanup()
  for id, _ in pairs(clocks) do clocks[id] = nil end
  midi_sync_waiters = {}
end

-- ===================================================================== SuperCollider nativo (extensão Android)
-- OSC básico para scripts que carregam SynthDefs próprios em _path.dust.
local sc_reply_backlog = {}
local sc_api
sc_api = {
  is_running = function() return host.sc_available() end,
  send = function(path, ...)
    return host.sc_send(tostring(path), {...})
  end,
  int = function(value)
    return {__norns_sc_int = math.floor(tonumber(value) or 0)}
  end,
  float_array = function(values)
    if type(values) ~= "table" or #values > 12000 then return false end
    local copy = {}
    for i = 1, #values do
      local value = tonumber(values[i])
      if not value or value ~= value or value == math.huge or value == -math.huge then return false end
      copy[i] = value
    end
    return {__norns_sc_float_array = true, values = copy}
  end,
  buffer_alloc = function(buffer, frames, channels)
    buffer, frames, channels = tonumber(buffer), tonumber(frames), tonumber(channels or 1)
    if not buffer or not frames or not channels or buffer < 0 or frames < 1 or channels < 1
        or buffer ~= math.floor(buffer) or frames ~= math.floor(frames) or channels ~= math.floor(channels)
        or buffer > 2147483647 or frames > 2147483647 or channels > 64 then return false end
    return host.sc_send("/b_alloc", {sc_api.int(buffer), sc_api.int(frames), sc_api.int(channels)})
  end,
  buffer_free = function(buffer)
    buffer = tonumber(buffer)
    if not buffer or buffer < 0 or buffer ~= math.floor(buffer) or buffer > 2147483647 then return false end
    return host.sc_send("/b_free", {sc_api.int(buffer)})
  end,
  buffer_zero = function(buffer)
    buffer = tonumber(buffer)
    if not buffer or buffer < 0 or buffer ~= math.floor(buffer) or buffer > 2147483647 then return false end
    return host.sc_send("/b_zero", {sc_api.int(buffer)})
  end,
  softcut_readback = function(channel, start, count)
    channel, start, count = tonumber(channel), tonumber(start), tonumber(count)
    if not channel or not start or not count or channel < 1 or channel > 2
        or start < 0 or count < 1 or channel ~= math.floor(channel)
        or start ~= math.floor(start) or count ~= math.floor(count)
        or start > 16777216 or count > 16777216 - start then return false end
    return host.sc_softcut_readback(channel, start, count)
  end,
  buffer_setn = function(buffer, start, values)
    buffer, start = tonumber(buffer), tonumber(start)
    if not buffer or not start or buffer < 0 or start < 0 or buffer ~= math.floor(buffer)
        or start ~= math.floor(start) or buffer > 2147483647 or start > 2147483647
        or type(values) ~= "table" then return false end
    local offset, total = 1, #values
    while offset <= total do
      local chunk = {}
      local last = math.min(total, offset + 11999)
      for i = offset, last do
        local value = tonumber(values[i])
        if not value or value ~= value or value == math.huge or value == -math.huge then return false end
        chunk[#chunk + 1] = value
      end
      local packet = {sc_api.int(buffer), sc_api.int(start + offset - 1), sc_api.int(#chunk), sc_api.float_array(chunk)}
      if not packet[4] or not host.sc_send("/b_setn", packet) then return false end
      offset = last + 1
    end
    return true
  end,
  load_synthdef = function(file)
    return host.sc_load_synthdef(tostring(file))
  end,
  poll_reply = function()
    if #sc_reply_backlog > 0 then return table.remove(sc_reply_backlog, 1) end
    return host.sc_poll_reply()
  end,
  sync = (function()
    local next_id = 1
    return function()
      local id = next_id
      next_id = next_id + 1
      if next_id > 2147483647 then next_id = 1 end
      if host.sc_send("/sync", {sc_api.int(id)}) then return id end
      return false
    end
  end)(),
}
sc = sc_api -- API pública para scripts; o motor usa a referência local protegida.

-- Gerenciamento básico de nodes do servidor. IDs são inteiros OSC e ficam
-- reservados localmente até /n_free ou /g_freeAll ser aceito para envio.
local sc_owned_nodes = {}
local sc_next_node_id = 100000

local function sc_valid_node_id(value)
  value = tonumber(value)
  if not value or value ~= math.floor(value) or value < 1 or value > 2147483647 then return nil end
  return math.floor(value)
end

local function sc_valid_nonnegative_int(value)
  value = tonumber(value)
  if not value or value ~= math.floor(value) or value < 0 or value > 2147483647 then return nil end
  return math.floor(value)
end

local function sc_append_controls(args, controls)
  if controls == nil then return true end
  if type(controls) ~= "table" then return false end
  for key, value in pairs(controls) do
    if type(key) ~= "string" or key == "" or type(value) ~= "number"
        or value ~= value or value == math.huge or value == -math.huge then
      return false
    end
    args[#args + 1] = key
    args[#args + 1] = value
  end
  return true
end

function sc_api.new(synth_name, controls, add_action, target_id)
  if not sc_api.is_running() or type(synth_name) ~= "string" or synth_name == "" then return false end
  add_action = sc_valid_nonnegative_int(add_action or 0)
  target_id = sc_valid_nonnegative_int(target_id or 0)
  if add_action == nil or add_action > 4 or target_id == nil then return false end

  local node_id = sc_next_node_id
  while sc_owned_nodes[node_id] do
    node_id = node_id + 1
    if node_id > 2147483647 then node_id = 100000 end
    if node_id == sc_next_node_id then return false end
  end
  sc_next_node_id = node_id + 1
  if sc_next_node_id > 2147483647 then sc_next_node_id = 100000 end

  local args = {synth_name, sc_api.int(node_id), sc_api.int(add_action), sc_api.int(target_id)}
  if not sc_append_controls(args, controls) then return false end
  if not host.sc_send("/s_new", args) then return false end
  sc_owned_nodes[node_id] = true
  return node_id
end

function sc_api.set(node_id, controls)
  node_id = sc_valid_node_id(node_id)
  if not sc_api.is_running() or node_id == nil then return false end
  local args = {sc_api.int(node_id)}
  if not sc_append_controls(args, controls) then return false end
  return host.sc_send("/n_set", args)
end

function sc_api.free(node_id)
  node_id = sc_valid_node_id(node_id)
  if not sc_api.is_running() or node_id == nil then return false end
  if not host.sc_send("/n_free", {sc_api.int(node_id)}) then return false end
  sc_owned_nodes[node_id] = nil
  return true
end

-- Remove do registro local um node cujo SynthDef usa doneAction=2 e já terminou.
function sc_api._forget_auto_freed(node_id)
  node_id = sc_valid_node_id(node_id)
  if node_id == nil then return false end
  sc_owned_nodes[node_id] = nil
  return true
end
function sc_api.query(node_id)
  node_id = sc_valid_node_id(node_id)
  if not sc_api.is_running() or node_id == nil then return false end
  return host.sc_send("/n_query", {sc_api.int(node_id)})
end

function sc_api.free_all()
  if not sc_api.is_running() or not host.sc_send("/g_freeAll", {sc_api.int(0)}) then return false end
  sc_owned_nodes = {}
  return true
end

function sc_api.owns_node(node_id)
  node_id = sc_valid_node_id(node_id)
  return node_id ~= nil and sc_owned_nodes[node_id] == true
end

-- ===================================================================== engine
-- PolyPerc e TestSine usam SynthDefs nativos quando /d_recv e confirmado.
-- Durante o carregamento, os comandos ficam em fila; falhas retornam ao Java.
local function engine_finish_load(ok, generation)
  if generation ~= nil and engine._native_generation ~= generation then return end
  if type(audio) == "table" and type(audio.level_eng_cut) == "function"
      and (softcut._engine_cut_level or 0) > 0 then
    if not audio.level_eng_cut(softcut._engine_cut_level) then
      host.log("audio.level_eng_cut: rota mantida até a gravação Softcut sincronizar.", false)
    end
  end
  local callback = engine._load_callback
  engine._load_callback = nil
  if callback then
    local success, err = pcall(callback, engine.list_commands())
    if not success then host.log(tostring(err), true) end
  end
end

local kolor_control_names = {
  "ampMin", "ampMax", "ampLFOMin", "ampLFOMax",
  "rateMin", "rateMax", "rateLFOMin", "rateLFOMax",
  "panMin", "panMax", "panLFOMin", "panLFOMax",
  "lpfMin", "lpfMax", "lpfLFOMin", "lpfLFOMax",
  "resonanceMin", "resonanceMax", "resonanceLFOMin", "resonanceLFOMax",
  "hpfMin", "hpfMax", "hpfLFOMin", "hpfLFOMax",
  "sampleStartMin", "sampleStartMax", "sampleStartLFOMin", "sampleStartLFOMax",
  "sampleEndMin", "sampleEndMax", "sampleEndLFOMin", "sampleEndLFOMax",
  "retrigMin", "retrigMax", "retrigLFOMin", "retrigLFOMax",
  "delaySendMin", "delaySendMax", "delaySendLFOMin", "delaySendLFOMax",
  "delayFeedbackMin", "delayFeedbackMax", "delayFeedbackLFOMin", "delayFeedbackLFOMax"
}

local function engine_kolor_number(value)
  local number = tonumber(value)
  if not number or number ~= number or number == math.huge or number == -math.huge then return nil end
  return number
end

local function engine_kolor_slot(value)
  local number = engine_kolor_number(value)
  if not number or number ~= math.floor(number) or number < 1 or number > 12 then return nil end
  return math.floor(number)
end

local function engine_kolor_cleanup()
  if type(engine) ~= "table" then return end
  -- engine has a permissive __index that returns command functions for unknown
  -- keys; guard internal collections before passing them to pairs().
  local nodes = type(engine._kolor_nodes) == "table" and engine._kolor_nodes or {}
  for _, node in pairs(nodes) do
    if node then sc_api.free(node) end
  end
  if engine._kolor_delay_node then sc_api.free(engine._kolor_delay_node) end
  for slot, loaded in pairs(engine._kolor_loaded or {}) do
    if loaded then sc_api.buffer_free(30000 + slot) end
  end
  -- A pending /b_allocRead can allocate after the engine is switched; queue
  -- /b_free behind it so stale sample buffers cannot leak across engines.
  if engine._kolor_loading and engine._kolor_loading.slot then
    sc_api.buffer_free(30000 + engine._kolor_loading.slot)
  end
  engine._kolor_nodes = {}
  engine._kolor_voice_banks = {}
  engine._kolor_loaded = {}
  engine._kolor_load_queue = {}
  engine._kolor_loading = nil
  engine._kolor_pending = {}
  engine._kolor_delay_node = nil
end

local engine_kolor_load_next

local function engine_kolor_poll_load(sync_id, slot, deadline)
  if engine._native_state ~= "native" or not engine._kolor_loading
      or engine._kolor_loading.sync_id ~= sync_id then return end
  local preserved = {}
  for i = 1, #sc_reply_backlog do preserved[#preserved + 1] = sc_reply_backlog[i] end
  sc_reply_backlog = {}
  local synchronized, failed, failure = false, false, nil
  for i = 1, 32 do
    local reply = host.sc_poll_reply()
    if not reply then break end
    if reply.address == "/synced" and tonumber(reply.sync_id) == sync_id then
      synchronized = true
    elseif reply.address == "/fail" and reply.command == "/b_allocRead" then
      failed, failure = true, reply.message
    else
      preserved[#preserved + 1] = reply
    end
  end
  for i = 1, #preserved do sc_reply_backlog[#sc_reply_backlog + 1] = preserved[i] end
  if failed or (not synchronized and host.now() >= deadline) then
    host.log("SimpleDelayKolor: falha ao carregar banco " .. tostring(slot)
      .. (failure and (": " .. tostring(failure)) or " (timeout)"), true)
    sc_api.buffer_free(30000 + slot)
    engine._kolor_loading = nil
    engine_kolor_load_next()
    return
  end
  if synchronized then
    engine._kolor_loaded[slot] = true
    engine._kolor_loading = nil
    engine_kolor_load_next()
    return
  end
  sched_add(host.now() + 0.01, function()
    engine_kolor_poll_load(sync_id, slot, deadline)
  end)
end

engine_kolor_load_next = function()
  if engine._native_state ~= "native" or engine._kolor_loading then return end
  local request = table.remove(engine._kolor_load_queue or {}, 1)
  if not request then return end
  local slot, buffer = request.slot, 30000 + request.slot
  if engine._kolor_loaded[slot] then
    for voice, node in pairs(engine._kolor_nodes or {}) do
      if engine._kolor_voice_banks[voice] == slot then
        sc_api.set(node, {t_gate = 0})
        engine._kolor_voice_banks[voice] = nil
      end
    end
    sc_api.buffer_free(buffer)
    engine._kolor_loaded[slot] = nil
  end
  if not sc_api.send("/b_allocRead", sc_api.int(buffer), request.path, sc_api.int(0), sc_api.int(-1)) then
    host.log("SimpleDelayKolor: não foi possível solicitar o WAV do banco " .. tostring(slot), true)
    engine_kolor_load_next()
    return
  end
  local sync_id = sc_api.sync()
  if not sync_id then
    host.log("SimpleDelayKolor: não foi possível sincronizar o carregamento do banco " .. tostring(slot), true)
    sc_api.buffer_free(buffer)
    engine_kolor_load_next()
    return
  end
  engine._kolor_loading = {slot = slot, sync_id = sync_id}
  sched_add(host.now() + 0.01, function()
    engine_kolor_poll_load(sync_id, slot, host.now() + 5.0)
  end)
end

local function engine_kolor_sample(slot_value, path_value)
  local slot = engine_kolor_slot(slot_value)
  if not slot or type(path_value) ~= "string" or path_value == "" then return false end
  -- Map the upstream Norns dust root to the app-private Android dust root.
  local legacy_root = "/home/we/dust/"
  if string.sub(path_value, 1, #legacy_root) == legacy_root then
    path_value = _path.dust .. string.sub(path_value, #legacy_root + 1)
  end
  local path = host.resolve_sample_path(path_value)
  if not path then
    host.log("SimpleDelayKolor: banco deve ser um WAV válido dentro da pasta dust (máximo 100 MB).", true)
    return false
  end
  engine._kolor_load_queue = engine._kolor_load_queue or {}
  engine._kolor_load_queue[#engine._kolor_load_queue + 1] = {slot = slot, path = path}
  engine_kolor_load_next()
  return true
end

local function engine_kolor_play(args)
  local voice = engine_kolor_slot(args[1])
  local current_time = engine_kolor_number(args[2])
  local bank = engine_kolor_slot(args[48])
  local lfo_rate = engine_kolor_number(args[47])
  local seconds_per_beat = engine_kolor_number(args[49])
  if not voice or not current_time or not bank or not lfo_rate or not seconds_per_beat
      or not engine._kolor_loaded[bank] then
    host.log("SimpleDelayKolor: kolorplay requer voz 1-12 e banco WAV já carregado.", false)
    return false
  end
  local controls = {
    currentTime = current_time, lfolfo = lfo_rate,
    sampleBufnum = 30000 + bank, secondsPerBeat = math.max(0.001, math.min(4, seconds_per_beat)),
    t_trig = 1, t_gate = 1
  }
  for i = 1, #kolor_control_names do
    local value = engine_kolor_number(args[i + 2])
    if not value then
      host.log("SimpleDelayKolor: parâmetro não numérico em kolorplay (" .. tostring(i + 2) .. ").", true)
      return false
    end
    controls[kolor_control_names[i]] = value
  end
  local node = engine._kolor_nodes[voice]
  if node then
    if not sc_api.set(node, controls) then return false end
  else
    node = sc_api.new("NornsKolorPlayer", controls)
    if not node then return false end
    engine._kolor_nodes[voice] = node
  end
  engine._kolor_voice_banks[voice] = bank
  return true
end

local function engine_fallback()
  engine._native_generation = (engine._native_generation or 0) + 1
  if engine._native_sync_id ~= nil or engine._kolor_phase_sync_id ~= nil then
    engine._retired_native_sync_ids = engine._retired_native_sync_ids or {}
    if engine._native_sync_id ~= nil then
      engine._retired_native_sync_ids[engine._native_sync_id] = true
    end
    if engine._kolor_phase_sync_id ~= nil then
      engine._retired_native_sync_ids[engine._kolor_phase_sync_id] = true
    end
  end
  engine._native_sync_id = nil
  engine._kolor_phase_sync_id = nil
  engine._native_load_failed = false
  engine._native_load_failure = nil
  if engine.name == "SimpleDelayKolor" then
    engine_kolor_cleanup()
    engine._native_state = "unavailable"
    engine.loaded = false
    engine._native_pending = {}
    host.engine_load("none")
    host.log("SimpleDelayKolor: o servidor/SynthDefs nativos não estão disponíveis; engine mantido indisponível.", true)
    engine_finish_load(false)
    return
  elseif engine.name == "Stratum" then
    if engine._native_node then sc_api.free(engine._native_node) end
    engine._native_node = nil
    engine._native_state = "unavailable"
    engine.loaded = false
    host.engine_load("none")
    local stratum_output = type(STRATUM_LIB) == "table" and STRATUM_LIB.output or nil
    if type(stratum_output) == "table" and type(stratum_output.set_engine_ready) == "function" then
      pcall(stratum_output.set_engine_ready, false)
    end
    if type(params) == "table" and type(params.get) == "function" and type(params.set) == "function" then
      local ok, mode = pcall(params.get, params, "output_mode")
      if ok and (mode == 1 or mode == 3 or mode == 5) then
        pcall(params.set, params, "output_mode", 2)
      end
    end
    host.log("Stratum: MiRings/MiClouds nativos indisponíveis; engine desativado (MIDI quando aplicável), sem PolyPerc.", true)
    engine_finish_load(false)
    return
  end
  if engine._native_node then sc_api.free(engine._native_node) end
  engine._native_node = nil
  local voices = engine._native_voices or {}
  for i = 1, #voices do
    if voices[i] and voices[i].id then sc_api.free(voices[i].id) end
  end
  engine._native_voices = {}
  engine._native_state = "java"
  local pending = engine._native_pending or {}
  engine._native_pending = {}
  -- Native success switches the Java SynthCore to "none". Re-select the
  -- requested engine before replaying cached controls or queued commands.
  host.engine_load(engine.name)
  if engine.name == "TestSine" then
    local params = engine._native_params.testSine or {freq = 440, amp = 0.1}
    host.engine_cmd("hz", params.freq or 440, 0)
    host.engine_cmd("amp", params.amp or 0.1, 0)
  elseif engine.name == "32Harmony" then
    for name, value in pairs(engine._native_params.harmony or {}) do
      host.engine_cmd(name, value, 0)
    end
  elseif engine.name == "PolyPerc" then
    for name, value in pairs(engine._native_params) do
      if type(value) == "number" then host.engine_cmd(name, value, 0) end
    end
  end
  for i = 1, #pending do
    local item = pending[i]
    host.engine_cmd(item.name, item.a, item.b)
  end
  engine_finish_load(false)
end

local function engine_polyperc_note(hz)
  hz = tonumber(hz) or 0
  if hz <= 1 or hz > 19200 then return false end
  local voices = engine._native_voices or {}
  local now = host.now()
  while voices[1] and voices[1].expires <= now do
    local finished = table.remove(voices, 1)
    sc_api._forget_auto_freed(finished.id)
  end
  while #voices >= 12 do
    local oldest = table.remove(voices, 1)
    if oldest then sc_api.free(oldest.id) end
  end
  local p = engine._native_params
  local node = sc_api.new("PolyPerc", {
    freq = hz,
    amp = math.min(4, p.amp * (1 + 0.6 * p.gain * 0.97) * 0.8),
    pw = p.pw, release = p.release, cutoff = p.cutoff,
    gain = p.gain * 0.97, pan = p.pan
  })
  if not node then
    host.log("SuperCollider: não foi possível criar voz PolyPerc; retornando ao Java.", true)
    for i = 1, #voices do sc_api.free(voices[i].id) end
    engine._native_state = "java"
    if type(audio) == "table" and type(audio.level_eng_cut) == "function"
        and (softcut._engine_cut_level or 0) > 0 then
      audio.level_eng_cut(softcut._engine_cut_level)
    end
    engine._native_voices = {}
    host.engine_load("PolyPerc")
    host.engine_cmd("hz", hz, 0)
    return false
  end
  voices[#voices + 1] = {id = node, expires = now + p.release + 0.08}
  engine._native_voices = voices
  return true
end

local function engine_polyperc_command(name, value)
  value = tonumber(value) or 0
  local p = engine._native_params
  if name == "hz" then
    return engine_polyperc_note(value)
  elseif name == "amp" then
    p.amp = math.max(0, math.min(4, value))
  elseif name == "pw" then
    p.pw = math.max(0.01, math.min(0.99, value))
  elseif name == "release" then
    p.release = math.max(0.005, math.min(20, value))
  elseif name == "cutoff" then
    p.cutoff = math.max(20, math.min(20000, value))
  elseif name == "gain" then
    p.gain = math.max(0, math.min(4, value))
  elseif name == "pan" then
    p.pan = math.max(-1, math.min(1, value))
  else
    return false
  end
  return true
end

local engine_dispatch_command

local function engine_poll_kolor_native(generation, deadline)
  if engine._native_generation ~= generation or engine._native_state ~= "loading"
      or engine.name ~= "SimpleDelayKolor" then return end
  local preserved = {}
  local synchronized = false
  local function inspect_reply(reply)
    local reply_sync_id = tonumber(reply.sync_id)
    if reply.address == "/synced" and reply_sync_id == engine._kolor_phase_sync_id then
      synchronized = true
    elseif reply.address == "/synced"
        and engine._retired_native_sync_ids
        and engine._retired_native_sync_ids[reply_sync_id] then
      engine._retired_native_sync_ids[reply_sync_id] = nil
    elseif reply.address == "/fail" and reply.command == "/d_recv" then
      local retired_pending = false
      for _ in pairs(engine._retired_native_sync_ids or {}) do
        retired_pending = true
        break
      end
      if not retired_pending then
        engine._native_load_failed = true
        engine._native_load_failure = reply.message
      end
    elseif reply.address == "/done" and reply.command == "/d_recv" then
      -- /done is uncorrelated; only this phase's /synced is authoritative.
    else
      preserved[#preserved + 1] = reply
    end
  end
  for i = 1, #sc_reply_backlog do inspect_reply(sc_reply_backlog[i]) end
  sc_reply_backlog = {}
  for i = 1, 32 do
    local reply = host.sc_poll_reply()
    if not reply then break end
    inspect_reply(reply)
  end
  for i = 1, #preserved do sc_reply_backlog[#sc_reply_backlog + 1] = preserved[i] end

  if synchronized then
    engine._kolor_phase_sync_id = nil
    if engine._native_load_failed then
      host.log("SimpleDelayKolor: falha ao carregar SynthDef ("
          .. tostring(engine._native_load_failure) .. ").", true)
      engine_fallback()
      return
    end
    if engine._kolor_phase == 1 then
      if not sc_api.load_synthdef("NornsKolorDelay.scsyndef") then
        engine_fallback()
        return
      end
      local sync_id = sc_api.sync()
      if not sync_id then
        engine_fallback()
        return
      end
      engine._native_load_failed = false
      engine._native_load_failure = nil
      engine._kolor_phase = 2
      engine._kolor_phase_sync_id = sync_id
      sched_add(host.now() + 0.01, function()
        engine_poll_kolor_native(generation, host.now() + 5.0)
      end)
      return
    end
    if engine._kolor_phase == 2 then
      local node = sc_api.new("NornsKolorDelay", {
        delay = engine._kolor_delay or 0.03, volume = engine._kolor_volume or 0
      })
      if not node then
        engine_fallback()
        return
      end
      engine._kolor_delay_node = node
      engine._kolor_nodes = {}
      engine._kolor_voice_banks = {}
      engine._kolor_loaded = {}
      engine._kolor_load_queue = {}
      engine._kolor_pending = engine._kolor_pending or {}
      engine._kolor_phase = nil
      engine._native_state = "native"
      host.engine_load("none")
      local pending = engine._native_pending or {}
      engine._native_pending = {}
      for i = 1, #pending do
        local item = pending[i]
        engine_dispatch_command(item.name, item.a, item.b)
      end
      local kolor_pending = engine._kolor_pending or {}
      engine._kolor_pending = {}
      for i = 1, #kolor_pending do
        local item = kolor_pending[i]
        if item.name == "kolorsample" then
          engine_kolor_sample(item.args[1], item.args[2])
        elseif item.name == "kolorplay" then
          engine_kolor_play(item.args)
        else
          engine_dispatch_command(item.name, unpack(item.args))
        end
      end
      engine_finish_load(true, generation)
      return
    end
  end

  if host.now() >= deadline then
    host.log("SimpleDelayKolor: timeout aguardando sincronização dos SynthDefs.", true)
    engine_fallback()
    return
  end
  sched_add(host.now() + 0.01, function()
    engine_poll_kolor_native(generation, deadline)
  end)
end

local function engine_poll_native(generation, deadline)
  if engine.name == "SimpleDelayKolor" then
    return engine_poll_kolor_native(generation, deadline)
  end
  if engine._native_generation ~= generation or engine._native_state ~= "loading" then return end

  -- /done /d_recv has no request identifier. A delayed reply from a retired
  -- engine generation must never complete the current load. /sync is an
  -- ordered server barrier and its identifier is generation-specific.
  local preserved = {}
  local synchronized, failed, failure = false, false, nil
  local function inspect_reply(reply)
    local reply_sync_id = tonumber(reply.sync_id)
    if reply.address == "/synced" and reply_sync_id == engine._native_sync_id then
      synchronized = true
    elseif reply.address == "/synced"
        and engine._retired_native_sync_ids
        and engine._retired_native_sync_ids[reply_sync_id] then
      engine._retired_native_sync_ids[reply_sync_id] = nil
    elseif reply.address == "/fail" and reply.command == "/d_recv" then
      -- Replies preceding a retired /sync fence belong to the retired load.
      local retired_pending = false
      for _ in pairs(engine._retired_native_sync_ids or {}) do
        retired_pending = true
        break
      end
      if not retired_pending then
        engine._native_load_failed = true
        engine._native_load_failure = reply.message
        failed, failure = true, reply.message
      end
    elseif reply.address == "/done" and reply.command == "/d_recv" then
      -- Uncorrelated completion; the matching /synced barrier is authoritative.
    else
      preserved[#preserved + 1] = reply
    end
  end
  for i = 1, #sc_reply_backlog do inspect_reply(sc_reply_backlog[i]) end
  sc_reply_backlog = {}
  for i = 1, 32 do
    local reply = host.sc_poll_reply()
    if not reply then break end
    inspect_reply(reply)
  end
  for i = 1, #preserved do sc_reply_backlog[#sc_reply_backlog + 1] = preserved[i] end
  failed = failed or engine._native_load_failed
  failure = failure or engine._native_load_failure

  if synchronized then
    -- The current barrier has been consumed; fallback must not retire it.
    engine._native_sync_id = nil
    if failed then
      host.log("SuperCollider: falha ao carregar " .. tostring(engine.name) .. " ("
          .. tostring(failure) .. "); usando fallback.", false)
      engine_fallback()
      return
    end
    if engine.name == "TestSine" then
      local node = sc_api.new("TestSine", {freq = 440, amp = 0.1})
      if not node then engine_fallback(); return end
      engine._native_node = node
    elseif engine.name == "PolyPerc" then
      engine._native_voices = {}
    elseif engine.name == "32Harmony" then
      local node = sc_api.new("Norns32Harmony", {
        rate1In = 1, rate2In = 1, rootIn = 32.7,
        range1In = 4, range2In = 4, attackIn = 0.01, decayIn = 3.5,
        lpfCutoffIn = 4000, hpfCutoffIn = 20, quantAmtIn = 0.93,
        step0In = 1, step1In = 2 ^ (2 / 12), step2In = 2 ^ (4 / 12),
        step3In = 2 ^ (7 / 12), step4In = 2 ^ (9 / 12),
        freqMultIn = 1, saturationAmtIn = 1
      })
      if not node then engine_fallback(); return end
      engine._native_node = node
    elseif engine.name == "Stratum" then
      local node = sc_api.new("NornsStratum", {
        out = 0, rings_pit = 48, rings_struct = 0.36, rings_bright = 0.5,
        rings_damp = 0.5, rings_pos = 0.33, rings_model = 0,
        rings_poly = 4, rings_easteregg = 0, rings_gate = 0,
        rings_amp = 0.8, rings_odd_even = 0.5, rings_bypass = 0,
        clouds_pos = 0.5, clouds_size = 0.3, clouds_pitch = 0,
        clouds_dens = 0.5, clouds_tex = 0.5, clouds_spread = 0,
        clouds_fb = 0.2, clouds_freeze = 0, clouds_rvb = 0.1,
        clouds_lofi = 0, clouds_mode = 0, clouds_mix = 0.45, clouds_bypass = 0
      })
      if not node then engine_fallback(); return end
      engine._native_node = node
    end
    engine._native_state = "native"
    engine._native_sync_id = nil
    host.engine_load("none")
    local pending = engine._native_pending or {}
    engine._native_pending = {}
    for j = 1, #pending do
      local item = pending[j]
      engine_dispatch_command(item.name, item.a, item.b)
    end
    local kolorPending = engine._kolor_pending or {}
    engine._kolor_pending = {}
    for j = 1, #kolorPending do
      local item = kolorPending[j]
      if item.name == "kolorsample" then
        engine_kolor_sample(item.args[1], item.args[2])
      elseif item.name == "kolorplay" then
        engine_kolor_play(item.args)
      else
        engine_dispatch_command(item.name, unpack(item.args))
      end
    end
    engine_finish_load(true, generation)
    return
  end
  if host.now() >= deadline then
    if engine.name == "Stratum" then
      host.log("Stratum: timeout ao inicializar MiRings/MiClouds; motor desativado sem substituição.", true)
    else
      host.log("SuperCollider: timeout ao sincronizar " .. tostring(engine.name) .. "; usando fallback.", false)
    end
    engine_fallback()
    return
  end
  sched_add(host.now() + 0.01, function() engine_poll_native(generation, deadline) end)
end

local function engine_start_native(callback)
  local synthdef
  if engine.name == "none" or engine.name == "None" then
    engine._native_state = "java"
    if type(callback) == "function" then
      local ok, err = pcall(callback, engine.list_commands())
      if not ok then host.log(tostring(err), true) end
    end
    return false
  elseif engine.name == "TestSine" then synthdef = "TestSine.scsyndef"
  elseif engine.name == "PolyPerc" then synthdef = "PolyPerc.scsyndef"
  elseif engine.name == "FM" or engine.name == "Pluck" or engine.name == "SimpleDelay" then
    engine._native_state = "java"
    if type(callback) == "function" then
      local ok, err = pcall(callback, engine.list_commands())
      if not ok then host.log(tostring(err), true) end
    end
    return false
  elseif engine.name == "32Harmony" then synthdef = "Norns32Harmony.scsyndef"
  elseif engine.name == "Stratum" then synthdef = "NornsStratum.scsyndef"
  elseif engine.name == "SimpleDelayKolor" then
    synthdef = "NornsKolorPlayer.scsyndef"
    engine._kolor_phase = 1
  else
    engine._native_state = "unavailable"
    engine.loaded = false
    host.engine_load("none")
    host.log("Engine '" .. tostring(engine.name) .. "' não está disponível neste build; não será substituída por PolyPerc.", true)
    if type(callback) == "function" then
      local ok, err = pcall(callback, engine.list_commands())
      if not ok then host.log(tostring(err), true) end
    end
    return false
  end
  if not sc_api.is_running() or not sc_api.load_synthdef(synthdef) then
    if engine.name == "SimpleDelayKolor" then
      engine._native_state = "unavailable"
      engine.loaded = false
      host.engine_load("none")
      host.log("SimpleDelayKolor: requer scsynth nativo; nenhum fallback silencioso será anunciado.", true)
    elseif engine.name == "Stratum" then
      engine._native_state = "unavailable"
      engine.loaded = false
      host.engine_load("none")
      host.log("Stratum: SynthDef/UGens nativos não carregados; saída Rings desabilitada.", true)
    else
      engine._native_state = "java"
    end
    if type(callback) == "function" then
      local ok, err = pcall(callback, engine.list_commands())
      if not ok then host.log(tostring(err), true) end
    end
    return false
  end
  engine._native_state = "loading"
  engine._native_pending = {}
  engine._load_callback = type(callback) == "function" and callback or nil
  local generation = engine._native_generation
  engine._native_load_failed = false
  engine._native_load_failure = nil
  if engine.name ~= "SimpleDelayKolor" then
    -- /done /d_recv is untagged; complete only after this load's /sync fence.
    local sync_id = sc_api.sync()
    if not sync_id then
      engine_fallback()
      return false
    end
    engine._native_sync_id = sync_id
    engine._native_load_failed = false
  end
  if engine.name == "SimpleDelayKolor" then
    local sync_id = sc_api.sync()
    if not sync_id then
      engine_fallback()
      return false
    end
    engine._kolor_phase = 1
    engine._kolor_phase_sync_id = sync_id
    sched_add(host.now() + 0.01, function()
      engine_poll_kolor_native(generation, host.now() + 5.0)
    end)
  else
    sched_add(host.now() + 0.01, function()
      engine_poll_native(generation, host.now() + 5.0)
    end)
  end
  return true
end

engine_dispatch_command = function(name, ...)
  if not engine.loaded then
    host.log("Engine '" .. tostring(engine.name) .. "' ainda não foi carregada.", false)
    return
  end
  local args = {...}
  local value = tonumber(args[1]) or 0
  if engine.name == "TestSine" and (name == "hz" or name == "amp") then
    if engine._native_state == "native" then
      if not engine._native_node then
        engine_fallback()
        return host.engine_cmd(name, value, tonumber(args[2]) or 0)
      end
      local params = engine._native_params.testSine
      if name == "hz" then params.freq = value else params.amp = value end
      if not sc_api.set(engine._native_node, {[name == "hz" and "freq" or "amp"] = value}) then
        engine_fallback()
      end
      return true
    elseif engine._native_state == "loading" then
      engine._native_pending[#engine._native_pending + 1] = {name = name, a = value, b = tonumber(args[2]) or 0}
      return true
    end
  elseif engine.name == "PolyPerc" then
    if engine._native_state == "loading" then
      engine._native_pending[#engine._native_pending + 1] = {name = name, a = value, b = tonumber(args[2]) or 0}
      return true
    elseif engine._native_state == "native" then
      return engine_polyperc_command(name, value)
    end
  elseif engine.name == "FM" then
    local commands = {hz=true, amp=true, ratio=true, index=true, release=true, pan=true}
    if not commands[name] then return false end
    return host.engine_cmd(name, ...)
  elseif engine.name == "Pluck" then
    local commands = {hz=true, amp=true, decay=true, damp=true, pan=true}
    if not commands[name] then return false end
    return host.engine_cmd(name, ...)
  elseif engine.name == "SimpleDelay" then
    if name ~= "threshold" then return false end
    engine._native_params.simpleDelay = engine._native_params.simpleDelay or {threshold = -60}
    engine._native_params.simpleDelay.threshold = value
    return host.engine_cmd(name, value, 0)
  elseif engine.name == "SimpleDelayKolor" then
    if name == "delay" or name == "volume" then
      local numeric = engine_kolor_number(args[1])
      if not numeric then return false end
      if name == "delay" then
        engine._kolor_delay = math.max(0.001, math.min(0.03, numeric))
      else
        engine._kolor_volume = math.max(0, math.min(4, numeric))
      end
      if engine._native_state == "native" and engine._kolor_delay_node then
        return sc_api.set(engine._kolor_delay_node, {[name] = name == "delay"
          and engine._kolor_delay or engine._kolor_volume})
      end
      return engine._native_state == "loading"
    elseif name == "kolorsample" or name == "kolorplay" then
      if engine._native_state == "loading" then
        engine._kolor_pending = engine._kolor_pending or {}
        engine._kolor_pending[#engine._kolor_pending + 1] = {name = name, args = args}
        return true
      elseif engine._native_state == "native" then
        if name == "kolorsample" then return engine_kolor_sample(args[1], args[2]) end
        return engine_kolor_play(args)
      end
      return false
    end
    return false
  elseif engine.name == "Stratum" then
    local commands = {
      rings_gate=true, rings_pit=true, rings_struct=true, rings_bright=true,
      rings_damp=true, rings_pos=true, rings_model=true, rings_poly=true,
      rings_easteregg=true, rings_amp=true, rings_odd_even=true, rings_bypass=true,
      clouds_pos=true, clouds_size=true, clouds_pitch=true, clouds_dens=true,
      clouds_tex=true, clouds_spread=true, clouds_fb=true, clouds_freeze=true,
      clouds_rvb=true, clouds_lofi=true, clouds_mode=true, clouds_mix=true,
      clouds_bypass=true
    }
    if not commands[name] then return false end
    if engine._native_state == "loading" then
      engine._native_pending[#engine._native_pending + 1] = {name = name, a = value, b = 0}
      return true
    elseif engine._native_state == "native" then
      if not engine._native_node then engine_fallback(); return false end
      engine._native_params.stratum = engine._native_params.stratum or {}
      engine._native_params.stratum[name] = value
      if not sc_api.set(engine._native_node, {[name] = value}) then
        engine_fallback()
        return false
      end
      return true
    end
    return false
  elseif engine.name == "32Harmony" then
    local commands = {rate1In=true, rate2In=true, rootIn=true, range1In=true, range2In=true,
      attackIn=true, decayIn=true, lpfCutoffIn=true, hpfCutoffIn=true, quantAmtIn=true,
      step0In=true, step1In=true, step2In=true, step3In=true, step4In=true,
      freqMultIn=true, saturationAmtIn=true}
    if not commands[name] then return false end
    if engine._native_state == "loading" then
      engine._native_pending[#engine._native_pending + 1] = {name = name, a = value, b = 0}
      return true
    elseif engine._native_state == "native" then
      engine._native_params = engine._native_params or {}
      engine._native_params.harmony = engine._native_params.harmony or {}
      engine._native_params.harmony[name] = value
      if not engine._native_node then
        engine_fallback()
      elseif not sc_api.set(engine._native_node, {[name] = value}) then
        engine_fallback()
      end
      return true
    end
  end
  return host.engine_cmd(name, ...)
end

engine = setmetatable({name = "none", loaded = false, _native_state = "java",
  _native_generation = 0, _native_pending = {}, _native_node = nil,
  _native_voices = {}, _kolor_nodes = {}, _kolor_voice_banks = {},
  _kolor_loaded = {}, _kolor_load_queue = {}, _kolor_loading = nil,
  _kolor_pending = {}, _kolor_delay_node = nil, _native_params = {
    amp = 0.5, pw = 0.5, release = 0.5, cutoff = 1000, gain = 2, pan = 0,
    testSine = {freq = 440, amp = 0.1}, harmony = {}, simpleDelay = {threshold = -60},
    stratum = {
      rings_pit = 48, rings_struct = 0.36, rings_bright = 0.5, rings_damp = 0.5,
      rings_pos = 0.33, rings_model = 0, rings_poly = 4, rings_easteregg = 0,
      rings_gate = 0, rings_amp = 0.8, rings_odd_even = 0.5, rings_bypass = 0,
      clouds_pos = 0.5, clouds_size = 0.3, clouds_pitch = 0, clouds_dens = 0.5,
      clouds_tex = 0.5, clouds_spread = 0, clouds_fb = 0.2, clouds_freeze = 0,
      clouds_rvb = 0.1, clouds_lofi = 0, clouds_mode = 0, clouds_mix = 0.45,
      clouds_bypass = 0
    }
  }}, {
  __index = function(t, k)
    -- Internal state fields must stay nil when absent; only public engine
    -- command names are synthesized into callable dispatch functions.
    if type(k) ~= "string" or string.sub(k, 1, 1) == "_" then return nil end
    return function(...) return engine_dispatch_command(k, ...) end
  end,
})

function engine._reset_native()
  engine._native_generation = engine._native_generation + 1
  if engine._native_sync_id ~= nil then
    engine._retired_native_sync_ids = engine._retired_native_sync_ids or {}
    engine._retired_native_sync_ids[engine._native_sync_id] = true
  end
  if engine._kolor_phase_sync_id ~= nil then
    engine._retired_native_sync_ids = engine._retired_native_sync_ids or {}
    engine._retired_native_sync_ids[engine._kolor_phase_sync_id] = true
  end
  engine_kolor_cleanup()
  engine._registered_commands = nil
  if engine._native_node then sc_api.free(engine._native_node) end
  engine._native_node = nil
  local voices = engine._native_voices or {}
  local now = host.now()
  for i = 1, #voices do
    if voices[i].expires > now then sc_api.free(voices[i].id)
    else sc_api._forget_auto_freed(voices[i].id) end
  end
  engine._native_voices = {}
  engine._native_state = "java"
  engine._native_pending = {}
  engine._load_callback = nil
  engine._native_sync_id = nil
  engine._kolor_phase_sync_id = nil
  engine._native_load_failed = false
  engine._native_load_failure = nil
  engine._kolor_phase = nil
  engine._kolor_delay = 0.03
  engine._kolor_volume = 0
  engine._kolor_nodes = {}
  engine._kolor_voice_banks = {}
  engine._kolor_loaded = {}
  engine._kolor_load_queue = {}
  engine._kolor_loading = nil
  engine._kolor_pending = {}
  engine._kolor_delay_node = nil
  engine._native_params = {
    amp = 0.5, pw = 0.5, release = 0.5, cutoff = 1000, gain = 2, pan = 0,
    testSine = {freq = 440, amp = 0.1}, harmony = {},
    stratum = {
      rings_pit = 48, rings_struct = 0.36, rings_bright = 0.5, rings_damp = 0.5,
      rings_pos = 0.33, rings_model = 0, rings_poly = 4, rings_easteregg = 0,
      rings_gate = 0, rings_amp = 0.8, rings_odd_even = 0.5, rings_bypass = 0,
      clouds_pos = 0.5, clouds_size = 0.3, clouds_pitch = 0, clouds_dens = 0.5,
      clouds_tex = 0.5, clouds_spread = 0, clouds_fb = 0.2, clouds_freeze = 0,
      clouds_rvb = 0.1, clouds_lofi = 0, clouds_mode = 0, clouds_mix = 0.45,
      clouds_bypass = 0
    }
  }
end

function engine._start_native(callback)
  return engine_start_native(callback)
end

function engine.load(name, cb)
  if engine._native_state == "loading" then return false end
  if type(name) == "function" then cb = name; name = nil end
  if name ~= nil then engine.name = tostring(name) end
  if engine.name == nil or engine.name == "" then engine.name = "none" end
  engine._reset_native()
  if engine.name ~= "none" and engine.name ~= "None"
      and engine.name ~= "PolyPerc" and engine.name ~= "TestSine"
      and engine.name ~= "32Harmony" and engine.name ~= "Stratum" and engine.name ~= "FM" and engine.name ~= "Pluck"
      and engine.name ~= "SimpleDelay" and engine.name ~= "SimpleDelayKolor" then
    engine.loaded = false
    engine._native_state = "unavailable"
    host.engine_load("none")
    host.log("Engine '" .. tostring(engine.name) .. "' não está disponível neste build; não será substituída por PolyPerc.", true)
    if type(cb) == "function" then safe(cb, {}) end
    return false
  end
  host.engine_load(engine.name)
  engine.loaded = true
  engine_start_native(cb)
  return true
end

local function engine_registration_count(data, count)
  if type(data) ~= "table" then return nil end
  local limit = count == nil and #data or tonumber(count)
  if not limit or limit ~= limit or limit == math.huge or limit == -math.huge
      or limit ~= math.floor(limit) or limit < 0 or limit > #data then
    return nil
  end
  return limit
end

function engine.register(data, count)
  local limit = engine_registration_count(data, count)
  if limit == nil then return false end
  local names = {}
  for i = 1, limit do
    local name = data[i]
    if type(name) ~= "string" or name == "" then return false end
    names[#names + 1] = name
  end
  engine.names = names
  return true
end

function engine.add_command(id, name, fmt)
  id = tonumber(id)
  if not id or id ~= id or id == math.huge or id == -math.huge
      or id ~= math.floor(id) or id < 0 or id > 4095
      or type(name) ~= "string" or name == ""
      or (fmt ~= nil and type(fmt) ~= "string") then
    return false
  end
  engine._registered_commands = engine._registered_commands or {}
  engine._registered_commands[id + 1] = {name = name, format = fmt or ""}
  return true
end

function engine.register_commands(data, count)
  local limit = engine_registration_count(data, count)
  if limit == nil then return false end
  local commands = {}
  for i = 1, limit do
    local item = data[i]
    local name, format
    if type(item) == "table" then
      name = item.name or item[1]
      format = item.format or item.fmt or item[2] or ""
    elseif type(item) == "string" then
      name, format = item, ""
    else
      return false
    end
    if type(name) ~= "string" or name == "" or type(format) ~= "string" then
      return false
    end
    commands[i] = {name = name, format = format}
  end
  engine._registered_commands = commands
  return true
end

function engine.list_commands()
  if type(engine._registered_commands) == "table" then
    local result = {}
    for i = 1, #engine._registered_commands do
      local item = engine._registered_commands[i]
      if type(item) == "table" and item.name then result[#result + 1] = item.name end
    end
    return result
  end
  if engine.name == "PolyPerc" then
    return {"hz", "amp", "pw", "release", "cutoff", "gain", "pan"}
  elseif engine.name == "TestSine" then
    return {"hz", "amp"}
  elseif engine.name == "FM" then
    return {"hz", "amp", "ratio", "index", "release", "pan"}
  elseif engine.name == "Pluck" then
    return {"hz", "amp", "decay", "damp", "pan"}
  elseif engine.name == "SimpleDelay" then
    return {"threshold"}
  elseif engine.name == "SimpleDelayKolor" then
    return {"delay", "volume", "kolorsample", "kolorplay"}
  elseif engine.name == "32Harmony" then
    return {"rate1In", "rate2In", "rootIn", "range1In", "range2In", "attackIn",
      "decayIn", "lpfCutoffIn", "hpfCutoffIn", "quantAmtIn", "step0In", "step1In",
      "step2In", "step3In", "step4In", "freqMultIn", "saturationAmtIn"}
  elseif engine.name == "Stratum" then
    return {"rings_gate", "rings_pit", "rings_struct", "rings_bright", "rings_damp",
      "rings_pos", "rings_model", "rings_poly", "rings_easteregg", "rings_amp",
      "rings_odd_even", "rings_bypass", "clouds_pos", "clouds_size", "clouds_pitch",
      "clouds_dens", "clouds_tex", "clouds_spread", "clouds_fb", "clouds_freeze",
      "clouds_rvb", "clouds_lofi", "clouds_mode", "clouds_mix", "clouds_bypass"}
  end
  return {}
end
-- ===================================================================== stubs (ainda não implementados)
local function null_object(label, fields)
  local warned = false
  local obj = {available = false, connected = false}
  local mt = {}
  mt.__index = function(t, k)
    if type(k) == "number" then return nil end
    if not warned then
      warned = true
      host.log(label .. ": recurso ainda não implementado (ignorado)", false)
    end
    return t
  end
  mt.__call = function() return obj end
  mt.__tostring = function() return label end
  mt.__concat = function(a, b) return tostring(a) .. tostring(b) end
  mt.__len = function() return 0 end
  setmetatable(obj, mt)
  if fields then
    for k, v in pairs(fields) do rawset(obj, k, v) end
  end
  return obj
end

-- MIDI real via android.media.midi (USB, BLE-MIDI e portas virtuais do sistema).
midi = { devices = {}, vports = {}, event = nil, _ports = {} }

-- Norns mantém 16 slots estáveis: remover um dispositivo não desloca os
-- índices que os scripts guardam em midi.connect(n).
for i = 1, 16 do
  midi.vports[i] = {index = i, id = nil, name = "none", device = nil,
    connected = false, connecting = false, event = nil, info = nil}
end
midi.vports[1].id, midi.vports[1].name = 0, "virtual"
midi.vports[1].virtual, midi.vports[1].type = true, "output"

local function midi_slot_for_id(id)
  for i = 2, 16 do
    if midi.vports[i].id == id then return midi.vports[i] end
  end
end
local function midi_slot_for_name(name)
  for i = 2, 16 do
    if midi.vports[i].name == name then return midi.vports[i] end
  end
end

-- O Android descreve as portas pelo ponto de vista do dispositivo:
-- inputs recebem dados enviados pelo Norns; outputs enviam dados ao Norns.
-- Preserva slots e nomes em hotplug; dispositivos novos ocupam o primeiro
-- slot livre, enquanto um dispositivo removido deixa o slot reservado.
local function install_midi_device_methods(device)
  local function selected_slot(self)
    local slot = self.port and midi.vports[self.port] or nil
    if not slot then return nil end
    if not slot.device then
      if slot.index == 1 then midi.connect_virtual() else midi.connect(slot.index) end
    end
    self.connected, self.connecting = slot.connected == true, slot.connecting == true
    return slot
  end
  function device:send(data)
    local slot = selected_slot(self)
    if not slot then return false end
    if type(data) == "table" and data.type then data = midi.to_data(data) end
    return slot:send(data)
  end
  local methods = {
    "note_on", "note_off", "cc", "program_change", "pitchbend",
    "key_pressure", "channel_pressure", "start", "continue", "stop",
    "clock", "song_position", "song_select", "all_notes_off", "clock_receive"
  }
  for _, method in ipairs(methods) do
    local method_name = method
    device[method_name] = function(self, ...)
      local slot = selected_slot(self)
      if not slot or type(slot[method_name]) ~= "function" then return false end
      return slot[method_name](slot, ...)
    end
  end
  function device:close()
    local slot = self.port and midi.vports[self.port] or nil
    if not slot then return false end
    local result = slot:close()
    self.connected = false
    return result
  end
end

-- Norns expõe midi.devices como mapa indexado pelo ID do dispositivo.
-- Os objetos permanecem estáveis enquanto o mesmo ID estiver conectado.
function midi.refresh()
  local external = host.midi_devices() or {}
  local previous = midi.devices or {}
  local devices = {}
  local virtual = previous[0] or {id = 0, dev = 0, name = "virtual",
    inputs = 0, outputs = 1, type = "output", virtual = true, port = 1}
  virtual.id, virtual.dev, virtual.name, virtual.port = 0, 0, "virtual", 1
  virtual.virtual, virtual.type = true, "output"
  virtual.inputs, virtual.outputs = 0, 1
  install_midi_device_methods(virtual)
  devices[0] = virtual

  local seen = {}
  for i = 1, #external do
    local d = external[i]
    local name = d.name or ("MIDI " .. tostring(d.id or i))
    if d.id ~= 0 and name ~= "Norns Mobile MIDI Out"
        and ((d.inputs or 0) > 0 or (d.outputs or 0) > 0) then
      local direction = ((d.inputs or 0) > 0 and (d.outputs or 0) > 0) and "duplex"
        or ((d.outputs or 0) > 0 and "input" or "output")
      local info = {id = d.id or i, name = name, type = direction,
        inputs = d.inputs or 0, outputs = d.outputs or 0}
      local slot = midi_slot_for_id(info.id) or midi_slot_for_name(name)
      if slot and seen[slot.index] then slot = nil end
      if not slot then
        for n = 2, 16 do
          if midi.vports[n].name == "none" and not seen[n] then slot = midi.vports[n]; break end
        end
      end
      if slot then
        if slot.device and slot.device.id ~= info.id then
          slot.device:close()
          slot.device = nil
        end
        slot.id, slot.name, slot.type, slot.info = info.id, name, direction, info
        slot.inputs, slot.outputs = info.inputs, info.outputs
        slot.connected = slot.device ~= nil and slot.device.connected == true
        seen[slot.index] = true

        local device = previous[info.id] or {
          id = info.id, dev = info.id, event = nil, remove = nil, port = nil
        }
        device.id, device.dev, device.name = info.id, info.id, name
        device.type, device.inputs, device.outputs = direction, info.inputs, info.outputs
        device.port = slot.index
        device.connected, device.connecting = slot.connected, slot.connecting == true
        info.device = device
        install_midi_device_methods(device)
        devices[info.id] = device
      else
        host.log("MIDI: todos os 15 slots externos estão ocupados; ignorando " .. name, false)
      end
    end
  end
  midi.devices = devices
  for n = 2, 16 do
    local slot = midi.vports[n]
    if slot.info and not seen[n] then
      slot.info = nil
      slot.id, slot.type = nil, nil
      slot.inputs, slot.outputs = 0, 0
      slot.connected = false
      slot.connecting = false
      if slot.device then slot.device:close(); slot.device = nil end
    end
  end
  return midi.devices
end

local function send_midi_message(port, status, a, b)
  local bytes = {status, a == nil and 0 or a, b == nil and 0 or b}
  for i = 1, #bytes do
    local value = tonumber(bytes[i])
    if not value or value ~= value or value == math.huge or value == -math.huge
        or value ~= math.floor(value) or value < 0
        or (i == 1 and value > 255) or (i > 1 and value > 127) then
      return false
    end
    bytes[i] = value
  end
  if bytes[1] < 0x80 or not (port.connected or port.connecting) then return false end
  return host.midi_send(port.id, bytes[1], bytes[2], bytes[3]) ~= false
end

local function install_midi_port_methods(port, send, close_port)
  local function channel(ch)
    return math.max(1, math.min(16, math.floor(tonumber(ch) or 1))) - 1
  end
  function port:send(data)
    if type(data) ~= "table" or #data < 1 or #data > 65536 then return false end
    for i = 1, #data do
      local byte = tonumber(data[i])
      if not byte or byte ~= byte or byte == math.huge or byte == -math.huge
          or byte ~= math.floor(byte) or byte < 0 or byte > 255 then return false end
    end
    local status = data[1]
    if status < 0x80 then return false end
    if port.connected or port.connecting then
      return host.midi_send_bytes(port.id, data)
    end
    return false
  end
  function port:note_on(note, velocity, ch)
    send(0x90 + channel(ch), note, velocity == nil and 100 or velocity)
  end
  function port:note_off(note, velocity, ch)
    -- Norns defaults note-off velocity to 100.
    send(0x80 + channel(ch), note, velocity == nil and 100 or velocity)
  end
  function port:cc(cc, value, ch) send(0xB0 + channel(ch), cc, value) end
  function port:program_change(program, ch) send(0xC0 + channel(ch), program, 0) end
  function port:pitchbend(value, ch)
    value = math.max(0, math.min(16383, math.floor((tonumber(value) or 8192) + 0.5)))
    send(0xE0 + channel(ch), value % 128, math.floor(value / 128))
  end
  function port:key_pressure(note, value, ch) send(0xA0 + channel(ch), note, value) end
  function port:channel_pressure(value, ch) send(0xD0 + channel(ch), value, 0) end
  function port:start() send(0xFA, 0, 0) end
  function port:continue() send(0xFB, 0, 0) end
  function port:stop() send(0xFC, 0, 0) end
  function port:clock() send(0xF8, 0, 0) end
  function port:song_position(lsb, msb) send(0xF2, lsb or 0, msb or 0) end
  function port:song_select(value) send(0xF3, value or 0, 0) end
  function port:all_notes_off(ch) send(0xB0 + channel(ch), 123, 0) end
  function port:clock_receive(enabled)
    self._clock_receive_enabled = enabled ~= false and enabled ~= 0
  end
  function port:close()
    if close_port then close_port(self) end
  end
end

local vport_methods = {
  "send", "note_on", "note_off", "cc", "program_change", "pitchbend",
  "key_pressure", "channel_pressure", "start", "continue", "stop", "clock",
  "song_position", "song_select", "all_notes_off", "clock_receive"
}
for _, method in ipairs(vport_methods) do
  local method_name = method
  for i = 1, 16 do
    midi.vports[i][method_name] = function(self, ...)
      local device = self.device
      if not device or type(device[method_name]) ~= "function" then return false end
      return device[method_name](device, ...)
    end
  end
end
for i = 1, 16 do
  midi.vports[i].close = function(self)
    local mapped = midi.devices and midi.devices[self.id]
    if mapped then mapped.connected, mapped.connecting = false, false end
    if self.device and type(self.device.close) == "function" then self.device:close() end
    self.device, self.connected, self.connecting = nil, false, false
    return true
  end
end

function midi.connect_virtual()
  local slot = midi.vports[1]
  local existing = midi._ports[0]
  if existing and existing.connected then
    slot.device, slot.connected, slot.connecting = existing, true, false
    return slot
  end
  local port = {id = 0, name = "Norns Mobile MIDI Out", device = true,
    connected = true, virtual = true, event = slot.event, _clock_receive_enabled = false}
  midi._ports[0] = port
  local function send(status, a, b)
    return send_midi_message(port, status, a, b)
  end
  install_midi_port_methods(port, send, function(self)
    for ch = 1, 16 do send(0xB0 + ch - 1, 123, 0) end
    self.connected = false
    local mapped = midi.devices and midi.devices[0]
    if mapped then mapped.connected, mapped.connecting = false, false end
    if midi._ports[0] == self then midi._ports[0] = nil end
    slot.device, slot.connected, slot.connecting = nil, false, false
  end)
  slot.device, slot.connected, slot.connecting = port, true, false
  local mapped = midi.devices and midi.devices[0]
  if mapped then mapped.connected, mapped.connecting = true, false end
  return slot
end

function midi.connect(n)
  n = math.floor(tonumber(n) or 1)
  if n < 1 or n > 16 then return nil end
  local slot = midi.vports[n]
  if n == 1 then return midi.connect_virtual() end
  if not slot.info then midi.refresh() end
  local info = slot.info
  if not info then
    slot.connected, slot.connecting = false, false
    return slot
  end
  local device_id = tonumber(info.id)
  if not device_id then return slot end
  local existing = midi._ports[device_id]
  if existing and (existing.connected or existing.connecting) then
    slot.device, slot.connected, slot.connecting = existing,
      existing.connected == true, existing.connecting == true
    return slot
  end
  if existing then midi._ports[device_id] = nil end
  local port = {id = device_id, name = info.name, device = true,
    connected = false, connecting = false, event = slot.event,
    type = info.type, _clock_receive_enabled = true}
  port.connecting = host.midi_open(device_id)
  if port.connecting then
    midi._ports[device_id] = port
  else
    port.connection_error = "não foi possível iniciar a abertura MIDI"
    host.log("MIDI: não foi possível iniciar a abertura de " .. port.name, false)
  end
  local function send(status, a, b)
    return send_midi_message(port, status, a, b)
  end
  install_midi_port_methods(port, send, function(self)
    if self.id > 0 then host.midi_close(self.id) end
    self.connected, self.connecting = false, false
    local mapped = midi.devices and midi.devices[self.id]
    if mapped then mapped.connected, mapped.connecting = false, false end
    if midi._ports[self.id] == self then midi._ports[self.id] = nil end
    slot.device, slot.connected, slot.connecting = nil, false, false
    if type(midi.update_clock_receive) == "function" then midi.update_clock_receive() end
  end)
  slot.device, slot.connected, slot.connecting = port, false, port.connecting == true
  local mapped = midi.devices and midi.devices[device_id]
  if mapped then mapped.connected, mapped.connecting = false, port.connecting == true end
  if port.connecting and type(midi.update_clock_receive) == "function" then
    midi.update_clock_receive()
  end
  return slot
end

function midi.new(id, name, dev)
  id = math.floor(tonumber(id) or -1)
  local port = {id = id, name = tostring(name or ("MIDI " .. tostring(id))),
    device = dev, connected = dev ~= nil, connecting = false,
    event = nil, _clock_receive_enabled = true}
  local function send(status, a, b)
    return send_midi_message(port, status, a, b)
  end
  install_midi_port_methods(port, send, function(self)
    if self.id > 0 then host.midi_close(self.id) end
    self.connected = false
  end)
  return port
end

local function default_midi_port()
  if midi._ports[0] and midi._ports[0].connected then return midi._ports[0] end
  local ids = {}
  for id, port in pairs(midi._ports) do
    if port.connected then ids[#ids + 1] = id end
  end
  table.sort(ids)
  return ids[1] and midi._ports[ids[1]] or nil
end

-- Only one external input is allowed to drive the shared MIDI clock.
-- The virtual Norns output is intentionally excluded from clock reception.
-- Scripts/UI may pin an input by device ID; nil restores deterministic auto-select.
midi.clock_receive_device = nil
function midi.set_clock_receive_device(device_id)
  if device_id == nil or device_id == false then
    midi.clock_receive_device = nil
    midi.update_clock_receive()
    return true
  end
  local id = tonumber(device_id)
  local port = id and midi._ports[id] or nil
  if not port or id == 0 or not port.connected
      or (port.type ~= "input" and port.type ~= "duplex") then return false end
  midi.clock_receive_device = id
  midi.update_clock_receive()
  return true
end

function midi.update_clock_receive()
  local selected
  if clock and clock.get_source() == "midi" then
    local requested = tonumber(midi.clock_receive_device)
    local candidate = requested and requested ~= 0 and midi._ports[requested] or nil
    if candidate and candidate.connected
        and (candidate.type == "input" or candidate.type == "duplex") then
      selected = candidate
    else
      -- Keep the requested ID pinned for reconnection, but never leave clock
      -- reception without a source while another eligible input is available.
      local ids = {}
      for id, port in pairs(midi._ports) do
        if id ~= 0 and port.connected
            and (port.type == "input" or port.type == "duplex") then
          ids[#ids + 1] = id
        end
      end
      table.sort(ids)
      selected = ids[1] and midi._ports[ids[1]] or nil
    end
  end
  for _, port in pairs(midi._ports) do
    port:clock_receive(port == selected)
  end
  return selected ~= nil
end

function midi.send(data)
  local port = default_midi_port()
  return port and port:send(data) or false
end

local function midi_send_default(method, ...)
  local port = default_midi_port()
  if not port or type(port[method]) ~= "function" then return false end
  return port[method](port, ...)
end
midi.note_on = function(...) return midi_send_default("note_on", ...) end
midi.note_off = function(...) return midi_send_default("note_off", ...) end
midi.cc = function(...) return midi_send_default("cc", ...) end
midi.pitchbend = function(...) return midi_send_default("pitchbend", ...) end
midi.key_pressure = function(...) return midi_send_default("key_pressure", ...) end
midi.channel_pressure = function(...) return midi_send_default("channel_pressure", ...) end
midi.program_change = function(...) return midi_send_default("program_change", ...) end
midi.start = function(...) return midi_send_default("start", ...) end
midi.stop = function(...) return midi_send_default("stop", ...) end
midi.continue = function(...) return midi_send_default("continue", ...) end
midi.clock = function(...) return midi_send_default("clock", ...) end
midi.song_position = function(...) return midi_send_default("song_position", ...) end
midi.song_select = function(...) return midi_send_default("song_select", ...) end
midi.clock_receive = function(enabled)
  local port = default_midi_port()
  if not port then return false end
  return port:clock_receive(enabled)
end

function midi.cleanup()
  local pending = {}
  for _, port in pairs(midi._ports) do pending[#pending + 1] = port end
  for i = 1, #pending do pending[i]:close() end
  midi._ports = {}
  midi.event = nil
end

local function midi_finite_number(value, fallback)
  local number = tonumber(value)
  if not number or number ~= number or number == math.huge or number == -math.huge then
    return fallback
  end
  return number
end
local function midi_7bit(value, fallback)
  return math.max(0, math.min(127, math.floor(midi_finite_number(value, fallback) or 0)))
end
function midi.to_data(msg)
  if type(msg) ~= "table" then return {} end
  local t = msg.type
  local raw_channel = midi_finite_number(msg.ch, 1)
  local ch = math.max(1, math.min(16, math.floor(raw_channel))) - 1
  local status
  if t == "note_on" then status = 0x90 + ch
  elseif t == "note_off" then status = 0x80 + ch
  elseif t == "cc" then status = 0xB0 + ch
  elseif t == "program_change" then status = 0xC0 + ch
  elseif t == "pitchbend" then status = 0xE0 + ch
  elseif t == "key_pressure" then status = 0xA0 + ch
  elseif t == "channel_pressure" then status = 0xD0 + ch
  elseif t == "start" then status = 0xFA
  elseif t == "continue" then status = 0xFB
  elseif t == "stop" then status = 0xFC
  elseif t == "clock" then status = 0xF8
  elseif t == "song_position" then status = 0xF2
  elseif t == "song_select" then status = 0xF3
  else return {} end
  local a = midi_finite_number(msg.note or msg.cc or msg.program or msg.val, 0)
  local b = midi_finite_number(msg.vel or msg.val, t == "note_off" and 100 or 0)
  if t == "pitchbend" then
    local bend = math.max(0, math.min(16383, math.floor(midi_finite_number(msg.val, 8192))))
    return {status, bend % 128, math.floor(bend / 128)}
  elseif t == "song_position" then
    local position = midi_finite_number(msg.position or msg.val, nil)
    if position ~= nil then
      position = math.max(0, math.min(16383, math.floor(position)))
      return {0xF2, position % 128, math.floor(position / 128)}
    end
    local lsb = midi_7bit(msg.lsb, 0)
    local msb = midi_7bit(msg.msb, 0)
    return {0xF2, lsb, msb}
  elseif t == "song_select" then
    return {0xF3, midi_7bit(a, 0)}
  elseif status >= 0xF0 then return {status} end
  a, b = midi_7bit(a, 0), midi_7bit(b, t == "note_off" and 100 or 0)
  if status >= 0xC0 and status < 0xE0 then return {status, a} end
  return {status, a, b}
end
function midi.to_msg(data)
  if type(data) ~= "table" then return {type = "unknown"} end
  local status = tonumber(data[1] or data.status) or 0
  local a = tonumber(data[2] or data.note or data.cc) or 0
  local b = tonumber(data[3] or data.vel or data.val) or 0
  local kind = status >= 0xF0 and status or (status - (status % 16))
  local ch = status >= 0xF0 and 1 or ((status % 16) + 1)
  local result = {ch = ch, status = status}
  if kind == 0x90 and b > 0 then result.type, result.note, result.vel = "note_on", a, b
  elseif kind == 0x80 or (kind == 0x90 and b == 0) then result.type, result.note, result.vel = "note_off", a, b
  elseif kind == 0xB0 then result.type, result.cc, result.val = "cc", a, b
  elseif kind == 0xC0 then result.type, result.program = "program_change", a
  elseif kind == 0xD0 then result.type, result.val = "channel_pressure", a
  elseif kind == 0xA0 then result.type, result.note, result.val = "key_pressure", a, b
  elseif kind == 0xE0 then result.type, result.val = "pitchbend", a + b * 128
  elseif status == 0xFA then result.type = "start"
  elseif status == 0xFB then result.type = "continue"
  elseif status == 0xFC then result.type = "stop"
  elseif status == 0xF8 then result.type = "clock"
  elseif status == 0xF2 then result.type, result.position = "song_position", a + b * 128
  elseif status == 0xF3 then result.type, result.song = "song_select", a
  elseif status == 0xF0 then result.type, result.raw = "sysex", data
  elseif status == 0xFE then result.type = "active_sensing"
  else result.type, result.raw = "other", data end
  return result
end
midi.add = function() end
midi.remove = function() end
midi.refresh()

-- Grid/Arc usam serialosc via OSC/UDP na rede local; HID de saída continua indisponível.
local function monome_level(value)
  return math.max(0, math.min(15, math.floor(tonumber(value) or 0)))
end
local function monome_flag(value)
  return value == true or (type(value) == "number" and value ~= 0)
end
local function monome_send(device, path, args)

  if type(device) ~= "table" or not device.id then return false end
  return host.monome_send(device.id, path, args or {})
end
local function arc_led_index(radians)
  radians = tonumber(radians) or 0
  if radians ~= radians or radians == math.huge or radians == -math.huge then return 0 end
  local turn = radians % (2 * math.pi)
  return math.floor(turn / (2 * math.pi) * 64 + 0.5) % 64
end

local function make_vports(kind, count)
  local ports = {}
  for i = 1, count do
    local p = {name = "none", device = nil, available = false, connected = false}
    if kind == "grid" then
      p.cols, p.rows = 0, 0
      function p:led(x, y, level, rel)
        if not self.device then return false end
        x, y = math.floor(tonumber(x) or 0), math.floor(tonumber(y) or 0)
        if x < 1 or y < 1 or x > self.cols or y > self.rows then return false end
        local device = self.device
        device.grid_levels = device.grid_levels or {}
        device.grid_levels[y] = device.grid_levels[y] or {}
        local previous = device.grid_levels[y][x] or 0
        local target = monome_level(monome_flag(rel) and previous + (tonumber(level) or 0) or level)
        local ok = monome_send(device, "/grid/led/level/set", {x - 1, y - 1, target})
        if ok then device.grid_levels[y][x] = target end
        return ok
      end
      function p:all(level, rel)
        if not self.device then return false end
        if monome_flag(rel) then
          local ok = true
          for y = 1, self.rows do
            for x = 1, self.cols do ok = self:led(x, y, level, true) and ok end
          end
          return ok
        end
        local target = monome_level(level)
        local ok = monome_send(self.device, "/grid/led/level/all", {target})
        if ok then
          self.device.grid_levels = {}
          for y = 1, self.rows do
            self.device.grid_levels[y] = {}
            for x = 1, self.cols do self.device.grid_levels[y][x] = target end
          end
        end
        return ok
      end
      function p:refresh() return self.device ~= nil end
      function p:rotation(value)
        local degrees = math.max(0, math.min(270, math.floor((tonumber(value) or 0) / 90 + 0.5) * 90))
        return monome_send(self.device, "/sys/rotation", {degrees})
      end
      function p:intensity(value)
        return monome_send(self.device, "/grid/led/intensity", {monome_level(value)})
      end
      function p:tilt_enable(sensor, enabled)
        sensor = math.max(1, math.floor(tonumber(sensor) or 1))
        return monome_send(self.device, "/tilt/set", {sensor - 1, monome_flag(enabled) and 1 or 0})
      end
    elseif kind == "arc" then
      function p:led(ring, x, level, rel)
        if not self.device then return false end
        ring, x = math.floor(tonumber(ring) or 0), math.floor(tonumber(x) or 0)
        if ring < 1 or ring > (self.device.rings or 4) or x < 1 or x > 64 then return false end
        local device = self.device
        device.arc_levels = device.arc_levels or {}
        device.arc_levels[ring] = device.arc_levels[ring] or {}
        local previous = device.arc_levels[ring][x] or 0
        local target = monome_level(monome_flag(rel) and previous + (tonumber(level) or 0) or level)
        local ok = monome_send(device, "/ring/set", {ring - 1, x - 1, target})
        if ok then device.arc_levels[ring][x] = target end
        return ok
      end
      function p:all(level, rel)
        if not self.device then return false end
        local ok = true
        if monome_flag(rel) then
          for ring = 1, (self.device.rings or 4) do
            for x = 1, 64 do ok = self:led(ring, x, level, true) and ok end
          end
        else
          local target = monome_level(level)
          self.device.arc_levels = {}
          for ring = 1, (self.device.rings or 4) do
            ok = monome_send(self.device, "/ring/all", {ring - 1, target}) and ok
            self.device.arc_levels[ring] = {}
            for x = 1, 64 do self.device.arc_levels[ring][x] = target end
          end
        end
        return ok
      end
      function p:refresh() return self.device ~= nil end
      function p:segment(ring, from, to, level, rel)
        if not self.device then return false end
        ring = math.floor(tonumber(ring) or 0)
        if ring < 1 or ring > (self.device.rings or 4) then return false end
        local tau = math.pi * 2
        local step = tau / 64
        local function overlap(a, b, c, d)
          if a > b then return overlap(a, tau, c, d) + overlap(0, b, c, d) end
          if c > d then return overlap(a, b, c, tau) + overlap(a, b, 0, d) end
          return math.max(0, math.min(b, d) - math.max(a, c))
        end
        local function wrapped_overlap(a, b, c, d)
          return overlap(a % tau, b % tau, c % tau, d % tau)
        end
        from, to = tonumber(from) or 0, tonumber(to) or 0
        local ok = true
        for x = 1, 64 do
          local startAngle = step * (x - 1)
          local endAngle = step * x
          local amount = wrapped_overlap(from, to, startAngle, endAngle) / step
          local ledLevel = math.floor(amount * (tonumber(level) or 0) + 0.5)
          ok = self:led(ring, x, ledLevel, rel) and ok
        end
        return ok
      end
    elseif kind == "hid" then
      p.event = nil
    end
    ports[i] = p
  end
  return ports
end

local function safe_port(ports, n, kind)
  n = tonumber(n) or 1
  if n ~= math.floor(n) then return nil end
  -- Norns connect(n) returns the selected vport or nil for an invalid index.
  return ports[n]
end

grid = {
  devices = {},
  vports = make_vports("grid", 4),
  add = function(dev) print("grid added:", dev and dev.id, dev and dev.name) end,
  remove = noop,
  update_devices = function()
    local found = host.monome_devices() or {}
    local seen = {}
    for i = 1, #found do
      local d = found[i]
      if d.type == "grid" then seen[d.id] = true end
    end
    -- Free ports from removed devices before assigning newly discovered devices.
    local stale = {}
    for id in pairs(grid.devices) do if not seen[id] then stale[#stale + 1] = id end end
    for i = 1, #stale do _norns.grid_device_remove(stale[i]) end
    for i = 1, #found do
      local d = found[i]
      if d.type == "grid" then
        _norns.grid_device_add(d.id, d.serial, d.name, d.cols, d.rows)
        _norns.grid_device_info(d.id, d.cols, d.rows)
      end
    end
    host.monome_refresh()
    return true
  end,
  cleanup = function()
    for _, p in ipairs(grid.vports) do
      if p.device then p:all(0); p:refresh() end
      p.key, p.tilt, p.remove = nil, nil, nil
    end
    for _, device in pairs(grid.devices) do device.key, device.tilt = nil, nil end
    grid.add = function(dev) print("grid added:", dev and dev.id, dev and dev.name) end
    grid.remove = noop
  end,
  connect = function(n)
    grid.update_devices()
    return safe_port(grid.vports, n, "grid")
  end,
  rotation = function(value)
    local degrees = math.max(0, math.min(270, math.floor((tonumber(value) or 0) / 90 + 0.5) * 90))
    local ok = true
    for _, device in pairs(grid.devices) do
      ok = monome_send(device, "/sys/rotation", {degrees}) and ok
    end
    return ok
  end,
  tilt_enable = function(sensor, enabled)
    sensor = math.max(1, math.floor(tonumber(sensor) or 1))
    local ok = true
    for _, device in pairs(grid.devices) do
      ok = monome_send(device, "/tilt/set", {sensor - 1, monome_flag(enabled) and 1 or 0}) and ok
    end
    return ok
  end,
}
arc = {
  devices = {},
  vports = make_vports("arc", 4),
  add = function(dev) print("arc added:", dev and dev.id, dev and dev.name) end,
  remove = noop,
  update_devices = function()
    local found = host.monome_devices() or {}
    local seen = {}
    for i = 1, #found do
      local d = found[i]
      if d.type == "arc" then seen[d.id] = true end
    end
    -- Free ports from removed devices before assigning newly discovered devices.
    local stale = {}
    for id in pairs(arc.devices) do if not seen[id] then stale[#stale + 1] = id end end
    for i = 1, #stale do _norns.arc_device_remove(stale[i]) end
    for i = 1, #found do
      local d = found[i]
      if d.type == "arc" then
        _norns.arc_device_add(d.id, d.serial, d.name, d.rings)
        _norns.arc_device_info(d.id, d.rings)
      end
    end
    host.monome_refresh()
    return true
  end,
  cleanup = function()
    for _, p in ipairs(arc.vports) do
      if p.device then p:all(0); p:refresh() end
      p.delta, p.key, p.remove = nil, nil, nil
    end
    for _, device in pairs(arc.devices) do device.delta, device.key = nil, nil end
    arc.add = function(dev) print("arc added:", dev and dev.id, dev and dev.name) end
    arc.remove = noop
  end,
  connect = function(n)
    arc.update_devices()
    return safe_port(arc.vports, n, "arc")
  end,
}

_norns = _norns or {}
local function monome_assign_port(kind, collection, device, fullName)
  if device.port then return true end
  -- Honor a script's named port preference before assigning the first free slot.
  local selected
  for index, port in ipairs(collection.vports) do
    if not port.device and port.name == fullName then selected = index; break end
  end
  if not selected then
    for index, port in ipairs(collection.vports) do
      if not port.device and port.name == "none" then selected = index; break end
    end
  end
  -- A removed device leaves its preferred name on the vport. Reuse any
  -- remaining free slot only after exact-name and never-used slots are tried.
  if not selected then
    for index, port in ipairs(collection.vports) do
      if not port.device then selected = index; break end
    end
  end
  if not selected then return false end
  device.port = selected
  local port = collection.vports[selected]
  port.device, port.name, port.available, port.connected = device, fullName, true, true
  if kind == "grid" then port.cols, port.rows = device.cols, device.rows end
  if type(collection.add) == "function" then safe(collection.add, device) end
  return true
end

local function monome_attach(kind, id, serial, name, cols, rows, rings)
  local collection = kind == "arc" and arc or grid
  local fullName = tostring(name or (kind == "arc" and "Arc" or "Grid"))
      .. " " .. tostring(serial or "")
  local existing = collection.devices[id]
  if existing then
    existing.serial = serial or existing.serial
    existing.name = fullName
    if kind == "arc" then existing.rings = math.max(1, tonumber(rings) or existing.rings or 4)
    else existing.cols, existing.rows = tonumber(cols) or existing.cols, tonumber(rows) or existing.rows end
    monome_assign_port(kind, collection, existing, fullName)
    return existing
  end
  local device = {id = id, serial = serial, name = fullName,
    cols = tonumber(cols) or 0, rows = tonumber(rows) or 0,
    rings = math.max(1, tonumber(rings) or 4), port = nil,
    grid_levels = {}, arc_levels = {}}
  collection.devices[id] = device
  monome_assign_port(kind, collection, device, fullName)
  return device
end
function _norns.grid_device_add(id, serial, name, cols, rows)
  return monome_attach("grid", tonumber(id) or id, serial, name, cols, rows, 0)
end
function _norns.grid_device_info(id, cols, rows)
  local device = grid.devices[tonumber(id) or id]
  if not device then return false end
  device.cols, device.rows = tonumber(cols) or device.cols, tonumber(rows) or device.rows
  local port = device.port and grid.vports[device.port]
  if port then port.cols, port.rows = device.cols, device.rows end
  return true
end
function _norns.grid_device_remove(id)
  id = tonumber(id) or id
  local device = grid.devices[id]
  if not device then return false end
  if device.port then
    local port = grid.vports[device.port]
    if type(port.remove) == "function" then safe(port.remove) end
    port.device, port.available, port.connected = nil, false, false
    port.cols, port.rows = 0, 0
  end
  if type(grid.remove) == "function" then safe(grid.remove, device) end
  grid.devices[id] = nil
  return true
end
function _norns.arc_device_add(id, serial, name, rings)
  return monome_attach("arc", tonumber(id) or id, serial, name, 0, 0, rings)
end
function _norns.arc_device_info(id, rings)
  local device = arc.devices[tonumber(id) or id]
  if not device then return false end
  device.rings = math.max(1, tonumber(rings) or device.rings or 4)
  return true
end
function _norns.arc_device_remove(id)
  id = tonumber(id) or id
  local device = arc.devices[id]
  if not device then return false end
  if device.port then
    local port = arc.vports[device.port]
    if type(port.remove) == "function" then safe(port.remove) end
    port.device, port.available, port.connected = nil, false, false
  end
  if type(arc.remove) == "function" then safe(arc.remove, device) end
  arc.devices[id] = nil
  return true
end

hid = {
  devices = {}, vports = make_vports("hid", 4),
  add = function(dev) print("HID added:", dev and dev.id, dev and dev.name) end,
  remove = noop,
  types = {EV_SYN = 0, EV_KEY = 1, EV_REL = 2, EV_ABS = 3},
  codes = {
    KEY_ESC=1, KEY_1=2, KEY_2=3, KEY_3=4, KEY_4=5, KEY_5=6, KEY_6=7, KEY_7=8, KEY_8=9, KEY_9=10, KEY_0=11,
    KEY_MINUS=12, KEY_EQUAL=13, KEY_BACKSPACE=14, KEY_TAB=15, KEY_Q=16, KEY_W=17, KEY_E=18, KEY_R=19,
    KEY_T=20, KEY_Y=21, KEY_U=22, KEY_I=23, KEY_O=24, KEY_P=25, KEY_LEFTBRACE=26, KEY_RIGHTBRACE=27,
    KEY_ENTER=28, KEY_LEFTCTRL=29, KEY_A=30, KEY_S=31, KEY_D=32, KEY_F=33, KEY_G=34, KEY_H=35,
    KEY_J=36, KEY_K=37, KEY_L=38, KEY_SEMICOLON=39, KEY_APOSTROPHE=40, KEY_GRAVE=41, KEY_LEFTSHIFT=42,
    KEY_BACKSLASH=43, KEY_Z=44, KEY_X=45, KEY_C=46, KEY_V=47, KEY_B=48, KEY_N=49, KEY_M=50,
    KEY_COMMA=51, KEY_DOT=52, KEY_SLASH=53, KEY_RIGHTSHIFT=54, KEY_KPASTERISK=55, KEY_LEFTALT=56,
    KEY_SPACE=57, KEY_CAPSLOCK=58, KEY_F1=59, KEY_F2=60, KEY_F3=61, KEY_F4=62, KEY_F5=63,
    KEY_F6=64, KEY_F7=65, KEY_F8=66, KEY_F9=67, KEY_F10=68, KEY_NUMLOCK=69, KEY_SCROLLLOCK=70,
    KEY_KP7=71, KEY_KP8=72, KEY_KP9=73, KEY_KPMINUS=74, KEY_KP4=75, KEY_KP5=76, KEY_KP6=77,
    KEY_KPPLUS=78, KEY_KP1=79, KEY_KP2=80, KEY_KP3=81, KEY_KP0=82, KEY_KPDOT=83,
    KEY_F11=87, KEY_F12=88, KEY_KPENTER=96, KEY_RIGHTCTRL=97, KEY_KPSLASH=98, KEY_SYSRQ=99,
    KEY_RIGHTALT=100, KEY_HOME=102, KEY_UP=103, KEY_PAGEUP=104, KEY_LEFT=105, KEY_RIGHT=106,
    KEY_END=107, KEY_DOWN=108, KEY_PAGEDOWN=109, KEY_INSERT=110, KEY_DELETE=111, KEY_KPEQUAL=117,
    KEY_PAUSE=119, KEY_LEFTMETA=125, KEY_RIGHTMETA=126,
    BTN_1=0x101, BTN_2=0x102, BTN_3=0x103, BTN_4=0x104, BTN_5=0x105, BTN_6=0x106, BTN_7=0x107, BTN_8=0x108, BTN_9=0x109,
    BTN_10=0x2c0, BTN_11=0x2c1, BTN_12=0x2c2, BTN_13=0x2c3, BTN_14=0x2c4, BTN_15=0x2c5, BTN_16=0x2c6,
    BTN_A=0x130, BTN_B=0x131, BTN_X=0x133, BTN_Y=0x134, BTN_TL=0x136, BTN_TR=0x137,
    BTN_TL2=0x138, BTN_TR2=0x139, BTN_SELECT=0x13a, BTN_START=0x13b, BTN_MODE=0x13c,
    BTN_THUMBL=0x13d, BTN_THUMBR=0x13e, BTN_FORWARD=0x115, BTN_BACK=0x116,
    ABS_X=0, ABS_Y=1, ABS_Z=2, ABS_RX=3, ABS_RY=4, ABS_RZ=5, ABS_HAT0X=0x10, ABS_HAT0Y=0x11,
    REL_X=0, REL_Y=1, REL_WHEEL=8, REL_HWHEEL=6, BTN_LEFT=0x110, BTN_RIGHT=0x111, BTN_MIDDLE=0x112
  },
  update_devices = function()
    local ids = {}
    for id, device in pairs(hid.devices) do device.port = nil; ids[#ids + 1] = id end
    table.sort(ids)
    -- Preserve named port assignments first, then fill empty ports deterministically.
    for i, port in ipairs(hid.vports) do
      port.device = nil
      if port.name ~= "none" then
        for _, id in ipairs(ids) do
          local device = hid.devices[id]
          if device.name == port.name and not device.port then
            port.device, device.port = device, i
            break
          end
        end
        -- Keep the selected name while the device is unplugged so the same
        -- physical device can reclaim its vport when it reconnects.
      end
    end
    for i, port in ipairs(hid.vports) do
      if port.name == "none" then
        for _, id in ipairs(ids) do
          local device = hid.devices[id]
          if not device.port then
            port.device, port.name, device.port = device, device.name, i
            break
          end
        end
      end
      port.available = port.device ~= nil
      port.connected = port.available
    end
  end,
  cleanup = function()
    for _, p in ipairs(hid.vports) do p.event, p.remove = nil, nil end
    for _, d in pairs(hid.devices) do d.event = nil end
    hid.add = function(dev) print("HID added:", dev and dev.id, dev and dev.name) end
    hid.remove = noop
  end,
  connect = function(n) return safe_port(hid.vports, n, "hid") end,
}
local function hid_codes_for_type(prefix)
  local out = {}
  for name, code in pairs(hid.codes) do
    if string.sub(name, 1, #prefix) == prefix then out[code] = name end
  end
  return out
end
_norns = _norns or {}
function _norns.hid_device_add(id, name, vendor, product, ascii_keyboard, mouse, gamepad_device)
  id = tonumber(id) or -1
  if id < 0 then return nil end
  local base_name = tostring(name or "Android HID")
  local vendor_id, product_id = tonumber(vendor) or 0, tonumber(product) or 0
  local device = hid.devices[id]
  local added = device == nil
  if not device then
    device = {id = id, event = nil, remove = nil, port = nil,
      types = {hid.types.EV_KEY, hid.types.EV_REL, hid.types.EV_ABS},
      codes = {[hid.types.EV_KEY] = (function() local c = hid_codes_for_type("KEY_"); for k,v in pairs(hid_codes_for_type("BTN_")) do c[k] = v end; return c end)(),
        [hid.types.EV_REL] = hid_codes_for_type("REL_"), [hid.types.EV_ABS] = hid_codes_for_type("ABS_")}}
    hid.devices[id] = device
  end
  device.name = base_name .. " [" .. string.format("%04x:%04x", vendor_id, product_id) .. "]"
  device.vendor_id, device.product_id = vendor_id, product_id
  device.guid = string.format("%04x:%04x", vendor_id, product_id)
  device.is_ascii_keyboard = ascii_keyboard == true
  device.is_mouse = mouse == true
  device.is_gamepad = gamepad_device == true
  hid.update_devices()
  if added and type(hid.add) == "function" then safe(hid.add, device) end
  return device
end
function _norns.hid_device_remove(id)
  id = tonumber(id) or -1
  local device = hid.devices[id]
  if not device then return false end
  local port = device.port and hid.vports[device.port] or nil
  -- Detach and mark the old identity before user callbacks. A remove callback may
  -- synchronously replug the same Android ID; that replacement must survive.
  device.available = false
  hid.devices[id] = nil
  if type(hid.remove) == "function" then safe(hid.remove, device) end
  if type(device.remove) == "function" then safe(device.remove) end
  if port and type(port.remove) == "function" then safe(port.remove, port) end
  -- Recompute after callbacks so a reentrant same-ID add keeps its new port.
  -- The selected vport name and its Lua callbacks remain stable while absent.
  hid.update_devices()
  return true
end
hid.types.EV_SYN = hid.types.EV_SYN
hid.types.EV_KEY = hid.types.EV_KEY
hid.types.EV_REL = hid.types.EV_REL
hid.types.EV_ABS = hid.types.EV_ABS

_norns = _norns or {}
_norns.grid = {
  key = function(id, x, y, z)
    local d = grid.devices[id]
    if d and type(d.key) == "function" then safe(d.key, x, y, z) end
    local p = d and d.port and grid.vports[d.port]
    if p and type(p.key) == "function" then safe(p.key, x, y, z) end
  end,
  tilt = function(id, x, y, z)
    local d = grid.devices[id]
    if d and type(d.tilt) == "function" then safe(d.tilt, x, y, z) end
    local p = d and d.port and grid.vports[d.port]
    if p and type(p.tilt) == "function" then safe(p.tilt, x, y, z) end
  end,
}
_norns.grid_key = function(id, x, y, z) return _norns.grid.key(id, x, y, z) end
_norns.grid_tilt = function(id, x, y, z) return _norns.grid.tilt(id, x, y, z) end
_norns.arc = {
  delta = function(id, n, delta)
    local d = arc.devices[id]
    if d and type(d.delta) == "function" then safe(d.delta, n, delta) end
    local p = d and d.port and arc.vports[d.port]
    if p and type(p.delta) == "function" then safe(p.delta, n, delta) end
  end,
  key = function(id, n, z)
    local d = arc.devices[id]
    if d and type(d.key) == "function" then safe(d.key, n, z) end
    local p = d and d.port and arc.vports[d.port]
    if p and type(p.key) == "function" then safe(p.key, n, z) end
  end,
}
_norns.arc_delta = function(id, n, delta) return _norns.arc.delta(id, n, delta) end
_norns.arc_key = function(id, n, state) return _norns.arc.key(id, n, state) end
local function hid_integer(value, minimum, maximum)
  local number = tonumber(value)
  if not number or number ~= number or number == math.huge or number == -math.huge
      or number ~= math.floor(number) or number < minimum or number > maximum then
    return nil
  end
  return number
end
_norns.hid = {
  event = function(id, event_type, code, value)
    id = hid_integer(id, 0, 2147483647)
    event_type = hid_integer(event_type, hid.types.EV_KEY, hid.types.EV_ABS)
    code = hid_integer(code, 0, 65535)
    value = hid_integer(value, -2147483648, 2147483647)
    if id == nil or event_type == nil or code == nil or value == nil then return false end
    local d = hid.devices[id]
    if not d or not d.codes or not d.codes[event_type] or d.codes[event_type][code] == nil then
      return false
    end
    if type(d.event) == "function" then safe(d.event, event_type, code, value) end
    -- Device callbacks may synchronously remove or replace this HID device.
    -- Do not forward the in-flight event through a vport after that transition.
    if hid.devices[id] ~= d then return true end
    local p = d.port and hid.vports[d.port]
    if p and p.device == d and type(p.event) == "function" then
      safe(p.event, event_type, code, value)
    end
    return true
  end,
}

function _norns.hid_event(id, event_type, code, value)
  return _norns.hid.event(id, event_type, code, value)
end

-- Crow: USB CDC-ACM serial bridge using the official crow text command/event protocol.
local crowDevice = nil
local crowEventBuffer = ""
local crowEventHandlers = {}
local crowEventCount = 0

local function crow_send(command)
  if not crowDevice or type(command) ~= "string" then return false end
  local payload = command
  if string.sub(payload, -1) ~= "\n" then payload = payload .. "\n" end
  return serial.send(crowDevice, payload)
end

local function crow_register_event(fn)
  if type(fn) ~= "function" then return nil end
  if crowEventCount >= 52 then
    host.log("crow: limite de 52 callbacks de evento atingido.", true)
    return nil
  end
  crowEventCount = crowEventCount + 1
  local code = crowEventCount <= 26
    and string.char(crowEventCount + 64)
    or string.char(crowEventCount + 70)
  crowEventHandlers[code] = fn
  if norns.crow and type(norns.crow.events) == "table" then
    norns.crow.events[code] = fn
  end
  return code
end

local function crow_quote(value, seen)
  local kind = type(value)
  if kind == "string" then return string.format("%q", value) end
  if kind == "number" then
    if value ~= value or value == math.huge or value == -math.huge then return "0" end
    return string.format("%.12g", value)
  end
  if kind == "boolean" then return value and "true" or "false" end
  if kind == "nil" then return "nil" end
  if kind ~= "table" then return "nil" end
  seen = seen or {}
  if seen[value] then return "{}" end
  seen[value] = true
  local parts = {}
  for key, item in pairs(value) do
    local encodedKey = type(key) == "number" and ("[" .. tostring(key) .. "]")
      or ("[" .. crow_quote(tostring(key)) .. "]")
    parts[#parts + 1] = encodedKey .. "=" .. crow_quote(item, seen)
  end
  seen[value] = nil
  return "{" .. table.concat(parts, ",") .. "}"
end

local function crow_value_code(value)
  if type(value) ~= "function" then return crow_quote(value) end
  local code = crow_register_event(value)
  if not code then return "nil" end
  return "function(...) _c.tell('" .. code .. "', quote(...)) end"
end

local function crow_proxy(path)
  return setmetatable({__crow_path = path}, {
    __index = function(_, key)
      local suffix = type(key) == "number" and ("[" .. tostring(key) .. "]")
        or ("." .. tostring(key))
      return crow_proxy(path .. suffix)
    end,
    __newindex = function(_, key, value)
      local suffix = type(key) == "number" and ("[" .. tostring(key) .. "]")
        or ("." .. tostring(key))
      crow_send(path .. suffix .. " = " .. crow_value_code(value))
    end,
    __call = function(_, ...)
      local args = {}
      for i = 1, select("#", ...) do args[#args + 1] = crow_quote(select(i, ...)) end
      crow_send(path .. "(" .. table.concat(args, ",") .. ")")
    end,
  })
end

norns.crow = {
  dev = nil,
  events = {},
  add = function(id, name, dev)
    host.log("crow conectado: " .. tostring(name) .. " (" .. tostring(id) .. ")", false)
  end,
  remove = function(id) host.log("crow desconectado: " .. tostring(id), false) end,
  receive = function(line) print("crow:", line) end,
  send = crow_send,
  connected = function() return crowDevice ~= nil end,
  register_event = crow_register_event,
}
-- Compatibilidade com core/crow/public.lua do norns: mantém estado, callbacks
-- e descoberta das variáveis públicas anunciadas pelo firmware.
norns.crow.public = {
  io = crow_proxy("public"),
  _names = {}, _params = {},
  viewing = {input = {}, output = {}},
  change = function() end,
  discovered = function() print("crow.public discovered") end,
}
function norns.crow.public.clear()
  local public = norns.crow.public
  public._names, public._params = {}, {}
  public.viewing = {input = {}, output = {}}
  if type(public.change) == "function" then safe(public.change) end
end
function norns.crow.public.reset()
  local public = norns.crow.public
  public.change = function() end
  public.discovered = function() print("crow.public discovered") end
  public.clear()
end
function norns.crow.public.discover()
  crow_send("public.discover()")
end
function norns.crow.public.ready()
  norns.crow.public.clear()
  norns.crow.public.discover()
end
function norns.crow.public.add(name, value, kind)
  local public = norns.crow.public
  if name == "_end" then
    if type(public.discovered) == "function" then safe(public.discovered) end
    return
  elseif name == "_clear" then
    public.clear()
    return
  end
  name = tostring(name or "")
  if name == "" then return end
  local index = public._names[name]
  if not index then
    index = #public._params + 1
    public._names[name] = index
  end
  local entry = {name = name, val = value, list = type(value) == "table",
    listix = 1, listlen = type(value) == "table" and #value or 0}
  public.capture_type(entry, kind)
  public._params[index] = entry
end
function norns.crow.public.update(name, value, sub)
  local public = norns.crow.public
  local index = public._names[name]
  if not index then return false end
  local entry = public._params[index]
  if sub and type(entry.val) == "table" then entry.val[sub] = value
  else entry.val = value end
  if type(public.change) == "function" then safe(public.change, name, entry.val) end
  return true
end
function norns.crow.public.view(direction, channel, value)
  local public = norns.crow.public
  if direction ~= "input" and direction ~= "output" then return false end
  public.viewing[direction][channel] = value
  if type(public.change) == "function" then safe(public.change) end
  return true
end
function norns.crow.public.get_count()
  return #norns.crow.public._params
end
function norns.crow.public.get_index(index)
  return norns.crow.public._params[tonumber(index) or -1]
end
function norns.crow.public.lookup(name)
  local public = norns.crow.public
  local index = public._names[name]
  return index and public._params[index] or nil
end
function norns.crow.public.inferint(entry)
  local function isFractional(value)
    return type(value) == "number" and value ~= math.floor(value)
  end
  if entry.list then
    for i = 1, entry.listlen or 0 do
      if isFractional(entry.val[i]) then return false end
    end
  elseif isFractional(entry.val) then
    return false
  end
  if isFractional(entry.min) or isFractional(entry.max) then return false end
  return true
end
function norns.crow.public.capture_type(entry, kind)
  kind = type(kind) == "table" and kind or {}
  local count = #kind
  if type(kind[1]) == "string" then
    if count == 1 then
      entry.type = kind[1]
    else
      entry.type, entry.option = "option", {}
      for i = 2, count do entry.option[#entry.option + 1] = kind[i] end
      return
    end
  else
    if count >= 2 then
      entry.min, entry.max = kind[1], kind[2]
      if type(entry.min) == "number" and type(entry.max) == "number" then
        entry.range = entry.max - entry.min
      end
    end
    entry.type = kind[3]
  end
  if type(entry.type) == "string" and string.sub(entry.type, 1, 1) == "@" then
    entry.readonly = true
    entry.type = string.sub(entry.type, 2)
  end
  if type(entry.type) ~= "string" or entry.type == "" then
    entry.type = norns.crow.public.inferint(entry) and "int" or "float"
  elseif entry.type == "exp" then
    entry.exp, entry.type = true, "float"
  elseif entry.type == "xslider" then
    entry.exp, entry.type = true, "slider"
  end
end
function norns.crow.public.increment(value, delta, entry)
  if entry.exp then
    delta = delta * math.abs(value) / 100
  elseif entry.type == "float" or entry.type == "slider" then
    delta = entry.range and delta * (entry.range / 100) or delta / 50
  end
  return value + delta
end
function norns.crow.public.delta(index, delta, alternate)
  local public = norns.crow.public
  local entry = public._params[tonumber(index) or -1]
  if not entry or entry.readonly then return false end
  delta = tonumber(delta) or 0
  local value
  if entry.list then
    if entry.listlen < 1 then return false end
    if alternate then
      entry.listix = util.wrap((entry.listix or 1) + delta, 1, entry.listlen)
      return true
    end
    entry.val[entry.listix or 1] = public.increment(entry.val[entry.listix or 1], delta, entry)
    value = entry.val
  elseif entry.type == "option" then
    if not entry.option or #entry.option == 0 then return false end
    local selected = 1
    for i, option in ipairs(entry.option or {}) do
      if option == entry.val then selected = i; break end
    end
    value = entry.option[util.wrap(selected + delta, 1, #entry.option)]
  elseif type(entry.val) == "number" then
    value = public.increment(entry.val, delta, entry)
  else
    return false
  end
  public.io[entry.name] = value
  return true
end
local publicIo = {
  view = crow_proxy("public.view"),
  discover = function() crow_send("public.discover()") end,
}
setmetatable(publicIo, {
  __index = function(_, name)
    local entry = norns.crow.public.lookup(name)
    if entry then return entry.val end
    return nil
  end,
  __newindex = function(_, name, value)
    local public = norns.crow.public
    local entry = public.lookup(name)
    if not entry then
      host.log("crow.public." .. tostring(name) .. " ainda não foi declarado no script do crow.", false)
      return
    end
    if entry.range then
      if entry.list and type(value) == "table" then
        for i, item in ipairs(value) do value[i] = util.clamp(item, entry.min, entry.max) end
      elseif type(value) == "number" then
        value = util.clamp(value, entry.min, entry.max)
      end
    end
    entry.val = value
    crow_send("public.update(" .. crow_quote(entry.name) .. "," .. crow_quote(value) .. ")")
  end,
})
norns.crow.public.io = publicIo
function norns.crow.reset_events()
  norns.crow.events = {
    identity = function(value) print("crow identity:", value) end,
    version = function(value) print("crow version:", value) end,
    ready = norns.crow.public.ready,
    pub = norns.crow.public.add,
    pupdate = norns.crow.public.update,
    pubview = norns.crow.public.view,
    change = function() end,
  }
  for code, fn in pairs(crowEventHandlers) do norns.crow.events[code] = fn end
end
norns.crow.reset_events()
norns.crow.public.reset()

local function crow_decode_string(token)
  local quote = string.sub(token, 1, 1)
  if #token < 2 or string.sub(token, -1) ~= quote
      or (quote ~= "'" and quote ~= '"') then return token end
  local out, i = {}, 2
  while i < #token do
    local ch = string.sub(token, i, i)
    if ch == "\\" then
      i = i + 1
      if i >= #token then return token end
      local escaped = string.sub(token, i, i)
      if string.match(escaped, "%d") then
        -- Lua decimal escapes consume up to three digits (e.g. \012 is LF).
        local digits = string.match(string.sub(token, i), "^(%d%d?%d?)")
        local value = tonumber(digits)
        if not value or value > 255 then return token end
        out[#out + 1] = string.char(value)
        i = i + #digits - 1
      elseif escaped == "x" then
        local hex = string.sub(token, i + 1, i + 2)
        if #hex ~= 2 or not string.match(hex, "^[%da-fA-F][%da-fA-F]$") then return token end
        out[#out + 1] = string.char(tonumber(hex, 16))
        i = i + 2
      elseif escaped == "z" then
        while i < #token and string.match(string.sub(token, i + 1, i + 1), "%s") do
          i = i + 1
        end
      else
        local mapped = ({a="\a",b="\b",f="\f",n="\n",r="\r",t="\t",v="\v",
          ["\\"]="\\",['"']='"',["'"]="'",["\n"]="\n",["\r"]="\n"})[escaped]
        if mapped ~= nil then
          out[#out + 1] = mapped
        else
          -- Keep unknown escapes literal; never evaluate firmware text as Lua.
          out[#out + 1] = "\\" .. escaped
        end
      end
    else
      out[#out + 1] = ch
    end
    i = i + 1
  end
  return table.concat(out)
end

local function crow_parse_args(source)
  local result = {n = 0}
  local i, startAt, quoteChar, escaped, depth = 1, 1, nil, false, 0
  while i <= #source + 1 do
    local ch = string.sub(source, i, i)
    if quoteChar then
      if escaped then escaped = false
      elseif ch == "\\" then escaped = true
      elseif ch == quoteChar then quoteChar = nil end
    elseif ch == "'" or ch == '"' then quoteChar = ch
    elseif ch == "{" then depth = depth + 1
    elseif ch == "}" then depth = math.max(0, depth - 1)
    elseif (ch == "," and depth == 0) or i == #source + 1 then
      local token = string.gsub(string.sub(source, startAt, i - 1), "^%s*(.-)%s*$", "%1")
      if token ~= "" then
        local value = tonumber(token)
        if value == nil then
          if token == "true" then value = true
          elseif token == "false" then value = false
          elseif token == "nil" then value = nil
          elseif (string.sub(token,1,1) == "'" and string.sub(token,-1) == "'")
              or (string.sub(token,1,1) == '"' and string.sub(token,-1) == '"') then
            value = crow_decode_string(token)
          else
            value = token
          end
        end
        result.n = result.n + 1
        result[result.n] = value
      end
      startAt = i + 1
    end
    i = i + 1
  end
  return result
end

local function crow_process_line(line)
  line = string.gsub(line, "\r", "")
  if string.sub(line, 1, 2) == ">>" then
    local event, args = string.match(line, "^%s*%>%>([%w_]+)%s*%((.*)%)%s*$")
    if not event then event = string.match(line, "^%s*%>%>([%w_]+)%s*$"); args = "" end
    if event then
      local callback = norns.crow.events[event]
      if type(callback) == "function" then
        local values = crow_parse_args(args or "")
        safe(callback, (table.unpack or unpack)(values, 1, values.n))
      else
        host.log("crow evento não tratado: " .. tostring(event), false)
      end
    else
      norns.crow.receive(line)
    end
  elseif line ~= "" then
    norns.crow.receive(line)
  end
end

local crowEventDroppingOversizeLine = false
local function crow_receive(data)
  local incoming = tostring(data or "")
  if crowEventDroppingOversizeLine then
    local _, finish = string.find(incoming, "[\r\n]")
    if not finish then return end
    while string.sub(incoming, finish + 1, finish + 1) == "\r"
        or string.sub(incoming, finish + 1, finish + 1) == "\n" do finish = finish + 1 end
    incoming = string.sub(incoming, finish + 1)
    crowEventDroppingOversizeLine = false
  end
  crowEventBuffer = crowEventBuffer .. incoming
  while true do
    local n = string.find(crowEventBuffer, "[\r\n]")
    if not n then
      if #crowEventBuffer > 8192 then
        crowEventBuffer = ""
        crowEventDroppingOversizeLine = true
        host.log("crow: linha serial excedeu 8 KiB; descartada", false)
      end
      break
    end
    local line = string.sub(crowEventBuffer, 1, n - 1)
    local nextAt = n + 1
    while string.sub(crowEventBuffer, nextAt, nextAt) == "\r"
        or string.sub(crowEventBuffer, nextAt, nextAt) == "\n" do nextAt = nextAt + 1 end
    crowEventBuffer = string.sub(crowEventBuffer, nextAt)
    if #line <= 8192 then
      crow_process_line(line)
    else
      host.log("crow: linha serial excedeu 8 KiB; descartada", false)
    end
  end
end

norns.crow.event = function(id, line) crow_receive(line) end

local crow = {}
crow.output = crow_proxy("output")
crow.input = crow_proxy("input")
crow.ii = crow_proxy("ii")
crow.public = norns.crow.public.io
crow.send = crow_send
crow.connected = norns.crow.connected
crow.version = function() crow_send("^^v") end
crow.identity = function() crow_send("^^i") end
crow.reset = function() crow_send("crow.reset()") end
crow.clear = function() crow_send("^^c") end
crow.kill = function() crow_send("^^k") end
crow.init = function()
  crow_send("crow.reset()")
  crowEventBuffer = ""
  crowEventDroppingOversizeLine = false
  crowEventHandlers = {}
  crowEventCount = 0
  norns.crow.reset_events()
  norns.crow.public.reset()
  return true
end
crow.clock_enable = function()
  crow_send("input[1].change = function() tell('change',1,1) end")
  crow_send("input[1].mode('change',2,0.1,'rising')")
end
setmetatable(crow, {
  __index = function(_, key) return crow_proxy(tostring(key)) end,
  __newindex = function(_, key, value)
    crow_send(tostring(key) .. " = " .. crow_value_code(value))
  end,
  __call = function(_, command) return crow_send(tostring(command)) end,
})
crow.connect = function() return crow end
crow.add = function(...) return norns.crow.add(...) end
crow.remove = function(...) return norns.crow.remove(...) end
crow.receive = function(...) return norns.crow.receive(...) end
norns.crow.init = crow.init
norns.crow.clock_enable = crow.clock_enable

function norns.crow.findscript(file)
  local relative = tostring(file or "")
  local candidates = {
    norns.state.path .. "crow/" .. relative,
    _path.code .. relative,
  }
  for i = 1, #candidates do
    if host.read_file(candidates[i]) then return candidates[i] end
  end
  return nil
end

function norns.crow.loadscript(file, is_persistent, continuation)
  local path = norns.crow.findscript(file)
  if not path then
    print("crow.loadscript: arquivo não encontrado: " .. tostring(file))
    return false
  end
  local source = host.read_file(path)
  if not source then return false end
  if #source > 16384 then
    print("crow.loadscript: script excede o limite de 16 KiB.")
    return false
  end
  if not crowDevice then
    print("crow.loadscript: Crow não está conectado.")
    return false
  end
  local function upload()
    crow_send("^^s")
    clock.sleep(0.2)
    for line in string.gmatch(source .. "\n", "([^\r\n]*)[\r\n]+") do
      if line ~= "" then crow_send(line); clock.sleep(0.01) end
    end
    clock.sleep(0.2)
    crow_send(is_persistent and "^^w" or "^^e")
    if type(continuation) == "function" then
      clock.sleep(is_persistent and 0.5 or 0.2)
      safe(continuation)
    end
  end
  return clock.run(upload)
end

-- OSC 1.0 bridge. Norns signature: osc.send({host, port}, path, {args}).
osc = {
  event = nil,
  send = function(to, path, args, legacy_args)
    local hostname, port, payload
    if type(to) == "table" then
      hostname, port, payload = to[1], to[2], args or {}
    else
      -- Keep the earlier mobile signature working for existing user scripts.
      hostname, port, payload = to, path, legacy_args or {}
      path = args
    end
    if type(hostname) ~= "string" or type(path) ~= "string" then return false end
    return host.osc_send(hostname, tonumber(port) or 0, path, payload)
  end,
  listen = function(port) return host.osc_listen(tonumber(port) or 10111) end,
  close = function() host.osc_close() end,
  cleanup = function() host.osc_close() end,
  send_raw = function(to, path, args)
    return osc.send(to, path, args)
  end,
  -- Explicit OSC extended types. Ordinary Lua fractional numbers remain float32 for Norns compatibility.
  int64 = function(value) return {__osc_type = "int64", value = value} end,
  float64 = function(value) return {__osc_type = "float64", value = value} end,
  blob = function(bytes) return {__osc_type = "blob", value = bytes} end,
  send_bundle = function(to, messages, timetag_ms)
    if type(to) ~= "table" or type(messages) ~= "table" then return false end
    local hostname, port = to[1], tonumber(to[2]) or 0
    if type(hostname) ~= "string" then return false end
    return host.osc_send_bundle(hostname, port, messages, tonumber(timetag_ms) or 0)
  end,
}

-- Preserve the norns OSC core behavior: user callback first, then built-in
-- parameter and remote-control routes.
function _norns.osc_event(path, args, from)
  if type(osc.event) == "function" then safe(osc.event, path, args, from) end
  if type(path) ~= "string" or type(args) ~= "table" then return false end

  local parts = {}
  for part in string.gmatch(path, "[^/]+") do parts[#parts + 1] = part end
  if parts[1] == "param" and (#parts == 2 or #parts == 3) then
    local set_id, param_id
    if #parts == 2 then set_id, param_id = "", parts[2]
    else set_id, param_id = parts[2], parts[3] end
    if args[1] == nil then return false end
    local set = paramset.sets and paramset.sets[set_id]
    if not set then return false end
    local ok, param = pcall(function() return set:lookup_param(param_id) end)
    if not ok or not param or param.id ~= param_id then return false end
    return safe(function() param:set(args[1]) end)
  elseif string.sub(path, 1, 11) == "/remote/key"
      or string.sub(path, 1, 11) == "/remote/enc"
      or string.sub(path, 1, 11) == "/remote/brd" then
    -- Norns accepts both /remote/key with {n,value} and /remote/key/n with {value}.
    local n, value
    if #args > 1 then n, value = tonumber(args[1]), tonumber(args[2])
    else n, value = tonumber(string.sub(path, 13)), tonumber(args[1]) end
    -- Reject malformed remote controls before math.floor or callback dispatch.
    -- OSC can carry NaN/Inf floats; these must not reach Lua input handlers.
    if not n or n ~= n or n == math.huge or n == -math.huge
        or n ~= math.floor(n) or value == nil or value ~= value
        or value == math.huge or value == -math.huge then return false end
    local command = string.sub(path, 1, 11)
    if command == "/remote/key" or command == "/remote/enc" then
      if n < 1 or n > 3 then return false end
      if command == "/remote/key" and value ~= 0 and value ~= 1 then return false end
    elseif command == "/remote/brd" then
      if n < 1 or n > 767 or (value ~= 0 and value ~= 1) then return false end
    else
      return false
    end
    if command == "/remote/key" and type(_norns.key) == "function" then
      _norns.key(n, value)
      return true
    elseif command == "/remote/enc" and type(_norns.enc) == "function" then
      _norns.enc(n, value)
      return true
    elseif command == "/remote/brd" and keyboard
        and type(keyboard.process) == "function" then
      return keyboard.process(1, n, value)
    end
  end
  return false
end

-- norns listens on UDP 10111 unless a script explicitly selects another port.
osc.listen(10111)
softcut = {
  VOICE_COUNT = 6,
  BUFFER_SIZE = 16777216 / 48000,
}
local softcut_native_command
local function sc(voice, command, value)
  voice = math.max(1, math.min(6, math.floor(tonumber(voice) or 1)))
  value = tonumber(value) or 0
  local result = host.softcut(voice, command, value)
  if softcut_native_command then softcut_native_command(voice, command, value) end
  return result
end
-- Em Lua, o número 0 também é truthy; a API aceita tanto booleanos quanto 0/1.
local function sc_flag(value)
  return (value == true or (type(value) == "number" and value ~= 0)) and 1 or 0
end

-- Softcut nativo: seis nodes persistentes sobre dois buffers compartilhados.
-- Nesta fase os nodes são criados em modo silencioso (play=0); o motor Java
-- continua sendo a única saída audível até a transferência completa dos buffers
-- e a validação do roteamento nativo.
softcut._native_state = "java"
softcut._native_output_validated = false
softcut._native_output_owner = false
softcut._native_output_uncertain = false
-- Bus 31 is reserved by SC_AndroidAudio for the previous block engine tap.
softcut._engine_cut_level = 0
softcut._engine_cut_source = "java"
softcut._tape_cut_level = 0
softcut._tape_record_active = false
softcut._native_generation = 0
softcut._native_nodes = {}
softcut._native_recorders = {}
softcut._native_record_owner = {}
softcut._native_recording = {}
softcut._native_buffers = {20001, 20002}
softcut._native_buffer_frames = 1
softcut._native_params = {}
for voice = 1, softcut.VOICE_COUNT do
  softcut._native_params[voice] = {
    buf = softcut._native_buffers[(voice % 2) + 1],
    buffer_channel = (voice % 2) + 1,
    rate = 1, loop_start = (voice - 1) * 2,
    loop_end = (voice - 1) * 2 + 1, position = 0,
    play = 0, phase_play = 0, phase_enable = 1,
    enable = 0, fade_time = 0.0005, loop = 1, level = 0, pan = 0,
    rate_slew_time = 0.001, level_slew_time = 0.001,
    pan_slew_time = 0.001, rec = 0, rec_level = 0,
    pre_level = 1, input_level = 1, recpre_slew_time = 0.001,
    rec_offset = -0.00015,
    pre_filter_fc = 16000, pre_filter_fc_mod = 1, pre_filter_rq = 2,
    pre_filter_lp = 1, pre_filter_hp = 0, pre_filter_bp = 0, pre_filter_br = 0, pre_filter_dry = 0,
    post_filter_fc = 12000, post_filter_fc_mod = 0, post_filter_rq = 2,
    post_filter_lp = 0, post_filter_hp = 0, post_filter_bp = 0, post_filter_br = 0, post_filter_dry = 0
  }
end
softcut._native_cut_matrix = {}
for src = 1, softcut.VOICE_COUNT do
  softcut._native_cut_matrix[src] = {}
  for dst = 1, softcut.VOICE_COUNT do softcut._native_cut_matrix[src][dst] = 0 end
end
softcut._native_recorder_monitor_owner = {}
softcut._native_recorder_windows = {}
softcut._native_phase_diagnostics = {}
softcut._native_routing_diagnostics = nil
softcut._native_supervisor = {
  running = false, fresh = false, stale = true, cycles = 0,
  stale_cycles = 0, corrections = 0, voices = {}, recorders = {}
}
softcut._native_adc_level = 1
-- Envio global do engine Java para os seis gravadores Softcut Java.
softcut._engine_cut_level = 0

local softcut_native_supervisor_tick

local function softcut_native_phase_reanchor(voice, position)
  local node = softcut._native_nodes[voice]
  if not node or type(position) ~= "number"
      or position ~= position or position == math.huge or position == -math.huge then
    return false
  end
  -- Re-anchor only the muted phase witness. Never touch the recorder or audio gate.
  local ok, result = pcall(sc_api.set, node, {position = position})
  return ok and result ~= false
end

local function softcut_native_supervisor_mark_stale(reason)
  local supervisor = softcut._native_supervisor
  supervisor.fresh = false
  supervisor.stale = true
  supervisor.last_error = reason
  supervisor.stale_cycles = (supervisor.stale_cycles or 0) + 1
  for voice = 1, softcut.VOICE_COUNT do
    local state = supervisor.voices[voice] or {}
    state.fresh = false
    state.stale = true
    supervisor.voices[voice] = state
  end
  for channel = 1, 2 do
    local state = supervisor.recorders[channel] or {}
    state.fresh = false
    state.stale = true
    state.recovery_blocked = true
    supervisor.recorders[channel] = state
  end
end

local function softcut_native_supervisor_start(generation)
  local supervisor = {
    running = true, fresh = false, stale = true, cycles = 0,
    stale_cycles = 0, corrections = 0, voices = {}, recorders = {},
    recorder_recoveries_queued = 0,
    started_at = host.now(), period_seconds = 0.033,
    stale_after_seconds = 0.18, last_error = nil
  }
  for voice = 1, softcut.VOICE_COUNT do
    supervisor.voices[voice] = {
      fresh = false, stale = true, samples = 0,
      corrections = 0, last_correction_at = -math.huge
    }
  end
  for channel = 1, 2 do
    supervisor.recorders[channel] = {
      fresh = false, stale = true, recording = false,
      samples = 0, recovery_pending = false, recoveries_queued = 0,
      last_reanchor_attempt_at = -math.huge
    }
  end
  softcut._native_phase_diagnostics = {}
  softcut._native_routing_diagnostics = nil
  softcut._native_supervisor = supervisor
  sched_add(host.now() + 0.033, function()
    softcut_native_supervisor_tick(generation)
  end)
end

softcut_native_supervisor_tick = function(generation)
  if generation ~= softcut._native_generation
      or softcut._native_state ~= "ready_muted" then return end
  local now = host.now()
  local ok, diagnostics = pcall(host.sc_softcut_diagnostics)
  if not ok or not diagnostics or diagnostics.snapshot_fresh ~= true then
    softcut_native_supervisor_mark_stale(
      not ok and tostring(diagnostics) or "snapshot Oboe ausente ou sem avanço")
  else
    local supervisor = softcut._native_supervisor
    supervisor.fresh = true
    supervisor.stale = false
    supervisor.last_error = nil
    supervisor.cycles = supervisor.cycles + 1
    supervisor.snapshot_sequence = diagnostics.snapshot_sequence
    supervisor.snapshot_age_seconds = diagnostics.snapshot_age_seconds
    supervisor.last_snapshot_at = now
    supervisor.routing = diagnostics.routing
    softcut._native_routing_diagnostics = diagnostics.routing
    local nativeGateClosed = diagnostics.routing ~= nil and diagnostics.routing.native_gate_open == false

    for voice = 1, softcut.VOICE_COUNT do
      local state = supervisor.voices[voice]
      local params = softcut._native_params[voice]
      local javaOk, javaPosition = pcall(host.softcut_position, voice)
      local nativePosition = diagnostics.positions and tonumber(diagnostics.positions[voice])
      javaPosition = javaOk and tonumber(javaPosition) or nil
      if params and javaPosition and nativePosition
          and javaPosition == javaPosition and nativePosition == nativePosition
          and javaPosition ~= math.huge and javaPosition ~= -math.huge
          and nativePosition ~= math.huge and nativePosition ~= -math.huge then
        local sampleOk, stats = pcall(host.sc_softcut_phase_sample, voice,
          javaPosition, nativePosition, params.loop_start, params.loop_end, params.loop ~= 0)
        if sampleOk and stats then
          softcut._native_phase_diagnostics[voice] = stats
          state.fresh = true
          state.stale = false
          state.samples = stats.samples
          state.java_position = javaPosition
          state.native_position = nativePosition
          state.last_error_seconds = stats.last_error_seconds
          state.max_abs_error_seconds = stats.max_abs_error_seconds
          state.rms_error_seconds = stats.rms_error_seconds
          state.reanchor_threshold_seconds = stats.reanchor_threshold_seconds
          state.last_sample_at = now
          state.last_error = nil
          if stats.should_reanchor == true and nativeGateClosed
              and now - (state.last_correction_attempt_at or -math.huge) >= 0.20 then
            state.last_correction_attempt_at = now
            if softcut_native_phase_reanchor(voice, javaPosition) then
              state.corrections = (state.corrections or 0) + 1
              state.last_correction_at = now
              state.last_correction_error_seconds = stats.last_error_seconds
              supervisor.corrections = supervisor.corrections + 1
            else
              state.last_correction_failed_at = now
            end
          end
          state.reanchor_blocked_by_gate = not nativeGateClosed
        else
          state.fresh = false
          state.stale = true
          state.last_error = "amostra de fase inválida"
        end
      else
        state.fresh = false
        state.stale = true
        state.last_error = "posição Java/nativa inválida"
      end
    end

    -- BufWr cursors are independent from the six playback phase witnesses.
    for channel = 1, 2 do
      local state = supervisor.recorders[channel]
      local owner = softcut._native_recorder_monitor_owner[channel]
          or softcut._native_record_owner[channel]
      local nativePosition = diagnostics.recorder_positions
          and tonumber(diagnostics.recorder_positions[channel])
      local recorderActive = diagnostics.recorder_active
          and diagnostics.recorder_active[channel] == true
      local reanchorPending = diagnostics.recorder_reanchor_pending
          and diagnostics.recorder_reanchor_pending[channel] == true
      local luaOwner = softcut._native_record_owner[channel]
      local params = owner and softcut._native_params[owner] or nil
      local window = softcut._native_recorder_windows[channel]

      -- Retry an unconfirmed stop only after the Java worker had time to finish
      -- its first /sync. The single worker serializes duplicate stop requests.
      if recorderActive and not luaOwner and state.stop_requested_at
          and now - state.stop_requested_at >= 0.50
          and now - (state.last_stop_retry_at or -math.huge) >= 1.0 then
        local recorderNode = softcut._native_recorders[channel]
        if recorderNode and window then
          host.sc_softcut_record_stop(channel, recorderNode, window.start, window.finish)
          state.last_stop_retry_at = now
          state.stop_retries = (state.stop_retries or 0) + 1
        end
      end

      -- Ignore idle drift while the playback head continues moving. When a
      -- recovery is pending, compare against the Java cursor captured at stop.
      local shouldSample = recorderActive or state.recovery_pending == true
      if shouldSample and owner and params and window and window.finish > window.start
          and nativePosition and nativePosition >= 0 then
        local javaHeadPosition
        if not recorderActive and state.stop_java_position ~= nil then
          javaHeadPosition = state.stop_java_position
        else
          local javaOk, value = pcall(host.softcut_position, owner)
          javaHeadPosition = javaOk and tonumber(value) or nil
        end
        local javaWritePosition = javaHeadPosition
            and (javaHeadPosition + (params.rec_offset or 0)) or nil
        if javaHeadPosition and javaHeadPosition == javaHeadPosition
            and javaHeadPosition ~= math.huge and javaHeadPosition ~= -math.huge
            and javaWritePosition and javaWritePosition == javaWritePosition
            and javaWritePosition ~= math.huge and javaWritePosition ~= -math.huge then
          local sampleOk, stats = pcall(host.sc_softcut_recorder_sample, channel,
            javaWritePosition, nativePosition, window.start, window.finish, true, recorderActive)
          if sampleOk and stats and stats.valid == true then
            state.fresh = true
            state.stale = false
            state.owner = owner
            state.recording = recorderActive
            state.java_position = javaHeadPosition
            state.java_write_position = javaWritePosition
            state.native_position = nativePosition
            state.samples = stats.samples
            state.last_error_seconds = stats.last_error_seconds
            state.max_abs_error_seconds = stats.max_abs_error_seconds
            state.rms_error_seconds = stats.rms_error_seconds
            state.threshold_seconds = stats.threshold_seconds
            state.last_sample_at = now
            state.reanchor_pending = reanchorPending
            state.last_error = nil

            if stats.recovery_required == true then
              state.recovery_pending = true
              state.recovery_blocked_by_recording = recorderActive
            elseif state.recovery_pending and not recorderActive then
              state.recovery_pending = false
              state.recovered_at = now
              state.recovery_status = "recovered"
              state.recovery_blocked_by_recording = false
            end

            if state.recovery_pending and not recorderActive and not reanchorPending
                and stats.reanchor_allowed == true
                and now - (state.last_reanchor_attempt_at or -math.huge) >= 0.50 then
              state.last_reanchor_attempt_at = now
              local recorderNode = softcut._native_recorders[channel]
              local queued = recorderNode and host.sc_softcut_recorder_reanchor(
                channel, recorderNode, javaHeadPosition)
              if queued then
                state.reanchor_queued_at = now
                state.recovery_status = "queued_after_confirmed_stop"
                state.recoveries_queued = (state.recoveries_queued or 0) + 1
                supervisor.recorder_recoveries_queued =
                  supervisor.recorder_recoveries_queued + 1
              else
                state.last_reanchor_queue_failed_at = now
                state.recovery_status = "waiting_for_confirmed_idle"
              end
            end
            if recorderActive then
              state.recovery_blocked_by_recording = state.recovery_pending
            end
          else
            state.fresh = false
            state.stale = true
            state.last_error = "sample do cursor BufWr inválido"
          end
        else
          state.fresh = false
          state.stale = true
          state.last_error = "posição Java inválida para cursor BufWr"
        end
      else
        state.recording = recorderActive
        state.reanchor_pending = reanchorPending
        if recorderActive and not owner then
          state.fresh = false
          state.stale = true
          state.last_error = "BufWr ativo sem voz proprietária"
        elseif recorderActive then
          state.fresh = false
          state.stale = true
          state.last_error = "posição do cursor BufWr ausente ou inválida"
        elseif state.recovery_pending then
          state.fresh = false
          state.stale = true
          state.last_error = "cursor BufWr ausente durante recovery pendente"
        else
          state.fresh = false
          state.stale = false
          state.last_error = nil
        end
      end
    end
  end
  sched_add(now + 0.033, function()
    softcut_native_supervisor_tick(generation)
  end)
end

local function softcut_native_start_recording(channel, voice)
  local owner = softcut._native_record_owner[channel]
  local node = softcut._native_recorders[channel]
  local params = softcut._native_params[voice]
  if owner ~= voice or not node or not params or softcut._native_state ~= "ready_muted"
      or (softcut._tape_cut_level or 0) > 0 then return false end
  -- O envio Java usa SoftcutCore como autoridade. O envio nativo usa o bus 31
  -- e o BufWr nativo como autoridade; nunca gravar os dois caminhos em paralelo.
  if (softcut._engine_cut_level or 0) > 0
      and softcut._engine_cut_source ~= "native" then return false end

  local offset = params.rec_offset or 0
  -- Keep the Phasor's wrap window identical to Java. The write offset only
  -- expands buffer reservation/readback, never the base phase loop.
  local startSec = math.max(0, params.loop_start)
  local endSec = math.max(startSec, params.loop_end)
  local readbackStartSec = math.max(0, params.loop_start + math.min(0, offset))
  local readbackEndSec = math.max(readbackStartSec, params.loop_end + math.max(0, offset))
  local positionOk, startPosition = pcall(host.softcut_position, voice)
  startPosition = positionOk and tonumber(startPosition) or params.position
  if type(startPosition) ~= "number" or startPosition ~= startPosition
      or startPosition == math.huge or startPosition == -math.huge then
    return false
  end

  -- A new recording session is the safe recovery boundary: seed both silent
  -- phase and BufWr from the live Java cursor before the worker confirms /sync.
  params.position = startPosition
  local phaseNode = softcut._native_nodes[voice]
  if phaseNode then
    pcall(sc_api.set, phaseNode, {position = startPosition, phase_play = params.phase_play})
  end

  local nativeEngine = engine and engine._native_state == "native"
      and softcut._engine_cut_source == "native"
  local matrixControls = {
    level_slew_time = params.level_slew_time,
    enabled = params.enable, rec_offset = params.rec_offset,
    engine_level = nativeEngine and (softcut._engine_cut_level or 0) or 0
  }
  for src = 1, softcut.VOICE_COUNT do
    matrixControls["cut_from_" .. src] = softcut._native_cut_matrix[src][voice] or 0
  end
  sc_api.set(node, matrixControls)
  softcut._native_recorder_monitor_owner[channel] = voice
  softcut._native_recorder_windows[channel] = {start = startSec, finish = endSec}
  local ok = host.sc_softcut_record_start(channel, node,
    startSec, endSec, startPosition, params.rate,
    params.rec_level, params.pre_level, params.input_level, params.recpre_slew_time,
    readbackEndSec)
  softcut._native_recording[channel] = ok and true or false
  if ok then
    local state = softcut._native_supervisor.recorders[channel]
    if state then
      state.recovery_pending = false
      state.recovery_status = "seeded_from_java_on_start"
      state.stop_java_position = nil
      state.stop_requested_at = nil
      state.started_at = host.now()
    end
  end
  return softcut._native_recording[channel]
end

local function softcut_native_stop_recording(channel, voice)
  if softcut._native_record_owner[channel] ~= voice then return false end
  if softcut._native_recording[channel] then
    local node = softcut._native_recorders[channel]
    local params = softcut._native_params[voice]
    if node and params then
      local offset = params.rec_offset or 0
      local readbackStartSec = math.max(0, params.loop_start + math.min(0, offset))
      local readbackEndSec = math.max(readbackStartSec, params.loop_end + math.max(0, offset))
      local state = softcut._native_supervisor.recorders[channel]
      if state then
        local positionOk, position = pcall(host.softcut_position, voice)
        state.stop_java_position = positionOk and tonumber(position) or params.position
        state.stop_requested_at = host.now()
        state.recovery_status = "waiting_for_confirmed_stop"
      end
      host.sc_softcut_record_stop(channel, node, readbackStartSec, readbackEndSec)
    end
  end
  softcut._native_recording[channel] = nil
  return true
end

local function softcut_native_has_active_recording()
  for channel = 1, 2 do
    local state = softcut._native_supervisor
        and softcut._native_supervisor.recorders
        and softcut._native_supervisor.recorders[channel]
    if softcut._native_recording[channel]
        or (state and (state.recording == true or state.recovery_pending == true
          or state.stop_requested_at ~= nil)) then
      return true
    end
  end
  return false
end

local function softcut_native_fallback(reason)
  local nativeEngineSend = engine and engine._native_state == "native"
      and softcut._engine_cut_source == "native" and (softcut._engine_cut_level or 0) > 0
  if nativeEngineSend then
    host.log("Softcut: fallback bloqueado enquanto o engine nativo envia áudio ao Softcut.", false)
    return false
  end
  if (softcut._native_output_owner or softcut._native_output_uncertain) and softcut_native_has_active_recording() then
    host.log("Softcut: fallback adiado até a gravação nativa terminar e sincronizar.", false)
    return false
  end
  -- Stop scheduled supervision before attempting the ownership transition.
  softcut._native_generation = softcut._native_generation + 1
  softcut._native_supervisor.running = false
  softcut._native_supervisor.fresh = false
  softcut._native_supervisor.stale = true
  -- Never free native nodes or claim Java ownership until the native output
  -- gate confirms mute and the Java path has safely resumed.
  local outputReturned = host.sc_softcut_output(false, false)
  softcut._native_supervisor.last_error = tostring(reason)
  if not outputReturned then
    softcut._native_output_uncertain = true
    softcut._native_state = "fallback_pending"
    host.log("Softcut: não foi possível confirmar o silêncio nativo; nodes preservados e Tape bloqueado.", false)
    return false
  end
  softcut._native_output_owner = false
  softcut._native_output_uncertain = false
  softcut._native_state = "java"
  softcut._native_output_validated = false
  host.sc_softcut_ready(false)
  for channel = 1, 2 do
    local recorder = softcut._native_recorders[channel]
    if recorder then sc_api.free(recorder) end
    softcut._native_recorders[channel] = nil
    softcut._native_recording[channel] = nil
  end
  for voice = 1, softcut.VOICE_COUNT do
    local node = softcut._native_nodes[voice]
    if node then sc_api.free(node) end
    softcut._native_nodes[voice] = nil
  end
  for i = 1, 2 do
    sc_api.buffer_free(softcut._native_buffers[i])
  end
  host.log("Softcut SuperCollider: " .. tostring(reason) .. "; mantendo fallback Java.", false)
  return true
end

local function softcut_native_poll(generation, phase, deadline)
  if generation ~= softcut._native_generation or softcut._native_state == "java" then return end
  local matched = false
  for i = 1, 48 do
    local reply = host.sc_poll_reply()
    if not reply then break end
    local expected = (phase == "synth_voice" or phase == "synth_recorder") and "/d_recv" or "/b_alloc"
    if reply.address == "/done" and reply.command == expected then
      matched = true
      break
    elseif reply.address == "/fail" and reply.command == expected then
      softcut_native_fallback(reply.message or "falha no servidor")
      return
    else
      sc_reply_backlog[#sc_reply_backlog + 1] = reply
    end
  end
  if not matched then
    if host.now() >= deadline then
      softcut_native_fallback("timeout durante " .. phase)
      return
    end
    sched_add(host.now() + 0.01, function()
      softcut_native_poll(generation, phase, deadline)
    end)
    return
  end

  if phase == "synth_voice" then
    if not sc_api.load_synthdef("SoftcutRecorder.scsyndef") then
      softcut_native_fallback("não foi possível carregar o SynthDef de gravação")
      return
    end
    softcut._native_state = "loading_recorder"
    sched_add(host.now() + 0.01, function()
      softcut_native_poll(generation, "synth_recorder", host.now() + 5)
    end)
  elseif phase == "synth_recorder" then
    softcut._native_state = "allocating_left"
    if not sc_api.buffer_alloc(softcut._native_buffers[1], 1, 1) then
      softcut_native_fallback("não foi possível alocar o buffer 1")
      return
    end
    sched_add(host.now() + 0.01, function()
      softcut_native_poll(generation, "buffer_left", host.now() + 5)
    end)
  elseif phase == "buffer_left" then
    softcut._native_state = "allocating_right"
    if not sc_api.buffer_alloc(softcut._native_buffers[2], 1, 1) then
      softcut_native_fallback("não foi possível alocar o buffer 2")
      return
    end
    sched_add(host.now() + 0.01, function()
      softcut_native_poll(generation, "buffer_right", host.now() + 5)
    end)
  else
    for voice = 1, softcut.VOICE_COUNT do
      local params = softcut._native_params[voice]
      local node = sc_api.new("SoftcutVoice", {
        out = 9, position_bus = 10 + voice, buf = params.buf,
        rate = params.rate, loop_start = params.loop_start,
        loop_end = params.loop_end, position = params.position,
        play = 0, phase_play = params.phase_play, phase_enable = params.phase_enable,
        loop = params.loop, level = params.level, pan = params.pan,
        rate_slew_time = params.rate_slew_time,
        level_slew_time = params.level_slew_time,
        pan_slew_time = params.pan_slew_time,
        enable = params.enable, fade_time = params.fade_time,
        cut_bus = 2 + voice,
        pre_filter_fc = params.pre_filter_fc, pre_filter_fc_mod = params.pre_filter_fc_mod,
        pre_filter_rq = params.pre_filter_rq, pre_filter_lp = params.pre_filter_lp,
        pre_filter_hp = params.pre_filter_hp, pre_filter_bp = params.pre_filter_bp,
        pre_filter_br = params.pre_filter_br, pre_filter_dry = params.pre_filter_dry,
        post_filter_fc = params.post_filter_fc, post_filter_fc_mod = params.post_filter_fc_mod,
        post_filter_rq = params.post_filter_rq, post_filter_lp = params.post_filter_lp,
        post_filter_hp = params.post_filter_hp, post_filter_bp = params.post_filter_bp,
        post_filter_br = params.post_filter_br, post_filter_dry = params.post_filter_dry
      }, 1, 0)
      if not node then
        softcut_native_fallback("não foi possível criar a voz " .. voice)
        return
      end
      softcut._native_nodes[voice] = node
    end
    for channel = 1, 2 do
      local recorder = sc_api.new("SoftcutRecorder", {
        buf = softcut._native_buffers[channel], in_bus = 2,
        engine_bus = 31, engine_level = (engine and engine._native_state == "native"
            and softcut._engine_cut_source == "native")
            and (softcut._engine_cut_level or 0) or 0,
        start_frame = 0, end_frame = 1, position = 0, rate = 1,
        record = 0, position_bus = 16 + channel,
        rec_level = 0, pre_level = 1,
        input_level = 1, adc_level = softcut._native_adc_level or 1,
        recpre_slew_time = 0.001, level_slew_time = 0.001,
        cut_bus_1 = 3, cut_bus_2 = 4, cut_bus_3 = 5,
        cut_bus_4 = 6, cut_bus_5 = 7, cut_bus_6 = 8
      }, 1, 0)
      if not recorder then
        softcut_native_fallback("não foi possível criar o gravador do buffer " .. channel)
        return
      end
      softcut._native_recorders[channel] = recorder
    end
    softcut._native_state = "ready_muted"
    host.sc_softcut_ready(true)
    for channel = 1, 2 do
      local owner = softcut._native_record_owner[channel]
      if owner then softcut_native_start_recording(channel, owner) end
    end
    softcut_native_supervisor_start(generation)
    host.log("Softcut SuperCollider: seis vozes silenciosas, supervisor de fase ativo e fallback Java preservado.", false)
  end
end

function softcut._set_native_output_owner(enabled)
  -- O binding Java usa optboolean; normalize 0/1 da API norns para booleano Lua.
  enabled = sc_flag(enabled) ~= 0
  if enabled and (softcut._native_state ~= "ready_muted"
      or not softcut._native_output_validated) then return false end
  local changed = host.sc_softcut_output(enabled, softcut._native_output_validated == true)
  if changed then
    softcut._native_output_owner = enabled
    softcut._native_output_uncertain = false
  elseif enabled then
    -- A failed native enable may be ambiguous at the JNI boundary.
    softcut._native_output_uncertain = true
  else
    softcut._native_output_uncertain = true
  end
  return changed
end

function softcut._reset_native()
  local nativeEngineSend = engine and engine._native_state == "native"
      and softcut._engine_cut_source == "native" and (softcut._engine_cut_level or 0) > 0
  if nativeEngineSend or ((softcut._native_output_owner or softcut._native_output_uncertain)
      and softcut_native_has_active_recording()) then
    host.log("Softcut: reset nativo recusado enquanto o engine envia áudio ou há gravação sem readback.", false)
    return false
  end
  local outputReturned = host.sc_softcut_output(false, false)
  if not outputReturned then
    softcut._native_output_uncertain = true
    softcut._native_state = "fallback_pending"
    softcut._native_supervisor.running = false
    softcut._native_generation = softcut._native_generation + 1
    host.log("Softcut: reset nativo interrompido; mute não confirmado, nodes preservados.", false)
    return false
  end
  softcut._native_output_owner = false
  softcut._native_output_uncertain = false
  host.sc_softcut_ready(false)
  softcut._native_supervisor.running = false
  softcut._native_supervisor.fresh = false
  softcut._native_supervisor.stale = true
  softcut._native_phase_diagnostics = {}
  softcut._native_routing_diagnostics = nil
  softcut._native_recorder_monitor_owner = {}
  softcut._native_recorder_windows = {}
  softcut._native_generation = softcut._native_generation + 1
  for channel = 1, 2 do
    local recorder = softcut._native_recorders[channel]
    if recorder then sc_api.free(recorder) end
    softcut._native_recorders[channel] = nil
    softcut._native_recording[channel] = nil
  end
  for voice = 1, softcut.VOICE_COUNT do
    local node = softcut._native_nodes[voice]
    if node then sc_api.free(node) end
    softcut._native_nodes[voice] = nil
  end
  for i = 1, 2 do
    sc_api.buffer_free(softcut._native_buffers[i])
  end
  softcut._native_state = "java"
  return true
end

function softcut._start_native()
  if (softcut._tape_cut_level or 0) > 0 then
    host.log("Softcut nativo: desative Tape->Softcut antes de iniciar a rota nativa.", false)
    return false
  end
  if not softcut._reset_native() then return false end
  if not sc_api.is_running()
      or not sc_api.load_synthdef("SoftcutVoice.scsyndef") then
    softcut._native_state = "java"
    return false
  end
  softcut._native_state = "loading_synth"
  local generation = softcut._native_generation
  sched_add(host.now() + 0.01, function()
    softcut_native_poll(generation, "synth_voice", host.now() + 5)
  end)
  return true
end

softcut_native_command = function(voice, command, value)
  local params = softcut._native_params[voice]
  if not params then return end
  local control
  if command == "rate" or command == "position" or command == "loop_start"
      or command == "loop_end" or command == "level" or command == "pan"
      or command == "loop" or command == "enable" or command == "fade_time"
      or command == "rate_slew_time" or command == "level_slew_time" or command == "pan_slew_time"
      or command == "rec_level" or command == "pre_level"
      or command == "input_level" or command == "recpre_slew_time"
      or command:match("^pre_filter_") or command:match("^post_filter_") then
    control = command
    params[control] = value
    if command == "enable" then params.phase_enable = value end
  elseif command == "rec_offset" then
    params.rec_offset = value
  elseif command == "buffer" then
    local channel = math.max(1, math.min(2, math.floor(value)))
    params.buffer_channel = channel
    params.buf = softcut._native_buffers[channel]
    control = "buf"
  elseif command == "play" then
    params.play = value
    params.phase_play = (params.play ~= 0 or params.rec ~= 0) and 1 or 0
    control = "phase_play"
  elseif command == "rec" then
    params.rec = value
    params.phase_play = (params.play ~= 0 or params.rec ~= 0) and 1 or 0
    local phaseNode = softcut._native_nodes[voice]
    if phaseNode and softcut._native_state == "ready_muted" then
      sc_api.set(phaseNode, {phase_play = params.phase_play})
    end
    local channel = params.buffer_channel
    if value ~= 0 then
      if not softcut._native_recording[channel] then
        softcut_native_start_recording(channel, voice)
      end
    else
      softcut_native_stop_recording(channel, voice)
    end
    return
  end

  if softcut._native_state ~= "ready_muted" then return end
  local node = softcut._native_nodes[voice]
  if node and control and control ~= "play"
      and command ~= "rec_level" and command ~= "pre_level"
      and command ~= "input_level" and command ~= "recpre_slew_time" then
    local controls = {[control] = params[control]}
    if command == "enable" then controls.phase_enable = params.phase_enable end
    sc_api.set(node, controls)
  end

  local channel = params.buffer_channel
  if softcut._native_record_owner[channel] == voice
      and softcut._native_recording[channel] then
    local recorder = softcut._native_recorders[channel]
    local recControl
    if command == "rate" or command == "position" or command == "rec_level"
        or command == "pre_level" or command == "input_level" or command == "enable"
        or command == "rec_offset" or command == "recpre_slew_time" or command == "level_slew_time" then
      recControl = command
    end
    if recorder and recControl then
      local key = recControl == "enable" and "enabled" or recControl
      sc_api.set(recorder, {[key] = params[recControl]})
    end
    if command == "loop_start" or command == "loop_end" then
      softcut_native_start_recording(channel, voice)
    end
  end
end
function softcut.enable(v, x) sc(v, "enable", sc_flag(x)) end
function softcut.play(v, x) sc(v, "play", sc_flag(x)) end
function softcut.position(v, x) sc(v, "position", x) end
function softcut.get_position(v)
  v = math.max(1, math.min(6, math.floor(tonumber(v) or 1)))
  -- Phase telemetry and correction run in the independent supervisor scheduler.
  return host.softcut_position(v)
end
function softcut.native_phase_diagnostics(v)
  v=math.max(1,math.min(6,math.floor(tonumber(v) or 1)))
  return softcut._native_phase_diagnostics[v]
end
function softcut.native_routing_diagnostics()
  return softcut._native_routing_diagnostics
end
function softcut.native_supervisor_diagnostics()
  return softcut._native_supervisor
end
-- Non-destructive scsynth buffer-store probe. Uses temporary IDs and
-- distinct channel payloads; it never touches live Softcut buffers or opens audio.
function softcut.native_buffer_isolation_probe()
  return host.sc_softcut_buffer_isolation_probe()
end

function softcut.native_recorder_diagnostics(channel)
  channel = math.max(1, math.min(2, math.floor(tonumber(channel) or 1)))
  return softcut._native_supervisor.recorders[channel]
end
function softcut.query_position(v)
  v = math.max(1, math.min(6, math.floor(tonumber(v) or 1)))
  if type(softcut._event_position) == "function" then
    safe(softcut._event_position, v, softcut.get_position(v))
  end
end
function softcut.render_buffer(ch, start, duration, samples)
  ch = math.max(1, math.min(2, math.floor(tonumber(ch) or 1)))
  local first = tonumber(start) or 0
  local length = tonumber(duration) or -1
  local limit = math.max(1, math.min(65536, math.floor(tonumber(samples) or 1024)))
  host.softcut_buffer_render(ch, first, length, limit)
end
function softcut.voice_sync(dst, src, offset)
  dst = math.max(1, math.min(6, math.floor(tonumber(dst) or 1)))
  src = math.max(1, math.min(6, math.floor(tonumber(src) or 1)))
  offset = tonumber(offset) or 0
  host.softcut_sync(dst, src, offset)
  if softcut_native_command then softcut_native_command(dst, "position", host.softcut_position(src) + offset) end
end
function softcut.rate(v, x) sc(v, "rate", x) end
function softcut.level(v, x) sc(v, "level", x) end
function softcut.pan(v, x) sc(v, "pan", x) end
function softcut.loop(v, x) sc(v, "loop", sc_flag(x)) end
function softcut.loop_start(v, x) sc(v, "loop_start", x) end
function softcut.loop_end(v, x) sc(v, "loop_end", x) end
function softcut.buffer(v, channel)
  v = math.max(1, math.min(6, math.floor(tonumber(v) or 1)))
  local params = softcut._native_params[v]
  local nextChannel = math.max(1, math.min(2, math.floor(tonumber(channel) or 1)))
  if params and params.buffer_channel ~= nextChannel and params.rec ~= 0 then
    softcut.rec(v, 0)
  end
  sc(v, "buffer", nextChannel)
end
function softcut.pan_slew_time(v, x) sc(v, "pan_slew_time", x) end
function softcut.level_slew_time(v, x) sc(v, "level_slew_time", x) end
function softcut.rate_slew_time(v, x) sc(v, "rate_slew_time", x) end
function softcut.recpre_slew_time(v, x) sc(v, "recpre_slew_time", x) end
function softcut.fade_time(v, x) sc(v, "fade_time", x) end
-- Filtros Softcut: estágios state-variable pré e pós, com saídas LP/HP/BP/BR/dry.
local function sc_filter(v, stage, parameter, value)
  sc(v, stage .. "_filter_" .. parameter, value)
end
function softcut.pre_filter_fc(v, x) sc_filter(v, "pre", "fc", x) end
function softcut.pre_filter_fc_mod(v, x) sc_filter(v, "pre", "fc_mod", x) end
function softcut.pre_filter_rq(v, x) sc_filter(v, "pre", "rq", x) end
function softcut.pre_filter_lp(v, x) sc_filter(v, "pre", "lp", x) end
function softcut.pre_filter_hp(v, x) sc_filter(v, "pre", "hp", x) end
function softcut.pre_filter_bp(v, x) sc_filter(v, "pre", "bp", x) end
function softcut.pre_filter_br(v, x) sc_filter(v, "pre", "br", x) end
function softcut.pre_filter_dry(v, x) sc_filter(v, "pre", "dry", x) end
function softcut.post_filter_fc(v, x) sc_filter(v, "post", "fc", x) end
function softcut.post_filter_fc_mod(v, x) sc_filter(v, "post", "fc_mod", x) end
function softcut.post_filter_rq(v, x) sc_filter(v, "post", "rq", x) end
function softcut.post_filter_lp(v, x) sc_filter(v, "post", "lp", x) end
function softcut.post_filter_hp(v, x) sc_filter(v, "post", "hp", x) end
function softcut.post_filter_bp(v, x) sc_filter(v, "post", "bp", x) end
function softcut.post_filter_br(v, x) sc_filter(v, "post", "br", x) end
function softcut.post_filter_dry(v, x) sc_filter(v, "post", "dry", x) end
-- Aliases antigos do norns apontam para o estágio pré-filtro.
function softcut.filter_fc(v, x) softcut.pre_filter_fc(v, x) end
function softcut.filter_fc_mod(v, x) softcut.pre_filter_fc_mod(v, x) end
function softcut.filter_rq(v, x) softcut.pre_filter_rq(v, x) end
function softcut.filter_lp(v, x) softcut.pre_filter_lp(v, x) end
function softcut.filter_hp(v, x) softcut.pre_filter_hp(v, x) end
function softcut.filter_bp(v, x) softcut.pre_filter_bp(v, x) end
function softcut.filter_br(v, x) softcut.pre_filter_br(v, x) end
function softcut.filter_dry(v, x) softcut.pre_filter_dry(v, x) end
-- Compatibilidade com a extensão simplificada anterior.
function softcut.filter(v, enabled) sc(v, "filter", sc_flag(enabled)) end
function softcut.filter_cutoff(v, hz) softcut.pre_filter_fc(v, hz) end
function softcut.filter_type(v, kind)
  softcut.pre_filter_lp(v, kind == 0 and 1 or 0)
  softcut.pre_filter_hp(v, kind == 1 and 1 or 0)
  softcut.pre_filter_dry(v, kind == 2 and 1 or 0)
end
-- Gravação/overdub interno por voz; pre_level também é individual por voz.
function softcut.rec(v, enabled)
  v = math.max(1, math.min(6, math.floor(tonumber(v) or 1)))
  local active = sc_flag(enabled)
  local params = softcut._native_params[v]
  local channel = params and params.buffer_channel or 1
  local owner = softcut._native_record_owner[channel]
  if active ~= 0 then
    if owner and owner ~= v then
      host.log("Softcut: buffer " .. channel .. " já está sendo gravado pela voz " .. owner .. "; segunda escrita recusada.", false)
      return false
    end
    softcut._native_record_owner[channel] = v
  end
  local result = sc(v, "rec", active)
  if active == 0 and softcut._native_record_owner[channel] == v then
    softcut._native_record_owner[channel] = nil
  end
  return result
end
function softcut.rec_level(v, level) sc(v, "rec_level", level) end
function softcut.pre_level(v, level) sc(v, "pre_level", level) end
function softcut.rec_offset(v, offset) sc(v, "rec_offset", offset) end
-- Entrada física Android (mono) roteada individualmente para cada voz.
-- O segundo canal ainda não existe no backend e, portanto, permanece silencioso.
function softcut.level_input_cut(input, cut, level)
  input = math.floor(tonumber(input) or 1)
  cut = math.max(1, math.min(6, math.floor(tonumber(cut) or 1)))
  level = tonumber(level)
  if not level or level ~= level or level == math.huge or level == -math.huge then return false end
  level = math.max(0, math.min(2, level))
  sc(cut, "input_level", input == 1 and level or 0)
  return true
end
function softcut.level_adc_cut(input, cut, level)
  softcut.level_input_cut(input, cut, level)
end
-- Matriz de envio mono entre vozes; roteamento tem latência de um bloco de áudio.
function softcut.level_cut_cut(src, dst, level)
  src = math.max(1, math.min(6, math.floor(tonumber(src) or 1)))
  dst = math.max(1, math.min(6, math.floor(tonumber(dst) or 1)))
  level = tonumber(level) or 0
  softcut._native_cut_matrix[src][dst] = level
  sc(src, "cut_level_" .. dst, level)
  local channel = softcut._native_params[dst].buffer_channel
  if softcut._native_record_owner[channel] == dst and softcut._native_recording[channel] then
    local recorder = softcut._native_recorders[channel]
    if recorder then sc_api.set(recorder, {["cut_from_" .. src] = level}) end
  end
end
function softcut.buffer_read_mono(file, start_src, start_dst, duration, ch_src, ch_dst, preserve, mix)
  return host.softcut_buffer(tostring(file), tonumber(start_src) or 0,
    tonumber(start_dst) or 0, tonumber(duration) or -1,
    tonumber(ch_src) or 1, tonumber(ch_dst) or 1,
    tonumber(preserve) or 0, tonumber(mix) or 1)
end
function softcut.buffer_clear()
  host.softcut_buffer_clear(1, 0, -1, 0, 0)
  host.softcut_buffer_clear(2, 0, -1, 0, 0)
end
function softcut.buffer_clear_region(start, duration, fade_time, preserve)
  for channel = 1, 2 do
    host.softcut_buffer_clear(channel, tonumber(start) or 0, tonumber(duration) or -1,
      tonumber(fade_time) or 0, tonumber(preserve) or 0)
  end
end
function softcut.buffer_clear_channel(channel)
  channel = math.max(1, math.min(2, math.floor(tonumber(channel) or 1)))
  host.softcut_buffer_clear(channel, 0, -1, 0, 0)
end
function softcut.buffer_clear_region_channel(channel, start, duration, fade_time, preserve)
  channel = math.max(1, math.min(2, math.floor(tonumber(channel) or 1)))
  host.softcut_buffer_clear(channel, tonumber(start) or 0, tonumber(duration) or -1,
    tonumber(fade_time) or 0, tonumber(preserve) or 0)
end
function softcut.buffer_copy_mono(src_ch, dst_ch, start_src, start_dst, duration, fade_time, preserve, reverse)
  host.softcut_buffer_copy(
    math.max(1, math.min(2, math.floor(tonumber(src_ch) or 1))),
    math.max(1, math.min(2, math.floor(tonumber(dst_ch) or 1))),
    tonumber(start_src) or 0, tonumber(start_dst) or 0, tonumber(duration) or -1,
    tonumber(fade_time) or 0, tonumber(preserve) or 0, sc_flag(reverse))
end
function softcut.buffer_copy_stereo(start_src, start_dst, duration, fade_time, preserve, reverse)
  softcut.buffer_copy_mono(1, 1, start_src, start_dst, duration, fade_time, preserve, reverse)
  softcut.buffer_copy_mono(2, 2, start_src, start_dst, duration, fade_time, preserve, reverse)
end
function softcut.buffer_write_mono(file, start, duration, channel)
  return host.softcut_buffer_write(tostring(file), tonumber(start) or 0,
    tonumber(duration) or -1, math.max(1, math.min(2, math.floor(tonumber(channel) or 1))))
end
function softcut.buffer_write_stereo(file, start, duration)
  return host.softcut_buffer_write(tostring(file), tonumber(start) or 0,
    tonumber(duration) or -1, 0)
end
function softcut.buffer_read_stereo(file, start_src, start_dst, duration, preserve, mix)
  local a = host.softcut_buffer(tostring(file), tonumber(start_src) or 0,
    tonumber(start_dst) or 0, tonumber(duration) or -1, 1, 1,
    tonumber(preserve) or 0, tonumber(mix) or 1)
  local b = host.softcut_buffer(tostring(file), tonumber(start_src) or 0,
    tonumber(start_dst) or 0, tonumber(duration) or -1, 2, 2,
    tonumber(preserve) or 0, tonumber(mix) or 1)
  return a ~= false and b ~= false
end
-- Eventos de posição/fase consultados no motor de áudio em baixa frequência.
-- Não bloqueia a thread Lua nem a thread de áudio.
softcut._event_generation = 0
softcut._phase_quant = {}
softcut._phase_offset = {}
softcut._last_position = {}
softcut._last_phase_bucket = {}
function softcut.phase_quant(v, quantum)
  v = math.max(1, math.min(6, math.floor(tonumber(v) or 1)))
  softcut._phase_quant[v] = math.max(0, tonumber(quantum) or 0)
  softcut._last_phase_bucket[v] = nil
end
function softcut.phase_offset(v, offset)
  v = math.max(1, math.min(6, math.floor(tonumber(v) or 1)))
  softcut._phase_offset[v] = tonumber(offset) or 0
  softcut._last_phase_bucket[v] = nil
end
local softcut_start_events
function softcut.poll_start_phase()
  softcut._phase_poll_enabled = true
  softcut_start_events()
end
function softcut.poll_stop_phase()
  softcut._phase_poll_enabled = false
  softcut._event_generation = softcut._event_generation + 1
end
function softcut.event() end
softcut_start_events = function()
  if not softcut._phase_poll_enabled or type(softcut._event_phase) ~= "function" then return end
  softcut._event_generation = softcut._event_generation + 1
  local generation = softcut._event_generation
  local function poll()
    if generation ~= softcut._event_generation or not softcut._phase_poll_enabled then return end
    for v = 1, softcut.VOICE_COUNT do
      local quantum = softcut._phase_quant[v] or 0
      local position = softcut.get_position(v)
      local offset = softcut._phase_offset[v] or 0
      local phase
      if quantum > 0 then
        phase = math.floor((position + offset) / quantum) * quantum
      else
        phase = position
      end
      if softcut._last_phase_bucket[v] ~= phase then
        softcut._last_phase_bucket[v] = phase
        safe(softcut._event_phase, v, phase)
      end
    end
    sched_add(host.now() + 0.001, poll)
  end
  sched_add(host.now() + 0.001, poll)
end
function softcut.event_phase(fn)
  softcut._event_phase = fn
  if softcut._phase_poll_enabled then softcut_start_events() end
end
function softcut.event_position(fn)
  -- O callback de posição é sob demanda: só query_position(v) o dispara.
  softcut._event_position = fn
end
function softcut.event_render(fn) softcut._event_render = fn end
-- Estado padrão e especificações de controle compatíveis com softcut.lua do norns.
-- Cada chamada retorna tabelas novas para evitar que scripts alterem defaults globais.
function softcut.defaults()
  local state = {}
  for voice = 1, softcut.VOICE_COUNT do
    local sends = {}
    local inputs = {0, 0}
    for i = 1, softcut.VOICE_COUNT do sends[i] = 0 end
    state[voice] = {
      enable = 0, play = 0, record = 0, buffer = (voice % 2) + 1,
      level = 0, pan = 0, level_input_cut = inputs, level_cut_cut = sends,
      rate = 1, loop_start = (voice - 1) * 2, loop_end = (voice - 1) * 2 + 1,
      loop = 1, fade_time = 0.0005, rec_level = 0, pre_level = 0,
      rec = 0, rec_offset = -0.00015, position = 0,
      pre_filter_fc = 16000, pre_filter_dry = 0, pre_filter_lp = 1,
      pre_filter_hp = 0, pre_filter_bp = 0, pre_filter_br = 0,
      pre_filter_fc_mod = 1, pre_filter_rq = 2,
      post_filter_fc = 12000, post_filter_fc_mod = 0, post_filter_dry = 0, post_filter_lp = 0,
      post_filter_hp = 0, post_filter_bp = 0, post_filter_br = 0,
      post_filter_rq = 2, level_slew_time = 0.001,
      rate_slew_time = 0.001, pan_slew_time = 0.001,
      recpre_slew_time = 0.001, phase_quant = 1, phase_offset = 0
    }
  end
  return state
end

function softcut.params()
  local specs = {}
  local function control(minimum, maximum, warp, step, default, units)
    return {type = "control", controlspec = controlspec.new(
      minimum, maximum, warp or "lin", step or 0, default, units or "")}
  end
  local function number(minimum, maximum, default)
    return {type = "number", min = minimum, max = maximum, default = default}
  end
  for voice = 1, softcut.VOICE_COUNT do
    local spec = {
      enable = number(0, 1, 0),
      level = control(0, 1, "lin", 0, 0.25),
      pan = control(-1, 1, "lin", 0, 0),
      level_input_cut = control(0, 1, "lin", 0, 0.5),
      level_cut_cut = control(0, 1, "lin", 0, 0),
      rate = control(-8, 8, "lin", 0, 0),
      loop_start = control(0, softcut.BUFFER_SIZE, "lin", 0, (voice - 1) * 2, "sec"),
      loop_end = control(0, softcut.BUFFER_SIZE, "lin", 0, (voice - 1) * 2 + 1, "sec"),
      loop = number(0, 1, 1),
      fade_time = control(0, 1, "lin", 0, 0),
      rec_level = control(0, 1, "lin", 0, 0),
      pre_level = control(0, 1, "lin", 0, 0),
      play = number(0, 1, 0),
      rec = number(0, 1, 0),
      rec_offset = number(-100, 100, -0.00015),
      position = control(0, softcut.BUFFER_SIZE, "lin", 0, (voice - 1) * 2, "sec"),
      pre_filter_fc = control(10, 12000, "exp", 1, 12000, "Hz"),
      pre_filter_fc_mod = control(0, 1, "lin", 0, 1),
      pre_filter_rq = control(0.0005, 8, "exp", 0, 2),
      pre_filter_lp = control(0, 1, "lin", 0, 1),
      pre_filter_hp = control(0, 1, "lin", 0, 0),
      pre_filter_bp = control(0, 1, "lin", 0, 0),
      pre_filter_br = control(0, 1, "lin", 0, 0),
      pre_filter_dry = control(0, 1, "lin", 0, 0),
      post_filter_fc = control(10, 12000, "exp", 1, 12000, "Hz"),
      post_filter_fc_mod = control(0, 1, "lin", 0, 0),
      post_filter_rq = control(0.0005, 8, "exp", 0, 2),
      post_filter_lp = control(0, 1, "lin", 0, 0),
      post_filter_hp = control(0, 1, "lin", 0, 0),
      post_filter_bp = control(0, 1, "lin", 0, 0),
      post_filter_br = control(0, 1, "lin", 0, 0),
      post_filter_dry = control(0, 1, "lin", 0, 0),
      level_slew_time = control(0, 8, "lin", 0, 0),
      pan_slew_time = control(0, 8, "lin", 0, 0),
      rate_slew_time = control(0, 8, "lin", 0, 0),
      recpre_slew_time = control(0, 8, "lin", 0, 0),
      phase_quant = control(0, 8, "lin", 0, 0.125),
      phase_offset = control(-8, 8, "lin", 0, 0)
    }
    local voiceIndex = voice
    for key, parameter in pairs(spec) do
      parameter.id = key
      parameter.name = "cut" .. voiceIndex .. key
      local setter = softcut[key]
      if key == "level_input_cut" then
        parameter.action = function(value) softcut.level_input_cut(1, voiceIndex, value) end
      elseif key ~= "level_cut_cut" and type(setter) == "function" then
        parameter.action = function(value) setter(voiceIndex, value) end
      end
    end
    specs[voice] = spec
  end
  return specs
end

function softcut.reset()
  softcut.buffer_clear()
  if audio and type(audio.level_adc_cut) == "function" then audio.level_adc_cut(1) end
  local state = softcut.defaults()
  softcut.poll_stop_phase()
  softcut.event_phase(nil)
  for v = 1, softcut.VOICE_COUNT do
    local d = state[v]
    softcut.enable(v, d.enable)
    softcut.play(v, d.play)
    softcut.buffer(v, d.buffer)
    softcut.position(v, d.position)
    softcut.rate(v, d.rate)
    softcut.level(v, d.level)
    softcut.pan(v, d.pan)
    softcut.loop(v, d.loop)
    softcut.loop_start(v, d.loop_start)
    softcut.loop_end(v, d.loop_end)
    softcut.fade_time(v, d.fade_time)
    softcut.rec(v, d.rec)
    softcut.rec_level(v, d.rec_level)
    softcut.pre_level(v, d.pre_level)
    softcut.rec_offset(v, d.rec_offset)
    softcut.pre_filter_fc(v, d.pre_filter_fc)
    softcut.pre_filter_fc_mod(v, d.pre_filter_fc_mod)
    softcut.pre_filter_rq(v, d.pre_filter_rq)
    softcut.pre_filter_lp(v, d.pre_filter_lp)
    softcut.pre_filter_hp(v, d.pre_filter_hp)
    softcut.pre_filter_bp(v, d.pre_filter_bp)
    softcut.pre_filter_br(v, d.pre_filter_br)
    softcut.pre_filter_dry(v, d.pre_filter_dry)
    softcut.post_filter_fc(v, d.post_filter_fc)
    softcut.post_filter_fc_mod(v, d.post_filter_fc_mod)
    softcut.post_filter_rq(v, d.post_filter_rq)
    softcut.post_filter_lp(v, d.post_filter_lp)
    softcut.post_filter_hp(v, d.post_filter_hp)
    softcut.post_filter_bp(v, d.post_filter_bp)
    softcut.post_filter_br(v, d.post_filter_br)
    softcut.post_filter_dry(v, d.post_filter_dry)
    softcut.level_slew_time(v, d.level_slew_time)
    softcut.pan_slew_time(v, d.pan_slew_time)
    softcut.rate_slew_time(v, d.rate_slew_time)
    softcut.recpre_slew_time(v, d.recpre_slew_time)
    softcut.phase_quant(v, d.phase_quant)
    softcut.phase_offset(v, d.phase_offset)
    for input = 1, 2 do
      softcut.level_input_cut(input, v, d.level_input_cut[input])
    end
    for destination = 1, softcut.VOICE_COUNT do
      softcut.level_cut_cut(v, destination, d.level_cut_cut[destination])
    end
  end
end
local function audio_route_unavailable(api)
  host.log("audio." .. tostring(api) .. ": roteamento de portas não está disponível no backend Android.", false)
  return false
end

audio = {
  -- Script-overridable callback: input L/R and final output L/R in crone's 0..64 IEC-60268 scale.
  vu = noop,
  -- Faust effects run only on the Java mixer; native scsynth output is rejected.
  rev_on = function()
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then
      host.log("audio.rev_on: DSP de efeitos ainda não está roteado no mixer nativo.", false)
      return false
    end
    return host.audio_fx("enabled", "reverb", true)
  end,
  rev_off = function()
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then return false end
    return host.audio_fx("enabled", "reverb", false)
  end,
  rev_param = function(name, value)
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then return false end
    return host.audio_fx("parameter", "reverb", tostring(name), tonumber(value))
  end,
  level_rev_dac = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then return false end
    return host.audio_fx("mix", "reverb", math.max(0, math.min(8, level)))
  end,
  -- Independent pre-FX sends. Softcut is tapped separately from the master output in Java mode.
  level_monitor_rev = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then return false end
    return host.audio_fx("send", "reverb", "monitor", math.max(0, math.min(1, level)))
  end,
  level_eng_rev = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then return false end
    return host.audio_fx("send", "reverb", "engine", math.max(0, math.min(1, level)))
  end,
  level_tape_rev = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then return false end
    return host.audio_fx("send", "reverb", "tape", math.max(0, math.min(1, level)))
  end,
  comp_on = function()
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then
      host.log("audio.comp_on: DSP de efeitos ainda não está roteado no mixer nativo.", false)
      return false
    end
    return host.audio_fx("enabled", "compressor", true)
  end,
  comp_off = function()
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then return false end
    return host.audio_fx("enabled", "compressor", false)
  end,
  comp_mix = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then return false end
    return host.audio_fx("mix", "compressor", math.max(0, math.min(1, level)))
  end,
  comp_param = function(name, value)
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then return false end
    return host.audio_fx("parameter", "compressor", tostring(name), tonumber(value))
  end,
  -- ADC input gain affects the captured input before analysis and routing. Native mode is rejected.
  level_adc = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    if engine and engine._native_state == "native" then
      host.log("audio.level_adc: controle da entrada ADC nativa ainda indisponível.", false)
      return false
    end
    return host.audio_level_adc(math.max(0, math.min(1, level)))
  end,
  -- Master Java output gain. Native scsynth output is deliberately not claimed here.
  level_dac = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    if engine and engine._native_state == "native" then
      host.log("audio.level_dac: controle do DAC nativo ainda indisponível.", false)
      return false
    end
    return host.audio_level_dac(math.max(0, math.min(1, level)))
  end,
  -- Softcut master DAC gain applies only while the Java output owns Softcut; it never enables voices.
  level_cut = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    return host.audio_level_cut(math.max(0, math.min(1, level)))
  end,
  -- Engine output gain applies only to the Java SynthCore; native scsynth routing is not claimed.
  level_eng = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    if engine and (engine._native_state == "native" or engine._native_state == "loading") then
      host.log("audio.level_eng: ganho do engine nativo ainda indisponível.", false)
      return false
    end
    return host.audio_level_eng(math.max(0, math.min(1, level)))
  end,
  -- Monitor is a real mono ADC -> stereo Java output mix; native scsynth is rejected.
  level_monitor = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    if engine and engine._native_state == "native" then
      host.log("audio.level_monitor: monitor ADC nativo ainda indisponível.", false)
      return false
    end
    return host.audio_level_monitor(math.max(0, math.min(1, level)))
  end,
  -- Android currently exposes one mono ADC channel: mono mirrors it to L/R.
  monitor_mono = function()
    if engine and engine._native_state == "native" then
      host.log("audio.monitor_mono: monitor ADC nativo ainda indisponível.", false)
      return false
    end
    return host.audio_monitor_mode(false)
  end,
  -- Do not claim stereo monitoring while the capture backend supplies only mono.
  monitor_stereo = function()
    if engine and engine._native_state == "native" then
      host.log("audio.monitor_stereo: captura ADC estéreo ainda indisponível.", false)
      return false
    end
    return host.audio_monitor_mode(true)
  end,
  -- Pitch analysis is opt-in, as on norns; disabling it clears its history and saves CPU.
  pitch_on = function() return host.audio_pitch_on() end,
  pitch_off = function() return host.audio_pitch_off() end,
  -- Hardware-specific controls unavailable on Android are explicit, fail-closed APIs.
  headphone_gain = function()
    host.log("audio.headphone_gain: ganho físico de fone não está disponível pelo backend Android.", false)
    return false
  end,
  restart = function()
    host.log("audio.restart: o ciclo de vida de áudio Android não permite reiniciar sclang/scsynth por esta API.", false)
    return false
  end,
  -- Isolated Java Softcut post-pan/pre-master send into the Faust reverb bus.
  -- Native scsynth remains fail-closed until its output can be tapped without duplicating audio.
  level_cut_rev = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    if (engine and (engine._native_state == "native" or engine._native_state == "loading"))
        or softcut._native_output_owner == true
        or softcut._native_output_uncertain == true
        or softcut._native_state == "fallback_pending" then
      host.log("audio.level_cut_rev: envio Softcut para reverb indisponível fora da saída Java confirmada.", false)
      return false
    end
    return host.audio_fx("send", "reverb", "softcut", math.max(0, math.min(1, level)))
  end,
  -- WAV metadata only: the file browser never loads the sample payload.
  file_info = function(file) return host.audio_file_info(tostring(file)) end,
  -- Norns tape capacity is measured in seconds of 48 kHz stereo PCM16.
  tape_compute_diskfree = function(reserve_s)
    reserve_s = tonumber(reserve_s) or 250
    if reserve_s ~= reserve_s or reserve_s == math.huge or reserve_s == -math.huge then
      reserve_s = 250
    end
    reserve_s = math.max(0, reserve_s)
    local bytes_per_second = 48000 * 2 * 2
    local available = host.disk_free_bytes() - reserve_s * bytes_per_second
    return math.max(0, math.floor(available / bytes_per_second))
  end,
  -- Tape: reprodução e gravação WAV estéreo em disco, sem bloquear a thread de áudio.
  tape_play_open = function(file) return host.tape_play_open(tostring(file)) end,
  tape_play_start = function() return host.tape_play_start() end,
  tape_play_pause = function(paused) return host.tape_play_pause(sc_flag(paused)) end,
  tape_play_stop = function() return host.tape_play_stop() end,
  tape_play_loop = function(enabled) return host.tape_play_loop(sc_flag(enabled)) end,
  -- Upstream convenience controls pause/resume both playback and recording together.
  tape_pause = function()
    local playback = host.tape_play_pause(true)
    local recording = host.tape_record_pause(true)
    return playback and recording
  end,
  tape_resume = function()
    local playback = host.tape_play_pause(false)
    local recording = host.tape_record_pause(false)
    return playback and recording
  end,
  tape_loop = function(enabled) return host.tape_play_loop(sc_flag(enabled)) end,
  tape_record_open = function(file)
    if engine and engine._native_state == "native" then
      host.log("Tape: gravação exige o engine Java até a captura do mix nativo ser validada.", false)
      return false
    end
    return host.tape_record_open(tostring(file))
  end,
  tape_record_start = function()
    if engine and engine._native_state == "native" then return false end
    local started = host.tape_record_start()
    if started then softcut._tape_record_active = true end
    return started
  end,
  tape_record_pause = function(paused) return host.tape_record_pause(sc_flag(paused)) end,
  tape_record_stop = function()
    local stopped = host.tape_record_stop()
    softcut._tape_record_active = false
    return stopped
  end,
  -- Volume do Tape na saída principal, independente do envio para Softcut.
  level_tape = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    return host.tape_level(math.max(0, math.min(1, level)))
  end,
  -- Ganho global da entrada ADC, antes do ganho individual level_input_cut.
  level_adc_cut = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then
      return false
    end
    level = math.max(0, math.min(2, level))
    softcut._native_adc_level = level
    host.softcut(0, "adc_level", level)
    for channel = 1, 2 do
      local recorder = softcut._native_recorders[channel]
      if recorder then sc_api.set(recorder, {adc_level = level}) end
    end
    return true
  end,
  -- API Norns global: encaminha o engine ativo, Java ou SuperCollider, ao Softcut.
  level_eng_cut = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then
      return false
    end
    level = math.max(0, math.min(1, level))
    local nativeEngine = engine and engine._native_state == "native"
    local targetSource = nativeEngine and "native" or "java"
    if level > 0 and nativeEngine and (softcut._tape_cut_level or 0) > 0 then
      host.log("audio.level_eng_cut: desative Tape->Softcut antes de usar o envio nativo.", false)
      return false
    end
    if level > 0 and nativeEngine and softcut._tape_record_active then
      host.log("audio.level_eng_cut: pare a gravação Tape antes de ativar o engine nativo.", false)
      return false
    end
    if level > 0 and nativeEngine and softcut._native_state ~= "ready_muted" then
      host.log("audio.level_eng_cut: engine nativo exige o gravador Softcut nativo pronto.", false)
      return false
    end
    if targetSource ~= softcut._engine_cut_source then
      local voiceRecording = false
      for voice = 1, softcut.VOICE_COUNT do
        local params = softcut._native_params[voice]
        if params and params.rec ~= 0 then voiceRecording = true; break end
      end
      if voiceRecording then
        if level == 0 then
          -- É seguro silenciar durante a gravação, mas a fonte só muda depois do stop.
          softcut._engine_cut_level = 0
          for voice = 1, softcut.VOICE_COUNT do
            host.softcut(voice, "engine_level", 0)
          end
          for channel = 1, 2 do
            local recorder = softcut._native_recorders[channel]
            if recorder then sc_api.set(recorder, {engine_level = 0}) end
          end
          return true
        end
        host.log("audio.level_eng_cut: pare a gravação Softcut antes de trocar a fonte do engine.", false)
        return false
      end
    end
    if level > 0 then
      for channel = 1, 2 do
        local state = softcut._native_supervisor
            and softcut._native_supervisor.recorders
            and softcut._native_supervisor.recorders[channel]
        if softcut._native_recording[channel]
            or (state and (state.recording == true or state.recovery_pending == true
              or state.stop_requested_at ~= nil)) then
          host.log("audio.level_eng_cut: aguarde a gravação terminar e sincronizar.", false)
          return false
        end
      end
    end
    softcut._engine_cut_level = level
    softcut._engine_cut_source = targetSource
    local javaLevel = nativeEngine and 0 or level
    local nativeLevel = nativeEngine and level or 0
    for voice = 1, softcut.VOICE_COUNT do
      host.softcut(voice, "engine_level", javaLevel)
    end
    for channel = 1, 2 do
      local recorder = softcut._native_recorders[channel]
      if recorder then sc_api.set(recorder, {engine_level = nativeLevel}) end
      local owner = softcut._native_record_owner[channel]
      local params = owner and softcut._native_params[owner]
      if nativeEngine and nativeLevel > 0 and owner and params
          and params.rec ~= 0 and not softcut._native_recording[channel] then
        softcut_native_start_recording(channel, owner)
      end
    end
    return true
  end,
  -- Envio independente do Tape para a entrada de gravação do Softcut.
  level_tape_cut = function(level)
    level = tonumber(level)
    if not level or level ~= level or level == math.huge or level == -math.huge then return false end
    level = math.max(0, math.min(1, level))
    -- The native recorder has no Tape input bus yet. Keep Java Softcut as the
    -- single buffer authority while Tape is sent, after confirming native mute.
    if level > 0 and engine and engine._native_state == "native"
        and softcut._engine_cut_source == "native" and (softcut._engine_cut_level or 0) > 0 then
      host.log("Tape->Softcut indisponível enquanto o envio do engine nativo estiver ativo.", false)
      return false
    end
    if level > 0 and softcut._native_state == "fallback_pending" then
      return false
    elseif level > 0 and softcut._native_state ~= "java" then
      if softcut._native_output_owner or softcut._native_output_uncertain then
        if softcut_native_has_active_recording() then
          host.log("Tape->Softcut: pare e sincronize a gravação Softcut nativa antes de trocar a saída.", false)
          return false
        end
      end
      if not softcut_native_fallback("envio Tape->Softcut usa o roteamento Java") then return false end
    end
    local accepted = host.tape_level_cut(level)
    if accepted then softcut._tape_cut_level = level end
    return accepted
  end,
  -- Norns JACK port topology has no equivalent in the current Android audio backend.
  default_softcut_routes = {},
  default_supercollider_routes = {},
  default_system_routes = {},
  _default_routing_altered = false,
  input_ports = function() return {} end,
  output_ports = function() return {} end,
  port_connections = function() return {} end,
  port_connect = function() return audio_route_unavailable("port_connect") end,
  port_disconnect = function() return audio_route_unavailable("port_disconnect") end,
  routing_is_altered = function() return audio._default_routing_altered == true end,
  routing_disconnect_all = function() return audio_route_unavailable("routing_disconnect_all") end,
  routing_default = function() return audio_route_unavailable("routing_default") end,
  _routing_apply = function(routes, operation)
    if type(routes) ~= "table" or type(operation) ~= "function" then return false end
    local changed = false
    for source, destination in pairs(routes) do
      if operation(source, destination) then changed = true end
    end
    return changed
  end,
  routing_connect = function(routes) return audio._routing_apply(routes, audio.port_connect) end,
  routing_disconnect = function(routes) return audio._routing_apply(routes, audio.port_disconnect) end,
  system_connect = function() return audio_route_unavailable("system_connect") end,
  system_disconnect = function() return audio_route_unavailable("system_disconnect") end,
  softcut_connect = function() return audio_route_unavailable("softcut_connect") end,
  softcut_disconnect = function() return audio_route_unavailable("softcut_disconnect") end,
  supercollider_connect = function() return audio_route_unavailable("supercollider_connect") end,
  supercollider_disconnect = function() return audio_route_unavailable("supercollider_disconnect") end,
  with_change_tracking_disabled = function(fn)
    if type(fn) ~= "function" then return false end
    local result = fn()
    audio._default_routing_altered = false
    return result
  end,
  set_audio_level = function(value)
    value = tonumber(value)
    if not value or value ~= value or value == math.huge or value == -math.huge then return false end
    value = math.max(0, math.min(64, value))
    local current = tonumber(norns.state.out) or 64
    if value == current then return true end
    -- Do not persist a requested level if the active backend rejects it.
    local accepted = audio.level_dac(value / 64)
    if accepted then norns.state.out = value end
    return accepted
  end,
  adjust_output_level = function(delta)
    delta = tonumber(delta)
    if not delta or delta ~= delta or delta == math.huge or delta == -math.huge then return false end
    return audio.set_audio_level((tonumber(norns.state.out) or 64) + delta)
  end,
  add_params = function()
    -- Mirror the upstream global mixer controls in the same ParamSet shown by
    -- ParamsView. Unsupported hardware-only controls (headphone gain and
    -- stereo ADC monitoring) are intentionally not advertised on Android.
    local mix = norns.state.mix
    local function dbamp(value)
      if value == -math.huge then return 0 end
      return 10 ^ (value / 20)
    end
    local function add_control(id, name, key, spec, apply)
      params:add_control(id, name, spec)
      params:set_action(id, function(value)
        local previous = mix[key]
        local accepted = apply(value)
        if accepted == false then
          params:set(id, previous, true)
          return false
        end
        mix[key] = value
        return true
      end)
      params:set_save(id, false)
    end
    local function add_db(id, name, key, default, apply, max_db)
      local value = mix[key]
      if value == nil then value = default; mix[key] = value end
      add_control(id, name, key,
        controlspec.new(-math.huge, max_db or 0, "db", 0, value, "dB"),
        function(db) return apply(dbamp(db)) end)
    end
    local function add_option(id, name, key, options, default, apply)
      if mix[key] == nil then mix[key] = default end
      params:add_option(id, name, options, mix[key])
      params:set_action(id, function(value)
        local previous = mix[key]
        local accepted = apply(value)
        if accepted == false then
          params:set(id, previous, true)
          return false
        end
        mix[key] = value
        return true
      end)
      params:set_save(id, false)
    end

    params:add_group("LEVELS", 9)
    add_db("output_level", "output", "output", 0, audio.level_dac)
    add_db("input_level", "input", "input", 0, audio.level_adc)
    add_db("monitor_level", "monitor", "monitor", 0, audio.level_monitor)
    add_db("engine_level", "engine", "engine", 0, audio.level_eng)
    add_db("softcut_level", "softcut", "cut", 0, audio.level_cut)
    add_db("tape_level", "tape", "tape", 0, audio.level_tape)
    params:add_separator("monitoring_separator", "monitoring")
    add_option("monitor_mode", "monitor mode", "monitor_mode", {"MONO"}, 1,
      function() return audio.monitor_mono() end)
    -- Keep the upstream parameter ID for scripts and presets, but hide the
    -- control because Android exposes no safe headphone hardware-gain API.
    params:add_number("headphone_gain", "headphone gain", 0, 63, mix.headphone_gain)
    params:set_action("headphone_gain", function(value)
      local previous = mix.headphone_gain
      if audio.headphone_gain(value) == false then
        params:set("headphone_gain", previous, true)
        return false
      end
      mix.headphone_gain = value
      return true
    end)
    params:set_save("headphone_gain", false)
    params:hide("headphone_gain")

    params:add_group("REVERB", 11)
    add_option("reverb", "reverb", "aux", {"OFF", "ON"}, 2,
      function(value)
        if value == 1 then return audio.rev_off() end
        return audio.rev_on()
      end)
    add_db("rev_eng_input", "input engine", "rev_eng_input", -9, audio.level_eng_rev)
    add_db("rev_cut_input", "input softcut", "rev_cut_input", -9, audio.level_cut_rev)
    add_db("rev_monitor_input", "input monitor", "rev_monitor_input", -math.huge, audio.level_monitor_rev)
    add_db("rev_tape_input", "input tape", "rev_tape_input", -math.huge, audio.level_tape_rev)
    add_db("rev_return_level", "return level", "rev_return_level", 0, audio.level_rev_dac)
    add_control("rev_pre_delay", "pre delay", "rev_pre_delay",
      controlspec.new(20, 100, "lin", 0, mix.rev_pre_delay, "ms"),
      function(value) return audio.rev_param("pre_del", value) end)
    add_control("rev_lf_fc", "lf fc", "rev_lf_fc",
      controlspec.new(50, 1000, "exp", 0, mix.rev_lf_fc, "Hz"),
      function(value) return audio.rev_param("lf_fc", value) end)
    add_control("rev_low_time", "low time", "rev_low_time",
      controlspec.new(0.1, 16, "lin", 0, mix.rev_low_time, "s"),
      function(value) return audio.rev_param("low_rt60", value) end)
    add_control("rev_mid_time", "mid time", "rev_mid_time",
      controlspec.new(0.1, 16, "lin", 0, mix.rev_mid_time, "s"),
      function(value) return audio.rev_param("mid_rt60", value) end)
    add_control("rev_hf_damping", "hf damping", "rev_hf_damping",
      controlspec.new(1500, 20000, "exp", 0, mix.rev_hf_damping, "Hz"),
      function(value) return audio.rev_param("hf_damp", value) end)

    params:add_group("COMPRESSOR", 8)
    add_option("compressor", "compressor", "ins", {"OFF", "ON"}, 1,
      function(value)
        if value == 1 then return audio.comp_off() end
        return audio.comp_on()
      end)
    add_control("comp_mix", "mix", "comp_mix",
      controlspec.new(0, 1, "lin", 0, mix.comp_mix, ""),
      audio.comp_mix)
    add_control("comp_ratio", "ratio", "comp_ratio",
      controlspec.new(1, 20, "lin", 0, mix.comp_ratio, ""),
      function(value) return audio.comp_param("ratio", value) end)
    add_control("comp_threshold", "threshold", "comp_threshold",
      controlspec.new(-100, 10, "db", 0, mix.comp_threshold, "dB"),
      function(value) return audio.comp_param("threshold", value) end)
    add_control("comp_attack", "attack", "comp_attack",
      controlspec.new(1, 1000, "exp", 0, mix.comp_attack, "ms"),
      function(value) return audio.comp_param("attack", value * 0.001) end)
    add_control("comp_release", "release", "comp_release",
      controlspec.new(1, 1000, "exp", 0, mix.comp_release, "ms"),
      function(value) return audio.comp_param("release", value * 0.001) end)
    add_control("comp_pre_gain", "pre gain", "comp_pre_gain",
      controlspec.new(-20, 60, "db", 0, mix.comp_pre_gain, "dB"),
      function(value) return audio.comp_param("gain_pre", value) end)
    add_control("comp_post_gain", "post gain", "comp_post_gain",
      controlspec.new(-20, 60, "db", 0, mix.comp_post_gain, "dB"),
      function(value) return audio.comp_param("gain_post", value) end)

    params:add_group("SOFTCUT", 3)
    add_db("cut_input_adc", "input adc", "cut_input_adc", 0, audio.level_adc_cut)
    add_db("cut_input_eng", "input engine", "cut_input_eng", 0, audio.level_eng_cut)
    add_db("cut_input_tape", "input tape", "cut_input_tape", -math.huge, audio.level_tape_cut)
    return true
  end,
}
 
-- Tape state mirrors the upstream nested snapshot and numeric state constants.
-- Java events are serialized onto the Lua executor before subscribers are invoked.
local TAPE_PLAY_STATE_EMPTY = 0
local TAPE_PLAY_STATE_READY = 1
local TAPE_PLAY_STATE_PLAYING = 2
local TAPE_PLAY_STATE_PAUSED = 3
local TAPE_REC_STATE_EMPTY = 0
local TAPE_REC_STATE_READY = 1
local TAPE_REC_STATE_RECORDING = 2
local TAPE_REC_STATE_PAUSED = 3

local function tape_filename(path)
  if type(path) ~= "string" then return nil end
  path = string.gsub(path, "\\", "/")
  return string.match(path, "([^/]+)$")
end

local initial_tape_state = host.tape_state()
audio.tape = {
  _state = {
    play = {
      state = initial_tape_state.play_state or TAPE_PLAY_STATE_EMPTY,
      pos = initial_tape_state.play_pos_s or 0,
      len = initial_tape_state.play_len_s or 0,
      loop = initial_tape_state.loop_enabled == nil or initial_tape_state.loop_enabled == true,
      file = tape_filename(initial_tape_state.play_file)
    },
    rec = {
      state = initial_tape_state.rec_state or TAPE_REC_STATE_EMPTY,
      pos = initial_tape_state.rec_pos_s or 0,
      file = tape_filename(initial_tape_state.rec_file)
    }
  },
  _subscribers = {},
  constants = {
    TAPE_PLAY_STATE_EMPTY = TAPE_PLAY_STATE_EMPTY,
    TAPE_PLAY_STATE_READY = TAPE_PLAY_STATE_READY,
    TAPE_PLAY_STATE_PLAYING = TAPE_PLAY_STATE_PLAYING,
    TAPE_PLAY_STATE_PAUSED = TAPE_PLAY_STATE_PAUSED,
    TAPE_REC_STATE_EMPTY = TAPE_REC_STATE_EMPTY,
    TAPE_REC_STATE_READY = TAPE_REC_STATE_READY,
    TAPE_REC_STATE_RECORDING = TAPE_REC_STATE_RECORDING,
    TAPE_REC_STATE_PAUSED = TAPE_REC_STATE_PAUSED
  }
}

local function tape_state_copy(state)
  local copy = {}
  for key, value in pairs(state or {}) do
    if type(value) == "table" then
      local nested = {}
      for nested_key, nested_value in pairs(value) do nested[nested_key] = nested_value end
      copy[key] = nested
    else
      copy[key] = value
    end
  end
  return copy
end

local function tape_state_publish(state)
  audio.tape._state = tape_state_copy(state)
  for key, fn in pairs(audio.tape._subscribers) do
    if type(fn) == "function" then
      local ok, err = pcall(fn, tape_state_copy(audio.tape._state))
      if not ok then host.log("audio.tape subscriber: " .. tostring(err), false) end
    else
      audio.tape._subscribers[key] = nil
    end
  end
end

function audio.tape.get_state()
  return tape_state_copy(audio.tape._state)
end
function audio.tape.subscribe(key, fn)
  if key == nil or type(fn) ~= "function" then return false end
  audio.tape._subscribers[key] = fn
  return true
end
function audio.tape.unsubscribe(key)
  if key == nil or audio.tape._subscribers[key] == nil then return false end
  audio.tape._subscribers[key] = nil
  return true
end
function audio.tape._update_state(state)
  local next_state = tape_state_copy(audio.tape._state)
  local play = next_state.play
  local rec = next_state.rec
  if state.play_state ~= nil then play.state = state.play_state end
  if state.play_pos_s ~= nil then play.pos = state.play_pos_s end
  if state.play_len_s ~= nil then play.len = state.play_len_s end
  if state.loop_enabled ~= nil then play.loop = state.loop_enabled == true or state.loop_enabled == 1 end
  if state.play_file ~= nil then play.file = tape_filename(state.play_file) end
  if state.rec_state ~= nil then rec.state = state.rec_state end
  if state.rec_pos_s ~= nil then rec.pos = state.rec_pos_s end
  if state.rec_file ~= nil then rec.file = tape_filename(state.rec_file) end
  tape_state_publish(next_state)
end

-- Upstream callback entry points retained for scripts and engine integrations.
function audio.tape._on_status(play_state, play_pos_s, play_len_s, rec_state, rec_pos_s, loop_enabled)
  audio.tape._update_state({
    play_state = play_state, play_pos_s = play_pos_s, play_len_s = play_len_s,
    rec_state = rec_state, rec_pos_s = rec_pos_s, loop_enabled = loop_enabled
  })
end
function audio.tape._on_play_file(path)
  local next_state = tape_state_copy(audio.tape._state)
  next_state.play.file = tape_filename(path)
  tape_state_publish(next_state)
end
function audio.tape._on_rec_file(path)
  local next_state = tape_state_copy(audio.tape._state)
  next_state.rec.file = tape_filename(path)
  tape_state_publish(next_state)
end

function audio.tape_is_recording()
  return audio.tape.get_state().rec.state == TAPE_REC_STATE_RECORDING
end

poll = {available = true, _instances = {}}
poll._names = {"amp_in_l", "amp_in_r", "amp_out_l", "amp_out_r",
  "cpu_avg", "cpu_peak", "pitch_in_l", "pitch_in_r"}

function poll.list_names()
  local names = {}
  for i = 1, #poll._names do names[i] = poll._names[i] end
  return names
end

function poll.set(name, callback)
  name = tostring(name or "")
  local poll_id
  for i = 1, #poll._names do
    if poll._names[i] == name then poll_id = i; break end
  end
  if not poll_id then
    host.log("poll.set: poll '" .. name .. "' não está disponível.", false)
    return nil
  end
  local instance = {id = poll_id, name = name, time = 0.1,
    callback = type(callback) == "function" and callback or nil,
    _clock = nil, _running = false}
  function instance:update(fn)
    if type(fn) == "function" then self.callback = fn end
    local value = host.poll_value(self.name)
    if type(self.callback) == "function" then safe(self.callback, value) end
    return value
  end
  function instance:start()
    if self._clock then return false end
    self._running = true
    self._clock = clock.run(function()
      while self._running do
        self:update()
        clock.sleep(math.max(0.01, tonumber(self.time) or 0.1))
      end
    end)
    return true
  end
  function instance:stop()
    self._running = false
    if self._clock then
      local id = self._clock
      self._clock = nil
      clock.cancel(id)
    end
    return true
  end
  table.insert(poll._instances, instance)
  return instance
end

function poll.clear_all()
  for i = 1, #poll._instances do poll._instances[i]:stop() end
  poll._instances = {}
  return true
end

-- Physical Android keyboard mapped to the norns keyboard module.
keyboard = {available = true, state = {}, _code_chars = {}}
keyboard.code = noop
keyboard.char = noop
function keyboard.clear()
  keyboard.code = noop
  keyboard.char = noop
end
function keyboard.shift()
  return keyboard.state.LEFTSHIFT == true or keyboard.state.RIGHTSHIFT == true
end
function keyboard.alt()
  return keyboard.state.LEFTALT == true or keyboard.state.RIGHTALT == true
end
function keyboard.altgr() return keyboard.state.RIGHTALT == true end
function keyboard.ctrl()
  return keyboard.state.LEFTCTRL == true or keyboard.state.RIGHTCTRL == true
end
function keyboard.meta()
  return keyboard.state.LEFTMETA == true or keyboard.state.RIGHTMETA == true
end
function keyboard.code_to_char(code)
  if code == nil then return nil end
  local chars = keyboard._code_chars[tostring(code)]
  if type(chars) ~= "table" then return chars end
  if keyboard.altgr() then
    return keyboard.shift() and chars.shift_altgr or chars.altgr
  end
  return keyboard.shift() and chars.shift or chars.normal
end
-- OSC /remote/brd uses the Linux evdev key-code namespace used by norns.
function keyboard.process(device, code, value)
  code, value = tonumber(code), tonumber(value)
  if not code or not value then return false end
  for name, evdev_code in pairs(hid.codes) do
    if string.sub(name, 1, 4) == "KEY_" and evdev_code == code then
      local logical = string.sub(name, 5)
      local aliases = {
        ESC = "ESCAPE", LEFTBRACE = "LEFTBRACKET",
        RIGHTBRACE = "RIGHTBRACKET", DOT = "PERIOD",
        KPASTERISK = "STAR"
      }
      logical = aliases[logical] or logical
      return _norns.keyboard_event(logical, value, keyboard.code_to_char(logical),
        keyboard.shift(), keyboard.alt(), keyboard.ctrl(), keyboard.meta())
    end
  end
  return false
end

gamepad = {
  available = true, connected = false, devices = {}, state = {
    DPDOWN = false, DPUP = false, DPLEFT = false, DPRIGHT = false,
    LDOWN = false, LUP = false, LLEFT = false, LRIGHT = false,
    RDOWN = false, RUP = false, RLEFT = false, RRIGHT = false,
    TLEFT = false, TRIGHT = false, DOWN = false, UP = false, LEFT = false, RIGHT = false
  },
  dpad = noop, analog = noop, axis = noop, button = noop
}
local gamepad_axis_states = {
  dpadx = {"DPLEFT", "DPRIGHT"}, dpady = {"DPUP", "DPDOWN"},
  leftx = {"LLEFT", "LRIGHT"}, lefty = {"LUP", "LDOWN"},
  rightx = {"RLEFT", "RRIGHT"}, righty = {"RUP", "RDOWN"}
}
function gamepad.clear()
  gamepad.dpad, gamepad.analog, gamepad.axis, gamepad.button = noop, noop, noop, noop
end
function gamepad.up() return gamepad.state.DPUP or gamepad.state.LUP end
function gamepad.down() return gamepad.state.DPDOWN or gamepad.state.LDOWN end
function gamepad.left() return gamepad.state.DPLEFT or gamepad.state.LLEFT end
function gamepad.right() return gamepad.state.DPRIGHT or gamepad.state.LRIGHT end
local gamepad_state_keys = {
  "DPDOWN","DPUP","DPLEFT","DPRIGHT","LDOWN","LUP","LLEFT","LRIGHT",
  "RDOWN","RUP","RLEFT","RRIGHT","TLEFT","TRIGHT","DOWN","UP","LEFT","RIGHT"
}
local function gamepad_rebuild_state()
  for _, key in ipairs(gamepad_state_keys) do gamepad.state[key] = false end
  for _, state in pairs(gamepad._device_state or {}) do
    for key in pairs(state.buttons or {}) do gamepad.state[key] = false end
  end
  for _, state in pairs(gamepad._device_state or {}) do
    for key, pressed in pairs(state.buttons or {}) do
      if pressed then gamepad.state[key] = true end
    end
    for axis, value in pairs(state.axes or {}) do
      local sign = value > 0.5 and 1 or (value < -0.5 and -1 or 0)
      local keys = gamepad_axis_states[axis]
      if keys then
        if sign < 0 then gamepad.state[keys[1]] = true end
        if sign > 0 then gamepad.state[keys[2]] = true end
      elseif axis == "triggerleft" and value > 0.5 then gamepad.state.TLEFT = true
      elseif axis == "triggerright" and value > 0.5 then gamepad.state.TRIGHT = true end
    end
  end
  gamepad.state.UP = gamepad.state.DPUP or gamepad.state.LUP
  gamepad.state.DOWN = gamepad.state.DPDOWN or gamepad.state.LDOWN
  gamepad.state.LEFT = gamepad.state.DPLEFT or gamepad.state.LLEFT
  gamepad.state.RIGHT = gamepad.state.DPRIGHT or gamepad.state.LRIGHT
end
function _norns.gamepad_device(id, name, connected)
  id = tonumber(id) or -1
  gamepad._device_state = gamepad._device_state or {}
  if connected then
    gamepad.devices[id] = gamepad.devices[id] or {id = id}
    gamepad.devices[id].name = tostring(name or "Android Gamepad")
    gamepad.devices[id].connected = true
    gamepad._device_state[id] = gamepad._device_state[id] or {buttons = {}, axes = {}}
  else
    local removed_state = gamepad._device_state[id]
    local released_buttons = {}
    for name, pressed in pairs(removed_state and removed_state.buttons or {}) do
      if pressed then released_buttons[name] = true end
    end
    gamepad.devices[id] = nil
    gamepad._device_state[id] = nil
    if gamepad._axis_signs then
      local prefix = tostring(id) .. ":"
      for key in pairs(gamepad._axis_signs) do
        if string.sub(key, 1, #prefix) == prefix then gamepad._axis_signs[key] = nil end
      end
    end
    gamepad_rebuild_state()
    -- A disconnected controller cannot deliver key-up events. Synthesize a
    -- release only when no other connected controller still holds that key.
    for name in pairs(released_buttons) do
      local held_elsewhere = false
      for _, state in pairs(gamepad._device_state) do
        if state.buttons and state.buttons[name] then held_elsewhere = true; break end
      end
      if not held_elsewhere and type(gamepad.button) == "function" then
        safe(gamepad.button, name, 0)
      end
    end
  end
  gamepad.connected = next(gamepad.devices) ~= nil
  return true
end
local function gamepad_device(id, name)
  id = tonumber(id) or -1
  if not gamepad.devices[id] then _norns.gamepad_device(id, name, true) end
  gamepad._device_state = gamepad._device_state or {}
  gamepad._device_state[id] = gamepad._device_state[id] or {buttons = {}, axes = {}}
  return id, gamepad._device_state[id]
end
function _norns.gamepad_button(name, value, id, device_name)
  local deviceId, state = gamepad_device(id, device_name)
  if type(name) ~= "string" or name == "" then return false end
  local numeric = tonumber(value) or 0
  state.buttons[name] = numeric > 0
  gamepad_rebuild_state()
  if type(gamepad.button) == "function" then safe(gamepad.button, name, numeric) end
  return true
end
function _norns.gamepad_axis(sensor_axis, value, half_reso, id, device_name)
  local deviceId, state = gamepad_device(id, device_name)
  if type(sensor_axis) ~= "string" or not gamepad_axis_states[sensor_axis]
      and sensor_axis ~= "triggerleft" and sensor_axis ~= "triggerright" then
    return false
  end
  local v = tonumber(value)
  if not v or v ~= v or v == math.huge or v == -math.huge then return false end
  v = math.max(-1, math.min(1, v))
  state.axes[sensor_axis] = v
  gamepad_rebuild_state()
  if type(gamepad.analog) == "function" then safe(gamepad.analog, sensor_axis, v, tonumber(half_reso) or 1) end
  gamepad._axis_signs = gamepad._axis_signs or {}
  local axisKey = tostring(deviceId) .. ":" .. sensor_axis
  local sign = v > 0.5 and 1 or (v < -0.5 and -1 or 0)
  if gamepad._axis_signs[axisKey] ~= sign then
    gamepad._axis_signs[axisKey] = sign
    if type(gamepad.axis) == "function" then safe(gamepad.axis, sensor_axis, sign) end
    if sensor_axis == "dpadx" or sensor_axis == "dpady" then
      local axis = sensor_axis == "dpadx" and "X" or "Y"
      if type(gamepad.dpad) == "function" then safe(gamepad.dpad, axis, sign) end
    end
  end
  return true
end

-- Android USB CDC-ACM serial backend. This is deliberately limited to
-- standard CDC-ACM devices; FTDI/CP210x/CH340 vendor protocols are not claimed.
-- Android USB CDC-ACM serial transport, shaped like the norns serial module.
-- This backend supports CDC-ACM 8-N-1 only; vendor-specific UART drivers are separate.
serial = {
  _handlers = {}, _pending = {}, _ports = {}, devices = {}, connected = false,
  speed = {
    B0=0, B50=1, B75=2, B110=3, B134=4, B150=5, B200=6, B300=7,
    B600=8, B1200=9, B1800=10, B2400=11, B4800=12, B9600=13,
    B19200=14, B38400=15, B57600=4097, B115200=4098, B230400=4099,
    B460800=4100, B500000=4101, B576000=4102, B921600=4103,
    B1000000=4104, B1152000=4105, B1500000=4106, B2000000=4107,
    B2500000=4108, B3000000=4109, B3500000=4110, B4000000=4111
  },
  cc = {VINTR=0,VQUIT=1,VERASE=2,VKILL=3,VEOF=4,VTIME=5,VMIN=6,VSTART=8,VSTOP=9,VSUSP=10,VEOL=11},
  iflag = {IGNBRK=1,BRKINT=2,IGNPAR=4,PARMRK=8,INPCK=16,ISTRIP=32,INCLR=64,IGNCR=128,ICRNL=256,IUCLC=512,IXON=1024,IXANY=2048,IXOFF=4096,IMAXBEL=8192,IUTF8=16384},
  oflag = {OPOST=1,OLCUC=2,ONLCR=4,OCRNL=8,ONOCR=16,ONLRET=32,OFILL=64,OFDEL=128,VTDLY=16384,VT0=0,VT1=16384},
  cflag = {CSIZE=48,CS5=0,CS6=16,CS7=32,CS8=48,CSTOPB=64,CREAD=128,PARENB=256,PARODD=512,HUPCL=1024,CLOCAL=2048},
  lflag = {ISIG=1,ICANON=2,ECHO=8,ECHOE=16,ECHOK=32,ECHONL=64,NOFLSH=128,TOSTOP=256,IEXTEN=32768}
}
local function serial_term()
  -- Norns serial defaults to raw 8-N-1 unless a handler requests another format.
  return {ispeed=0,ospeed=0,iflag=0,oflag=0,
    cflag=serial.cflag.CS8 + serial.cflag.CREAD + serial.cflag.CLOCAL,
    lflag=0,cc={}}
end
function serial.add_handler(args)
  if type(args) ~= "table" or args.id == nil or type(args.match) ~= "function"
      or type(args.add) ~= "function" or type(args.remove) ~= "function"
      or type(args.event) ~= "function" then
    host.log("serial.add_handler: handler inválido.", true)
    return false
  end
  if serial._handlers[args.id] ~= nil then
    host.log("serial.add_handler: identificador duplicado " .. tostring(args.id), true)
    return false
  end
  serial._handlers[args.id] = args
  serial.refresh()
  return true
end
function serial.send(dev, data)
  local id = type(dev) == "table" and tonumber(dev.id) or nil
  if not id or type(data) ~= "string" or not serial._ports[id] then return false end
  return host.usb_serial_write(id, data)
end
function serial.remove_handler(handler_id)
  local handler = serial._handlers[handler_id]
  if not handler then return false end
  for id, pending in pairs(serial._pending) do
    if pending.handler_id == handler_id then
      host.usb_serial_close(id); serial._pending[id] = nil
    end
  end
  for id, port in pairs(serial._ports) do
    if port.handler_id == handler_id then
      host.usb_serial_close(id); serial._ports[id] = nil
      safe(handler.remove, id)
    end
  end
  serial._handlers[handler_id] = nil
  serial.connected = next(serial._ports) ~= nil
  return true
end
function serial.refresh()
  local ok, found = pcall(host.usb_serial_devices)
  if not ok or type(found) ~= "table" then return {} end
  serial.devices = found
  for i = 1, #found do
    local attrs = found[i]
    local id = tonumber(attrs.id)
    if id and not serial._ports[id] and not serial._pending[id] then
      local handler_id = _norns.serial.match(attrs.vendor, attrs.model, attrs.serial, attrs.interface, attrs.vendor_id, attrs.product_id)
      if handler_id ~= nil then
        local term = serial_term()
        local configured = _norns.serial.configure(handler_id, term)
        if type(configured) == "table" then
          -- Accept handlers that return only the fields they changed.
          for key, value in pairs(configured) do term[key] = value end
        end
        local speed = tonumber(term.ospeed) or tonumber(term.ispeed) or serial.speed.B115200
        if speed == 0 then speed = serial.speed.B115200 end
        if host.usb_serial_open(id, speed, tonumber(term.cflag) or 0) then
          serial._pending[id] = {handler_id=handler_id, attrs=attrs, term=term}
        end
      end
    end
  end
  return found
end
function serial.cleanup()
  for id in pairs(serial._pending) do host.usb_serial_close(id) end
  for id, port in pairs(serial._ports) do
    host.usb_serial_close(id)
    _norns.serial.remove(port.handler_id, id)
  end
  serial._ports, serial._pending = {}, {}
  -- Keep registered transports (Crow, etc.) across script reloads.
  serial.connected = false
end
_norns.serial = {}
function _norns.serial.match(vendor, model, serial_num, interface_num, vendor_id, product_id)
  local attrs = {vendor=vendor,model=model,serial=serial_num,interface=interface_num,
    vendor_id=vendor_id,product_id=product_id}
  for id, handler in pairs(serial._handlers) do
    local ok, matched = pcall(handler.match, attrs)
    if ok and matched then return id end
  end
  return nil
end
function _norns.serial.configure(handler_id, term)
  local handler = serial._handlers[handler_id]
  if not handler then return term end
  if type(handler.configure) == "function" then
    local ok, configured = pcall(handler.configure, term)
    if ok and type(configured) == "table" then return configured end
  end
  return term
end
function _norns.serial.add(handler_id, id, name, dev)
  local handler = serial._handlers[handler_id]
  if handler then safe(handler.add, id, name, dev) end
end
function _norns.serial.remove(handler_id, id)
  local handler = serial._handlers[handler_id]
  if handler then safe(handler.remove, id) end
end
function _norns.serial.event(handler_id, id, data)
  local handler = serial._handlers[handler_id]
  if handler then safe(handler.event, id, data) end
end
function _norns.serial_connected(id, name)
  id = tonumber(id)
  local pending = id and serial._pending[id]
  if not pending then return false end
  serial._pending[id] = nil
  local dev = {id=id,name=tostring(name or "USB Serial"),attrs=pending.attrs}
  serial._ports[id] = {handler_id=pending.handler_id,dev=dev}
  serial.connected = true
  _norns.serial.add(pending.handler_id,id,dev.name,dev)
  return true
end
function _norns.serial_data(id, data)
  id = tonumber(id)
  local port = id and serial._ports[id]
  if not port then return false end
  local payload = data or ""
  for offset = 1, #payload, 255 do
    -- A handler may disconnect or replace this port from inside its callback.
    -- Never deliver remaining chunks to a stale transport after that transition.
    if serial._ports[id] ~= port then break end
    _norns.serial.event(port.handler_id,id,string.sub(payload,offset,offset+254))
  end
  return true
end
function _norns.serial_disconnected(id, reason)
  id = tonumber(id)
  local pending = id and serial._pending[id]
  local port = id and serial._ports[id]
  serial._pending[id], serial._ports[id] = nil, nil
  serial.connected = next(serial._ports) ~= nil
  if port then _norns.serial.remove(port.handler_id,id) end
  if reason then host.log("serial: " .. tostring(reason), false) end
  return pending ~= nil or port ~= nil
end
function _norns.serial_refresh() return serial.refresh() end

serial.add_handler({
  id = "norns.crow.usb",
  match = function(attrs)
    return attrs.vendor_id == 1155 and attrs.product_id == 22336
      and attrs.vendor == "monome & whimsical raps"
      and attrs.model == "crow: telephone line"
      and attrs.interface == "00"
  end,
  configure = function(term)
    term.ispeed = serial.speed.B115200
    term.ospeed = serial.speed.B115200
    return term
  end,
  add = function(id, name, dev)
    crowDevice = dev
    norns.crow.dev = dev
    norns.crow.add(id, name, dev)
    if type(clock) == "table" and type(clock.run) == "function" then
      clock.run(function() clock.sleep(0.55); crow.reset() end)
    else
      crow.reset()
    end
  end,
  remove = function(id)
    if crowDevice and crowDevice.id == id then
      norns.crow.remove(id)
      crowDevice = nil
      norns.crow.dev = nil
      crowEventBuffer = ""
    end
  end,
  event = function(id, data) crow_receive(data) end,
})

-- ===================================================================== parâmetros
params = paramset.new()
params.on_change = function() host.params_changed() end

-- Upstream Stratum LFO invokes this hook from its add/show/hide paths (three call sites).
-- ParamSet already notifies the Android menu through on_change; expose the same hook
-- without requiring norns' private menu implementation.
_menu = _menu or {}
if type(_menu.rebuild_params) ~= "function" then
  _menu.rebuild_params = function()
    if params and type(params.on_change) == "function" then
      params.on_change(params)
    end
  end
end

local function add_system_params()
  local g = params:add_group("clock_group", "CLOCK", 1)
  g.system = true
  local p = params:add_number("clock_tempo", "tempo", 1, 300, tempo)
  p.system = true
  p.action = function(v) set_tempo(v) end
end
add_system_params()

-- ===================================================================== entradas chamadas pelo Java
_norns = _norns or {}

function _norns.tick()
  in_tick = true
  local nowt = host.now()
  local due = {}
  while timers[1] and timers[1].t <= nowt do
    due[#due + 1] = table.remove(timers, 1)
  end
  for i = 1, #due do
    local ok, err = pcall(due[i].fn)
    if not ok then host.log(tostring(err), true) end
  end
  in_tick = false
  rearm()
end

function _norns.key(n, z)
  local handler = menu_mode and menu_callbacks.key or key
  if type(handler) == "function" then safe(handler, n, z) end
end

function _norns.keyboard_event(code, value, ch, shift, alt, ctrl, meta,
                                normal_char, shift_char, altgr_char, shift_altgr_char)
  keyboard.state.SHIFT = shift == true
  keyboard.state.ALT = alt == true
  keyboard.state.CTRL = ctrl == true
  keyboard.state.META = meta == true
  if type(code) ~= "string" then return false end
  keyboard.state[code] = value > 0
  if normal_char or shift_char or altgr_char or shift_altgr_char then
    keyboard._code_chars[code] = {
      normal = normal_char, shift = shift_char,
      altgr = altgr_char, shift_altgr = shift_altgr_char
    }
  elseif type(ch) == "string" and ch ~= "" and keyboard._code_chars[code] == nil then
    keyboard._code_chars[code] = ch
  end
  if type(keyboard.code) == "function" then safe(keyboard.code, code, value) end
  if value ~= 0 and type(ch) == "string" and ch ~= ""
      and type(keyboard.char) == "function" then safe(keyboard.char, ch) end
  return true
end

function _norns.enc(n, d)
  local handler = menu_mode and menu_callbacks.enc or enc
  if type(handler) ~= "function" then return end
  local nowt = host.now()
  local previous = encoder_last_time[n]
  local elapsed = previous and math.max(0, nowt - previous) or 1
  encoder_last_time[n] = nowt
  local factor = encoder_sensitivity
  if encoder_acceleration > 0 and elapsed < 0.12 then
    factor = factor * (1 + encoder_acceleration * (0.12 - elapsed) / 0.12)
  end
  local scaled = math.floor(math.abs(d) * factor + 0.5)
  if scaled == 0 and d ~= 0 then scaled = 1 end
  if d < 0 then scaled = -scaled end
  safe(handler, n, scaled)
end

function _norns.cleanup()
  -- Invalidate asynchronous engine/Softcut completion callbacks before teardown.
  _norns._script_generation = (_norns._script_generation or 0) + 1
  _norns._loaded_generation = nil
  _norns._run_generation = nil
  -- Match the upstream script lifecycle: a script cleanup callback is one-shot.
  -- Clear it before invocation so repeated/re-entrant teardown cannot run it twice.
  local script_cleanup = cleanup
  cleanup = noop
  -- Retire script-owned input callbacks before invoking cleanup. Any delayed
  -- dispatch that reaches this VM after teardown must observe inert handlers.
  key, enc, redraw, init = noop, noop, noop, noop
  if type(script_cleanup) == "function" then safe(script_cleanup) end
  -- A modal file selector owns the input callbacks until it exits. Retire it
  -- before clocks and script state are torn down so no stale handler survives.
  local selector = loaded.fileselect or loaded["lib/fileselect"]
  if type(selector) == "table" and type(selector.exit) == "function"
      and selector.exited ~= true then
    safe(selector.exit)
  end
  norns.menu.set(noop, noop, noop, noop)
  norns.menu.toggle(false)
  if norns and norns.crow and type(norns.crow.init) == "function" then safe(norns.crow.init) end
  if serial and type(serial.cleanup) == "function" then serial.cleanup() end
  if engine and engine._reset_native then engine._reset_native() end
  if softcut and softcut._reset_native then softcut._reset_native() end
  if poll and type(poll.clear_all) == "function" then poll.clear_all() end
  clock.cleanup()
  metro.free_all()
  if midi and type(midi.cleanup) == "function" then midi.cleanup() end
  grid.cleanup()
  arc.cleanup()
  hid.cleanup()
  timers = {}
  host.wake_at(-1)
end

function _norns.load_script(path)
  -- Every load owns a generation; callbacks from a previous load/cleanup are stale.
  _norns._script_generation = (_norns._script_generation or 0) + 1
  local script_generation = _norns._script_generation
  local dir, file = string.match(path, "^(.*/)([^/]+)$")
  if not dir then dir = ""; file = path end
  local folder = string.match(dir, "([^/]+)/$") or ""
  local short = string.match(file, "^(.*)%.lua$") or file
  norns.state.name = short
  norns.state.shortname = short
  norns.state.path = dir
  norns.state.lib = dir .. "lib/"
  -- Norns separa os dados por nome completo do script, inclusive quando
  -- vários scripts vivem na mesma pasta (ex.: we/study/study1 e study2).
  local data_name = dir
  if string.sub(data_name, 1, #_path.code) == _path.code then
    data_name = string.sub(data_name, #_path.code + 1)
  end
  data_name = string.gsub(data_name, "/+$", "")
  if data_name == "" then
    data_name = short
  elseif string.sub(data_name, -#short) ~= short then
    data_name = data_name .. "/" .. short
  end
  norns.state.data = _path.data .. data_name .. "/"
  -- Norns guarda o último slot em data/pset-last.txt (um arquivo por script).
  host.mkdir(norns.state.data)
  local saved_pset_slot = tonumber(host.read_file(norns.state.data .. "pset-last.txt") or "")
  if saved_pset_slot and saved_pset_slot >= 1 then
    norns.state.pset_last = math.floor(saved_pset_slot)
  else
    norns.state.pset_last = 1
  end

  key = noop
  enc = noop
  redraw = noop
  init = noop
  cleanup = noop
  engine._reset_native()
  if softcut and softcut._reset_native then softcut._reset_native() end
  engine.name = "none"
  engine.loaded = false
  engine._registered_commands = nil

  params:clear()
  audio.add_params()
  add_system_params()

  local src = host.read_file(path)
  if not src then
    host.log("Não foi possível ler " .. tostring(path), true)
    return false
  end
  local fn, err = load(strip_bom(src), "@" .. folder .. "/" .. file)
  if not fn then
    host.log(tostring(err), true)
    return false
  end
  local ok, e = pcall(fn)
  if not ok then
    host.log(tostring(e), true)
    return false
  end

  _norns._loaded_generation = script_generation
  _norns._run_generation = nil
  return norns.script.run()
end

-- ---- parâmetros: ponte com a interface
local function clean(s)
  s = tostring(s)
  s = string.gsub(s, "\\", "\\\\")
  s = string.gsub(s, "\t", "\\t")
  s = string.gsub(s, "\r", "\\r")
  s = string.gsub(s, "\n", "\\n")
  return s
end

-- Remove ruído de ponto flutuante (ex.: -8.999999999999998) e "Infinity" do texto exibido.
local function tidy_value_text(s)
  s = tostring(s)
  s = string.gsub(s, "%-[Ii]nf%a*", "-inf")
  s = string.gsub(s, "(%-?%d+%.%d%d%d%d%d%d%d%d%d+)", function(n)
    local v = tonumber(n)
    if not v then return n end
    local t = string.format("%.3f", v)
    t = string.gsub(t, "0+$", "")
    t = string.gsub(t, "%.$", ".0")
    return t
  end)
  return s
end

function _norns.params_serialize()
  local rows = params:ui_rows(true)
  local out = {}
  for i = 1, #rows do
    local r = rows[i]
    local norm = r.norm
    if norm ~= norm then norm = 0 end
    out[#out + 1] = table.concat({r.kind, clean(r.id), clean(r.name), clean((r.kind == "control" or r.kind == "number") and tidy_value_text(r.str) or r.str), string.format("%.6f", norm), tostring(norns.state.pset_last or 1), clean(r.path or "")}, "\t")
  end
  return table.concat(out, "\n")
end

function _norns.param_norm(id, norm)
  safe(function() params:set_raw(id, norm) end)
end

function _norns.param_delta(id, d)
  safe(function() params:delta(id, d) end)
end

function _norns.param_trigger(id)
  safe(function()
    local p = params:lookup_param(id)
    if p.t == paramset.tBINARY then p:delta(1) else p:set(1) end
  end)
end

function _norns.param_set_value(id, value)
  safe(function()
    if type(id) ~= "string" or type(value) ~= "string" then return end
    local p = params:lookup_param(id)
    if p.t ~= paramset.tFILE and p.t ~= paramset.tTEXT then return end
    p:set(value)
    host.params_changed()
  end)
end

function _norns.param_preset_read(slot)
  safe(function()
    if type(slot) ~= "number" or slot < 1 or slot > 16 then return end
    if params:read(math.floor(slot)) then host.params_changed() end
  end)
end

function _norns.param_preset_write(slot)
  safe(function()
    if type(slot) ~= "number" or slot < 1 or slot > 16 then return end
    params:write(math.floor(slot))
    host.params_changed()
  end)
end

function _norns.param_preset_delete(slot)
  safe(function()
    if type(slot) ~= "number" or slot < 1 or slot > 16 then return end
    params:delete(math.floor(slot))
    host.params_changed()
  end)
end

-- Script lifecycle equivalents. Paths are constrained to the private Dust root
-- by host.read_file; shell execution and device power operations remain disabled.
norns.script.load = function(filename)
  filename = tostring(filename or "")
  -- Upstream script.load(nil/"") reloads the active script. Keep this distinct
  -- from clear(), which intentionally leaves no current script to reload.
  if filename == "" then
    local current_path = tostring(norns.state.path or "")
    local current_name = tostring(norns.state.shortname or "")
    if current_path == "" or current_name == "" then return false end
    filename = current_path .. current_name .. ".lua"
  elseif string.sub(filename, 1, 1) ~= "/" then
    filename = _path.code .. filename
  end
  _norns.cleanup()
  return _norns.load_script(filename)
end

norns.script.run = function()
  -- A script chunk must have completed successfully in the current generation.
  -- Repeated run() calls are idempotent: engine/init/Softcut start only once.
  local generation = _norns._script_generation or 0
  if _norns._loaded_generation ~= generation then return false end
  if _norns._run_generation == generation then return false end
  _norns._run_generation = generation

  host.engine_load(engine.name or "none")
  engine.loaded = true
  engine._start_native(function()
    if _norns._script_generation ~= generation then return end
    if _norns._run_generation ~= generation then return end
    if softcut and softcut._start_native then softcut._start_native() end
  end)
  if type(init) == "function" and not safe(init) then
    _norns.cleanup()
    return false
  end
  if type(redraw) == "function" and not safe(redraw) then
    _norns.cleanup()
    return false
  end
  host.params_changed()
  return true
end

norns.script.clear = function()
  _norns.cleanup()
  engine._reset_native()
  if softcut and softcut._reset_native then softcut._reset_native() end
  host.engine_load("none")
  key, enc, redraw, init, cleanup = noop, noop, noop, noop, noop
  norns.state.name, norns.state.shortname = "", ""
  norns.state.path, norns.state.lib, norns.state.data, norns.state.pset_last = "", "", "", nil
  host.params_changed()
  return true
end

norns.rerun = function()
  return norns.script.load()
end

-- Read the leading comment block using the same metadata convention as
-- upstream core/script.lua. host.read_file enforces the app's private Dust root;
-- no shell, arbitrary filesystem access, or script execution is involved.
norns.script.metadata = function(filename)
  filename = tostring(filename or "")
  if filename == "" then return {} end
  if string.sub(filename, 1, 1) ~= "/" then filename = _path.code .. filename end
  local src = host.read_file(filename)
  if type(src) ~= "string" then
    host.log("file not found: " .. filename, false)
    return {}
  end
  local meta = {}
  local cursor = 1
  while cursor <= #src do
    local newline = string.find(src, "\n", cursor, true)
    local line
    if newline then
      line = string.sub(src, cursor, newline - 1)
      cursor = newline + 1
    else
      line = string.sub(src, cursor)
      cursor = #src + 1
    end
    line = string.gsub(line, "\r$", "")
    if string.sub(line, 1, 2) == "--" then
      table.insert(meta, string.sub(line, 4))
    else
      if #meta == 0 then meta[1] = "no script information" end
      break
    end
  end
  return meta
end

return true
