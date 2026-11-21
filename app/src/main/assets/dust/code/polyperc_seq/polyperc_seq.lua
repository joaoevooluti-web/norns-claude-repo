-- polyperc_seq
-- sequenciador de passos com o
-- engine PolyPerc
--
-- K2: tocar / parar
-- K3: nova sequencia
-- E1: tempo
-- E2: nota raiz
-- E3: filtro (cutoff)

engine.name = "PolyPerc"

local MusicUtil = require "musicutil"

local STEPS = 8
local seq = {}
local pos = 0
local playing = false
local scale = {}
local scale_names = {}

for i = 1, #MusicUtil.SCALES do
  scale_names[i] = MusicUtil.SCALES[i].name
end

local function build_scale()
  scale = MusicUtil.generate_scale(params:get("root"), params:get("scale"), 3)
end

local function randomize()
  for i = 1, STEPS do
    if math.random() < 0.2 then
      seq[i] = 0
    else
      seq[i] = math.random(1, #scale)
    end
  end
end

local function player()
  while true do
    clock.sync(1 / 4)
    if playing then
      pos = pos % STEPS + 1
      local degree = seq[pos]
      if degree ~= nil and degree > 0 and scale[degree] then
        engine.hz(MusicUtil.note_num_to_freq(scale[degree]))
      end
      redraw()
    end
  end
end

function init()
  params:add_separator("polyperc_seq")
  params:add_number("root", "nota raiz", 24, 72, 48, function(p)
    return MusicUtil.note_num_to_name(p:get(), true)
  end)
  params:set_action("root", function() build_scale() end)
  params:add_option("scale", "escala", scale_names, 12)
  params:set_action("scale", function() build_scale() end)
  params:add_control("cutoff", "cutoff", controlspec.new(50, 5000, "exp", 0, 800, "Hz"))
  params:set_action("cutoff", function(x) engine.cutoff(x) end)
  params:add_control("release", "release", controlspec.new(0.1, 3.2, "lin", 0, 0.5, "s"))
  params:set_action("release", function(x) engine.release(x) end)
  params:add_control("gain", "resonancia", controlspec.new(0, 3.5, "lin", 0, 1.5, ""))
  params:set_action("gain", function(x) engine.gain(x) end)
  params:add_control("pw", "largura do pulso", controlspec.new(0.05, 0.95, "lin", 0, 0.5, ""))
  params:set_action("pw", function(x) engine.pw(x) end)
  params:add_control("amp", "volume", controlspec.new(0, 1, "lin", 0, 0.5, ""))
  params:set_action("amp", function(x) engine.amp(x) end)

  build_scale()
  randomize()
  params:bang()
  clock.run(player)
end

function key(n, z)
  if z == 1 then
    if n == 2 then
      playing = not playing
      if not playing then pos = 0 end
    elseif n == 3 then
      randomize()
    end
    redraw()
  end
end

function enc(n, d)
  if n == 1 then
    params:delta("clock_tempo", d)
  elseif n == 2 then
    params:delta("root", d)
  elseif n == 3 then
    params:delta("cutoff", d)
  end
  redraw()
end

function redraw()
  screen.clear()
  screen.aa(0)
  screen.line_width(1)
  screen.font_size(8)

  screen.level(15)
  screen.move(2, 8)
  screen.text(playing and "tocando" or "parado")
  screen.move(126, 8)
  screen.text_right(string.format("%.0f bpm", params:get("clock_tempo")))

  -- passos
  local w = 14
  for i = 1, STEPS do
    local x = 8 + (i - 1) * 15
    local h = 0
    if seq[i] and seq[i] > 0 then h = math.floor(seq[i] * 30 / #scale) + 2 end
    screen.level(i == pos and 15 or 4)
    if h > 0 then
      screen.rect(x, 50 - h, w - 2, h)
      screen.fill()
    else
      screen.rect(x, 48, w - 2, 2)
      screen.fill()
    end
  end

  screen.level(10)
  screen.move(2, 62)
  screen.text(MusicUtil.note_num_to_name(params:get("root"), true))
  screen.move(126, 62)
  screen.text_right(string.format("%.0f Hz", params:get("cutoff")))
  screen.update()
end
