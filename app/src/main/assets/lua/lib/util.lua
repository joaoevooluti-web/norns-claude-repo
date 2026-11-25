-- util: funções utilitárias (compatível com o módulo util do norns)
local util = {}

function util.clamp(n, min, max)
  return math.min(max, math.max(n, min))
end

function util.round(number, quant)
  quant = quant or 1
  if quant == 0 then return number end
  local r = math.floor(number / quant + 0.5) * quant
  -- Remove ruído de ponto flutuante (ex.: 35*0.1 = 3.5000000000000004).
  if quant ~= math.floor(quant) and r == r and r ~= math.huge and r ~= -math.huge then
    local decimals = math.max(0, math.ceil(-math.log(quant) / math.log(10) - 1e-9))
    if decimals <= 12 then
      local m = 10 ^ decimals
      r = math.floor(r * m + 0.5) / m
      if r ~= r then return number end
    end
  end
  return r
end

function util.round_up(number, quant)
  quant = quant or 1
  if quant == 0 then return number end
  return math.ceil(number / quant) * quant
end

function util.linlin(slo, shi, dlo, dhi, f)
  if f <= slo then return dlo
  elseif f >= shi then return dhi
  else return (f - slo) / (shi - slo) * (dhi - dlo) + dlo end
end

function util.linexp(slo, shi, dlo, dhi, f)
  if f <= slo then return dlo
  elseif f >= shi then return dhi
  else return (dhi / dlo) ^ ((f - slo) / (shi - slo)) * dlo end
end

function util.explin(slo, shi, dlo, dhi, f)
  if f <= slo then return dlo
  elseif f >= shi then return dhi
  else return (math.log(f / slo)) / (math.log(shi / slo)) * (dhi - dlo) + dlo end
end

function util.expexp(slo, shi, dlo, dhi, f)
  if f <= slo then return dlo
  elseif f >= shi then return dhi
  else return (dhi / dlo) ^ (math.log(f / slo) / math.log(shi / slo)) * dlo end
end

function util.wrap(n, min, max)
  if max < min then min, max = max, min end
  local y = n
  local d = max - min + 1
  while y > max do y = y - d end
  while y < min do y = y + d end
  return y
end

function util.wrap_max(n, min, max)
  local y = n
  local d = max - min
  if d <= 0 then return min end
  while y >= max do y = y - d end
  while y < min do y = y + d end
  return y
end

function util.time()
  -- Use the monotonic host clock (seconds, sub-second precision) for scheduling.
  -- os.time() has only one-second resolution and breaks recorded pattern timing.
  return _host.now()
end

function util.file_exists(path)
  return _host.file_exists(path)
end

function util.make_dir(path)
  _host.mkdir(path)
end

function util.string_starts(s, start)
  return string.sub(s, 1, string.len(start)) == start
end

function util.string_ends(s, ending)
  return ending == "" or string.sub(s, -string.len(ending)) == ending
end

function util.trim_string_to_width(str, width)
  local result = str
  while #result > 0 and screen.text_extents(result) > width do
    result = string.sub(result, 1, #result - 1)
  end
  return result
end

function util.scandir(directory)
  return _host.list_dir(directory) or {}
end

-- Run a shell command and return its output, as on norns. Only the small POSIX subset that
-- nornsmobile_shell understands works here; any other command returns an empty string.
function util.os_capture(cmd, raw)
  local f = io.popen(cmd, 'r')
  if not f then return "" end
  local s = f:read('*a') or ""
  f:close()
  if raw then return s end
  s = string.gsub(s, '^%s+', '')
  s = string.gsub(s, '%s+$', '')
  s = string.gsub(s, '[\n\r]+', ' ')
  return s
end

function util.s_to_hms(s)
  local h = math.floor(s / 3600)
  local m = math.floor((s - h * 3600) / 60)
  local sec = math.floor(s - h * 3600 - m * 60)
  return string.format("%d:%02d:%02d", h, m, sec)
end

-- Return a compact uppercase acronym for long device/menu labels.
-- Compatible with the norns utility API used by community scripts.
function util.acronym(value)
  local words = {}
  for word in tostring(value or ""):gmatch("%S+") do
    local first = word:match("^[%w]")
    if first then words[#words + 1] = first:upper() end
  end
  if #words == 0 then return tostring(value or "") end
  return table.concat(words)
end

function util.acos(x)
  return math.acos(x)
end

return util
