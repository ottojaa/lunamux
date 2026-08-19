/**
 * TEMPORARY, DEBUG-ONLY tuning surface for the switcher's motion.
 *
 * Animation feel is not a thing anyone gets right by reading numbers off a
 * screen recording — it has to be tried on the device, by the person whose thumb
 * it is. [SwitcherTuning] holds every constant the card row's fling and the tab
 * dock's motion read, and [SwitcherTuningSheet] exposes them as sliders behind a
 * `BuildConfig.DEBUG` gate in the dock's corner.
 *
 * The defaults are the shipping values; the sliders only ever override them in a
 * debug build's memory (nothing is persisted, so a restart is a reset). Once the
 * numbers settle, they belong back in the constants that seed this object — and
 * this whole file goes away with the sliders.
 *
 * @see SwitcherCardRow
 * @see TabDock
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Live values for the switcher's motion, overridable from [SwitcherTuningSheet].
 *
 * Process-wide and deliberately mutable state: a slider drag has to reach the
 * fling behaviour and the dock mid-gesture, and both read these on every frame
 * they animate. Read by [rememberSwitcherFlingBehavior] and [TabDock].
 */
object SwitcherTuning {

    /** Stiffness of the row's settle spring; higher arrives sooner. */
    var snapStiffness by mutableStateOf(SWITCHER_SNAP_STIFFNESS)

    /** Damping ratio of the row's settle spring; below 1 overshoots. */
    var snapDamping by mutableStateOf(1f)

    /**
     * Whether the row's momentum uses the platform's spline decay (the same
     * friction every Android scroller has) rather than [decayFriction].
     */
    var platformDecay by mutableStateOf(true)

    /**
     * Friction multiplier for the row's momentum when [platformDecay] is off.
     * Below 1 coasts further than the platform, above 1 stops sooner.
     */
    var decayFriction by mutableStateOf(1f)

    /** Release velocity, dp/s, above which a flick advances a card. */
    var flickIntentDp by mutableStateOf(SWITCHER_FLICK_INTENT_DP)

    /** Scale of a dock chip that is fully out of focus. */
    var dockSiblingScale by mutableStateOf(DOCK_SIBLING_SCALE)

    /** Opacity of a dock chip that is fully out of focus. */
    var dockSiblingAlpha by mutableStateOf(DOCK_SIBLING_ALPHA)

    /** Layout gap between dock chips, in dp. */
    var dockGapDp by mutableStateOf(DOCK_CHIP_GAP_DP)

    /**
     * How far out of focus a chip is at one card's distance. 1 means the
     * emphasis has fully handed over to the neighbour by the time the row has
     * moved one card; lower spreads the falloff over more cards.
     */
    var dockFalloff by mutableStateOf(DOCK_FALLOFF)

    /** One line naming every current value, for reporting a set that feels right. */
    val summary: String
        get() = "stiffness=${snapStiffness.toInt()} damping=${"%.2f".format(snapDamping)} " +
            "decay=${if (platformDecay) "platform" else "friction " + "%.2f".format(decayFriction)} " +
            "flick=${flickIntentDp.toInt()}dp/s | dock scale=${"%.2f".format(dockSiblingScale)} " +
            "alpha=${"%.2f".format(dockSiblingAlpha)} gap=${dockGapDp.toInt()}dp " +
            "falloff=${"%.2f".format(dockFalloff)}"

    /** Put every value back to the constant that seeds it. */
    fun reset() {
        snapStiffness = SWITCHER_SNAP_STIFFNESS
        snapDamping = 1f
        platformDecay = true
        decayFriction = 1f
        flickIntentDp = SWITCHER_FLICK_INTENT_DP
        dockSiblingScale = DOCK_SIBLING_SCALE
        dockSiblingAlpha = DOCK_SIBLING_ALPHA
        dockGapDp = DOCK_CHIP_GAP_DP
        dockFalloff = DOCK_FALLOFF
    }
}

/**
 * Bottom sheet of sliders over [SwitcherTuning], with the current set printed at
 * the top so a combination that feels right can be read straight off the screen.
 *
 * Opened from the dock's debug corner; only ever composed in a debug build.
 *
 * @param onDismiss called when the sheet is swiped away or dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitcherTuningSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "Switcher motion (debug)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = SidebarTextBright,
            )
            Text(
                SwitcherTuning.summary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = SidebarTextSecondary,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            TuningSection("Card row")
            TuningSlider(
                label = "Settle stiffness",
                value = SwitcherTuning.snapStiffness,
                range = 20f..600f,
                format = { it.toInt().toString() },
                onChange = { SwitcherTuning.snapStiffness = it },
            )
            TuningSlider(
                label = "Settle damping",
                value = SwitcherTuning.snapDamping,
                range = 0.5f..1.2f,
                format = { "%.2f".format(it) },
                onChange = { SwitcherTuning.snapDamping = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Platform decay",
                    fontSize = 13.sp,
                    color = SidebarTextBright,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Switch(
                    checked = SwitcherTuning.platformDecay,
                    onCheckedChange = { SwitcherTuning.platformDecay = it },
                )
            }
            TuningSlider(
                label = "Decay friction (platform decay off)",
                value = SwitcherTuning.decayFriction,
                range = 0.3f..2.5f,
                format = { "%.2f".format(it) },
                onChange = { SwitcherTuning.decayFriction = it },
            )
            TuningSlider(
                label = "Flick threshold (dp/s)",
                value = SwitcherTuning.flickIntentDp,
                range = 0f..800f,
                format = { it.toInt().toString() },
                onChange = { SwitcherTuning.flickIntentDp = it },
            )

            TuningSection("Tab dock")
            TuningSlider(
                label = "Out-of-focus scale",
                value = SwitcherTuning.dockSiblingScale,
                range = 0.4f..1f,
                format = { "%.2f".format(it) },
                onChange = { SwitcherTuning.dockSiblingScale = it },
            )
            TuningSlider(
                label = "Out-of-focus opacity",
                value = SwitcherTuning.dockSiblingAlpha,
                range = 0.15f..1f,
                format = { "%.2f".format(it) },
                onChange = { SwitcherTuning.dockSiblingAlpha = it },
            )
            TuningSlider(
                label = "Chip gap (dp)",
                value = SwitcherTuning.dockGapDp,
                range = 0f..32f,
                format = { it.toInt().toString() },
                onChange = { SwitcherTuning.dockGapDp = it },
            )
            TuningSlider(
                label = "Focus falloff (per card)",
                value = SwitcherTuning.dockFalloff,
                range = 0.3f..2f,
                format = { "%.2f".format(it) },
                onChange = { SwitcherTuning.dockFalloff = it },
            )

            TextButton(onClick = { SwitcherTuning.reset() }) {
                Text("Reset to defaults", color = SidebarAccent)
            }
        }
    }
}

/**
 * Section heading inside [SwitcherTuningSheet].
 *
 * @param title the section's name.
 */
@Composable
private fun TuningSection(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = SidebarAccent,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

/**
 * One labelled slider row: name, live value, and the slider itself.
 *
 * @param label    what the value controls.
 * @param value    the current value.
 * @param range    the slider's bounds.
 * @param format   renders [value] for the label.
 * @param onChange invoked with each new value as the slider is dragged.
 */
@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 12.sp, color = SidebarTextSecondary)
            Text(
                format(value),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = SidebarTextBright,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
