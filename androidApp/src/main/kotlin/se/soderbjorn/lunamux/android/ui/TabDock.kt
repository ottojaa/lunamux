/**
 * Bottom tab dock for the switcher-style overview.
 *
 * The overview renders tabs as an app-switcher card row ([SwitcherCardRow] in
 * OverviewScreen.kt); this dock is the analog of the OS switcher's app-icon
 * row beneath the cards: one compact labelled chip per tab, so every tab stays
 * visible and directly reachable no matter where the card row is flung.
 *
 * Semantics mirror the row's browse-vs-commit split:
 *  - tapping a **non-centered** chip only centers that tab's card (no server
 *    command);
 *  - tapping the **centered** chip dives into that tab's focused pane — the
 *    same commit a tap on the centered card performs;
 *  - long-pressing a chip opens the tab context menu (rename / listing
 *    toggles / close), unchanged from the old tab strip;
 *  - a trailing `⋮` lists unlisted (hidden) tabs, selecting one activates it
 *    server-side so it surfaces in the row.
 *
 * This replaces the former top `OverviewTabStrip`; the strip's context menu
 * and unlisted-tabs menu composables are reused as-is (now internal in
 * OverviewScreen.kt).
 *
 * @see OverviewContent
 * @see TabContextMenu
 * @see UnlistedTabsMenu
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.OverviewTab
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.UnlistedTab

/**
 * The switcher's bottom tab dock: one chip per tab (status dot + title), the
 * centered tab highlighted in the accent color, with the tab context menu on
 * long-press and the unlisted-tabs `⋮` at the end.
 *
 * Composed by [OverviewContent] below the card row (hidden while editing a
 * layout, whose gestures own the screen).
 *
 * @param tabs           the visible tabs, in row order.
 * @param unlistedTabs   hidden tabs not in [tabs]; surfaced via the trailing
 *   `⋮` menu so they can be re-activated. Empty hides the menu.
 * @param centeredIndex  index of the card-row's centered tab; its chip is
 *   highlighted and its tap dives instead of centering.
 * @param closeEnabled   whether "Close tab" is offered (false for the last tab).
 * @param onCenter       center the card at the given index (no server command).
 * @param onDive         dive into the given tab's focused pane (commits the tab).
 * @param onActivateUnlisted activate an unlisted tab by id (server round-trip;
 *   the tab then surfaces in the row).
 * @param onRename       open the rename dialog for the long-pressed tab.
 * @param onToggleHidden flip the long-pressed tab's hidden-from-tab-strip
 *   ("unlisted") flag.
 * @param onToggleSidebarHidden flip the long-pressed tab's hidden-from-sidebar
 *   flag (also hides it from the sessions list, which mirrors the sidebar).
 * @param onClose        confirm + close the long-pressed tab.
 */
/**
 * How far an off-centre chip is drawn toward the middle, as a fraction of its
 * distance from it. Small on purpose: enough to cluster the strip behind the
 * centred chip, not enough to stack the chips on top of one another.
 */
