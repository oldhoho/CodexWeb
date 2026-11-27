package com.aishell.codexweb;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stages the runtime into files/payload from assets/payload/<name>, refreshing
 * it whenever VERSION changes.
 * Layout inside the archive:
 *   rootfs.tar.gz  bin/{proot,loader,loader-m32,proot-loader}
 */
final class RuntimePayload {
    static final String ROOTFS_DIR = "rootfs";
    private static final String MARK = ".payload-version";
    private static volatile String cached;

    static File root(Context ctx) {
        return payloadDir(ctx);
    }

    /** Resolves a logical payload name (e.g. "bin/proot") inside files/payload. */
    static File member(Context ctx, String name) {
        return new File(payloadDir(ctx), flat(name));
    }

    static String ensure(Context ctx) throws IOException {
        if (cached != null) return cached;
        synchronized (RuntimePayload.class) {
            if (cached != null) return cached;
            BackendService.log("[boot] 解包运行时资源");
            File dir;
            try {
                dir = stage(ctx);
            } catch (Throwable t) {
                throw new IOException(stageSummary(t), t);
            }
            cached = dir.getAbsolutePath();
            return cached;
        }
    }

    private static final String PAYLOAD = "payload";
    // Bump when payload assets change: it invalidates files/payload and the
    // extracted rootfs stamp so old copies are never reused.
    // Keep in sync with src/version (the build copies that file into the APK).
    static final String VERSION = "81";
    static String flat(String name) {
        return name.replace('/', '_');
    }

    private static final String[] LAYOUT = {
            "VERSION", "rootfs.tar.gz", "bin/proot", "bin/loader", "bin/loader-m32",
            "bin/proot-loader", "codexweb/codexd.py", "codexweb/launch.sh",
            "codexweb/ui/index.html",
            "codexweb/tools/apktool.jar", "codexweb/tools/jadx.jar",
    };

    /** Files copied straight out of assets/payload/ (flat, no nesting). */
    static File payloadDir(Context ctx) {
        return new File(ctx.getFilesDir(), "payload");
    }

    private static void stageFiles(Context ctx, File out) throws IOException {
        if (dirMatches(out)) {
            BackendService.log("[boot] 运行时已解包，跳过");
            return;
        }
        deleteTree(out);
        mustMkdirs(out);
        // Flat layout: Android's FUSE layer was dropping files written into a
        // freshly created subdirectory ("bin/"), so members live side by side
        // and only the names keep their original grouping.
        for (String name : LAYOUT) {
            File dst = new File(out, flat(name));
            long t0 = System.currentTimeMillis();
            long written = writeAsset(ctx, name, dst);
            if (name.startsWith("bin/")) dst.setExecutable(true, false);
            BackendService.log(String.format(Locale.US, "[boot] %s %d KB %d ms",
                    name, written / 1024, System.currentTimeMillis() - t0));
            if (name.equals("VERSION")) {
                writeText(new File(out, MARK), VERSION);
            }
        }
    }

    /**
     * Android's FUSE storage can publish directories and files with a delay,
     * so every step is made visible to the *parent* entry before moving on:
     * mkdir until the directory is listable, then create the file and verify
     * it by opening it again.
     */
    private static long writeAsset(Context ctx, String name, File dst) throws IOException {
        mustMkdirs(dst.getParentFile());
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            long written = 0;
            try {
                java.io.FileOutputStream fo = new java.io.FileOutputStream(dst);
                try (InputStream in = ctx.getAssets().open(PAYLOAD + "/" + name)) {
                    byte[] buf = new byte[262144];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        written += n;
                    }
                } finally {
                    fo.flush();
                    fo.getFD().sync();
                    fo.close();
                }
                if (visible(dst, written)) return written;
                last = new IOException("写入后不可见 " + name + " " + written);
            } catch (IOException ex) {
                last = ex;
            }
            BackendService.log("[boot] 重写 " + name + " 第 " + attempt + " 次: " + last.getMessage());
            try {
                Thread.sleep(120L * attempt);
            } catch (InterruptedException e) {
                throw new IOException("interrupted", e);
            }
            // a half-published entry has to be recreated, not appended to
            dst.delete();
        }
        throw new IOException("无法写入 " + name + ": " + last, last);
    }

    /** Version mark plus every member present and non-empty. */
    private static boolean dirMatches(File out) {
        if (!VERSION.equals(readText(new File(out, MARK)).trim())) return false;
        for (String name : LAYOUT) {
            File f = new File(out, flat(name));
            if (!f.isFile() || f.length() == 0) return false;
        }
        return true;
    }

    private static void mustMkdirs(File dir) throws IOException {
        if (dir.isDirectory()) return;
        for (int i = 0; i < 40; i++) {
            dir.mkdirs();
            if (dir.isDirectory()) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("无法创建目录 " + dir);
    }

    private static boolean visible(File f, long expect) {
        for (int i = 0; i < 40; i++) {
            if (f.length() == expect) {
                try (InputStream probe = new java.io.FileInputStream(f)) {
                    if (probe.read() >= 0) return true;
                } catch (IOException ignored) {
                    // retry: FUSE has not published it yet
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * All layout files must exist before anything else runs: a partially
     * restored runtime used to fail later with a bare ENOENT.
     */
    private static File stage(Context ctx) throws IOException {
        File dir = payloadDir(ctx);
        stageFiles(ctx, dir);
        for (String name : LAYOUT) {
            File f = new File(dir, flat(name));
            if (!visible(f, f.length()) || f.length() == 0) {
                File p = f.getParentFile();
                throw new IOException("运行时缺失 " + name + " len=" + f.length()
                        + " 父目录=" + p + " 内容=" + java.util.Arrays.toString(p.listFiles()));
            }
        }
        BackendService.log("[boot] 运行时就绪 " + dir.getAbsolutePath()
                + " free=" + dir.getUsableSpace() / 1048576 + "MB");
        return dir;
    }

    private static String stageSummary(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(c.getClass().getSimpleName()).append(':').append(c.getMessage());
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }

    static String readText(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (IOException ex) {
            return "";
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static void writeText(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(text.getBytes(StandardCharsets.UTF_8));
            o.flush();
            o.getFD().sync();
        } finally {
            o.close();
        }
    }

    static void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(dst);
        try (InputStream in = new java.io.FileInputStream(src)) {
            byte[] buf = new byte[262144];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            o.flush();
            o.getFD().sync();
        } finally {
            o.close();
        }
    }

    static void writeExecutable(File f, String text) throws IOException {
        writeText(f, text);
        f.setExecutable(true, false);
    }

    static List<String> files(File dir) {
        List<String> out = new ArrayList<>();
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) if (k.isFile()) out.add(k.getName());
        return out;
    }
}
