package org.nornsmobile.app;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Marcador dos exemplos embutidos. Antes ele era só ".examples-<versão>": quem já tinha aberto o app nessa versão
 * nunca recebia um script novo do APK (o mobile_selftest não apareceria no aparelho). Agora o nome também depende
 * da lista de arquivos embutidos.
 */
public class DustExamplesMarkerTest {
    private static final List<String> BUNDLED = Arrays.asList(
            "dust/code/hello_norns/hello_norns.lua",
            "dust/code/device_check/device_check.lua",
            "dust/code/polyperc_seq/polyperc_seq.lua");

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        assertTrue("Missing file: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void markerNameHasTheVersionAndAnEightDigitHexOfTheFileList() {
        String name = Dust.examplesMarkerName("0.4.5", BUNDLED);
        assertTrue(name, name.matches("\\.examples-0\\.4\\.5-[0-9a-f]{8}"));
    }

    @Test public void markerNameIgnoresOrderAndDuplicates() {
        List<String> shuffled = new ArrayList<String>(BUNDLED);
        Collections.reverse(shuffled);
        shuffled.add(BUNDLED.get(0));
        assertEquals(Dust.examplesMarkerName("0.4.5", BUNDLED), Dust.examplesMarkerName("0.4.5", shuffled));
    }

    @Test public void aNewBundledScriptChangesTheMarkerEvenWithTheSameVersion() {
        List<String> withSelftest = new ArrayList<String>(BUNDLED);
        withSelftest.add("dust/code/mobile_selftest/mobile_selftest.lua");
        String before = Dust.examplesMarkerName("0.4.5", BUNDLED);
        String after = Dust.examplesMarkerName("0.4.5", withSelftest);
        assertFalse("um exemplo novo precisa disparar a cópia", before.equals(after));
        // O marcador antigo (só a versão) nunca coincide com o novo.
        assertFalse(after.equals(".examples-0.4.5"));
    }

    @Test public void aRenamedOrRemovedScriptAndANewVersionAlsoChangeTheMarker() {
        String base = Dust.examplesMarkerName("0.4.5", BUNDLED);
        assertFalse(base.equals(Dust.examplesMarkerName("0.4.6", BUNDLED)));
        assertFalse(base.equals(Dust.examplesMarkerName("0.4.5", BUNDLED.subList(0, 2))));
        List<String> renamed = new ArrayList<String>(BUNDLED);
        renamed.set(0, "dust/code/hello_norns/hello.lua");
        assertFalse(base.equals(Dust.examplesMarkerName("0.4.5", renamed)));
    }

    @Test public void staleMarkersAreRemovedButTheCurrentOneAndOtherFilesStay() throws IOException {
        File root = Files.createTempDirectory("norns-examples-marker").toFile();
        String keep = Dust.examplesMarkerName("0.4.5", BUNDLED);
        File[] stale = {new File(root, ".examples-0.4.4"), new File(root, ".examples-0.4.5"),
                new File(root, ".examples-0.4.5-00000000")};
        for (File f : stale) assertTrue(f.createNewFile());
        File current = new File(root, keep);
        File folder = new File(root, "code");
        File notes = new File(root, "notes.txt");
        assertTrue(current.createNewFile());
        assertTrue(folder.mkdir());
        assertTrue(notes.createNewFile());

        assertEquals(3, Dust.removeStaleExampleMarkers(root, keep));

        for (File f : stale) assertFalse(f.getName(), f.exists());
        assertTrue(current.exists());
        assertTrue(folder.isDirectory());
        assertTrue(notes.exists());
        assertEquals(0, Dust.removeStaleExampleMarkers(root, keep));
        assertEquals(0, Dust.removeStaleExampleMarkers(new File(root, "nao-existe"), keep));
    }

    @Test public void mainActivityUsesTheListBasedMarkerAndTheBundledSelftestIsPartOfTheAssets() throws Exception {
        String main = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        int start = main.indexOf("private void installExamples()");
        int end = main.indexOf("private void copyAssetTree(", start);
        assertTrue(start >= 0 && end > start);
        String body = main.substring(start, end);
        assertTrue(body.contains("Dust.examplesMarkerName(VERSION, bundled)"));
        assertTrue(body.contains("listAssetFiles(\"dust\", bundled)"));
        assertTrue(body.contains("Dust.removeStaleExampleMarkers(dust.root, markerName)"));
        assertFalse("o marcador só com a versão impede scripts novos de chegarem", body.contains("\".examples-\" + VERSION"));
        assertTrue(read("app/src/main/assets/dust/code/mobile_selftest/mobile_selftest.lua").startsWith("-- mobile_selftest"));
    }
}
