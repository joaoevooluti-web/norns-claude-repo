-- Módulo `cjson` do Norns Mobile.
--
-- Reimplementa em Lua puro a API do lua-cjson 2.1.0, o módulo C que o norns real carrega com
-- `require "cjson"`. O app não carrega bibliotecas nativas de scripts, então os scripts que leem e gravam
-- JSON (receitas, presets, configuração) usariam um módulo que não existe. Aqui valem as mesmas regras e as
-- mesmas mensagens de erro do original:
--
--   cjson.encode(valor)   cjson.decode(texto)   cjson.null   cjson.new()   require "cjson.safe"
--   cjson.encode_sparse_array / encode_max_depth / decode_max_depth / encode_number_precision
--   cjson.encode_keep_buffer / encode_invalid_numbers / decode_invalid_numbers
--
-- Diferenças deliberadas em relação ao módulo C:
--   * cjson.null é uma tabela única, porque o LuaJ não cria lightuserdata. Compare sempre com `==`.
--   * As chaves de um objeto saem em ordem (números, depois textos): o mesmo preset gera sempre o mesmo
--     arquivo. O módulo C usa a ordem do hash, que ninguém pode assumir.
--   * Os números não passam por tostring/string.format: o LuaJ converte para float em tostring e ignora
--     largura e precisão em %f/%g. Veja format_number.

local type, tostring, tonumber, next, error, pcall, select, rawget, rawequal, setmetatable =
  type, tostring, tonumber, next, error, pcall, select, rawget, rawequal, setmetatable
local find, sub, byte, char, format, gsub, match, rep, lower =
  string.find, string.sub, string.byte, string.char, string.format, string.gsub, string.match, string.rep,
  string.lower
local concat, sort = table.concat, table.sort
local floor, huge, abs = math.floor, math.huge, math.abs

local INT_MAX = 2147483647
local VERSION = "2.1.0"

-- Valor de JSON null. Uma tabela vazia e única; nenhum encode a trata como objeto.
local null = setmetatable({}, {__tostring = function() return "null" end})

-- ===================================================================== números

-- Equivale a "%.<precision>g" do C, sem depender do string.format do LuaJ. Inteiros pequenos saem pelo
-- tostring (exato). Os demais partem dos 17 dígitos de "%.17g": o LuaJ responde com Double.toString
-- (ex.: "1.0E-5") e o C com "1.0000000000000001e-05"; os dois formatos são lidos do mesmo jeito.
local function format_number(x, precision)
  if x == floor(x) then
    local limit = 10 ^ precision
    if x < limit and x > -limit then
      return (gsub(tostring(x), "%.0$", ""))
    end
  end
  local text = format("%.17g", x)
  local sign, int, frac, exp = match(text, "^(-?)(%d*)%.?(%d*)[eE]?([+-]?%d*)$")
  if not sign then return text end
  local digits = int .. frac
  local point = #int + (tonumber((gsub(exp, "^%+", ""))) or 0) -- valor = 0.DIGITOS * 10^point
  local lead = #match(digits, "^0*")
  digits = sub(digits, lead + 1)
  point = point - lead
  if digits == "" then return "0" end

  if #digits > precision then
    local round_up = byte(digits, precision + 1) >= 53 -- '5'
    digits = sub(digits, 1, precision)
    if round_up then
      local i = precision
      while i >= 1 and byte(digits, i) == 57 do i = i - 1 end -- '9' vira '0' e o vai-um segue
      if i == 0 then
        digits = "1" .. rep("0", precision - 1)
        point = point + 1
      else
        digits = sub(digits, 1, i - 1) .. char(byte(digits, i) + 1) .. rep("0", precision - i)
      end
    end
  end
  digits = gsub(digits, "0+$", "")

  local sci = point - 1 -- expoente em notação científica
  local n = #digits
  local out
  if sci < -4 or sci >= precision then
    local e = abs(sci)
    out = sub(digits, 1, 1) .. (n > 1 and ("." .. sub(digits, 2)) or "")
      .. "e" .. (sci < 0 and "-" or "+") .. (e < 10 and "0" or "") .. e
  elseif sci >= 0 then
    if n <= sci + 1 then
      out = digits .. rep("0", sci + 1 - n)
    else
      out = sub(digits, 1, sci + 1) .. "." .. sub(digits, sci + 2)
    end
  else
    out = "0." .. rep("0", -sci - 1) .. digits
  end
  return sign .. out
