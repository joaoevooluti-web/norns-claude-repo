-- hello_norns
-- exemplo basico do Norns Mobile
--
-- E1 E2 E3: arraste os encoders
-- K1 K2 K3: toque nas teclas

local enc_val = {0, 0, 0}
local key_down = {false, false, false}
local frame = 0

function init()
  print("hello_norns iniciado")
  local m = metro.init(function()
    frame = frame + 1
    redraw()
  end, 1 / 15, -1)
  m:start()
end

function enc(n, d)
  enc_val[n] = enc_val[n] + d
  redraw()
end

function key(n, z)
  key_down[n] = (z == 1)
  redraw()
end

function redraw()
  screen.clear()
  screen.aa(0)
  screen.line_width(1)
  screen.font_size(8)

  screen.level(15)
  screen.move(4, 10)
  screen.text("hello norns mobile")

  -- ponteiro que gira com E1
  local cx, cy, r = 100, 34, 16
  local a = enc_val[1] * 0.15
  screen.level(4)
  screen.circle(cx, cy, r)
  screen.stroke()
  screen.level(15)
  screen.move(cx, cy)
  screen.line(cx + math.cos(a) * r, cy + math.sin(a) * r)
  screen.stroke()

  -- valores dos encoders
  for i = 1, 3 do
    screen.level(10)
    screen.move(4, 14 + i * 9)
    screen.text("E" .. i .. ": " .. enc_val[i])
  end

  -- teclas
  for i = 1, 3 do
    screen.level(key_down[i] and 15 or 3)
    screen.rect(4 + (i - 1) * 14, 54, 10, 8)
    screen.fill()
  end

  -- animacao simples
  screen.level(8)
  screen.pixel(60 + (frame % 30), 58)
  screen.fill()

  screen.update()
end
