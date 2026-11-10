package com.nexbox.core.fake.service.context.providers;

import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import java.lang.reflect.Method;

import com.nexbox.core.fake.device.DeviceSpoofManager;

/** Hooks the actual IContentProvider path used by Settings.Secure, including direct queries. */
public final class SettingsProviderStub extends SystemProviderStub {
    @Override
    protected String describeFailureContext(Method method, Object[] args) {
        if (!"call".equals(method.getName()) || args == null
                || args.length != method.getParameterTypes().length) return "";
        int request = ProviderCallIdentity.layout(method).callMethod;
        if (request < 0 || request + 1 >= args.length || !(args[request] instanceof String)) return "";
        String operation = (String) args[request];
        if (!operation.matches("(?:GET|PUT|DELETE|RESET|LIST)_(?:secure|system|global|config)")) {
            return " settingsOperation=other";
        }
        // The RPC argument is a setting name only for individual reads/writes/deletes.
        // Do not inspect the extras Bundle: it carries values, tags and other private data.
        if (operation.startsWith("RESET_") || operation.startsWith("LIST_")) {
            return " settingsOperation=" + operation;
        }
        String key = args[request + 1] instanceof String ? (String) args[request + 1] : null;
        String name = key == null ? "none"
                : key.matches("[a-z][a-z0-9_.]{0,95}") ? key : "<redacted>";
        return " settingsOperation=" + operation + " settingKey=" + name;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (!DeviceSpoofManager.isCurrentProcessEnabled()) return super.invoke(proxy, method, args);
        if ("call".equals(method.getName()) && args != null && args.length >= 3) {
            // All supported IContentProvider.call signatures end in (method, arg, extras).
            int request = args.length - 3;
            if ("GET_secure".equals(args[request]) && Settings.Secure.ANDROID_ID.equals(args[request + 1])) {
                Bundle result = new Bundle();
                result.putString("value", DeviceSpoofManager.getAndroidIdForCurrentUser());
                // No host generation tracker: Settings must not cache a host ID for this result.
                return result;
            }
        }

        Object result = super.invoke(proxy, method, args);
        if ("query".equals(method.getName()) && result instanceof Cursor && isSecureQuery(args)) {
            return new AndroidIdCursor((Cursor) result, targetsAndroidId(args),
                    DeviceSpoofManager.getAndroidIdForCurrentUser());
        }
        return result;
    }

    private static boolean isSecureQuery(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg instanceof Uri) {
                Uri uri = (Uri) arg;
                return "settings".equals(uri.getAuthority()) && !uri.getPathSegments().isEmpty()
                        && "secure".equals(uri.getPathSegments().get(0));
            }
        }
        return false;
    }

    private static boolean targetsAndroidId(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg instanceof Uri && "/secure/android_id".equals(((Uri) arg).getPath())) return true;
            if (arg instanceof Bundle) {
                Bundle query = (Bundle) arg;
                if (isAndroidIdSelection(query.getString("android:query-arg-sql-selection"),
                        query.getStringArray("android:query-arg-sql-selection-args"))) return true;
            }
        }
        // Legacy query ends with URI, projection, selection, selectionArgs, sortOrder, ...
        for (int i = 0; i + 3 < args.length; i++) {
            if (args[i] instanceof Uri && args[i + 2] instanceof String) {
                String[] selectionArgs = args[i + 3] instanceof String[] ? (String[]) args[i + 3] : null;
                return isAndroidIdSelection((String) args[i + 2], selectionArgs);
            }
        }
        return false;
    }

    private static boolean isAndroidIdSelection(String selection, String[] args) {
        if (selection == null) return false;
        String normalized = selection.replaceAll("\\s+", "").replace("(", "").replace(")", "");
        return ("name=?".equals(normalized) && args != null && args.length == 1
                && Settings.Secure.ANDROID_ID.equals(args[0]))
                || "name='android_id'".equals(normalized) || "name=\"android_id\"".equals(normalized);
    }

    private static final class AndroidIdCursor extends CursorWrapper {
        private final int nameColumn;
        private final int valueColumn;
        private final boolean targeted;
        private final String androidId;

        AndroidIdCursor(Cursor cursor, boolean targeted, String androidId) {
            super(cursor);
            this.nameColumn = cursor.getColumnIndex("name");
            this.valueColumn = cursor.getColumnIndex("value");
            this.targeted = targeted;
            this.androidId = androidId;
        }

        private boolean replace(int column) {
            return column == valueColumn && valueColumn >= 0 && (targeted
                    || (nameColumn >= 0 && Settings.Secure.ANDROID_ID.equals(super.getString(nameColumn))));
        }

        @Override public String getString(int column) { return replace(column) ? androidId : super.getString(column); }
        @Override public long getLong(int column) { return replace(column) ? numericValue() : super.getLong(column); }
        @Override public int getInt(int column) { return replace(column) ? (int) numericValue() : super.getInt(column); }
        @Override public short getShort(int column) { return replace(column) ? (short) numericValue() : super.getShort(column); }
        @Override public float getFloat(int column) { return replace(column) ? numericValue() : super.getFloat(column); }
        @Override public double getDouble(int column) { return replace(column) ? numericValue() : super.getDouble(column); }
        @Override public boolean isNull(int column) { return !replace(column) && super.isNull(column); }
        @Override public int getType(int column) { return replace(column) ? Cursor.FIELD_TYPE_STRING : super.getType(column); }
        @Override public byte[] getBlob(int column) {
            return replace(column) ? androidId.getBytes(java.nio.charset.StandardCharsets.UTF_8) : super.getBlob(column);
        }
        @Override public void copyStringToBuffer(int column, CharArrayBuffer buffer) {
            if (!replace(column)) { super.copyStringToBuffer(column, buffer); return; }
            if (buffer.data == null || buffer.data.length < androidId.length()) buffer.data = new char[androidId.length()];
            androidId.getChars(0, androidId.length(), buffer.data, 0);
            buffer.sizeCopied = androidId.length();
        }

        private long numericValue() {
            // Match a SQLite text-to-integer prefix conversion, never return the host value.
            int end = 0;
            while (end < androidId.length() && androidId.charAt(end) >= '0' && androidId.charAt(end) <= '9') end++;
            return end == 0 ? 0 : Long.parseLong(androidId.substring(0, end));
        }
    }
}
