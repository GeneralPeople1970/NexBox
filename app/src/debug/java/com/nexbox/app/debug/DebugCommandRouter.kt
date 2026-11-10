package com.nexbox.app.debug

import android.content.Context
import com.nexbox.app.debug.DebugCommandContract.Companion.APP
import com.nexbox.app.debug.DebugCommandContract.Companion.LOG_SELECTION
import com.nexbox.app.debug.DebugCommandContract.Companion.PROFILE
import com.nexbox.app.debug.DebugCommandContract.Companion.SIM
import com.nexbox.app.debug.DebugCommandContract.Companion.USER
import com.nexbox.app.debug.DebugCommandContract.Companion.words
import com.nexbox.app.debug.DebugCommandJson.jsonArray
import com.nexbox.app.util.NexLog
import com.nexbox.core.NexBoxCore
import org.json.JSONObject

internal class DebugCommandRouter(context: Context) {
    private val users = DebugUsersCommands()
    private val apps = DebugAppsCommands(context)
    private val gms = DebugGmsCommands()
    private val device = DebugDeviceCommands(context)
    private val location = DebugLocationCommands(context)
    private val modules = DebugModulesCommands(context)
    private val gallery = DebugGalleryCommands(context)
    private val runtime = DebugRuntimeCommands()
    private val appearance = DebugAppearanceCommands(context)
    private val updates = DebugUpdateCommands(context)
    private val logs = DebugLogsCommands(context)
    private val conditional = DebugConditionalCommands()
    private data class Route(val contract: DebugCommandContract, val run: (JSONObject) -> JSONObject)
    private val routes = linkedMapOf<String, Route>()

