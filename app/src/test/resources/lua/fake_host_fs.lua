-- Sistema de arquivos em memória para os testes do shell emulado (nornsmobile_shell).
--
-- Imita as regras do host Java (Dust): tudo vive sob /dust, "/home/we/..." é traduzido para dentro
-- dele e qualquer caminho que escape devolve nil/false. As operações de arquivo seguem o
-- comportamento de Dust.java (mkdirs, copy, removeAll, renameTo).

local unpack = table.unpack or unpack

local HOME = "/home/we"
local PROTECTED = {code = true, data = true, audio = true}

local FS = {root = {type = "dir", children = {}}, logs = {}}

local function split(p)
  local parts = {}
  for part in string.gmatch(p, "[^/]+") do parts[#parts + 1] = part end
  return parts
end

local function collapse(parts)
  local out = {}
  for i = 1, #parts do
    local part = parts[i]
    if part == ".." then
      if #out == 0 then return nil end
      out[#out] = nil
    elseif part ~= "." then
      out[#out + 1] = part
    end
  end
  return out
end

-- Devolve a lista de componentes dentro de dust, ou nil quando o caminho escapa dela.
local function normalize(path)
  if type(path) ~= "string" or path == "" then return nil end
  local parts = split(path)
  if path == HOME or string.sub(path, 1, #HOME + 1) == HOME .. "/" then
    table.remove(parts, 1)
    table.remove(parts, 1)
    if parts[1] == "dust" then table.remove(parts, 1) end
    return collapse(parts)
  elseif string.sub(path, 1, 1) == "/" then
    if parts[1] ~= "dust" then return nil end
    table.remove(parts, 1)
    return collapse(parts)
  end
  return collapse(parts)
end

local function lookup(parts)
  local node = FS.root
  for i = 1, #parts do
    if node.type ~= "dir" then return nil end
    node = node.children[parts[i]]
    if node == nil then return nil end
  end
  return node
end

local function mkdirs(parts)
  local node = FS.root
  for i = 1, #parts do
    local child = node.children[parts[i]]
    if child == nil then
      child = {type = "dir", children = {}}
      node.children[parts[i]] = child
    elseif child.type ~= "dir" then
      return nil
    end
    node = child
  end
  return node
end

local function sorted_names(dir)
  local names = {}
  for name in pairs(dir.children) do names[#names + 1] = name end
  table.sort(names)
  return names
end

local function deep_copy(node)
  if node.type == "file" then return {type = "file", data = node.data} end
  local copy = {type = "dir", children = {}}
  for name, child in pairs(node.children) do copy.children[name] = deep_copy(child) end
  return copy
end

local function same(a, b)
  if #a ~= #b then return false end
  for i = 1, #a do
    if a[i] ~= b[i] then return false end
  end
  return true
end

-- true quando parts está estritamente dentro de base
local function inside(parts, base)
  if #parts <= #base then return false end
  for i = 1, #base do
    if parts[i] ~= base[i] then return false end
  end
  return true
end

local host = {}

function host.log(message, is_error)
  FS.logs[#FS.logs + 1] = tostring(message)
end

function host.file_exists(p)
  local parts = normalize(p)
  return parts ~= nil and lookup(parts) ~= nil
end

function host.read_file(p)
  local parts = normalize(p)
  local node = parts and lookup(parts)
  if node and node.type == "file" then return node.data end
  return nil
end

function host.list_dir(p)
  local parts = normalize(p)
  local node = parts and lookup(parts)
  if node and node.type == "dir" then return sorted_names(node) end
  return nil
end

function host.mkdir(p)
  local parts = normalize(p)
  return parts ~= nil and mkdirs(parts) ~= nil
end

function host.write_file(p, data, append)
  local parts = normalize(p)
  if not parts or #parts == 0 then return false end
  local name = table.remove(parts)
  local dir = mkdirs(parts)
  if not dir then return false end
  local existing = dir.children[name]
  if existing and existing.type == "dir" then return false end
  if append and existing then
    existing.data = existing.data .. data
  else
    dir.children[name] = {type = "file", data = data}
  end
  return true
end

function host.remove(p)
  local parts = normalize(p)
  if not parts or #parts == 0 then return false end
  local node = lookup(parts)
  if not node then return false end
  if node.type == "dir" and next(node.children) ~= nil then return false end
  local name = table.remove(parts)
  lookup(parts).children[name] = nil
  return true
end

function host.rename(a, b)
  local from, to = normalize(a), normalize(b)
  if not from or not to or #from == 0 or #to == 0 then return false end
  local node = lookup(from)
  if not node then return false end
  if same(from, to) then return true end
  if inside(to, from) then return false end
  local to_name = table.remove(to)
  local to_dir = lookup(to)
  if not to_dir or to_dir.type ~= "dir" then return false end
  local existing = to_dir.children[to_name]
  if existing then
    if node.type ~= existing.type then return false end
    if existing.type == "dir" and next(existing.children) ~= nil then return false end
  end
  local from_name = table.remove(from)
  lookup(from).children[from_name] = nil
  to_dir.children[to_name] = node
  return true
end

function host.copy(a, b, recursive)
  local from, to = normalize(a), normalize(b)
  if not from or not to then return false end
  local node = lookup(from)
  if not node then return false end
  local target = to
  local dst = lookup(to)
  if dst and dst.type == "dir" then
    if #from == 0 then return false end
    target = {unpack(to)}
    target[#target + 1] = from[#from]
  end
  if same(from, target) then return false end
  if node.type == "dir" then
    if not recursive then return false end
    if inside(target, from) then return false end
    local existing = lookup(target)
    if existing and existing.type ~= "dir" then return false end
    local dir = mkdirs(target)
    if not dir then return false end
    for name, child in pairs(node.children) do dir.children[name] = deep_copy(child) end
    return true
  end
  local name = target[#target]
  local parent = lookup({unpack(target, 1, #target - 1)})
  if not parent or parent.type ~= "dir" then return false end
  local existing = parent.children[name]
  if existing and existing.type == "dir" then return false end
  parent.children[name] = {type = "file", data = node.data}
  return true
end

function host.remove_all(p)
  local parts = normalize(p)
  if not parts then return false end
  local node = lookup(parts)
  if not node then return false end
  if #parts == 0 or (#parts == 1 and PROTECTED[parts[1]]) then return false end
  local name = table.remove(parts)
  lookup(parts).children[name] = nil
  return true
end

-- ---------------------------------------------------------------- ajudantes dos testes

function FS.add_dir(p) assert(host.mkdir(p), "add_dir " .. p) end
function FS.add_file(p, data) assert(host.write_file(p, data or "", false), "add_file " .. p) end
function FS.read(p) return host.read_file(p) end
function FS.is_dir(p) return host.list_dir(p) ~= nil end
function FS.is_file(p) return host.read_file(p) ~= nil end
function FS.exists(p) return host.file_exists(p) end

function FS.list(p)
  local names = host.list_dir(p)
  return names and table.concat(names, ",") or nil
end

-- Todos os caminhos e conteúdos, em ordem; serve para provar que nada mudou.
function FS.snapshot()
  local lines = {}
  local function walk(node, prefix)
    for _, name in ipairs(sorted_names(node)) do
      local child = node.children[name]
      local path = prefix .. "/" .. name
      if child.type == "dir" then
        lines[#lines + 1] = path .. "/"
        walk(child, path)
      else
        lines[#lines + 1] = path .. "=" .. child.data
      end
    end
  end
  walk(FS.root, "")
  return table.concat(lines, "\n")
end

mkdirs({"code"})
mkdirs({"data"})
mkdirs({"audio"})

FS.host = host
return FS
