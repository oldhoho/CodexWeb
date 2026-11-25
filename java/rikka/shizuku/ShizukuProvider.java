package rikka.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import moe.shizuku.api.BinderContainer;

/**
 * Receives the Shizuku binder from the privileged Shizuku server at app start.
 * Declare in the manifest with authority com.aishell.codexweb.shizuku,
 * exported=true, multiprocess=false and a permission only shell can hold.
 */
public class ShizukuProvider extends ContentProvider {
    public static final String METHOD_SEND_BINDER = "sendBinder";
    public static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info.multiprocess) throw new IllegalStateException("multiprocess must be false");
        if (!info.exported) throw new IllegalStateException("exported must be true");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) return null;
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        if (METHOD_SEND_BINDER.equals(method)) {
            BinderContainer c = extras.getParcelable(EXTRA_BINDER);
            if (c != null && c.binder != null) {
                Shizuku.onBinderReceived(c.binder, getContext().getPackageName());
            }
        }
        return new Bundle();
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