    init {
        route("help", "topic", services = false, description = "Lists current contracts; topic selects one command.", run = ::help)
        route("runtime.status", services = false, description = "Host build and service state, without waiting for core services.") { runtime.runtimeStatus() }
        route("config.retry-stops", "targets", "targets", mutation = true,
            description = "Retries only the captured stop batch; does not save, randomize or launch.", run = runtime::retryConfigurationStops)
        route("users.list") { users.usersList() }
        route("users.clones") { users.cloneGroups() }
        route("users.selected") { users.selectedUser() }
        route("users.select", USER, USER, mutation = true, run = users::selectUser)
        route("users.rename", "$USER name", "$USER name", mutation = true,
            description = "Saves the name and refreshes owned recent tasks; may stop this group if needed. Reports saving and refresh separately.",
            run = users::renameUser)
        route("users.delete", USER, USER, mutation = true, run = users::deleteUser)
        route("users.delete-if-inventory", "$USER expectedInventoryToken confirm requestId onlyIfEmpty",
            "$USER expectedInventoryToken confirm requestId", mutation = true, safety = "conditional-user-delete",
            description = "Compares raw inventory inside core deletion; partial failures preserve effects. No replay.", run = conditional::deleteUser)
        route("users.prune-empty", USER, USER, mutation = true, run = users::pruneEmptyUser)
        route("users.prune-all-empty", "confirm", "confirm", mutation = true, run = users::pruneAllEmptyUsers)
        route("apps.host-list", "query sort reverse showSystem limit", run = apps::hostApps)
        route("apps.raw-inventory", USER, USER, safety = "raw-package-inventory",
            description = "Raw installed flags including hidden/Google/unprojectable packages; tokens expire on restart, revision changes or after 10 minutes.", run = conditional::rawInventory)
        route("apps.clone-list", "$USER query sort reverse limit", description = "Includes sourceAvailable; missing source files do not remove clone identities. Sorting supports name (default), package_name or clone_order (the home grid's persisted order).", run = apps::cloneApps)
        route("apps.source-sync", mutation = true,
            description = "Synchronizes host-backed shared code across groups, preserving clone data; rejected packages return success=false.") { apps.syncHostSources() }
        route("apps.clone-check", "$APP path fromHost", run = apps::cloneCheck)
        route("apps.options-get", services = false) { apps.appsOptionsGet() }
        route("apps.options-set", "sort reverse showPackageName showSystemApps", any = "sort reverse showPackageName showSystemApps",
            services = false, mutation = true, run = apps::appsOptionsSet)
        route("apps.clone-host", "$APP newUser launch", "packageName", mutation = true,
            description = "An existing group requires userId/createTime; a new group requires newUser=true. New clones append to the home order; cloneOrderPersistence is asynchronous.") { apps.clone(it, host = true) }
        route("apps.clone-apk", "$USER path newUser launch", "path", mutation = true,
            description = "Device APK/APKS path; existing group requires userId/createTime. New clones append to the home order; cloneOrderPersistence is asynchronous.") { apps.clone(it, host = false) }
        route("apps.start", APP, APP, mutation = true, description = "Missing base/split files fail with source_unavailable before dispatch. requestAccepted does not confirm completed startup.", run = apps::startApp)
        route("apps.stop", APP, APP, mutation = true, run = apps::stopApp)
        route("apps.clear-data", APP, APP, mutation = true, run = apps::clearApp)
        route("apps.uninstall", APP, APP, mutation = true,
            description = "Removes the exact user's clone, clears its home-order entry and prunes the empty user. cloneOrderPersistence is asynchronous.", run = apps::uninstallApp)
        route("gms.list") { gms.gmsList() }
        route("gms.install", USER, USER, mutation = true,
            description = "Installs components only for the captured user generation; partial failures refresh shared state.", run = gms::gmsInstall)
        route("gms.uninstall", USER, USER, mutation = true,
            description = "Removes components for the captured user generation, including incomplete installations; success requires the complete target set to be absent.", run = gms::gmsUninstall)
        route("gms.reset", USER, USER, mutation = true, run = gms::gmsReset)
        route("gms.install-if-inventory", "$USER expectedInventoryToken requireTraceFree requestId",
            "$USER expectedInventoryToken requireTraceFree requestId", mutation = true, safety = "conditional-gms-install",
            description = "One core batch; separates created/existing components; retains partial work without replay or implicit rollback.", run = conditional::installGms)
        route("gms.reset-if-inventory", "$USER expectedInventoryToken expectedComponents confirm requestId",
            "$USER expectedInventoryToken expectedComponents confirm requestId", mutation = true, safety = "conditional-gms-reset",
            description = "Compares the full inventory and exact component instances before closing sessions, stopping and clearing.") { conditional.removeGms(it, true) }
        route("gms.uninstall-if-inventory", "$USER expectedInventoryToken expectedComponents confirm requestId",
            "$USER expectedInventoryToken expectedComponents confirm requestId", mutation = true, safety = "conditional-gms-uninstall",
            description = "Removes only the exact environment component set within the core lifecycle section.") { conditional.removeGms(it, false) }
        route("device.get", APP, "userId", run = device::deviceGet)
        route("device.save", "$APP profile $PROFILE", USER, "profile $PROFILE", mutation = true, run = device::deviceSave)
        route("device.randomize", "$APP part", USER, mutation = true,
            description = "all changes model and identifiers, preserving SIM; saves the result.", run = device::deviceRandomize)
        route("device.reset", APP, USER, mutation = true, run = device::deviceReset)
        route("device.scope-get", USER, "userId", run = device::deviceScopeGet)
        route("device.scope-set", "$USER packages", "$USER packages", mutation = true, run = device::deviceScopeSet)
        route("device.catalog-list", "query compatibleOnly offset limit", services = false, run = device::catalogList)
        route("device.catalog-select", "$APP catalogId", "$USER catalogId", mutation = true,
            description = "Saves a catalogue model in basic mode, preserving identifiers and SIM.", run = device::catalogSelect)
        route("runtime.esim-get") { device.deviceEsimGet() }
        route("runtime.esim-set", "enabled", "enabled", mutation = true, run = device::deviceEsimSet)
        alias("device.esim-get", "runtime.esim-get")
        alias("device.esim-set", "runtime.esim-set")
        route("sim.get", APP, "userId", run = device::simGet)
        route("sim.set", "$APP profile simProfile $SIM", USER, "profile simProfile $SIM", mutation = true, run = device::simSet)
        route("sim.randomize", APP, USER, mutation = true, run = device::simRandomize)
        route("sim.randomize-field", "$APP slot field", "$USER slot field", mutation = true, run = device::simRandomizeField)
        route("sim.set-count", "$APP count", "$USER count", mutation = true, run = device::simSetCount)
        route("sim.carriers", services = false) { device.simCarriers() }
        route("sim.reset", APP, USER, mutation = true, run = device::simReset)
        route("sim.enable", APP, USER, mutation = true) { device.simToggle(it, true) }
        route("sim.disable", APP, USER, mutation = true) { device.simToggle(it, false) }
        route("location.get", USER, "userId", run = location::locationGet)
        route("location.get-app", APP, "userId packageName", run = location::locationGetApp)
        route("location.apply", "$APP latitude longitude", "$USER latitude longitude", mutation = true) { location.locationApply(it, saveOnly = false) }
        route("location.save-point", "$APP latitude longitude", "$USER latitude longitude", mutation = true) { location.locationApply(it, saveOnly = true) }
        route("location.disable", APP, USER, mutation = true,
            description = "For an app, deselects scope while preserving its saved point and override.", run = location::locationDisable)
        route("location.reset-app", APP, APP, mutation = true,
            description = "Restores group inheritance without changing app scope.", run = location::locationResetApp)
        route("location.scope-get", USER, "userId", run = location::locationScopeGet)
        route("location.scope-set", "$USER packages", "$USER packages", mutation = true, run = location::locationScopeSet)
        route("location.rocker-get", APP, APP, run = location::rockerGet)
        route("location.rocker-move", "$APP angle distance", "$APP angle distance", mutation = true,
            description = "One live movement of 0..2.5 metres; requires enabled scope; does not stop apps.", run = location::rockerMove)
        route("location.map-source-get", services = false) { location.mapSourceGet() }
        route("location.map-source-set", "provider template attribution", "provider", services = false, mutation = true, run = location::mapSourceSet)
        route("location.current", services = false,
            description = "Requires user-granted location permission; available confirms a fresh fix, not cached/default display.") { location.currentLocation() }
        route("location.favorites-list", services = false) { location.favoritesList() }
        route("location.favorite-add", "name latitude longitude", "name latitude longitude", services = false, mutation = true, run = location::favoriteAdd)
        route("location.favorite-rename", "favoriteId name", "favoriteId name", services = false, mutation = true, run = location::favoriteRename)
        route("location.favorite-delete", "favoriteId confirm", "favoriteId confirm", services = false, mutation = true, run = location::favoriteDelete)
        route("location.favorite-create", "name latitude longitude requestId", "name latitude longitude requestId",
            services = false, mutation = true, safety = "favorite-create-only",
            description = "Shared favorite; coordinate conflict preserves the existing entry. Versions expire on host process restart.", run = location::favoriteCreate)
        route("location.favorite-rename-if-version", "favoriteId expectedVersion name requestId", "favoriteId expectedVersion name requestId",
            services = false, mutation = true, safety = "favorite-compare-rename", run = location::favoriteRenameIfVersion)
        route("location.favorite-delete-if-version", "favoriteId expectedVersion confirm requestId", "favoriteId expectedVersion confirm requestId",
            services = false, mutation = true, safety = "favorite-compare-delete", run = location::favoriteDeleteIfVersion)
        route("location.favorite-apply", "$APP favoriteId", "$USER favoriteId", mutation = true) { location.favoriteApply(it, saveOnly = false) }
        route("location.favorite-save-point", "$APP favoriteId", "$USER favoriteId", mutation = true) { location.favoriteApply(it, saveOnly = true) }
        route("modules.list", USER, "userId", run = modules::modulesList)
        route("modules.host-list", services = false) { modules.modulesHostList() }
        route("modules.list-all") { modules.modulesListAll() }
        route("modules.set", "$USER modulePackage enabled targets", "$USER modulePackage enabled targets", mutation = true,
            description = "Module targets require an explicit static-scope declaration, including self-targets.", run = modules::modulesSet)
        route("modules.stop-targets", "$USER targets", "$USER targets", mutation = true,
            description = "Stops the whole target batch; never launches applications.", run = modules::modulesRestart)
        alias("modules.restart", "modules.stop-targets")
        route("modules.open-config", "$USER modulePackage", "$USER modulePackage", mutation = true,
            description = "Resolves and dispatches the captured user's module management activity. requestAccepted does not confirm startup completion; stale user generations are rejected.", run = modules::modulesOpenConfig)
        route("modules.install-existing", "$USER modulePackage", "$USER modulePackage", mutation = true, run = modules::modulesInstallExisting)
        route("modules.import-host", "$USER modulePackage", "$USER modulePackage", mutation = true, run = modules::modulesImportHost)
        route("modules.import-apk", "$USER path", "$USER path", mutation = true, run = modules::modulesImportApk)
        route("gallery.list", "query type reverse limit", run = gallery::galleryList)
        route("gallery.rescan", mutation = true) { gallery.galleryRescan() }
        route("gallery.get", "mediaId", "mediaId", run = gallery::galleryGet)
        route("gallery.preview", "mediaId", "mediaId", mutation = true, run = gallery::galleryPreview)
        route("gallery.import", "path paths", any = "path paths", mutation = true, run = gallery::galleryImport)
        route("gallery.import-verified", "contentBase64 expectedLength expectedSha256 mimeType requestId",
            "contentBase64 expectedLength expectedSha256 mimeType requestId", mutation = true, safety = "media-import-verified-bytes",
            description = "One PNG, 1..65536 bytes, at most 1024x1024; requires guest quiescence. Publishes validated owned bytes.", run = gallery::importVerified)
        route("gallery.delete-if-version", "mediaId expectedVersion expectedOwnerToken expectedSha256 confirm requestId",
            "mediaId expectedVersion expectedOwnerToken expectedSha256 confirm requestId", mutation = true, safety = "media-compare-delete",
            description = "Requires guest quiescence, unexposed write capability and an exclusive kernel lease; otherwise busy/unavailable. Tokens expire on core restart.", run = gallery::deleteIfVersion)
        route("gallery.export-if-version", "mediaId expectedVersion expectedOwnerToken expectedSha256 confirmExternalWrite requestId",
            "mediaId expectedVersion expectedOwnerToken expectedSha256 confirmExternalWrite requestId", mutation = true, safety = "media-compare-export-snapshot",
            description = "Checks under the same quiescence/lease boundary as delete, then exports only a private byte snapshot. No replay.", run = gallery::exportIfVersion)
        route("gallery.delete", "mediaId mediaIds confirm", "confirm", "mediaId mediaIds", mutation = true, run = gallery::galleryDelete)
        route("gallery.export", "mediaId mediaIds", any = "mediaId mediaIds", mutation = true,
            description = "Publishes copies to the system gallery; does not delete the shared originals.", run = gallery::galleryExport)
        route("runtime.get", APP, services = false, run = runtime::runtimeGet)
        route("runtime.set", "$APP hideRoot autoFreeze instantFreezeButton freezeExcluded", any = "hideRoot autoFreeze instantFreezeButton freezeExcluded",
            mutation = true, run = runtime::runtimeSet)
        route("appearance.get", services = false) { appearance.get() }
        route("appearance.set", "uiStyle themeMode liquidGlassEnabled predictiveBackEnabled", any = "uiStyle themeMode liquidGlassEnabled predictiveBackEnabled",
            services = false, mutation = true, description = "Host appearance only; does not stop guests. Storage uses asynchronous apply.", run = appearance::set)
        route("updates.get", services = false,
            description = "Reads cached update state and project URLs without a network request; a previous state.errorCode is not a new command failure.") { updates.get() }
        route("updates.set", "autoCheckEnabled", "autoCheckEnabled", services = false, mutation = true,
            description = "Saves the host automatic update check preference, enabled by default. Enabling it in the foreground may trigger a due automatic check; persistence is asynchronous.", run = updates::set)
        route("updates.check", services = false, mutation = true,
            description = "One GitHub stable release request; concurrent checks share the active request. No download or installation. Fetch failure returns ok=false while preserving cached state.") { updates.check() }
        route("logs.get", services = false) { logs.logsGet() }
        route("logs.set", "recordingEnabled adbEnabled detailedEnabled debugEnabled cliEnabled", any = "recordingEnabled adbEnabled detailedEnabled debugEnabled cliEnabled",
            services = false, mutation = true,
            description = "detailedEnabled sets the capture, ADB and text-query floor: false accepts WARN/ERROR; true accepts VERBOSE and above.",
            run = logs::logsSet)
        route("logs.enable", services = false, mutation = true) { logs.logsSet(JSONObject().put("debugEnabled", true)) }
        route("logs.disable", services = false, mutation = true) { logs.logsSet(JSONObject().put("debugEnabled", false)) }
        route("logs.clear", services = false, mutation = true, description = "Clears app memory/history asynchronously, not system Logcat.") { logs.logsClear() }
        route("logs.list", "$LOG_SELECTION afterId captureSession", services = false, run = logs::logsList)
        route("logs.history", "$LOG_SELECTION stopAtLimit", services = false,
            description = "Queries sanitized historical records including multiline stacks; missing legacy identity never matches an identity filter.", run = logs::logsHistory)
        route("logs.export", "$LOG_SELECTION diagnostics", services = false, mutation = true,
            description = "Filtered snapshot or full diagnostics ZIP; diagnostics=true cannot be combined with filters.", run = logs::logsExport)
        route("logs.exits", "process pid sinceMillis untilMillis limit", services = false,
            description = "OS exit reasons for this host UID; not proof of guest startup or a full tombstone.", run = logs::logsExits)
    }

