-- paramset: conjunto de parâmetros (subconjunto compatível com o norns)
local util = require 'util'
local controlspec = require 'controlspec'

local ParamSet = {}
ParamSet.__index = ParamSet

-- IDs numéricos alinhados à API oficial do norns.
ParamSet.tSEPARATOR = 0
ParamSet.tNUMBER = 1
ParamSet.tOPTION = 2
ParamSet.tCONTROL = 3
ParamSet.tFILE = 4
ParamSet.tTAPER = 5
ParamSet.tTRIGGER = 6
ParamSet.tGROUP = 7
ParamSet.tTEXT = 8
ParamSet.tBINARY = 9
ParamSet.sets = ParamSet.sets or {}

local function class(parent)
  local c = {}
  c.__index = c
  return setmetatable(c, {__index = parent})
end

local function noop() end

-- ---------------------------------------------------------------- base
local Base = {}
Base.__index = Base

function Base:get() return self.value end
function Base:get_raw() return 0 end
function Base:set(v, silent) end
function Base:set_raw(r, silent) end
function Base:delta(d) end
function Base:string() return tostring(self.value) end
function Base:set_action(fn) self.action = fn or noop end
function Base:hide() self.hidden = true; self.ps:_touch() end
function Base:show() self.hidden = false; self.ps:_touch() end
function Base:_dump() return nil end
function Base:_load(str, silent) end

function Base:bang()
  if self.action then self.action(self:get()) end
end

function Base:_fire(silent)
  if not silent and self.action then self.action(self:get()) end
  self.ps:_touch()
end

local function init(o, ps, t, id, name)
  o.ps = ps
  o.t = t
  o.id = id
  o.name = name or id
  o.action = noop
  o.hidden = false
  o.save = true
  o.allow_pmap = true
  return o
end

-- ---------------------------------------------------------------- number
local Number = class(Base)
Number.kind = "number"

function Number.new(ps, id, name, min, max, default, formatter, wrap)
  local o = init(setmetatable({}, Number), ps, ParamSet.tNUMBER, id, name)
  o.min = min or 0
  o.max = max or 1
  o.value = default or o.min
  o.default = o.value
  o.formatter = formatter
  o.wrap = wrap or false
  return o
end

function Number:constrain(v)
  if self.wrap then return util.wrap(v, self.min, self.max) end
  return util.clamp(v, self.min, self.max)
end

function Number:set(v, silent)
  local c = self:constrain(v)
  if c ~= self.value then
    self.value = c
    self:_fire(silent)
  end
end

function Number:delta(d) self:set(self.value + d) end

function Number:get_raw()
  if self.max == self.min then return 0 end
  return (self.value - self.min) / (self.max - self.min)
end

function Number:set_raw(r, silent)
  r = util.clamp(r, 0, 1)
  self:set(math.floor(self.min + r * (self.max - self.min) + 0.5), silent)
end

function Number:string()
  if self.formatter then return tostring(self.formatter(self)) end
  return tostring(self.value)
end

function Number:_dump() return tostring(self.value) end
function Number:_load(str, silent)
  local n = tonumber(str)
  if n then self:set(n, silent) end
end

-- ---------------------------------------------------------------- option
local Option = class(Base)
Option.kind = "option"

function Option.new(ps, id, name, options, default)
  local o = init(setmetatable({}, Option), ps, ParamSet.tOPTION, id, name)
  o.options = options or {}
  o.count = #o.options
  o.value = util.clamp(default or 1, 1, math.max(1, o.count))
  o.default = o.value
  return o
end

function Option:set(v, silent)
  local c = util.clamp(math.floor(v + 0.5), 1, math.max(1, self.count))
  if c ~= self.value then
    self.value = c
    self:_fire(silent)
  end
end

function Option:delta(d) self:set(self.value + d) end

function Option:get_raw()
  if self.count <= 1 then return 0 end
  return (self.value - 1) / (self.count - 1)
end

function Option:set_raw(r, silent)
  r = util.clamp(r, 0, 1)
  self:set(math.floor(1 + r * (self.count - 1) + 0.5), silent)
