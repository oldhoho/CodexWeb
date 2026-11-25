package com.aishell.codexweb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BackendService extends Service {
    static final String CH = "codexweb";
    static final int NOTE_ID = 7;
    // codexd announces the token/PIN on its own stdout; nothing else parses it.
    private static final String PREFIX = "CXINFO ";

    private static volatile Process proc;
    private static volatile boolean starting;
    private static volatile Sink sink;
    private static final StringBuilder tail = new StringBuilder();
    private PowerManager.WakeLock wake;
    private static volatile DevicePump devicePump;
    // Android may reclaim the service at any time. Track whether the *user*
    // asked to stop so the supervisor can restart after an OS kill but stays
    // quiet after an explicit stop from the console.
    private static volatile boolean userStopped;
    private static volatile Thread guard;
    private static volatile long lastBootStart;

    interface Sink {
        void log(String line);
    }

    interface StateCb {
        void onState(String state);
    }

    static void setLogSink(Sink s) {
        sink = s;
        synchronized (tail) {
            if (s != null && tail.length() > 0) s.log(tail.toString());
        }
    }

    static void log(String msg) {
        String line = redact(msg);
        synchronized (tail) {
            tail.append(line).append('\n');
            int cut = tail.length() - 30000;
            if (cut > 0) tail.delete(0, cut);
        }
        Sink s = sink;
        if (s != null) s.log(line);
        android.util.Log.i("CodexWeb", line);
    }

    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile(
            "(?i)(sk-[A-Za-z0-9_\\-]{6,}|bearer\\s+[A-Za-z0-9._\\-]{6,}|token[=:\\\" ]+[A-Za-z0-9_\\-]{12,})");

    static String redact(String s) {
        return SECRET.matcher(s).replaceAll("***");
    }

    static File stateFile(Context ctx) {
        return new File(ctx.getFilesDir(), "state.json");
    }

    // Credentials survive an automatic backend restart. Without this every
    // self-heal rotated the token/PIN/launch-key, instantly invalidating the
    // open WebView session and any launcher URL the user had saved.
    static File credsFile(Context ctx) {
        return new File(ctx.getFilesDir(), "creds.json");
    }

    private static String field(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String[] readCreds(Context ctx) {
        try {
            File f = credsFile(ctx);
            if (!f.isFile()) return null;
            String s = readText(f);
            String token = field(s, "token"), pin = field(s, "pin"), key = field(s, "launch_key");
            if (token == null || token.length() < 16 || pin == null || pin.length() != 4
                    || key == null || key.length() < 16) return null;
            return new String[]{token, pin, key};
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeCreds(Context ctx, String token, String pin, String key) {
        try {
            writeText(credsFile(ctx), "{\"token\":\"" + token + "\",\"pin\":\"" + pin
                    + "\",\"launch_key\":\"" + key + "\"}");
        } catch (IOException ignored) {
        }
    }

    static void start(Context ctx) {
        userStopped = false;
        Intent i = new Intent(ctx, BackendService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    static void stop(Context ctx) {
        userStopped = true;
        ctx.stopService(new Intent(ctx, BackendService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Codex Web", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTE_ID, notification("正在启动"));
    }

    private Notification notification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setContentTitle("Codex Web :8123")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A foreground service that returns NOT_STICKY is gone for good once
        // the OS reclaims it. STICKY asks the system to recreate us; the guard
        // below covers the case where the service survives but codexd dies.
        // A null intent means an OS-initiated restart: honour an explicit stop.
        if (intent == null && userStopped) return START_STICKY;
        if (proc != null && proc.isAlive()) {
            startGuard();
            return START_STICKY;
        }
        if (!starting) {
            starting = true;
            lastBootStart = System.currentTimeMillis();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (wake == null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "codexweb:backend");
                wake.setReferenceCounted(false);
            }
            wake.acquire(10 * 60 * 1000L);
            new Thread(this::boot, "cx-boot").start();
        }
        startGuard();
        return START_STICKY;
    }

    /** Keep codexd alive even when the service itself is not recreated. */
    private void startGuard() {
        Thread g = guard;
        if (g != null && g.isAlive()) return;
        guard = new Thread(() -> {
            long nextWake = System.currentTimeMillis() + 60000;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
                if (userStopped) continue;
                // Renew the wake lock periodically with a bounded timeout so a
                // leaked, never-released lock cannot pin the device awake.
                if (System.currentTimeMillis() >= nextWake) {
                    nextWake = System.currentTimeMillis() + 60000;
                    PowerManager.WakeLock wl = wake;
                    if (wl != null) {
                        try { wl.acquire(2 * 60 * 1000L); } catch (Exception ignored) { }
                    }
                }
                Process p = proc;
                boolean alive = p != null && p.isAlive();
                boolean serving = alive && awaitPort(8123, 700);
                if (!starting && !serving) {
                    // Give an in-flight boot enough time before declaring it dead.
                    if (System.currentTimeMillis() - lastBootStart < 8000) continue;
                    log("[guard] backend down (alive=" + alive + "), restarting");
                    if (p != null) {
                        try { p.destroy(); } catch (Exception ignored) { }
                    }
                    DevicePump.stop();
                    stateFile(this).delete();
                    starting = true;
                    lastBootStart = System.currentTimeMillis();
                    new Thread(this::boot, "cx-boot-guard").start();
                }
            }
        }, "cx-guard");
        guard.setDaemon(true);
        guard.start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Thread g = guard;
        if (g != null) { g.interrupt(); guard = null; }
        Process p = proc;
        if (p != null) {
            p.destroy();
            try {
                p.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        proc = null;
        starting = false;
        DevicePump.stop();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    private void phase(String s) {
        getSystemService(NotificationManager.class).notify(NOTE_ID, notification(s));
        log("[boot] " + s);
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    private void boot() {
        String token;
        String pin;
        String launchKey;
        Process mine = null;
        try {
            long bootStart = System.currentTimeMillis();
            File files = getFilesDir();
            // The UI and codexd.py are small and independent of the rootfs, so
            // they are copied fresh on every start. That lets a payload pushed
            // through the dev channel take effect after a plain restart.
            File ro = new File(RuntimePayload.ensure(this));
            long ensureMs = System.currentTimeMillis() - bootStart;
            BackendService.log("[boot] ensure " + ensureMs + "ms ro=" + ro.getAbsolutePath()
                    + " list=" + java.util.Arrays.toString(ro.listFiles()));
            File web = new File(files, "codexweb");
            File ui = new File(web, "ui");
            ui.mkdirs();
            RuntimePayload.writeExecutable(new File(web, "launch.sh"),
                    RuntimePayload.readText(RuntimePayload.member(this, "codexweb/launch.sh")));
            copyText(RuntimePayload.member(this, "codexweb/codexd.py"), new File(web, "codexd.py"));
            copyText(RuntimePayload.member(this, "codexweb/ui/index.html"), new File(ui, "index.html"));
            // Offline tool jars (apktool / jadx): shipped inside the APK because
            // the guest often cannot reach GitHub at runtime. Staged into
            // files/codexweb/tools, which proot binds at /codexweb/tools.
            File tools = new File(web, "tools");
            tools.mkdirs();
            copyText(RuntimePayload.member(this, "codexweb/tools/apktool.jar"), new File(tools, "apktool.jar"));
            copyText(RuntimePayload.member(this, "codexweb/tools/jadx.jar"), new File(tools, "jadx.jar"));
            File rootfs = new File(files, RuntimePayload.ROOTFS_DIR);
            File home = new File(files, "home");
            File data = new File(files, "data");
            File run = new File(files, "run");
            File tmp = new File(files, "tmp");
            home.mkdirs();
            data.mkdirs();
            run.mkdirs();
            tmp.mkdirs();
            // the loader is written next to proot's temp files and must be
            // executable, which the app-private dir allows unprivileged
            File loader = new File(run, "loader");
            copyIfMissing(RuntimePayload.member(this, "bin/loader"), loader);
            copyIfMissing(RuntimePayload.member(this, "bin/proot"), new File(run, "proot"));
            if (!loader.canRead() || loader.length() == 0) {
                throw new IOException("loader 不可读 " + loader);
            }

            phase("准备 rootfs");
            Rootfs.prepare(this, rootfs, RuntimePayload.member(this, "rootfs.tar.gz"));

            // Drop any stale ready-state from a previous run so the UI cannot
            // open a page with an already-consumed token during restart.
            stateFile(this).delete();

            phase("启动 proot");
            // Reuse credentials across self-heals so an open WebView session and
            // any saved launcher URL keep working. New ones only on first boot.
            String[] saved = readCreds(this);
            if (saved == null) {
                token = randomToken();
                launchKey = randomToken();
                pin = String.format(Locale.US, "%04d", new java.security.SecureRandom().nextInt(10000));
                log("[boot] generated new credentials");
            } else {
                token = saved[0];
                pin = saved[1];
                launchKey = saved[2];
                log("[boot] reused saved credentials across restart");
            }
            writeCreds(this, token, pin, launchKey);
            List<String> argv = new ArrayList<>();
            argv.add(new File(run, "proot").getAbsolutePath());
            argv.add("-0");
            argv.add("-r");
            argv.add(rootfs.getAbsolutePath());
            argv.add("-w");
            argv.add("/root");
            argv.add("-b");
            argv.add("/proc");
            argv.add("-b");
            argv.add("/sys");
            argv.add("-b");
            argv.add("/dev");
            argv.add("-b");
            argv.add("/sdcard");
            argv.add("-b");
            argv.add(web.getAbsolutePath() + ":/codexweb");
            argv.add("-b");
            argv.add(home.getAbsolutePath() + ":/root/codex-web");
            argv.add("/bin/sh");
            argv.add("/codexweb/launch.sh");

            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.environment().put("HOME", "/root");
            pb.environment().put("PATH", "/usr/local/bin:/usr/bin:/bin");
            pb.environment().put("TERM", "dumb");
            pb.environment().put("LANG", "C.UTF-8");
            pb.environment().put("CX_PORT", "8123");
            pb.environment().put("CX_BIND", "0.0.0.0");
            pb.environment().put("PROOT_NO_SECCOMP", "1");
            pb.environment().put("PROOT_TMP_DIR", tmp.getAbsolutePath());
            pb.environment().put("PROOT_LOADER", loader.getAbsolutePath());
            pb.environment().put("CX_TOKEN", token);
            pb.environment().put("CX_PIN", pin);
            // Long-lived for this boot: launchers that cannot enter the PIN can
            // open a URL carrying cxkey. Rotates on every restart.
            pb.environment().put("CX_LAUNCH_KEY", launchKey);
            pb.environment().put("CX_LAN_IP", lanIp());
            pb.redirectErrorStream(true);
            proc = pb.start();
            mine = proc;
            log("[boot] proot 已启动");
            pump(proc.getInputStream(), token, pin, launchKey);
            int code = proc.waitFor();
            phase("后端已退出 (" + code + ")");
        } catch (Exception ex) {
            log("boot failed: " + ex);
            phase("启动失败: " + ex.getMessage());
        } finally {
            // The guard may already have launched a replacement while this
            // waitFor() was unwinding; never wipe the new process's state.
            if (mine != null && proc == mine) {
                starting = false;
                proc = null;
            }
        }
    }

    /** Byte copy: source files may be binary or larger than any text buffer. */
    private static void copyText(File src, File dst) throws IOException {
        if (dst.isFile() && dst.length() == src.length()) return;
        RuntimePayload.copyFile(src, dst);
    }

    private static void copyIfMissing(File src, File dst) throws IOException {
        if (dst.isFile() && dst.length() == src.length() && dst.canRead()) {
            dst.setExecutable(true, false);
            return;
        }
        RuntimePayload.copyFile(src, dst);
        dst.setExecutable(true, false);
    }

    private void pump(InputStream in, String token, String pin, String launchKey) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        boolean ready = false;
        while ((line = r.readLine()) != null) {
            if (!ready && line.startsWith(PREFIX)) {
                ready = true;
                // codexd prints this only after the listen socket is bound. Confirm
                // with a raw TCP connect (no HTTP request, so the one-shot token is
                // not consumed) before telling the UI it can open the page.
                if (awaitPort(8123, 8000)) {
                    String state = "{\"port\":8123,\"token\":\"" + token + "\",\"pin\":\"" + pin
                            + "\",\"launch_key\":\"" + launchKey
                            + "\",\"lan\":\"http://" + lanIp() + ":8123/\"}";
                    try {
                        writeText(stateFile(this), state);
                        getSystemService(NotificationManager.class)
                                .notify(NOTE_ID, notification("运行中 · PIN " + pin));
                        log("[boot] ready (port confirmed)");
                    } catch (IOException ex) {
                        log("state write failed: " + ex);
                    }
                    // Start the AI device-control bridge poller (loopback only).
                    DevicePump.start(this, launchKey);
                } else {
                    log("[boot] port 8123 did not accept connections");
                }
                continue;
            }
            log(" | " + line);
        }
    }

    /** Poll a loopback TCP port until it accepts a connection or timeout. */
    private static boolean awaitPort(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            java.net.Socket s = new java.net.Socket();
            try {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 400);
                return true;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (!n.startsWith("wlan") && !n.startsWith("eth")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    static String buildUrl(String state) {
        String k = extract(state, "launch_key");
        if (k != null) return "http://127.0.0.1:8123/?cxkey=" + k;
        String t = extract(state, "token");
        return t == null ? null : "http://127.0.0.1:8123/?token=" + t;
    }

    static void readState(File state, StateCb cb) {
        String text = "";
        if (state.isFile()) {
            try (InputStream in = new FileInputStream(state)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[2048];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                text = bo.toString("UTF-8");
            } catch (IOException ignored) {
            }
        }
        cb.onState(text);
    }

    static String extract(String json, String key) {
        if (json == null) return null;
        String k = "\"" + key + "\":\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int s = i + k.length();
        int e = json.indexOf('"', s);
        return e < 0 ? null : json.substring(s, e);
    }

    static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void writeText(File f, String text) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Background poller for the AI device-control bridge. The AI shell lives
     * in proot and cannot touch the host Android shell; instead it drops a
     * whitelisted action into the local backend, which this thread executes
     * through Shizuku (PhoneControlBridge) and writes the result back.
     * Loopback only, authenticated with the per-boot launch key.
     */
    private static final class DevicePump {
        private static final String TAG = "CXDevice";
        private final android.content.Context ctx;
        private final String key;
        private volatile boolean running = true;
        private Thread thread;

        private DevicePump(android.content.Context ctx, String key) {
            this.ctx = ctx.getApplicationContext();
            this.key = key == null ? "" : key;
        }

        static synchronized void start(android.content.Context ctx, String key) {
            if (devicePump != null) return;
            devicePump = new DevicePump(ctx, key);
            devicePump.thread = new Thread(devicePump::loop, "cx-device");
            devicePump.thread.setDaemon(true);
            devicePump.thread.start();
            android.util.Log.i(TAG, "device pump started");
        }

        static synchronized void stop() {
            DevicePump p = devicePump;
            if (p == null) return;
            p.running = false;
            devicePump = null;
        }

        private void loop() {
            while (running) {
                try {
                    String resp = post("/api/device/poll", "{}");
                    if (resp != null) {
                        org.json.JSONObject jo = new org.json.JSONObject(resp);
                        Object taskObj = jo.opt("task");
                        if (taskObj instanceof org.json.JSONObject) {
                            org.json.JSONObject task = (org.json.JSONObject) taskObj;
                            String id = task.optString("id", "");
                            String action = task.optString("action", "");
                            String arg = task.optString("arg", "");
                            if (!id.isEmpty()) {
                                String resultJson = PhoneControlBridge.execute(ctx, action, arg);
                                org.json.JSONObject result;
                                try {
                                    result = new org.json.JSONObject(resultJson);
                                } catch (Throwable malformed) {
                                    result = new org.json.JSONObject();
                                    try {
                                        result.put("ok", false);
                                        result.put("error", "执行结果解析失败");
                                    } catch (Throwable ignored) {}
                                }
                                org.json.JSONObject done = new org.json.JSONObject();
                                try {
                                    done.put("id", id);
                                    done.put("result", result);
                                } catch (Throwable ignored) {}
                                post("/api/device/finish", done.toString());
                            }
                            continue; // immediately look for the next task
                        }
                    }
                } catch (Throwable t) {
                    // backend not up yet / transient: back off quietly
                }
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        /** POST JSON to the loopback backend with the launch key; return body. */
        private String post(String path, String body) {
            java.net.HttpURLConnection c = null;
            try {
                java.net.URL u = new java.net.URL("http://127.0.0.1:8123" + path);
                c = (java.net.HttpURLConnection) u.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(1200);
                c.setReadTimeout(3000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("X-CX-Key", key);
                c.setRequestProperty("Connection", "close");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = c.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in == null) return null;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                return bo.toString("UTF-8");
            } catch (Throwable t) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }
}
