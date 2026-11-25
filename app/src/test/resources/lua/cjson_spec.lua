-- Especificação do módulo cjson (lib/cjson.lua e lib/cjson/safe.lua).
-- O teste Java passa os dois módulos como argumentos e roda cada grupo; este arquivo devolve
-- {grupo = {{name = "...", run = function() ... end}, ...}}. Os valores esperados vêm das regras do
-- lua-cjson 2.1.0 (mensagens, posições, detecção de vetor), não da saída do próprio módulo.
local cjson, safe = ...

local groups = {}
local function group(name)
  local list = {}
  groups[name] = list
  return function(case, run) list[#list + 1] = {name = case, run = run} end
end

local function show(v)
  if type(v) == "string" then return string.format("%q", v) end
  return tostring(v)
end

local function eq(got, want, what)
  if got ~= want then
    error((what or "valor") .. ": veio " .. show(got) .. ", esperado " .. show(want), 0)
  end
end

-- O LuaJ põe "origem:linha " na frente de toda mensagem de erro; o que importa é o resto.
local function strip(msg) return (tostring(msg):gsub("^[^ ]*:%d+ ", "")) end

local function fails(fn, ...)
  local ok, e = pcall(fn, ...)
  if ok then error("esperava um erro, mas voltou " .. show(e), 0) end
  return strip(e)
end

local function deep_equal(a, b)
  if rawequal(a, cjson.null) or rawequal(b, cjson.null) then return rawequal(a, b) end
  if type(a) ~= type(b) then return false end
  if type(a) ~= "table" then return a == b end
  for k, v in pairs(a) do
    if not deep_equal(v, b[k]) then return false end
  end
  for k in pairs(b) do
    if a[k] == nil then return false end
  end
  return true
end

-- ===================================================================== encode
local enc = group("encode")

enc("vetores", function()
  eq(cjson.encode({1, 2, 3}), "[1,2,3]")
  eq(cjson.encode({{1}, {2, {3}}}), "[[1],[2,[3]]]")
  eq(cjson.encode({"a", true, false}), '["a",true,false]')
end)

enc("objetos saem com as chaves em ordem", function()
  eq(cjson.encode({a = 1}), '{"a":1}')
  eq(cjson.encode({b = 2, a = 1, c = 3}), '{"a":1,"b":2,"c":3}')
  eq(cjson.encode({a = {b = {c = "x"}}}), '{"a":{"b":{"c":"x"}}}')
  eq(cjson.encode({a = 1, B = 2}), '{"B":2,"a":1}', "ordem por byte")
  eq(cjson.encode({["9"] = 1, ["10"] = 2}), '{"10":2,"9":1}', "ordem de texto, não numérica")
end)

enc("chaves numéricas que não formam vetor viram texto, antes das chaves de texto", function()
  eq(cjson.encode({b = 1, [2.5] = 1, [-1] = 2}), '{"-1":2,"2.5":1,"b":1}')
  eq(cjson.encode({[0] = "z"}), '{"0":"z"}')
  eq(cjson.encode({1, 2, a = 3}), '{"1":1,"2":2,"a":3}')
  eq(cjson.encode({[0] = "z", [1] = "a"}), '{"0":"z","1":"a"}', "a chave 0 impede o vetor")
end)

enc("tabela vazia é objeto", function()
  eq(cjson.encode({}), "{}")
  eq(cjson.encode({a = {}}), '{"a":{}}')
  eq(cjson.encode({{}}), "[{}]")
end)

enc("valores simples", function()
  eq(cjson.encode(nil), "null")
  eq(cjson.encode(true), "true")
  eq(cjson.encode(false), "false")
  eq(cjson.encode("abc"), '"abc"')
  eq(cjson.encode(5), "5")
  eq(cjson.encode(-0.5), "-0.5")
end)

enc("cjson.null vira null", function()
  eq(cjson.encode(cjson.null), "null")
  eq(cjson.encode({cjson.null}), "[null]")
  eq(cjson.encode({a = cjson.null}), '{"a":null}')
  eq(tostring(cjson.null), "null")
end)

enc("buracos num vetor viram null", function()
  eq(cjson.encode({1, nil, 3}), "[1,null,3]")
  eq(cjson.encode({[1] = 1, [2] = 2, [4] = 4}), "[1,2,null,4]")
  eq(cjson.encode({1, 2, 3, 4, 5, [12] = 12}), "[1,2,3,4,5,null,null,null,null,null,null,12]",
    "12 = 6 itens * razão 2: ainda é vetor")
end)

enc("vetor esparso demais é erro, a não ser que se peça objeto", function()
  eq(fails(cjson.encode, {[1] = 1, [100] = 2}), "Cannot serialise table: excessively sparse array")
  eq(fails(cjson.encode, {[1] = 1, [11] = 2}), "Cannot serialise table: excessively sparse array")
  eq(cjson.encode({[1] = 1, [10] = 2}), "[1,null,null,null,null,null,null,null,null,2]", "10 não passa de safe=10")
  local convert = cjson.new()
  eq(convert.encode_sparse_array(true), true)
  eq(convert.encode({[1] = 1, [100] = 2}), '{"1":1,"100":2}')
  local safe_limit = cjson.new()
  safe_limit.encode_sparse_array(false, 2, 200)
  local text = safe_limit.encode({[1] = 1, [100] = 2})
  local _, nulls = text:gsub("null", "")
  eq(nulls, 98, "vetor de 100 posições")
  local no_ratio = cjson.new()
  no_ratio.encode_sparse_array(false, 0)
  eq(select(2, no_ratio.encode({[1] = 1, [50] = 2}):gsub("null", "")), 48, "razão 0 desliga a verificação")
end)

enc("chave que não é número nem texto", function()
  eq(fails(cjson.encode, {[true] = 1}), "Cannot serialise boolean: table key must be a number or string")
  eq(fails(cjson.encode, {[{}] = 1}), "Cannot serialise table: table key must be a number or string")
end)

enc("tipos sem representação", function()
  eq(fails(cjson.encode, print), "Cannot serialise function: type not supported")
  eq(fails(cjson.encode, {a = {print}}), "Cannot serialise function: type not supported")
  eq(fails(cjson.encode, coroutine.create(function() end)), "Cannot serialise thread: type not supported")
end)

enc("escapes de texto", function()
  eq(cjson.encode('a"b'), '"a\\"b"')
  eq(cjson.encode("a\\b"), '"a\\\\b"')
  eq(cjson.encode("a/b"), '"a\\/b"')
  eq(cjson.encode("\b\f\n\r\t"), '"\\b\\f\\n\\r\\t"')
  eq(cjson.encode("\1\31\127"), '"\\u0001\\u001f\\u007f"')
  eq(cjson.encode("a\0b"), '"a\\u0000b"')
  eq(cjson.encode(""), '""')
  eq(cjson.encode({['a"b'] = 1}), '{"a\\"b":1}')
  eq(cjson.encode({["a/b"] = 1}), '{"a\\/b":1}')
end)

enc("UTF-8 passa sem alteração, inclusive bytes entre 128 e 159", function()
  local text = "\195\169\226\130\172\240\159\152\128" -- é € 😀: 0x82, 0x9F, 0x98 e 0x80 ficam nessa faixa
  eq(cjson.encode(text), '"' .. text .. '"')
  eq(cjson.encode({[text] = text}), '{"' .. text .. '":"' .. text .. '"}')
end)

enc("profundidade", function()
  local limited = cjson.new()
  eq(limited.encode_max_depth(3), 3)
  eq(limited.encode({{{1}}}), "[[[1]]]")
  eq(fails(limited.encode, {{{{1}}}}), "Cannot serialise, excessive nesting (4)")
  eq(fails(limited.encode, {a = {b = {c = {}}}}), "Cannot serialise, excessive nesting (4)")
  local cycle = {}
  cycle.a = cycle
  eq(fails(cjson.encode, cycle), "Cannot serialise, excessive nesting (1001)", "profundidade padrão é 1000")
  local deep = {}
  local node = deep
  for _ = 1, 999 do
    node[1] = {}
    node = node[1]
  end
  eq(#cjson.encode(deep), 2000, "1000 níveis cabem: 999 pares de colchetes e um {} no fundo")
end)

enc("metatables não mudam o resultado", function()
  local t = setmetatable({a = 1}, {__index = function() return "x" end, __eq = function() return true end})
  eq(cjson.encode(t), '{"a":1}')
  eq(cjson.encode(setmetatable({1, 2}, {__index = function() return 9 end})), "[1,2]")
  eq(cjson.encode(setmetatable({}, {__index = function() return 1 end})), "{}")
end)

enc("tabela do usuário nunca é confundida com cjson.null, nem com __eq igual", function()
  local mt = getmetatable(cjson.null)
  local always = function() return true end
  mt.__eq = always -- o LuaJ só chama __eq quando as duas metatables têm a mesma função
  local ok, text = pcall(cjson.encode, setmetatable({a = 1}, {__eq = always}))
  mt.__eq = nil
  eq(ok, true)
  eq(text, '{"a":1}')
end)

enc("exige exatamente um argumento", function()
  eq(fails(cjson.encode), "bad argument #1 to 'encode' (expected 1 argument)")
  eq(fails(cjson.encode, 1, 2), "bad argument #1 to 'encode' (expected 1 argument)")
end)

enc("tabelas grandes", function()
  local big = {}
  for i = 1, 5000 do big[i] = {id = i, name = "item" .. i, tags = {i, i + 1}} end
  local back = cjson.decode(cjson.encode(big))
  eq(#back, 5000)
  eq(back[5000].name, "item5000")
  eq(back[1234].tags[2], 1235)
end)

-- ===================================================================== números
local num = group("numbers")

num("inteiros", function()
  for _, case in ipairs({
    {0, "0"}, {1, "1"}, {-1, "-1"}, {42, "42"}, {100000, "100000"}, {123456789012, "123456789012"},
    {99999999999999, "99999999999999"}, {-99999999999999, "-99999999999999"},
    {2147483647, "2147483647"}, {2 ^ 31, "2147483648"}, {2 ^ 40, "1099511627776"},
  }) do
    eq(cjson.encode(case[1]), case[2], "encode de " .. tostring(case[1]))
  end
end)

num("fracionários com 14 dígitos significativos, como %.14g", function()
  for _, case in ipairs({
    {0.5, "0.5"}, {-0.5, "-0.5"}, {0.1, "0.1"}, {0.1 + 0.2, "0.3"}, {1 / 3, "0.33333333333333"},
    {-1 / 3, "-0.33333333333333"}, {2 / 3, "0.66666666666667"}, {3.14159, "3.14159"},
    {math.pi, "3.1415926535898"}, {123456.789, "123456.789"}, {1234.5678, "1234.5678"}, {100.25, "100.25"},
    {0.0001, "0.0001"}, {0.00012345, "0.00012345"}, {-8.999999999999998, "-9"},
  }) do
    eq(cjson.encode(case[1]), case[2], "encode de " .. case[2])
  end
end)

num("notação científica fora de 1e-4 .. 1e14", function()
  for _, case in ipairs({
    {1e-5, "1e-05"}, {1.5e-7, "1.5e-07"}, {5e-5, "5e-05"}, {0.00001234, "1.234e-05"}, {-2.5e-10, "-2.5e-10"},
    {1e-100, "1e-100"}, {1e14, "1e+14"}, {123456789012346, "1.2345678901235e+14"}, {2 ^ 53, "9.007199254741e+15"},
    {1e15, "1e+15"}, {1234567890123456789, "1.2345678901235e+18"}, {1e20, "1e+20"}, {1.5e20, "1.5e+20"},
    {1e100, "1e+100"}, {1.7976931348623157e308, "1.7976931348623e+308"},
  }) do
    eq(cjson.encode(case[1]), case[2], "encode de " .. case[2])
  end
end)

num("encode_number_precision", function()
  local p = cjson.new()
  eq(p.encode_number_precision(), 14)
  eq(p.encode_number_precision(3), 3)
  for _, case in ipairs({
    {3.14159, "3.14"}, {12, "12"}, {100, "100"}, {1000, "1e+03"}, {1234, "1.23e+03"}, {999.9, "1e+03"},
    {0.5, "0.5"}, {-1.5, "-1.5"}, {0.00012345, "0.000123"},
  }) do
    eq(p.encode(case[1]), case[2], "precisão 3: " .. case[2])
  end
  eq(p.encode_number_precision(1), 1)
  eq(p.encode(0.26), "0.3")
  eq(p.encode(17), "2e+01")
  eq(cjson.encode(math.pi), "3.1415926535898", "outra instância não é afetada")
  eq(fails(p.encode_number_precision, 0), "bad argument #1 to 'encode_number_precision' (expected integer between 1 and 14)")
  eq(fails(p.encode_number_precision, 15), "bad argument #1 to 'encode_number_precision' (expected integer between 1 and 14)")
end)

num("NaN e infinito", function()
  local nan, inf = 0 / 0, math.huge
  eq(fails(cjson.encode, nan), "Cannot serialise number: must not be NaN or Inf")
  eq(fails(cjson.encode, inf), "Cannot serialise number: must not be NaN or Inf")
  eq(fails(cjson.encode, {-inf}), "Cannot serialise number: must not be NaN or Inf")
  eq(fails(cjson.encode, {[inf] = 1}), "Cannot serialise number: must not be NaN or Inf")
  local c = cjson.new()
  eq(c.encode_invalid_numbers(), false)
  eq(c.encode_invalid_numbers(true), true)
  eq(c.encode({nan, inf, -inf}), "[nan,inf,-inf]")
  eq(c.encode_invalid_numbers("null"), "null")
  eq(c.encode({nan, inf, -inf}), "[null,null,null]")
  eq(c.encode_invalid_numbers("off"), false)
  eq(fails(c.encode, nan), "Cannot serialise number: must not be NaN or Inf")
  eq(c.encode_invalid_numbers("on"), true)
  eq(fails(c.encode_invalid_numbers, "maybe"), "bad argument #1 to 'encode_invalid_numbers' (invalid option 'maybe')")
  eq(fails(c.encode_invalid_numbers, 5), "bad argument #1 to 'encode_invalid_numbers' (string expected, got number)")
end)

-- ===================================================================== decode
local dec = group("decode")

dec("valores simples", function()
  eq(cjson.decode("1"), 1)
  eq(cjson.decode("-2.5e2"), -250)
  eq(cjson.decode("true"), true)
  eq(cjson.decode("false"), false)
  eq(rawequal(cjson.decode("null"), cjson.null), true)
  eq(cjson.decode('"abc"'), "abc")
  eq(cjson.decode(' \t\r\n 5 \n'), 5)
  eq(cjson.decode(123), 123, "número vira texto antes de ler")
  eq(cjson.decode(1 / 3), tonumber("0.33333333333333"), "o texto do número tem 14 dígitos, como o %.14g")
end)

dec("números", function()
  eq(cjson.decode("0"), 0)
  eq(cjson.decode("-0"), 0)
  eq(cjson.decode("123"), 123)
  eq(cjson.decode("-45.5"), -45.5)
  eq(cjson.decode("1e3"), 1000)
  eq(cjson.decode("1E+3"), 1000)
  eq(cjson.decode("2.5e-1"), 0.25)
  eq(cjson.decode("0.1"), 0.1)
  eq(cjson.decode("123456789012"), 123456789012)
  eq(cjson.decode("123456789.123456789"), tonumber("123456789.123456789"))
  eq(cjson.decode("1e400"), math.huge)
  eq(cjson.decode("[1,2]")[2], 2)
end)

dec("vetores e objetos", function()
  local t = cjson.decode('{"a":[1,2.5,-3e2,true,false,null,"x"],"b":{},"c":[]}')
  eq(#t.a, 7)
  eq(t.a[1], 1)
  eq(t.a[2], 2.5)
  eq(t.a[3], -300)
  eq(t.a[4], true)
  eq(t.a[5], false)
  eq(rawequal(t.a[6], cjson.null), true)
  eq(t.a[7], "x")
  eq(next(t.b), nil)
  eq(next(t.c), nil)
  local spaced = cjson.decode('  { "k" : [ 1 , { "z" : null } ] }  ')
  eq(spaced.k[1], 1)
  eq(rawequal(spaced.k[2].z, cjson.null), true)
  eq(cjson.decode('{"a":1,"a":2}').a, 2, "a última chave repetida vence")
  eq(cjson.decode('{"1":"x"}')["1"], "x", "chave continua texto")
  local with_null = cjson.decode("[1,null,3]")
  eq(#with_null, 3, "null não abre buraco")
  eq(rawequal(with_null[2], cjson.null), true)
end)

dec("escapes e unicode", function()
  eq(cjson.decode('"\\"\\\\\\/\\b\\f\\n\\r\\t"'), '"\\/\b\f\n\r\t')
  eq(cjson.decode('"\\u0041"'), "A")
  eq(cjson.decode('"\\u00e9"'), "\195\169")
  eq(cjson.decode('"\\u00E9"'), "\195\169", "hexa em maiúsculas")
  eq(cjson.decode('"\\u20ac"'), "\226\130\172")
  eq(cjson.decode('"\\u07ff"'), "\223\191", "último ponto de 2 bytes")
  eq(cjson.decode('"\\u0800"'), "\224\160\128", "primeiro ponto de 3 bytes")
  eq(cjson.decode('"\\uffff"'), "\239\191\191")
  eq(cjson.decode('"\\ud83d\\ude00"'), "\240\159\152\128", "par substituto")
  eq(cjson.decode('"\\ud800\\udc00"'), "\240\144\128\128", "primeiro ponto de 4 bytes")
  eq(cjson.decode('"\\udbff\\udfff"'), "\244\143\191\191", "último ponto válido")
  eq(cjson.decode('"\\u0000"'), "\0")
  eq(cjson.decode('"\\u007f"'), "\127")
  eq(cjson.decode('"a\\u0062c"'), "abc")
  eq(cjson.decode('"\195\169\240\159\152\128"'), "\195\169\240\159\152\128", "UTF-8 cru passa")
  eq(cjson.decode('"a\nb"'), "a\nb", "quebra de linha crua é aceita, como no lua-cjson")
end)

dec("erros de sintaxe, com a posição do lua-cjson", function()
  local function expect(text, message) eq(fails(cjson.decode, text), message, "decode de " .. show(text)) end
  expect("", "Expected value but found T_END at character 1")
  expect("   ", "Expected value but found T_END at character 4")
  expect("[1,2", "Expected comma or array end but found T_END at character 5")
  expect('{"a" 1}', "Expected colon but found T_NUMBER at character 6")
  expect("{1:2}", "Expected object key string but found T_NUMBER at character 2")
  expect('{"a":1 "b":2}', "Expected comma or object end but found T_STRING at character 8")
  expect("[1 2]", "Expected comma or array end but found T_NUMBER at character 4")
  expect("[1,]", "Expected value but found T_ARR_END at character 4")
  expect("{,}", "Expected object key string but found T_COMMA at character 2")
  expect('{"a":}', "Expected value but found T_OBJ_END at character 6")
  expect("nul", "Expected value but found invalid token at character 1")
  expect("[1] x", "Expected the end but found invalid token at character 5")
  expect("[1]]", "Expected the end but found T_ARR_END at character 4")
  expect("1 2", "Expected the end but found T_NUMBER at character 3")
  expect("-", "Expected value but found invalid number at character 1")
  expect('"abc', "Expected value but found unexpected end of string at character 5")
  expect('"a\\q"', "Expected value but found invalid escape code at character 3")
  expect('"\\u12"', "Expected value but found invalid unicode escape code at character 2")
  expect('"\\u12zz"', "Expected value but found invalid unicode escape code at character 2")
  expect('"\\ud83d"', "Expected value but found invalid unicode escape code at character 2")
  expect('"\\ude00"', "Expected value but found invalid unicode escape code at character 2")
  expect('"\\ud83d\\u0041"', "Expected value but found invalid unicode escape code at character 2")
  expect('"\\ude00\\udc00"', "Expected value but found invalid unicode escape code at character 2")
  expect('"ab\\ud83d\\ud83d"', "Expected value but found invalid unicode escape code at character 4")
  expect('[1,"a\\x"]', "Expected value but found invalid escape code at character 6")
  expect('["a" "b\\x"]', "Expected comma or array end but found invalid escape code at character 8")
  expect("\0[", "JSON parser does not support UTF-16 or UTF-32")
  expect("[\0", "JSON parser does not support UTF-16 or UTF-32")
end)

dec("exige um texto", function()
  eq(fails(cjson.decode), "bad argument #1 to 'decode' (expected 1 argument)")
  eq(fails(cjson.decode, "1", "2"), "bad argument #1 to 'decode' (expected 1 argument)")
  eq(fails(cjson.decode, nil), "bad argument #1 to 'decode' (string expected, got nil)")
  eq(fails(cjson.decode, {}), "bad argument #1 to 'decode' (string expected, got table)")
end)

dec("profundidade", function()
  local limited = cjson.new()
  eq(limited.decode_max_depth(3), 3)
  eq(limited.decode("[[[1]]]")[1][1][1], 1)
  eq(fails(limited.decode, "[[[[1]]]]"), "Found too many nested data structures (4) at character 4")
  eq(fails(limited.decode, '{"a":{"a":{"a":{}}}}'), "Found too many nested data structures (4) at character 16")
  eq(limited.decode("[[1],[2],[3]]")[3][1], 3, "irmãos não somam profundidade")
  eq(fails(cjson.decode, string.rep("[", 1001)), "Found too many nested data structures (1001) at character 1001")
  local deep = cjson.decode(string.rep("[", 1000) .. string.rep("]", 1000))
  local levels = 0
  while type(deep[1]) == "table" do
    deep = deep[1]
    levels = levels + 1
  end
  eq(levels, 999, "1000 níveis cabem")
end)

dec("números fora do padrão JSON (decode_invalid_numbers é verdadeiro por padrão)", function()
  eq(cjson.decode_invalid_numbers(), true)
  eq(cjson.decode("0x10"), 16)
  eq(cjson.decode("0XfF"), 255)
  eq(cjson.decode("-0x10"), -16)
  eq(cjson.decode("01"), 1)
  eq(cjson.decode("1."), 1)
  eq(cjson.decode("-.5"), -0.5)
  eq(cjson.decode("inf"), math.huge)
  eq(cjson.decode("-inf"), -math.huge)
  eq(cjson.decode("Infinity"), math.huge)
  local nan = cjson.decode("nan")
  eq(nan ~= nan, true, "nan")
  eq(cjson.decode("[0x1,2]")[1], 1)
end)

dec("com decode_invalid_numbers desligado", function()
  local strict = cjson.new()
  eq(strict.decode_invalid_numbers(false), false)
  eq(strict.decode("10"), 10)
  eq(strict.decode("-0.5"), -0.5)
  eq(strict.decode("0"), 0)
  eq(strict.decode("0.5"), 0.5)
  eq(fails(strict.decode, "0x10"), "Expected value but found invalid number at character 1")
  eq(fails(strict.decode, "-0x10"), "Expected value but found invalid number at character 1")
  eq(fails(strict.decode, "01"), "Expected value but found invalid number at character 1")
  eq(fails(strict.decode, "-01"), "Expected value but found invalid number at character 1")
  eq(fails(strict.decode, "-inf"), "Expected value but found invalid number at character 1")
  eq(fails(strict.decode, "-nan"), "Expected value but found invalid number at character 1")
  eq(fails(strict.decode, "inf"), "Expected value but found invalid token at character 1")
  eq(fails(strict.decode, "[1,nan]"), "Expected value but found invalid token at character 4")
  eq(strict.decode_invalid_numbers("on"), true)
  eq(strict.decode("0x10"), 16)
end)

-- ===================================================================== ida e volta
local round = group("roundtrip")

local function lcg(seed)
  local s = seed
  return function(n)
    s = (s * 75 + 74) % 65537
    return s % n
  end
end

local function gen(rnd, depth)
  local kind = rnd(depth > 0 and 8 or 6)
  if kind == 0 then return rnd(1000) - 500 end
  if kind == 1 then return (rnd(20001) - 10000) / 100 end
  if kind == 2 then return rnd(2) == 0 end
  if kind == 3 then
    return "s" .. rnd(100) .. ({"", '"', "\\", "/", "\n", "\195\169", "\226\130\172", "\0"})[rnd(8) + 1]
  end
  if kind == 4 then return cjson.null end
  if kind == 5 then return rnd(1000) / 8 end
  local t = {}
  if kind == 6 then
    for i = 1, rnd(5) + 1 do t[i] = gen(rnd, depth - 1) end
  else
    for _ = 1, rnd(5) + 1 do t["k" .. rnd(50)] = gen(rnd, depth - 1) end
  end
  return t
end

round("estruturas aleatórias fixas voltam iguais", function()
  local rnd = lcg(12345)
  for i = 1, 300 do
    local value = gen(rnd, 4)
    local text = cjson.encode(value)
    local back = cjson.decode(text)
    if not deep_equal(value, back) then error("rodada " .. i .. " não voltou igual: " .. text, 0) end
    eq(cjson.encode(back), text, "rodada " .. i .. ": encode(decode(texto)) repete o texto")
  end
end)

round("estado típico de script", function()
  local state = {
    name = "pad \"A\"", steps = {1, 0, 1, 1, 0, 0.5, 0.25}, bpm = 120.5, running = false,
    sample = {path = "/home/we/dust/audio/x.wav", start = 0.125, loop = true}, note = cjson.null,
  }
  local text = cjson.encode(state)
  eq(text, '{"bpm":120.5,"name":"pad \\"A\\"","note":null,"running":false,'
    .. '"sample":{"loop":true,"path":"\\/home\\/we\\/dust\\/audio\\/x.wav","start":0.125},'
    .. '"steps":[1,0,1,1,0,0.5,0.25]}')
  eq(deep_equal(state, cjson.decode(text)), true)
end)

-- ===================================================================== opções e instâncias
local opts = group("options")

opts("valores padrão", function()
  local c = cjson.new()
  eq(c.encode_max_depth(), 1000)
  eq(c.decode_max_depth(), 1000)
  eq(c.encode_number_precision(), 14)
  eq(c.encode_keep_buffer(), true)
  eq(c.encode_invalid_numbers(), false)
  eq(c.decode_invalid_numbers(), true)
  local convert, ratio, safe_limit = c.encode_sparse_array()
  eq(convert, false)
  eq(ratio, 2)
  eq(safe_limit, 10)
  eq(c._NAME, "cjson")
  eq(c._VERSION, "2.1.0")
end)

opts("encode_sparse_array devolve e guarda os três valores", function()
  local c = cjson.new()
  local convert, ratio, safe_limit = c.encode_sparse_array(true, 5, 20)
  eq(convert, true)
  eq(ratio, 5)
  eq(safe_limit, 20)
  convert, ratio, safe_limit = c.encode_sparse_array()
  eq(convert, true)
  eq(ratio, 5)
  eq(safe_limit, 20)
  eq(c.encode_sparse_array("off"), false)
  eq(fails(c.encode_sparse_array, false, -1), "bad argument #2 to 'encode_sparse_array' (expected integer between 0 and 2147483647)")
  eq(fails(c.encode_sparse_array, false, 1, 2, 3), "bad argument #4 to 'encode_sparse_array' (found too many arguments)")
end)

opts("profundidades", function()
  local c = cjson.new()
  eq(c.encode_max_depth(5), 5)
  eq(c.encode_max_depth(), 5)
  eq(c.decode_max_depth(7), 7)
  eq(c.decode_max_depth(), 7)
  eq(fails(c.encode_max_depth, 0), "bad argument #1 to 'encode_max_depth' (expected integer between 1 and 2147483647)")
  eq(fails(c.decode_max_depth, 0), "bad argument #1 to 'decode_max_depth' (expected integer between 1 and 2147483647)")
  eq(fails(c.encode_max_depth, "x"), "bad argument #1 to 'encode_max_depth' (number expected, got string)")
  eq(fails(c.encode_max_depth, 1, 2), "bad argument #2 to 'encode_max_depth' (found too many arguments)")
end)

opts("opções booleanas", function()
  local c = cjson.new()
  eq(c.encode_keep_buffer(false), false)
  eq(c.encode_keep_buffer(), false)
  eq(c.encode_keep_buffer("on"), true)
  eq(c.decode_invalid_numbers("off"), false)
  eq(fails(c.decode_invalid_numbers, "maybe"), "bad argument #1 to 'decode_invalid_numbers' (invalid option 'maybe')")
  eq(fails(c.decode_invalid_numbers, 5), "bad argument #1 to 'decode_invalid_numbers' (string expected, got number)")
end)

opts("cada instância tem a própria configuração e o mesmo null", function()
  local a, b = cjson.new(), cjson.new()
  a.encode_max_depth(1)
  eq(fails(a.encode, {{}}), "Cannot serialise, excessive nesting (2)")
  eq(b.encode({{}}), "[{}]")
  eq(cjson.encode({{}}), "[{}]")
  eq(rawequal(a.null, cjson.null), true)
  eq(rawequal(b.null, safe.null), true)
  eq(type(a.new), "function")
  eq(rawequal(cjson.new(), cjson), false)
end)

-- ===================================================================== cjson.safe
local sf = group("safe")

sf("devolve nil e a mensagem em vez de lançar erro", function()
  local value, message = safe.decode("[1,")
  eq(value, nil)
  eq(strip(message), "Expected value but found T_END at character 4")
  value, message = safe.encode(print)
  eq(value, nil)
  eq(strip(message), "Cannot serialise function: type not supported")
  value, message = safe.encode()
  eq(value, nil)
  eq(strip(message), "bad argument #1 to 'encode' (expected 1 argument)")
  value, message = safe.decode(nil)
  eq(value, nil)
  eq(strip(message), "bad argument #1 to 'decode' (string expected, got nil)")
end)

sf("quando dá certo funciona como o cjson", function()
  eq(safe.encode({1, {a = true}}), '[1,{"a":true}]')
  eq(safe.decode("[1,2]")[2], 2)
  eq(rawequal(safe.decode("null"), safe.null), true)
  eq(rawequal(safe.null, cjson.null), true)
  eq(safe._NAME, "cjson.safe")
  eq(safe._VERSION, "2.1.0")
end)

sf("configuração separada do cjson e de new()", function()
  eq(safe.encode_max_depth(1), 1)
  local value, message = safe.encode({{}})
  eq(value, nil)
  eq(strip(message), "Cannot serialise, excessive nesting (2)")
  eq(cjson.encode({{}}), "[{}]", "o módulo cjson não foi afetado")
  local other = safe.new()
  eq(other.encode({{}}), "[{}]", "new() começa com a configuração padrão")
  eq(other._NAME, "cjson.safe")
  other.encode_max_depth(1)
  local nothing, why = other.encode({{}})
  eq(nothing, nil)
  eq(strip(why), "Cannot serialise, excessive nesting (2)")
end)

return groups