end

function Option:string() return tostring(self.options[self.value]) end
function Option:_dump() return tostring(self.value) end
function Option:_load(str, silent)
  local n = tonumber(str)
  if n then self:set(n, silent) end
end

-- ---------------------------------------------------------------- control
local Control = class(Base)
Control.kind = "control"

function Control.new(ps, id, name, cs, formatter, t)
  local o = init(setmetatable({}, Control), ps, t or ParamSet.tCONTROL, id, name)
  o.controlspec = cs or controlspec.new(0, 1, "lin", 0, 0, "")
  o.formatter = formatter
  o.raw = o.controlspec:unmap(o.controlspec.default)
  o.default_raw = o.raw
  return o
end

function Control:get() return self.controlspec:map(self.raw) end
function Control:get_raw() return self.raw end

function Control:set_raw(r, silent)
  if r ~= r then return end
  if self.controlspec.wrap then
    while r > 1 do r = r - 1 end
    while r < 0 do r = r + 1 end
  else
    r = util.clamp(r, 0, 1)
  end
  if r ~= self.raw then
    self.raw = r
    self:_fire(silent)
  end
end

function Control:set(v, silent) self:set_raw(self.controlspec:unmap(v), silent) end
function Control:delta(d) self:set_raw(self.raw + d * self.controlspec.quantum) end

-- Remove ruído de ponto flutuante de textos (ex.: "3.4999999999999996 s" -> "3.5 s").
local function tidy_numbers(s)
  return (string.gsub(s, "(%-?%d+%.%d%d%d%d%d%d%d%d+)", function(num)
    local n = tonumber(num)
    if not n then return num end
    local t = string.format("%.3f", n)
    t = string.gsub(t, "0+$", "")
    t = string.gsub(t, "%.$", ".0")
    return t
  end))
end

function Control:string()
  if self.formatter then return tidy_numbers(tostring(self.formatter(self))) end
  local s = string.format("%.2f", self:get())
  local u = self.controlspec.units
  if u ~= nil and u ~= "" then s = s .. " " .. u end
  return s
end

function Control:_dump() return tostring(self.raw) end
function Control:_load(str, silent)
  local n = tonumber(str)
  if n then self:set_raw(n, silent) end
end

-- ---------------------------------------------------------------- taper
-- Non-linear control curve used by the original norns params:add_taper API.
local Taper = class(Base)
Taper.kind = "taper"

function Taper.new(ps, id, name, min, max, default, k, units)
  local o = init(setmetatable({}, Taper), ps, ParamSet.tTAPER, id, name)
  o.min = min or 0
  o.max = max or 1
  o.k = k or 0
  o.units = units or ""
  o.default = default
  if o.default == nil then o.default = o.min end
  o.value = o:unmap_value(o.default)
  return o
end

function Taper:map_value(v)
  v = util.clamp(v, 0, 1)
  local mapped
  if self.k == 0 then
    mapped = v
  else
    local ek = math.exp(self.k)
    if ek == math.huge then
      -- Avoid an infinite denominator for extreme curvature values.
      mapped = math.exp((v - 1) * self.k)
    else
      mapped = (math.exp(v * self.k) - 1) / (ek - 1)
    end
  end
  return self.min + mapped * (self.max - self.min)
end

function Taper:unmap_value(v)
  if v ~= v then return 0 end
  local range = self.max - self.min
  if range == 0 then return 0 end
  local raw = (v - self.min) / range
  raw = util.clamp(raw, 0, 1)
  if self.k ~= 0 then
    local ek = math.exp(self.k)
    if ek == math.huge then
      if raw <= 0 then return 0 end
      raw = 1 + math.log(raw) / self.k
    else
      raw = math.log(raw * (ek - 1) + 1) / self.k
    end
  end
  return util.clamp(raw, 0, 1)
end

function Taper:get() return self:map_value(self.value) end
function Taper:get_raw() return self.value end

function Taper:set(v, silent)
  self:set_raw(self:unmap_value(v), silent)
end

