package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ParamSetCompatibilityTest {
    @Test public void parameterLibraryParsesAsLuaChunk() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        // Compile without executing: runtime dependencies are installed by boot.lua.
        JsePlatform.standardGlobals().load(source, "lib/paramset.lua");
    }

    @Test public void taperUsesTheNornsCurvatureParameterInsteadOfLinearControlspec() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        assertTrue(source.contains("local Taper = class(Base)"));
        assertTrue(source.contains("function Taper:map_value(v)"));
        assertTrue(source.contains("(math.exp(v * self.k) - 1) / (ek - 1)"));
        assertTrue(source.contains("function Taper:unmap_value(v)"));
        assertTrue(source.contains("function ParamSet:add_taper(id, name, min, max, default, k, units)"));
        assertTrue(source.contains("Taper.new(self, id, name, min, max, default, k, units)"));
        assertFalse(source.contains("Control.new(self, id, name, cs, nil, ParamSet.tTAPER)"));
    }

    @Test public void taperPreservesRawAndMappedValuesAndCallsParameterAction() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        assertTrue(source.contains("function Taper:get() return self:map_value(self.value) end"));
        assertTrue(source.contains("function Taper:get_raw() return self.value end"));
        assertTrue(source.contains("function Taper:set(v, silent)"));
        assertTrue(source.contains("function Taper:set_raw(v, silent)"));
        assertTrue(source.contains("self:_fire(silent)"));
        assertTrue(source.contains("function Taper:delta(d)"));
        assertTrue(source.contains("function Taper:get_delta()"));
    }

    @Test public void taperSupportsDefaultsFormattingAndPresetSerialization() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        assertTrue(source.contains("function Taper:set_default()"));
        assertTrue(source.contains("function Taper:string()"));
        assertTrue(source.contains("function Taper:_dump()"));
        assertTrue(source.contains("function Taper:_load(value, silent)"));
        assertTrue(source.contains("o.default = default"));
        assertTrue(source.contains("o.units = units or \"\""));
    }

    @Test public void getRangeReturnsTheNornsTableShapeForNumbersOptionsControlsAndTapers() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        int start = source.indexOf("function ParamSet:get_range(id)");
        int end = source.indexOf("\nend", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("return {p.min, p.max}"));
        assertTrue(method.contains("return {p.controlspec.minval, p.controlspec.maxval}"));
        assertTrue(method.contains("return {1, p.count}"));
        assertFalse(method.contains("return p.min, p.max"));
    }

    @Test public void genericAddPreservesAllowPmapForSupportedParameterTypes() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        int start = source.indexOf("function ParamSet:add(args)");
        int end = source.indexOf("\nend", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("args.wrap, args.allow_pmap"));
        assertTrue(method.contains("args.options, args.default, args.allow_pmap"));
        assertTrue(method.contains("args.controlspec, args.formatter, args.allow_pmap"));
        assertTrue(method.contains("args.behavior, args.default, args.allow_pmap"));
        assertTrue(method.contains("p.allow_pmap = p.allow_pmap ~= false"));
    }

    @Test public void genericCustomParameterRegistrationPreservesExplicitSaveOptOut() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        int start = source.indexOf("if args.param then");
        int end = source.indexOf("\n  end", start);
        assertTrue(start >= 0 && end > start);
        String registration = source.substring(start, end);
        assertTrue(registration.contains("if p.save == nil then p.save = true end"));
        assertFalse(registration.contains("p.save = true\n"));
        assertTrue(registration.contains("p.allow_pmap = p.allow_pmap ~= false"));
    }


    @Test public void psetCallbacksReceiveTheNumericSlotForWriteReadAndDelete() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        assertTrue(source.contains("local pset_number = type(filename) == \"number\" and filename or nil"));
        assertTrue(source.contains("self.action_write(filename, name, pset_number)"));
        assertTrue(source.contains("self.action_read(filename, silent, pset_number)"));
        assertTrue(source.contains("local pset_number = number"));
        assertTrue(source.contains("self.action_delete(filename, name, pset_number)"));
    }


    @Test public void psetDeleteCallbackOnlyRunsAfterSuccessfulFileRemoval() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        int start = source.indexOf("function ParamSet:delete");
        int end = source.indexOf("\nend", start);
        assertTrue(start >= 0 && end > start);
        String deletion = source.substring(start, end);
        assertTrue(deletion.contains("local ok = os.remove(filename)"));
        assertTrue(deletion.contains("if ok and self.action_delete then"));
        assertTrue(deletion.contains("self.action_delete(filename, name, pset_number)"));
        assertTrue(deletion.indexOf("if ok and self.action_delete then") < deletion.indexOf("self.action_delete(filename, name, pset_number)"));
        assertTrue(deletion.contains("return ok"));
    }


    @Test public void defaultLoadsLastPresetAndThenBangsParameters() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        int start = source.indexOf("function ParamSet:default()");
        int end = source.indexOf("\nend", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("local st = norns and norns.state or {}"));
        assertTrue(method.contains("self:read(st.pset_last)"));
        assertTrue(method.contains("self:bang()"));
        assertFalse(method.contains("p.default"));
    }

    @Test public void bangSkipsTriggerParametersAndInactiveBinaryTriggers() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        int start = source.indexOf("function ParamSet:bang(id)");
        int end = source.indexOf("\nend", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("p.t == ParamSet.tTRIGGER"));
        assertTrue(method.contains("p.behavior == \"trigger\" and p.value == 0"));
    }

    @Test public void resetRemainsTheExplicitFactoryDefaultsOperation() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        assertTrue(source.contains("function ParamSet:reset(id)"));
        assertTrue(source.contains("reset_parameter_defaults(self, id)"));
        assertTrue(source.contains("p:set(p.default, true)"));
    }

    @Test public void psetWriteUsesOriginalNornsQuotedColonFormat() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        int start = source.indexOf("function ParamSet:write(filename, name)");
        int end = source.indexOf("\nfunction ParamSet:read", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("pset_quote(p.id) .. \": \" .. encoded"));
        assertTrue(method.contains("p:get()"));
        assertFalse(method.contains("p.id .. \"=\""));
        assertTrue(method.contains("pset_filename(filename)"));
    }

    @Test public void psetReadSupportsNornsAndLegacyFormats() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        int start = source.indexOf("function ParamSet:read(filename, silent)");
        int end = source.indexOf("\nfunction ParamSet:delete", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("local quoted_id, _, value = string.match"));
        assertTrue(method.contains("id, value = string.match(line, \"^([^=]+)=(.*)$\")"));
        assertTrue(method.contains("p:_load(value, silent)"));
        assertTrue(method.contains("value == \"-inf\""));
        assertTrue(method.contains("value == \"inf\""));
        assertTrue(method.contains("pset_unquote(value)"));
        assertTrue(method.contains("already_set[index]"));
        assertTrue(method.contains("p.behavior ~= \"trigger\""));
    }

    @Test public void readWithoutSlotResolvesPersistedNumericLastSlot() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        int start = source.indexOf("function ParamSet:read(filename, silent)");
        int end = source.indexOf("\nfunction ParamSet:delete", start);
        String method = source.substring(start, end);
        assertTrue(method.indexOf("if filename == nil then filename = norns and norns.state and norns.state.pset_last end") < method.indexOf("local pset_number = type(filename) == \"number\" and filename or nil"));
        assertTrue(method.contains("if type(filename) == \"number\" then filename = pset_filename(filename) end"));
        assertTrue(method.contains("remember_pset_slot(pset_number)"));
    }
    @Test public void psetLastSlotIsPersistedOnlyAfterSuccessfulNumberedReadOrWrite() throws Exception {
        String source = read("app/src/main/assets/lua/lib/paramset.lua");
        int writeStart = source.indexOf("function ParamSet:write(filename, name)");
        int writeEnd = source.indexOf("function ParamSet:read", writeStart);
        String write = source.substring(writeStart, writeEnd);
        assertTrue(write.contains("remember_pset_slot(pset_number)"));
        assertTrue(write.indexOf("if not ok then return false end") < write.indexOf("remember_pset_slot(pset_number)"));
        int readStart = source.indexOf("function ParamSet:read(filename, silent)");
        int readEnd = source.indexOf("function ParamSet:delete", readStart);
        String read = source.substring(readStart, readEnd);
        assertTrue(read.contains("remember_pset_slot(pset_number)"));
        assertTrue(read.indexOf("local f = io.open(filename, \"r\")") < read.indexOf("remember_pset_slot(pset_number)"));
        assertTrue(source.contains("st.data .. \"pset-last.txt\""));
    }

    @Test public void scriptDataFolderIsUniqueForScriptsSharingOneDirectory() throws Exception {
        String source = read("app/src/main/assets/lua/boot.lua");
        assertTrue(source.contains("data_name = data_name .. \"/\" .. short"));
        assertTrue(source.contains("norns.state.data = _path.data .. data_name .. \"/\""));
        assertTrue(source.contains("string.sub(data_name, -#short) ~= short"));
    }

    @Test public void scriptLoadRestoresNornsLastPresetSlotFromDataFolder() throws Exception {
        String source = read("app/src/main/assets/lua/boot.lua");
        assertTrue(source.contains("host.read_file(norns.state.data .. \"pset-last.txt\")"));
        assertTrue(source.contains("norns.state.pset_last = math.floor(saved_pset_slot)"));
        assertTrue(source.contains("norns.state.pset_last = 1"));
        assertFalse(source.contains("norns.state.pset_last = norns.state.data .. short .. \".pset\""));
    }

    @Test public void scriptLoadInitializesAndRestoresDefaultPsetSlot() throws Exception {
        String source = read("app/src/main/assets/lua/boot.lua");
        assertTrue(source.contains("norns.state.pset_last = math.floor(saved_pset_slot)"));
        assertTrue(source.contains("norns.state.pset_last = 1"));
        assertTrue(source.contains("norns.state.path, norns.state.lib, norns.state.data, norns.state.pset_last = \"\", \"\", \"\", nil"));
    }

    @Test public void paramsUiRestoresLastPresetSlotFromLuaSerialization() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("tostring(norns.state.pset_last or 1)"));
        String view = read("app/src/main/java/org/nornsmobile/app/ParamsView.java");
        assertTrue(view.contains("p.presetSlot = Integer.parseInt(f[5])"));
        assertTrue(view.contains("selectPresetSlot(p.presetSlot)"));
    }

    @Test public void paramsUiPresetActionsReachNornsParamsetReadWriteAndDelete() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("function _norns.param_preset_read(slot)"));
        assertTrue(boot.contains("params:read(math.floor(slot))"));
        assertTrue(boot.contains("function _norns.param_preset_write(slot)"));
        assertTrue(boot.contains("params:write(math.floor(slot))"));
        assertTrue(boot.contains("function _norns.param_preset_delete(slot)"));
        assertTrue(boot.contains("params:delete(math.floor(slot))"));
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(host.contains("callNorns(\"param_preset_read\""));
        assertTrue(host.contains("callNorns(\"param_preset_write\""));
        assertTrue(host.contains("callNorns(\"param_preset_delete\""));
        String view = read("app/src/main/java/org/nornsmobile/app/ParamsView.java");
        assertTrue(view.contains("PRESET %02d"));
        assertTrue(view.contains("CARREGAR"));
        assertTrue(view.contains("SALVAR"));
        assertTrue(view.contains("EXCLUIR"));
    }

    @Test public void fileAndTextParametersKeepDistinctValuesActionsAndPersistence() throws Exception {
        Globals globals = paramsetRuntime();
        assertLua(globals,
                "local ps = ParamSetUnderTest.new('file-text-runtime', 'file-text-runtime')\n"
                + "local selected_file = nil\n"
                + "local file = ps:add_file('sample', 'Sample', '/dust/audio/')\n"
                + "assert(file.t == ParamSetUnderTest.tFILE and file.kind == 'file')\n"
                + "assert(file.path == '/dust/audio/' and file:get() == '' and file:string() == '')\n"
                + "file:set_action(function(value) selected_file = value end)\n"
                + "file:set('/dust/audio/kick.wav')\n"
                + "assert(file:get() == '/dust/audio/kick.wav')\n"
                + "assert(file:string() == 'kick.wav' and selected_file == '/dust/audio/kick.wav')\n"
                + "local text_value = ps:add_text('caption', 'Caption', 'initial')\n"
                + "assert(text_value.t == ParamSetUnderTest.tTEXT and text_value:get() == 'initial')\n"
                + "text_value:set('line one\\\nline two')\n"
                + "assert(text_value:string() == 'line one\\\nline two')\n"
                + "local saved_file, saved_text = file:_dump(), text_value:_dump()\n"
                + "file:set('')\n"
                + "text_value:set('')\n"
                + "file:_load(saved_file)\n"
                + "text_value:_load(saved_text)\n"
                + "assert(file:get() == '/dust/audio/kick.wav' and file.path == '/dust/audio/')\n"
                + "assert(text_value:get() == 'line one\\\nline two')\n"
                + "return true",
                "paramset-file-text-contract.lua");
    }

    @Test public void fileChooserRootIsIncludedInUiRowsWithoutBecomingTheSelectedValue() throws Exception {
        Globals globals = paramsetRuntime();
        assertLua(globals,
                "local ps = ParamSetUnderTest.new('file-ui-path', 'file-ui-path')\n"
                + "local root = '/dust/audio/'\n"
                + "local f = ps:add_file('sample', 'Sample', root)\n"
                + "local rows = ps:ui_rows()\n"
                + "assert(rows[1].kind == 'file' and rows[1].str == '')\n"
                + "assert(rows[1].path == root and f:get() == '')\n"
                + "return true",
                "paramset-file-ui-path.lua");
    }

    @Test public void parameterUiSerializationPreservesMultilineTextAndEscapedSeparators() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String view = read("app/src/main/java/org/nornsmobile/app/ParamsView.java");
        assertTrue(boot.contains("string.gsub(s, \"\\n\", \"\\\\n\")"));
        assertTrue(view.contains("private static String unescape(String value)"));
        assertTrue(view.contains("if (next == 'n') out.append('\\n')"));
        assertTrue(view.contains("else if (next == 't') out.append('\\t')"));
    }

    @Test public void fileTextEditorsAreConnectedAndDustConfined() throws Exception {
        String view = read("app/src/main/java/org/nornsmobile/app/ParamsView.java");
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(view.contains("showTextEditor(row)"));
        assertTrue(view.contains("cb.onText(row.p.id, input.getText().toString())"));
        assertTrue(view.contains("cb.onFile(row.p.id, row.p.path)"));
        assertTrue(view.contains("if (f.length > 6) p.path = unescape(f[6])"));
        assertTrue(activity.contains("dust.resolve(rootPath)"));
        assertTrue(activity.contains("dust.isInside(allowedRoot)"));
        assertTrue(activity.contains("dust.list(current)"));
        assertTrue(activity.contains("isWithinDirectory(allowedRoot, canonical)"));
        assertTrue(activity.contains("host.paramSetValue(id, canonical.getAbsolutePath())"));
        assertFalse(activity.contains("REQ_PARAM_FILE"));
        assertTrue(host.contains("callNorns(\"param_set_value\""));
        assertTrue(boot.contains("p.t ~= paramset.tFILE and p.t ~= paramset.tTEXT"));
        assertTrue(boot.contains("p:set(value)"));
    }

    private static Globals paramsetRuntime() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        globals.set("util", globals.load(read("app/src/main/assets/lua/lib/util.lua"), "lib/util.lua").call());
        globals.set("controlspec", globals.load(read("app/src/main/assets/lua/lib/controlspec.lua"), "lib/controlspec.lua").call());
        globals.load(
                "require = function(name) "
                + "if name == 'util' then return util end "
                + "if name == 'controlspec' then return controlspec end "
                + "error('unexpected module: ' .. tostring(name)) end",
                "paramset-require.lua").call();
        globals.set("ParamSetUnderTest", globals.load(
                read("app/src/main/assets/lua/lib/paramset.lua"), "lib/paramset.lua").call());
        return globals;
    }

    private static void assertLua(Globals globals, String source, String chunk) {
        assertTrue(chunk + " must return true", globals.load(source, chunk).call().toboolean());
    }

    @Test public void genericTypedAddHonorsPersistenceAndPmapOptOutsAtRuntime() throws Exception {
        Globals globals = paramsetRuntime();
        assertLua(globals,
                "local ps = ParamSetUnderTest.new('typed-runtime', 'typed-runtime')\n"
                + "local number = ps:add({type='number', id='temporary', min=0, max=10, default=2, save=false, allow_pmap=false})\n"
                + "assert(number.save == false)\n"
                + "assert(number.allow_pmap == false)\n"
                + "local option = ps:add({type='option', id='mode', options={'a','b'}, default=1, allow_pmap=false})\n"
                + "assert(option.save == true and option.allow_pmap == false)\n"
                + "local taper = ps:add({type='taper', id='curve', min=0, max=1, default=0.5, k=2, allow_pmap=false})\n"
                + "assert(taper.allow_pmap == false)\n"
                + "return true",
                "paramset-typed-runtime-flags.lua");
    }

    @Test public void customParameterRegistrationPreservesSaveFalseAtRuntime() throws Exception {
        Globals globals = paramsetRuntime();
        assertLua(globals,
                "local ps = ParamSetUnderTest.new('custom-runtime', 'custom-runtime')\n"
                + "local custom = {id='transient', t=1, save=false, allow_pmap=false}\n"
                + "ps:add({param=custom})\n"
                + "assert(custom.ps == ps and custom.save == false and custom.allow_pmap == false)\n"
                + "local defaulted = {id='persistent', t=1}\n"
                + "ps:add({param=defaulted})\n"
                + "assert(defaulted.save == true)\n"
                + "return true",
                "paramset-custom-runtime-flags.lua");
    }

    @Test public void removingGroupMemberRestoresOnlyItsOwnPendingSlot() throws Exception {
        Globals globals = paramsetRuntime();
        assertLua(globals,
                "local ps = ParamSetUnderTest.new('group-runtime', 'group-runtime')\n"
                + "ps:add_number('outside', 'Outside', 0, 1, 0)\n"
                + "ps:add_group('pair', 'Pair', 2)\n"
                + "ps:add_number('inside', 'Inside', 0, 1, 0)\n"
                + "assert(ps.group == 1)\n"
                + "assert(ps:remove('outside') and ps.group == 1)\n"
                + "assert(ps:remove('inside') and ps.group == 2)\n"
                + "ps:add_number('replacement', 'Replacement', 0, 1, 0)\n"
                + "ps:add_number('last', 'Last', 0, 1, 0)\n"
                + "assert(ps.group == 0 and ps.active_group_id == nil)\n"
                + "return true",
                "paramset-group-member-runtime.lua");
    }

    @Test public void removingOpenGroupHeaderAndClearingSetResetGroupState() throws Exception {
        Globals globals = paramsetRuntime();
        assertLua(globals,
                "local ps = ParamSetUnderTest.new('group-clear-runtime', 'group-clear-runtime')\n"
                + "ps:add_group('header', 'Header', 3)\n"
                + "assert(ps.group == 3 and ps.active_group_id == 'header')\n"
                + "assert(ps:remove('header'))\n"
                + "assert(ps.group == 0 and ps.active_group_id == nil)\n"
                + "ps:add_group('again', 'Again', 2)\n"
                + "ps:clear()\n"
                + "assert(ps.group == 0 and ps.active_group_id == nil)\n"
                + "return true",
                "paramset-group-header-clear-runtime.lua");
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