    private fun route(name: String, parameters: String = "", required: String = "", any: String = "",
                      services: Boolean = true, mutation: Boolean = false, description: String = "",
                      safety: String? = null,
                      run: (JSONObject) -> JSONObject) {
        check(name !in routes)
        routes[name] = Route(DebugCommandContract(words(parameters), words(required), words(any), services, mutation, description,
            safetyContract = safety), run)
    }

    private fun alias(name: String, target: String) {
        val route = routes.getValue(target)
        routes[name] = Route(route.contract.copy(aliasFor = target), route.run)
    }

    fun execute(command: String, payload: JSONObject, requestId: String): JSONObject {
        val route = requireNotNull(routes[command]) { "Unknown debug command; use help" }
        val conditionalWrite = isConditionalMutation(command)
        try {
            route.contract.validate(payload)
        } catch (failure: Exception) {
            if (!conditionalWrite) throw failure
            return DebugConditionalCommands.json(com.nexbox.core.utils.MutationReceipt(requestId).failure("invalid_request"))
        }
        // Inspection never injects its own records into the snapshot or repopulates a cleared log.
        if (command in QUIET_COMMANDS) return route.run(payload)
        return NexLog.trace("DebugCLI", command, logFields(command, payload, requestId)) { operation ->
            var invoked = false
            try {
                if (route.contract.requiresServices || (command == "runtime.get" && !payload.isNull("packageName"))) {
                    operation.step("waitServices")
                    check(NexBoxCore.get().waitForServicesAvailable(10_000L)) { "NexBox services are unavailable" }
                }
                invoked = true
                val result = route.run(payload)
                val failure = debugResultError(result)
                val dispatch = result.has("requestAccepted") || result.optBoolean("launchRequested")
                operation.finish(failure == null, "outcome=${failure?.code ?: if (dispatch) "request_dispatched" else "completed"}")
                result
            } catch (failure: Throwable) {
                // Finish before trace's fallback handler can log a raw nested JSON exception.
                operation.fail(DebugCommandJson.loggableFailure(failure))
                if (!conditionalWrite) throw failure
                val receipt = com.nexbox.core.utils.MutationReceipt(requestId)
                if (invoked) receipt.begin("dispatch")
                DebugConditionalCommands.json(receipt.failure(if (invoked) "response_unavailable" else "service_unavailable"))
            }
        }
    }

