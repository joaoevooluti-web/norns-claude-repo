-- controlspec: mapeamento entre valor normalizado (0..1) e valores de parâmetro
local ControlSpec = {}
ControlSpec.__index = ControlSpec

local function dbamp(db)
  if db == -math.huge then return 0 end
  return 10 ^ (db / 20)
end

local function ampdb(amp)
  if amp <= 0 then return -math.huge end
  return 20 * math.log(amp) / math.log(10)
end

function ControlSpec.new(minval, maxval, warp, step, default, units, quantum, wrap)
  local o = setmetatable({}, ControlSpec)
  o.minval = minval or 0
  o.maxval = maxval or 1
  o.warp = warp or "lin"
  o.step = step or 0
  o.default = default or o.minval
  o.units = units or ""
  o.quantum = quantum or 0.01
  o.wrap = wrap or false
  return o
end

function ControlSpec.def(args)
  args = args or {}
  return ControlSpec.new(args.min, args.max, args.warp, args.step, args.default,
                         args.units, args.quantum, args.wrap)
end

function ControlSpec:map(value)
  if value < 0 then value = 0 elseif value > 1 then value = 1 end
  local mn, mx = self.minval, self.maxval
  local mapped
  if self.warp == "exp" then
    if mn <= 0 then mn = 0.000001 end
    mapped = (mx / mn) ^ value * mn
  elseif self.warp == "db" then
    local a, b = dbamp(mn), dbamp(mx)
    mapped = ampdb(value * value * (b - a) + a)
  else
    mapped = value * (mx - mn) + mn
  end
  if self.step ~= nil and self.step ~= 0 and mapped == mapped and mapped ~= -math.huge then
    mapped = math.floor(mapped / self.step + 0.5) * self.step
  end
  return mapped
end

function ControlSpec:unmap(value)
  local mn, mx = self.minval, self.maxval
  local raw
  if self.warp == "exp" then
    if mn <= 0 then mn = 0.000001 end
    if value <= 0 then value = mn end
    raw = math.log(value / mn) / math.log(mx / mn)
  elseif self.warp == "db" then
    local a, b = dbamp(mn), dbamp(mx)
    local amp = dbamp(value)
    if b == a then raw = 0 else raw = math.sqrt(math.max(0, (amp - a) / (b - a))) end
  else
    if mx == mn then raw = 0 else raw = (value - mn) / (mx - mn) end
  end
  if raw ~= raw then raw = 0 end
  if raw < 0 then raw = 0 elseif raw > 1 then raw = 1 end
  return raw
end

ControlSpec.UNIPOLAR = ControlSpec.new(0, 1, "lin", 0, 0, "")
ControlSpec.BIPOLAR = ControlSpec.new(-1, 1, "lin", 0, 0, "")
ControlSpec.FREQ = ControlSpec.new(20, 20000, "exp", 0, 440, "Hz")
ControlSpec.LOWFREQ = ControlSpec.new(0.1, 100, "exp", 0, 6, "Hz")
ControlSpec.MIDFREQ = ControlSpec.new(25, 4200, "exp", 0, 440, "Hz")
ControlSpec.WIDEFREQ = ControlSpec.new(0.1, 20000, "exp", 0, 440, "Hz")
ControlSpec.PHASE = ControlSpec.new(0, 6.283185307, "lin", 0, 0, "")
ControlSpec.RQ = ControlSpec.new(0.001, 2, "exp", 0, 0.707, "")
ControlSpec.MIDI = ControlSpec.new(0, 127, "lin", 1, 64, "")
ControlSpec.MIDINOTE = ControlSpec.new(0, 127, "lin", 1, 64, "")
ControlSpec.MIDIVELOCITY = ControlSpec.new(1, 127, "lin", 1, 64, "")
ControlSpec.DB = ControlSpec.new(-math.huge, 18, "db", 0, 0, "dB")
ControlSpec.AMP = ControlSpec.new(0, 1, "lin", 0, 0, "")
ControlSpec.PAN = ControlSpec.new(-1, 1, "lin", 0, 0, "")
ControlSpec.RATE = ControlSpec.new(-4, 4, "lin", 0, 1, "")
ControlSpec.DELAY = ControlSpec.new(0.0001, 1, "exp", 0, 0.3, "secs")

return ControlSpec
