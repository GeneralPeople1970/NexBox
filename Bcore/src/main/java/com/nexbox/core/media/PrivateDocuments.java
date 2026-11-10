package com.nexbox.core.media;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.core.system.xposed.XposedCallerGuard;
import com.nexbox.core.utils.compat.BundleCompat;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Core-process document ownership and persistable grants. Never addresses phone storage. */
public final class PrivateDocuments {
    private static final Map<String, Bundle> sessions = new HashMap<>();
    private static JSONObject grants;
    private static final Map<String, ProcessRecord> temporary = new HashMap<>();
    private static final AtomicFile store = new AtomicFile(new File(BEnvironment.getSystemDir(), "document-grants.json"));
    private PrivateDocuments() { }
    public static String authority() { return NexBoxCore.getHostPkg() + ".nexbox.Documents"; }
    static Object lock() { return BPackageManagerService.get().getLifecycleLock(); }
    static JSONObject grants() throws Exception {
        if (grants == null) grants = store.getBaseFile().exists() ? new JSONObject(new String(store.readFully(), StandardCharsets.UTF_8)) : new JSONObject();
        return grants;
    }
    private static void save(JSONObject next) throws Exception {
        FileOutputStream out = null;
        try { out = store.startWrite(); out.write(next.toString().getBytes(StandardCharsets.UTF_8)); store.finishWrite(out); grants = next; }
        catch (Exception failure) { if (out != null) store.failWrite(out); throw failure; }
    }
    private static ProcessRecord caller() {
        ProcessRecord p = XposedCallerGuard.requireGuest();
        com.nexbox.core.core.system.user.BUserInfo u = BUserManagerService.get().getUserInfo(p.userId);
        if (u == null || u.createTime != p.userGeneration || p.bActivityThread == null || !p.bActivityThread.asBinder().isBinderAlive())
            throw new SecurityException("Expired document caller");
        return p;
    }
    public static Bundle operation(String operation, Bundle request) {
        synchronized (lock()) {
            try {
                ProcessRecord p = caller();
                Bundle response = new Bundle();
                if ("session".equals(operation)) {
                    Intent intent = BundleCompat.getParcelable(request, "intent", Intent.class);
                    if (intent == null) throw new IllegalArgumentException("Missing selection intent");
                    long now = SystemClock.elapsedRealtime();
                    sessions.values().removeIf(s -> now - s.getLong("created") > 30 * 60_000);
                    if (sessions.size() >= 64) throw new IllegalStateException("Too many file pickers");
                    Bundle session = new Bundle();
                    session.putInt("user", p.userId); session.putLong("generation", p.userGeneration);
                    session.putString("package", p.getPackageName()); session.putParcelable("intent", intent);
                    session.putLong("created", now); session.putInt("pid", p.pid);
                    session.putBinder("process", p);
                    String token = UUID.randomUUID().toString(); sessions.put(token, session); response.putString("session", token);
                    return response;
                }
                if ("listGrants".equals(operation)) {
                    ArrayList<Bundle> list = new ArrayList<>();
                    for (String key : keys(grants())) {
                        JSONObject entry = grants().getJSONObject(key);
                        if (!belongs(entry, p) || entry.optInt("persisted") == 0) continue;
                        Bundle value = new Bundle(); value.putString("uri", entry.getString("uri"));
                        value.putInt("flags", entry.getInt("persisted")); value.putLong("time", entry.getLong("time")); list.add(value);
                    }
                    response.putParcelableArrayList("grants", list); return response;
                }
                Uri uri = BundleCompat.getParcelable(request, "uri", Uri.class);
                if (uri == null || !authority().equals(uri.getAuthority())) throw new SecurityException("Not a private document URI");
                JSONObject next = new JSONObject(grants().toString()); JSONObject entry = next.optJSONObject(uri.toString());
                int flags = request.getInt("flags");
                if (flags == 0 || (flags & ~3) != 0) throw new IllegalArgumentException("Invalid grant flags");
                if (entry == null || !belongs(entry, p)) throw new SecurityException("Document grant missing");
                if ("take".equals(operation)) {
                    if (!entry.optBoolean("persistable") || (entry.getInt("offered") & flags) != flags ||
                            temporary.get(uri.toString()) != p && (entry.optInt("persisted") & flags) != flags)
                        throw new SecurityException("No offered persistable permission");
                    entry.put("persisted", entry.optInt("persisted") | flags).put("time", System.currentTimeMillis());
                } else if ("release".equals(operation)) entry.put("persisted", entry.optInt("persisted") & ~flags);
                else throw new IllegalArgumentException("Unknown grant operation");
                save(next); return response;
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Document operation failed", failure); }
        }
    }
    private static boolean belongs(JSONObject e, ProcessRecord p) throws Exception {
        return e.getInt("user") == p.userId && e.getLong("generation") == p.userGeneration && e.getString("package").equals(p.getPackageName());
    }
    private static Bundle session(String token) {
        XposedCallerGuard.enforceHostManager();
        Bundle s = sessions.get(token);
        if (s == null || SystemClock.elapsedRealtime() - s.getLong("created") > 30 * 60_000) throw new SecurityException("File picker expired");
        com.nexbox.core.core.system.user.BUserInfo u = BUserManagerService.get().getUserInfo(s.getInt("user"));
        if (u == null || u.createTime != s.getLong("generation") ||
                BPackageManagerService.get().getPackageInfo(s.getString("package"), 0, u.id) == null) throw new SecurityException("File picker owner removed");
        return s;
    }
    public static Bundle picker(String action, String token, Bundle request) {
        synchronized (lock()) {
            try {
                Bundle s = session(token); Bundle result = new Bundle();
                Intent original = BundleCompat.getParcelable(s, "intent", Intent.class);
                Intent intent = Intent.ACTION_CHOOSER.equals(original.getAction()) ? BundleCompat.getParcelable(original, Intent.EXTRA_INTENT, Intent.class) : original;
                if ("info".equals(action)) {
                    result.putParcelable("intent", original);
                    ArrayList<Bundle> candidates = candidates(s, original);
                    s.putParcelableArrayList("candidates", candidates);
                    result.putParcelableArrayList("candidates", candidates); return result;
                }
                if ("candidate".equals(action)) {
                    ArrayList<?> candidates = BundleCompat.getParcelableArrayList(s, "candidates");
                    int index = request.getInt("index", -1);
                    if (candidates == null || index < 0 || index >= candidates.size()) throw new SecurityException("Unknown picker candidate");
                    Bundle candidate = Bundle.class.cast(candidates.get(index));
                    Intent chosen = BundleCompat.getParcelable(candidate, "intent", Intent.class);
                    android.content.pm.ResolveInfo resolved = BPackageManagerService.get().resolveActivity(chosen, 0, chosen.getType(), s.getInt("user"));
                    if (resolved == null || resolved.activityInfo == null || !com.nexbox.core.core.system.am.BActivityManagerService.get().canAccessPendingComponent(s.getString("package"), resolved.activityInfo, resolved.activityInfo.permission, s.getInt("user"))) throw new SecurityException("Picker candidate changed");
                    result.putParcelable("launch", com.nexbox.core.core.system.am.BActivityManagerService.get().prepareDocumentCandidate(new Intent(chosen), s.getInt("user")));
                    return result;
                }
                if ("close".equals(action)) { sessions.remove(token); return result; }
                String id = request == null ? null : request.getString("id");
                if ("list".equals(action)) {
                    ArrayList<Bundle> entries = new ArrayList<>();
                    if (id == null) for (String root : new String[]{"documents", "downloads", "images", "videos", "app"}) {
                        String doc = id(s, root, ""); file(doc, true); entries.add(describe(doc, root));
                    } else {
                        ownSession(id, s); File parent = file(id, false);
                        File[] files = parent.listFiles(); if (files == null) throw new IllegalStateException("Cannot list directory");
                        Arrays.sort(files, Comparator.comparing((File f) -> !f.isDirectory()).thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                        JSONObject parts = decode(id);
                        for (File child : files) {
                            if (Files.isSymbolicLink(child.toPath())) continue;
                            String path = parts.getString("path");
                            String childId = id(s, parts.getString("root"), path.isEmpty() ? child.getName() : path + "/" + child.getName());
                            if (child.isDirectory() || accepts(intent, mime(child))) entries.add(describe(childId, child.getName()));
                        }
                    }
                    result.putParcelableArrayList("entries", entries); return result;
                }
                if ("choose".equals(action)) {
                    ArrayList<String> selected = request.getStringArrayList("ids");
                    boolean tree = Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction());
                    boolean create = Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction());
                    boolean persistable = tree || create || Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction());
                    if (selected == null || selected.isEmpty() || selected.size() > 100 ||
                            selected.size() > 1 && (tree || create || !intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))) throw new IllegalArgumentException("Invalid selection");
                    ArrayList<Uri> uris = new ArrayList<>(); ArrayList<String> types = new ArrayList<>(); int returnedModes = 3;
                    JSONObject next = new JSONObject(grants().toString());
                    for (String selectedId : selected) {
                        ownSession(selectedId, s); File selectedFile = file(selectedId, false);
                        if (create) {
                            if (!selectedFile.isDirectory()) throw new IllegalArgumentException("Choose a directory");
                            String name = request.getString("name", intent.getStringExtra(Intent.EXTRA_TITLE));
                            validateName(name);
                            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(intent.getType());
                            if (extension != null && !name.toLowerCase(Locale.ROOT).endsWith("." + extension)) name += "." + extension;
                            File child = new File(selectedFile, name);
                            if (!child.createNewFile()) throw new IllegalArgumentException("File already exists");
                            JSONObject parts = decode(selectedId); String parent = parts.getString("path");
                            selectedId = id(s, parts.getString("root"), parent.isEmpty() ? name : parent + "/" + name); selectedFile = child;
                        }
                        if (tree != selectedFile.isDirectory() || !selectedFile.canRead() || !tree && !accepts(intent, mime(selectedFile))) throw new IllegalArgumentException("File is not selectable");
                        Uri uri = tree ? DocumentsContract.buildTreeDocumentUri(authority(), selectedId) : DocumentsContract.buildDocumentUri(authority(), selectedId);
                        int flags = persistable && selectedFile.canWrite() ? 3 : 1; returnedModes &= flags;
                        JSONObject entry = next.optJSONObject(uri.toString()); if (entry == null) entry = new JSONObject();
                        entry.put("user", s.getInt("user")).put("generation", s.getLong("generation")).put("package", s.getString("package"))
                                .put("id", selectedId).put("uri", uri.toString()).put("tree", tree).put("offered", flags).put("persistable", persistable);
                        // Transient grants are tied to the live selecting app process; persisted grants survive it.
                        ProcessRecord process = com.nexbox.core.core.system.BProcessManagerService.get().findProcessByPid(s.getInt("pid"));
                        if (process == null || process != s.getBinder("process")) throw new SecurityException("Selecting process exited");
                        temporary.put(uri.toString(), process);
                        next.put(uri.toString(), entry); uris.add(uri); types.add(tree ? DocumentsContract.Document.MIME_TYPE_DIR : mime(selectedFile));
                    }
                    save(next);
                    Intent output = new Intent().setDataAndType(uris.get(0), new HashSet<>(types).size() == 1 ? types.get(0) : "*/*");
                    output.setClipData(new ClipData(new ClipDescription("", types.toArray(new String[0])), new ClipData.Item(uris.get(0))));
                    for (int i = 1; i < uris.size(); i++) output.getClipData().addItem(new ClipData.Item(uris.get(i)));
                    output.addFlags(returnedModes | (persistable ? Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION : 0) | (tree ? Intent.FLAG_GRANT_PREFIX_URI_PERMISSION : 0));
                    result.putParcelable("result", output); sessions.remove(token); return result;
                }
                throw new IllegalArgumentException("Unknown picker action");
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Private file selection failed", failure); }
        }
    }
    private static ArrayList<Bundle> candidates(Bundle session, Intent original) throws Exception {
        ArrayList<Intent> requests = new ArrayList<>();
        Intent primary = Intent.ACTION_CHOOSER.equals(original.getAction()) ? BundleCompat.getParcelable(original, Intent.EXTRA_INTENT, Intent.class) : original;
        if (primary != null) requests.add(primary);
        android.os.Parcelable[] initial = BundleCompat.getParcelableArray(original, Intent.EXTRA_INITIAL_INTENTS);
        if (initial != null) for (android.os.Parcelable value : initial) if (value instanceof Intent && requests.size() < 32) requests.add((Intent) value);
        android.os.Parcelable[] alternatives = BundleCompat.getParcelableArray(original, Intent.EXTRA_ALTERNATE_INTENTS);
        if (alternatives != null) for (android.os.Parcelable value : alternatives) if (value instanceof Intent && requests.size() < 32) requests.add((Intent) value);
        Set<android.content.ComponentName> excluded = new HashSet<>();
        android.os.Parcelable[] exclusions = BundleCompat.getParcelableArray(original, Intent.EXTRA_EXCLUDE_COMPONENTS);
        if (exclusions != null) for (android.os.Parcelable value : exclusions) if (value instanceof android.content.ComponentName) excluded.add((android.content.ComponentName) value);
        ArrayList<Bundle> result = new ArrayList<>();
        for (Intent request : requests) {
            java.util.List<android.content.pm.ResolveInfo> resolved = BPackageManagerService.get().queryIntentActivities(request, 0, request.getType(), session.getInt("user"));
            if (resolved == null) continue;
            for (android.content.pm.ResolveInfo info : resolved) {
                android.content.pm.ActivityInfo activity = info.activityInfo;
                if (activity == null || !activity.enabled || !com.nexbox.core.core.system.am.BActivityManagerService.get().canAccessPendingComponent(session.getString("package"), activity, activity.permission, session.getInt("user"))) continue;
                if (excluded.contains(new android.content.ComponentName(activity.packageName, activity.name))) continue;
                Intent candidate = new Intent(request).setComponent(new android.content.ComponentName(activity.packageName, activity.name));
                Bundle item = new Bundle(); item.putString("name", activity.packageName); item.putParcelable("intent", candidate); result.add(item);
            }
        }
        return result;
    }
    private static void ownSession(String id, Bundle s) throws Exception {
        JSONObject p = decode(id);
        if (p.getInt("user") != s.getInt("user") || p.getLong("generation") != s.getLong("generation") || !p.getString("package").equals(s.getString("package"))) throw new SecurityException("Document owner mismatch");
    }
    static File authorized(String id, int flags) throws Exception {
        synchronized (lock()) {
            ProcessRecord p = caller(); JSONObject target = decode(id);
            for (String key : keys(grants())) {
                JSONObject e = grants().getJSONObject(key);
                int allowed = e.optInt("persisted") | (temporary.get(key) == p ? e.optInt("offered") : 0);
                if (!belongs(e, p) || (allowed & flags) != flags) continue;
                JSONObject base = decode(e.getString("id"));
                if (id.equals(e.getString("id")) || e.optBoolean("tree") && sameRoot(base, target) && descendant(base.getString("path"), target.getString("path"))) return file(id, false);
            }
            throw new SecurityException("No document permission");
        }
    }
    private static boolean sameRoot(JSONObject a, JSONObject b) throws Exception {
        return a.getInt("user") == b.getInt("user") && a.getLong("generation") == b.getLong("generation") && a.getString("package").equals(b.getString("package")) && a.getString("root").equals(b.getString("root"));
    }
    static boolean descendant(String parent, String child) { return parent.isEmpty() || child.startsWith(parent + "/"); }
    static JSONObject decode(String id) throws Exception { return new JSONObject(new String(Base64.decode(id, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8)); }
    static String encode(JSONObject data) { return Base64.encodeToString(data.toString().getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static String id(Bundle s, String root, String path) throws Exception { return encode(new JSONObject().put("user", s.getInt("user")).put("generation", s.getLong("generation")).put("package", s.getString("package")).put("root", root).put("path", path)); }
    static File file(String id, boolean createRoot) throws Exception {
        JSONObject p = decode(id); File root;
        switch (p.getString("root")) {
            case "documents": root = new File(BEnvironment.getExternalUserDir(p.getInt("user")), "Documents"); break;
            case "downloads": root = NexBoxMediaStore.getDownloadsRoot(); break;
            case "images": root = NexBoxMediaStore.getImagesRoot(p.getInt("user")); break;
            case "videos": root = NexBoxMediaStore.getVideosRoot(p.getInt("user")); break;
            case "app": root = BEnvironment.getDataFilesDir(p.getString("package"), p.getInt("user")); break;
            default: throw new SecurityException("Unknown document root");
        }
        // Android may alias /data/user/0 to /data/data. Resolve the host-owned anchor once,
        // then reject links introduced below that anchor instead of rejecting the OS alias.
        File anchor = "documents".equals(p.getString("root")) ? BEnvironment.getExternalVirtualRoot() : BEnvironment.getVirtualRoot();
        java.nio.file.Path relative = anchor.toPath().toAbsolutePath().normalize().relativize(root.toPath().toAbsolutePath().normalize());
        if (relative.startsWith("..")) throw new SecurityException("Document root escapes container");
        root = new File(anchor.getCanonicalFile(), relative.toString());
        if (!root.getCanonicalPath().equals(root.getAbsolutePath())) throw new SecurityException("Linked document root");
        if (createRoot && !root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Cannot create document root");
        String path = p.getString("path");
        if (path.startsWith("/") || path.contains("\\") || Arrays.asList(path.split("/")).contains("..")) throw new SecurityException("Invalid document path");
        File target = path.isEmpty() ? root : new File(root, path);
        if (!target.getCanonicalPath().equals(target.getAbsolutePath()) || !root.getCanonicalPath().equals(root.getAbsolutePath()) ||
                !(target.equals(root) || target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))) throw new SecurityException("Document path escapes root");
        return target;
    }
    static String mime(File f) {
        if (f.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        String name = f.getName(); String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext); return mime == null ? "application/octet-stream" : mime;
    }
    private static boolean accepts(Intent request, String mime) {
        if (Intent.ACTION_OPEN_DOCUMENT_TREE.equals(request.getAction())) return true;
        String base = request.getType() == null ? "*/*" : request.getType();
        if (!IntentFilterMime.matches(base, mime)) return false;
        String[] extras = request.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        return extras == null || extras.length == 0 || Arrays.stream(extras).anyMatch(type -> IntentFilterMime.matches(type, mime));
    }
    static void validateName(String name) { if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf(0) >= 0) throw new IllegalArgumentException("Invalid file name"); }
    private static Bundle describe(String id, String label) throws Exception { File f = file(id, false); Bundle b = new Bundle(); b.putString("id", id); b.putString("name", label); b.putBoolean("directory", f.isDirectory()); return b; }
    private static ArrayList<String> keys(JSONObject o) { ArrayList<String> list = new ArrayList<>(); o.keys().forEachRemaining(list::add); return list; }
    public static void removeOwner(int user, String pkg) {
        synchronized (lock()) {
            try {
                JSONObject next = new JSONObject(grants().toString());
                for (String key : keys(grants())) { JSONObject e = grants().getJSONObject(key); if (e.getInt("user") == user && (pkg == null || pkg.equals(e.getString("package")))) { next.remove(key); temporary.remove(key); } }
                save(next); sessions.values().removeIf(s -> s.getInt("user") == user && (pkg == null || pkg.equals(s.getString("package"))));
            } catch (Exception e) { throw new IllegalStateException("Could not clean document grants", e); }
        }
    }
    private static final class IntentFilterMime {
        static boolean matches(String filter, String mime) { return filter != null && (filter.equals("*/*") || filter.equals(mime) || filter.endsWith("/*") && mime.startsWith(filter.substring(0, filter.length() - 1))); }
    }
}