function Taper:set_raw(v, silent)
  v = tonumber(v)
  if not v or v ~= v then return end
  local nextValue = util.clamp(v, 0, 1)
  if nextValue ~= self.value then
    self.value = nextValue
    self:_fire(silent)
  end
end

function Taper:get_delta()
  local range = math.abs(self.max - self.min)
  return 1 / math.min(math.max(range, 200), 800)
end

function Taper:delta(d)
  self:set_raw(self.value + (tonumber(d) or 0) * self:get_delta())
end

function Taper:set_default()
  self:set(self.default)
end

function Taper:string()
  local value = self:get()
  local absolute = math.abs(value)
  local format
  if absolute >= 100 then format = "%.0f"
  elseif absolute >= 10 then format = "%.1f"
  elseif absolute >= 1 then format = "%.2f"
  elseif absolute >= 0.001 then format = "%.3f"
  else format = "%.0f" end
  local units = string.gsub(self.units, "%%", "%%%%")
  if units ~= "" then format = format .. " " .. units end
  return string.format(format, value)
end

function Taper:_dump() return tostring(self:get()) end
function Taper:_load(value, silent)
  local number = tonumber(value)
  if number then self:set(number, silent) end
end

-- ---------------------------------------------------------------- binary
local Binary = class(Base)
Binary.kind = "binary"

function Binary.new(ps, id, name, behavior, default)
  local o = init(setmetatable({}, Binary), ps, ParamSet.tBINARY, id, name)
  o.behavior = behavior or "toggle"
  o.value = (default == 1 or default == true) and 1 or 0
  o.default = o.value
  return o
end

function Binary:set(v, silent)
  if type(v) == "boolean" then v = v and 1 or 0 end
  v = (v ~= 0) and 1 or 0
  if self.behavior == "trigger" then
    self:_fire(silent)
  elseif v ~= self.value then
    self.value = v
    self:_fire(silent)
  end
end

function Binary:delta(d) self:set(1 - self.value) end
function Binary:get_raw() return self.value end
function Binary:set_raw(r, silent) self:set(r >= 0.5 and 1 or 0, silent) end
function Binary:string() return self.value == 1 and "on" or "off" end
function Binary:_dump() return tostring(self.value) end
function Binary:_load(str, silent)
  local n = tonumber(str)
  if n then self:set(n, silent) end
end

-- ---------------------------------------------------------------- trigger
local Trigger = class(Base)
Trigger.kind = "trigger"

function Trigger.new(ps, id, name)
  return init(setmetatable({}, Trigger), ps, ParamSet.tTRIGGER, id, name)
end

function Trigger:get() return nil end
function Trigger:set(v, silent) if not silent then self.action() end end
function Trigger:delta(d) self:set(1) end
function Trigger:bang() end
function Trigger:string() return "" end

-- ---------------------------------------------------------------- text / file
local Text = class(Base)
Text.kind = "text"

function Text.new(ps, id, name, text, t)
  local o = init(setmetatable({}, Text), ps, t or ParamSet.tTEXT, id, name)
  o.value = text or ""
  if t == ParamSet.tFILE then o.kind = "file" end
  return o
end

function Text:set(v, silent)
  v = tostring(v)
  if v ~= self.value then
    self.value = v
    self:_fire(silent)
  end
end

function Text:string()
  if self.t == ParamSet.tFILE then
    local base = string.match(self.value, "([^/]+)$")
    return base or self.value
  end
  return self.value
end

function Text:_dump() return string.format("%q", self.value) end
function Text:_load(str, silent)
  local fn = load("return " .. str)
  if fn then
    local ok, v = pcall(fn)
    if ok and type(v) == "string" then self:set(v, silent) end
  end
end

-- ---------------------------------------------------------------- separador / grupo
local Separator = class(Base)
Separator.kind = "separator"

function Separator.new(ps, id, name)
  return init(setmetatable({}, Separator), ps, ParamSet.tSEPARATOR, id, name)
end

function Separator:get() return nil end
function Separator:bang() end
function Separator:string() return "" end

local Group = class(Base)
Group.kind = "group"

