-- nornsmobile_shell: subconjunto POSIX de os.execute e io.popen para o Norns Mobile.
--
-- Scripts do norns chamam o shell para tarefas simples de arquivo (mkdir, rm, cp...). O Android não
-- oferece esse shell aos apps, então este módulo interpreta um subconjunto pequeno e faz o trabalho
-- pelas funções do host, que só enxergam a pasta dust (e traduzem /home/we/... para ela).
--
-- Suportado: mkdir, rm, cp, mv, touch, cat, ls, cd, echo, true, false; os operadores  ;  &&  ||  e
-- um & no fim da linha; aspas simples e duplas; curingas (* e ?) no último componente do caminho;
-- redirecionamentos de saída para /dev/null ou para um arquivo (>, >>, 2>/dev/null, 2>&1).
-- Qualquer outra coisa (pipes, $VAR, crase, outros programas) continua sem suporte e devolve
-- nil + "não é suportado", como antes.

local M = {}

local host = _host
local HOME = "/home/we"
local MAX_COMMAND = 16384
local reported = {}

-- Para onde vai o que os comandos imprimem quando não é capturado; os testes podem trocar.
M.write_stdout = function(text) io.stdout:write(text) end
M.write_stderr = function(text) io.stderr:write(text) end

local function note_unsupported(what)
  if reported[what] then return end
  reported[what] = true
  if host and type(host.log) == "function" then
    host.log("Shell: '" .. what .. "' não é suportado no Norns Mobile", false)
  end
end

-- ===================================================================== caminhos

local function is_dir(p) return host.list_dir(p) ~= nil end
local function exists(p) return host.file_exists(p) == true end

local function strip_trailing_slashes(p)
  local s = (string.gsub(p, "/+$", ""))
  if s == "" and p ~= "" then return "/" end
  return s
end

local function basename(p)
  local s = strip_trailing_slashes(p)
  if s == "/" then return "/" end
  return (string.match(s, "([^/]*)$"))
end

local function dirname(p)
  local s = strip_trailing_slashes(p)
  if s == "/" then return "/" end
  local d = string.match(s, "^(.*)/[^/]*$")
  if d == nil then return "." end
  if d == "" then return "/" end
  return d
end

local function join(dir, name)
  if string.sub(dir, -1) == "/" then return dir .. name end
  return dir .. "/" .. name
end

-- Caminhos relativos valem a partir do "cd" mais recente; sem "cd", a partir da raiz dust.
local function absolute(ctx, p)
  if ctx.cwd == nil or string.sub(p, 1, 1) == "/" then return p end
  return join(ctx.cwd, p)
end

-- ===================================================================== análise da linha

-- Cada palavra é uma lista de {caractere, entreAspas}; assim um curinga só vale sem aspas.
local function word_text(word)
  local t = {}
  for i = 1, #word do t[i] = word[i][1] end
  return table.concat(t)
end

local function is_magic(entry)
  return not entry[2] and (entry[1] == "*" or entry[1] == "?")
end

local function has_glob(word)
  for i = 1, #word do
    if is_magic(word[i]) then return true end
  end
  return false
end

