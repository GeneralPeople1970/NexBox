package com.nexbox.core.core.system.xposed

import android.content.pm.ApplicationInfo
import com.nexbox.core.xposed.ModuleMetadata

/** Unsupported/disabled module declarations must not become ordinary scope targets. */
class XposedModulePolicy private constructor() {
    companion object {
        @JvmStatic
        fun isCandidate(info: ApplicationInfo?): Boolean {
            if (info == null) return false
            return hasLegacyDeclaration(info) || ModuleMetadata.hasModernEntry(info.sourceDir)
        }

        @JvmStatic
        fun hasLegacyDeclaration(info: ApplicationInfo): Boolean = info.metaData?.let {
            it.containsKey("xposedmodule") || it.containsKey("xposedminversion")
        } == true

        /** Only an explicit static declaration can make a module (including itself) a target. */
        @JvmStatic
        fun allowsTarget(modulePackage: String, targetPackage: String, targetIsModule: Boolean,
                         staticScope: Boolean, declaredScope: Collection<String>): Boolean =
            if (staticScope) targetPackage in declaredScope
            else targetPackage != modulePackage && !targetIsModule
    }
}
