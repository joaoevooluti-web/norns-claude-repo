-- Formatters compatíveis com lib.formatters do norns.
-- Mantém as funções como closures quando usadas como formatters de parâmetros.

local Formatters = {}
local ControlSpec = require "controlspec"

local function format(param, value, units)
  local spec = param and param.controlspec
  local suffix = units
  if suffix == nil and spec then suffix = spec.units end
  return tostring(value) .. ((suffix and suffix ~= "") and (" " .. suffix) or "")
end

function Formatters.format_freq_raw(freq)
  freq = tonumber(freq) or 0
  if freq < 0.1 then
    return tostring(util.round(freq, 0.001)) .. " Hz"
  elseif freq < 100 then
    return tostring(util.round(freq, 0.01)) .. " Hz"
  elseif util.round(freq, 1) < 1000 then
    return tostring(util.round(freq, 1)) .. " Hz"
  end
  return tostring(util.round(freq / 1000, 0.01)) .. " kHz"
end

function Formatters.format_secs_raw(secs)
  secs = tonumber(secs) or 0
  if secs > 3600 then
    return tostring(util.round(secs / 3600, 0.1)) .. " h"
  elseif secs >= 120 then
    return tostring(util.round(secs / 60, 0.1)) .. " min"
  elseif secs >= 60 then
    return tostring(util.round(secs)) .. " s"
  elseif util.round(secs, 0.01) >= 1 then
    return tostring(util.round(secs, 0.1)) .. " s"
  end
  local out = tostring(util.round(secs, 0.01))
  if #out < 4 then out = out .. "0" end
  return out .. " s"
end

function Formatters.default(param)
  return Formatters.round(0.01)(param)
end

function Formatters.percentage(param)
  return format(param, util.round(param:get() * 100), "%")
end

function Formatters.unipolar_as_percentage(param)
  return format(param, util.round(param:get() * 100), "%")
end

function Formatters.bipolar_as_percentage(param)
  return format(param, util.round(param:get() * 100), "%")
end

function Formatters.secs_as_ms(param)
  return format(param, util.round(param:get() * 1000), "ms")
end

function Formatters.unipolar_as_true_false(param)
  return format(param, param:get() == 1 and "true" or "false", "")
end

function Formatters.unipolar_as_enabled_disabled(param)
  return format(param, param:get() == 1 and "enabled" or "disabled", "")
end

function Formatters.bipolar_as_pan_widget(param)
  local dots_per_side = 10
  local value = util.clamp(tonumber(param:get()) or 0, -1, 1)
  local amount = math.abs(value)
  local pct = util.round(amount * 100)
  local left, right, descr
  if value > 0 then
    left = dots_per_side + util.round(amount * dots_per_side)
    right = util.round((1 - amount) * dots_per_side)
    if pct >= 1 then descr = "R" .. pct end
  elseif value < 0 then
    left = util.round((1 - amount) * dots_per_side)
    right = dots_per_side + util.round(amount * dots_per_side)
    if pct >= 1 then descr = "L" .. pct end
  else
    left, right = dots_per_side, dots_per_side
  end
  descr = descr or "MID"
  return format(param, descr .. " " .. "|" .. string.rep(".", math.max(0, left)) .. "|" .. string.rep(".", math.max(0, right)) .. "|", "")
end

function Formatters.unipolar_as_multimode_filter_freq(param)
  local chars = 20
  local value = ControlSpec.BIPOLAR:map(util.clamp(tonumber(param:get()) or 0, 0, 1))
  local pct = util.round(math.abs(value) * 100)
  local descr, widget
  if value > 0 then
    local clear = util.clamp(util.round(value * chars), 0, chars)
    descr = pct >= 1 and ("HP" .. pct) or "OFF"
    widget = "|" .. string.rep(".", clear) .. string.rep("|", chars - clear + 1)
  elseif value < 0 then
    local fill = util.clamp(util.round((value + 1) * chars), 0, chars)
    descr = pct >= 1 and ("LP" .. (100 - pct)) or "OFF"
    widget = string.rep("|", fill + 1) .. string.rep(".", chars - fill) .. "|"
  else
    descr, widget = "OFF", string.rep("|", chars + 2)
  end
  return format(param, widget .. " " .. descr, "")
end

function Formatters.round(precision)
  precision = precision or 1
  return function(param)
    return format(param, util.round(tonumber(param:get()) or 0, precision))
  end
end

function Formatters.format_freq(param)
  return Formatters.format_freq_raw(param:get())
end

function Formatters.format_secs(param)
  return Formatters.format_secs_raw(param:get())
end

return Formatters
