package com.nexbox.app.data

import android.content.Context
import android.content.SharedPreferences
import com.nexbox.app.BuildConfig
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.net.ssl.HttpsURLConnection

data class UpdateRelease(
    val tag: String,
    val name: String,
    val version: String,
    val publishedAt: String,
    val url: String,
    val notesZh: String,
    val notesEn: String
)

data class UpdateState(
    val autoCheckEnabled: Boolean = true,
    val checking: Boolean = false,
    val lastCheckedAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val releases: List<UpdateRelease> = emptyList(),
    val updateAvailable: Boolean = false,
    val errorCode: String? = null
) {
    val latest: UpdateRelease? get() = releases.firstOrNull()
}

/** Shared host update state. Reading it never requests the network or installs an APK. */
class UpdateRepository private constructor(private val preferences: SharedPreferences) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentVersion = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var activeRequest: Request? = null

    /** apply() confirms the preference's memory value, not completion of its asynchronous disk write. */
    fun setAutoCheckEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        if (mutableState.value.autoCheckEnabled == enabled) return@synchronized true
        try {
            preferences.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        } catch (failure: RuntimeException) {
            NexLog.w(TAG, "action=setAutomatic result=failed error=storage_error type=${failure.javaClass.simpleName}")
            return@synchronized false
        }
        mutableState.value = mutableState.value.copy(autoCheckEnabled = enabled)
        NexLog.i(TAG, "action=setAutomatic result=applied enabled=$enabled persistence=asynchronous")
        true
    }

    suspend fun check(source: String = "manual"): UpdateState = checkInternal(source, automatic = false)

    suspend fun checkAutomatically() {
        checkInternal("automatic", automatic = true)
    }

    private suspend fun checkInternal(source: String, automatic: Boolean): UpdateState {
        while (true) {
            currentCoroutineContext().ensureActive()
            val request = synchronized(lock) {
                val snapshot = mutableState.value
                if (automatic && !snapshot.autoCheckEnabled) return snapshot
                val existing = activeRequest?.takeUnless { it.result.isCompleted && !it.cancelled }
                if (existing != null) {
                    if (!existing.cancelled) existing.waiters++
                    existing
                } else {
                    val now = System.currentTimeMillis()
                    if (automatic && snapshot.lastAttemptAt > 0 &&
                        (now < snapshot.lastAttemptAt || now - snapshot.lastAttemptAt < AUTOMATIC_INTERVAL_MS)) {
                        return snapshot
                    }
                    Request(source.takeIf { it in setOf("manual", "automatic", "cli") } ?: "other").also {
                        it.waiters = 1
                        activeRequest = it
                        mutableState.value = snapshot.copy(checking = true, errorCode = null)
                        it.job = scope.launch(start = CoroutineStart.LAZY) { perform(it) }
                        // Completion releases the request even if cancellation prevents a
                        // queued coroutine from entering perform().
                        it.job?.invokeOnCompletion { failure -> finishRequest(it, failure) }
                    }
                }
            }
            if (request.cancelled) {
                request.finished.await()
                continue
            }
            request.job?.start()
            try {
                return request.result.await()
            } finally {
                synchronized(lock) {
                    request.waiters--
                    if (request.waiters == 0 && !request.result.isCompleted) {
                        request.cancelled = true
                        request.job?.cancel()
                    }
                }
            }
        }
    }

    private suspend fun perform(request: Request) {
        try {
            val releases = withTimeout(REQUEST_TIMEOUT_MS) { download(request) }
            currentCoroutineContext().ensureActive()
            if (releases.isEmpty()) throw UpdateFailure("no_stable_release")
            val localVersion = currentVersion ?: throw UpdateFailure("current_version_invalid")
            val newest = ReleaseVersion.parse(releases.first().version) ?: throw UpdateFailure("invalid_response")
            val available = newest > localVersion
            val checkedAt = System.currentTimeMillis()
            synchronized(lock) {
                if (request.cancelled) throw CancellationException("Update request cancelled")
                try {
                    preferences.edit().putString(KEY_RELEASES, toJson(releases).toString())
                        .putLong(KEY_LAST_CHECKED, checkedAt).remove(KEY_ERROR).apply()
                } catch (failure: RuntimeException) {
                    throw UpdateFailure("storage_error")
                }
                mutableState.value = mutableState.value.copy(
                    checking = false,
                    lastCheckedAt = checkedAt,
                    releases = releases,
                    updateAvailable = available,
                    errorCode = null
                )
                request.result.complete(mutableState.value)
            }
            NexLog.i(TAG, "source=${request.source} stage=complete result=${if (available) "update_available" else "up_to_date"} releases=${releases.size}")
        } catch (failure: TimeoutCancellationException) {
            fail(request, "network_error")
        } catch (failure: CancellationException) {
            fail(request, "cancelled", failure)
        } catch (failure: UpdateFailure) {
            fail(request, failure.code)
        } catch (failure: IOException) {
            fail(request, "network_error")
        } catch (failure: SecurityException) {
            fail(request, "network_error")
        } catch (failure: JSONException) {
            fail(request, "invalid_response")
        } catch (failure: RuntimeException) {
            NexLog.w(TAG, "source=${request.source} stage=request result=failed type=${failure.javaClass.simpleName}")
            fail(request, "invalid_response")
        }
    }

    private fun finishRequest(request: Request, failure: Throwable?) = synchronized(lock) {
        if (!request.result.isCompleted) {
            if (failure is CancellationException) {
                fail(request, "cancelled", failure)
            } else if (failure != null) {
                request.result.completeExceptionally(failure)
            }
        }
        if (activeRequest === request) {
            mutableState.value = mutableState.value.copy(checking = false)
            activeRequest = null
        }
        request.finished.complete(Unit)
    }

    private fun fail(request: Request, code: String, cancelled: CancellationException? = null) {
        synchronized(lock) {
            try {
                preferences.edit().putString(KEY_ERROR, code).apply()
            } catch (failure: RuntimeException) {
                NexLog.w(TAG, "source=${request.source} stage=cacheError result=failed error=storage_error type=${failure.javaClass.simpleName}")
            }
            mutableState.value = mutableState.value.copy(checking = false, updateAvailable = false, errorCode = code)
            if (cancelled != null) request.result.cancel(cancelled)
            else request.result.complete(mutableState.value)
        }
        val detail = "source=${request.source} stage=complete result=${if (cancelled != null) "cancelled" else "failed"} error=$code"
        if (cancelled != null) NexLog.i(TAG, detail) else NexLog.w(TAG, detail)
    }

    /** Cancellation closes the connection even while an IO worker is blocked in connect/read. */
    private suspend fun download(request: Request): List<UpdateRelease> = suspendCancellableCoroutine { continuation ->
        val connection = URL(API_ENDPOINT).openConnection() as HttpsURLConnection
        val reader = scope.launch(start = CoroutineStart.LAZY) {
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECTION_TIMEOUT_MS
                connection.readTimeout = CONNECTION_TIMEOUT_MS
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                connection.setRequestProperty("User-Agent", "NexBox/${BuildConfig.VERSION_NAME}")
                currentCoroutineContext().ensureActive()
                recordAttempt(request)
                val status = connection.responseCode
                currentCoroutineContext().ensureActive()
                if (status != HttpsURLConnection.HTTP_OK) {
                    val limited = status == 429 || status == HttpsURLConnection.HTTP_FORBIDDEN &&
                        (connection.getHeaderField("X-RateLimit-Remaining") == "0" ||
                            connection.getHeaderField("Retry-After") != null)
                    throw UpdateFailure(if (limited) "rate_limited" else "http_$status")
                }
                val length = connection.contentLengthLong
                if (length > MAX_RESPONSE_BYTES) throw UpdateFailure("invalid_response")
                val response = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (response.size() + count > MAX_RESPONSE_BYTES) throw UpdateFailure("invalid_response")
                        response.write(buffer, 0, count)
                    }
                }
                currentCoroutineContext().ensureActive()
                val releases = parseResponse(JSONArray(response.toString(Charsets.UTF_8.name())))
                continuation.resumeWith(Result.success(releases))
            } catch (failure: Exception) {
                continuation.resumeWith(Result.failure(failure))
            } finally {
                connection.disconnect()
            }
        }
        continuation.invokeOnCancellation {
            connection.disconnect()
            reader.cancel()
        }
        reader.start()
    }

    private fun recordAttempt(request: Request) = synchronized(lock) {
        if (request.cancelled) throw CancellationException("Update request cancelled")
        val now = System.currentTimeMillis()
        try {
            preferences.edit().putLong(KEY_LAST_ATTEMPT, now).apply()
        } catch (failure: RuntimeException) {
            throw UpdateFailure("storage_error")
        }
        mutableState.value = mutableState.value.copy(lastAttemptAt = now)
        NexLog.i(TAG, "source=${request.source} stage=request result=started")
    }

    private fun parseResponse(array: JSONArray): List<UpdateRelease> {
        val parsed = mutableListOf<Pair<ReleaseVersion, UpdateRelease>>()
        for (index in 0 until minOf(array.length(), MAX_RELEASES)) {
            val release = array.getJSONObject(index)
            val draft = release.opt("draft") as? Boolean ?: throw UpdateFailure("invalid_response")
            val prerelease = release.opt("prerelease") as? Boolean ?: throw UpdateFailure("invalid_response")
            if (draft || prerelease) continue
            val tag = string(release, "tag_name", 120, required = true)
            val version = ReleaseVersion.parse(tag) ?: continue
            val publishedAt = string(release, "published_at", 40, required = true)
            validateDate(publishedAt)
            val notes = ReleaseNotes.parse(string(release, "body", MAX_RESPONSE_BYTES))
            parsed += version to UpdateRelease(
                tag = tag,
                name = string(release, "name", 200).ifBlank { "NexBox ${version.display}" },
                version = version.display,
                publishedAt = publishedAt,
                url = releaseUrl(tag),
                notesZh = notes.first,
                notesEn = notes.second
            )
        }
        return parsed.sortedWith { left, right -> right.first.compareTo(left.first) }
            .distinctBy { it.first.identity }.take(MAX_RELEASES).map { it.second }
    }

    private fun load(): UpdateState {
        val values = try {
            preferences.all
        } catch (failure: RuntimeException) {
            NexLog.w(TAG, "stage=loadCache result=failed error=storage_error type=${failure.javaClass.simpleName}")
            return UpdateState(errorCode = "storage_error")
        }
        var error = values[KEY_ERROR] as? String
        val releases = try {
            val cached = values[KEY_RELEASES] as? String
            if (cached.isNullOrBlank()) emptyList() else {
                if (cached.length > MAX_RESPONSE_BYTES) throw UpdateFailure("invalid_response")
                val array = JSONArray(cached)
                val result = mutableListOf<Pair<ReleaseVersion, UpdateRelease>>()
                for (index in 0 until minOf(array.length(), MAX_RELEASES)) {
                    val release = array.getJSONObject(index)
                    val tag = string(release, "tag", 120, required = true)
                    val version = ReleaseVersion.parse(tag) ?: throw UpdateFailure("invalid_response")
                    val publishedAt = string(release, "publishedAt", 40, required = true)
                    validateDate(publishedAt)
                    result += version to UpdateRelease(
                        tag, string(release, "name", 200), version.display, publishedAt, releaseUrl(tag),
                        string(release, "notesZh", 24_000), string(release, "notesEn", 24_000)
                    )
                }
                result.sortedWith { left, right -> right.first.compareTo(left.first) }
                    .distinctBy { it.first.identity }.map { it.second }
            }
        } catch (failure: JSONException) {
            error = "invalid_response"
            NexLog.w(TAG, "stage=loadCache result=failed error=invalid_response")
            emptyList()
        } catch (failure: UpdateFailure) {
            error = failure.code
            NexLog.w(TAG, "stage=loadCache result=failed error=${failure.code}")
            emptyList()
        }
        val checkedAt = (values[KEY_LAST_CHECKED] as? Long ?: 0).coerceAtLeast(0)
        if (currentVersion == null && checkedAt > 0) error = "current_version_invalid"
        val newest = releases.firstOrNull()?.version?.let(ReleaseVersion::parse)
        return UpdateState(
            autoCheckEnabled = values[KEY_AUTO_CHECK] as? Boolean ?: true,
            lastCheckedAt = checkedAt,
            lastAttemptAt = (values[KEY_LAST_ATTEMPT] as? Long ?: 0).coerceAtLeast(0),
            releases = releases,
            updateAvailable = error == null && checkedAt > 0 && newest != null && currentVersion != null && newest > currentVersion,
            errorCode = error
        )
    }

    private fun toJson(releases: List<UpdateRelease>): JSONArray = JSONArray().apply {
        releases.forEach { release ->
            put(JSONObject().put("tag", release.tag).put("name", release.name)
                .put("publishedAt", release.publishedAt).put("notesZh", release.notesZh).put("notesEn", release.notesEn))
        }
    }

    private fun string(value: JSONObject, key: String, maxLength: Int, required: Boolean = false): String {
        val raw = value.opt(key)
        if (raw == null || raw == JSONObject.NULL) {
            if (required) throw UpdateFailure("invalid_response")
            return ""
        }
        if (raw !is String || raw.length > maxLength || required && raw.isBlank()) throw UpdateFailure("invalid_response")
        return raw
    }

    private fun validateDate(value: String) {
        try {
            Instant.parse(value)
        } catch (failure: DateTimeParseException) {
            throw UpdateFailure("invalid_response")
        }
    }

    private fun releaseUrl(tag: String): String =
        "$RELEASES_URL/tag/${URLEncoder.encode(tag, Charsets.UTF_8.name()).replace("+", "%20")}"

    private class UpdateFailure(val code: String) : IOException(code)

    private class Request(val source: String) {
        val result = CompletableDeferred<UpdateState>()
        val finished = CompletableDeferred<Unit>()
        var job: Job? = null
        var waiters: Int = 0
        @Volatile var cancelled = false
    }

    companion object {
        const val PROJECT_URL = "https://github.com/GeneralPeople1970/NexBox"
        const val RELEASES_URL = "$PROJECT_URL/releases"
        private const val API_ENDPOINT = "https://api.github.com/repos/GeneralPeople1970/NexBox/releases?per_page=30"
        private const val TAG = "Updates"
        private const val PREFERENCES_NAME = "app_updates"
        private const val KEY_AUTO_CHECK = "auto_check_enabled"
        private const val KEY_LAST_CHECKED = "last_checked_at"
        private const val KEY_LAST_ATTEMPT = "last_attempt_at"
        private const val KEY_RELEASES = "releases"
        private const val KEY_ERROR = "last_error"
        private const val MAX_RELEASES = 30
        private const val MAX_RESPONSE_BYTES = 3 * 1024 * 1024
        private const val CONNECTION_TIMEOUT_MS = 10_000
        private const val REQUEST_TIMEOUT_MS = 25_000L
        private const val AUTOMATIC_INTERVAL_MS = 24 * 60 * 60 * 1000L
        @Volatile private var instance: UpdateRepository? = null

        @JvmStatic
        fun get(context: Context): UpdateRepository = instance ?: synchronized(this) {
            instance ?: UpdateRepository(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
