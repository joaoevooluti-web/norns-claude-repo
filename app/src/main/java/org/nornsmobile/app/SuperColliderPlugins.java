package org.nornsmobile.app;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Installs bundled ARM64 SuperCollider UGen assets into app-private storage. */
final class SuperColliderPlugins {
    private static final String ASSET_ROOT = "scsynth/plugins";
    private SuperColliderPlugins() { }

    /** Identifica a instalação do APK: se não mudou, os arquivos copiados continuam válidos. */
    private static String buildStamp(Context context) {
        try {
            return String.valueOf(context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean upToDate(File root, String stamp, String ext) {
        if (stamp == null) return false;
        File marker = new File(root, ".stamp");
        try {
            if (!marker.isFile()) return false;
            byte[] b = new byte[(int) marker.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(marker)) { if (in.read(b) != b.length) return false; }
            if (!stamp.equals(new String(b, "UTF-8"))) return false;
        } catch (Exception e) {
            return false;
        }
        String[] have = root.list();
        if (have == null) return false;
        for (String n : have) if (n.endsWith(ext)) return true;
        return false;
    }

    private static void writeStamp(File root, String stamp) {
        if (stamp == null) return;
        try (FileOutputStream out = new FileOutputStream(new File(root, ".stamp"))) {
            out.write(stamp.getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    static File install(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "scsynth/plugins");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Não foi possível criar " + root);
        String stamp = buildStamp(context);
        if (upToDate(root, stamp, ".so")) return root;
        String[] names = context.getAssets().list(ASSET_ROOT);
        if (names == null || names.length == 0) throw new IOException("Nenhum plugin SuperCollider encontrado nos assets");
        List<String> sorted = new ArrayList<>();
        Collections.addAll(sorted, names);
        Collections.sort(sorted);
        int installed = 0;
        for (String name : sorted) {
            if (!name.endsWith(".so") || name.contains("/")) continue;
            File target = new File(root, name);
            // Sempre atualiza: evita manter plugins antigos com o mesmo nome.
            copy(context, ASSET_ROOT + "/" + name, target);
            if (target.length() == 0) throw new IOException("Plugin vazio: " + name);
            installed++;
        }
        if (installed == 0) throw new IOException("Nenhum arquivo .so de plugin válido");
        writeStamp(root, stamp);
        AppLog.i("SuperCollider: " + installed + " plugins UGen instalados em " + root.getAbsolutePath());
        return root;
    }

    static File installSynthDefs(Context context) throws IOException {
        String assetRoot = "scsynth/synthdefs";
        File root = new File(context.getFilesDir(), assetRoot);
        if (!root.exists() && !root.mkdirs()) throw new IOException("Não foi possível criar " + root);
        String stamp = buildStamp(context);
        if (upToDate(root, stamp, ".scsyndef")) return root;
        String[] names = context.getAssets().list(assetRoot);
        if (names == null || names.length == 0) throw new IOException("Nenhum SynthDef encontrado nos assets");
        int installed = 0;
        for (String name : names) {
            if (!name.endsWith(".scsyndef") || name.contains("/")) continue;
            File target = new File(root, name);
            copy(context, assetRoot + "/" + name, target);
            if (target.length() == 0) throw new IOException("SynthDef vazio: " + name);
            installed++;
        }
        if (installed == 0) throw new IOException("Nenhum arquivo .scsyndef válido");
        writeStamp(root, stamp);
        AppLog.i("SuperCollider: " + installed + " SynthDef(s) instalados em " + root.getAbsolutePath());
        return root;
    }

    private static void copy(Context context, String asset, File target) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (InputStream in = context.getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        if (target.exists() && !target.delete()) {
            temp.delete();
            throw new IOException("Não foi possível substituir plugin antigo: " + target.getName());
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("Falha ao instalar plugin " + target.getName());
        }
    }
}
