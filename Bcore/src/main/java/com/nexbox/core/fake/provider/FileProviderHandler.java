package com.nexbox.core.fake.provider;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;

import java.io.File;
import java.util.List;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;


public class FileProviderHandler {

    public static Uri convertFileUri(Context context, Uri uri) {
        File file = convertFile(context, uri);
        if (file == null)
            return null;
        return NexBoxCore.getBStorageManager().getUriForFile(file.getAbsolutePath());
    }

    public static File convertFile(Context context, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null) return null;
        List<ProviderInfo> providers = BActivityThread.getProviders();
        for (ProviderInfo provider : providers) {
            if (provider == null || provider.authority == null
                    || !java.util.Arrays.asList(provider.authority.split(";")).contains(uri.getAuthority())) continue;
            try {
                File fileForUri = FileProvider.getFileForUri(context, uri.getAuthority(), uri);
                if (fileForUri != null && fileForUri.exists()) {
                    return fileForUri;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