-- Divide a linha em palavras, operadores (; && || &) e redirecionamentos de saída.
-- Devolve nil, motivo quando a linha usa algo que não é suportado.
local function tokenize(line)
  local tokens = {}
  local cur = nil
  local i, n = 1, #line

  local function push(ch, quoted)
    cur = cur or {}
    cur[#cur + 1] = {ch, quoted}
  end
  local function end_word()
    if cur then
      tokens[#tokens + 1] = {kind = "word", word = cur}
      cur = nil
    end
  end
  local function op(text)
    end_word()
    tokens[#tokens + 1] = {kind = "op", text = text}
  end

  while i <= n do
    local c = string.sub(line, i, i)
    if c == "'" then
      local j = string.find(line, "'", i + 1, true)
      if not j then return nil, "aspas simples sem fechar" end
      cur = cur or {}
      for k = i + 1, j - 1 do push(string.sub(line, k, k), true) end
      i = j + 1
    elseif c == '"' then
      cur = cur or {}
      i = i + 1
      local closed = false
      while i <= n do
        local d = string.sub(line, i, i)
        if d == '"' then
          closed = true
          i = i + 1
          break
        end
        if d == "$" or d == "`" then return nil, "expansão de variável ou de comando" end
        if d == "\\" and i < n then
          local e = string.sub(line, i + 1, i + 1)
          if e == '"' or e == "\\" or e == "$" or e == "`" then
            push(e, true)
            i = i + 2
          elseif e == "\n" then
            i = i + 2
          else
            push("\\", true)
            i = i + 1
          end
        else
          push(d, true)
          i = i + 1
        end
      end
      if not closed then return nil, "aspas duplas sem fechar" end
    elseif c == "\\" then
      if i == n then
        push("\\", true)
        i = i + 1
      else
        local e = string.sub(line, i + 1, i + 1)
        if e ~= "\n" then push(e, true) end
        i = i + 2
      end
    elseif c == "$" or c == "`" then
      return nil, "expansão de variável ou de comando"
    elseif c == " " or c == "\t" or c == "\r" then
      end_word()
      i = i + 1
    elseif c == "\n" or c == ";" then
      op(";")
      i = i + 1
    elseif c == "&" then
      local nx = string.sub(line, i + 1, i + 1)
      if nx == "&" then
        op("&&")
        i = i + 2
      elseif nx == ">" then
        return nil, "redirecionamento &>"
      else
        op("&")
        i = i + 1
      end
    elseif c == "|" then
      if string.sub(line, i + 1, i + 1) == "|" then
        op("||")
        i = i + 2
      else
        return nil, "pipe (|)"
      end
    elseif c == "<" then
      return nil, "redirecionamento de entrada (<)"
    elseif c == "(" or c == ")" then
      return nil, "subshell"
    elseif c == ">" then
      -- "2>" e "1>" só valem quando o número vem colado no ">"
      local fd = 1
      if cur and #cur == 1 and not cur[1][2] and (cur[1][1] == "1" or cur[1][1] == "2") then
        fd = tonumber(cur[1][1])
        cur = nil
      else
        end_word()
      end
      local append = false
      i = i + 1
      if string.sub(line, i, i) == ">" then
        append = true
        i = i + 1
      end
      if string.sub(line, i, i) == "&" then
        local target = string.match(line, "^&([12])", i)
        if not target then return nil, "redirecionamento >&" end
        tokens[#tokens + 1] = {kind = "redir", fd = fd, dup = tonumber(target)}
        i = i + 2
      else
        tokens[#tokens + 1] = {kind = "redir", fd = fd, append = append}
      end
    elseif c == "~" and cur == nil
        and (i == n or string.find(string.sub(line, i + 1, i + 1), "^[%s/;&|>]")) then
      for k = 1, #HOME do push(string.sub(HOME, k, k), true) end
      i = i + 1
    else
      push(c, false)
      i = i + 1
    end
  end
  end_word()
  return tokens
end

-- Agrupa os tokens em comandos simples ligados por ; && ||.
local function parse(line)
  local tokens, reason = tokenize(line)
  if not tokens then return nil, reason end
  local commands = {}
  local cmd = {words = {}, redirs = {}, link = ";"}
  local pending_redir = nil
  local background = false

  local function finish(next_link)
    if #cmd.words > 0 or #cmd.redirs > 0 then
      commands[#commands + 1] = cmd
    end
    cmd = {words = {}, redirs = {}, link = next_link}
  end

  for idx = 1, #tokens do
    local t = tokens[idx]
    if pending_redir then
      if t.kind ~= "word" then return nil, "redirecionamento sem destino" end
      pending_redir.target = t.word
      pending_redir = nil
    elseif t.kind == "word" then
      cmd.words[#cmd.words + 1] = t.word
    elseif t.kind == "redir" then
      cmd.redirs[#cmd.redirs + 1] = t
      if not t.dup then pending_redir = t end
    elseif t.text == "&" then
      local rest = false
      for k = idx + 1, #tokens do
        if not (tokens[k].kind == "op" and tokens[k].text == ";") then rest = true end
      end
      if rest then return nil, "comando em segundo plano no meio da linha" end
      background = true
    else
      finish(t.text)
    end
  end
  if pending_redir then return nil, "redirecionamento sem destino" end
  finish(";")
  return commands, nil, background
end

-- ===================================================================== expansão de palavras

local function glob_pattern(word, from, to)
  local p = {"^"}
  for i = from, to do
    local ch, quoted = word[i][1], word[i][2]
    if not quoted and ch == "*" then
      p[#p + 1] = ".*"
    elseif not quoted and ch == "?" then
      p[#p + 1] = "."
    elseif string.find(ch, "^%w$") then
      p[#p + 1] = ch
    else
      p[#p + 1] = "%" .. ch
    end
  end
  p[#p + 1] = "$"
  return table.concat(p)
end

-- Expande * e ? no último componente do caminho; sem nenhum resultado a palavra fica como está.
local function expand_glob(ctx, word)
  local last_slash = 0
  for i = 1, #word do
    if word[i][1] == "/" then last_slash = i end
  end
  for i = 1, last_slash do
    if is_magic(word[i]) then return nil, "curinga em diretório intermediário" end
  end
  local dir_chars = {}
  for i = 1, last_slash do dir_chars[i] = word[i][1] end
  local dir_text = table.concat(dir_chars)
  local names = host.list_dir(absolute(ctx, dir_text == "" and "." or dir_text))
  local pattern = glob_pattern(word, last_slash + 1, #word)
  local dotted = word[last_slash + 1] ~= nil and word[last_slash + 1][1] == "."
  local out = {}
  if names then
    for i = 1, #names do
      local name = names[i]
      if (dotted or string.sub(name, 1, 1) ~= ".") and string.find(name, pattern) then
        out[#out + 1] = dir_text .. name
      end
    end
  end
  if #out == 0 then return {word_text(word)} end
  return out
end

local function expand_words(ctx, words)
  local argv = {}
  for i = 1, #words do
    if has_glob(words[i]) then
      local list, reason = expand_glob(ctx, words[i])
      if not list then return nil, reason end
      for k = 1, #list do argv[#argv + 1] = list[k] end
    else
      argv[#argv + 1] = word_text(words[i])
    end
  end
  return argv
end

-- ===================================================================== comandos

local commands = {}

local LONG_OPTIONS = {
  mkdir = {["--parents"] = "p", ["--verbose"] = "v"},
  rm = {["--recursive"] = "r", ["--force"] = "f", ["--verbose"] = "v"},
  cp = {["--recursive"] = "r", ["--force"] = "f", ["--verbose"] = "v", ["--no-clobber"] = "n"},
  mv = {["--force"] = "f", ["--verbose"] = "v", ["--no-clobber"] = "n"},
  ls = {["--all"] = "a", ["--almost-all"] = "A"},
  touch = {["--no-create"] = "c"},
}

-- allowed: letra -> "bool" ou "arg". Devolve opções e operandos, ou nil e a mensagem.
local function parse_flags(name, argv, allowed)
  local flags, operands = {}, {}
  local only_operands = false
  local i = 1
  while i <= #argv do
    local a = argv[i]
    if only_operands or a == "-" or string.sub(a, 1, 1) ~= "-" then
      operands[#operands + 1] = a
    elseif a == "--" then
      only_operands = true
    elseif string.sub(a, 1, 2) == "--" then
      local letter = LONG_OPTIONS[name] and LONG_OPTIONS[name][a]
      if not letter then return nil, name .. ": opção não suportada: " .. a end
      flags[letter] = true
    else
      for k = 2, #a do
        local ch = string.sub(a, k, k)
        local kind = allowed[ch]
        if not kind then return nil, name .. ": opção não suportada: -" .. ch end
        if kind == "arg" then
          local value = string.sub(a, k + 1)
          if value == "" then
            i = i + 1
            value = argv[i]
          end
          flags[ch] = value or true
          break
        end
        flags[ch] = true
      end
    end
    i = i + 1
  end
  return flags, operands
end

local function bad_option(ctx, std, message)
  ctx.unsupported = ctx.unsupported or message
  std.err(message .. "\n")
  return 2
end

local function missing_operand(std, name)
  std.err(name .. ": falta operando\n")
  return 1
end

commands.mkdir = function(ctx, argv, std)
  local flags, operands = parse_flags("mkdir", argv, {p = "bool", v = "bool", m = "arg"})
  if not flags then return bad_option(ctx, std, operands) end
  if #operands == 0 then return missing_operand(std, "mkdir") end
  local status = 0
  for i = 1, #operands do
    local arg = operands[i]
    local p = absolute(ctx, arg)
    if exists(p) then
      if not flags.p or not is_dir(p) then
        std.err("mkdir: não foi possível criar o diretório '" .. arg .. "': Arquivo existe\n")
        status = 1
      end
    elseif not flags.p and not is_dir(dirname(p)) then
      std.err("mkdir: não foi possível criar o diretório '" .. arg .. "': Arquivo ou diretório inexistente\n")
      status = 1
    elseif not host.mkdir(p) then
      std.err("mkdir: não foi possível criar o diretório '" .. arg .. "'\n")
      status = 1
    elseif flags.v then
      std.out("mkdir: criado o diretório '" .. arg .. "'\n")
    end
  end
  return status
end

commands.rm = function(ctx, argv, std)
  local flags, operands = parse_flags("rm", argv, {r = "bool", R = "bool", f = "bool", v = "bool"})
  if not flags then return bad_option(ctx, std, operands) end
  local recursive = flags.r or flags.R
  if #operands == 0 then
    if flags.f then return 0 end
    return missing_operand(std, "rm")
  end
  local status = 0
  for i = 1, #operands do
    local arg = operands[i]
    local base = basename(arg)
    local p = absolute(ctx, arg)
    if base == "." or base == ".." then
      std.err("rm: não é permitido remover '.' nem '..': '" .. arg .. "'\n")
      status = 1
    elseif not exists(p) then
      if not flags.f then
        std.err("rm: não foi possível remover '" .. arg .. "': Arquivo ou diretório inexistente\n")
        status = 1
      end
    elseif is_dir(p) then
      if not recursive then
        std.err("rm: não foi possível remover '" .. arg .. "': É um diretório\n")
        status = 1
      elseif not host.remove_all(p) then
        std.err("rm: não foi possível remover '" .. arg .. "'\n")
        status = 1
      elseif flags.v then
        std.out("removido '" .. arg .. "'\n")
      end
    elseif not host.remove(p) then
      std.err("rm: não foi possível remover '" .. arg .. "'\n")
      status = 1
    elseif flags.v then
      std.out("removido '" .. arg .. "'\n")
    end
  end
  return status
end

-- Destino de cada origem em cp e mv: dentro da pasta de destino, se ela existe.
local function target_for(dst, src)
  if is_dir(dst) then return join(dst, basename(src)) end
  return dst
end

commands.cp = function(ctx, argv, std)
  local flags, operands = parse_flags("cp", argv, {r = "bool", R = "bool", a = "bool", f = "bool", v = "bool", n = "bool"})
  if not flags then return bad_option(ctx, std, operands) end
  local recursive = flags.r or flags.R or flags.a
  if #operands < 2 then
    std.err("cp: falta o operando de destino\n")
    return 1
  end
  local dst_arg = operands[#operands]
  local dst = absolute(ctx, dst_arg)
  if #operands > 2 and not is_dir(dst) then
    std.err("cp: o alvo '" .. dst_arg .. "' não é um diretório\n")
    return 1
  end
  local status = 0
  for i = 1, #operands - 1 do
    local arg = operands[i]
    local src = absolute(ctx, arg)
    if not exists(src) then
      std.err("cp: não foi possível obter estado de '" .. arg .. "': Arquivo ou diretório inexistente\n")
      status = 1
    elseif is_dir(src) and not recursive then
      std.err("cp: -r não especificado; omitindo o diretório '" .. arg .. "'\n")
      status = 1
    elseif flags.n and exists(target_for(dst, src)) then
      -- cp -n não sobrescreve
    elseif not host.copy(src, dst, recursive == true) then
      std.err("cp: não foi possível copiar '" .. arg .. "' para '" .. dst_arg .. "'\n")
      status = 1
    elseif flags.v then
      std.out("'" .. arg .. "' -> '" .. dst_arg .. "'\n")
    end
  end
  return status
end

commands.mv = function(ctx, argv, std)
  local flags, operands = parse_flags("mv", argv, {f = "bool", v = "bool", n = "bool"})
  if not flags then return bad_option(ctx, std, operands) end
  if #operands < 2 then
    std.err("mv: falta o operando de destino\n")
    return 1
  end
  local dst_arg = operands[#operands]
  local dst = absolute(ctx, dst_arg)
  if #operands > 2 and not is_dir(dst) then
    std.err("mv: o alvo '" .. dst_arg .. "' não é um diretório\n")
    return 1
  end
  local status = 0
  for i = 1, #operands - 1 do
    local arg = operands[i]
    local src = absolute(ctx, arg)
    local target = target_for(dst, src)
    if not exists(src) then
      std.err("mv: não foi possível obter estado de '" .. arg .. "': Arquivo ou diretório inexistente\n")
      status = 1
    elseif flags.n and exists(target) then
      -- mv -n não sobrescreve
    elseif not host.rename(src, target) then
      std.err("mv: não foi possível mover '" .. arg .. "' para '" .. dst_arg .. "'\n")
      status = 1
    elseif flags.v then
      std.out("'" .. arg .. "' -> '" .. dst_arg .. "'\n")
    end
  end
  return status
end

commands.touch = function(ctx, argv, std)
  local flags, operands = parse_flags("touch", argv, {c = "bool"})
  if not flags then return bad_option(ctx, std, operands) end
  if #operands == 0 then return missing_operand(std, "touch") end
  local status = 0
  for i = 1, #operands do
    local arg = operands[i]
    local p = absolute(ctx, arg)
    if exists(p) then
      if not is_dir(p) then host.write_file(p, "", true) end
    elseif not flags.c then
      if not is_dir(dirname(p)) or not host.write_file(p, "", false) then
        std.err("touch: não foi possível tocar '" .. arg .. "': Arquivo ou diretório inexistente\n")
        status = 1
      end
    end
  end
  return status
end

commands.cat = function(ctx, argv, std)
  local flags, operands = parse_flags("cat", argv, {})
  if not flags then return bad_option(ctx, std, operands) end
  local status = 0
  for i = 1, #operands do
    local arg = operands[i]
    if arg ~= "-" then
      local p = absolute(ctx, arg)
      local data = (not is_dir(p)) and host.read_file(p) or nil
      if data then
        std.out(data)
      elseif is_dir(p) then
        std.err("cat: " .. arg .. ": É um diretório\n")
        status = 1
      else
        std.err("cat: " .. arg .. ": Arquivo ou diretório inexistente\n")
        status = 1
      end
    end
  end
  return status
end

local function list_names(p, flags)
  local names = host.list_dir(p) or {}
  local out = {}
  if flags.a then
    out[#out + 1] = "."
    out[#out + 1] = ".."
  end
  for i = 1, #names do
    local name = names[i]
    if flags.a or flags.A or string.sub(name, 1, 1) ~= "." then out[#out + 1] = name end
  end
  return out
end

commands.ls = function(ctx, argv, std)
  local flags, operands = parse_flags("ls", argv, {["1"] = "bool", a = "bool", A = "bool"})
  if not flags then return bad_option(ctx, std, operands) end
  if #operands == 0 then operands = {"."} end
  local status = 0
  local files, dirs = {}, {}
  for i = 1, #operands do
    local arg = operands[i]
    local p = absolute(ctx, arg)
    if is_dir(p) then
      dirs[#dirs + 1] = {arg = arg, path = p}
    elseif exists(p) then
      files[#files + 1] = arg
    else
      std.err("ls: não foi possível acessar '" .. arg .. "': Arquivo ou diretório inexistente\n")
      status = 2
    end
  end
  for i = 1, #files do std.out(files[i] .. "\n") end
  for i = 1, #dirs do
    if #operands > 1 then
      if i > 1 or #files > 0 then std.out("\n") end
      std.out(dirs[i].arg .. ":\n")
    end
    local names = list_names(dirs[i].path, flags)
    for k = 1, #names do std.out(names[k] .. "\n") end
  end
  return status
end

commands.cd = function(ctx, argv, std)
  if #argv > 1 then
    std.err("cd: argumentos demais\n")
    return 1
  end
  local arg = argv[1] or HOME
  local p = absolute(ctx, arg)
  if not is_dir(p) then
    std.err("cd: " .. arg .. ": Arquivo ou diretório inexistente\n")
    return 1
  end
  ctx.cwd = p
  return 0
end

commands.echo = function(ctx, argv, std)
  local newline = true
  local first = 1
  if argv[1] == "-n" then
    newline = false
    first = 2
  end
  local parts = {}
  for i = first, #argv do parts[#parts + 1] = argv[i] end
  std.out(table.concat(parts, " ") .. (newline and "\n" or ""))
  return 0
end

commands["true"] = function() return 0 end
commands["false"] = function() return 1 end
commands[":"] = function() return 0 end

-- ===================================================================== execução

-- Abre os redirecionamentos (como o shell, antes de rodar o comando) e devolve o destino de
-- cada descritor: "stdout", "stderr", "null" ou {file = caminho}.
local function open_redirects(ctx, redirs, std)
  local dest = {[1] = "stdout", [2] = "stderr"}
  for i = 1, #redirs do
    local r = redirs[i]
    if r.dup then
      dest[r.fd] = dest[r.dup]
    else
      local text = word_text(r.target)
      if text == "/dev/null" then
        dest[r.fd] = "null"
      else
        if has_glob(r.target) then return nil, "curinga no destino do redirecionamento" end
        local p = absolute(ctx, text)
        if is_dir(p) or not is_dir(dirname(p)) or not host.write_file(p, "", r.append == true) then
          std.err("sh: não foi possível criar '" .. text .. "': Arquivo ou diretório inexistente\n")
          return false
        end
        dest[r.fd] = {file = p}
      end
    end
  end
  return dest
end

local function deliver(ctx, dest, text)
  if text == "" or dest == "null" then return end
  if dest == "stdout" then
    ctx.out[#ctx.out + 1] = text
  elseif dest == "stderr" then
    ctx.err[#ctx.err + 1] = text
  else
    host.write_file(dest.file, text, true)
  end
end

local function run_simple(ctx, cmd)
  local out_buf, err_buf = {}, {}
  local std = {
    out = function(s) out_buf[#out_buf + 1] = s end,
    err = function(s) err_buf[#err_buf + 1] = s end,
  }
  local dest, why = open_redirects(ctx, cmd.redirs, std)
  if dest == nil then
    ctx.unsupported = ctx.unsupported or why
    return 2
  end
  local status
  if dest == false then
    status = 1
    deliver(ctx, "stderr", table.concat(err_buf))
    return status
  end
  local argv, reason = expand_words(ctx, cmd.words)
  if not argv then
    ctx.unsupported = ctx.unsupported or reason
    return 2
  end
  if #argv == 0 then
    status = 0
  else
    local name = table.remove(argv, 1)
    local handler = commands[name]
    if handler == nil then
      note_unsupported(name)
      ctx.unsupported = ctx.unsupported or name
      std.err("sh: " .. name .. ": comando não suportado\n")
      status = 127
    else
      status = handler(ctx, argv, std)
    end
  end
  deliver(ctx, dest[1], table.concat(out_buf))
  deliver(ctx, dest[2], table.concat(err_buf))
  return status
end

-- Roda a linha inteira. Devolve o contexto (com .out, .err e .unsupported) e o código de saída.
local function run(line)
  local ctx = {cwd = nil, out = {}, err = {}, unsupported = nil}
  if #line > MAX_COMMAND then
    ctx.unsupported = "linha de comando longa demais"
    return ctx, 2
  end
  local list, reason, background = parse(line)
  if not list then
    note_unsupported(reason)
    ctx.unsupported = reason
    return ctx, 2
  end
  local status = 0
  for i = 1, #list do
    local cmd = list[i]
    local link = cmd.link
    local should_run = i == 1 or link == ";" or (link == "&&" and status == 0) or (link == "||" and status ~= 0)
    if should_run then status = run_simple(ctx, cmd) end
  end
  if background then status = 0 end
  return ctx, status
end

local function flush_stderr(ctx)
  local text = table.concat(ctx.err)
  if text ~= "" then M.write_stderr(text) end
end

function M.execute(cmd)
  if type(cmd) ~= "string" then return nil, "os.execute não é suportado" end
  local ctx, status = run(cmd)
  local text = table.concat(ctx.out)
  if text ~= "" then M.write_stdout(text) end
  flush_stderr(ctx)
  if ctx.unsupported then
    return nil, "os.execute não é suportado: " .. ctx.unsupported, status
  end
  if status == 0 then return true, "exit", 0 end
  return nil, "exit", status
end

-- FileR é a metatabela dos arquivos de leitura do boot.lua; a saída do comando vira o conteúdo.
function M.popen(cmd, mode, FileR)
  if type(cmd) ~= "string" or type(FileR) ~= "table" then return nil, "io.popen não é suportado" end
  if mode ~= nil and mode ~= "r" then
    return nil, "io.popen não é suportado no modo '" .. tostring(mode) .. "'"
  end
  local ctx, status = run(cmd)
  flush_stderr(ctx)
  if ctx.unsupported then
    return nil, "io.popen não é suportado: " .. ctx.unsupported
  end
  local handle = setmetatable({data = table.concat(ctx.out), pos = 1, closed = false}, FileR)
  handle.close = function(self)
    self.closed = true
    if status == 0 then return true, "exit", 0 end
    return nil, "exit", status
  end
  return handle
end

return M
