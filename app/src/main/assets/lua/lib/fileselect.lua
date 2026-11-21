--- fileselect utility
--
-- The [norns script reference](https://monome.org/docs/norns/reference/)
-- has [examples for this module](https://monome.org/docs/norns/reference/lib/fileselect).
--
-- @module lib.fileselect
-- @alias fs

-- reroutes redraw/enc/key

local fs = {}

-- lightweight debounce to avoid rapid start/stop thrashing during preview
local MIN_PREVIEW_INTERVAL = 0.1 -- seconds between preview restarts
local pending_preview_start = nil
local exit_generation = 0

function fs.enter(folder, callback, filter_string)
  fs.folders = {}
  fs.list = {}
  fs.display_list = {}
  fs.lengths = {}
  fs.pos = 0
  fs.depth = 0
  fs.folder = folder
  fs.callback = callback
  fs.done = false
  fs.path = nil
  fs.filter = filter_string and filter_string or "all"
  fs.previewing = nil
  fs.previewing_timeout_counter = nil
  fs.preview_debounce_clock = nil
  fs.exited = false
  exit_generation = exit_generation + 1
  pending_preview_start = nil

  if fs.folder:sub(-1, -1) ~= "/" then
    fs.folder = fs.folder .. "/"
  end

  fs.getlist()

  if norns.menu.status() == false then
    fs.key_restore = key
    fs.enc_restore = enc
    fs.redraw_restore = redraw
    fs.refresh_restore = refresh
    key = fs.key
    enc = fs.enc
    redraw = norns.none
    refresh = norns.none
    norns.menu.init()
  else
    fs.key_restore = norns.menu.get_key()
    fs.enc_restore = norns.menu.get_enc()
    fs.redraw_restore = norns.menu.get_redraw()
    fs.refresh_restore = norns.menu.get_refresh()
    norns.menu.set(fs.enc, fs.key, fs.redraw, fs.refresh)
  end
  fs.redraw()
end

function fs.exit()
  if fs.exited then return false end
  fs.exited = true
  fs.done = true
  if pending_preview_start then clock.cancel(pending_preview_start); pending_preview_start = nil end
  if fs.previewing then
    fs.previewing = nil
    audio.tape_play_stop()
  end
  -- ensure any clocks created by fileselect are cancelled
  if fs.preview_debounce_clock then
    clock.cancel(fs.preview_debounce_clock)
    fs.preview_debounce_clock = nil
  end
  if fs.previewing_timeout_counter then
    clock.cancel(fs.previewing_timeout_counter)
    fs.previewing_timeout_counter = nil
  end
  if norns.menu.status() == false then
    key = fs.key_restore
    enc = fs.enc_restore
    redraw = fs.redraw_restore
    refresh = fs.refresh_restore
    norns.menu.init()
  else
    norns.menu.set(fs.enc_restore, fs.key_restore, fs.redraw_restore, fs.refresh_restore)
  end
  local callback = fs.callback
  fs.callback = nil
  if callback then
    if fs.path then callback(fs.path) else callback("cancel") end
  end
  return true
end

function fs.pushd(dir)
  local subdir = dir:match(fs.folder .. '(.*)')
  for match in subdir:gmatch("([^/]*)/") do
    fs.depth = fs.depth + 1
    fs.folders[fs.depth] = match .. "/"
  end
  fs.getlist()
  fs.redraw()
end

fs.getdir = function()
  local path = fs.folder
  for k, v in pairs(fs.folders) do
    path = path .. v
  end
  --print("path: "..path)
  return path
end

fs.getlist = function()
  local dir = fs.getdir()
  fs.list = util.scandir(dir)
  fs.display_list = {}
  fs.lengths = {}
  fs.visible = {}
  fs.visible_indices = {}
  fs.pos = 0

  if fs.depth > 0 then
    table.insert(fs.list, 1, "../")
  end
  fs.len = #fs.list

  -- Generate display list and lengths
  for k, v in ipairs(fs.list) do
    local line = v
    local max_line_length = 128
    local display_length = "";
    local fulldir = dir .. line

    fs.visible[k] = true

    if string.sub(line, -1) ~= "/" then
      local _, samples, rate = audio.file_info(fulldir)
      -- if file is audio:
      if samples > 0 and rate > 0 then
        -- if there's no filter or we specify an "audio" or format filter:
        if fs.filter == "all" or fs.filter == "audio" or fs.filter == fulldir:match("^.+(%..+)$") then
          display_length = util.s_to_hms(math.floor(samples / rate))
          max_line_length = 97
        else -- otherwise, do not display audio file:
          fs.visible[k] = false
          display_length = nil
        end
        -- if file is NOT audio:
      elseif fs.filter ~= "all" then
        if fs.filter == "audio" or fs.filter ~= fulldir:match("^.+(%..+)$") then
          fs.visible[k] = false
          display_length = nil
        end
      end
    end

    if fs.visible[k] then
      line = util.trim_string_to_width(line, max_line_length)
      table.insert(fs.display_list, line)
      table.insert(fs.lengths, display_length)
      table.insert(fs.visible_indices, k)
    end
  end
end

local function stop()
  if pending_preview_start then clock.cancel(pending_preview_start); pending_preview_start = nil end
  if fs.previewing then
    fs.previewing = nil
    audio.tape_play_stop()
    -- allow a brief cleanup window before another preview can start
    if fs.preview_debounce_clock then
      clock.cancel(fs.preview_debounce_clock)
    end
    fs.preview_debounce_clock = clock.run(function()
      clock.sleep(0.05)
      fs.preview_debounce_clock = nil
    end)
    fs.redraw()
  end
end

local function timeout()
  if fs.previewing_timeout_counter == nil then
    fs.previewing_timeout_counter = clock.run(function()
      clock.sleep(1)
      fs.previewing_timeout_counter = nil
    end)
  end
end

local function start()
  -- simple debouncing: skip if timeout active or we're in cleanup window
  if fs.previewing_timeout_counter ~= nil then return end
  if fs.preview_debounce_clock ~= nil then return end
  timeout()
  stop()
  -- delay start slightly to ensure stop cleanup completes
  local generation = exit_generation
  pending_preview_start = clock.run(function()
    clock.sleep(MIN_PREVIEW_INTERVAL)
    pending_preview_start = nil
    if fs.done or fs.exited or generation ~= exit_generation then return end
    -- Resolve the currently selected visible row after debounce.
    local list_index = fs.visible_indices[fs.pos + 1]
    local selected = list_index and fs.list[list_index]
    if not selected or string.sub(selected, -1) == "/" then return end
    fs.file = selected
    fs.previewing = fs.pos
    audio.tape_play_open(fs.getdir() .. fs.file)
    audio.tape_play_start()
    fs.redraw()
  end)
end

fs.key = function(n, z)
  -- back
  if n == 2 and z == 1 then
    fs.done = true
    stop()
    -- select
  elseif n == 3 and z == 1 then
    stop()
    local list_index = fs.visible_indices[fs.pos + 1]
    if list_index and fs.list[list_index] then
      fs.file = fs.list[list_index]
      if fs.file == "../" then
        fs.folders[fs.depth] = nil
        fs.depth = fs.depth - 1
        fs.getlist()
        fs.redraw()
      elseif string.find(fs.file, '/') then
        --print("folder selected")
        fs.depth = fs.depth + 1
        fs.folders[fs.depth] = fs.file
        fs.getlist()
        fs.redraw()
      else
        -- print("file selected")
        local path = fs.folder
        for k, v in pairs(fs.folders) do
          path = path .. v
        end
        fs.path = path .. fs.file
        fs.done = true
      end
    end
  elseif z == 0 and fs.done == true then
    fs.exit()
  end
end

fs.enc = function(n, d)
  if n == 2 then
    fs.pos = util.clamp(fs.pos + d, 0, math.max(0, #fs.display_list - 1))
    fs.redraw()
  elseif n == 3 and d > 0 then
    local list_index = fs.visible_indices[fs.pos + 1]
    fs.file = list_index and fs.list[list_index] or nil
    if fs.file and fs.lengths[fs.pos + 1] and fs.lengths[fs.pos + 1] ~= "" then
      start()
    end
  elseif n == 3 and d < 0 then
    -- always stop with left scroll
    stop()
  end
end

fs.redraw = function()
  screen.clear()
  screen.font_face(1)
  screen.font_size(8)
  if #fs.list == 0 then
    screen.level(4)
    screen.move(0, 20)
    screen.text("(no files)")
  else
    for i = 1, 6 do
      if (i > 2 - fs.pos) and (i < #fs.display_list - fs.pos + 3) then
        local list_index = i + fs.pos - 2
        screen.move(0, 10 * i)
        if (i == 3) then
          screen.level(15)
        else
          screen.level(4)
        end
        local text = fs.display_list[list_index]
        if list_index - 1 == fs.previewing then
          text = util.trim_string_to_width('* ' .. text, 97)
        end
        screen.text(text)
        if fs.lengths[list_index] then
          screen.move(128, 10 * i)
          screen.text_right(fs.lengths[list_index])
        end
      end
    end
  end
  screen.update()
end

fs.refresh = function() fs.redraw() end

return fs
