-- device_check
-- roteiro de verificacao no aparelho
-- (Galaxy S10): tela, teclas,
-- encoders, clock e audio Java.
--
-- K2: tocar nota de teste
-- K3: alternar metro 1 Hz
-- E1: frequencia (Hz)
-- E2: volume
-- E3: cutoff

engine.name = "PolyPerc"

local MusicUtil = require "musicutil"

local hz = 220
local ticks = 0
local last_key = "-"
local last_enc = "-"
local clock_beats = 0
local metro_on = false
local m

local function beep()
  engine.hz(hz)
end

function init()
  params:add_control("dc_amp", "volume", controlspec.new(0, 1, "lin", 0, 0.4, ""))
  params:set_action("dc_amp", function(x) engine.amp(x) end)
  params:add_control("dc_cutoff", "cutoff", controlspec.new(50, 5000, "exp", 0, 1200, "Hz"))
  params:set_action("dc_cutoff", function(x) engine.cutoff(x) end)
  params:bang()

  m = metro.init()
  m.time = 1
  m.count = -1
  m.event = function()
    ticks = ticks + 1
    beep()
    redraw()
  end

  clock.run(function()
    while true do
      clock.sync(1)
      clock_beats = clock_beats + 1
      redraw()
    end
  end)
end

function key(n, z)
  last_key = "K" .. n .. (z == 1 and " v" or " ^")
  if z == 1 then
    if n == 2 then
      beep()
    elseif n == 3 then
      metro_on = not metro_on
      if metro_on then m:start() else m:stop() end
    end
  end
  redraw()
end

function enc(n, d)
  last_enc = "E" .. n .. " " .. d
  if n == 1 then
    hz = util.clamp(hz + d * 5, 40, 2000)
  elseif n == 2 then
    params:delta("dc_amp", d)
  elseif n == 3 then
    params:delta("dc_cutoff", d)
  end
  redraw()
end

function redraw()
  screen.clear()
  screen.aa(0)
  screen.font_size(8)
  screen.level(15)
  screen.move(2, 8)
  screen.text("device_check  " .. (metro_on and "metro ON" or "metro off"))
  screen.level(10)
  screen.move(2, 20)
  screen.text(string.format("%d Hz  %s", hz, MusicUtil.note_num_to_name(MusicUtil.freq_to_note_num(hz), true)))
  screen.move(2, 30)
  screen.text(string.format("amp %.2f  cutoff %.0f", params:get("dc_amp"), params:get("dc_cutoff")))
  screen.move(2, 40)
  screen.text("tecla " .. last_key .. "  enc " .. last_enc)
  screen.move(2, 50)
  screen.text("metro " .. ticks .. "  clock " .. clock_beats)
  screen.move(2, 60)
  screen.text(string.format("%.0f bpm", params:get("clock_tempo")))
  screen.update()
end

function cleanup()
  if m then m:stop() end
end
