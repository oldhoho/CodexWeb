package com.aishell.codexweb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private WebView web;
    private View consoleView;
    private ProgressBar progress;
    private TextView logView;
    private TextView statusView;
    private TextView launchView;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Android WebView file chooser. We deliberately cancel WebView's own
    // FileList and stage the picked content under filesDir, then expose it to
    // the page in base64 chunks via CXFileBridge. This works on Android 7+
    // without READ_EXTERNAL_STORAGE and avoids content:// fetch failures.
    private static final int REQ_PICK_FILE = 1001;
    private ValueCallback<Uri[]> filePathCallback;
    private final Map<String, File> pendingUploads = new ConcurrentHashMap<>();

    // Stage-2 verification channel: intents carrying content:// URIs write
    // files below filesDir so an automated client can push the runtime.
    static final String EXTRA_KIND = "cx_kind";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sp = getSharedPreferences("cx", MODE_PRIVATE);
        boolean autostart = sp.getBoolean("autostart", true);
        sp.edit().putInt("boots", sp.getInt("boots", 0) + 1).apply();
        setContentView(buildUi());
        BackendService.setLogSink(this::appendLog);
        if (autostart) BackendService.start(getApplicationContext());
        handleIntent(getIntent());
        // Auto-open the in-app WebView once the backend reports ready.
        waitAndOpen(0);
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(statusView);
        Button console = new Button(this);
        console.setText("控制台");
        console.setOnClickListener(v -> showConsole());
        bar.addView(console);
        root.addView(bar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        configureWeb(web);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        consoleView = buildConsole();
        consoleView.setVisibility(View.GONE);
        root.addView(consoleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        refreshStatus();
        ui.postDelayed(this::refreshStatus, 8000);
        return root;
    }

    private LinearLayout buildConsole() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Codex Web · 控制台");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        Button open = new Button(this);
        open.setText("打开");
        open.setOnClickListener(v -> openBackend());
        row.addView(open);
        Button back = new Button(this);
        back.setText("返回页面");
        back.setOnClickListener(v -> showWeb());
        row.addView(back);
        Button restart = new Button(this);
        restart.setText("重启");
        restart.setOnClickListener(v -> {
            BackendService.stop(getApplicationContext());
            ui.postDelayed(() -> {
                BackendService.start(getApplicationContext());
                waitAndOpen(0);
            }, 500);
        });
        row.addView(restart);
        root.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        Button lan = new Button(this);
        lan.setText("局域网");
        lan.setOnClickListener(v -> showLan());
        row2.addView(lan);
        Button stop = new Button(this);
        stop.setText("停止后台");
        stop.setOnClickListener(v -> {
            BackendService.stop(getApplicationContext());
            ui.postDelayed(this::refreshStatus, 600);
        });
        row2.addView(stop);
        Button copyLaunch = new Button(this);
        copyLaunch.setText("复制凭证 URL");
        copyLaunch.setOnClickListener(v -> {
            String url = launchView == null ? "" : launchView.getText().toString().trim();
            if (url.isEmpty() || url.startsWith("凭证")) {
                Toast.makeText(this, "凭证 URL 尚未就绪", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("CodexWeb URL", url));
            Toast.makeText(this, "已复制凭证 URL", Toast.LENGTH_SHORT).show();
        });
        row2.addView(copyLaunch);
        root.addView(row2);

        launchView = new TextView(this);
        launchView.setTextSize(11);
        launchView.setTypeface(Typeface.MONOSPACE);
        launchView.setText("凭证 URL：启动中…");
        root.addView(launchView);

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        scroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(10);
        root.addView(scroll, lp);
        return root;
    }

    private void configureWeb(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // localStorage (session id)
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        w.addJavascriptInterface(new FileBridge(), "CXFileBridge");
        w.addJavascriptInterface(new PhoneControlBridge(this), "CXPhoneBridge");
        w.addJavascriptInterface(new NativeBridge(), "CXNative");
        CookieManager.getInstance().setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, false);
        }
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u != null && ("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) {
                    // The file bridge is only safe on our own local origin.
                    // Send external links to the browser instead of loading
                    // them into a WebView that has a JS interface attached.
                    if ("127.0.0.1".equals(u.getHost()) && u.getPort() == 8123) return false;
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                return true;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                // The page script removes ?token= from the URL on load; mirror that
                // here so the address never lingers in WebView history either.
                if (url != null && url.contains("token=")) {
                    String clean = url.replaceFirst("([?&])token=[^&]*&?", "$1");
                    clean = clean.replaceAll("[?&]$", "");
                    view.loadUrl("javascript:history.replaceState(null,'','" + clean + "')");
                }
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_PICK_FILE);
                } catch (Exception ex) {
                    if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    appendLog("file chooser failed: " + ex);
                }
                return true;
            }
            // The page uses window.confirm()/alert() for destructive actions and
            // the "API unreachable, still send?" guard. Without these overrides
            // WebView silently dismisses JS dialogs (confirm -> false), so those
            // actions appear to do nothing. Show real native dialogs instead.
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("提示")
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) { result.confirm(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) { result.cancel(); }
                        })
                        .show();
                return true;
            }
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("确认")
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) { result.confirm(); }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) { result.cancel(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) { result.cancel(); }
                        })
                        .show();
                return true;
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE) return;
        ValueCallback<Uri[]> callback = filePathCallback;
        filePathCallback = null;
        if (callback == null) return;
        // Cancel WebView's own FileList: the JS side receives staged files via
        // __cxFileReady() and uploads through the normal /api/files/upload API.
        callback.onReceiveValue(null);
        if (resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        for (Uri u : uris) stageUpload(u);
    }

    private void stageUpload(Uri uri) {
        new Thread(() -> {
            File dst = null;
            try {
                String name = uri.getLastPathSegment();
                if (name == null || name.isEmpty()) name = "file";
                name = safeName(name);
                File dir = new File(getFilesDir(), "uploads");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir failed");
                dst = File.createTempFile("cxup-", "-" + name, dir);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dst)) {
                    if (in == null) throw new IOException("cannot open " + uri);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                final String id = UUID.randomUUID().toString().replace("-", "");
                final String fname = name;
                pendingUploads.put(id, dst);
                ui.post(() -> web.evaluateJavascript(
                        "window.__cxFileReady && window.__cxFileReady("
                                + JSONObject.quote(id) + "," + JSONObject.quote(fname) + ")", null));
                appendLog("staged upload " + name + " " + dst.length() + "B");
            } catch (Exception ex) {
                if (dst != null) dst.delete();
                final String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                appendLog("stage upload failed: " + msg);
                ui.post(() -> web.evaluateJavascript(
                        "window.__cxFileError && window.__cxFileError("
                                + JSONObject.quote(msg) + ")", null));
            }
        }).start();
    }

    /** Lets the settings page check / request exemption from Doze on ROMs
     *  that otherwise freeze the background service. */
    public class NativeBridge {
        @JavascriptInterface
        public String batteryState() {
            if (android.os.Build.VERSION.SDK_INT < 23) return "unsupported";
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(getPackageName()) ? "ignored" : "optimized";
        }

        @JavascriptInterface
        public void requestBattery() {
            ui.post(() -> {
                if (android.os.Build.VERSION.SDK_INT < 23) return;
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Toast.makeText(MainActivity.this, "已忽略电池优化", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开设置，请手动关闭本应用的电池优化", Toast.LENGTH_LONG).show();
                }
            });
        }

        /** Message action row: hand text to the system share sheet (WeChat,
         *  Notes, etc.). Runs on the UI thread; chooser keeps the app alive. */
        @JavascriptInterface
        public void shareText(final String text) {
            ui.post(() -> {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
                    startActivity(Intent.createChooser(send, "分享到"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法调起系统分享", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    public class FileBridge {
        @JavascriptInterface
        public long size(String id) {
            File f = pendingUploads.get(id);
            return f == null ? 0 : f.length();
        }

        @JavascriptInterface
        public String read(String id, int offset, int length) {
            File f = pendingUploads.get(id);
            if (f == null || offset < 0 || length <= 0) return "";
            int len = Math.min(length, 1 << 18);
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(offset);
                byte[] buf = new byte[len];
                int n = raf.read(buf, 0, len);
                if (n <= 0) return "";
                return Base64.encodeToString(Arrays.copyOf(buf, n), Base64.NO_WRAP);
            } catch (Exception ex) {
                return "";
            }
        }

        @JavascriptInterface
        public void release(String id) {
            File f = pendingUploads.remove(id);
            if (f != null) f.delete();
        }
    }

    private void showWeb() {
        web.setVisibility(View.VISIBLE);
        consoleView.setVisibility(View.GONE);
    }

    private void showConsole() {
        web.setVisibility(View.GONE);
        consoleView.setVisibility(View.VISIBLE);
    }

    // Poll for the ready state file, then load the backend in the embedded WebView.
    private void waitAndOpen(final int attempt) {
        BackendService.readState(BackendService.stateFile(this), state -> {
            String token = BackendService.extract(state, "token");
            if (token != null) {
                ui.post(this::refreshStatus);
                ui.post(this::showWeb);
                ui.post(() -> loadBackend(token));
            } else if (attempt < 120) {
                ui.postDelayed(() -> waitAndOpen(attempt + 1), 500);
            } else {
                ui.post(() -> appendLog("后端启动超时，请查看控制台日志"));
            }
        });
    }

    private void loadBackend(String token) {
        String url = "http://127.0.0.1:8123/?token=" + token;
        web.loadUrl(url);
        appendLog("打开 " + BackendService.redact(url));
    }

    private void refreshStatus() {
        BackendService.readState(BackendService.stateFile(this), state -> ui.post(() -> {
            String token = BackendService.extract(state, "token");
            statusView.setText(token == null
                    ? "端口 8123 · 启动中…"
                    : "端口 8123 · 运行中 · PIN " + BackendService.extract(state, "pin"));
            if (launchView != null) {
                String url = BackendService.buildUrl(state);
                launchView.setText("凭证 URL："
                        + (url == null ? "启动中…" : url)
                        + "\n（启动器可直接填这个地址；重启后会更换）");
            }
        }));
    }

    private void showLan() {
        BackendService.readState(BackendService.stateFile(this), state -> ui.post(() -> {
            String lan = BackendService.extract(state, "lan");
            String pin = BackendService.extract(state, "pin");
            showConsole();
            appendLog((lan == null ? "未就绪" : "局域网 " + lan) + " · PIN " + pin);
        }));
    }

    private void openBackend() {
        BackendService.readState(BackendService.stateFile(this), state -> {
            String token = BackendService.extract(state, "token");
            if (token == null) {
                ui.post(() -> appendLog("后端尚未就绪"));
                return;
            }
            ui.post(() -> { showWeb(); loadBackend(token); });
        });
    }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) {
            web.goBack();
        } else if (consoleView.getVisibility() == View.VISIBLE) {
            showWeb();
        } else {
            super.onBackPressed();
        }
    }

    void appendLog(String line) {
        ui.post(() -> {
            if (logView == null) return;
            logView.append(line + "\n");
            android.text.Editable text = logView.getEditableText();
            int cut = text.length() - 60000;
            if (cut > 0) text.delete(0, cut);
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // Trust boundary: a content:// URI is a handle granted by the system for
    // one intent; the file is confined to filesDir/<kind>/<basename>.
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String kind = intent.getStringExtra(EXTRA_KIND);
        Uri data = intent.getData();
        if (data != null && "content".equals(data.getScheme())) receive(data, kind);
        if (intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri u = intent.getClipData().getItemAt(i).getUri();
                if (u != null && "content".equals(u.getScheme())) receive(u, kind);
            }
        }
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object v = extras.get(key);
                if (v instanceof String && ((String) v).startsWith("cxbin:")) {
                    writeInline(key, ((String) v).substring(6));
                }
            }
        }
    }

    private void writeInline(String name, String b64) {
        new Thread(() -> {
            try {
                File dst = new File(getFilesDir(), "payload/put/" + safeName(name));
                dst.getParentFile().mkdirs();
                byte[] b = Base64.decode(b64, Base64.DEFAULT);
                try (OutputStream o = new FileOutputStream(dst)) {
                    o.write(b);
                }
                dst.setExecutable(true, false);
                appendLog("inline " + dst + " " + b.length + "B");
            } catch (Exception ex) {
                appendLog("inline failed: " + ex);
            }
        }).start();
    }

    private void receive(Uri uri, String kind) {
        final String sub = kind == null || kind.isEmpty() ? "put" : kind;
        new Thread(() -> {
            try {
                String name = uri.getLastPathSegment();
                if (uri.getQueryParameter("name") != null) name = uri.getQueryParameter("name");
                File dst = new File(getFilesDir(), sub + "/" + safeName(name));
                dst.getParentFile().mkdirs();
                File tmp = new File(dst.getPath() + ".part");
                long total = 0;
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) throw new IOException("cannot open " + uri);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                }
                if (!tmp.renameTo(dst)) throw new IOException("rename failed");
                dst.setExecutable(true, false);
                appendLog("received " + dst + " " + total + "B");
                if (dst.getName().endsWith(".b64")) decodeB64(dst);
            } catch (Exception ex) {
                appendLog("receive failed: " + ex);
            }
        }).start();
    }

    private static void decodeB64(File b64) throws IOException {
        File out = new File(b64.getPath().substring(0, b64.getPath().length() - 4));
        try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(b64));
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            byte[] dec = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                int k = 0;
                for (int i = 0; i < n; i++) {
                    byte c = buf[i];
                    if (c != '\n' && c != '\r' && c != ' ' && c != '\t') dec[k++] = c;
                }
                if (k > 0) os.write(Base64.decode(dec, 0, k, Base64.NO_WRAP));
            }
        }
        out.setExecutable(true, false);
    }

    private static String safeName(String name) throws IOException {
        if (name == null) throw new IOException("no name");
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) throw new IOException("bad name");
        return name;
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
