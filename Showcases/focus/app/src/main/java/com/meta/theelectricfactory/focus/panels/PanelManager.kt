// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.meta.theelectricfactory.focus.panels

import androidx.compose.runtime.Composable
import com.meta.spatial.compose.composePanel
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.theelectricfactory.focus.MainActivity
import com.meta.theelectricfactory.focus.R
import com.meta.theelectricfactory.focus.utils.FOCUS_DP

object PanelRegistrationIds {
    const val HomePanel = 20
    const val AIPanel = 21
    const val TasksPanel = 22
    const val Toolbar = 23
    const val StickySubPanel = 24
    const val LabelSubPanel = 25
    const val ArrowSubPanel = 26
    const val BoardSubPanel = 27
    const val ShapesSubPanel = 28
    const val StickerSubPanel = 29
    const val TimerSubPanel = 30
}

fun panelRegistration(
    registrationId: Int,
    widthInMeters: Float,
    heightInMeters: Float,
    homePanel: Boolean = false,
    content: @Composable () -> Unit,
): PanelRegistration {
    return PanelRegistration(registrationId) { _ ->
        config {
            width = widthInMeters
            height = heightInMeters
            layoutWidthInDp = FOCUS_DP * width
            layerConfig = LayerConfig()
            enableTransparent = true
            includeGlass = false
            themeResourceId = R.style.Theme_Focus_Transparent
        }

        if (homePanel) {
            activityClass = MainActivity::class.java
        } else {
            composePanel { setContent { content() } }
        }
    }
}