package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Subconjunto POSIX de os.execute/io.popen (lib/nornsmobile_shell.lua). Roda o módulo de produção sobre
 * um sistema de arquivos em memória que imita as regras do host Java: tudo preso à pasta dust.
 */
public class PosixShellTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        assertTrue("Missing file: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final String PRELUDE = ""
            + "stdout, stderr = {}, {}\n"
            + "shell.write_stdout = function(t) stdout[#stdout + 1] = t end\n"
            + "shell.write_stderr = function(t) stderr[#stderr + 1] = t end\n"
            + "function sh(cmd)\n"
            + "  stdout, stderr = {}, {}\n"
            + "  local ok, how, code = shell.execute(cmd)\n"
            + "  return {ok = ok, how = how, code = code, out = table.concat(stdout), err = table.concat(stderr)}\n"
            + "end\n";

    /** Resultado de os.execute: o trio do Lua mais o que foi impresso. */
    private static final class Result {
        final boolean ok;
        final String how;
        final int code;
        final String out;
        final String err;

        Result(LuaValue t) {
            ok = t.get("ok").toboolean();
            how = t.get("how").isnil() ? null : t.get("how").tojstring();
            code = t.get("code").isnil() ? -1 : t.get("code").toint();
            out = t.get("out").tojstring();
            err = t.get("err").tojstring();
        }
    }

    /** Runtime com o sistema de arquivos falso, o módulo de produção e o FileR real do boot.lua. */
    private static final class Env {
        final Globals g = JsePlatform.standardGlobals();
        final LuaValue fs;

        Env() throws Exception {
            fs = g.load(read("app/src/test/resources/lua/fake_host_fs.lua"), "fake_host_fs.lua").call();
            g.set("fs", fs);
            g.set("_host", fs.get("host"));
            g.set("shell", g.load(read("app/src/main/assets/lua/lib/nornsmobile_shell.lua"),
                    "nornsmobile_shell.lua").call());
            String boot = read("app/src/main/assets/lua/boot.lua");
            int start = boot.indexOf("local FileR = {}");
            int end = boot.indexOf("local FileW = {}", start);
            assertTrue("boot.lua must keep the FileR handle class", start >= 0 && end > start);
            g.load(boot.substring(start, end) + "\nFileR_class = FileR\n", "boot-FileR.lua").call();
            g.load(PRELUDE, "prelude.lua").call();
        }

        Result sh(String cmd) {
            return new Result(g.get("sh").call(LuaValue.valueOf(cmd)));
        }

        Env file(String path, String data) {
            fs.get("add_file").call(LuaValue.valueOf(path), LuaValue.valueOf(data));
            return this;
        }

        Env dir(String path) {
            fs.get("add_dir").call(LuaValue.valueOf(path));
            return this;
        }

        boolean isDir(String path) { return fs.get("is_dir").call(LuaValue.valueOf(path)).toboolean(); }
        boolean isFile(String path) { return fs.get("is_file").call(LuaValue.valueOf(path)).toboolean(); }
        boolean exists(String path) { return fs.get("exists").call(LuaValue.valueOf(path)).toboolean(); }

        String content(String path) {
            LuaValue v = fs.get("read").call(LuaValue.valueOf(path));
            return v.isnil() ? null : v.tojstring();
        }

        String list(String path) {
            LuaValue v = fs.get("list").call(LuaValue.valueOf(path));
            return v.isnil() ? null : v.tojstring();
        }

        String snapshot() { return fs.get("snapshot").call().tojstring(); }

        int logLines(String containing) {
            LuaValue logs = fs.get("logs");
            int n = 0;
            for (int i = 1; i <= logs.length(); i++) {
                if (logs.get(i).tojstring().contains(containing)) n++;
            }
            return n;
        }

        Varargs lua(String code) {
            return g.load(code, "test.lua").invoke();
        }
    }

    private static void assertSuccess(Result r) {
        assertTrue("expected success but got " + r.how + " code=" + r.code + " err=" + r.err, r.ok);
        assertEquals(0, r.code);
    }

    private static void assertFailure(int code, Result r) {
        assertFalse("expected failure", r.ok);
        assertEquals("exit", r.how);
        assertEquals(r.err, code, r.code);
    }

    private static void assertUnsupported(Result r) {
        assertFalse("expected unsupported", r.ok);
        assertNotNull(r.how);
        assertTrue(r.how, r.how.contains("não é suportado"));
    }

    // ------------------------------------------------------------------ mkdir

    @Test public void mkdirCreatesNestedFoldersThroughNornsAndAppPaths() throws Exception {
        Env e = new Env();
        assertSuccess(e.sh("mkdir -p /home/we/dust/data/myscript/presets"));
        assertTrue(e.isDir("/dust/data/myscript/presets"));
        assertSuccess(e.sh("mkdir -p /dust/audio/loops/a /dust/audio/loops/b"));
        assertTrue(e.isDir("/dust/audio/loops/a") && e.isDir("/dust/audio/loops/b"));
        // "mkdir -p" numa pasta que já existe não é erro
        assertSuccess(e.sh("mkdir -p /home/we/dust/data/myscript"));
    }

    @Test public void mkdirWithoutDashPFollowsPosixRules() throws Exception {
        Env e = new Env();
        assertSuccess(e.sh("mkdir /dust/data/one"));
        Result again = e.sh("mkdir /dust/data/one");
        assertFailure(1, again);
        assertTrue(again.err, again.err.contains("Arquivo existe"));
        // sem -p a pasta pai precisa existir
        assertFailure(1, e.sh("mkdir /dust/data/no/parent"));
        assertFalse(e.exists("/dust/data/no"));
        // "mkdir -p" sobre um arquivo falha
        e.file("/dust/data/file.txt", "x");
        assertFailure(1, e.sh("mkdir -p /dust/data/file.txt"));
        assertFailure(1, e.sh("mkdir"));
    }

    @Test public void quotingKeepsSpacesAndExpansionsAreRefused() throws Exception {
        Env e = new Env();
        assertSuccess(e.sh("mkdir -p '/home/we/dust/data/my folder/sub dir'"));
        assertTrue(e.isDir("/dust/data/my folder/sub dir"));
        assertSuccess(e.sh("mkdir -p \"/dust/data/a b\""));
        assertTrue(e.isDir("/dust/data/a b"));
        assertSuccess(e.sh("mkdir -p /dust/data/x\\ y"));
        assertTrue(e.isDir("/dust/data/x y"));
        assertSuccess(e.sh("mkdir -p \"/dust/data/say \\\"hi\\\"\""));
        assertTrue(e.isDir("/dust/data/say \"hi\""));
        // ~ vira /home/we
        assertSuccess(e.sh("mkdir -p ~/dust/data/tilde"));
        assertTrue(e.isDir("/dust/data/tilde"));

        String before = e.snapshot();
        assertUnsupported(e.sh("mkdir -p $HOME/dust/data/var"));
        assertUnsupported(e.sh("mkdir -p \"$HOME/x\""));
        assertUnsupported(e.sh("mkdir -p `echo x`"));
        assertUnsupported(e.sh("mkdir -p 'unclosed"));
        assertEquals("nada deve ser criado quando a linha não é suportada", before, e.snapshot());
    }

    // ------------------------------------------------------------------ rm

    @Test public void rmRemovesFilesAndFoldersAndReportsMissingOnes() throws Exception {
        Env e = new Env();
        e.file("/dust/data/a.txt", "a");
        e.file("/dust/data/dir/inner/b.txt", "b");

        assertSuccess(e.sh("rm /dust/data/a.txt"));
        assertFalse(e.exists("/dust/data/a.txt"));
        Result missing = e.sh("rm /dust/data/a.txt");
        assertFailure(1, missing);
        assertTrue(missing.err, missing.err.contains("inexistente"));
        assertSuccess(e.sh("rm -f /dust/data/a.txt"));

        Result isDir = e.sh("rm /dust/data/dir");
        assertFailure(1, isDir);
        assertTrue(isDir.err, isDir.err.contains("diretório"));
        assertTrue(e.isDir("/dust/data/dir/inner"));
        assertSuccess(e.sh("rm -rf /home/we/dust/data/dir"));
        assertFalse(e.exists("/dust/data/dir"));
        assertSuccess(e.sh("rm -r -f /dust/data/nothing"));
    }

    @Test public void rmNeverRemovesDustItselfOrItsStandardFolders() throws Exception {
        Env e = new Env();
        e.file("/dust/code/keep/keep.lua", "k");
        e.file("/dust/data/d.txt", "d");
        e.file("/dust/audio/a.wav", "w");
        String before = e.snapshot();
        assertFailure(1, e.sh("rm -rf /home/we/dust"));
        assertFailure(1, e.sh("rm -rf /home/we"));
        assertFailure(1, e.sh("rm -rf /home/we/dust/code"));
        assertFailure(1, e.sh("rm -rf /dust/data"));
        assertFailure(1, e.sh("rm -rf /dust/audio"));
        assertFailure(1, e.sh("rm -rf ."));
        assertFailure(1, e.sh("rm -rf /dust/data/.."));
        e.sh("rm -rf /");
        e.sh("rm -rf /etc /home/weird");
        assertEquals(before, e.snapshot());

        // "." e ".." nunca são removidos, nem quando apontam para uma pasta comum
        e.file("/dust/data/tree/inner.txt", "i");
        e.dir("/dust/data/tree/deep");
        String withTree = e.snapshot();
        assertFailure(1, e.sh("rm -rf /dust/data/tree/."));
        assertFailure(1, e.sh("rm -rf /dust/data/tree/deep/.."));
        assertEquals(withTree, e.snapshot());
    }

    @Test public void rmExpandsGlobsInTheLastPathComponent() throws Exception {
        Env e = new Env();
        e.file("/dust/data/tmp/a.tmp", "1");
        e.file("/dust/data/tmp/b.tmp", "2");
        e.file("/dust/data/tmp/keep.txt", "3");
        e.file("/dust/data/tmp/.hidden.tmp", "4");
        assertSuccess(e.sh("rm /home/we/dust/data/tmp/*.tmp"));
        assertEquals(".hidden.tmp,keep.txt", e.list("/dust/data/tmp"));
        // sem resultado o curinga fica como está (e o rm reclama, como no shell)
        assertFailure(1, e.sh("rm /dust/data/tmp/*.nothing"));
        assertSuccess(e.sh("rm -f /dust/data/tmp/*.nothing"));
        // aspas desligam o curinga
        e.file("/dust/data/tmp/star*.txt", "s");
        assertSuccess(e.sh("rm '/dust/data/tmp/star*.txt'"));
        assertEquals(".hidden.tmp,keep.txt", e.list("/dust/data/tmp"));
    }

    // ------------------------------------------------------------------ cp e mv

    @Test public void cpCopiesFilesAndFoldersFollowingCpRules() throws Exception {
        Env e = new Env();
        e.file("/dust/audio/in.wav", "WAV");
        e.file("/dust/data/tree/a.txt", "A");
        e.file("/dust/data/tree/sub/b.txt", "B");
        e.dir("/dust/data/pasta");

        assertSuccess(e.sh("cp /home/we/dust/audio/in.wav /home/we/dust/audio/out.wav"));
        assertEquals("WAV", e.content("/dust/audio/out.wav"));
        assertSuccess(e.sh("cp /dust/audio/in.wav /dust/data/pasta"));
        assertEquals("WAV", e.content("/dust/data/pasta/in.wav"));
        e.file("/dust/audio/two.wav", "TWO");
        assertSuccess(e.sh("cp /dust/audio/in.wav /dust/audio/two.wav /dust/data/pasta/"));
        assertEquals("TWO", e.content("/dust/data/pasta/two.wav"));

        Result needsR = e.sh("cp /dust/data/tree /dust/data/plain");
        assertFailure(1, needsR);
        assertTrue(needsR.err, needsR.err.contains("-r"));
        assertFalse(e.exists("/dust/data/plain"));
        assertSuccess(e.sh("cp -r /dust/data/tree /dust/data/copy"));
        assertEquals("B", e.content("/dust/data/copy/sub/b.txt"));
        assertSuccess(e.sh("cp -R /dust/data/tree /dust/data/pasta"));
        assertEquals("A", e.content("/dust/data/pasta/tree/a.txt"));
        assertSuccess(e.sh("cp -rf /dust/data/tree/ /dust/data/copy2"));
        assertEquals("A", e.content("/dust/data/copy2/a.txt"));
    }

    @Test public void cpErrorsAreReportedWithoutSideEffects() throws Exception {
        Env e = new Env();
        e.file("/dust/data/a.txt", "A");
        e.file("/dust/data/b.txt", "B");
        assertFailure(1, e.sh("cp /dust/data/missing.txt /dust/data/x.txt"));
        assertFailure(1, e.sh("cp /dust/data/a.txt"));
        assertFailure(1, e.sh("cp /dust/data/a.txt /dust/data/b.txt /dust/data/not-a-dir"));
        assertFailure(1, e.sh("cp /dust/data/a.txt /dust/nao/existe/x.txt"));
        assertFalse(e.exists("/dust/nao"));
        assertFailure(1, e.sh("cp /dust/data/a.txt /dust/data/a.txt"));
        e.dir("/dust/data/folder");
        assertFailure(1, e.sh("cp -r /dust/data/folder /dust/data/folder/inside"));
        // cp -n não sobrescreve
        assertSuccess(e.sh("cp -n /dust/data/a.txt /dust/data/b.txt"));
        assertEquals("B", e.content("/dust/data/b.txt"));
        assertSuccess(e.sh("cp -f /dust/data/a.txt /dust/data/b.txt"));
        assertEquals("A", e.content("/dust/data/b.txt"));
    }

    @Test public void mvMovesAndRenamesInsideDust() throws Exception {
        Env e = new Env();
        e.file("/dust/data/a.txt", "A");
        e.file("/dust/data/b.txt", "B");
        e.dir("/dust/data/pasta");
        assertSuccess(e.sh("mv /dust/data/a.txt /dust/data/c.txt"));
        assertFalse(e.exists("/dust/data/a.txt"));
        assertEquals("A", e.content("/dust/data/c.txt"));
        assertSuccess(e.sh("mv /dust/data/c.txt /dust/data/b.txt /dust/data/pasta"));
        assertEquals("A", e.content("/dust/data/pasta/c.txt"));
        assertEquals("B", e.content("/dust/data/pasta/b.txt"));
        assertSuccess(e.sh("mv /home/we/dust/data/pasta /home/we/dust/data/renamed"));
        assertTrue(e.isFile("/dust/data/renamed/c.txt"));
        assertFailure(1, e.sh("mv /dust/data/missing /dust/data/x"));
        assertFailure(1, e.sh("mv /dust/data/renamed/b.txt /dust/data/renamed/c.txt /dust/data/nao-e-pasta"));
        e.file("/dust/data/keep.txt", "K");
        assertSuccess(e.sh("mv -n /dust/data/renamed/b.txt /dust/data/keep.txt"));
        assertEquals("K", e.content("/dust/data/keep.txt"));
    }

    // ------------------------------------------------------------------ touch, cat, echo

    @Test public void touchCreatesFilesOnlyWhereTheParentExists() throws Exception {
        Env e = new Env();
        assertSuccess(e.sh("touch /home/we/dust/data/new.txt"));
        assertEquals("", e.content("/dust/data/new.txt"));
        e.file("/dust/data/keep.txt", "keep");
        assertSuccess(e.sh("touch /dust/data/keep.txt"));
        assertEquals("keep", e.content("/dust/data/keep.txt"));
        assertFailure(1, e.sh("touch /dust/no/parent/f.txt"));
        assertFalse(e.exists("/dust/no"));
        assertSuccess(e.sh("touch -c /dust/data/never.txt"));
        assertFalse(e.exists("/dust/data/never.txt"));
        assertSuccess(e.sh("touch /dust/data/a /dust/data/b"));
        assertTrue(e.isFile("/dust/data/a") && e.isFile("/dust/data/b"));
    }

    @Test public void catPrintsFilesAndEchoWritesThroughRedirects() throws Exception {
        Env e = new Env();
        e.file("/dust/data/a.txt", "um\ndois\n");
        e.file("/dust/data/b.txt", "tres\n");
        Result cat = e.sh("cat /home/we/dust/data/a.txt /dust/data/b.txt");
        assertSuccess(cat);
        assertEquals("um\ndois\ntres\n", cat.out);
        assertFailure(1, e.sh("cat /dust/data/missing.txt"));
        assertFailure(1, e.sh("cat /dust/data"));

        assertSuccess(e.sh("echo hello world > /dust/data/log.txt"));
        assertEquals("hello world\n", e.content("/dust/data/log.txt"));
        assertSuccess(e.sh("echo -n more >> /dust/data/log.txt"));
        assertEquals("hello world\nmore", e.content("/dust/data/log.txt"));
        assertSuccess(e.sh("echo 'quoted   spaces' > /dust/data/q.txt"));
        assertEquals("quoted   spaces\n", e.content("/dust/data/q.txt"));
    }

    // ------------------------------------------------------------------ ls

    @Test public void lsListsSortedNamesHidingDotFiles() throws Exception {
        Env e = new Env();
        e.file("/dust/data/b.txt", "b");
        e.file("/dust/data/a.txt", "a");
        e.file("/dust/data/.hidden", "h");
        e.dir("/dust/data/sub");
        Result plain = e.sh("ls /home/we/dust/data");
        assertSuccess(plain);
        assertEquals("a.txt\nb.txt\nsub\n", plain.out);
        assertEquals("a.txt\nb.txt\nsub\n", e.sh("ls -1 /dust/data").out);
        assertEquals(".\n..\n.hidden\na.txt\nb.txt\nsub\n", e.sh("ls -a /dust/data").out);
        assertEquals(".hidden\na.txt\nb.txt\nsub\n", e.sh("ls -A /dust/data").out);
        // ls de um arquivo imprime o caminho exatamente como foi dado
        assertEquals("/dust/data/a.txt\n", e.sh("ls /dust/data/a.txt").out);
        assertEquals("", e.sh("ls /dust/data/sub").out);
    }

    @Test public void lsReportsMissingPathsAndSeveralOperands() throws Exception {
        Env e = new Env();
        e.file("/dust/data/x/one.txt", "1");
        e.file("/dust/data/y/two.txt", "2");
        Result missing = e.sh("ls /dust/data/missing");
        assertFailure(2, missing);
        assertTrue(missing.err, missing.err.contains("não foi possível acessar"));
        Result multi = e.sh("ls /dust/data/x /dust/data/y");
        assertSuccess(multi);
        assertEquals("/dust/data/x:\none.txt\n\n/dust/data/y:\ntwo.txt\n", multi.out);
    }

    @Test public void lsExpandsGlobsAndSkipsDotFiles() throws Exception {
        Env e = new Env();
        e.file("/dust/audio/a.wav", "1");
        e.file("/dust/audio/b.wav", "2");
        e.file("/dust/audio/c.aif", "3");
        e.file("/dust/audio/.d.wav", "4");
        assertEquals("/dust/audio/a.wav\n/dust/audio/b.wav\n", e.sh("ls /dust/audio/*.wav").out);
        assertEquals("/home/we/dust/audio/c.aif\n", e.sh("ls /home/we/dust/audio/c.???").out);
        assertEquals("/dust/audio/a.wav\n/dust/audio/b.wav\n/dust/audio/c.aif\n", e.sh("ls /dust/audio/*").out);
        // aspas desligam o curinga
        assertFailure(2, e.sh("ls '/dust/audio/*.wav'"));
        // curinga em pasta intermediária não é suportado
        assertUnsupported(e.sh("ls /dust/*/a.wav"));
    }

    // ------------------------------------------------------------------ cd e operadores

    @Test public void cdChangesTheFolderForTheRestOfTheLine() throws Exception {
        Env e = new Env();
        e.dir("/dust/data");
        Result r = e.sh("cd /home/we/dust/data && mkdir -p sub && touch sub/f.txt && ls");
        assertSuccess(r);
        assertEquals("sub\n", r.out);
        assertTrue(e.isFile("/dust/data/sub/f.txt"));
        assertEquals("f.txt\n", e.sh("cd /dust/data/sub; ls").out);
        // sem cd, caminhos relativos valem a partir da raiz dust
        assertSuccess(e.sh("mkdir -p data/rel"));
        assertTrue(e.isDir("/dust/data/rel"));
        // cd para pasta que não existe interrompe o && e nada é criado
        String before = e.snapshot();
        assertFailure(1, e.sh("cd /dust/missing && mkdir -p created"));
        assertEquals(before, e.snapshot());
        // cd não vaza para a chamada seguinte
        e.sh("cd /dust/data");
        assertSuccess(e.sh("mkdir -p fresh"));
        assertTrue(e.isDir("/dust/fresh"));
        assertFailure(1, e.sh("cd /etc"));
    }

    @Test public void operatorsFollowShellSemantics() throws Exception {
        Env e = new Env();
        assertFailure(1, e.sh("false && mkdir -p /dust/skipped"));
        assertFalse(e.exists("/dust/skipped"));
        assertSuccess(e.sh("false || mkdir -p /dust/ran"));
        assertTrue(e.isDir("/dust/ran"));
        assertSuccess(e.sh("true && mkdir -p /dust/and"));
        assertSuccess(e.sh("true || mkdir -p /dust/never"));
        assertFalse(e.exists("/dust/never"));
        assertSuccess(e.sh("false; mkdir -p /dust/after-semicolon"));
        assertTrue(e.isDir("/dust/after-semicolon"));
        assertSuccess(e.sh("mkdir -p /dust/n1\nmkdir -p /dust/n2"));
        assertTrue(e.isDir("/dust/n1") && e.isDir("/dust/n2"));
        // um & no fim roda o comando e devolve sucesso
        assertSuccess(e.sh("mkdir -p /dust/bg &"));
        assertTrue(e.isDir("/dust/bg"));
        assertSuccess(e.sh(""));
        assertSuccess(e.sh("  ;  "));
        // o status da linha é o do último comando executado
        assertSuccess(e.sh("false || true"));
        assertFailure(1, e.sh("true && false"));
    }

    @Test public void redirectsGoToNullOrToFiles() throws Exception {
        Env e = new Env();
        e.file("/dust/data/a.txt", "a");
        Result quiet = e.sh("ls /dust/data > /dev/null");
        assertSuccess(quiet);
        assertEquals("", quiet.out);
        Result noErr = e.sh("ls /nonexistent 2>/dev/null");
        assertFailure(2, noErr);
        assertEquals("", noErr.err);
        Result both = e.sh("ls /nonexistent > /dev/null 2>&1");
        assertFailure(2, both);
        assertEquals("", both.out + both.err);
        Result toStdout = e.sh("ls /nonexistent 2>&1");
        assertTrue(toStdout.out, toStdout.out.contains("não foi possível acessar"));
        assertEquals("", toStdout.err);

        // > cria (e trunca) o arquivo antes de rodar, como o shell; a listagem inclui o próprio arquivo
        assertSuccess(e.sh("ls /dust/data > /home/we/dust/data/list.txt"));
        assertEquals("a.txt\nlist.txt\n", e.content("/dust/data/list.txt"));
        assertSuccess(e.sh("ls /dust/data/a.txt >> /dust/data/list.txt"));
        assertEquals("a.txt\nlist.txt\n/dust/data/a.txt\n", e.content("/dust/data/list.txt"));
        // destino que não pode ser criado: o comando nem roda
        String before = e.snapshot();
        assertFailure(1, e.sh("mkdir -p /dust/side-effect > /dust/no/parent/out.txt"));
        assertFailure(1, e.sh("mkdir -p /dust/side-effect > /etc/out.txt"));
        assertEquals(before, e.snapshot());
        assertUnsupported(e.sh("cat < /dust/data/a.txt"));
        assertUnsupported(e.sh("ls > /dust/data/*.txt"));
    }

    // ------------------------------------------------------------------ o que não é suportado

    @Test public void unsupportedCommandsFailWithTheLegacyMessageAndAreLoggedOnce() throws Exception {
        Env e = new Env();
        Result first = e.sh("amixer sset Master 50%");
        assertUnsupported(first);
        assertEquals(127, first.code);
        assertTrue(first.how, first.how.contains("amixer"));
        e.sh("amixer sset Master 60%");
        e.sh("amixer cset numid=1 0");
        assertEquals("o log deve citar o comando uma única vez", 1, e.logLines("amixer"));
        assertUnsupported(e.sh("ls /dust | wc -l"));
        assertUnsupported(e.sh("(mkdir -p /dust/sub)"));
        assertUnsupported(e.sh("sleep 1"));
        assertFalse(e.exists("/dust/sub"));
    }

    @Test public void unsupportedOptionsAreRefusedBeforeTouchingAnything() throws Exception {
        Env e = new Env();
        e.file("/dust/data/a.txt", "A");
        String before = e.snapshot();
        Result longList = e.sh("ls -l /dust/data");
        assertUnsupported(longList);
        assertTrue(longList.how, longList.how.contains("-l"));
        assertUnsupported(e.sh("rm -rfi /dust/data/a.txt"));
        assertUnsupported(e.sh("cp --preserve=all /dust/data/a.txt /dust/data/b.txt"));
        assertUnsupported(e.sh("mkdir --mode=700 /dust/x"));
        assertEquals(before, e.snapshot());
    }

    @Test public void supportedPartsOfAMixedLineStillRunLikeInAShell() throws Exception {
        Env e = new Env();
        Result r = e.sh("mkdir -p /dust/data/mixed && amixer sset Master 50% && mkdir -p /dust/data/after");
        assertUnsupported(r);
        assertEquals(127, r.code);
        assertTrue("o que vem antes do comando sem suporte roda", e.isDir("/dust/data/mixed"));
        assertFalse("o && corta o que vem depois", e.exists("/dust/data/after"));
        // com ";" o resto continua
        assertUnsupported(e.sh("amixer x; mkdir -p /dust/data/semicolon"));
        assertTrue(e.isDir("/dust/data/semicolon"));
    }

    @Test public void veryLongCommandLinesAreRejected() throws Exception {
        Env e = new Env();
        StringBuilder sb = new StringBuilder("mkdir -p /dust/");
        for (int i = 0; i < 17000; i++) sb.append('a');
        assertUnsupported(e.sh(sb.toString()));
    }

    // ------------------------------------------------------------------ nada fora de dust

    @Test public void nothingOutsideDustIsReadWrittenOrRemoved() throws Exception {
        Env e = new Env();
        e.file("/dust/data/a.txt", "A");
        e.dir("/dust/code");
        String before = e.snapshot();
        String[] attempts = {
            "cp /etc/passwd /dust/data/stolen",
            "cp /dust/data/a.txt /etc/out",
            "cp -r /dust/data /tmp/data-copy",
            "mv /dust/data/a.txt /etc/out",
            "mv /etc/passwd /dust/data/x",
            "mkdir -p /etc/x",
            "mkdir -p /home/we/dust/../../etc/zzz",
            "mkdir -p /home/weird/x",
            "touch /etc/x",
            "touch /home/weird/x",
            "cat /etc/passwd",
            "ls /etc",
            "ls /home/weird",
            "echo x > /etc/y",
            "cd /etc && mkdir z",
            "cd /dust/data/../.. && mkdir z",
        };
        for (String cmd : attempts) {
            Result r = e.sh(cmd);
            assertFalse(cmd + " should fail", r.ok);
            assertFalse(cmd + " must not leak outside files", r.out.contains("root:"));
        }
        e.sh("rm -rf /etc /home/weird /tmp");
        assertEquals(before, e.snapshot());
    }

    // ------------------------------------------------------------------ os.execute e io.popen

    @Test public void executeReturnsTheLuaCompatibleTriple() throws Exception {
        Env e = new Env();
        Varargs ok = e.lua("return shell.execute('mkdir -p /dust/data/x')");
        assertTrue(ok.arg(1).toboolean());
        assertEquals("exit", ok.arg(2).tojstring());
        assertEquals(0, ok.arg(3).toint());
        Varargs bad = e.lua("return shell.execute('rm /dust/data/missing')");
        assertTrue(bad.arg(1).isnil());
        assertEquals("exit", bad.arg(2).tojstring());
        assertEquals(1, bad.arg(3).toint());
        Varargs none = e.lua("return shell.execute()");
        assertTrue(none.arg(1).isnil());
        assertTrue(none.arg(2).tojstring().contains("não é suportado"));
        Varargs number = e.lua("return shell.execute(42)");
        assertTrue(number.arg(1).isnil());
    }

    @Test public void executePrintsWhatTheCommandsWriteToTheConsoleHooks() throws Exception {
        Env e = new Env();
        e.file("/dust/data/a.txt", "A\n");
        Result r = e.sh("cat /dust/data/a.txt; ls /nonexistent");
        assertEquals("A\n", r.out);
        assertTrue(r.err, r.err.contains("não foi possível acessar"));
    }

    @Test public void popenCapturesStdoutAsAReadableFile() throws Exception {
        Env e = new Env();
        e.file("/dust/data/one.txt", "1");
        e.file("/dust/data/two.txt", "2");
        e.file("/dust/data/.dot", "3");
        Varargs r = e.lua(""
                + "local f = shell.popen('ls /home/we/dust/data', 'r', FileR_class)\n"
                + "local names = {}\n"
                + "for line in f:lines() do names[#names + 1] = line end\n"
                + "local a, b, c = f:close()\n"
                + "local all = shell.popen('ls -a /dust/data', nil, FileR_class):read('*a')\n"
                + "return table.concat(names, ','), a, b, c, all\n");
        assertEquals("one.txt,two.txt", r.arg(1).tojstring());
        assertTrue(r.arg(2).toboolean());
        assertEquals("exit", r.arg(3).tojstring());
        assertEquals(0, r.arg(4).toint());
        assertEquals(".\n..\n.dot\none.txt\ntwo.txt\n", r.arg(5).tojstring());
    }

    @Test public void popenReportsFailuresAndUnsupportedUsesLikeTheOldStub() throws Exception {
        Env e = new Env();
        Varargs failing = e.lua(""
                + "local f = shell.popen('ls /nonexistent', 'r', FileR_class)\n"
                + "local data = f:read('*a')\n"
                + "local a, b, c = f:close()\n"
                + "return data, a, b, c\n");
        assertEquals("", failing.arg(1).tojstring());
        assertTrue(failing.arg(2).isnil());
        assertEquals("exit", failing.arg(3).tojstring());
        assertEquals(2, failing.arg(4).toint());

        Varargs unsupported = e.lua("return shell.popen('amixer sget Master', 'r', FileR_class)");
        assertTrue(unsupported.arg(1).isnil());
        assertTrue(unsupported.arg(2).tojstring().contains("não é suportado"));
        Varargs writing = e.lua("return shell.popen('mkdir -p /dust/x', 'w', FileR_class)");
        assertTrue(writing.arg(1).isnil());
        assertTrue(writing.arg(2).tojstring().contains("não é suportado"));
        assertFalse("popen em modo de escrita não executa nada", e.exists("/dust/x"));
        Varargs nonString = e.lua("return shell.popen(nil, 'r', FileR_class)");
        assertTrue(nonString.arg(1).isnil());
    }

    @Test public void popenOutputOfSeveralCommandsKeepsTheirOrder() throws Exception {
        Env e = new Env();
        e.file("/dust/data/a.txt", "AAA");
        Varargs r = e.lua(""
                + "local f = shell.popen('echo start; cat /dust/data/a.txt; echo; echo end', 'r', FileR_class)\n"
                + "return f:read('*a')\n");
        assertEquals("start\nAAA\nend\n", r.arg(1).tojstring());
    }

    // ------------------------------------------------------------------ boot.lua

    @Test public void processApisReachTheShellModuleWithTheRealFileHandle() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int start = boot.indexOf("local FileR = {}");
        int end = boot.indexOf("\nluajava = nil", start);
        assertTrue("boot.lua must keep the sandboxed io/os block", start >= 0 && end > start);

        Env e = new Env();
        Globals g = e.g;
        g.set("host", e.fs.get("host"));
        g.load("_path = {data = '/dust/data/'}\n", "path-fixture.lua").call();
        // O módulo de produção chega ao boot.lua pelo require, como no app.
        g.load("package.preload['nornsmobile_shell'] = function() return shell end\n", "preload.lua").call();
        g.load("local noop = function() end\n" + boot.substring(start, end), "boot-io-os.lua").call();

        e.file("/dust/data/one.txt", "1");
        e.file("/dust/data/two.txt", "2");
        Varargs r = e.lua(""
                + "local f = io.popen('ls /home/we/dust/data')\n"
                + "local names = {}\n"
                + "for line in f:lines() do names[#names + 1] = line end\n"
                + "f:close()\n"
                + "local made = os.execute('mkdir -p /home/we/dust/data/viaos/sub')\n"
                + "local ok, why = os.execute('amixer sset Master 50%')\n"
                + "local pf, pwhy = io.popen('amixer sget Master')\n"
                + "return table.concat(names, ','), made, ok, why, pf, pwhy\n");
        assertEquals("one.txt,two.txt", r.arg(1).tojstring());
        assertTrue(r.arg(2).toboolean());
        assertTrue(e.isDir("/dust/data/viaos/sub"));
        assertTrue(r.arg(3).isnil());
        assertTrue(r.arg(4).tojstring().contains("não é suportado"));
        assertTrue(r.arg(5).isnil());
        assertTrue(r.arg(6).tojstring().contains("não é suportado"));
    }

    @Test public void processApisFallBackToTheOldMessageWhenTheModuleCannotBeLoaded() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int start = boot.indexOf("local FileR = {}");
        int end = boot.indexOf("\nluajava = nil", start);
        Globals g = JsePlatform.standardGlobals();
        LuaValue result = g.load(
                "logs = {}\n"
                + "host = {log = function(message, is_error) logs[#logs + 1] = tostring(message) end}\n"
                + "_path = {data = '/tmp/'}\n"
                + "package.preload['nornsmobile_shell'] = function() error('módulo quebrado') end\n"
                + "local noop = function() end\n"
                + boot.substring(start, end)
                + "\nlocal a, b = os.execute('mkdir x')\n"
                + "assert(a == nil and string.find(b, 'não é suportado'), 'os.execute must fail gracefully')\n"
                + "local c, d = io.popen('ls')\n"
                + "assert(c == nil and string.find(d, 'não é suportado'), 'io.popen must fail gracefully')\n"
                + "assert(#logs == 2 and string.find(logs[1], 'Shell:'), 'a falha de carga deve aparecer no log')\n"
                + "return true", "boot-fallback.lua").call();
        assertTrue(result.toboolean());
    }

    @Test public void utilOsCaptureTrimsTheOutputOfSupportedCommands() throws Exception {
        Env e = new Env();
        e.file("/dust/data/one.txt", "1");
        e.file("/dust/data/two.txt", "2");
        Globals g = e.g;
        // io.popen do app: delega ao módulo com o FileR real (o boot.lua faz o mesmo)
        g.load("io.popen = function(cmd, mode) return shell.popen(cmd, mode, FileR_class) end\n", "popen.lua").call();
        LuaValue util = g.load(read("app/src/main/assets/lua/lib/util.lua"), "util.lua").call();
        g.set("util", util);
        Varargs out = e.lua(""
                + "local trimmed = util.os_capture('ls /dust/data')\n"
                + "local raw = util.os_capture('ls /dust/data', true)\n"
                + "local nothing = util.os_capture('amixer sget Master')\n"
                + "return trimmed, raw, nothing\n");
        assertEquals("one.txt two.txt", out.arg(1).tojstring());
        assertEquals("one.txt\ntwo.txt\n", out.arg(2).tojstring());
        assertEquals("", out.arg(3).tojstring());
    }
}
