package com.nexbox.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import com.nexbox.app.util.NexLog
import com.nexbox.core.media.NexBoxMediaContract
import com.nexbox.core.media.NexBoxMediaEntry
import com.nexbox.core.media.NexBoxMediaStore
import com.nexbox.core.media.VerifiedPng
import com.nexbox.core.fake.frameworks.BStorageManager
import com.nexbox.core.utils.MutationReceipt
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Host media access shared by gallery presentation, imports and the Debug command surface. */
internal object SharedMediaRepository {
    fun importVerified(content: String, length: Long, sha256: String, mime: String, requestId: String): Bundle =
        conditionalCoreCall(requestId) {
            BStorageManager.get().importVerifiedMedia(content, length, sha256, mime, requestId)
        }

    fun deleteIfVersion(id: Long, version: String, owner: String, sha256: String, requestId: String): Bundle =
        conditionalCoreCall(requestId) { BStorageManager.get().deleteMediaIfVersion(id, version, owner, sha256, requestId) }

    suspend fun exportIfVersion(context: Context, id: Long, version: String, owner: String, sha256: String,
                                requestId: String): Bundle = withContext(Dispatchers.IO) {
        val snapshot = conditionalCoreCall(requestId) {
            BStorageManager.get().getMediaExportSnapshot(id, version, owner, sha256, requestId)
        }
        val resolver = context.applicationContext.contentResolver
        publishVerifiedSnapshot(snapshot, id, version, owner, sha256, requestId, object : VerifiedMediaDestination {
            override fun create(name: String?): Uri? = resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/NexBox")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })
            override fun write(target: Uri, bytes: ByteArray) {
                resolver.openOutputStream(target, "w").use { output ->
                    if (output == null) throw IOException("Destination stream unavailable")
                    output.write(bytes)
                }
            }
            override fun publish(target: Uri): Boolean = resolver.update(target,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1
            override fun rollback(target: Uri): Boolean = resolver.delete(target, null, null) == 1
        })
    }

    internal suspend fun publishVerifiedSnapshot(snapshot: Bundle, id: Long, version: String, owner: String,
                                                  sha256: String, requestId: String, writer: VerifiedMediaDestination): Bundle = withContext(Dispatchers.IO) {
        val bytes = snapshot.getByteArray("snapshotBytes")
        snapshot.remove("snapshotBytes")
        if (!snapshot.getBoolean("success")) return@withContext snapshot
        val receipt = MutationReceipt(requestId)
        receipt.data.putLong("mediaId", id)
        receipt.data.putBoolean("destinationCreated", false)
        receipt.data.putBoolean("published", false)
        receipt.data.putBoolean("rollbackAttempted", false)
        receipt.data.putBoolean("rollbackConfirmed", false)
        var destination: Uri? = null
        try {
            if (bytes == null || bytes.size !in 1..VerifiedPng.MAX_BYTES
                || !VerifiedPng.digest(bytes).equals(sha256, true) || !snapshot.getBoolean("snapshotMatched")
                || snapshot.getString("version") != version || snapshot.getString("ownerToken") != owner
                || snapshot.getLong("mediaId") != id || snapshot.getString("mimeType") != "image/png") {
                return@withContext receipt.failure("invalid_snapshot_receipt")
            }
            currentCoroutineContext().ensureActive()
            receipt.data.putBoolean("snapshotMatched", true)
            receipt.data.putString("contentSha256", VerifiedPng.digest(bytes))
            receipt.data.putLong("sizeBytes", bytes.size.toLong())
            receipt.begin("create_destination")
            receipt.data.putString("destinationCreated", null)
            destination = writer.create(snapshot.getString("displayName"))
                ?: throw IOException("Destination creation was not confirmed")
            val target = requireNotNull(destination)
            receipt.data.putString("destination", target.toString())
            receipt.data.putBoolean("destinationCreated", true)
            receipt.confirmed("destinationCreated", Bundle().apply { putString("uri", target.toString()) })
            receipt.begin("write_destination")
            writer.write(target, bytes)
            receipt.confirmed("destinationBytesWritten")
            currentCoroutineContext().ensureActive()
            receipt.begin("publish_destination")
            receipt.data.putString("published", null)
            if (!writer.publish(target)) {
                throw IOException("Destination publication was not confirmed")
            }
            receipt.data.putBoolean("published", true)
            receipt.confirmed("destinationPublished")
            receipt.success()
        } catch (failure: Exception) {
            destination?.let { target ->
                withContext(NonCancellable) {
                    receipt.data.putBoolean("rollbackAttempted", true)
                    receipt.begin("rollback_destination")
                    try {
                        if (writer.rollback(target)) {
                            receipt.data.putBoolean("rollbackConfirmed", true)
                            receipt.confirmed("destinationRolledBack")
                        }
                    } catch (unconfirmed: Exception) { /* Keep the destination and unknown rollback in the receipt. */ }
                }
            }
            receipt.failure("external_write_unconfirmed")
        } finally {
            NexLog.i("Gallery") {
                "requestId=$requestId stage=${receipt.data.getString("stage")}" +
                    " mutationState=${receipt.data.getString("mutationState")} success=${receipt.data.getBoolean("success")}" +
                    " reason=" + receipt.data.getString("errorCode")
            }
        }
    }

    // Synchronous calls keep the caller's dispatch/cancellation policy and the core's ordering/pending semantics.
    fun listMediaEntries(): List<NexBoxMediaEntry> =
        NexBoxMediaStore.listMediaEntries(NexBoxMediaContract.SHARED_USER_ID)

    fun refreshMediaEntries(): List<NexBoxMediaEntry> =
        NexBoxMediaStore.refreshMediaEntries(NexBoxMediaContract.SHARED_USER_ID)

    fun rescan() = NexBoxMediaStore.rescanUser(NexBoxMediaContract.SHARED_USER_ID)

    fun getEntry(mediaId: Long): NexBoxMediaEntry? =
        NexBoxMediaStore.getEntry(NexBoxMediaContract.SHARED_USER_ID, mediaId)

    fun importMedia(
        context: Context,
        sourceUri: Uri,
        mimeHint: String? = null,
        cancellation: CancellationSignal? = null
    ): NexBoxMediaEntry =
        NexBoxMediaStore.importMedia(context, NexBoxMediaContract.SHARED_USER_ID, sourceUri, mimeHint, cancellation)

    fun deleteMedia(mediaId: Long): Boolean {
        require(mediaId > 0) { "A valid media ID is required" }
        return deleteUri(NexBoxMediaContract.buildPrivateItemUri(
            MediaStore.Files.FileColumns.MEDIA_TYPE_NONE, mediaId, NexBoxMediaContract.SHARED_USER_ID))
    }

    fun deleteMedia(entry: NexBoxMediaEntry): Boolean = deleteUri(entry.privateUri)

    private fun deleteUri(uri: Uri): Boolean =
        NexBoxMediaStore.delete(NexBoxMediaContract.SHARED_USER_ID, uri, null, null) > 0

    fun requireReadable(entry: NexBoxMediaEntry) {
        NexBoxMediaStore.openFile(NexBoxMediaContract.SHARED_USER_ID, entry.privateUri, "r").use { }
    }

    suspend fun importBatch(context: Context, uris: List<Uri>, onProgress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val operation = NexLog.operation("MediaImport", "importBatch", "library=shared count=${uris.size}")
        var successful = 0
        try {
            uris.forEachIndexed { index, uri ->
                ensureActive()
                try {
                    require(uri.scheme == "content") { "A readable content URI is required" }
                    // Copy the picker/share grant immediately; do not retain gallery-wide
                    // or persistable permissions after importing into NexBox's own storage.
                    withMediaCancellationSignal { cancellation ->
                        runInterruptible {
                            importMedia(context, uri, null, cancellation)
                        }
                    }
                    ensureActive()
                    successful++
                    operation.step("item", "index=${index + 1} success=true")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    ensureActive()
                    NexLog.w("MediaImport", "op=${operation.id} index=${index + 1} failed", failure)
                }
                onProgress(index + 1)
            }
            operation.finish(successful == uris.size, "successful=$successful total=${uris.size}")
            successful
        } catch (cancelled: CancellationException) {
            operation.fail(cancelled)
            throw cancelled
        }
    }

    /** Publishes a copy in the host MediaStore, retaining the private shared source. */
    suspend fun export(context: Context, mediaId: Long): Uri {
        val resolver = context.applicationContext.contentResolver
        val operation = NexLog.operation("Gallery", "exportMedia", "mediaId=$mediaId")
        var destination: Uri? = null
        try {
            val result = withContext(Dispatchers.IO) {
                val entry = getEntry(mediaId)
                    ?: throw IOException("Media item is unavailable")
                check(entry.isPending == 0) { "Media item has not finished saving" }
                val collection = if (entry.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, entry.displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        "${if (entry.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/NexBox")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (entry.dateTaken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, entry.dateTaken)
                }
                val exportedUri = resolver.insert(collection, values)
                    ?: throw IOException("Could not create the exported media")
                destination = exportedUri
                resolver.openInputStream(entry.privateUri).use { input ->
                    if (input == null) throw IOException("Could not read the media")
                    resolver.openOutputStream(exportedUri, "w").use { output ->
                        if (output == null) throw IOException("Could not write the exported media")
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                check(resolver.update(exportedUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) == 1) { "Could not publish the exported media" }
                operation.step("published")
                exportedUri
            }
            operation.finish()
            return result
        } catch (failure: Throwable) {
            // Includes prompt cancellation while returning from IO after publishing the copy.
            destination?.let { uri ->
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        val deleted = resolver.delete(uri, null, null)
                        operation.step("rollback", "deleted=$deleted")
                    } catch (cleanup: Exception) {
                        failure.addSuppressed(cleanup)
                        NexLog.w("Gallery", "action=exportRollback mediaId=$mediaId", cleanup)
                    }
                }
            }
            operation.fail(failure)
            throw failure
        }
    }
}

internal interface VerifiedMediaDestination {
    fun create(name: String?): Uri?
    fun write(target: Uri, bytes: ByteArray)
    fun publish(target: Uri): Boolean
    fun rollback(target: Uri): Boolean
}

/** Notify Android's blocking provider/thumbnail API as soon as its owning request is cancelled. */
internal suspend fun <T> withMediaCancellationSignal(block: suspend (CancellationSignal) -> T): T = coroutineScope {
    val signal = CancellationSignal()
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            signal.cancel()
        }
    }
    try {
        block(signal)
    } finally {
        cancellation.cancel()
    }
}
