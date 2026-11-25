package com.aishell.codexweb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Extracts the Alpine rootfs once per archive (stamp-marked).
 *
 * Two passes: links and directories first, contents second, so symlink targets
 * exist before the entries that point at them and directory entries cannot be
 * clobbered by later file writes.
 */
final class Rootfs {
    private static final String STAMP = ".cx-stamp";

    static void prepare(android.content.Context ctx, File root, File tgz) throws IOException {
        File stamp = new File(ctx.getFilesDir(), ".rootfs-stamp");
        String want = RuntimePayload.VERSION + ":" + tgz.length() + ":" + tgz.lastModified();
        String have = stamp.isFile() ? RuntimePayload.readText(stamp).trim() : "";
        if (!want.equals(have) || !root.isDirectory()) {
            BackendService.log("[boot] 解压 rootfs");
            RuntimePayload.deleteTree(root);
            root.mkdirs();
            long t0 = System.currentTimeMillis();
            extract(tgz, root);
            fixLinks(root);
            mkdirs(root, "proc", "sys", "dev", "tmp", "sdcard", "opt/codex");
            RuntimePayload.writeText(stamp, want);
            BackendService.log("[boot] rootfs 完成 " + (System.currentTimeMillis() - t0) + "ms");
        }
        mkdirs(root, "root/.codex", "root/codex-web", "opt/codex");
        writeIfChanged(new File(root, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost\n");
        writeIfChanged(new File(root, "etc/resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        writeIfChanged(new File(root, "etc/profile.d/cx.sh"),
                "export PATH=/usr/local/bin:/usr/bin:/bin\nexport LANG=C.UTF-8\nexport TERM=xterm-256color\n");
        new File(root, "etc/mtab").delete();
    }

    /** Absolute links only make sense relative to the guest root; rewrite them. */
    private static void fixLinks(File root) {
        link(root, "bin/sh", "busybox");
        link(root, "bin/ash", "busybox");
        link(root, "bin/cat", "busybox");
        link(root, "bin/ls", "busybox");
        link(root, "bin/cp", "busybox");
        link(root, "bin/rm", "busybox");
        link(root, "bin/mkdir", "busybox");
        link(root, "bin/sed", "busybox");
        link(root, "bin/grep", "busybox");
        link(root, "bin/tar", "busybox");
        link(root, "usr/bin/python", "python3");
        link(root, "usr/bin/python3", "python3.12");
        link(root, "var/run", "../../run");
        File lib = new File(root, "lib");
        File[] z = lib.listFiles((d, n) -> n.startsWith("libz.so.1."));
        if (z != null && z.length == 1) link(root, "lib/libz.so.1", z[0].getName());
        link(root, "usr/bin/zcat", "../../bin/zcat");
        link(root, "usr/bin/gunzip", "../../bin/gunzip");
        link(root, "usr/bin/awk", "../../bin/awk");
    }

    private static void link(File root, String rel, String target) {
        File f = new File(root, rel);
        if (f.exists()) return;
        // The tar already restored these applets as real symlinks, e.g.
        // bin/sh -> /bin/busybox. On the Android host that absolute target is
        // "dangling", so f.exists() is false even though the link is fine —
        // proot resolves it inside the guest root at runtime. Treat an
        // existing symlink as already correct and do NOT copy over it: that
        // copy fails on the dangling target and produced the misleading
        // "[rootfs] 无法修补" warning on every first extraction.
        try {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return;
        } catch (Exception ignored) {
        }
        f.getParentFile().mkdirs();
        try {
            java.nio.file.Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(target));
        } catch (IOException | UnsupportedOperationException ex) {
            if (target.endsWith("busybox")) {
                try {
                    java.nio.file.Files.copy(new File(root, "bin/busybox").toPath(), f.toPath());
                    f.setExecutable(true, false);
                } catch (IOException ignored) {
                    BackendService.log("[rootfs] 无法修补 " + rel);
                }
            }
        }
    }

    private static void mkdirs(File root, String... rels) {
        for (String r : rels) new File(root, r).mkdirs();
    }

    private static void writeIfChanged(File f, String text) throws IOException {
        if (f.isFile() && text.equals(RuntimePayload.readText(f))) return;
        f.getParentFile().mkdirs();
        RuntimePayload.writeText(f, text);
    }

    private static void extract(File tgz, File root) throws IOException {
        for (int pass = 0; pass < 2; pass++) {
            Tar tar = new Tar(new GZIPInputStream(new FileInputStream(tgz), 1 << 16), root, pass);
            try {
                tar.run();
            } finally {
                tar.close();
            }
        }
    }

    private static final class Tar {
        private final InputStream in;
        private final File root;
        private final int pass;
        private final byte[] block = new byte[512];
        private final byte[] buf = new byte[1 << 16];
        private String longName;
        private String longLink;
        private long files;

        Tar(InputStream in, File root, int pass) {
            this.in = in;
            this.root = root;
            this.pass = pass;
        }

        void run() throws IOException {
            while (true) {
                if (!readBlock()) return;
                if (isZero(block)) {
                    if (!readBlock() || isZero(block)) return;
                }
                char type = (char) block[156];
                long size = octal(block, 124, 12);
                if (type == 'L' || type == 'K') {
                    byte[] data = new byte[(int) size];
                    readFully(data, (int) size);
                    skip(pad(size));
                    String s = new String(data, 0, data.length, StandardCharsets.UTF_8);
                    if (type == 'L') longName = s;
                    else longLink = s;
                    continue;
                }
                if (type == 'x' || type == 'g') {
                    byte[] data = new byte[(int) size];
                    readFully(data, (int) size);
                    skip(pad(size));
                    if (type == 'x') pax(data);
                    continue;
                }
                String name = longName != null ? longName : str(block, 0, 100);
                String prefix = str(block, 345, 155);
                if (longName == null && !prefix.isEmpty()) name = prefix + "/" + name;
                String link = longLink != null ? longLink : str(block, 157, 100);
                longName = null;
                longLink = null;

                File dst = safe(name);
                if (pass == 0) {
                    if (dst != null) {
                        switch (type) {
                            case '5':
                                dst.mkdirs();
                                break;
                            case '2':
                                // Restore the real symlink from the tar. The target
                                // need not exist yet; createSymbolicLink stores it as-is.
                                // Fall back to a copy of the target file when the
                                // filesystem refuses symlinks (some FUSE builds).
                                dst.getParentFile().mkdirs();
                                symlink(link, dst);
                                break;
                            case '1':
                                dst.getParentFile().mkdirs();
                                dst.delete();
                                hardlink(link, dst);
                                break;
                            default:
                                dst.getParentFile().mkdirs();
                                break;
                        }
                    }
                    skip(size + pad(size));
                } else if ((type == '0' || type == '\0' || type == '7') && dst != null) {
                    writeFile(dst, size, octal(block, 100, 8));
                    skip(pad(size));
                } else {
                    skip(size + pad(size));
                }
            }
        }

        private void pax(byte[] data) {
            String s = new String(data, StandardCharsets.UTF_8);
            int i = 0;
            while (i < s.length()) {
                int sp = s.indexOf(' ', i);
                if (sp < 0) break;
                int len;
                try {
                    len = Integer.parseInt(s.substring(i, sp));
                } catch (NumberFormatException e) {
                    break;
                }
                if (len <= 0 || i + len > s.length()) break;
                String rec = s.substring(sp + 1, i + len - 1);
                int eq = rec.indexOf('=');
                if (eq > 0) {
                    String k = rec.substring(0, eq);
                    String v = rec.substring(eq + 1);
                    if ("path".equals(k)) longName = v;
                    if ("linkpath".equals(k)) longLink = v;
                }
                i += len;
            }
        }


        /** Create a tar symlink; if the target is an in-root file, copy it as a
         *  fallback so busybox/python still work on filesystems without symlink
         *  support. Absolute targets are interpreted relative to the guest root. */
        private void symlink(String target, File dst) {
            if (target == null || target.isEmpty()) return;
            try {
                dst.delete();
                java.nio.file.Files.createSymbolicLink(dst.toPath(), java.nio.file.Paths.get(target));
                return;
            } catch (IOException | UnsupportedOperationException ex) {
                // fall through to copy fallback
            }
            File src = null;
            try {
                src = safe(target.startsWith("/") ? target.substring(1) : target);
                if (src == null && dst.getParentFile() != null) {
                    String rel = new File(dst.getParentFile(), target).getCanonicalPath();
                    File candidate = new File(rel);
                    if (candidate.getCanonicalPath().startsWith(root.getCanonicalPath())) src = candidate;
                }
            } catch (IOException ignored) {
            }
            if (src != null && src.isFile()) {
                try {
                    copy(src, dst);
                    dst.setExecutable(true, false);
                    return;
                } catch (IOException ignored) {
                }
            }
            BackendService.log("[rootfs] 符号链接失败 " + dst.getName() + " -> " + target);
        }

        private void hardlink(String target, File dst) {
            File src;
            try {
                src = safe(target);
            } catch (IOException ex) {
                src = null;
            }
            if (src != null && src.isFile()) {
                try {
                    java.nio.file.Files.createLink(dst.toPath(), src.toPath());
                    return;
                } catch (IOException | UnsupportedOperationException ex) {
                    try {
                        copy(src, dst);
                        return;
                    } catch (IOException ignored) {
                        // fall through to the log line below
                    }
                }
            }
            BackendService.log("[rootfs] 硬链接失败 " + dst.getName());
        }

        private void copy(File src, File dst) throws IOException {
            try (InputStream i = new FileInputStream(src); OutputStream o = new FileOutputStream(dst)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = i.read(b)) > 0) o.write(b, 0, n);
            }
        }

        private File safe(String name) throws IOException {
            String rel = name.startsWith("./") ? name.substring(2) : name;
            if (rel.isEmpty() || rel.equals(".")) return null;
            File f = new File(root, rel);
            if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) return null;
            return f;
        }

