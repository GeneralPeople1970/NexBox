package com.nexbox.core.fake.service.context.providers;

import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;

import com.nexbox.core.fake.device.DeviceSpoofManager;
import com.nexbox.core.fake.device.OaidValue;

/** Existing Vivo/Meizu/Nubia providers only; does not synthesize missing OEM services. */
public final class OaidProviderStub extends SystemProviderStub {
    private final String authority;

    public OaidProviderStub(String authority) {
        this.authority = authority;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object result = super.invoke(proxy, method, args);
        if (!DeviceSpoofManager.isCurrentProcessEnabled()) return result;
        if ("call".equals(method.getName()) && "cn.nubia.identity".equals(authority)
                && args != null && args.length >= 3 && "getOAID".equals(args[args.length - 3])
                && result instanceof Bundle) {
            Bundle original = (Bundle) result;
            if (original.getInt("code", -1) == 0) {
                Bundle copy = new Bundle(original);
                copy.putString("id", OaidValue.replace(original.getString("id")));
                return copy;
            }
        }
        if ("query".equals(method.getName()) && result instanceof Cursor && isOaidQuery(args)) {
            return new OaidCursor((Cursor) result);
        }
        return result;
    }

    private boolean isOaidQuery(Object[] args) {
        if (args == null) return false;
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof Uri)) continue;
            Uri uri = (Uri) args[i];
            if (!authority.equals(uri.getAuthority())) return false;
            if ("com.vivo.vms.IdProvider".equals(authority)) return "/IdentifierId/OAID".equals(uri.getPath());
            if ("com.meizu.flyme.openidsdk".equals(authority)) {
                // Modern IContentProvider.query uses a Bundle; older versions use selectionArgs.
                for (int j = i + 2; j < args.length; j++) {
                    String[] selection = args[j] instanceof Bundle
                            ? ((Bundle) args[j]).getStringArray("android:query-arg-sql-selection-args")
                            : (args[j] instanceof String[] ? (String[]) args[j] : null);
                    if (selection != null && selection.length == 1 && "oaid".equals(selection[0])) return true;
                }
            }
            return false;
        }
        return false;
    }

    private static final class OaidCursor extends CursorWrapper {
        private final int valueColumn;

        OaidCursor(Cursor cursor) {
            super(cursor);
            valueColumn = cursor.getColumnIndex("value");
        }

        @Override public String getString(int column) {
            String original = super.getString(column);
            return column == valueColumn ? OaidValue.replace(original) : original;
        }
        @Override public byte[] getBlob(int column) {
            if (column != valueColumn) return super.getBlob(column);
            String value = getString(column);
            return value == null ? null : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        @Override public void copyStringToBuffer(int column, CharArrayBuffer buffer) {
            if (column != valueColumn) { super.copyStringToBuffer(column, buffer); return; }
            String value = getString(column);
            if (value == null) { buffer.sizeCopied = 0; return; }
            if (buffer.data == null || buffer.data.length < value.length()) buffer.data = new char[value.length()];
            value.getChars(0, value.length(), buffer.data, 0);
            buffer.sizeCopied = value.length();
        }
    }
}
