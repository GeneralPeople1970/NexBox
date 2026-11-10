package com.nexbox.core.core.system.os;

import android.os.storage.StorageVolume;
import java.lang.String;
import android.net.Uri;
import android.os.Bundle;

interface IBStorageManagerService {
      StorageVolume[] getVolumeList(int uid, String packageName, int flags, int userId);
      Uri getUriForFile(String file);
      Bundle importVerifiedMedia(String contentBase64, long expectedLength, String expectedSha256, String mimeType, String requestId);
      Bundle deleteMediaIfVersion(long mediaId, String expectedVersion, String expectedOwnerToken, String expectedSha256, String requestId);
      Bundle getMediaExportSnapshot(long mediaId, String expectedVersion, String expectedOwnerToken, String expectedSha256, String requestId);
}
