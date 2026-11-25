package rikka.shizuku;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;

import static rikka.shizuku.ShizukuApiConstants.*;

/**
 * Embedded minimal Shizuku client. Covers exactly what CodexWeb needs:
 * binder attach, permission check/request, and privileged newProcess().
 * No androidx / Gradle dependency.
 */
public final class Shizuku {
    private static IBinder binder;
    private static IShizukuService service;
    private static boolean permissionGranted;
    // Diagnostics so the UI can explain exactly why a request did nothing.
    private static String lastError = "";
    private static boolean attachOk = false;
    private static int serverVersion = -1;
    private static int selfUid = -1;
    private static String attachPackage = null;

    public interface OnRequestPermissionResultListener {
        void onRequestPermissionResult(int requestCode, int grantResult);
    }

    private static final List<OnRequestPermissionResultListener> LISTENERS =
            new ArrayList<OnRequestPermissionResultListener>();

    private static final IShizukuApplication APP = new IShizukuApplication.Stub() {
        @Override
        public void bindApplication(Bundle data) {
            permissionGranted = data != null && data.getBoolean(
                    BIND_APPLICATION_PERMISSION_GRANTED, false);
            if (data != null) {
                serverVersion = data.getInt(BIND_APPLICATION_SERVER_VERSION, -1);
            }
        }

        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            boolean allowed = data != null && data.getBoolean(
                    REQUEST_PERMISSION_REPLY_ALLOWED, false);
            permissionGranted = allowed;
            int result = allowed ? PackageManager.PERMISSION_GRANTED
                                 : PackageManager.PERMISSION_DENIED;
            synchronized (LISTENERS) {
                for (OnRequestPermissionResultListener l : LISTENERS) {
                    l.onRequestPermissionResult(requestCode, result);
                }
            }
        }

