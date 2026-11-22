package org.nornsmobile.app;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiManager;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Tela única do app: abas SCRIPTS, EDITOR, NORNS, PARAMS e LOG. */
public class MainActivity extends Activity {

    static final String VERSION = "0.4.5";

    private static final int REQ_IMPORT = 1001;
    private static final int REQ_EXPORT = 1002;
    private static final int REQ_STATE_EXPORT = 1003;
    private static final int REQ_STATE_IMPORT = 1004;
    private static final int REQ_AUDIO_INPUT = 1003;

    private static final int TAB_LIB = 0;
    private static final int TAB_EDIT = 1;
    private static final int TAB_NORNS = 2;
    private static final int TAB_PARAMS = 3;
    private static final int TAB_LOG = 4;

    private Dust dust;
    private ScreenSurface surface;
    private AudioEngine audio;
    private LuaHost host;
    private LibraryView library;
    private EditorView editor;
    private NornsView norns;
    private ParamsView paramsView;
    private LogView logView;
    private FrameLayout content;
    private View[] pages;
    private TextView[] tabs;
    private int current = -1;
    private File lastScript;
    private File importTarget;
    private File exportSource;
    private File pendingAudioScript;
    private SharedPreferences prefs;
    private MidiManager midiManager;
    private MidiManager.DeviceCallback midiDeviceCallback;
    private InputManager inputManager;
    private final HashMap<Integer, String> inputDeviceDescriptors = new HashMap<Integer, String>();
    private InputManager.InputDeviceListener inputDeviceListener;

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashHandler();
        try {
            init();
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    private void init() {
        prefs = getSharedPreferences("norns_mobile", MODE_PRIVATE);
        dust = new Dust(getFilesDir());
        installExamples();
        surface = new ScreenSurface();
        audio = new AudioEngine(this);
        host = new LuaHost(this, dust, surface, audio, new LuaHost.Listener() {
            @Override
            public void onState(final boolean running, final String name) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        norns.setRunning(running, name);
                        if (running) {
                            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        } else {
                            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                            paramsView.clear();
                        }
                    }
                });
            }