        private void writeFile(File dst, long size, long mode) throws IOException {
            if (dst.isDirectory() || java.nio.file.Files.isSymbolicLink(dst.toPath())) {
                skip(size);
                return;
            }
            dst.getParentFile().mkdirs();
            try (OutputStream o = new FileOutputStream(dst)) {
                long left = size;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("truncated tar");
                    o.write(buf, 0, n);
                    left -= n;
                }
            }
            if ((mode & 0100) != 0) dst.setExecutable(true, false);
            dst.setReadable(true, false);
            files++;
            if (files % 3000 == 0) BackendService.log("[boot] rootfs " + files + " 个文件");
        }

        private void skip(long n) throws IOException {
            long left = n;
            while (left > 0) {
                int k = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (k < 0) throw new IOException("truncated tar");
                left -= k;
            }
        }

        private static long pad(long size) {
            return (512 - (size % 512)) % 512;
        }

        private void readFully(byte[] dst, int n) throws IOException {
            int got = 0;
            while (got < n) {
                int k = in.read(dst, got, n - got);
                if (k < 0) throw new IOException("truncated tar entry");
                got += k;
            }
        }

        private boolean readBlock() throws IOException {
            int off = 0;
            while (off < 512) {
                int k = in.read(block, off, 512 - off);
                if (k < 0) return false;
                off += k;
            }
            return true;
        }

        private static boolean isZero(byte[] b) {
            for (byte x : b) if (x != 0) return false;
            return true;
        }

        private static String str(byte[] b, int off, int len) {
            int end = off;
            while (end < off + len && b[end] != 0) end++;
            return new String(b, off, end - off, StandardCharsets.UTF_8);
        }

        private static long octal(byte[] b, int off, int len) {
            if ((b[off] & 0x80) != 0) {
                long v = b[off] & 0x7f;
                for (int i = off + 1; i < off + len; i++) v = (v << 8) | (b[i] & 0xff);
                return v;
            }
            long v = 0;
            for (int i = off; i < off + len; i++) {
                byte c = b[i];
                if (c == 0 || c == ' ') {
                    if (v != 0) break;
                    continue;
                }
                if (c < '0' || c > '7') break;
                v = v * 8 + (c - '0');
            }
            return v;
        }

        void close() throws IOException {
            in.close();
        }
    }
}