function Group.new(ps, id, name, n)
  local o = init(setmetatable({}, Group), ps, ParamSet.tGROUP, id, name)
  o.n = n or 0
  return o
end

function Group:get() return nil end
function Group:bang() end
function Group:string() return "" end

-- ---------------------------------------------------------------- ParamSet
function ParamSet.new(args, name)
  local ps = setmetatable({}, ParamSet)
  -- Aceita tanto new({name=...}) quanto a assinatura oficial new(id, name).
  ps.id = type(args) == "table" and (args.id or "") or (args or "")
  ps.name = type(args) == "table" and (args.name or "") or (name or "")
  ps.params = {}
  ps.count = 0
  ps.lookup = {}
  ps.on_change = nil
  ps.hidden = {}
  ps.group = 0
  ps.action_write, ps.action_read, ps.action_delete = nil, nil, nil
  ParamSet.sets[ps.id] = ps
  return ps
end

function ParamSet:_touch()
  if self.on_change then self.on_change(self) end
end

function ParamSet:_register(p, args)
  if args and args.action then p.action = args.action end
  local idx = self.lookup[p.id]
  if idx then
    self.params[idx] = p
  else
    self.count = self.count + 1
    self.params[self.count] = p
    self.lookup[p.id] = self.count
  end
  if p.t ~= ParamSet.tGROUP and self.group > 0 then
    p._group_id = self.active_group_id
    self.group = math.max(0, self.group - 1)
    if self.group == 0 then self.active_group_id = nil end
  end
  self:_touch()
  return p
end

function ParamSet:add_number(id, name, min, max, default, formatter, wrap, allow_pmap)
  local p = Number.new(self, id, name, min, max, default, formatter, wrap)
  p.allow_pmap = allow_pmap ~= false
  return self:_register(p)
end

function ParamSet:add_option(id, name, options, default, allow_pmap)
  local p = Option.new(self, id, name, options, default)
  p.allow_pmap = allow_pmap ~= false
  return self:_register(p)
end

function ParamSet:add_control(id, name, cs, formatter, allow_pmap)
  local p = Control.new(self, id, name, cs, formatter)
  p.allow_pmap = allow_pmap ~= false
  return self:_register(p)
end

function ParamSet:add_taper(id, name, min, max, default, k, units)
  return self:_register(Taper.new(self, id, name, min, max, default, k, units))
end

function ParamSet:add_binary(id, name, behavior, default, allow_pmap)
  local p = Binary.new(self, id, name, behavior, default)
  p.allow_pmap = allow_pmap ~= false
  return self:_register(p)
end

function ParamSet:add_trigger(id, name)
  return self:_register(Trigger.new(self, id, name))
end

function ParamSet:add_text(id, name, text)
  return self:_register(Text.new(self, id, name, text, ParamSet.tTEXT))
end

function ParamSet:add_file(id, name, path)
  -- The chooser root is metadata, not the selected value. Keep it separate
  -- so an unselected file parameter starts empty and persists only its value.
  local p = Text.new(self, id, name, "", ParamSet.tFILE)
  p.path = path or ""
  return self:_register(p)
end

function ParamSet:add_separator(id, name)
  if id == nil then id = "separator" end
  if name == nil then
    name = id
    id = "separator_" .. (self.count + 1)
  end
  return self:_register(Separator.new(self, id, name))
end

function ParamSet:add_group(id, name, n)
  if id == nil then id = "group" end
  if n == nil and type(name) == "number" then
    n = name
    name = id
    id = "group_" .. (self.count + 1)
  end
  n = n or 1
  if self.group > 0 then
    if print then print("ERROR: paramset cannot nest GROUPs") end
    return nil
  end
  local p = self:_register(Group.new(self, id, name, n))
  self.group = n
  self.active_group_id = id
  return p
end

