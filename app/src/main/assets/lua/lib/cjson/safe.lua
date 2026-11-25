-- `cjson.safe` do Norns Mobile: o mesmo cjson, mas encode/decode devolvem nil + mensagem em vez de lançar
-- erro (o `require "cjson.safe"` do lua-cjson). Cada módulo tem a própria configuração; `cjson.null` é o
-- mesmo valor nos dois.

local cjson = require("cjson")

local function protect(instance)
  local encode, decode = instance.encode, instance.decode
  function instance.encode(...)
    local ok, result = pcall(encode, ...)
    if ok then return result end
    return nil, result
  end
  function instance.decode(...)
    local ok, result = pcall(decode, ...)
    if ok then return result end
    return nil, result
  end
  function instance.new() return protect(cjson.new()) end
  instance._NAME = "cjson.safe"
  return instance
end

return protect(cjson.new())