        @Override
        public void showPermissionConfirmation(int uid, int pid, String pkg, int code) {
            // Only used by Sui; no-op for plain Shizuku.
        }
    };

    private Shizuku() {}

    public static void addRequestPermissionResultListener(OnRequestPermissionResultListener l) {
        synchronized (LISTENERS) {
            if (!LISTENERS.contains(l)) LISTENERS.add(l);
        }
    }

    public static void onBinderReceived(IBinder newBinder, String packageName) {
        if (newBinder == null) {
            binder = null;
            service = null;
            permissionGranted = false;
            attachOk = false;
            lastError = "binder is null";
            return;
        }
        if (binder == newBinder && attachOk) return;
        binder = newBinder;
        attachPackage = packageName;
        service = IShizukuService.Stub.asInterface(newBinder);
        attachOk = false;
        lastError = "";
        try {
            binder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    binder = null;
                    service = null;
                    permissionGranted = false;
                    attachOk = false;
                    lastError = "Shizuku service died";
                }
            }, 0);
        } catch (RemoteException ignored) {
        }
        // Attach must succeed before the server will list this app or accept a
        // permission request. Try V13 first, then fall back to V11. Each attempt
        // is isolated so one failing transaction cannot suppress the other.
        Throwable v13err = null;
        boolean attached = false;
        try {
            attached = attachV13(newBinder, packageName);
        } catch (Throwable t) {
            v13err = t;
        }
        if (!attached) {
            try {
                attached = attachV11(newBinder, packageName);
            } catch (Throwable t) {
                lastError = "attach V11 failed: " + t;
            }
            if (!attached && v13err != null) {
                lastError = "attach V13: " + v13err + " | V11 returned false";
            }
        }
        attachOk = attached;
        if (attached) lastError = "";
    }

    private static boolean attachV13(IBinder b, String packageName) throws RemoteException {
        Bundle args = new Bundle();
        args.putInt(ATTACH_APPLICATION_API_VERSION, SERVER_VERSION);
        args.putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(BINDER_DESCRIPTOR);
            data.writeStrongBinder(APP.asBinder());
            data.writeInt(1);
            args.writeToParcel(data, 0);
            boolean ok = b.transact(18, data, reply, 0);
            reply.readException();
            return ok;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean attachV11(IBinder b, String packageName) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(BINDER_DESCRIPTOR);
            data.writeStrongBinder(APP.asBinder());
            data.writeString(packageName);
            boolean ok = b.transact(14, data, reply, 0);
            reply.readException();
            return ok;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static boolean pingBinder() {
        return binder != null && binder.pingBinder();
    }

    public static boolean isAttached() {
        return attachOk && pingBinder();
    }

    /** Retry the attach handshake; useful when the server came up after us. */
    public static void retryAttach() {
        if (!pingBinder() || attachOk || attachPackage == null) return;
        boolean attached = false;
        try {
            attached = attachV13(binder, attachPackage);
        } catch (Throwable ignored) {
        }
        if (!attached) {
            try {
                attached = attachV11(binder, attachPackage);
            } catch (Throwable ignored) {
            }
        }
        attachOk = attached;
        lastError = attached ? "" : "attach retry failed";
    }

    public static int serverVersion() {
        return serverVersion;
    }

    /** Human-readable reason why attach/permission is not ready, or empty. */
    public static String lastError() {
        return lastError == null ? "" : lastError;
    }

    public static int checkSelfPermission() {
        if (!pingBinder()) {
            lastError = "Shizuku binder not connected";
            return PackageManager.PERMISSION_DENIED;
        }
        if (permissionGranted) return PackageManager.PERMISSION_GRANTED;
        try {
            permissionGranted = service.checkSelfPermission();
        } catch (Throwable t) {
            lastError = "checkSelfPermission: " + t;
        }
        return permissionGranted ? PackageManager.PERMISSION_GRANTED
                                 : PackageManager.PERMISSION_DENIED;
    }

    /**
     * Returns true only when the request actually reached the Shizuku server.
     * Never fail silently: the caller surfaces lastError() to the user.
     */
    public static boolean requestPermission(int requestCode) {
        if (!pingBinder()) {
            lastError = "无法请求：Shizuku 服务未连接（请先在 Shizuku App 中启动服务）";
            return false;
        }
        if (!attachOk) {
            lastError = "无法请求：与 Shizuku 的握手(attach)未完成"
                    + (lastError.isEmpty() ? "" : " · " + lastError);
            return false;
        }
        try {
            service.requestPermission(requestCode);
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = "请求失败：" + t;
            return false;
        }
    }

    /** Run a privileged process through Shizuku. Caller must close streams. */
    public static ShizukuProcess newProcess(String[] cmd, String[] env, String dir) throws RemoteException {
        if (!pingBinder()) throw new RemoteException("Shizuku binder unavailable");
        IRemoteProcess p = service.newProcess(cmd, env, dir);
        if (p == null) throw new RemoteException("newProcess returned null");
        return new ShizukuProcess(p);
    }

    public static final class ShizukuProcess {
        private final IRemoteProcess remote;
        public final InputStream stdout;
        public final InputStream stderr;
        public final OutputStream stdin;

        ShizukuProcess(IRemoteProcess p) throws RemoteException {
            remote = p;
            stdout = stream(p.getInputStream());
            stderr = stream(p.getErrorStream());
            stdin = streamOut(p.getOutputStream());
        }

        private static InputStream stream(ParcelFileDescriptor fd) {
            return fd == null ? null : new FileInputStream(fd.getFileDescriptor());
        }

        private static OutputStream streamOut(ParcelFileDescriptor fd) {
            return fd == null ? null : new FileOutputStream(fd.getFileDescriptor());
        }

        public int waitFor() throws RemoteException {
            return remote.waitFor();
        }

        public void destroy() {
            try { remote.destroy(); } catch (Throwable ignored) {}
        }

        public void closeQuietly() {
            close(stdout); close(stderr); close(stdin); destroy();
        }

        private static void close(java.io.Closeable c) {
            if (c == null) return;
            try { c.close(); } catch (IOException ignored) {}
        }
    }
}
