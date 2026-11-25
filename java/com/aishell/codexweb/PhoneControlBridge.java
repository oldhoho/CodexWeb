package com.aishell.codexweb;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import rikka.shizuku.Shizuku;

/**
 * JS bridge for the on-device "phone control" tool. All commands are curated
 * by action id -- the web page never passes raw shell to this bridge.
 */
public class PhoneControlBridge {
    private static final String TAG = "CXPhone";
    private final Context ctx;

    public PhoneControlBridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private static boolean sh(String s) {
        return s.matches("[A-Za-z0-9._:/=+@-]+");
    }

    private static String q(String s) {
        return "'" + String.valueOf(s).replace("'", "'\\''") + "'";
    }

    @android.webkit.JavascriptInterface
    public String status() {
        JSONObject o = new JSONObject();
        try {
            boolean installed = false;
            try {
                ctx.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
                installed = true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            boolean binder = Shizuku.pingBinder();
            boolean attached = Shizuku.isAttached();
            boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            o.put("installed", installed);
            o.put("binder", binder);
            o.put("attached", attached);
            o.put("serverVersion", Shizuku.serverVersion());
            o.put("granted", granted);
            o.put("ok", installed && binder && attached && granted);
            String err = Shizuku.lastError();
            if (err != null && !err.isEmpty()) o.put("error", err);
        } catch (Throwable t) {
            try { o.put("error", String.valueOf(t)); } catch (Throwable ignored) {}
        }
        return o.toString();
    }

    /** Request permission and report whether it actually reached the server. */
    @android.webkit.JavascriptInterface
    public String request() {
        JSONObject o = new JSONObject();
        try {
            boolean sent = Shizuku.requestPermission(1);
            o.put("sent", sent);
            String err = Shizuku.lastError();
            if (err != null && !err.isEmpty()) o.put("error", err);
        } catch (Throwable t) {
            Log.w(TAG, "request", t);
            try { o.put("sent", false); o.put("error", String.valueOf(t)); }
            catch (Throwable ignored) {}
        }
        return o.toString();
    }

    /** Launch the Shizuku app so the user can start the service / review grants. */
    @android.webkit.JavascriptInterface
    public String openShizuku() {
        JSONObject o = new JSONObject();
        android.content.Intent intent = ctx.getPackageManager()
                .getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (intent == null) {
            try { o.put("ok", false); o.put("error", "未安装 Shizuku"); } catch (Throwable ignored) {}
            return o.toString();
        }
        try {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            o.put("ok", true);
        } catch (Throwable t) {
            try { o.put("ok", false); o.put("error", String.valueOf(t)); } catch (Throwable ignored) {}
        }
        return o.toString();
    }

    /** Refresh the permission state from the server (result also arrives via callback). */
    @android.webkit.JavascriptInterface
    public String refreshPermission() {
        Shizuku.retryAttach();
        Shizuku.checkSelfPermission();
        return status();
    }

    @android.webkit.JavascriptInterface
    public String run(String action, String arg) {
        return execute(ctx, action, arg);
    }

    /**
     * Execute a whitelisted action from the AI device bridge. Static so the
     * backend polling thread can call it without a WebView. Uses the exact
     * same curated command builder as the JS bridge -- no raw shell passes.
     * Returns a JSON object string: ok/cmd/exit/out/err or ok=false/error.
     */
    public static String execute(Context app, String action, String arg) {
        JSONObject o = new JSONObject();
        String cmd = build(action, arg == null ? "" : arg.trim());
        if (cmd == null) return fail(o, "不支持的操作：" + action);
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return fail(o, "Shizuku 尚未授权，请先在「手机控制」页请求授权");
        Shizuku.ShizukuProcess p = null;
        try {
            p = Shizuku.newProcess(new String[]{"/system/bin/sh", "-c", cmd}, null, null);
            if (p.stdin != null) p.stdin.close();
            String out = read(p.stdout);
            String err = read(p.stderr);
            int code = p.waitFor();
            o.put("ok", true);
            o.put("cmd", cmd);
            o.put("exit", code);
            o.put("out", out == null ? "" : out.trim());
            o.put("err", err == null ? "" : err.trim());
            return o.toString();
        } catch (Throwable t) {
            return fail(o, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (p != null) p.closeQuietly();
        }
    }

    private static String fail(JSONObject o, String msg) {
        try {
            o.put("ok", false); o.put("error", msg);
        } catch (Throwable ignored) {}
        return o.toString();
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (b.size() < 65536) b.write(buf, 0, Math.min(n, 65536 - b.size()));
        }
        return b.toString("UTF-8");
    }

    /** Whitelist of curated commands. Returns null when action is unknown. */
    private static String build(String action, String a) {
        if (action == null) return null;
        if (action.equals("tap")) {
            String[] v = a.split("\\s+");
            if (v.length != 2 || !isInt(v[0]) || !isInt(v[1])) return null;
            return "input tap " + v[0] + " " + v[1];
        }
        if (action.equals("swipe")) {
            String[] v = a.split("\\s+");
            if (v.length < 4 || v.length > 5) return null;
            for (String x : v) if (!isInt(x)) return null;
            StringBuilder sb = new StringBuilder("input swipe");
            for (String x : v) sb.append(' ').append(x);
            return sb.toString();
        }
        if (action.equals("text")) {
            if (a.isEmpty()) return null;
            return "input text " + q(a);
        }
        if (action.equals("key")) {
            String[] KEYS = {"HOME:3", "BACK:4", "RECENTS:187", "POWER:26",
                    "VOLUP:24", "VOLDOWN:25", "MUTE:0", "ENTER:66",
                    "DEL:67", "CAMERA:27", "PLAY:85", "PAUSE:85"};
            for (String k : KEYS) {
                String[] p = k.split(":");
                if (p[0].equalsIgnoreCase(a)) return "input keyevent " + p[1];
            }
            if (isInt(a)) return "input keyevent " + a;
            return null;
        }
        if (action.equals("openapp")) {
            if (!a.matches("[A-Za-z0-9_.]+")) return null;
            return "monkey -p " + q(a) + " -c android.intent.category.LAUNCHER 1";
        }
        if (action.equals("appsearch")) {
            // Curated "open an app's search page". The web side may only name
            // a known alias (or a package) plus a keyword -- never a raw `am`
            // command. Known apps use their official deep link; anything else
            // uses the standard ACTION_SEARCH intent with the keyword extra.
            String[] v = a.split("\\s+", 2);
            if (v.length != 2 || v[1].isEmpty() || v[1].length() > 200) return null;
            String app = v[0], kw = v[1];
            String pkg = null, tpl = null;
            String key = app.toLowerCase(java.util.Locale.ROOT);
            if (key.equals("bili") || key.equals("bilibili")
                    || key.equals("b站") || key.equals("哔哩哔哩")) {
                pkg = "tv.danmaku.bili"; tpl = "bilibili://search?keyword=%s";
            } else if (key.equals("douyin") || key.equals("抖音")) {
                pkg = "com.ss.android.ugc.aweme"; tpl = "snssdk1128://search?keyword=%s";
            } else if (key.equals("taobao") || key.equals("淘宝")) {
                pkg = "com.taobao.taobao"; tpl = "taobao://s?keyword=%s";
            } else if (key.equals("zhihu") || key.equals("知乎")) {
                pkg = "com.zhihu.android"; tpl = "zhihu://search?q=%s";
            } else if (key.equals("weibo") || key.equals("微博")) {
                pkg = "com.sina.weibo"; tpl = "sinaweibo://searchall?q=%s";
            } else if (key.equals("netease") || key.equals("网易云音乐")) {
                pkg = "com.netease.cloudmusic"; tpl = "orpheus://search?keyword=%s";
            } else if (key.equals("jd") || key.equals("京东")
                    || key.equals("baidu") || key.equals("百度")) {
                // These do not expose a stable search deep link; fall through
                // to the package-specific ACTION_SEARCH below.
                pkg = (key.equals("jd") || key.equals("京东"))
                        ? "com.jingdong.app.mall" : "com.baidu.searchbox";
            } else if (app.matches("[A-Za-z0-9_.]{1,120}")) {
                pkg = app;
            } else {
                return null;
            }
            if (tpl != null) {
                String enc;
                try {
                    enc = java.net.URLEncoder.encode(kw, "UTF-8");
                } catch (Exception e) {
                    return null;
                }
                String uri = String.format(java.util.Locale.ROOT, tpl, enc);
                return "am start -a android.intent.action.VIEW -d " + q(uri)
                        + " -p " + q(pkg);
            }
            return "am start -a android.intent.action.SEARCH -p " + q(pkg)
                    + " --es android.intent.extra.TEXT " + q(kw);
        }
        if (action.equals("openurl")) {
            if (!a.matches("^https?://[^\\s]+$")) return null;
            return "am start -a android.intent.action.VIEW -d " + q(a);
        }
        if (action.equals("dial")) {
            if (!a.matches("[0-9+*#\\- ]+")) return null;
            return "am start -a android.intent.action.DIAL -d " + q("tel:" + a.replace(" ", ""));
        }
        if (action.equals("wifi") || action.equals("bluetooth")) {
            if (!a.equals("on") && !a.equals("off")) return null;
            return "svc " + action + " " + a;
        }
        if (action.equals("airplane")) {
            if (!a.equals("on") && !a.equals("off")) return null;
            String v = a.equals("on") ? "1" : "0";
            String b = a.equals("on") ? "true" : "false";
            return "settings put global airplane_mode_on " + v
                    + "; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + b;
        }
        if (action.equals("brightness")) {
            if (!isInt(a)) return null;
            int n = Integer.parseInt(a);
            if (n < 1 || n > 255) return null;
            return "settings put system screen_brightness " + n;
        }
        if (action.equals("timeout")) {
            if (a.equals("never")) return "settings put system screen_off_timeout 2147483647";
            if (!isInt(a)) return null;
            return "settings put system screen_off_timeout " + Integer.parseInt(a);
        }
        if (action.equals("screenshot")) {
            return "screencap -p /sdcard/Pictures/cxweb-shot.png";
        }
        return null;
    }

    private static boolean isInt(String s) {
        try { Integer.parseInt(s); return true; } catch (Exception e) { return false; }
    }
}