end

-- ===================================================================== estado compartilhado

-- Nenhum encode/decode chama código do usuário, então um único estado de trabalho basta: cada chamada o
-- reinicia, e várias instâncias (cjson.new) só trocam a configuração `C`.
local C -- configuração da instância em uso

-- ===================================================================== encode

local ESCAPES = {
  ['"'] = '\\"', ["\\"] = "\\\\", ["/"] = "\\/", ["\b"] = "\\b", ["\f"] = "\\f",
  ["\n"] = "\\n", ["\r"] = "\\r", ["\t"] = "\\t", ["\127"] = "\\u007f",
}
for i = 0, 31 do
  local c = char(i)
  if not ESCAPES[c] then ESCAPES[c] = format("\\u%04x", i) end
end
-- Só ASCII: bytes >= 128 (UTF-8) passam como estão. Não use %c: no LuaJ ele também pega 128..159.
local ESCAPE_PATTERN = '[%z\1-\31"\\/\127]'

local buf, nbuf

local function encode_error(kind, reason)
  error("Cannot serialise " .. kind .. ": " .. reason, 0)
end

local function number_text(x)
  if x ~= x or x == huge or x == -huge then
    local mode = C.encode_invalid_numbers
    if mode == false then encode_error("number", "must not be NaN or Inf") end
    if mode == "null" then return "null" end
    if x ~= x then return "nan" end
    return x > 0 and "inf" or "-inf"
  end
  return format_number(x, C.encode_number_precision)
end

local function key_less(a, b)
  local ta, tb = type(a), type(b)
  if ta == tb then return a < b end
  return ta == "number" -- números antes de textos
end

local function encode_value(v, depth)
  local tv = type(v)
  if tv == "string" then
    nbuf = nbuf + 1
    buf[nbuf] = '"' .. gsub(v, ESCAPE_PATTERN, ESCAPES) .. '"'
  elseif tv == "number" then
    nbuf = nbuf + 1
    buf[nbuf] = number_text(v)
  elseif tv == "boolean" then
    nbuf = nbuf + 1
    buf[nbuf] = v and "true" or "false"
  elseif tv == "nil" then
    nbuf = nbuf + 1
    buf[nbuf] = "null"
  elseif tv == "table" then
    if rawequal(v, null) then
      nbuf = nbuf + 1
      buf[nbuf] = "null"
      return
    end
    depth = depth + 1
    if depth > C.encode_max_depth then
      error("Cannot serialise, excessive nesting (" .. depth .. ")", 0)
    end

    -- Vetor: todas as chaves são inteiros >= 1. Uma tabela vazia vira objeto ({}), como no módulo C.
    local max, items, array = 0, 0, true
    for k in next, v do
      if type(k) == "number" and k >= 1 and k < huge and k == floor(k) then
        items = items + 1
        if k > max then max = k end
      else
        array = false
        break
      end
    end
    if array and C.sparse_ratio > 0 and max > items * C.sparse_ratio and max > C.sparse_safe then
      if not C.sparse_convert then encode_error("table", "excessively sparse array") end
      array = false
    end

    if array and max > 0 then
      nbuf = nbuf + 1
      buf[nbuf] = "["
      for i = 1, max do
        if i > 1 then
          nbuf = nbuf + 1
          buf[nbuf] = ","
        end
        encode_value(rawget(v, i), depth)
      end
      nbuf = nbuf + 1
      buf[nbuf] = "]"
    else
      local keys, nkeys = {}, 0
      for k in next, v do
        local tk = type(k)
        if tk ~= "string" and tk ~= "number" then
          encode_error(tk, "table key must be a number or string")
        end
        nkeys = nkeys + 1
        keys[nkeys] = k
      end
      sort(keys, key_less)
      nbuf = nbuf + 1
      buf[nbuf] = "{"
      for i = 1, nkeys do
        local k = keys[i]
        if i > 1 then
          nbuf = nbuf + 1
          buf[nbuf] = ","
        end
        nbuf = nbuf + 1
        if type(k) == "string" then
          buf[nbuf] = '"' .. gsub(k, ESCAPE_PATTERN, ESCAPES) .. '":'
        else
          buf[nbuf] = '"' .. number_text(k) .. '":'
        end
        encode_value(rawget(v, k), depth)
      end
      nbuf = nbuf + 1
      buf[nbuf] = "}"
    end
  else
    encode_error(tv, "type not supported")
  end
