package org.nornsmobile.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Armazenamento no estilo norns: dust/code, dust/data, dust/audio (dentro da pasta privada do app).
 * Java puro (sem classes do Android) para poder ser testado fora do aparelho.
 */
final class Dust {

    static final long MAX_TOTAL_BYTES = 300L * 1024 * 1024;
    static final long MAX_FILE_BYTES = 150L * 1024 * 1024;
    static final int MAX_ENTRIES = 30000;
    static final int MAX_TEXT_BYTES = 1024 * 1024;

    static final class ImportResult {
        final String name;
        final int files;

        ImportResult(String name, int files) {
            this.name = name;
            this.files = files;
        }
    }

    final File root;
    final File code;
    final File data;
    final File audio;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Set<String> TEXT_EXT = new HashSet<String>(Arrays.asList(
            "lua", "txt", "md", "json", "csv", "pset", "sc", "scd", "ini", "cfg", "xml", "html",
            "yml", "yaml", "log", "toml", "tsv", "gitignore", "license"));

    Dust(File filesDir) {
        root = new File(filesDir, "dust");
        code = new File(root, "code");
        data = new File(root, "data");
        audio = new File(root, "audio");
        code.mkdirs();
        data.mkdirs();
        audio.mkdirs();
    }

    // ------------------------------------------------------------------ caminhos

    /** Pasta pessoal do usuário "we" no norns de verdade; muitos scripts escrevem esse caminho fixo. */
    static final String NORNS_HOME = "/home/we";

    /**
     * Resolve um caminho (absoluto ou relativo a dust/). Retorna null se sair da pasta dust.
     * Caminhos do norns de verdade ("/home/we/dust/data/x" ou "/home/we/x") são traduzidos para
     * dentro de dust/ antes da checagem, então ".." continua sem conseguir escapar.
     */
    File resolve(String path) {
        if (path == null || path.length() == 0) return null;
        try {
            String inside = nornsRelative(path);
            File f;
            if (inside != null) {
                f = new File(root, inside);
            } else {
                f = new File(path);
                if (!f.isAbsolute()) f = new File(root, path);
            }
            File c = f.getCanonicalFile();
            if (isInside(c)) return c;
        } catch (IOException e) {
            // ignora
        }
        return null;
    }

    /**
     * "/home/we", "/home/we/dust/a" e "/home/we/a" viram "", "a" e "a" (relativos a dust/).
     * Qualquer outro caminho, inclusive "/home/weird", devolve null e segue sem tradução.
     */
    static String nornsRelative(String path) {
        if (!path.startsWith(NORNS_HOME)) return null;
        int n = NORNS_HOME.length();
        if (path.length() > n && path.charAt(n) != '/') return null;
        String rest = stripLeadingSlashes(path.substring(n));
        if (rest.equals("dust") || rest.startsWith("dust/")) {
            rest = stripLeadingSlashes(rest.substring(4));
        }
        return rest;
    }