function ParamSet:add(args)
  if type(args) ~= "table" then error("params:add: esperado uma tabela", 2) end
  if args.param then
    local p = args.param
    p.ps = self
    -- Preserve explicit save=false on custom parameter objects.
    if p.save == nil then p.save = true end
    p.allow_pmap = p.allow_pmap ~= false
    if args.action then p.action = args.action end
    return self:_register(p)
  end
  local t = args.type
  local p
  if t == "number" then
    p = self:add_number(args.id, args.name, args.min, args.max, args.default, args.formatter, args.wrap, args.allow_pmap)
  elseif t == "option" then
    p = self:add_option(args.id, args.name, args.options, args.default, args.allow_pmap)
  elseif t == "control" then
    p = self:add_control(args.id, args.name, args.controlspec, args.formatter, args.allow_pmap)
  elseif t == "taper" then
    p = self:add_taper(args.id, args.name, args.min, args.max, args.default, args.k, args.units)
  elseif t == "binary" then
    p = self:add_binary(args.id, args.name, args.behavior, args.default, args.allow_pmap)
  elseif t == "trigger" then
    p = self:add_trigger(args.id, args.name)
  elseif t == "text" then
    p = self:add_text(args.id, args.name, args.text)
  elseif t == "file" then
    p = self:add_file(args.id, args.name, args.path)
  elseif t == "separator" then
    p = self:add_separator(args.id, args.name)
  elseif t == "group" then
    p = self:add_group(args.id, args.name, args.n)
  else
    error("params:add: tipo desconhecido '" .. tostring(t) .. "'", 2)
  end
  -- Generic typed registration must honor the same persistence and pmap
  -- opt-outs as the dedicated add_* methods.
  if p == nil then return nil end
  if args.save == false then p.save = false end
  if args.allow_pmap == false then p.allow_pmap = false end
  if args.action then p.action = args.action end
  return p
end

function ParamSet:get_id(index)
  return self.params[index] and self.params[index].id or nil
end

function ParamSet:lookup_param(id)
  local idx = id ~= nil and self.lookup[id] or nil
  if idx == nil then
    if type(id) == "number" and self.params[id] then return self.params[id] end
    local message = "params: parâmetro desconhecido '" .. tostring(id) .. "'"
    -- nil IDs normalmente indicam uma chamada incompatível no script. Preserve
    -- o erro, mas inclua a pilha para identificar o chamador sem mascará-lo.
    if id == nil and debug and debug.traceback then
      message = message .. "\\n" .. debug.traceback("Origem da chamada:", 2)
    end
    error(message, 3)
  end
  return self.params[idx]
end

function ParamSet:get(id) return self:lookup_param(id):get() end
function ParamSet:get_raw(id) return self:lookup_param(id):get_raw() end
function ParamSet:set(id, v, silent) self:lookup_param(id):set(v, silent) end
function ParamSet:set_raw(id, v, silent) self:lookup_param(id):set_raw(v, silent) end
function ParamSet:delta(id, d) self:lookup_param(id):delta(d) end
function ParamSet:string(id) return self:lookup_param(id):string() end
function ParamSet:t(id) return self:lookup_param(id).t end
function ParamSet:set_action(id, fn) self:lookup_param(id):set_action(fn) end
function ParamSet:hide(id) self:lookup_param(id):hide() end
function ParamSet:show(id) self:lookup_param(id):show() end

function ParamSet:get_name(id)
  if type(id) == "number" and self.params[id] then return self.params[id].name or "" end
  return self:lookup_param(id).name or ""
end

function ParamSet:set_save(id, state)
  self:lookup_param(id).save = not not state
end

function ParamSet:get_allow_pmap(id)
  local p = self:lookup_param(id)
  return p.allow_pmap ~= false
end

function ParamSet:visible(id)
  local p = self:lookup_param(id)
  return not p.hidden
end

function ParamSet:print()
  for i = 1, self.count do
    local p = self.params[i]
    print(tostring(i) .. " " .. tostring(p.name) .. " = " .. tostring(p:string()))
  end
end

function ParamSet:list()
  for i = 1, self.count do
    if self.params[i].id then print(self.params[i].id) end
  end
end

function ParamSet:get_range(id)
  local p = self:lookup_param(id)
  if p.min ~= nil and p.max ~= nil then return {p.min, p.max} end
  if p.controlspec then return {p.controlspec.minval, p.controlspec.maxval} end
  if p.count ~= nil then return {1, p.count} end
  return nil
