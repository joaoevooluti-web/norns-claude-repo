package org.nornsmobile.app;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Caminhos do norns de verdade ("/home/we/...") e operações de arquivo usadas pelo shell
 * emulado. Tudo continua preso à pasta dust do app.
 */
public class DustNornsPathTest {

    private static Dust newDust() throws IOException {
        return new Dust(Files.createTempDirectory("norns-dust-test").toFile());
    }

    private static File canonical(File f) throws IOException {
        return f.getCanonicalFile();
    }

    private static void write(File f, byte[] bytes) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private static byte[] read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static byte[] allByteValues() {
        byte[] data = new byte[512];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        return data;
    }

    // ------------------------------------------------------------------ tradução de /home/we

    @Test public void nornsHomePathsAreTranslatedIntoDust() throws Exception {
        Dust dust = newDust();
        File root = canonical(dust.root);
        assertEquals(new File(root, "data/a.txt"), dust.resolve("/home/we/dust/data/a.txt"));
        assertEquals(new File(root, "code/awake/awake.lua"), dust.resolve("/home/we/dust/code/awake/awake.lua"));
        assertEquals(root, dust.resolve("/home/we/dust"));
        assertEquals(root, dust.resolve("/home/we/dust/"));
        assertEquals(root, dust.resolve("/home/we"));
        assertEquals(root, dust.resolve("/home/we/"));
        // a pasta pessoal do app é o próprio dust (mesma convenção de _path.home)
        assertEquals(new File(root, "data/b"), dust.resolve("/home/we/data/b"));
        assertEquals(new File(root, "code/x"), dust.resolve("/home/we//dust//code/x"));
        // só o segmento exato "dust" é removido
        assertEquals(new File(root, "dustbin/x"), dust.resolve("/home/we/dustbin/x"));
        assertEquals(new File(root, "dust2/x"), dust.resolve("/home/we/dust2/x"));
    }

    @Test public void nornsHomeTranslationNeverEscapesDust() throws Exception {
        Dust dust = newDust();
        assertNull(dust.resolve("/home/we/dust/../../etc/passwd"));
        assertNull(dust.resolve("/home/we/../etc/passwd"));
        assertNull(dust.resolve("/home/we/dust/data/../../../x"));
        // prefixos parecidos não são o usuário "we"
        assertNull(dust.resolve("/home/weird/x"));
        assertNull(dust.resolve("/home/we2"));
        assertNull(dust.resolve("/home/other/dust/data/x"));
    }

    @Test public void ordinaryPathsKeepResolvingAsBefore() throws Exception {
        Dust dust = newDust();
        File root = canonical(dust.root);
        assertEquals(new File(root, "data/x"), dust.resolve("data/x"));
        assertEquals(new File(root, "audio/y.wav"), dust.resolve(new File(root, "audio/y.wav").getAbsolutePath()));
        assertNull(dust.resolve("/etc/passwd"));
        assertNull(dust.resolve("../outside"));
        assertNull(dust.resolve(null));
        assertNull(dust.resolve(""));
    }

    @Test public void nornsRelativeOnlyMatchesTheWeHomeDirectory() {
        assertEquals("", Dust.nornsRelative("/home/we"));
        assertEquals("", Dust.nornsRelative("/home/we/dust"));
        assertEquals("data/a", Dust.nornsRelative("/home/we/dust/data/a"));
        assertEquals("norns/sounds", Dust.nornsRelative("/home/we/norns/sounds"));
        assertNull(Dust.nornsRelative("/home/weird"));
        assertNull(Dust.nornsRelative("/home"));
        assertNull(Dust.nornsRelative("home/we/dust"));
        assertNull(Dust.nornsRelative("/tmp/home/we"));
    }

    // ------------------------------------------------------------------ cp

    @Test public void copyIsBinarySafeAndFollowsCpRulesForDirectories() throws Exception {
        Dust dust = newDust();
        File root = canonical(dust.root);
        File src = new File(root, "audio/in.wav");
        write(src, allByteValues());

        // arquivo -> arquivo novo
        File out = new File(root, "audio/out.wav");
        assertTrue(dust.copy(src, out, false));
        assertArrayEquals(allByteValues(), read(out));

        // arquivo -> pasta existente: vai para dentro dela
        File dir = new File(root, "data/pasta");
        assertTrue(dir.mkdirs());
        assertTrue(dust.copy(src, dir, false));
        assertArrayEquals(allByteValues(), read(new File(dir, "in.wav")));

        // sobrescreve um arquivo existente
        write(out, new byte[]{1, 2, 3});
        assertTrue(dust.copy(src, out, false));
        assertArrayEquals(allByteValues(), read(out));

        // a pasta de destino precisa existir ("cp a nao/existe/b" falha)
        assertFalse(dust.copy(src, new File(root, "nao/existe/b.wav"), false));
        assertFalse(new File(root, "nao").exists());

        // origem inexistente e copiar para si mesmo
        assertFalse(dust.copy(new File(root, "audio/nada.wav"), out, false));
        assertFalse(dust.copy(src, src, false));
    }