            @Override
            public void onParams(final String data) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        paramsView.setData(data);
                    }
                });
            }

        });
        setupGamepadDiagnostics();
        String last = prefs.getString("last_script", null);
        if (last != null) {
            File f = new File(last);
            if (f.isFile()) lastScript = f;
        }
        buildUi();
        setupMidiDiagnostics();
        reportPreviousCrash();
        try {
            SuperColliderPlugins.install(this);
        } catch (IOException pluginError) {
            AppLog.e("Falha ao preparar plugins SuperCollider: " + pluginError.getMessage());
        }
        AppLog.i("SuperCollider: " + SuperColliderBridge.status());
        AppLog.i("Norns Mobile " + VERSION + " pronto. Abra um script em SCRIPTS e toque em ▶.");
        showTab(TAB_LIB);
    }

    private void setupGamepadDiagnostics() {
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager == null) return;
        inputDeviceListener = new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int deviceId) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (device != null) inputDeviceDescriptors.put(deviceId, device.getDescriptor());
                updateHidDevice(deviceId, true); updateGamepadDevice(deviceId, true);
            }
            @Override public void onInputDeviceRemoved(int deviceId) {
                inputDeviceDescriptors.remove(deviceId);
                if (host != null) {
                    host.releaseKeyboardDevice(deviceId);
                    host.releaseMouseDevice(deviceId);
                }
                // Queue gamepad key/axis releases before HID removal so Lua never
                // loses the device while controls are still logically held.
                updateGamepadDevice(deviceId, false);
                updateHidDevice(deviceId, false);
            }
            @Override public void onInputDeviceChanged(int deviceId) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (device == null) return;
                String descriptor = device.getDescriptor();
                String previous = inputDeviceDescriptors.put(deviceId, descriptor);
                if (previous != null && !previous.equals(descriptor)) {
                    if (host != null) {
                        host.releaseKeyboardDevice(deviceId);
                        host.releaseMouseDevice(deviceId);
                    }
                    updateGamepadDevice(deviceId, false);
                    updateHidDevice(deviceId, false);
                }
                updateHidDevice(deviceId, true);
                updateGamepadDevice(deviceId, true);
            }
        };
        inputManager.registerInputDeviceListener(inputDeviceListener, null);
        for (int id : inputManager.getInputDeviceIds()) { updateHidDevice(id, true); updateGamepadDevice(id, true); }
    }

    private static boolean isHidInputDevice(InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                || (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private void updateHidDevice(int deviceId, boolean added) {
        InputDevice device = added ? InputDevice.getDevice(deviceId) : null;
        if (added && device == null) return;
        if (added) inputDeviceDescriptors.put(deviceId, device.getDescriptor());
        if (added && !isHidInputDevice(device)) {
            if (host != null) host.hidDeviceChanged(deviceId, device.getName(),
                    device.getVendorId(), device.getProductId(), device.getDescriptor(), false);
            return;
        }
        if (host != null) host.hidDeviceChanged(deviceId,
                device == null ? "Android HID" : device.getName(),
                device == null ? 0 : device.getVendorId(),
                device == null ? 0 : device.getProductId(),
                device == null ? null : device.getDescriptor(), added);
    }

    private void updateGamepadDevice(int deviceId, boolean added) {
        InputDevice device = added ? InputDevice.getDevice(deviceId) : null;
        if (added && device == null) return;
        if (added && !isGameController(device)) {
            if (host != null) host.gamepadDeviceChanged(deviceId, device.getName(), false);
            return;
        }
        if (host != null) host.gamepadDeviceChanged(deviceId,
                device == null ? "Android Gamepad" : device.getName(), added);
    }

    private static boolean isGameController(InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (midiManager != null) logMidiDevices("retomada do app");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (host != null) {
            host.releaseKeyboardKeys();
            host.releaseMouseButtons();
            host.releaseGamepadInputs();
        }
        if (norns != null) norns.releaseInputs();
        if (editor != null) editor.saveIfDirty();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (host != null && audio != null && !host.isRunning()) audio.stop();
    }

    @Override
    protected void onDestroy() {
        if (inputManager != null && inputDeviceListener != null) {
            try { inputManager.unregisterInputDeviceListener(inputDeviceListener); } catch (RuntimeException ignored) {}
        }
        if (norns != null) norns.releaseInputs();
        if (midiManager != null && midiDeviceCallback != null) {
            try { midiManager.unregisterDeviceCallback(midiDeviceCallback); } catch (RuntimeException ignored) {}
        }
        if (host != null) host.dispose();
        if (audio != null) audio.stop();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // Do not steal key events from the script editor or other Android controls.
        if (current == TAB_NORNS && host != null && host.gamepadKeyEvent(event)) return true;
        if (current == TAB_NORNS && host != null && host.keyboardEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (current == TAB_NORNS && host != null && host.hidMouseMotionEvent(event)) return true;
        if (current == TAB_NORNS && host != null && host.gamepadMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && host != null) {
            host.releaseKeyboardKeys();
            host.releaseMouseButtons();
            host.releaseGamepadInputs();
        }
    }

    @Override
    public void onBackPressed() {
        if (library != null) {
            if (current == TAB_LIB && library.canGoUp()) {
                library.goUp();
                return;
            }
            if (current != TAB_LIB) {
                showTab(TAB_LIB);
                return;
            }
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ interface

    private void buildUi() {
        getWindow().setStatusBarColor(Theme.BG);
        getWindow().setNavigationBarColor(Theme.PANEL);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                    return insets;
                }
            });
        }

        library = new LibraryView(this, dust, new LibraryView.Callbacks() {
            @Override
            public void onOpenFile(File f) {
                editor.openFile(f);
                showTab(TAB_EDIT);
            }

            @Override
            public void onRunScript(File luaFile) {
                runScript(luaFile);
            }

            @Override
            public void onImport(File targetDir) {
                importTarget = targetDir;
                startImportPicker();
            }

            @Override
            public void onExport(File src) {
                exportSource = src;
                startExportPicker(src);
            }

            @Override
            public void onRemoved(File f) {
                editor.closeIfInside(f);
            }
        });
        editor = new EditorView(this, dust, new EditorView.Callbacks() {
            @Override
            public void onRun(File file) {
                runScript(file);
            }
        });
        norns = new NornsView(this, surface, new NornsView.Callbacks() {
            @Override
            public void onRun() {
                if (lastScript != null) {
                    runScript(lastScript);
                } else {
                    Toast.makeText(MainActivity.this, "Escolha um script na aba SCRIPTS.", Toast.LENGTH_SHORT).show();
                    showTab(TAB_LIB);
                }
            }

            @Override
            public void onStop() {
                norns.releaseInputs();
                host.stop();
            }

            @Override
            public void onTest() {
                audio.playTest();
            }

            @Override
            public void onKey(int n, int z) {
                host.key(n, z);
            }

            @Override
            public void onEnc(int n, int d) {
                host.enc(n, d);
            }
        });
        paramsView = new ParamsView(this, new ParamsView.Callbacks() {
            @Override
            public void onNorm(String id, double norm) {
                host.paramNorm(id, norm);
            }

            @Override
            public void onDelta(String id, int d) {
                host.paramDelta(id, d);
            }

            @Override
            public void onTrigger(String id) {
                host.paramTrigger(id);
            }

            @Override
            public void onText(String id, String value) { host.paramSetValue(id, value); }

            @Override
            public void onFile(String id, String path) { startParamFilePicker(id, path); }

            @Override
            public void onPresetRead(int slot) {
                host.paramPresetRead(slot);
            }

            @Override
            public void onPresetWrite(int slot) {
                host.paramPresetWrite(slot);
            }

            @Override
            public void onPresetDelete(int slot) {
                host.paramPresetDelete(slot);
            }

            @Override
            public void onStateExport() {
                host.paramStateExport(new java.util.function.Consumer<Boolean>() {
                    @Override public void accept(final Boolean ok) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (!ok) {
                                    Toast.makeText(MainActivity.this, "Nenhum script ativo ou falha ao gravar o estado.", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                i.addCategory(Intent.CATEGORY_OPENABLE);
                                i.setType("application/octet-stream");
                                i.putExtra(Intent.EXTRA_TITLE, "norns-estado.pset");
                                try { startActivityForResult(i, REQ_STATE_EXPORT); }
                                catch (RuntimeException e) {
                                    Toast.makeText(MainActivity.this, "Não foi possível abrir o seletor de destino.", Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                });
            }

            @Override
            public void onStateImport() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, REQ_STATE_IMPORT); }
                catch (RuntimeException e) {
                    Toast.makeText(MainActivity.this, "Não foi possível abrir o seletor de arquivos.", Toast.LENGTH_LONG).show();
                }
            }
        });
        logView = new LogView(this);

        pages = new View[]{library, editor, norns, paramsView, logView};
        content = new FrameLayout(this);
        for (int i = 0; i < pages.length; i++) {
            content.addView(pages[i], new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Theme.PANEL);
        String[] names = {"SCRIPTS", "EDITOR", "NORNS", "PARAMS", "LOG"};
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(11);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Theme.dp(this, 14), 0, Theme.dp(this, 14));
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(idx);
                }
            });
            tabs[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void showTab(int index) {
        if (pages == null) return;
        if (current == TAB_NORNS && index != TAB_NORNS && host != null) {
            host.releaseKeyboardKeys();
            host.releaseMouseButtons();
            host.releaseGamepadInputs();
        }
        if (current == TAB_EDIT && index != TAB_EDIT) {
            editor.saveIfDirty();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(content.getWindowToken(), 0);
        }
        current = index;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == index ? Theme.ACCENT : Theme.MUTED);
            tabs[i].setBackgroundColor(i == index ? Theme.PANEL2 : Theme.PANEL);
        }
    }

    private void showFatal(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        TextView tv = new TextView(this);
        tv.setText("O Norns Mobile não conseguiu iniciar.\nCopie este texto e envie ao desenvolvedor:\n\n" + sw);
        tv.setTextIsSelectable(true);
        tv.setTextColor(0xFFFF9090);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(Theme.dp(this, 16), Theme.dp(this, 32), Theme.dp(this, 16), Theme.dp(this, 16));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Theme.BG);
        sv.addView(tv);
        setContentView(sv);
    }

    // ------------------------------------------------------------------ scripts

    private void runScript(File f) {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAudioScript = f;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_INPUT);
            return;
        }
        if (!f.isFile()) {
            Toast.makeText(this, "Arquivo não encontrado: " + f.getName(), Toast.LENGTH_LONG).show();
            return;
        }
        if (norns != null) norns.releaseInputs();
        editor.saveIfDirty();
        lastScript = f;
        prefs.edit().putString("last_script", f.getAbsolutePath()).apply();
        paramsView.clear();
        host.start(f);
        showTab(TAB_NORNS);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_INPUT) {
            File pending = pendingAudioScript;
            pendingAudioScript = null;
            if (pending != null) {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (audio != null) audio.startInput();
                    runScript(pending);
                } else {
                    Toast.makeText(this, "Microfone não autorizado. O script será iniciado sem gravação externa.", Toast.LENGTH_LONG).show();
                    runScriptWithoutInput(pending);
                }
            }
        }
    }

    private void runScriptWithoutInput(File f) {
        if (!f.isFile()) return;
        if (norns != null) norns.releaseInputs();
        editor.saveIfDirty();
        lastScript = f;
        prefs.edit().putString("last_script", f.getAbsolutePath()).apply();
        paramsView.clear();
        host.start(f);
        showTab(TAB_NORNS);
    }

    // ------------------------------------------------------------------ exemplos embutidos

    private void installExamples() {
        File marker = new File(dust.root, ".examples-" + VERSION);
        if (marker.exists()) return;
        try {
            copyAssetTree("dust", dust.root);
            marker.createNewFile();
        } catch (IOException e) {
            AppLog.e("Não foi possível instalar os exemplos: " + e.getMessage());
        }
    }

    private void copyAssetTree(String assetPath, File dest) throws IOException {
        String[] kids = getAssets().list(assetPath);
        if (kids == null || kids.length == 0) {
            if (!dest.exists()) {
                InputStream in = getAssets().open(assetPath);
                try {
                    File parent = dest.getParentFile();
                    if (parent != null) parent.mkdirs();
                    java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
                    try {
                        Dust.copyStream(in, out);
                    } finally {
                        Dust.closeQuietly(out);
                    }
                } finally {
                    Dust.closeQuietly(in);
                }
            }
            return;
        }
        dest.mkdirs();
        for (int i = 0; i < kids.length; i++) {
            copyAssetTree(assetPath + "/" + kids[i], new File(dest, kids[i]));
        }
    }

    // ------------------------------------------------------------------ importar / exportar

    private void startImportPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Não foi possível abrir o seletor de arquivos.", Toast.LENGTH_LONG).show();
        }
    }

    private void startParamFilePicker(String id, String rootPath) {
        File allowedRoot = rootPath == null || rootPath.length() == 0 ? dust.audio : dust.resolve(rootPath);
        if (allowedRoot == null || !dust.isInside(allowedRoot)) {
            Toast.makeText(this, "Diretório do parâmetro fora da pasta Dust.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!allowedRoot.exists() && !allowedRoot.mkdirs()) {
            Toast.makeText(this, "Não foi possível preparar o diretório Dust.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!allowedRoot.isDirectory()) {
            Toast.makeText(this, "O caminho permitido não é uma pasta.", Toast.LENGTH_LONG).show();
            return;
        }
        showParamFileDirectory(id, allowedRoot, allowedRoot);
    }

    private boolean isWithinDirectory(File root, File candidate) {
        try {
            String base = root.getCanonicalPath();
            String path = candidate.getCanonicalPath();
            return path.equals(base) || path.startsWith(base + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private void showParamFileDirectory(final String id, final File allowedRoot, final File current) {
        if (!dust.isInside(current) || !isWithinDirectory(allowedRoot, current) || !current.isDirectory()) {
            Toast.makeText(this, "Navegação fora do diretório Dust permitido.", Toast.LENGTH_LONG).show();
            return;
        }
        final ArrayList<File> entries = new ArrayList<File>();
        final ArrayList<String> labels = new ArrayList<String>();
        boolean atRoot;
        try { atRoot = current.getCanonicalFile().equals(allowedRoot.getCanonicalFile()); }
        catch (IOException e) {
            Toast.makeText(this, "Não foi possível validar o diretório.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!atRoot) {
            entries.add(null);
            labels.add("↩ ..");
        }
        List<File> children = dust.list(current);
        for (int i = 0; i < children.size(); i++) {
            File child = children.get(i);
            if (!dust.isInside(child) || !isWithinDirectory(allowedRoot, child)) continue;
            entries.add(child);
            labels.add((child.isDirectory() ? "📁 " : "   ") + child.getName());
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, "Nenhum arquivo nesta pasta Dust.", Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Selecionar arquivo · " + dust.display(current))
                .setItems(labels.toArray(new String[labels.size()]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        File selected = entries.get(which);
                        if (selected == null) {
                            File parent = current.getParentFile();
                            if (parent != null && isWithinDirectory(allowedRoot, parent))
                                showParamFileDirectory(id, allowedRoot, parent);
                            return;
                        }
                        if (selected.isDirectory()) {
                            showParamFileDirectory(id, allowedRoot, selected);
                            return;
                        }
                        try {
                            File canonical = selected.getCanonicalFile();
                            if (!canonical.isFile() || !dust.isInside(canonical)
                                    || !isWithinDirectory(allowedRoot, canonical)) {
                                Toast.makeText(MainActivity.this, "Arquivo fora do diretório permitido.", Toast.LENGTH_LONG).show();
                                return;
                            }
                            host.paramSetValue(id, canonical.getAbsolutePath());
                            Toast.makeText(MainActivity.this, "Arquivo selecionado: " + canonical.getName(), Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Toast.makeText(MainActivity.this, "Não foi possível validar o arquivo.", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("CANCELAR", null)
                .show();
    }

    private void startExportPicker(File src) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, src.getName() + ".zip");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Não foi possível abrir o seletor de destino.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_IMPORT) {
            List<Uri> uris = new ArrayList<Uri>();
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            importUris(uris);
        } else if (requestCode == REQ_STATE_EXPORT || requestCode == REQ_STATE_IMPORT) {
            final Uri u = data.getData();
            if (u == null) return;
            final boolean exporting = requestCode == REQ_STATE_EXPORT;
            new Thread(new Runnable() {
                @Override public void run() {
                    final File transfer = new File(dust.data, "state-transfer.pset");
                    String result;
                    try {
                        if (exporting) {
                            OutputStream out = getContentResolver().openOutputStream(u);
                            if (out == null) throw new IOException("não foi possível gravar no destino");
                            try {
                                java.io.FileInputStream in = new java.io.FileInputStream(transfer);
                                try { Dust.copyStream(in, out); } finally { Dust.closeQuietly(in); }
                                out.flush();
                            } finally { Dust.closeQuietly(out); }
                            result = "Estado exportado.";
                        } else {
                            copyUriTo(u, transfer);
                            host.paramStateImport(new java.util.function.Consumer<Boolean>() {
                                @Override public void accept(final Boolean ok) {
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            Toast.makeText(MainActivity.this, ok ? "Estado importado." : "Não foi possível importar (há um script rodando?).", Toast.LENGTH_LONG).show();
                                        }
                                    });
                                }
                            });
                            return;
                        }
                    } catch (Exception e) {
                        result = "Falha: " + e.getMessage();
                    }
                    final String msg = result;
                    runOnUiThread(new Runnable() {
                        @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
                    });
                }
            }).start();
        } else if (requestCode == REQ_EXPORT) {
            Uri u = data.getData();
            if (u != null) exportTo(u);
        }
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && n.length() > 0) return n;
            }
        } catch (RuntimeException e) {
            // usa o fallback abaixo
        } finally {
            if (c != null) c.close();
        }
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : "importado";
    }

    private void copyUriTo(Uri uri, File dest) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("não foi possível ler o arquivo");
        java.io.FileOutputStream out = null;
        try {
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            out = new java.io.FileOutputStream(dest);
            Dust.copyStream(in, out);
        } finally {
            Dust.closeQuietly(in);
            Dust.closeQuietly(out);
        }
    }

    private void importUris(final List<Uri> uris) {
        final File target = importTarget != null ? importTarget : dust.code;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder msg = new StringBuilder();
                for (int i = 0; i < uris.size(); i++) {
                    Uri u = uris.get(i);
                    String name = displayName(u);
                    try {
                        if (name.toLowerCase().endsWith(".zip")) {
                            File tmp = new File(getCacheDir(), "import.zip");
                            copyUriTo(u, tmp);
                            Dust.ImportResult r = dust.importZip(tmp, name);
                            tmp.delete();
                            msg.append("ZIP importado: ").append(r.name).append(" (").append(r.files).append(" arquivos)\n");
                        } else {
                            File dest = Dust.uniqueFile(target, Dust.sanitizeName(name));
                            copyUriTo(u, dest);
                            msg.append("Copiado: ").append(dest.getName()).append('\n');
                        }
                    } catch (Exception e) {
                        msg.append("Falha ao importar ").append(name).append(": ").append(e.getMessage()).append('\n');
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String s = msg.toString().trim();
                        AppLog.i(s);
                        Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show();
                        library.refresh();
                    }
                });
            }
        }, "norns-import").start();
    }

    private void exportTo(final Uri uri) {
        final File src = exportSource;
        if (src == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                OutputStream out = null;
                try {
                    out = getContentResolver().openOutputStream(uri);
                    if (out == null) throw new IOException("não foi possível gravar no destino");
                    int n = Dust.zipTo(src, out);
                    out.flush();
                    result = "Exportado: " + src.getName() + ".zip (" + n + " arquivos)";
                } catch (Exception e) {
                    result = "Falha ao exportar: " + e.getMessage();
                } finally {
                    Dust.closeQuietly(out);
                }
                final String msg = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        AppLog.i(msg);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "norns-export").start();
    }

    // ------------------------------------------------------------------ diagnóstico MIDI Android

    private void setupMidiDiagnostics() {
        midiManager = (MidiManager) getSystemService(Context.MIDI_SERVICE);
        if (midiManager == null) {
            AppLog.e("MIDI diagnóstico: MidiManager indisponível neste Android.");
            return;
        }
        AppLog.i("MIDI diagnóstico: Android API " + Build.VERSION.SDK_INT
                + " | fabricante=" + Build.MANUFACTURER + " | modelo=" + Build.MODEL);
        logMidiDevices("inicialização");
        midiDeviceCallback = new MidiManager.DeviceCallback() {
            @Override public void onDeviceAdded(MidiDeviceInfo info) {
                AppLog.i("MIDI diagnóstico: dispositivo ADICIONADO pelo Android.");
                logMidiInfo(info, "adicionado");
            }
            @Override public void onDeviceRemoved(MidiDeviceInfo info) {
                AppLog.e("MIDI diagnóstico: dispositivo REMOVIDO pelo Android: id=" + info.getId());
            }
            @Override public void onDeviceStatusChanged(MidiDeviceStatus status) {
                MidiDeviceInfo info = status.getDeviceInfo();
                AppLog.i("MIDI diagnóstico: status alterado id=" + info.getId()
                        + " | portas de entrada=" + info.getInputPortCount()
                        + " | portas de saída=" + info.getOutputPortCount());
            }
        };
        midiManager.registerDeviceCallback(midiDeviceCallback, new android.os.Handler(getMainLooper()));
    }

    private void logMidiDevices(String reason) {
        try {
            MidiDeviceInfo[] devices = midiManager.getDevices();
            int count = devices == null ? 0 : devices.length;
            AppLog.i("MIDI diagnóstico [" + reason + "]: Android enumerou " + count + " dispositivo(s).");
            if (devices != null) {
                for (MidiDeviceInfo info : devices) logMidiInfo(info, "enumerado");
            }
        } catch (RuntimeException error) {
            AppLog.e("MIDI diagnóstico: falha ao consultar dispositivos: " + error.getMessage());
        }
    }

    private void logMidiInfo(MidiDeviceInfo info, String event) {
        if (info == null) return;
        android.os.Bundle p = info.getProperties();
        String name = p.getString(MidiDeviceInfo.PROPERTY_NAME, "(sem nome)");
        String manufacturer = p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER, "(não informado)");
        String product = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT, "(não informado)");
        AppLog.i("MIDI dispositivo " + event + ": id=" + info.getId()
                + " | nome=" + name
                + " | fabricante=" + manufacturer
                + " | produto=" + product
                + " | tipo=" + info.getType()
                + " | portas de entrada=" + info.getInputPortCount()
                + " | portas de saída=" + info.getOutputPortCount());
    }

    // ------------------------------------------------------------------ diagnóstico

    private void installCrashHandler() {
        final File f = new File(getFilesDir(), "last_crash.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println("Thread: " + t.getName());
                    e.printStackTrace(pw);
                    pw.flush();
                    Dust.writeText(f, sw.toString());
                } catch (Throwable ignored) {
                    // não há mais nada a fazer
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private void reportPreviousCrash() {
        File f = new File(getFilesDir(), "last_crash.txt");
        String text = Dust.readOrNull(f);
        if (text != null) {
            AppLog.e("O app fechou inesperadamente da última vez:\n" + text);
            f.delete();
            Toast.makeText(this, "O app fechou da última vez. Veja a aba LOG.", Toast.LENGTH_LONG).show();
        }
    }
}
