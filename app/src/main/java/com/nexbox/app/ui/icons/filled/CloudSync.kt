/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Repackaged for NexBox from AndroidX 1.7.8; icon implementation unchanged.
package com.nexbox.app.ui.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.CloudSync: ImageVector
    get() {
        if (_cloudSync != null) {
            return _cloudSync!!
        }
        _cloudSync = materialIcon(name = "Filled.CloudSync") {
            materialPath {
                moveTo(21.5f, 14.98f)
                curveToRelative(-0.02f, 0.0f, -0.03f, 0.0f, -0.05f, 0.01f)
                curveTo(21.2f, 13.3f, 19.76f, 12.0f, 18.0f, 12.0f)
                curveToRelative(-1.4f, 0.0f, -2.6f, 0.83f, -3.16f, 2.02f)
                curveTo(13.26f, 14.1f, 12.0f, 15.4f, 12.0f, 17.0f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                lineToRelative(6.5f, -0.02f)
                curveToRelative(1.38f, 0.0f, 2.5f, -1.12f, 2.5f, -2.5f)
                reflectiveCurveTo(22.88f, 14.98f, 21.5f, 14.98f)
                close()
                moveTo(10.0f, 4.26f)
                verticalLineToRelative(2.09f)
                curveTo(7.67f, 7.18f, 6.0f, 9.39f, 6.0f, 12.0f)
                curveToRelative(0.0f, 1.77f, 0.78f, 3.34f, 2.0f, 4.44f)
                verticalLineTo(14.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.73f)
                curveTo(5.06f, 16.54f, 4.0f, 14.4f, 4.0f, 12.0f)
                curveTo(4.0f, 8.27f, 6.55f, 5.15f, 10.0f, 4.26f)
                close()
                moveTo(20.0f, 6.0f)
                horizontalLineToRelative(-2.73f)
                curveToRelative(1.43f, 1.26f, 2.41f, 3.01f, 2.66f, 5.0f)
                lineToRelative(-2.02f, 0.0f)
                curveTo(17.68f, 9.64f, 16.98f, 8.45f, 16.0f, 7.56f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(6.0f)
                close()
            }
        }
        return _cloudSync!!
    }

private var _cloudSync: ImageVector? = null