end

function ParamSet:get_options(id)
  local p = self:lookup_param(id)
  return p.options
end

function ParamSet:get_default(id)
  local p = self:lookup_param(id)
  if p.default_raw ~= nil then return p.controlspec:map(p.default_raw) end
  return p.default
end

function ParamSet:set_default(id, value)
  local p = self:lookup_param(id)
  if p.default_raw ~= nil then
    p.default_raw = p.controlspec:unmap(value)
  elseif p.default ~= nil then
    if p.constrain then value = p:constrain(value) end
    p.default = value
  else
    return false
  end
  return true
end

function ParamSet:remove(id)
  local index = self.lookup[id]
  if not index then return false end
  local removed = self.params[index]
  -- Removing an unfinished group header cancels that group. Removing one
  -- of its members restores the corresponding remaining slot.
  if removed.t == ParamSet.tGROUP and removed.id == self.active_group_id then
    self.group = 0
    self.active_group_id = nil
  elseif self.group > 0 and removed._group_id ~= nil
      and removed._group_id == self.active_group_id then
    self.group = self.group + 1
  end
  table.remove(self.params, index)
  self.lookup = {}
  for i = 1, #self.params do self.lookup[self.params[i].id] = i end
  self.count = #self.params
  self:_touch()
  return true
end

function ParamSet:bang(id)
  local function bang_parameter(p)
    if p.t == ParamSet.tTRIGGER then return end
    if p.t == ParamSet.tBINARY and p.behavior == "trigger" and p.value == 0 then return end
    if p.bang then p:bang() end
  end

  if id ~= nil then
    bang_parameter(self:lookup_param(id))
    return
  end
  for i = 1, self.count do bang_parameter(self.params[i]) end
end

function ParamSet:clear()
  self.params = {}
  self.count = 0
  self.lookup = {}
  self.group = 0
  self.active_group_id = nil
  self:_touch()
end

local function reset_parameter_defaults(self, id)
  local function reset(p)
    if p.default_raw ~= nil then
      p:set_raw(p.default_raw, true)
    elseif p.default ~= nil then
      p:set(p.default, true)
    end
  end

  if id ~= nil then
    reset(self:lookup_param(id))
    return
  end
  self.group = 0
  for i = 1, self.count do reset(self.params[i]) end
  self:_touch()
end

-- Mantém a operação explícita de restauração de fábrica separada do
-- carregamento do preset padrão, que é o significado de params:default() no norns.
function ParamSet:reset(id)
  reset_parameter_defaults(self, id)
end

function ParamSet:default()
  local st = norns and norns.state or {}
  self:read(st.pset_last)
  self:bang()
end

local function pset_filename(n)
  local st = norns.state
  local base = st.data .. (st.shortname or st.name or "")
  if n == nil then return base .. ".pset" end
  return base .. "-" .. string.format("%02d", n) .. ".pset"
end

-- Replica o estado do último slot do norns em data/pset-last.txt.
-- O arquivo é específico da pasta de dados do script atualmente carregado.
local function remember_pset_slot(slot)
  if type(slot) ~= "number" then return end
  local st = norns and norns.state
  if not st or type(st.data) ~= "string" or st.data == "" then return end
  st.pset_last = slot
  local f = io.open(st.data .. "pset-last.txt", "w")
  if f then
    f:write(tostring(slot))
    f:close()
  end
end

local function pset_quote(value)
  return string.format("%q", tostring(value))
end

local function pset_unquote(value)
  if type(value) ~= "string" or value:sub(1, 1) ~= '"' or value:sub(-1) ~= '"' then return nil end
  local decoded = value:sub(2, -2)
  decoded = decoded:gsub("\\(%d%d?%d?)", function(code) return string.char(tonumber(code)) end)
  decoded = decoded:gsub("\\(.)", function(ch)
    if ch == "n" then return "\n"
    elseif ch == "r" then return "\r"
    elseif ch == "t" then return "\t"
    else return ch end
  end)
  return decoded
end