    private static String stripLeadingSlashes(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '/') i++;
        return s.substring(i);
    }

    boolean isInside(File f) {
        try {
            String r = root.getCanonicalPath();
            String p = f.getCanonicalPath();
            return p.equals(r) || p.startsWith(r + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /** Caminho relativo a dust/ para mostrar na interface (ex.: "code/awake"). */
    String display(File f) {
        try {
            String r = root.getCanonicalPath();
            String p = f.getCanonicalPath();
            if (p.equals(r)) return "dust";
            if (p.startsWith(r + File.separator)) return "dust/" + p.substring(r.length() + 1);
        } catch (IOException e) {
            // ignora
        }
        return f.getPath();
    }

    // ------------------------------------------------------------------ listagem

    List<File> list(File dir) {
        File[] arr = dir.listFiles();
        List<File> out = new ArrayList<File>();
        if (arr == null) return out;
        for (File f : arr) {
            if (f.getName().startsWith(".")) continue;
            out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                boolean da = a.isDirectory();
                boolean db = b.isDirectory();
                if (da != db) return da ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    static boolean isScriptDir(File dir) {
        return dir.isDirectory() && new File(dir, dir.getName() + ".lua").isFile();
    }

    static File mainScript(File dir) {
        return new File(dir, dir.getName() + ".lua");
    }

    static String extension(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase();
    }

    static boolean isTextName(String name) {
        return TEXT_EXT.contains(extension(name));
    }

    /** Verdadeiro se o arquivo parece texto: extensão conhecida ou sem bytes nulos no início. */
    static boolean looksLikeText(File f) {
        if (!f.isFile() || f.length() > MAX_TEXT_BYTES) return false;
        if (isTextName(f.getName())) return true;
        String ext = extension(f.getName());
        if (ext.equals("wav") || ext.equals("zip") || ext.equals("png") || ext.equals("jpg")
                || ext.equals("flac") || ext.equals("mp3") || ext.equals("ogg") || ext.equals("aif")
                || ext.equals("aiff") || ext.equals("apk")) return false;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[1024];
            int n = in.read(b);
            for (int i = 0; i < n; i++) if (b[i] == 0) return false;
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    // ------------------------------------------------------------------ leitura / escrita

    static String readText(File f, int maxBytes) throws IOException {
        if (f.length() > maxBytes) throw new IOException("Arquivo grande demais para o editor (" + (f.length() / 1024) + " KB)");
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] bytes = bo.toByteArray();
            int off = 0;
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) off = 3;
            return new String(bytes, off, bytes.length - off, UTF8);
        } finally {
            closeQuietly(in);
        }
    }

    static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes(UTF8));
            out.flush();
        } finally {
            closeQuietly(out);
        }
        if (f.exists() && !f.delete()) throw new IOException("Não foi possível substituir " + f.getName());
        if (!tmp.renameTo(f)) throw new IOException("Não foi possível salvar " + f.getName());
    }

    /** Lê o arquivo como String UTF-8 (ou null se não existir / erro). */
    static String readOrNull(File f) {
        try {
            if (!f.isFile()) return null;
            return readText(f, 8 * 1024 * 1024);
        } catch (IOException e) {
            return null;
        }
    }

    static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        return f.delete();
    }

    /**
     * Apaga um arquivo ou uma pasta inteira (como "rm -rf"), sempre dentro de dust/. Recusa a raiz
     * dust e as pastas code/, data/ e audio/, para que um script com uma variável vazia não apague
     * tudo. Links que apontam para fora de dust/ não são seguidos.
     */
    boolean removeAll(File f) {
        if (f == null || !f.exists() || !isInside(f)) return false;
        try {
            File c = f.getCanonicalFile();
            if (c.equals(root.getCanonicalFile()) || c.equals(code.getCanonicalFile())
                    || c.equals(data.getCanonicalFile()) || c.equals(audio.getCanonicalFile())) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return deleteInside(f);
    }

    private boolean deleteInside(File f) {
        boolean ok = true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!isInside(k)) {
                        ok = false;
                        continue;
                    }
                    ok &= deleteInside(k);
                }
            }
        }
        return f.delete() && ok;
    }

    /**
     * Copia um arquivo ou, com recursive, uma pasta inteira (como "cp" e "cp -r"). Se o destino for
     * uma pasta que já existe, o item é copiado para dentro dela. Origem e destino precisam estar
     * dentro de dust/.
     */
    boolean copy(File src, File dst, boolean recursive) {
        if (src == null || dst == null || !src.exists()) return false;
        if (!isInside(src) || !isInside(dst)) return false;
        try {
            File target = dst.isDirectory() ? new File(dst, src.getName()) : dst;
            if (!isInside(target)) return false;
            String from = src.getCanonicalPath();
            String to = target.getCanonicalPath();
            if (from.equals(to)) return false;
            if (src.isDirectory()) {
                if (!recursive) return false;
                if (to.startsWith(from + File.separator)) return false;
                return copyTree(src, target);
            }
            File parent = target.getParentFile();
            if (parent == null || !parent.isDirectory()) return false;
            return copyFile(src, target);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean copyTree(File src, File dst) throws IOException {
        if (!dst.isDirectory() && !dst.mkdirs()) return false;
        File[] kids = src.listFiles();
        if (kids == null) return true;
        boolean ok = true;
        for (File k : kids) {
            if (!isInside(k)) continue;
            File out = new File(dst, k.getName());
            ok &= k.isDirectory() ? copyTree(k, out) : copyFile(k, out);
        }
        return ok;
    }

    private static boolean copyFile(File src, File dst) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            copyStream(in, out);
            return true;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            // ignora
        }
    }

    // ------------------------------------------------------------------ nomes

    static String sanitizeName(String s) {
        if (s == null) return "novo";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            } else if (Character.isLetter(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String r = sb.toString();
        while (r.startsWith(".")) r = r.substring(1);
        if (r.length() == 0) r = "novo";
        return r;
    }

    static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 2;
        while (true) {
            f = new File(dir, base + "-" + i + ext);
            if (!f.exists()) return f;
            i++;
        }
    }

    // ------------------------------------------------------------------ scripts

    File createScript(String rawName) throws IOException {
        String name = sanitizeName(rawName);
        if (name.endsWith(".lua")) name = name.substring(0, name.length() - 4);
        File dir = uniqueFile(code, name);
        name = dir.getName();
        File f = new File(dir, name + ".lua");
        writeText(f, scriptTemplate(name));
        return f;
    }

    static String scriptTemplate(String name) {
        return "-- " + name + "\n"
                + "-- descricao curta do script\n"
                + "--\n"
                + "-- E3: cutoff\n"
                + "-- K2: toca uma nota\n"
                + "\n"
                + "engine.name = \"PolyPerc\"\n"
                + "\n"
                + "function init()\n"
                + "  print(\"" + name + " iniciado\")\n"
                + "  params:add_control(\"cutoff\", \"cutoff\", controlspec.new(50, 5000, \"exp\", 0, 800, \"Hz\"))\n"
                + "  params:set_action(\"cutoff\", function(x) engine.cutoff(x) end)\n"
                + "end\n"
                + "\n"
                + "function key(n, z)\n"
                + "  if n == 2 and z == 1 then\n"
                + "    engine.hz(220)\n"
                + "  end\n"
                + "end\n"
                + "\n"
                + "function enc(n, d)\n"
                + "  if n == 3 then\n"
                + "    params:delta(\"cutoff\", d)\n"
                + "  end\n"
                + "  redraw()\n"
                + "end\n"
                + "\n"
                + "function redraw()\n"
                + "  screen.clear()\n"
                + "  screen.level(15)\n"
                + "  screen.move(4, 10)\n"
                + "  screen.text(\"" + name + "\")\n"
                + "  screen.update()\n"
                + "end\n";
    }

    // ------------------------------------------------------------------ importação de ZIP

    private static String normalizeEntry(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        if (n.length() == 0) return null;
        String[] parts = n.split("/");
        for (String p : parts) {
            if (p.equals("..")) return null;
        }
        if (n.startsWith("__MACOSX/") || n.endsWith(".DS_Store")) return null;
        return n;
    }

    /**
     * Importa um ZIP. Regras:
     *  - tudo dentro de "dust/"  -> restaura a árvore dust (code/data/audio)
     *  - uma única pasta no topo -> vira dust/code/<pasta> (remove sufixo -main/-master do GitHub)
     *  - arquivos soltos no topo -> dust/code/<nome-do-zip>/
     *  - várias pastas no topo   -> extrai todas em dust/code/
     */
    ImportResult importZip(File zip, String zipBaseName) throws IOException {
        ZipFile zf = new ZipFile(zip);
        try {
            List<ZipEntry> entries = new ArrayList<ZipEntry>();
            List<String> names = new ArrayList<String>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = normalizeEntry(e.getName());
                if (n == null) continue;
                entries.add(e);
                names.add(n);
                if (entries.size() > MAX_ENTRIES) throw new IOException("ZIP com arquivos demais");
            }
            if (entries.isEmpty()) throw new IOException("ZIP vazio");

            Set<String> tops = new HashSet<String>();
            boolean rootFiles = false;
            for (int i = 0; i < names.size(); i++) {
                String n = names.get(i);
                int slash = n.indexOf('/');
                if (slash < 0) {
                    if (!entries.get(i).isDirectory()) rootFiles = true;
                    else tops.add(n);
                } else {
                    tops.add(n.substring(0, slash));
                }
            }

            File destBase;
            String strip = "";
            String label;
            if (!rootFiles && tops.size() == 1 && tops.contains("dust")) {
                destBase = root;
                strip = "dust/";
                label = "dust";
            } else if (!rootFiles && tops.size() == 1
                    && (tops.contains("code") || tops.contains("data") || tops.contains("audio"))) {
                String top = tops.iterator().next();
                destBase = new File(root, top);
                strip = top + "/";
                label = top;
            } else if (!rootFiles && tops.size() == 1) {
                String top = tops.iterator().next();
                String use = top;
                String[] suffixes = {"-main", "-master"};
                for (String suf : suffixes) {
                    if (top.endsWith(suf) && top.length() > suf.length()) {
                        String cand = top.substring(0, top.length() - suf.length());
                        if (names.contains(top + "/" + cand + ".lua")) use = cand;
                    }
                }
                use = sanitizeName(use);
                destBase = new File(code, use);
                strip = top + "/";
                label = use;
            } else if (rootFiles) {
                String base = zipBaseName == null ? "importado" : zipBaseName;
                if (base.toLowerCase().endsWith(".zip")) base = base.substring(0, base.length() - 4);
                base = sanitizeName(base);
                destBase = new File(code, base);
                label = base;
            } else {
                destBase = code;
                label = "code";
            }
            if (!isInside(destBase)) throw new IOException("Destino inválido");

            long total = 0;
            int files = 0;
            for (int i = 0; i < entries.size(); i++) {
                ZipEntry e = entries.get(i);
                String n = names.get(i);
                if (strip.length() > 0) {
                    if (!n.startsWith(strip)) continue;
                    n = n.substring(strip.length());
                    if (n.length() == 0) continue;
                }
                File target = new File(destBase, n);
                if (!isInside(target)) throw new IOException("Entrada ZIP insegura: " + e.getName());
                if (e.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                InputStream in = zf.getInputStream(e);
                OutputStream out = new FileOutputStream(target);
                try {
                    byte[] buf = new byte[16384];
                    long fileBytes = 0;
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        fileBytes += r;
                        total += r;
                        if (fileBytes > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                            throw new IOException("ZIP grande demais");
                        }
                        out.write(buf, 0, r);
                    }
                } finally {
                    closeQuietly(in);
                    closeQuietly(out);
                }
                files++;
            }
            return new ImportResult(label, files);
        } finally {
            try {
                zf.close();
            } catch (IOException e) {
                // ignora
            }
        }
    }

    // ------------------------------------------------------------------ exportação

    /** Compacta uma pasta (ou arquivo) em ZIP; os caminhos começam no nome da pasta. */
    static int zipTo(File src, OutputStream rawOut) throws IOException {
        ZipOutputStream zo = new ZipOutputStream(rawOut);
        int[] count = new int[1];
        try {
            addToZip(zo, src, src.getName(), count);
            zo.finish();
            zo.flush();
        } finally {
            // quem chamou fecha rawOut
        }
        return count[0];
    }

    private static void addToZip(ZipOutputStream zo, File f, String path, int[] count) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            Arrays.sort(kids);
            for (File k : kids) {
                if (k.getName().startsWith(".")) continue;
                addToZip(zo, k, path + "/" + k.getName(), count);
            }
        } else if (f.isFile()) {
            zo.putNextEntry(new ZipEntry(path));
            InputStream in = new FileInputStream(f);
            try {
                copyStream(in, zo);
            } finally {
                closeQuietly(in);
            }
            zo.closeEntry();
            count[0]++;
        }
    }
}
