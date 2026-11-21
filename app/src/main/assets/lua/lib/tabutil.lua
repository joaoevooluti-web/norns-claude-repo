-- tabutil: utilitários de tabelas (compatível com o 'tab' do norns)
local tab = {}

function tab.count(t)
  local n = 0
  for _ in pairs(t) do n = n + 1 end
  return n
end

function tab.contains(t, value)
  for _, v in pairs(t) do
    if v == value then return true end
  end
  return false
end

function tab.key(t, value)
  for k, v in pairs(t) do
    if v == value then return k end
  end
  return nil
end

function tab.keys(t)
  local r = {}
  for k, _ in pairs(t) do r[#r + 1] = k end
  return r
end

function tab.invert(t)
  local r = {}
  for k, v in pairs(t) do r[v] = k end
  return r
end

function tab.gather(...)
  local r = {}
  local args = {...}
  for i = 1, #args do
    local t = args[i]
    if type(t) == "table" then
      for _, v in ipairs(t) do r[#r + 1] = v end
    end
  end
  return r
end

function tab.print(t)
  if type(t) ~= "table" then print(tostring(t)) return end
  local keys = {}
  for k, _ in pairs(t) do keys[#keys + 1] = k end
  table.sort(keys, function(a, b) return tostring(a) < tostring(b) end)
  for _, k in ipairs(keys) do print(tostring(k) .. "\t" .. tostring(t[k])) end
end

-- iterador em ordem alfabética das chaves
function tab.sort(t)
  local keys = {}
  for k, _ in pairs(t) do keys[#keys + 1] = k end
  table.sort(keys, function(a, b) return tostring(a) < tostring(b) end)
  local i = 0
  return function()
    i = i + 1
    if keys[i] ~= nil then return keys[i], t[keys[i]] end
  end
end

local function serialize(v, out, depth)
  local tv = type(v)
  if tv == "number" then
    if v ~= v or v == math.huge or v == -math.huge then out[#out + 1] = "0"
    else out[#out + 1] = tostring(v) end
  elseif tv == "boolean" then
    out[#out + 1] = tostring(v)
  elseif tv == "string" then
    out[#out + 1] = string.format("%q", v)
  elseif tv == "table" then
    if depth > 20 then out[#out + 1] = "nil" return end
    out[#out + 1] = "{"
    for k, val in pairs(v) do
      local tk = type(k)
      if (tk == "number" or tk == "string") and type(val) ~= "function" and type(val) ~= "userdata" then
        out[#out + 1] = "["
        serialize(k, out, depth + 1)
        out[#out + 1] = "]="
        serialize(val, out, depth + 1)
        out[#out + 1] = ","
      end
    end
    out[#out + 1] = "}"
  else
    out[#out + 1] = "nil"
  end
end

function tab.save(t, filename)
  local out = {"return "}
  serialize(t, out, 0)
  local f = io.open(filename, "w")
  if not f then return false end
  f:write(table.concat(out))
  f:close()
  return true
end

function tab.load(filename)
  local f = io.open(filename, "r")
  if not f then return nil end
  local src = f:read("*a")
  f:close()
  local fn = load(src, "@" .. filename)
  if not fn then return nil end
  local ok, res = pcall(fn)
  if ok then return res end
  return nil
end

return tab