end

local function encode(value)
  buf, nbuf = {}, 0
  encode_value(value, 0)
  local out = concat(buf, "", 1, nbuf)
  buf = nil
  return out
end

-- ===================================================================== decode

local S, POS, LEN, DEPTH -- texto, próxima posição (1-based), tamanho e profundidade atual
local TT, TV, TI -- token atual: tipo, valor e índice (0-based, como no lua-cjson)

local SIMPLE = {
  [123] = "T_OBJ_BEGIN", [125] = "T_OBJ_END", [91] = "T_ARR_BEGIN", [93] = "T_ARR_END",
  [58] = "T_COLON", [44] = "T_COMMA",
}
local UNESCAPE = {
  [34] = '"', [92] = "\\", [47] = "/", [98] = "\b", [102] = "\f", [110] = "\n", [114] = "\r", [116] = "\t",
}

local function token_error(message, index)
  TT, TV, TI = "T_ERROR", message, index
end

local function utf8_char(cp)
  if cp < 0x80 then return char(cp) end
  if cp < 0x800 then return char(0xC0 + floor(cp / 64), 0x80 + cp % 64) end
  if cp < 0x10000 then
    return char(0xE0 + floor(cp / 4096), 0x80 + floor(cp / 64) % 64, 0x80 + cp % 64)
  end
  return char(0xF0 + floor(cp / 262144), 0x80 + floor(cp / 4096) % 64, 0x80 + floor(cp / 64) % 64, 0x80 + cp % 64)
end

local function hex4(at)
  local h = sub(S, at, at + 3)
  if #h == 4 and find(h, "^%x%x%x%x$") then return tonumber(h, 16) end
  return nil
end

-- S[p] é a aspa de abertura.
local function scan_string(p)
  local pos = p + 1
  local parts, n = {}, 0
  while true do
    local q = find(S, '["\\]', pos)
    if not q then return token_error("unexpected end of string", LEN) end
    if q > pos then
      n = n + 1
      parts[n] = sub(S, pos, q - 1)
    end
    if byte(S, q) == 34 then
      POS = q + 1
      TT, TV = "T_STRING", concat(parts, "", 1, n)
      return
    end
    -- Barra invertida: o erro aponta para ela (índice 0-based = q - 1).
    local code = byte(S, q + 1)
    if code == 117 then -- \uXXXX
      local cp = hex4(q + 2)
      local used = 6
      if cp and cp >= 0xD800 and cp <= 0xDFFF then
        local low = cp <= 0xDBFF and sub(S, q + 6, q + 7) == "\\u" and hex4(q + 8) or nil
        if low and low >= 0xDC00 and low <= 0xDFFF then
          cp = ((cp - 0xD800) * 1024) + (low - 0xDC00) + 0x10000
          used = 12
        else
          cp = nil
        end
      end
      if not cp then return token_error("invalid unicode escape code", q - 1) end
      n = n + 1
      parts[n] = utf8_char(cp)
      pos = q + used
    else
      local c = code and UNESCAPE[code]
      if not c then return token_error("invalid escape code", q - 1) end
      n = n + 1
      parts[n] = c
      pos = q + 2
    end
  end
end

-- S[p] é '-' ou um dígito. A gramática é a do strtod, como no lua-cjson: aceita "1.", "-.5" e "01".
-- Com decode_invalid_numbers(false) recusa hexadecimal, zero à esquerda e inf/nan.
local function scan_number(p)
  local q = p
  local negative = byte(S, q) == 45
  if negative then q = q + 1 end
  local first = byte(S, q)
  if not C.decode_invalid_numbers then
    local second = byte(S, q + 1)
    if (first == 48 and second and (second == 120 or second == 88 or (second >= 48 and second <= 57)))
       or (first and first > 57 and (lower(sub(S, q, q + 2)) == "inf" or lower(sub(S, q, q + 2)) == "nan")) then
      return token_error("invalid number", p - 1)
    end
  end
  local _, last = find(S, "^0[xX]%x+", q)
  if last then
    local value = tonumber(sub(S, q + 2, last), 16)
    TT, TV, POS = "T_NUMBER", negative and -value or value, last + 1
    return
  end
  local word = lower(sub(S, q, q + 2))
  if word == "inf" or word == "nan" then
    local value = word == "nan" and 0 / 0 or huge
    local _, stop = find(S, "^[iI][nN][fF][iI][nN][iI][tT][yY]", q)
    TT, TV, POS = "T_NUMBER", negative and -value or value, (stop or q + 2) + 1
    return
  end
  _, last = find(S, "^%d+%.?%d*", q)
  if not last then _, last = find(S, "^%.%d+", q) end
  if not last then return token_error("invalid number", p - 1) end
  local _, exp_last = find(S, "^[eE][+-]?%d+", last + 1)
  if exp_last then last = exp_last end
  TT, TV, POS = "T_NUMBER", tonumber(sub(S, p, last)), last + 1
