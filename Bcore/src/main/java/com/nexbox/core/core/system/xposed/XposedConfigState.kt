package com.nexbox.core.core.system.xposed

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Collections
import java.util.TreeMap
import java.util.TreeSet
import java.util.regex.Pattern

/** Immutable, Android-independent configuration; deliberately contains no executable paths. */
class XposedConfigState private constructor(
    @JvmField val revision: Long,
    @JvmField val legacyMigrated: Boolean,
    pending: Collection<String>,
    users: Map<Int, Map<String, ModuleConfig>>
) {
    // Do not use data classes: issued module services rely on ModuleConfig object identity.
    class ModuleConfig(enabled: Boolean, scope: Collection<String?>?) {
        @JvmField val enabled: Boolean
        @JvmField val scope: Set<String>

        init {
            val targets = TreeSet<String>()
            if (scope != null) {
                for (target in scope) {
                    requirePackage(target)
                    targets.add(target!!)
                }
            }
            if (targets.size > MAX_ITEMS) throw IllegalArgumentException("Too many targets")
            this.scope = Collections.unmodifiableSet(targets)
            this.enabled = enabled && targets.isNotEmpty()
        }
    }

    @JvmField val pendingLegacy: Set<String> = Collections.unmodifiableSet(TreeSet(pending))
    private val users: Map<Int, Map<String, ModuleConfig>>

    init {
        val copy = TreeMap<Int, Map<String, ModuleConfig>>()
        for ((userId, modules) in users) {
            copy[userId] = Collections.unmodifiableMap(TreeMap(modules))
        }
        this.users = Collections.unmodifiableMap(copy)
    }

    fun modules(userId: Int): Map<String, ModuleConfig> {
        requireUser(userId)
        return users[userId] ?: Collections.emptyMap()
    }

    fun withModule(userId: Int, module: String?, enabled: Boolean, scope: Collection<String?>?): XposedConfigState {
        requireUser(userId)
        requirePackage(module)
        val moduleName = module!!
        val next = mutableUsers()
        val entries = next.getOrPut(userId) { TreeMap() }
        entries[moduleName] = ModuleConfig(enabled, scope)
        val pending = TreeSet(pendingLegacy)
        pending.remove(moduleName)
        return XposedConfigState(revision + 1, legacyMigrated, pending, next)
    }

    fun migrateLegacy(packages: Collection<String?>?): XposedConfigState {
        if (legacyMigrated) return this
        val pending = TreeSet<String>()
        // Match the Java boundary: null is ignored only after migration has already completed.
        for (name in packages ?: throw NullPointerException()) if (isPackage(name)) pending.add(name!!)
        // Old global switches are candidates only. They do not grant any per-user scope.
        return XposedConfigState(revision + 1, true, pending, users)
    }

    fun removePackage(userId: Int, packageName: String?, allUsers: Boolean): XposedConfigState {
        requirePackage(packageName)
        if (!allUsers) requireUser(userId)
        val name = packageName!!
        val next = mutableUsers()
        for ((id, modules) in next) {
            if (!allUsers && id != userId) continue
            modules.remove(name)
            for (module in modules.entries) {
                if (module.value.scope.contains(name)) {
                    val scope = TreeSet(module.value.scope)
                    scope.remove(name)
                    module.setValue(ModuleConfig(module.value.enabled, scope))
                }
            }
        }
        val pending = TreeSet(pendingLegacy)
        if (allUsers) pending.remove(name)
        return XposedConfigState(revision + 1, legacyMigrated, pending, next)
    }

    fun removeUser(userId: Int): XposedConfigState {
        requireUser(userId)
        val next = mutableUsers()
        next.remove(userId)
        return XposedConfigState(revision + 1, legacyMigrated, pendingLegacy, next)
    }

    private fun mutableUsers(): MutableMap<Int, MutableMap<String, ModuleConfig>> {
        val result = TreeMap<Int, MutableMap<String, ModuleConfig>>()
        for ((id, modules) in users) result[id] = TreeMap(modules)
        return result
    }

    @Throws(IOException::class)
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeLong(revision)
        output.writeBoolean(legacyMigrated)
        writeNames(output, pendingLegacy)
        output.writeInt(users.size)
        for ((userId, modules) in users) {
            output.writeInt(userId)
            output.writeInt(modules.size)
            for ((name, module) in modules) {
                output.writeUTF(name)
                output.writeBoolean(module.enabled)
                writeNames(output, module.scope)
            }
        }
        output.flush()
        if (bytes.size() > MAX_BYTES) throw IOException("Xposed config is too large")
        return bytes.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x4e585053
        private const val VERSION = 1
        const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_ITEMS = 10000
        private val PACKAGE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
        private val LEGACY_LINES = Pattern.compile("\\r?\\n")

        @JvmStatic
        fun empty(): XposedConfigState = XposedConfigState(0, false, emptySet(), emptyMap())

        @JvmStatic
        @Throws(IOException::class)
        fun decode(bytes: ByteArray?): XposedConfigState {
            if (bytes == null || bytes.size > MAX_BYTES) throw IOException("Invalid config size")
            try {
                val input = DataInputStream(ByteArrayInputStream(bytes))
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Unknown config format")
                val revision = input.readLong()
                if (revision < 0) throw IOException("Invalid revision")
                val migrated = input.readBoolean()
                val pending = readNames(input)
                val users = TreeMap<Int, Map<String, ModuleConfig>>()
                val userCount = count(input)
                repeat(userCount) {
                    val userId = input.readInt()
                    requireUser(userId)
                    val modules = TreeMap<String, ModuleConfig>()
                    val moduleCount = count(input)
                    repeat(moduleCount) {
                        val name = input.readUTF()
                        requirePackage(name)
                        val enabled = input.readBoolean()
                        if (modules.put(name, ModuleConfig(enabled, readNames(input))) != null) {
                            throw IOException("Duplicate module")
                        }
                    }
                    if (users.put(userId, modules) != null) throw IOException("Duplicate user")
                }
                if (input.available() != 0) throw IOException("Unexpected trailing config bytes")
                return XposedConfigState(revision, migrated, pending, users)
            } catch (error: IllegalArgumentException) {
                throw IOException("Invalid Xposed config", error)
            }
        }

        @Throws(IOException::class)
        private fun writeNames(output: DataOutputStream, names: Collection<String>) {
            output.writeInt(names.size)
            for (name in names) output.writeUTF(name)
        }

        @Throws(IOException::class)
        private fun readNames(input: DataInputStream): Set<String> {
            val count = count(input)
            val names = TreeSet<String>()
            repeat(count) {
                val name = input.readUTF()
                requirePackage(name)
                if (!names.add(name)) throw IOException("Duplicate package")
            }
            return names
        }

        @Throws(IOException::class)
        private fun count(input: DataInputStream): Int {
            val count = input.readInt()
            if (count < 0 || count > MAX_ITEMS) throw IOException("Invalid item count")
            return count
        }

        @JvmStatic
        fun parseLegacy(text: String?): Set<String> {
            val result = TreeSet<String>()
            if (text == null) return result
            for (line in LEGACY_LINES.split(text)) {
                // Java String.trim() removes <= U+0020, not every Unicode whitespace character.
                val name = line.trim { it <= ' ' }
                if (isPackage(name)) result.add(name)
            }
            return result
        }

        @JvmStatic
        fun isPackage(name: String?): Boolean =
            name != null && name.length <= 255 && PACKAGE_NAME.matcher(name).matches()

        @JvmStatic
        fun requirePackage(name: String?) {
            if (!isPackage(name)) throw IllegalArgumentException("Invalid package name")
        }

        @JvmStatic
        fun requireUser(userId: Int) {
            if (userId < 0) throw IllegalArgumentException("A concrete virtual user is required")
        }
    }
}
