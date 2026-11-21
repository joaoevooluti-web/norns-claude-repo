-- midi_test_seq
-- Sequenciador MIDI de teste para validar MIDI OUT do Norns Mobile.
-- 16 passos, Note On/Off, BPM, canal, nota raiz, velocidade, gate e porta de saída.
--
-- K2: iniciar/parar
-- K3: reiniciar sequenciador
-- E1: BPM
-- E2: nota raiz
-- E3: velocidade
--
-- A porta "Norns Mobile MIDI Out" permite que outros apps Android recebam MIDI
-- conectando-se ao dispositivo MIDI virtual do Norns Mobile.

local MusicUtil = require "musicutil"

local STEPS = 16
local step = 0
local playing = false
local midi_out = nil
local output_map = {}
local output_names = {}
local output_index = 1
local last_note = nil
local last_channel = 1
local sent_count = 0
local error_text = nil

local function note_name(n)
  return MusicUtil.note_num_to_name(n, true)
end

local function release_note()
  if midi_out and last_note then
    midi_out:note_off(last_note, 0, last_channel)
    last_note = nil
  end
end

local function connect_output(index)
  release_note()
  if midi_out then
    pcall(function() midi_out:close() end)
    midi_out = nil
  end

  local vport_index = output_map[index]
  if not vport_index then
    error_text = "Saida MIDI indisponivel"
    redraw()
    return
  end

  local ok, port = pcall(midi.connect, vport_index)
  if ok and port then
    midi_out = port
    output_index = index
    error_text = nil
    print("MIDI Test: conectado em " .. tostring(output_names[index]))
  else
    error_text = "Falha ao conectar MIDI"
    print("MIDI Test: erro ao conectar " .. tostring(port))
  end
  redraw()
end

local function refresh_outputs()
  midi.refresh()
  output_map = {}
  output_names = {}
  for i = 1, #midi.vports do
    local p = midi.vports[i]
    -- Android input ports recebem dados do Norns; id 0 e a saida virtual.
    if (p.inputs or 0) > 0 or p.id == 0 then
      output_map[#output_map + 1] = i
      output_names[#output_names + 1] = tostring(p.name or ("MIDI " .. i))
    end
  end
  if #output_names == 0 then
    output_names[1] = "Nenhuma saida MIDI"
    output_map[1] = nil
  end
end

local function play_step()
  step = (step % STEPS) + 1
  release_note()

  local id = string.format("step_%02d", step)
  if params:get(id) == 1 and midi_out then
    local note = params:get("root_note") + ((step - 1) % 8) * 2
    local channel = params:get("midi_channel")
    local velocity = params:get("velocity")
    midi_out:note_on(note, velocity, channel)
    last_note = note
    last_channel = channel
    sent_count = sent_count + 1
    clock.run(function()
      clock.sleep(params:get("gate") / 1000)
      if last_note == note and last_channel == channel then
        release_note()
      end
    end)
  end
  redraw()
end

local function sequencer()
  while true do
    clock.sync(1 / 4)
    if playing then play_step() end
  end
end

function init()
  refresh_outputs()

  params:add_separator("midi_test", "MIDI OUT TEST")
  params:add_option("midi_output", "saida MIDI", output_names, 1)
  params:set_action("midi_output", function(v) connect_output(v) end)

  params:add_number("midi_channel", "canal MIDI", 1, 16, 1)
  params:add_number("root_note", "nota raiz", 24, 96, 60, note_name)
  params:add_number("velocity", "velocity", 1, 127, 100)
  params:add_number("gate", "gate (ms)", 30, 450, 120)

  params:add_separator("pattern", "PADRAO 16 PASSOS")
  for i = 1, STEPS do
    local id = string.format("step_%02d", i)
    params:add_binary(id, string.format("%02d", i), "toggle", (i % 4 == 1) and 1 or 0)
  end

  params:set_action("midi_output", function(v) connect_output(v) end)
  connect_output(1)
  clock.run(sequencer)
  redraw()
end

function key(n, z)
  if z == 1 then
    if n == 2 then
      playing = not playing
      if not playing then release_note() end
    elseif n == 3 then
      step = 0
      sent_count = 0
      release_note()
    end
    redraw()
  end
end

function enc(n, d)
  if n == 1 then
    params:delta("clock_tempo", d)
  elseif n == 2 then
    params:delta("root_note", d)
  elseif n == 3 then
    params:delta("velocity", d)
  end
  redraw()
end

function cleanup()
  playing = false
  release_note()
  if midi_out then
    pcall(function() midi_out:all_notes_off(params:get("midi_channel")) end)
    pcall(function() midi_out:close() end)
    midi_out = nil
  end
end

function redraw()
  screen.clear()
  screen.aa(0)
  screen.font_size(8)

  screen.level(15)
  screen.move(2, 8)
  screen.text(playing and "MIDI OUT  PLAY" or "MIDI OUT  STOP")
  screen.move(126, 8)
  screen.text_right(string.format("%d BPM", params:get("clock_tempo")))

  -- Grade de 16 passos com indicador da posicao atual.
  for i = 1, STEPS do
    local col = (i - 1) % 8
    local row = math.floor((i - 1) / 8)
    local x = 3 + col * 16
    local y = 19 + row * 17
    local active = params:get(string.format("step_%02d", i)) == 1
    screen.level(i == step and 15 or (active and 9 or 2))
    screen.rect(x, y, 12, 10)
    screen.fill()
    screen.level(i == step and 0 or 15)
    screen.move(x + 6, y + 8)
    screen.text_center(tostring(i))
  end

  screen.level(12)
  screen.move(2, 57)
  screen.text(note_name(params:get("root_note")) .. "  CH " .. params:get("midi_channel"))
  screen.move(126, 57)
  screen.text_right("VEL " .. params:get("velocity"))

  screen.level(error_text and 8 or 10)
  screen.move(2, 64)
  screen.text(error_text or (midi_out and tostring(output_names[output_index]) or "Sem conexao MIDI"))
  screen.move(126, 64)
  screen.text_right(tostring(sent_count) .. " notas")
  screen.update()
end