end

local function next_token()
  local p = find(S, "[^ \t\n\r]", POS)
  if not p then
    POS, TT, TI = LEN + 1, "T_END", LEN
    return
  end
  POS, TI = p, p - 1
  local b = byte(S, p)
  local simple = SIMPLE[b]
  if simple then
    POS, TT = p + 1, simple
  elseif b == 34 then
    scan_string(p)
  elseif b == 45 or (b >= 48 and b <= 57) then
    scan_number(p)
  elseif sub(S, p, p + 3) == "true" then
    POS, TT, TV = p + 4, "T_BOOLEAN", true
  elseif sub(S, p, p + 4) == "false" then
    POS, TT, TV = p + 5, "T_BOOLEAN", false
  elseif sub(S, p, p + 3) == "null" then
    POS, TT = p + 4, "T_NULL"
  elseif C.decode_invalid_numbers and (lower(sub(S, p, p + 2)) == "inf" or lower(sub(S, p, p + 2)) == "nan") then
    scan_number(p)
  else
    token_error("invalid token", p - 1)
  end
end

local function throw_parse_error(expected)
  local found = TT == "T_ERROR" and TV or TT
  error("Expected " .. expected .. " but found " .. found .. " at character " .. (TI + 1), 0)
end

local function descend()
  DEPTH = DEPTH + 1
  if DEPTH > C.decode_max_depth then
    error("Found too many nested data structures (" .. DEPTH .. ") at character " .. (POS - 1), 0)
  end
end

-- O token atual já foi lido; ao voltar, o último token do valor foi consumido.
local function parse_value()
  local tt = TT
  if tt == "T_STRING" or tt == "T_NUMBER" or tt == "T_BOOLEAN" then
    return TV
  elseif tt == "T_NULL" then
    return null
  elseif tt == "T_OBJ_BEGIN" then
    descend()
    local obj = {}
    next_token()
    if TT == "T_OBJ_END" then
      DEPTH = DEPTH - 1
      return obj
    end
    while true do
      if TT ~= "T_STRING" then throw_parse_error("object key string") end
      local key = TV
      next_token()
      if TT ~= "T_COLON" then throw_parse_error("colon") end
      next_token()
      obj[key] = parse_value()
      next_token()
      if TT == "T_OBJ_END" then
        DEPTH = DEPTH - 1
        return obj
      end
      if TT ~= "T_COMMA" then throw_parse_error("comma or object end") end
      next_token()
    end
  elseif tt == "T_ARR_BEGIN" then
    descend()
    local arr = {}
    next_token()
    if TT == "T_ARR_END" then
      DEPTH = DEPTH - 1
      return arr
    end
    local i = 1
    while true do
      arr[i] = parse_value()
      next_token()
      if TT == "T_ARR_END" then
        DEPTH = DEPTH - 1
        return arr
      end
      if TT ~= "T_COMMA" then throw_parse_error("comma or array end") end
      next_token()
      i = i + 1
    end
  end
  throw_parse_error("value")
end

local function decode(text)
  S, POS, LEN, DEPTH = text, 1, #text, 0
  if LEN >= 2 and (byte(S, 1) == 0 or byte(S, 2) == 0) then
    error("JSON parser does not support UTF-16 or UTF-32", 0)
  end
  next_token()
  local value = parse_value()
  next_token()
  if TT ~= "T_END" then throw_parse_error("the end") end
  S = nil
  return value
end

-- ===================================================================== instâncias e opções

local function arg_error(index, name, message)
  error("bad argument #" .. index .. " to '" .. name .. "' (" .. message .. ")", 0)
end

local function check_count(name, max, ...)
  if select("#", ...) > max then arg_error(max + 1, name, "found too many arguments") end