    fun isConditionalMutation(command: String): Boolean = routes[command]?.contract?.let {
        it.safetyContract != null && it.mutation
    } == true

    private fun logFields(command: String, payload: JSONObject, requestId: String): String = buildList {
        add("command=$command requestId=$requestId")
        if (payload.has("userId")) add("userId=${payload.getInt("userId")}")
        if (!payload.isNull("packageName")) add("package=${DebugCommandJson.requiredPackage(payload)}")
        if (payload.has("modulePackage")) add("module=${DebugCommandJson.packageValue(payload, "modulePackage")}")
        if (payload.has("createTime")) add("generation=${payload.getLong("createTime")}")
    }.joinToString(" ")

    private fun help(payload: JSONObject): JSONObject {
        val topic = payload.optString("topic").trim()
        val selected = if (topic.isEmpty()) routes else mapOf(topic to requireNotNull(routes[topic]) { "Unknown help topic" })
        return JSONObject().put("contractVersion", 2).put("commands", jsonArray(selected.keys))
            .put("contracts", JSONObject().apply { selected.forEach { (name, route) -> put(name, route.contract.json()) } })
            .put("boundaries", jsonArray(listOf(
                "Debug callers must be adb shell, root, or the local host main process; a shared app UID alone is not sufficient.",
                "User-targeted mutations require the current userId and createTime.",
                "System permissions, account credentials and UI picker grants are never bypassed.",
                "Unknown or removed commands/parameters are rejected before any mutation.",
                "Launch receipts acknowledge dispatch only; false results use ok=false.",
                "Legacy gallery share, CARTO, firmware templates and SIM roaming are not supported."
            )))
    }

    private companion object {
        val QUIET_COMMANDS = setOf("help", "runtime.status", "updates.get", "logs.get", "logs.list", "logs.history", "logs.export", "logs.exits", "logs.clear")
    }
}