    @Test public void copyOfDirectoriesNeedsRecursiveAndNeverNestsIntoItself() throws Exception {
        Dust dust = newDust();
        File root = canonical(dust.root);
        File tree = new File(root, "data/tree");
        write(new File(tree, "a.txt"), "a".getBytes("UTF-8"));
        write(new File(tree, "sub/b.bin"), allByteValues());

        File plain = new File(root, "data/plain");
        assertFalse("cp sem -r recusa pastas", dust.copy(tree, plain, false));
        assertFalse(plain.exists());

        File copy = new File(root, "data/copy");
        assertTrue(dust.copy(tree, copy, true));
        assertEquals("a", new String(read(new File(copy, "a.txt")), "UTF-8"));
        assertArrayEquals(allByteValues(), read(new File(copy, "sub/b.bin")));

        // destino já é pasta: copia para dentro dela
        File holder = new File(root, "data/holder");
        assertTrue(holder.mkdirs());
        assertTrue(dust.copy(tree, holder, true));
        assertTrue(new File(holder, "tree/sub/b.bin").isFile());

        // uma pasta nunca é copiada para dentro dela mesma
        assertFalse(dust.copy(tree, new File(tree, "sub"), true));
        assertFalse(dust.copy(tree, new File(tree, "novo"), true));
        assertFalse(new File(tree, "novo").exists());
    }

    @Test public void copyRefusesAnythingOutsideDust() throws Exception {
        Dust dust = newDust();
        File root = canonical(dust.root);
        File outside = new File(Files.createTempDirectory("norns-outside").toFile(), "secret.txt");
        write(outside, "segredo".getBytes("UTF-8"));
        File inside = new File(root, "data/in.txt");
        write(inside, "dentro".getBytes("UTF-8"));

        assertFalse(dust.copy(outside, new File(root, "data/stolen.txt"), false));
        assertFalse(new File(root, "data/stolen.txt").exists());
        assertFalse(dust.copy(inside, outside, false));
        assertEquals("segredo", new String(read(outside), "UTF-8"));
    }

    // ------------------------------------------------------------------ rm -rf

    @Test public void removeAllDeletesTreesButProtectsDustRootAndStandardFolders() throws Exception {
        Dust dust = newDust();
        File root = canonical(dust.root);
        File tree = new File(root, "data/tree");
        write(new File(tree, "a.txt"), new byte[]{1});
        write(new File(tree, "sub/deep/b.txt"), new byte[]{2});
        assertTrue(dust.removeAll(tree));
        assertFalse(tree.exists());

        File single = new File(root, "data/one.txt");
        write(single, new byte[]{3});
        assertTrue(dust.removeAll(single));
        assertFalse(single.exists());

        // a raiz dust e as pastas padrão nunca são apagadas
        write(new File(root, "code/keep/keep.lua"), new byte[]{4});
        assertFalse(dust.removeAll(root));
        assertFalse(dust.removeAll(new File(root, "code")));
        assertFalse(dust.removeAll(new File(root, "data")));
        assertFalse(dust.removeAll(new File(root, "audio")));
        assertTrue(new File(root, "code/keep/keep.lua").isFile());

        // inexistente ou fora de dust/
        assertFalse(dust.removeAll(new File(root, "data/nada")));
        File outside = new File(Files.createTempDirectory("norns-outside").toFile(), "keep.txt");
        write(outside, new byte[]{5});
        assertFalse(dust.removeAll(outside));
        assertTrue(outside.isFile());
        assertFalse(dust.removeAll(null));
    }

    @Test public void removeAllResolvedFromNornsPathStaysInsideDust() throws Exception {
        Dust dust = newDust();
        File root = canonical(dust.root);
        write(new File(root, "data/s/x.txt"), new byte[]{1});
        assertTrue(dust.removeAll(dust.resolve("/home/we/dust/data/s")));
        assertFalse(new File(root, "data/s").exists());
        // "rm -rf /home/we/dust" e "rm -rf /home/we" resolvem para a raiz, que é protegida
        assertFalse(dust.removeAll(dust.resolve("/home/we/dust")));
        assertFalse(dust.removeAll(dust.resolve("/home/we")));
        assertTrue(root.isDirectory());
    }
}