end

local function option_bool(name, index, value, current)
  if value == nil then return current end
  if type(value) == "boolean" then return value end
  if value == "on" then return true end
  if value == "off" then return false end
  if type(value) ~= "string" then arg_error(index, name, "string expected, got " .. type(value)) end
  arg_error(index, name, "invalid option '" .. value .. "'")
end

local function option_int(name, index, value, current, min, max)
  if value == nil then return current end
  local n = tonumber(value)
  if not n then arg_error(index, name, "number expected, got " .. type(value)) end
  n = floor(n)
  if n < min or n > max then
    arg_error(index, name, "expected integer between " .. min .. " and " .. max)
  end
  return n
end

local function new_instance()
  local cfg = {
    sparse_convert = false, sparse_ratio = 2, sparse_safe = 10,
    encode_max_depth = 1000, decode_max_depth = 1000,
    encode_number_precision = 14, encode_keep_buffer = true,
    encode_invalid_numbers = false, decode_invalid_numbers = true,
  }
  local M = {null = null, _NAME = "cjson", _VERSION = VERSION}

  function M.encode(...)
    if select("#", ...) ~= 1 then arg_error(1, "encode", "expected 1 argument") end
    C = cfg
    return encode((...))
  end

  function M.decode(...)
    if select("#", ...) ~= 1 then arg_error(1, "decode", "expected 1 argument") end
    local text = ...
    if type(text) == "number" then
      C = cfg
      text = number_text(text)
    elseif type(text) ~= "string" then
      arg_error(1, "decode", "string expected, got " .. type(text))
    end
    C = cfg
    return decode(text)
  end

  function M.encode_sparse_array(...)
    check_count("encode_sparse_array", 3, ...)
    local convert, ratio, safe = ...
    cfg.sparse_convert = option_bool("encode_sparse_array", 1, convert, cfg.sparse_convert)
    cfg.sparse_ratio = option_int("encode_sparse_array", 2, ratio, cfg.sparse_ratio, 0, INT_MAX)
    cfg.sparse_safe = option_int("encode_sparse_array", 3, safe, cfg.sparse_safe, 0, INT_MAX)
    return cfg.sparse_convert, cfg.sparse_ratio, cfg.sparse_safe
  end

  function M.encode_max_depth(...)
    check_count("encode_max_depth", 1, ...)
    cfg.encode_max_depth = option_int("encode_max_depth", 1, (...), cfg.encode_max_depth, 1, INT_MAX)
    return cfg.encode_max_depth
  end

  function M.decode_max_depth(...)
    check_count("decode_max_depth", 1, ...)
    cfg.decode_max_depth = option_int("decode_max_depth", 1, (...), cfg.decode_max_depth, 1, INT_MAX)
    return cfg.decode_max_depth
  end

  function M.encode_number_precision(...)
    check_count("encode_number_precision", 1, ...)
    cfg.encode_number_precision =
      option_int("encode_number_precision", 1, (...), cfg.encode_number_precision, 1, 14)
    return cfg.encode_number_precision
  end

  function M.encode_keep_buffer(...)
    check_count("encode_keep_buffer", 1, ...)
    cfg.encode_keep_buffer = option_bool("encode_keep_buffer", 1, (...), cfg.encode_keep_buffer)
    return cfg.encode_keep_buffer
  end

  function M.decode_invalid_numbers(...)
    check_count("decode_invalid_numbers", 1, ...)
    cfg.decode_invalid_numbers = option_bool("decode_invalid_numbers", 1, (...), cfg.decode_invalid_numbers)
    return cfg.decode_invalid_numbers
  end

  function M.encode_invalid_numbers(...)
    check_count("encode_invalid_numbers", 1, ...)
    local value = ...
    if value ~= nil then
      if value == true or value == "on" then
        cfg.encode_invalid_numbers = true
      elseif value == false or value == "off" then
        cfg.encode_invalid_numbers = false
      elseif value == "null" then
        cfg.encode_invalid_numbers = "null"
      elseif type(value) ~= "string" then
        arg_error(1, "encode_invalid_numbers", "string expected, got " .. type(value))
      else
        arg_error(1, "encode_invalid_numbers", "invalid option '" .. value .. "'")
      end
    end
    return cfg.encode_invalid_numbers
  end

  M.new = new_instance
  return M
end

return new_instance()
