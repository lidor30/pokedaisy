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

package com.pokedaisy.app.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathFillType.Companion.EvenOdd
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Material icons the top-screen Library / Settings draw that aren't in Compose's small core set
 * (material-icons-core, which material3 brings): copied from androidx.compose.material:material-icons-extended
 * 1.7.8, the same paths, so the app doesn't ship that artifact (11.9 MB of dex for these 16; release builds
 * aren't shrunk). Core icons (Add, Delete, Edit, Search, ...) are still used from `Icons.Filled`.
 */
object LibraryIcons {
    val HelpOutline: ImageVector by lazy {
        icon("AutoMirrored.Filled.HelpOutline", autoMirror = true) {
        materialPath {
            moveTo(11.0f, 18.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(12.0f, 2.0f)
            curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
            reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
            reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(12.0f, 20.0f)
            curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
            reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
            reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
            reflectiveCurveToRelative(-3.59f, 8.0f, -8.0f, 8.0f)
            close()
            moveTo(12.0f, 6.0f)
            curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
            horizontalLineToRelative(2.0f)
            curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
            reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
            curveToRelative(0.0f, 2.0f, -3.0f, 1.75f, -3.0f, 5.0f)
            horizontalLineToRelative(2.0f)
            curveToRelative(0.0f, -2.25f, 3.0f, -2.5f, 3.0f, -5.0f)
            curveToRelative(0.0f, -2.21f, -1.79f, -4.0f, -4.0f, -4.0f)
            close()
        }
        }
    }

    val Sort: ImageVector by lazy {
        icon("AutoMirrored.Filled.Sort", autoMirror = true) {
        materialPath {
            moveTo(3.0f, 18.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(-2.0f)
            lineTo(3.0f, 16.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(3.0f, 6.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(18.0f)
            lineTo(21.0f, 6.0f)
            lineTo(3.0f, 6.0f)
            close()
            moveTo(3.0f, 13.0f)
            horizontalLineToRelative(12.0f)
            verticalLineToRelative(-2.0f)
            lineTo(3.0f, 11.0f)
            verticalLineToRelative(2.0f)
            close()
        }
        }
    }

    val ViewList: ImageVector by lazy {
        icon("AutoMirrored.Filled.ViewList", autoMirror = true) {
        materialPath {
            moveTo(3.0f, 14.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineTo(3.0f)
            verticalLineTo(14.0f)
            close()
            moveTo(3.0f, 19.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineTo(3.0f)
            verticalLineTo(19.0f)
            close()
            moveTo(3.0f, 9.0f)
            horizontalLineToRelative(4.0f)
            verticalLineTo(5.0f)
            horizontalLineTo(3.0f)
            verticalLineTo(9.0f)
            close()
            moveTo(8.0f, 14.0f)
            horizontalLineToRelative(13.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineTo(8.0f)
            verticalLineTo(14.0f)
            close()
            moveTo(8.0f, 19.0f)
            horizontalLineToRelative(13.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineTo(8.0f)
            verticalLineTo(19.0f)
            close()
            moveTo(8.0f, 5.0f)
            verticalLineToRelative(4.0f)
            horizontalLineToRelative(13.0f)
            verticalLineTo(5.0f)
            horizontalLineTo(8.0f)
            close()
        }
        }
    }

    val Code: ImageVector by lazy {
        icon("Filled.Code", autoMirror = false) {
        materialPath {
            moveTo(9.4f, 16.6f)
            lineTo(4.8f, 12.0f)
            lineToRelative(4.6f, -4.6f)
            lineTo(8.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(1.4f, -1.4f)
            close()
            moveTo(14.6f, 16.6f)
            lineToRelative(4.6f, -4.6f)
            lineToRelative(-4.6f, -4.6f)
            lineTo(16.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(-1.4f, -1.4f)
            close()
        }
        }
    }

    val GridView: ImageVector by lazy {
        icon("Filled.GridView", autoMirror = false) {
        materialPath(pathFillType = EvenOdd) {
            moveTo(3.0f, 3.0f)
            verticalLineToRelative(8.0f)
            horizontalLineToRelative(8.0f)
            lineTo(11.0f, 3.0f)
            lineTo(3.0f, 3.0f)
            close()
            moveTo(9.0f, 9.0f)
            lineTo(5.0f, 9.0f)
            lineTo(5.0f, 5.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(4.0f)
            close()
            moveTo(3.0f, 13.0f)
            verticalLineToRelative(8.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(-8.0f)
            lineTo(3.0f, 13.0f)
            close()
            moveTo(9.0f, 19.0f)
            lineTo(5.0f, 19.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(4.0f)
            close()
            moveTo(13.0f, 3.0f)
            verticalLineToRelative(8.0f)
            horizontalLineToRelative(8.0f)
            lineTo(21.0f, 3.0f)
            horizontalLineToRelative(-8.0f)
            close()
            moveTo(19.0f, 9.0f)
            horizontalLineToRelative(-4.0f)
            lineTo(15.0f, 5.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(4.0f)
            close()
            moveTo(13.0f, 13.0f)
            verticalLineToRelative(8.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(-8.0f)
            horizontalLineToRelative(-8.0f)
            close()
            moveTo(19.0f, 19.0f)
            horizontalLineToRelative(-4.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(4.0f)
            close()
        }
        }
    }

    val Link: ImageVector by lazy {
        icon("Filled.Link", autoMirror = false) {
        materialPath {
            moveTo(3.9f, 12.0f)
            curveToRelative(0.0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
            horizontalLineToRelative(4.0f)
            lineTo(11.0f, 7.0f)
            lineTo(7.0f, 7.0f)
            curveToRelative(-2.76f, 0.0f, -5.0f, 2.24f, -5.0f, 5.0f)
            reflectiveCurveToRelative(2.24f, 5.0f, 5.0f, 5.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(-1.9f)
            lineTo(7.0f, 15.1f)
            curveToRelative(-1.71f, 0.0f, -3.1f, -1.39f, -3.1f, -3.1f)
            close()
            moveTo(8.0f, 13.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(-2.0f)
            lineTo(8.0f, 11.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(17.0f, 7.0f)
            horizontalLineToRelative(-4.0f)
            verticalLineToRelative(1.9f)
            horizontalLineToRelative(4.0f)
            curveToRelative(1.71f, 0.0f, 3.1f, 1.39f, 3.1f, 3.1f)
            reflectiveCurveToRelative(-1.39f, 3.1f, -3.1f, 3.1f)
            horizontalLineToRelative(-4.0f)
            lineTo(13.0f, 17.0f)
            horizontalLineToRelative(4.0f)
            curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
            reflectiveCurveToRelative(-2.24f, -5.0f, -5.0f, -5.0f)
            close()
        }
        }
    }

    val History: ImageVector by lazy {
        icon("Filled.History", autoMirror = false) {
        materialPath {
            moveTo(13.0f, 3.0f)
            curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
            lineTo(1.0f, 12.0f)
            lineToRelative(3.89f, 3.89f)
            lineToRelative(0.07f, 0.14f)
            lineTo(9.0f, 12.0f)
            lineTo(6.0f, 12.0f)
            curveToRelative(0.0f, -3.87f, 3.13f, -7.0f, 7.0f, -7.0f)
            reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
            reflectiveCurveToRelative(-3.13f, 7.0f, -7.0f, 7.0f)
            curveToRelative(-1.93f, 0.0f, -3.68f, -0.79f, -4.94f, -2.06f)
            lineToRelative(-1.42f, 1.42f)
            curveTo(8.27f, 19.99f, 10.51f, 21.0f, 13.0f, 21.0f)
            curveToRelative(4.97f, 0.0f, 9.0f, -4.03f, 9.0f, -9.0f)
            reflectiveCurveToRelative(-4.03f, -9.0f, -9.0f, -9.0f)
            close()
            moveTo(12.0f, 8.0f)
            verticalLineToRelative(5.0f)
            lineToRelative(4.28f, 2.54f)
            lineToRelative(0.72f, -1.21f)
            lineToRelative(-3.5f, -2.08f)
            lineTo(13.5f, 8.0f)
            lineTo(12.0f, 8.0f)
            close()
        }
        }
    }

    val StarBorder: ImageVector by lazy {
        icon("Filled.StarBorder", autoMirror = false) {
        materialPath {
            moveTo(22.0f, 9.24f)
            lineToRelative(-7.19f, -0.62f)
            lineTo(12.0f, 2.0f)
            lineTo(9.19f, 8.63f)
            lineTo(2.0f, 9.24f)
            lineToRelative(5.46f, 4.73f)
            lineTo(5.82f, 21.0f)
            lineTo(12.0f, 17.27f)
            lineTo(18.18f, 21.0f)
            lineToRelative(-1.63f, -7.03f)
            lineTo(22.0f, 9.24f)
            close()
            moveTo(12.0f, 15.4f)
            lineToRelative(-3.76f, 2.27f)
            lineToRelative(1.0f, -4.28f)
            lineToRelative(-3.32f, -2.88f)
            lineToRelative(4.38f, -0.38f)
            lineTo(12.0f, 6.1f)
            lineToRelative(1.71f, 4.04f)
            lineToRelative(4.38f, 0.38f)
            lineToRelative(-3.32f, 2.88f)
            lineToRelative(1.0f, 4.28f)
            lineTo(12.0f, 15.4f)
            close()
        }
        }
    }

    val FileOpen: ImageVector by lazy {
        icon("Filled.FileOpen", autoMirror = false) {
        materialPath {
            moveTo(14.0f, 2.0f)
            horizontalLineTo(6.0f)
            curveTo(4.9f, 2.0f, 4.0f, 2.9f, 4.0f, 4.0f)
            verticalLineToRelative(16.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
            horizontalLineTo(15.0f)
            verticalLineToRelative(-8.0f)
            horizontalLineToRelative(5.0f)
            verticalLineTo(8.0f)
            lineTo(14.0f, 2.0f)
            close()
            moveTo(13.0f, 9.0f)
            verticalLineTo(3.5f)
            lineTo(18.5f, 9.0f)
            horizontalLineTo(13.0f)
            close()
            moveTo(17.0f, 21.66f)
            verticalLineTo(16.0f)
            horizontalLineToRelative(5.66f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-2.24f)
            lineToRelative(2.95f, 2.95f)
            lineToRelative(-1.41f, 1.41f)
            lineTo(19.0f, 19.41f)
            lineToRelative(0.0f, 2.24f)
            horizontalLineTo(17.0f)
            close()
        }
        }
    }

    val Folder: ImageVector by lazy {
        icon("Filled.Folder", autoMirror = false) {
        materialPath {
            moveTo(10.0f, 4.0f)
            horizontalLineTo(4.0f)
            curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
            lineTo(2.0f, 18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            horizontalLineToRelative(-8.0f)
            lineToRelative(-2.0f, -2.0f)
            close()
        }
        }
    }

    val FolderOff: ImageVector by lazy {
        icon("Filled.FolderOff", autoMirror = false) {
        materialPath {
            moveTo(20.0f, 6.0f)
            horizontalLineToRelative(-8.0f)
            lineToRelative(-2.0f, -2.0f)
            horizontalLineTo(6.83f)
            lineToRelative(14.93f, 14.93f)
            curveTo(21.91f, 18.65f, 22.0f, 18.34f, 22.0f, 18.0f)
            verticalLineTo(8.0f)
            curveTo(22.0f, 6.9f, 21.1f, 6.0f, 20.0f, 6.0f)
            close()
        }
        materialPath {
            moveTo(2.1f, 2.1f)
            lineTo(0.69f, 3.51f)
            lineToRelative(1.56f, 1.56f)
            curveTo(2.1f, 5.35f, 2.01f, 5.66f, 2.01f, 6.0f)
            lineTo(2.0f, 18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(13.17f)
            lineToRelative(3.31f, 3.31f)
            lineToRelative(1.41f, -1.41f)
            lineTo(2.1f, 2.1f)
            close()
        }
        }
    }

    val Image: ImageVector by lazy {
        icon("Filled.Image", autoMirror = false) {
        materialPath {
            moveTo(21.0f, 19.0f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            horizontalLineTo(5.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            close()
            moveTo(8.5f, 13.5f)
            lineToRelative(2.5f, 3.01f)
            lineTo(14.5f, 12.0f)
            lineToRelative(4.5f, 6.0f)
            horizontalLineTo(5.0f)
            lineToRelative(3.5f, -4.5f)
            close()
        }
        }
    }

    val Inventory2: ImageVector by lazy {
        icon("Filled.Inventory2", autoMirror = false) {
        materialPath {
            moveTo(20.0f, 2.0f)
            horizontalLineTo(4.0f)
            curveTo(3.0f, 2.0f, 2.0f, 2.9f, 2.0f, 4.0f)
            verticalLineToRelative(3.01f)
            curveTo(2.0f, 7.73f, 2.43f, 8.35f, 3.0f, 8.7f)
            verticalLineTo(20.0f)
            curveToRelative(0.0f, 1.1f, 1.1f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(0.9f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(8.7f)
            curveToRelative(0.57f, -0.35f, 1.0f, -0.97f, 1.0f, -1.69f)
            verticalLineTo(4.0f)
            curveTo(22.0f, 2.9f, 21.0f, 2.0f, 20.0f, 2.0f)
            close()
            moveTo(15.0f, 14.0f)
            horizontalLineTo(9.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(6.0f)
            verticalLineTo(14.0f)
            close()
            moveTo(20.0f, 7.0f)
            horizontalLineTo(4.0f)
            verticalLineTo(4.0f)
            horizontalLineToRelative(16.0f)
            verticalLineTo(7.0f)
            close()
        }
        }
    }

    val Save: ImageVector by lazy {
        icon("Filled.Save", autoMirror = false) {
        materialPath {
            moveTo(17.0f, 3.0f)
            lineTo(5.0f, 3.0f)
            curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 7.0f)
            lineToRelative(-4.0f, -4.0f)
            close()
            moveTo(12.0f, 19.0f)
            curveToRelative(-1.66f, 0.0f, -3.0f, -1.34f, -3.0f, -3.0f)
            reflectiveCurveToRelative(1.34f, -3.0f, 3.0f, -3.0f)
            reflectiveCurveToRelative(3.0f, 1.34f, 3.0f, 3.0f)
            reflectiveCurveToRelative(-1.34f, 3.0f, -3.0f, 3.0f)
            close()
            moveTo(15.0f, 9.0f)
            lineTo(5.0f, 9.0f)
            lineTo(5.0f, 5.0f)
            horizontalLineToRelative(10.0f)
            verticalLineToRelative(4.0f)
            close()
        }
        }
    }

    val VideogameAsset: ImageVector by lazy {
        icon("Filled.VideogameAsset", autoMirror = false) {
        materialPath {
            moveTo(21.0f, 6.0f)
            lineTo(3.0f, 6.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(8.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(18.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(23.0f, 8.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(11.0f, 13.0f)
            lineTo(8.0f, 13.0f)
            verticalLineToRelative(3.0f)
            lineTo(6.0f, 16.0f)
            verticalLineToRelative(-3.0f)
            lineTo(3.0f, 13.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(3.0f)
            lineTo(6.0f, 8.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(3.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(15.5f, 15.0f)
            curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
            reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
            close()
            moveTo(19.5f, 12.0f)
            curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveTo(18.67f, 9.0f, 19.5f, 9.0f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
            reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
            close()
        }
        }
    }

    val VisibilityOff: ImageVector by lazy {
        icon("Filled.VisibilityOff", autoMirror = false) {
        materialPath {
            moveTo(12.0f, 7.0f)
            curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
            curveToRelative(0.0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
            lineToRelative(2.92f, 2.92f)
            curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f)
            curveToRelative(-1.73f, -4.39f, -6.0f, -7.5f, -11.0f, -7.5f)
            curveToRelative(-1.4f, 0.0f, -2.74f, 0.25f, -3.98f, 0.7f)
            lineToRelative(2.16f, 2.16f)
            curveTo(10.74f, 7.13f, 11.35f, 7.0f, 12.0f, 7.0f)
            close()
            moveTo(2.0f, 4.27f)
            lineToRelative(2.28f, 2.28f)
            lineToRelative(0.46f, 0.46f)
            curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1.0f, 12.0f)
            curveToRelative(1.73f, 4.39f, 6.0f, 7.5f, 11.0f, 7.5f)
            curveToRelative(1.55f, 0.0f, 3.03f, -0.3f, 4.38f, -0.84f)
            lineToRelative(0.42f, 0.42f)
            lineTo(19.73f, 22.0f)
            lineTo(21.0f, 20.73f)
            lineTo(3.27f, 3.0f)
            lineTo(2.0f, 4.27f)
            close()
            moveTo(7.53f, 9.8f)
            lineToRelative(1.55f, 1.55f)
            curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
            curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
            curveToRelative(0.22f, 0.0f, 0.44f, -0.03f, 0.65f, -0.08f)
            lineToRelative(1.55f, 1.55f)
            curveToRelative(-0.67f, 0.33f, -1.41f, 0.53f, -2.2f, 0.53f)
            curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
            curveToRelative(0.0f, -0.79f, 0.2f, -1.53f, 0.53f, -2.2f)
            close()
            moveTo(11.84f, 9.02f)
            lineToRelative(3.15f, 3.15f)
            lineToRelative(0.02f, -0.16f)
            curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
            lineToRelative(-0.17f, 0.01f)
            close()
        }
        }
    }
}

private inline fun icon(name: String, autoMirror: Boolean, block: ImageVector.Builder.() -> ImageVector.Builder): ImageVector =
    ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        autoMirror = autoMirror,
    ).block().build()

private inline fun ImageVector.Builder.materialPath(
    pathFillType: PathFillType = PathFillType.NonZero,
    pathBuilder: PathBuilder.() -> Unit,
): ImageVector.Builder = path(
    fill = SolidColor(Color.Black), fillAlpha = 1f, stroke = null, strokeAlpha = 1f, strokeLineWidth = 1f,
    strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Bevel, strokeLineMiter = 1f,
    pathFillType = pathFillType, pathBuilder = pathBuilder,
)
