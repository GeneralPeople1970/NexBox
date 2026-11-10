package com.nexbox.core.core.system.pm;

import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import black.android.content.res.BRAssetManager;

/** Reads only manifest identity, without treating a config split as an installable base APK. */
public final class ApkManifest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private ApkManifest() { }

    public static ApkBundle.Manifest read(File apk) throws IOException {
        Set<String> nativeAbis = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            if (zip.getEntry("AndroidManifest.xml") == null) throw new IOException("Missing AndroidManifest.xml");
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();
                if (entry.isDirectory() || !path.startsWith("lib/") || !path.endsWith(".so")) continue;
                int end = path.indexOf('/', 4);
                if (end > 4) nativeAbis.add(path.substring(4, end));
            }
        }
        // getPackageArchiveInfo parses a monolithic APK and commonly rejects config splits.
        // An AssetManager can read the binary manifest of either kind independently.
        AssetManager assets = BRAssetManager.get()._new();
        if (assets == null) throw new IOException("Android manifest reader is unavailable");
        try (AssetManager ignored = assets) {
            Integer cookie = BRAssetManager.get(assets).addAssetPath(apk.getAbsolutePath());
            if (cookie == null || cookie == 0) throw new IOException("Cannot open APK assets");
            try (XmlResourceParser parser = assets.openXmlResourceParser(cookie, "AndroidManifest.xml")) {
                int event;
                do { event = parser.next(); }
                while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT);
                if (event != XmlPullParser.START_TAG || !"manifest".equals(parser.getName())) {
                    throw new IOException("Invalid manifest root");
                }
                String packageName = parser.getAttributeValue(null, "package");
                String splitName = parser.getAttributeValue(null, "split");
                String configForSplit = attribute(parser, "configForSplit");
                boolean feature = booleanAttribute(parser, "isFeatureSplit");
                long version = (parser.getAttributeIntValue(ANDROID, "versionCodeMajor", 0) & 0xffffffffL) << 32
                        | (parser.getAttributeIntValue(ANDROID, "versionCode", 0) & 0xffffffffL);
                String usesSplit = null;
                boolean splitRequired = false;
                while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (event != XmlPullParser.START_TAG || parser.getDepth() != 2) continue;
                    if ("uses-split".equals(parser.getName())) usesSplit = attribute(parser, "name");
                    if ("application".equals(parser.getName())) splitRequired = booleanAttribute(parser, "isSplitRequired");
                }
                return new ApkBundle.Manifest(packageName, splitName, version, feature,
                        configForSplit, usesSplit, splitRequired, nativeAbis);
            }
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Cannot parse APK manifest", error);
        }
    }

    private static String attribute(XmlResourceParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null ? parser.getAttributeValue(ANDROID, name) : value;
    }

    private static boolean booleanAttribute(XmlResourceParser parser, String name) {
        return parser.getAttributeBooleanValue(null, name,
                parser.getAttributeBooleanValue(ANDROID, name, false));
    }
}
