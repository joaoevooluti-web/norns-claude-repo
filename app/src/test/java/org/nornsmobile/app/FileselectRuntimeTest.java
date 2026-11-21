package org.nornsmobile.app;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class FileselectRuntimeTest {
  private static String read(String path) throws Exception {
    File f=new File("app/src/main/assets/lua/"+path);
    if(!f.isFile()) f=new File("src/main/assets/lua/"+path);
    assertTrue("Missing Lua asset "+path,f.isFile());
    return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);
  }
  @Test public void nornsMenuOwnsAndRestoresFourCallbacks() throws Exception {
    String boot=read("boot.lua");
    int a=boot.indexOf("-- Menu callback ownership mirrors norns:");
    int b=boot.indexOf("-- Encoder sensitivity/acceleration",a);
    assertTrue("norns.menu implementation must exist",a>=0&&b>a);
    Globals g=JsePlatform.standardGlobals();
    g.load("norns={}; noop=function() end").call();
    g.load(boot.substring(a,b),"production-menu.lua").call();
    int cleanupStart=boot.indexOf("function _norns.cleanup()");
    int cleanupEnd=boot.indexOf("function _norns.load_script",cleanupStart);
    assertTrue("script cleanup must retire a loaded fileselect instance",cleanupStart>=0&&cleanupEnd>cleanupStart);
    String cleanup=boot.substring(cleanupStart,cleanupEnd);
    assertTrue(cleanup.contains("loaded.fileselect or loaded[\"lib/fileselect\"]"));
    assertTrue(cleanup.contains("safe(selector.exit)"));
    assertTrue(cleanup.contains("norns.menu.toggle(false)"));

    g.load("""
      assert(not norns.menu.status())
      local k=function() end; local e=function() end; local r=function() end; local f=function() end
      assert(norns.menu.set(e,k,r,f))
      assert(norns.menu.get_key()==k and norns.menu.get_enc()==e)
      assert(norns.menu.get_redraw()==r and norns.menu.get_refresh()==f)
      assert(norns.menu.toggle() and norns.menu.status())
      assert(norns.menu.toggle(false)==false and not norns.menu.status())
      ""","menu-behavior.lua").call();
  }
  @Test public void fileselectNavigatesFiltersPreviewsDebouncesAndCleansUp() throws Exception {
    String source=read("lib/fileselect.lua");
    Globals g=JsePlatform.standardGlobals();
    g.load("""
      local now,nextId=0,0; local jobs={}
      clock={}
      function clock.run(fn)
        nextId=nextId+1; local id=nextId; local co=coroutine.create(fn)
        jobs[id]={co=co,due=now}; local ok,delay=coroutine.resume(co); assert(ok)
        if coroutine.status(co)=="dead" then jobs[id]=nil else jobs[id].due=now+(delay or 0) end
        return id
      end
      function clock.sleep(s) return coroutine.yield(s) end
      function clock.cancel(id) if id then jobs[id]=nil end end
      function clock.advance(s)
        now=now+s; local again=true
        while again do
          again=false
          for id,j in pairs(jobs) do
            if j.due<=now then
              local ok,delay=coroutine.resume(j.co); assert(ok)
              if coroutine.status(j.co)=="dead" then jobs[id]=nil else j.due=now+(delay or 0) end
              again=true; break
            end
          end
        end
      end
      util={
        clamp=function(v,a,b) return math.max(a,math.min(b,v)) end,
        scandir=function(path) if path=="/audio/sub/" then return {"inside.wav"} end
          return {"a.wav","notes.txt","sub/"} end,
        s_to_hms=function() return "0:00:01" end,
        trim_string_to_width=function(s) return s end
      }
      test_tape={opens={},starts=0,stops=0}
      audio={
        file_info=function(path) if path:match("\\.wav$") then return 2,48000,48000 end return 0,0,0 end,
        tape_play_open=function(path) table.insert(test_tape.opens,path) end,
        tape_play_start=function() test_tape.starts=test_tape.starts+1 end,
        tape_play_stop=function() test_tape.stops=test_tape.stops+1 end
      }
      screen={clear=function() end,font_face=function() end,font_size=function() end,level=function() end,
        move=function() end,text=function() end,text_right=function() end,update=function() end,
        text_extents=function(s) return #s end}
      norns={none=function() end,menu={
        _mode=false,_enc=function() end,_key=function() end,_redraw=function() end,_refresh=function() end,
        status=function() return norns.menu._mode end,init=function() return true end,
        toggle=function(v) if v==nil then norns.menu._mode=not norns.menu._mode else norns.menu._mode=v end end,
        set=function(e,k,r,f) norns.menu._enc=e;norns.menu._key=k;norns.menu._redraw=r;norns.menu._refresh=f end,
        get_enc=function() return norns.menu._enc end,get_key=function() return norns.menu._key end,
        get_redraw=function() return norns.menu._redraw end,get_refresh=function() return norns.menu._refresh end
      }}
      key=function() end; enc=function() end; redraw=function() end; refresh=function() end
      ""","fileselect-fixture.lua").call();
    LuaValue fs=g.load(source,"@lua/lib/fileselect.lua").call();
    g.set("fs",fs);
    g.load("""
      local selected=nil
      fs.enter("/audio",function(path) selected=path end,"audio")
      assert(#fs.display_list==2 and fs.display_list[1]=="a.wav" and fs.display_list[2]=="sub/")
      fs.enc(2,1); assert(fs.pos==1); fs.key(3,1)
      assert(fs.getdir()=="/audio/sub/")
      fs.key(2,1); fs.key(2,0); assert(selected=="cancel")
      assert(key~=fs.key and enc~=fs.enc,"global callbacks restored after cancel")
      selected=nil
      fs.enter("/audio",function(path) selected=path end,"all")
      fs.enc(3,1); clock.advance(0.1)
      assert(test_tape.starts==1 and test_tape.opens[1]=="/audio/a.wav")
      fs.enc(3,-1); assert(test_tape.stops==1)
      clock.advance(0.05); fs.enc(3,1); clock.advance(0.2)
      assert(test_tape.starts==1,"preview restart must be debounced")
      fs.exit(); assert(selected=="cancel" and fs.exited and fs.callback==nil)
      local before=test_tape.starts; clock.advance(2)
      assert(test_tape.starts==before,"cancelled preview must not restart")
      assert(key~=fs.key and enc~=fs.enc,"callbacks restored after exit")
      selected=nil
      fs.enter("/audio",function(path) selected=path end,"all")
      fs.enc(3,1); clock.advance(0.1); assert(test_tape.starts==2)
      fs.exit(); assert(test_tape.stops==2,"exit must stop an active preview")
      assert(selected=="cancel")
      ""","fileselect-behavior.lua").call();
  }
}