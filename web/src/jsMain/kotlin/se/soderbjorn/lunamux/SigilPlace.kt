/**
 * Works out, for every pane of the active world, the short "place" cue the agents overview draws
 * before a live row's status: the pane's tab number and, when the pane does not fill its tab, a
 * glyph for the part of the tab it fills (`1▘` is tab 1, top-left pane; `2` is a pane that fills
 * tab 2).
 *
 * The tab number is the tab's 1-based position among the visible tabs of the active world, which is
 * what the tab strip shows and what the tab hotkeys reach. The rectangle comes from the toolkit's
 * layout (the live state of the mounted shell, which includes drags the server has not echoed yet),
 * and for a tab the toolkit holds no rectangle for, from the geometry stored on the pane itself.
 *
 * The result is published as a [StateFlow] that the agents overview's view model reads, so the view
 * model stays common code. It is recomputed whenever the window config or a layout blob changes.
 *
 * @see placeGlyph
 * @see placesFlow
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import se.soderbjorn.lunamux.client.viewmodel.placeGlyph

/** A pane's rectangle within its tab, as fractions of the tab's width and height. */
private class Rect(val x: Double, val y: Double, val width: Double, val height: Double)

private val placesState = MutableStateFlow<Map<String, String>>(emptyMap())

/**
 * The place cue of every pane in the active world's visible tabs, by pane (leaf) id.
 *
 * Not published for a pane that is minimized or has no usable rectangle, nor for a pane in a hidden
 * tab or another world: those have no place a person could point to.
 *
 * Read by the agents overview's view model; refreshed by [startPlaceTracking].
 *
 * @see startPlaceTracking
 */
internal val placesFlow: StateFlow<Map<String, String>> = placesState.asStateFlow()

/**
 * Starts keeping [placesFlow] up to date: once now, then on every window config change and every
 * layout blob change, plus [notifyLayoutWritten] for the shell's own drags and resizes.
 *
 * Called once by the agents overview when its view model is first built.
 *
 * @param scope where the collectors run.
 * @see notifyLayoutWritten
 */
internal fun startPlaceTracking(scope: CoroutineScope) {
    refreshPlaces()
    scope.launch {
        merge(lunamuxClient.windowState.config, lunamuxClient.windowState.rawLayoutState).collect { refreshPlaces() }
    }
}

/**
 * Recomputes [placesFlow] after the toolkit wrote a layout blob, which is the moment a drag, resize,
 * maximize or minimize on this client becomes visible in the shell's layout state.
 *
 * Called by the settings persister adapter when the toolkit saves a layout key.
 *
 * @see startPlaceTracking
 */
internal fun notifyLayoutWritten() {
    refreshPlaces()
}

/** Recompute the map from the latest config and layout and publish it. */
private fun refreshPlaces() {
    val cfg = latestWindowConfig ?: lunamuxClient.windowState.config.value
    placesState.value = if (cfg == null) emptyMap() else computePlaces(cfg)
}

/**
 * Builds the place cue of every pane in the visible tabs of the active world of [cfg].
 *
 * @param cfg the window config to read tabs and panes from.
 * @return the cue by pane id: the tab number alone for a pane that fills its tab, else the number
 *   followed by the glyph; minimized panes are left out.
 */
internal fun computePlaces(cfg: WindowConfig): Map<String, String> {
    val geometry = layoutStateJson()
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject["geometryByTab"] as? JsonObject }.getOrNull() }
    val out = LinkedHashMap<String, String>()
    cfg.effectiveTabs.filter { !it.isHidden }.forEachIndexed { index, tab ->
        val tabGeometry = geometry?.get(tab.id) as? JsonObject
        for (pane in tab.panes) {
            val entry = tabGeometry?.get(pane.leaf.id) as? JsonObject
            val rect = rectOf(entry, pane) ?: continue
            out[pane.leaf.id] = "${index + 1}" + placeGlyph(rect.x, rect.y, rect.width, rect.height)
        }
    }
    return out
}

/**
 * The rectangle [pane] fills in its tab: the toolkit's entry when it has one, else the pane's own
 * stored geometry. A maximized pane fills the tab whatever its stored rectangle says.
 *
 * @param entry the toolkit's geometry entry for the pane, or null when it has none.
 * @param pane the pane as the server stores it.
 * @return the rectangle, or null for a minimized pane.
 */
private fun rectOf(entry: JsonObject?, pane: Pane): Rect? {
    if (entry != null) {
        if (flag(entry, "isMinimized")) return null
        if (flag(entry, "isMaximized")) return Rect(0.0, 0.0, 1.0, 1.0)
        val x = number(entry, "xPct")
        val y = number(entry, "yPct")
        val w = number(entry, "widthPct")
        val h = number(entry, "heightPct")
        if (x != null && y != null && w != null && h != null) return Rect(x, y, w, h)
    }
    if (pane.maximized) return Rect(0.0, 0.0, 1.0, 1.0)
    return Rect(pane.x, pane.y, pane.width, pane.height)
}

/** Whether [o] has [key] set to true. */
private fun flag(o: JsonObject, key: String): Boolean = (o[key] as? JsonPrimitive)?.content == "true"

/** [o]'s number at [key], or null when it is absent or not a number. */
private fun number(o: JsonObject, key: String): Double? = (o[key] as? JsonPrimitive)?.doubleOrNull
