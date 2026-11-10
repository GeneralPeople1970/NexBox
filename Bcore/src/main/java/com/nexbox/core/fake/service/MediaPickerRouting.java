package com.nexbox.core.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.provider.MediaStore;

import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.BundleCompat;

/** Routes transient media selection to the shared library without copying caller capabilities. */
final class MediaPickerRouting {
    private MediaPickerRouting() {}

    static Intent redirect(Intent original, String hostPackage) {
        Intent request = original;
        if (Intent.ACTION_CHOOSER.equals(request.getAction())) {
            Object target = BundleCompat.getParcelable(request, Intent.EXTRA_INTENT, Parcelable.class);
            if (!(target instanceof Intent)) return null;
            request = (Intent) target;
        }
        String action = request.getAction();
        boolean document = Intent.ACTION_OPEN_DOCUMENT.equals(action) ||
                Intent.ACTION_CREATE_DOCUMENT.equals(action) || Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action);
        // A system document result would bypass the private container library. Until
        // virtual persisted grants exist, return an explicit cancellation, never a host URI.
        if (document || Intent.ACTION_CHOOSER.equals(original.getAction()) && (Intent.ACTION_GET_CONTENT.equals(action) || Intent.ACTION_PICK.equals(action))) return privatePicker(hostPackage, original);
        // A named guest destination is resolved before this fallback routing stage.
        if (request.getComponent() != null || request.getPackage() != null) return null;
        boolean pickImages = MediaStore.ACTION_PICK_IMAGES.equals(action);
        if (pickImages && request != original) {
            // This addition covers only a plain chooser wrapper; complex dispatch is still separate work.
            if (original.getComponent() != null || original.getPackage() != null) return null;
            for (String key : original.getExtras().keySet()) {
                if (!Intent.EXTRA_INTENT.equals(key) && !Intent.EXTRA_TITLE.equals(key)) return unavailable(hostPackage, original);
            }
        }
        if (!pickImages && !Intent.ACTION_GET_CONTENT.equals(action) && !Intent.ACTION_PICK.equals(action)) {
            return null;
        }
        String type = request.getType();
        if (type == null && Intent.ACTION_PICK.equals(action)) {
            Uri uri = request.getData();
            if (uri != null && "content".equals(uri.getScheme()) && "media".equals(uri.getAuthority())) {
                if (uri.getPathSegments().contains("images")) type = "image/*";
                else if (uri.getPathSegments().contains("video")) type = "video/*";
            }
        }
        String[] types = pickImages ? MediaPickerMimePolicy.resolvePickImages(type)
                : MediaPickerMimePolicy.resolve(type, request.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
        if (types == null && !pickImages) return privatePicker(hostPackage, original);
        Intent picker = new Intent().setComponent(new ComponentName(hostPackage,
                "com.nexbox.app.view.gallery.NexBoxMediaPickerActivity"));
        // Invalid PICK_IMAGES requests still reach this activity, which returns RESULT_CANCELED.
        picker.putExtra(Intent.EXTRA_MIME_TYPES, types != null ? types : new String[0]);
        if (pickImages) {
            boolean hasMaximum = request.hasExtra(MediaStore.EXTRA_PICK_IMAGES_MAX);
            int maximum = MediaPickerMimePolicy.resolvePickImagesMax(hasMaximum,
                    request.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 0),
                    hasMaximum ? getPlatformPickImagesMaxLimit() : 1);
            picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maximum);
            picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, maximum > 1);
        } else {
            picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                    request.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        }
        picker.setFlags(original.getFlags() & Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        return picker;
    }

    static boolean needsPrivatePicker(Intent original) {
        Intent target = original;
        if (Intent.ACTION_CHOOSER.equals(original.getAction())) {
            target = BundleCompat.getParcelable(original, Intent.EXTRA_INTENT, Intent.class);
            if (target == null) return false;
            if (Intent.ACTION_GET_CONTENT.equals(target.getAction()) || Intent.ACTION_PICK.equals(target.getAction())) return true;
        }
        return Intent.ACTION_OPEN_DOCUMENT.equals(target.getAction()) || Intent.ACTION_CREATE_DOCUMENT.equals(target.getAction()) || Intent.ACTION_OPEN_DOCUMENT_TREE.equals(target.getAction());
    }
    static Intent privatePicker(String hostPackage, Intent original) {
        try {
            android.os.Bundle request = new android.os.Bundle();
            Intent copy = new Intent(original);
            com.nexbox.core.utils.IntentSanitizer.sanitizeClassExtrasForIpc(copy);
            request.putParcelable("intent", copy);
            String session = com.nexbox.core.NexBoxCore.getBActivityManager().getService().documentOperation("session", request).getString("session");
            return new Intent().setComponent(new ComponentName(hostPackage, "com.nexbox.app.view.gallery.PrivateDocumentPickerActivity"))
                    .putExtra("session", session).setFlags(original.getFlags() & Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        } catch (android.os.RemoteException failure) { throw new IllegalStateException("File picker unavailable", failure); }
    }

    private static Intent unavailable(String hostPackage, Intent original) {
        return new Intent().setComponent(new ComponentName(hostPackage,
                "com.nexbox.app.view.gallery.NexBoxMediaPickerActivity"))
                .putExtra("nexbox.unsupportedDocument", true)
                .setFlags(original.getFlags() & Intent.FLAG_ACTIVITY_FORWARD_RESULT);
    }

    private static int getPlatformPickImagesMaxLimit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return MediaStore.getPickImagesMaxLimit();
        // Android 11/12 can expose this through a media module update; do not guess its limit.
        try {
            return (Integer) MediaStore.class.getMethod("getPickImagesMaxLimit").invoke(null);
        } catch (NoSuchMethodException unavailable) {
            return 1;
        } catch (ReflectiveOperationException failure) {
            Slog.w("GalleryPicker", "action=maxLimit reason=" + failure.getClass().getSimpleName());
            return 1;
        }
    }
}