private const val DOCK_INWARD_PULL = 0.10f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TabDock(
    tabs: List<OverviewTab>,
    unlistedTabs: List<UnlistedTab>,
    centeredIndex: Int,
    closeEnabled: Boolean,
    onCenter: (Int) -> Unit,
    onDive: (OverviewTab) -> Unit,
    onActivateUnlisted: (String) -> Unit,
    onRename: (OverviewTab) -> Unit,
    onToggleHidden: (OverviewTab) -> Unit,
    onToggleSidebarHidden: (OverviewTab) -> Unit,
    onClose: (OverviewTab) -> Unit,
) {
    if (tabs.isEmpty()) return

    val listState = rememberLazyListState()
    // Keep the centered tab's chip in the MIDDLE of the dock as the card row is
    // flung, the way the OS switcher keeps the current app's icon centred under
    // its card. animateScrollToItem alone parks the item against the left edge,
    // which read as a row that had simply scrolled away; centring needs the
    // item's measured width, so bring it into view first and then centre it.
    LaunchedEffect(centeredIndex, tabs.size) {
        if (centeredIndex !in tabs.indices) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == centeredIndex }) {
            listState.animateScrollToItem(centeredIndex)
        }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == centeredIndex }
            ?: return@LaunchedEffect
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val delta = item.offset + item.size / 2f - viewportCenter
        // A dock whose chips all fit is centred by the arrangement below and
        // cannot scroll, so this is a no-op there.
        if (abs(delta) > 1f) listState.animateScrollBy(delta)
    }

    // The tab chip whose context menu is currently open (by tab id).
    var menuTabId by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(SidebarBackground),
    ) {
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        // Half the dock's width of empty space at each end. Without it the row
        // cannot scroll left of its first chip, so "centre the centred chip" was
        // silently a no-op for the first tab and the strip just sat against the
        // left edge — which is what it looked like on device.
        contentPadding = PaddingValues(horizontal = maxWidth / 2),
        // Wide enough that the inward pull below still leaves air around the
        // centred chip: the pull eats into this gap, and at 2dp it closed it
        // completely and the neighbours crowded the front chip.
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
            val centered = index == centeredIndex
            // Depth: the centred chip stands at the front, full size and
            // brightness; the others sit back — smaller, dimmer, and drawn in
            // towards the centre, so the strip reads as a row receding behind the
            // one chip whose tap dives. The inward pull is computed at draw time
            // from the live layout, so it tracks a fling frame by frame, while the
            // size/brightness step animates as the emphasis hands over.
            val emphasis by animateFloatAsState(
                targetValue = if (centered) 1f else 0f,
                animationSpec = tween(durationMillis = 160),
                label = "dockChipEmphasis",
            )
            Box(
                Modifier.graphicsLayer {
                    val chipScale = 0.78f + 0.22f * emphasis
                    scaleX = chipScale
                    scaleY = chipScale
                    alpha = 0.45f + 0.55f * emphasis
                    val info = listState.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                    if (item != null) {
                        val viewportCenter =
                            (info.viewportStartOffset + info.viewportEndOffset) / 2f
                        translationX =
                            -DOCK_INWARD_PULL * (item.offset + item.size / 2f - viewportCenter)
                    }
                },
            ) {
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides 0.dp,
                ) {
                    FilterChip(
                        selected = centered,
                        onClick = {},
                        label = {
                            Text(
                                tab.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp),
                            )
                        },
                        leadingIcon = { StatusDot(state = tab.aggregateState, boxDp = 12) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = SidebarBackground,
                            // The server-active tab's label carries the accent even
                            // when the row is browsing elsewhere — the only cue left
                            // for it once the cards stopped ringing themselves in
                            // accent, and distinct from the centred chip's filled
                            // container + accent border.
                            labelColor = if (tab.isActive) {
                                SidebarAccent
                            } else {
                                SidebarTextSecondary
                            },
                            selectedContainerColor = SidebarAccent.copy(alpha = 0.18f),
                            selectedLabelColor = SidebarAccent,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = centered,
                            borderColor = SidebarTextSecondary.copy(alpha = 0.4f),
                            selectedBorderColor = SidebarAccent,
                            borderWidth = 1.dp,
                            selectedBorderWidth = 2.dp,
                        ),
                    )
                }
                // Transparent overlay that catches the tap and the long-press,
                // sitting above the chip so its own click never fires. Tap on
                // the centered chip commits (dive); on any other it centers.
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { if (centered) onDive(tab) else onCenter(index) },
                            onLongClick = { menuTabId = tab.id },
                        ),
                )
                TabContextMenu(
                    expanded = menuTabId == tab.id,
                    isHidden = tab.isHidden,
                    isHiddenFromSidebar = tab.isHiddenFromSidebar,
                    closeEnabled = closeEnabled,
                    onDismiss = { menuTabId = null },
                    onRename = { menuTabId = null; onRename(tab) },
                    onToggleHidden = { menuTabId = null; onToggleHidden(tab) },
                    onToggleSidebarHidden = { menuTabId = null; onToggleSidebarHidden(tab) },
                    onClose = { menuTabId = null; onClose(tab) },
                )
            }
        }

        // Trailing `⋮` menu listing the unlisted (hidden) tabs. Tapping a row
        // activates that tab — it then surfaces temporarily in the dock (see
        // OverviewBackingViewModel.project). Mirrors the web/Mac far-right
        // overflow menu. Only rendered when some tabs are unlisted.
        if (unlistedTabs.isNotEmpty()) {
            item(key = "__unlisted__") {
                UnlistedTabsMenu(unlistedTabs = unlistedTabs, onSelect = onActivateUnlisted)
            }
        }
    }
    }
}