function ParamSet:write(filename, name)
  if filename == nil then filename = 1 end
  local pset_number = type(filename) == "number" and filename or nil
  if type(filename) == "number" then filename = pset_filename(filename)
  elseif type(filename) ~= "string" then return false end

  local lines = {}
  if name ~= nil then lines[#lines + 1] = "-- " .. tostring(name) end
  for i = 1, self.count do
    local p = self.params[i]
    if p.id and p.save ~= false and p.t ~= ParamSet.tTRIGGER and p.t ~= ParamSet.tSEPARATOR then
      local value = p:get()
      if value ~= nil then
        local encoded
        if type(value) == "string" then
          encoded = pset_quote(value)
        elseif value == math.huge then
          encoded = "inf"
        elseif value == -math.huge then
          encoded = "-inf"
        else
          encoded = tostring(value)
        end
        lines[#lines + 1] = pset_quote(p.id) .. ": " .. encoded
      end
    end
  end

  local f = io.open(filename, "w")
  if not f then return false end
  local ok = f:write(table.concat(lines, "\n") .. "\n")
  f:close()
  if not ok then return false end
  remember_pset_slot(pset_number)
  if self.action_write then self.action_write(filename, name, pset_number) end
  return true
end

function ParamSet:read(filename, silent)
  if filename == nil then filename = norns and norns.state and norns.state.pset_last end
  local pset_number = type(filename) == "number" and filename or nil
  if type(filename) == "number" then filename = pset_filename(filename) end
  if type(filename) ~= "string" or filename == "" then return false end

  local f = io.open(filename, "r")
  if not f then return false end
  local data = f:read("*a")
  f:close()
  local already_set = {}
  for line in string.gmatch(data, "[^\r\n]+") do
    if string.sub(line, 1, 2) == "--" then
      params.name = string.sub(line, 4)
    else
      local quoted_id, _, value = string.match(line, '^%s*("(.-)")%s*:%s*(.-)%s*$')
      local id = quoted_id and pset_unquote(quoted_id) or nil
      local legacy = false
      if id == nil then
        id, value = string.match(line, "^([^=]+)=(.*)$")
        legacy = id ~= nil
      end
      local index = id and self.lookup[id]
      if index and value ~= nil and not already_set[index] then
        local p = self.params[index]
        if legacy then
          p:_load(value, silent)
        elseif p.t ~= ParamSet.tTEXT and tonumber(value) ~= nil then
          local number = tonumber(value)
          if p.t ~= ParamSet.tBINARY or p.behavior ~= "trigger" then p:set(number, silent) end
        elseif value == "-inf" then
          p:set(-math.huge, silent)
        elseif value == "inf" then
          p:set(math.huge, silent)
        else
          local decoded = pset_unquote(value)
          if decoded ~= nil then p:set(decoded, silent) end
        end
        already_set[index] = true
      end
    end
  end
  remember_pset_slot(pset_number)
  if self.action_read then self.action_read(filename, silent, pset_number) end
  return true
end

function ParamSet:delete(filename, name, number)
  local pset_number = number
  if type(filename) == "number" then
    pset_number = filename
    filename = pset_filename(filename)
  elseif filename == nil then
    filename = pset_filename(nil)
  end
  if type(filename) ~= "string" then return false end
  local ok = os.remove(filename)
  if ok and self.action_delete then self.action_delete(filename, name, pset_number) end
  return ok
end

function ParamSet:ui_rows(system_last)
  local rows = {}
  local function add(p)
    if not p.hidden then
      local norm = 0
      local ok, r = pcall(p.get_raw, p)
      if ok and type(r) == "number" then norm = r end
      local ok2, str = pcall(p.string, p)
      if not ok2 then str = "?" end
      rows[#rows + 1] = {kind = p.kind, id = p.id, name = p.name, str = str, norm = norm, path = p.path or ""}
    end
  end
  for i = 1, self.count do
    local p = self.params[i]
    if not (system_last and p.system) then add(p) end
  end
  if system_last then
    for i = 1, self.count do
      local p = self.params[i]
      if p.system then add(p) end
    end
  end
  return rows
end

return ParamSet
