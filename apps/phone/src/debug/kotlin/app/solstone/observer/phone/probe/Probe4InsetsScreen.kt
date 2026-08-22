// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.probe

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Insets: composition vs layout (staleness) and composition vs root-unconsumed (consumption).
 * Drive: open drawer, open sheet, rotate to landscape.
 *   open: adb shell am start -n app.solstone.observer.phone/app.solstone.observer.phone.probe.ProbeIndexActivity
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Probe4InsetsScreen() {
    var drawerOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) drawerState.open() else drawerState.close()
    }
    ProbeScaffold(
        measures = "systemBars, displayCutout, safeDrawing, safeGestures in dp and px; composition-read vs layout-read vs root-unconsumed",
        drive = "Open drawer, open sheet, or leave both closed. Rotate to landscape. Reads are inside drawer content, sheet content, or the host body.",
    ) {
        Text(INSET_MAPPING)
        Button(onClick = {
            sheetOpen = false
            drawerOpen = true
        }) { Text("open drawer") }
        Button(onClick = {
            drawerOpen = false
            sheetOpen = true
        }) { Text("open sheet") }
        Button(onClick = {
            drawerOpen = false
            sheetOpen = false
        }) { Text("neither") }
        ModalNavigationDrawer(
            modifier = Modifier.height(420.dp),
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    InsetTable(label = "drawer")
                    Button(onClick = { drawerOpen = false }) { Text("close drawer") }
                }
            },
        ) {
            if (!drawerOpen && !sheetOpen) {
                InsetTable(label = "host")
            } else {
                Box(Modifier.fillMaxWidth()) { Text("host (baseline table hidden while drawer or sheet is open)") }
            }
        }
        if (sheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { sheetOpen = false },
                sheetState = sheetState,
            ) {
                InsetTable(label = "sheet")
            }
        }
    }
}

@Composable
private fun InsetTable(label: String) {
    val density = LocalDensity.current
    val view = LocalView.current
    val systemBars = WindowInsets.systemBars
    val displayCutout = WindowInsets.displayCutout
    val safeDrawing = WindowInsets.safeDrawing
    val safeGestures = WindowInsets.safeGestures
    val composition = InsetSnapshot(
        systemBars = boxOf(systemBars, density.density, density),
        displayCutout = boxOf(displayCutout, density.density, density),
        safeDrawing = boxOf(safeDrawing, density.density, density),
        safeGestures = boxOf(safeGestures, density.density, density),
    )
    var compositionAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var layoutSnapshot by remember { mutableStateOf<InsetSnapshot?>(null) }
    var layoutAt by remember { mutableLongStateOf(0L) }
    var rootSnapshot by remember { mutableStateOf<InsetSnapshot?>(null) }
    var rootAt by remember { mutableLongStateOf(0L) }
    val densityValue = density.density
    val handler = remember { Handler(Looper.getMainLooper()) }
    Box(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val layoutNow = InsetSnapshot(
                    systemBars = boxOf(systemBars, densityValue, this),
                    displayCutout = boxOf(displayCutout, densityValue, this),
                    safeDrawing = boxOf(safeDrawing, densityValue, this),
                    safeGestures = boxOf(safeGestures, densityValue, this),
                )
                val rootNow = rootUnconsumed(view, densityValue)
                val now = System.currentTimeMillis()
                handler.post {
                    layoutSnapshot = layoutNow
                    layoutAt = now
                    rootSnapshot = rootNow
                    rootAt = now
                }
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
    ) {
        val layout = layoutSnapshot
        val root = rootSnapshot
        Text(
            buildString {
                append("label=$label\n")
                append("composition at=$compositionAt\n")
                append(composition.render("composition"))
                append("layout at=$layoutAt\n")
                append(layout?.render("layout") ?: "layout=pending\n")
                append("root-unconsumed at=$rootAt\n")
                append(root?.render("root") ?: "root=pending\n")
            },
        )
    }
    val compositionKey = composition.render("composition")
    LaunchedEffect(compositionKey) {
        compositionAt = System.currentTimeMillis()
    }
}

private fun boxOf(
    insets: WindowInsets,
    density: Float,
    densitySource: Density,
): InsetBox {
    val l = insets.getLeft(densitySource, LayoutDirection.Ltr)
    val t = insets.getTop(densitySource)
    val r = insets.getRight(densitySource, LayoutDirection.Ltr)
    val b = insets.getBottom(densitySource)
    return InsetBox(l, t, r, b, density)
}

private fun rootUnconsumed(view: View, density: Float): InsetSnapshot {
    val root = ViewCompat.getRootWindowInsets(view)
    fun of(typeMask: Int): InsetBox {
        val insets = root?.getInsets(typeMask)
        return InsetBox(insets?.left ?: 0, insets?.top ?: 0, insets?.right ?: 0, insets?.bottom ?: 0, density)
    }
    val drawing = root?.getInsets(
        WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.ime(),
    )
    return InsetSnapshot(
        systemBars = of(WindowInsetsCompat.Type.systemBars()),
        displayCutout = of(WindowInsetsCompat.Type.displayCutout()),
        safeDrawing = InsetBox(drawing?.left ?: 0, drawing?.top ?: 0, drawing?.right ?: 0, drawing?.bottom ?: 0, density),
        safeGestures = of(WindowInsetsCompat.Type.systemGestures()),
    )
}

private data class InsetBox(val l: Int, val t: Int, val r: Int, val b: Int, val density: Float) {
    fun fmt(): String {
        val dl = l / density
        val dt = t / density
        val dr = r / density
        val db = b / density
        return "px=[$l,$t,$r,$b] dp=[$dl,$dt,$dr,$db]"
    }
}

private data class InsetSnapshot(
    val systemBars: InsetBox,
    val displayCutout: InsetBox,
    val safeDrawing: InsetBox,
    val safeGestures: InsetBox,
) {
    fun render(prefix: String): String =
        "$prefix.systemBars ${systemBars.fmt()}\n" +
            "$prefix.displayCutout ${displayCutout.fmt()}\n" +
            "$prefix.safeDrawing ${safeDrawing.fmt()}\n" +
            "$prefix.safeGestures ${safeGestures.fmt()}\n"
}

private const val INSET_MAPPING =
    "column1=composition WindowInsets.* in the composable body\n" +
        "column2=same WindowInsets.* sampled in Modifier.layout\n" +
        "column3=ViewCompat.getRootWindowInsets Type.* mapping: " +
        "systemBars->Type.systemBars() displayCutout->Type.displayCutout() " +
        "safeDrawing->Type.systemBars()|displayCutout()|ime() safeGestures->Type.systemGestures()"
