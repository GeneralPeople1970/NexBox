package com.nexbox.core.core.system.accounts;

import com.nexbox.core.utils.Slog;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;

import black.android.content.res.BRAssetManager;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.pm.BPackageSettings;
import com.nexbox.core.core.system.pm.PackageManagerCompat;

public class RegisteredServicesParser {

    public XmlResourceParser getParser(Context context, ServiceInfo serviceInfo, String name) {
        Bundle meta = serviceInfo.metaData;
        if (meta != null) {
            int xmlId = meta.getInt(name);
            if (xmlId != 0) {
                try {
                    Resources resources = getResources(context, serviceInfo.applicationInfo);
                    if (resources == null)
                        return null;
                    return resources.getXml(xmlId);
                } catch (Exception e) {
                    Slog.e("RegisteredServicesParser", "Container operation failed", e);
                }
            }
        }
        return null;
    }

    public Resources getResources(Context context, ApplicationInfo appInfo) {
        return PackageManagerCompat.getResources(context, appInfo);
    }
}
