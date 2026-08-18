/**
 * Overview mode content for the Lunamux Android app.
 *
 * Renders the tabs-and-panes model in the **app-switcher idiom**: one rounded
 * card per tab (each card a "scaled exposé" of that tab's pane layout) in a
 * free-flinging, center-snapping row — moving across many tabs is one gesture
 * — with a labelled tab dock at the bottom for orientation and direct jumps
 * (see [TabDock]). Browsing the row never changes server state; diving into a
 * pane (tap on the centered card) activates the tab, focuses the pane, and
 * drills into that pane's full-screen route.
 *
 * Window management (issue #58):
 *  - Tapping a pane also makes it the tab's active/focused pane.
 *  - Long-pressing a pane (or a dock chip) opens a **context menu** anchored to
 *    it with Open / Maximize / Restore / Minimize / Move or resize / Rename /
 *    Close.
 *  - "Move or resize" enters a single **edit-layout mode** where *every* pane in
 *    the tab can be freely dragged to move and resized by its bottom-right
 *    handle; a banner offers Done (and Back / tapping empty space exits).
 *  - Minimized panes leave the canvas and appear in a bottom dock strip; tapping
 *    a dock chip restores the pane.
 *
 * All decisions (geometry maths, LAYOUT_STATE authoring, the edit/drag state)
 * live in the shared [OverviewBackingViewModel] so iOS can render the same
 * model; this file is the Compose front-end.
 *
 * @see TreeScreen
 * @see se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel
 */
package se.soderbjorn.lunamux.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.abs
import kotlin.math.min
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.FileBrowserContent
import se.soderbjorn.lunamux.AgentContent
import se.soderbjorn.lunamux.GitContent
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.android.net.ConnectionHolder
import se.soderbjorn.lunamux.client.closeTab
import se.soderbjorn.lunamux.client.renamePane
import se.soderbjorn.lunamux.client.renameTab
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.DockedPane
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.Drag
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.OverviewPane
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.OverviewTab
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.UnlistedTab

/**
 * Fraction of the switcher row's width one card occupies.
 *
 * Nearly all of it. The OS switcher spends a fifth of its width on peeking
 * neighbours because its cards are app screenshots you recognise at a glance; a
 * card here is a terminal you have to *read*, so the screen goes to the text and
 * the neighbours are reduced to a sliver at the edges. The row still snaps and
 * flings the same way.
 *
 * Also the horizontal end scale of the return gesture's flight when no card has
 * been measured yet (see `returnFlight`).
 */
internal const val SWITCHER_CARD_FRACTION = 0.94f

/**
 * Fraction of the switcher row's height one card occupies. As with the width:
 * the height used to be derived from the card's width and the row's aspect,
 * which left a phone-shaped card floating in a quarter of empty row.
 */
internal const val SWITCHER_CARD_HEIGHT_FRACTION = 0.99f

/** Corner radius of a switcher card, and of the return gesture's shrinking screen. */
internal val SwitcherCardCorner = 20.dp

/**
 * The overview content: the switcher card row + bottom tab dock (or, while
 * editing a layout, that tab's full-surface exposé canvas) + window-management
 * affordances.
 *
 * @param vm                the shared overview model (hoisted from [TreeScreen]
 *   so the toolbar's New window / Layout actions share it).
 * @param onOpenTerminal    drill-in callback for a terminal pane (by session id).
 * @param onOpenFileBrowser drill-in callback for a file-browser pane (by pane id).
 * @param onOpenGit         drill-in callback for a git pane (by pane id).
 * @param modifier          layout modifier from [TreeScreen].
 */
@Composable
fun OverviewContent(
    vm: OverviewBackingViewModel,
    onOpenTerminal: (String) -> Unit,
    onOpenFileBrowser: (String) -> Unit,
    onOpenGit: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBrowsedTabChanged: (String?) -> Unit = {},
) {
    val client = ConnectionHolder.client()
    if (client == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Disconnected", color = SidebarTextSecondary)
        }
        return
    }

    val state by vm.stateFlow.collectAsStateWithLifecycle()
    val editTabId by vm.editTabId.collectAsStateWithLifecycle()
    val drag by vm.drag.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val miniTerminals = remember(client) { MiniTerminalRegistry(client, scope) }
    DisposableEffect(miniTerminals) {
        onDispose { miniTerminals.close() }
    }

    // Theme the headless thumbnail emulators: their default fg/bg/cursor slots
    // are Termux stock until overridden (the full-screen terminal gets the same
    // treatment via applyTerminalColors). Re-runs when the resolved theme
    // changes so existing thumbnails repaint.
    val thumbnailTheme = rememberTerminalPalette(client, "overview-thumbnails")
    LaunchedEffect(miniTerminals, thumbnailTheme) {
        miniTerminals.setDefaultColors(thumbnailTheme)
    }

    // The single pane whose card anchors the dive transition (see
    // DiveTransition.kt). Set at tap time, before navigation, so the shared
    // bounds are registered when the flight starts; saveable so the restored
    // overview re-attaches the same anchor for the reverse flight on pop.
    // Keyed by leaf id, not session id: linked panes share a session, and two
    // cards registering one shared-bounds key would clash.
    var divePaneId by rememberSaveable { mutableStateOf<String?>(null) }

    // Rename / close dialog targets raised from a pane's context menu.
    var renameTarget by remember { mutableStateOf<LeafNode?>(null) }
    var closeTarget by remember { mutableStateOf<LeafNode?>(null) }
    // Rename / close dialog targets raised from a tab chip's context menu.
    var renameTabTarget by remember { mutableStateOf<OverviewTab?>(null) }
    var closeTabTarget by remember { mutableStateOf<OverviewTab?>(null) }

    val tabs = state.tabs
    val activeIndex = tabs.indexOfFirst { it.isActive }.coerceAtLeast(0)
    // Re-key the card row on the active world. Each world shows a disjoint tab
    // list, so a world switch must give the row a *fresh* state seeded to the
    // new world's active index (the old pager's stale-settled-page ping-pong
    // bug, avoided the same way).
    val rowListState = key(state.worldId) {
        rememberLazyListState(initialFirstVisibleItemIndex = activeIndex)
    }

    // The card currently snapped to (or nearest) the viewport center. Browsing
    // is centering, not committing: unlike the old pager, scrolling the row
    // NEVER sends setActiveTab — only diving into a pane commits the tab (see
    // divePane below), matching the app-switcher idiom this row replicates.
    val centeredIndex by remember(rowListState) {
        derivedStateOf {
            val info = rowListState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                0
            } else {
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                visible.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: 0
            }
        }
    }

    // One-way server→row sync: an external active-tab change (desktop, another
    // phone) re-centers the row, but never mid-gesture — a fling in progress
    // wins over a remote echo. Keyed on WHICH tab is active, not on its index:
    // closing a tab ahead of the active one shifts that index without changing
    // what is active, and re-centering then would yank the row away from the
    // card the user had browsed to.
    val activeTabId = tabs.firstOrNull { it.isActive }?.id
    LaunchedEffect(activeTabId) {
        val index = tabs.indexOfFirst { it.id == activeTabId }
        if (index >= 0 && !rowListState.isScrollInProgress && centeredIndex != index) {
            rowListState.animateScrollToItem(index)
        }
    }

    // Publish the browsed card so the screen's toolbar actions (new pane,
    // layout preset) target what the user is looking at. Browsing deliberately
    // never activates a tab server-side, so the active tab is NOT that target.
    val browsedTabId = tabs.getOrNull(centeredIndex)?.id
    LaunchedEffect(browsedTabId) { onBrowsedTabChanged(browsedTabId) }
    DisposableEffect(Unit) { onDispose { onBrowsedTabChanged(null) } }

    // Arrival centering after a committed swipe-up return: center the row on
    // the tab the user came from, which the active-tab seed above may miss
    // when the terminal was entered without activating its tab (sidebar/list
    // opens). Consumed once, snapped (not animated) — the return overlay card
    // is still covering the screen when this runs.
    val returnGesture = LocalReturnGesture.current
    val returnSessionId = remember { returnGesture?.consumePendingCenterSession() }
    var returnCenterHandled by remember { mutableStateOf(returnSessionId == null) }
    LaunchedEffect(returnSessionId, tabs) {
        if (!returnCenterHandled && tabs.isNotEmpty()) {
            returnCenterHandled = true
            val index = tabs.indexOfFirst { tab ->
                tab.panes.any { it.leaf.sessionId == returnSessionId }
            }
            if (index >= 0) rowListState.scrollToItem(index)
        }
    }

    // While editing layout, Back leaves edit mode rather than the screen.
    BackHandler(enabled = editTabId != null) { vm.exitEdit() }

    // Diving into a pane is the ONLY thing that commits a tab server-side:
    // activate the tab (browsing never did — see centeredIndex above), focus
    // the pane, and navigate immediately (openPane is synchronous, so the dive
    // transition starts this frame; the server round-trip stays async and
    // non-blocking). One launch keeps the two commands' send order.
    val divePane: (OverviewTab, OverviewPane) -> Unit = { tab, pane ->
        divePaneId = pane.leaf.id
        scope.launch {
            if (!tab.isActive) vm.setActiveTab(tab.id)
            vm.focusPane(tab.id, pane.leaf.id)
        }
        openPane(pane.leaf, onOpenTerminal, onOpenFileBrowser, onOpenGit)
    }

    // One canvas parameterization shared by the two hosts below (a switcher
    // card, or the full-screen edit surface), so the 12 callbacks stay in sync.
    val canvasFor: @Composable (OverviewTab, Boolean) -> Unit = { tab, editing ->
        ExposeCanvas(
            tab = tab,
            editing = editing,
            drag = drag?.takeIf { it.tabId == tab.id },
            divePaneId = divePaneId,
            onOpenPane = { pane -> divePane(tab, pane) },
            onToggleMaximize = { pane -> scope.launch { vm.toggleMaximize(tab.id, pane.leaf.id) } },
            onMinimize = { pane -> scope.launch { vm.minimize(tab.id, pane.leaf.id) } },
            onEnterEdit = { vm.enterEdit(tab.id) },
            onRename = { leaf -> renameTarget = leaf },
            onClose = { leaf -> closeTarget = leaf },
            onBeginDrag = { pane -> vm.beginDrag(tab.id, pane.leaf.id) },
            onDragMove = { dx, dy -> vm.dragMoveBy(dx, dy) },
            onDragResize = { dw, dh -> vm.dragResizeBy(dw, dh) },
            onDragEnd = { scope.launch { vm.endDrag() } },
            onRestoreDock = { docked -> scope.launch { vm.restore(tab.id, docked.leaf.id) } },
        )
    }

    CompositionLocalProvider(LocalMiniTerminalRegistry provides miniTerminals) {
        Column(modifier) {
            val editingTab = tabs.firstOrNull { it.id == editTabId }
            if (editingTab != null) {
                // Edit-mode banner: names the mode and offers an unambiguous exit.
                EditBanner(onDone = { vm.exitEdit() })
            }

            if (tabs.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No tabs", color = SidebarTextSecondary)
                }
            } else if (editingTab != null) {
                // Editing expands the tab to the full surface (the pre-switcher
                // full-width layout), so move/resize keep today's precision; the
                // dock is hidden because its chips would compete for taps.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    canvasFor(editingTab, true)
                }
            } else {
                // App-switcher card row: one card per tab at ~70% width in the
                // screen's aspect, free momentum flinging with center snap,
                // neighbors peeking in from both sides.
                SwitcherCardRow(
                    tabs = tabs,
                    rowListState = rowListState,
                    centeredIndex = centeredIndex,
                    onCenter = { index -> scope.launch { rowListState.animateScrollToItem(index) } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { tab -> canvasFor(tab, false) }

                // The bottom tab dock — the switcher's app-icon-row analog:
                // one labelled chip per tab for orientation and direct jumps.
                TabDock(
                    tabs = tabs,
                    unlistedTabs = state.unlistedTabs,
                    centeredIndex = centeredIndex,
                    closeEnabled = tabs.size > 1,
                    onCenter = { index -> scope.launch { rowListState.animateScrollToItem(index) } },
                    onDive = { tab ->
                        val target = tab.panes.firstOrNull { it.isFocused }
                            ?: tab.panes.maxByOrNull { it.z }
                        if (target != null) {
                            divePane(tab, target)
                        } else if (!tab.isActive) {
                            // Every pane is docked, so there is nothing to dive
                            // into — activate the tab instead, or the chip reads
                            // as a dead button. Restoring a pane is one tap away
                            // on the card's dock strip.
                            scope.launch { vm.setActiveTab(tab.id) }
                        }
                    },
                    onActivateUnlisted = { id -> scope.launch { vm.setActiveTab(id) } },
                    onRename = { tab -> renameTabTarget = tab },
                    onToggleHidden = { tab ->
                        scope.launch { vm.setTabHidden(tab.id, !tab.isHidden) }
                    },
                    onToggleSidebarHidden = { tab ->
                        scope.launch {
                            vm.setTabHiddenFromSidebar(tab.id, !tab.isHiddenFromSidebar)
                        }
                    },
                    onClose = { tab -> closeTabTarget = tab },
                )
            }
        }
    }

    renameTarget?.let { leaf ->
        RenameDialog(
            title = "Rename window",
            initialValue = leaf.title,
            allowBlank = true,
            supportingText = "Leave empty to use the working directory",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                val socket = ConnectionHolder.windowSocket() ?: return@RenameDialog
                scope.launch { renamePane(socket, leaf.id, name) }
            },
        )
    }
    closeTarget?.let { leaf ->
        ConfirmCloseDialog(
            title = "Close “${leaf.title}”?",
            text = "The window's session will be ended.",
            confirmLabel = "Close",
            onDismiss = { closeTarget = null },
            onConfirm = {
                closeTarget = null
                scope.launch { vm.closePane(leaf.id) }
            },
        )
    }
    renameTabTarget?.let { tab ->
        RenameDialog(
            title = "Rename tab",
            initialValue = tab.title,
            allowBlank = false,
            onDismiss = { renameTabTarget = null },
            onConfirm = { name ->
                renameTabTarget = null
                if (name.isNotBlank()) {
                    val socket = ConnectionHolder.windowSocket() ?: return@RenameDialog
                    scope.launch { renameTab(socket, tab.id, name) }
                }
            },
        )
    }
    closeTabTarget?.let { tab ->
        ConfirmCloseDialog(
            title = "Close “${tab.title}”?",
            text = "All windows in this tab will be closed and their sessions ended.",
            confirmLabel = "Close tab",
            onDismiss = { closeTabTarget = null },
            onConfirm = {
                closeTabTarget = null
                val socket = ConnectionHolder.windowSocket() ?: return@ConfirmCloseDialog
                scope.launch { closeTab(socket, tab.id) }
            },
        )
    }
}

/**
 * The app-switcher card row: one rounded card per tab, ~70% of the surface
 * width at the surface's own aspect ratio, in a snapping [LazyRow] with free
 * momentum flinging — so moving across many tabs is one gesture, not one
 * swipe per tab. Neighbors peek in from both sides and shrink/dim slightly
 * with distance from center, matching the OS app switcher this replicates.
 *
 * Browsing is passive: only the centered card is interactive; tapping (or
 * long-pressing) a peeking card centers it and nothing else, so a fling can
 * never accidentally dive into a pane or open its menu. Selection semantics
 * live in the caller ([OverviewContent]).
 *
 * @param tabs          the tabs to render, one card each.
 * @param rowListState  the hoisted row state ([OverviewContent] re-keys it per
 *   world and drives centering from server echoes).
 * @param centeredIndex index of the card snapped to (or nearest) center; only
 *   it passes touches through to its panes.
 * @param onCenter      center the card at the given index.
 * @param modifier      layout modifier from the caller.
 * @param cardContent   the card's content for a tab (the tab's exposé canvas).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwitcherCardRow(
    tabs: List<OverviewTab>,
    rowListState: LazyListState,
    centeredIndex: Int,
    onCenter: (Int) -> Unit,
    modifier: Modifier = Modifier,
    cardContent: @Composable (OverviewTab) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val cardWidth = maxWidth * SWITCHER_CARD_FRACTION
        val cardHeight = maxHeight * SWITCHER_CARD_HEIGHT_FRACTION
        val sidePadding = (maxWidth - cardWidth) / 2
        val cardShape = RoundedCornerShape(SwitcherCardCorner)

        LazyRow(
            state = rowListState,
            flingBehavior = rememberSnapFlingBehavior(rowListState),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            // Symmetric padding of (viewport - card)/2 makes item offset 0 the
            // centered position, so snap positions and scrollToItem(i) both
            // land cards dead-center — including the first and last.
            contentPadding = PaddingValues(horizontal = sidePadding),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                Box(
                    modifier = Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .graphicsLayer {
                            // Distance-from-center parallax, computed at draw
                            // time from the live layout so it costs no
                            // recomposition while flinging.
                            val info = rowListState.layoutInfo
                            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                            val viewportWidth =
                                (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                            if (item != null && viewportWidth > 0f) {
                                val center = info.viewportStartOffset + viewportWidth / 2f
                                val distance =
                                    (item.offset + item.size / 2f - center) / viewportWidth
                                val falloff = min(1f, abs(distance))
                                val scale = 1f - 0.08f * falloff
                                scaleX = scale
                                scaleY = scale
                                alpha = 1f - 0.15f * falloff
                            }
                        }
                        .clip(cardShape)
                        .background(SidebarSurface.copy(alpha = 0.35f))
                        // A hairline, never the accent: the panes inside draw
                        // their own outline (accented when focused), so an
                        // accent ring around the card read as a double border.
                        // Which tab is server-active is said by its dock chip.
                        .border(
                            width = 1.dp,
                            color = SidebarTextSecondary.copy(alpha = 0.22f),
                            shape = cardShape,
                        ),
                ) {
                    cardContent(tab)
                    if (index != centeredIndex) {
                        // Peeking cards only center on any interaction — the
                        // gate also swallows long-presses so a pane's context
                        // menu can't open on a card you haven't centered.
                        Box(
                            Modifier
                                .matchParentSize()
                                .combinedClickable(
                                    onClick = { onCenter(index) },
                                    onLongClick = { onCenter(index) },
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** Route a pane open to the right full-screen drill-in. */
private fun openPane(
    leaf: LeafNode,
    onOpenTerminal: (String) -> Unit,
    onOpenFileBrowser: (String) -> Unit,
    onOpenGit: (String) -> Unit,
) {
    when (leaf.content) {
        is FileBrowserContent -> onOpenFileBrowser(leaf.id)
        is GitContent -> onOpenGit(leaf.id)
        // Agent consoles open the terminal screen bound to their session id:
        // the server mirrors both agent render modes into the /pty byte
        // stream (transcript mode with a cooked input line, screen mode as
        // the full grid), so the existing terminal screen is the renderer.
        is AgentContent -> onOpenTerminal(leaf.sessionId)
        else -> onOpenTerminal(leaf.sessionId)
    }
}

/**
 * The exposé canvas for one tab: lays out on-canvas panes by their geometry (or
 * the live edit-mode drag), hosts each pane's context menu, and shows the dock
 * strip beneath when the tab has minimized panes.
 *
 * @param tab              the tab to render.
 * @param editing          whether this tab is in edit-layout mode.
 * @param drag             the live drag iff it targets a pane in this tab.
 * @param divePaneId       leaf id of the pane anchoring the dive transition
 *   (the last-tapped card); only that card attaches [diveSharedBounds].
 * @param onOpenPane       focus + drill into a pane.
 * @param onToggleMaximize maximize/restore a pane.
 * @param onMinimize       dock a pane.
 * @param onEnterEdit      enter edit-layout mode.
 * @param onRename         open the rename dialog for a pane.
 * @param onClose          confirm + close a pane.
 * @param onBeginDrag      start a move/resize drag on a pane.
 * @param onDragMove       forward a move drag delta (tab fractions).
 * @param onDragResize     forward a resize drag delta (tab fractions).
 * @param onDragEnd        commit the in-flight drag.
 * @param onRestoreDock    restore a docked pane.
 */
@Composable
private fun ExposeCanvas(
    tab: OverviewTab,
    editing: Boolean,
    drag: Drag?,
    divePaneId: String?,
    onOpenPane: (OverviewPane) -> Unit,
    onToggleMaximize: (OverviewPane) -> Unit,
    onMinimize: (OverviewPane) -> Unit,
    onEnterEdit: () -> Unit,
    onRename: (LeafNode) -> Unit,
    onClose: (LeafNode) -> Unit,
    onBeginDrag: (OverviewPane) -> Unit,
    onDragMove: (Double, Double) -> Unit,
    onDragResize: (Double, Double) -> Unit,
    onDragEnd: () -> Unit,
    onRestoreDock: (DockedPane) -> Unit,
) {
    // The pane / dock chip whose context menu is currently open (by leaf id).
    var menuLeafId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.panes.isEmpty() && tab.dock.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No windows in this tab", color = SidebarTextSecondary, fontSize = 13.sp)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
                    val canvasW = maxWidth
                    val canvasH = maxHeight
                    val density = LocalDensity.current
                    val canvasWpx = with(density) { canvasW.toPx() }
                    val canvasHpx = with(density) { canvasH.toPx() }

                    for (pane in tab.panes) {
                        val draggingThis = drag?.paneId == pane.leaf.id
                        val box = when {
                            draggingThis -> drag!!.box.let { Geom(it.x, it.y, it.width, it.height) }
                            pane.maximized -> FullBox
                            else -> Geom(pane.x, pane.y, pane.width, pane.height)
                        }

                        Box(
                            modifier = Modifier
                                .offset(x = canvasW * box.x.toFloat(), y = canvasH * box.y.toFloat())
                                .size(width = canvasW * box.w.toFloat(), height = canvasH * box.h.toFloat())
                                .then(if (draggingThis) Modifier.zIndex(1f) else Modifier)
                                .padding(3.dp),
                        ) {
                            // Only the tapped terminal-like pane carries the
                            // shared-bounds anchor: unique key per flight, and
                            // git/files panes navigate to non-terminal routes
                            // where the other end never exists.
                            val diveModifier =
                                if (pane.leaf.id == divePaneId && leafKindOf(pane.leaf) == LeafKind.TERMINAL) {
                                    Modifier.diveSharedBounds(diveKey(pane.leaf.sessionId))
                                } else {
                                    Modifier
                                }
                            MiniPane(pane = pane, raised = draggingThis, modifier = diveModifier)

                            if (editing) {
                                // Whole-pane move drag.
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .pointerInput(pane.leaf.id) {
                                            detectDragGestures(
                                                onDragStart = { onBeginDrag(pane) },
                                                onDragEnd = { onDragEnd() },
                                                onDrag = { change, d ->
                                                    change.consume()
                                                    onDragMove((d.x / canvasWpx).toDouble(), (d.y / canvasHpx).toDouble())
                                                },
                                            )
                                        },
                                )
                                // Corner resize handle (drawn last so it wins touches).
                                ResizeHandle(
                                    modifier = Modifier.align(Alignment.BottomEnd),
                                    onStart = { onBeginDrag(pane) },
                                    onDrag = { dx, dy ->
                                        onDragResize((dx / canvasWpx).toDouble(), (dy / canvasHpx).toDouble())
                                    },
                                    onDragEnd = onDragEnd,
                                )
                            } else {
                                // Tap = focus + open; long-press = context menu.
                                PaneTapOverlay(
                                    onTap = { onOpenPane(pane) },
                                    onLongPress = { menuLeafId = pane.leaf.id },
                                )
                                PaneContextMenu(
                                    expanded = menuLeafId == pane.leaf.id,
                                    maximized = pane.maximized,
                                    minimized = false,
                                    onDismiss = { menuLeafId = null },
                                    onOpen = { menuLeafId = null; onOpenPane(pane) },
                                    onToggleMaximize = { menuLeafId = null; onToggleMaximize(pane) },
                                    onMinimize = { menuLeafId = null; onMinimize(pane) },
                                    onRestore = {},
                                    onEdit = { menuLeafId = null; onEnterEdit() },
                                    onRename = { menuLeafId = null; onRename(pane.leaf) },
                                    onClose = { menuLeafId = null; onClose(pane.leaf) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (tab.dock.isNotEmpty()) {
            DockStrip(
                dock = tab.dock,
                menuLeafId = menuLeafId,
                onRestore = onRestoreDock,
                onLongPress = { docked -> menuLeafId = docked.leaf.id },
                onDismissMenu = { menuLeafId = null },
                onRename = { leaf -> menuLeafId = null; onRename(leaf) },
                onClose = { leaf -> menuLeafId = null; onClose(leaf) },
            )
        }
    }
}

/** A lightweight geometry box in tab fractions. */
private data class Geom(val x: Double, val y: Double, val w: Double, val h: Double)

private val FullBox = Geom(0.0, 0.0, 1.0, 1.0)

/**
 * The per-pane context menu (anchored to the pane). Shows state-aware actions
 * with Mac-matching icons.
 *
 * @param expanded         whether this pane's menu is open.
 * @param maximized        whether the pane is maximized (Maximize ↔ Restore).
 * @param minimized        whether the pane is docked (shows Restore-only set).
 * @param onDismiss        dismiss without acting.
 * @param onOpen           enter the pane full-screen.
 * @param onToggleMaximize maximize / restore.
 * @param onMinimize       dock the pane.
 * @param onRestore        un-dock a minimized pane.
 * @param onEdit           enter edit-layout mode (free move + resize).
 * @param onRename         open the rename dialog.
 * @param onClose          confirm + close.
 */
@Composable
private fun PaneContextMenu(
    expanded: Boolean,
    maximized: Boolean,
    minimized: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onToggleMaximize: () -> Unit,
    onMinimize: () -> Unit,
    onRestore: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem(Icons.AutoMirrored.Filled.OpenInNew, "Open", onOpen)
        if (minimized) {
            MenuItem(Icons.Filled.OpenInFull, "Restore from dock", onRestore)
        } else {
            if (maximized) {
                MenuItem(Icons.Filled.CloseFullscreen, "Restore", onToggleMaximize)
            } else {
                MenuItem(Icons.Filled.OpenInFull, "Maximize", onToggleMaximize)
            }
            MenuItem(Icons.Filled.Minimize, "Minimize", onMinimize)
            MenuItem(Icons.Filled.OpenWith, "Move or resize", onEdit)
        }
        HorizontalDivider()
        MenuItem(Icons.Filled.Edit, "Rename…", onRename)
        MenuItem(Icons.Filled.Close, "Close", onClose, destructive = true)
    }
}

/** One context-menu row with a leading icon. */
@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = if (destructive) SidebarWarn else SidebarTextBright
    DropdownMenuItem(
        text = { Text(label, color = tint) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

/**
 * Transparent whole-pane tap target used in the idle (non-editing) state.
 *
 * @param onTap       invoked on a tap (focus + open).
 * @param onLongPress invoked on a long-press (open the context menu).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PaneTapOverlay(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    )
}

/**
 * The bottom-right resize handle shown on every pane in edit mode. A clearly-
 * exposed accent square with a diagonal grip, sized generously for touch.
 *
 * @param modifier  alignment modifier (caller anchors it bottom-end).
 * @param onStart   begin the drag (seed the pane's geometry).
 * @param onDrag    forward the pixel drag delta.
 * @param onDragEnd commit the resize.
 */
@Composable
private fun ResizeHandle(
    modifier: Modifier,
    onStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val handleColor = SidebarAccent
    val gripColor = SidebarBackground
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 6.dp))
            .background(handleColor)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onStart() },
                    onDragEnd = { onDragEnd() },
                    onDrag = { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
            val s = size.minDimension
            for (f in listOf(0.35f, 0.7f)) {
                drawLine(
                    color = gripColor,
                    start = Offset(s * f, s),
                    end = Offset(s, s * f),
                    strokeWidth = s * 0.12f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * The edit-layout banner shown above the canvas while a tab is being arranged.
 *
 * @param onDone leave edit mode.
 */
@Composable
private fun EditBanner(onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SidebarAccent.copy(alpha = 0.14f))
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Drag to move · drag a corner to resize",
            color = SidebarAccent,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDone) {
            Icon(Icons.Filled.Check, contentDescription = "Done", tint = SidebarAccent, modifier = Modifier.size(16.dp))
            Text(" Done", color = SidebarAccent, fontSize = 12.sp)
        }
    }
}

/**
 * The dock strip: a horizontally-scrollable row of chips for the tab's
 * minimized panes. Tapping a chip restores it; long-pressing opens a context
 * menu (so a parked pane can be renamed/closed without restoring).
 *
 * @param dock          the minimized panes.
 * @param menuLeafId    the leaf id whose context menu is open, if any.
 * @param onRestore     restore a docked pane.
 * @param onLongPress   open the context menu for a docked pane.
 * @param onDismissMenu dismiss the open context menu.
 * @param onRename      open the rename dialog for a docked pane.
 * @param onClose       confirm + close a docked pane.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockStrip(
    dock: List<DockedPane>,
    menuLeafId: String?,
    onRestore: (DockedPane) -> Unit,
    onLongPress: (DockedPane) -> Unit,
    onDismissMenu: () -> Unit,
    onRename: (LeafNode) -> Unit,
    onClose: (LeafNode) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(SidebarBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(dock, key = { it.leaf.id }) { docked ->
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, SidebarTextSecondary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .background(SidebarSurface)
                        .combinedClickable(
                            onClick = { onRestore(docked) },
                            onLongClick = { onLongPress(docked) },
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusDot(state = docked.sessionState, boxDp = 12)
                    PaneIcon(kind = leafKindOf(docked.leaf), floating = false, sizeDp = 12)
                    Text(
                        text = docked.leaf.title,
                        color = SidebarTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PaneContextMenu(
                    expanded = menuLeafId == docked.leaf.id,
                    maximized = false,
                    minimized = true,
                    onDismiss = onDismissMenu,
                    onOpen = { onDismissMenu(); onRestore(docked) },
                    onToggleMaximize = {},
                    onMinimize = {},
                    onRestore = { onRestore(docked) },
                    onEdit = {},
                    onRename = { onRename(docked.leaf) },
                    onClose = { onClose(docked.leaf) },
                )
            }
        }
    }
}

/** Map a leaf's content type to its [LeafKind] icon. */
private fun leafKindOf(leaf: LeafNode): LeafKind = when (leaf.content) {
    is FileBrowserContent -> LeafKind.FILE_BROWSER
    is GitContent -> LeafKind.GIT
    // Agent consoles render through the terminal surface (see openPane).
    is AgentContent -> LeafKind.TERMINAL
    else -> LeafKind.TERMINAL
}

/**
 * A single miniature pane: a themed, rounded card with a tiny title bar, the
 * type-specific live miniature, and the focused/accent outline. Purely visual —
 * input is layered on by [ExposeCanvas].
 *
 * @param pane     the projected pane.
 * @param raised   whether to lift the card (used for the pane being dragged).
 * @param modifier outermost modifier on the card's root box — [ExposeCanvas]
 *   uses it to attach the dive transition's shared bounds, which must wrap the
 *   whole card (shadow/clip/border included) so the entire card flies.
 */
@Composable
private fun MiniPane(
    pane: OverviewPane,
    raised: Boolean,
    modifier: Modifier = Modifier,
) {
    val focused = pane.isFocused
    val borderColor = if (focused || raised) SidebarAccent else SidebarTextSecondary.copy(alpha = 0.35f)
    val borderWidth = if (focused || raised) 2.dp else 1.dp
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (raised) Modifier.shadow(10.dp, shape) else Modifier)
            .clip(shape)
            .border(borderWidth, borderColor, shape)
            .background(SidebarSurface),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Distinct title-bar strip: an elevated `surfaceAlt` background with a
            // hairline divider below it, and the title always painted in the
            // brightest token regardless of focus — matching the web/Mac pane
            // headers, which never dim inactive panes (only the card border marks
            // focus). Slightly larger than the pane content for legibility.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SidebarSurfaceAlt)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(state = pane.sessionState, boxDp = 12)
                PaneIcon(kind = leafKindOf(pane.leaf), floating = false, sizeDp = 13)
                Text(
                    text = pane.leaf.title,
                    color = SidebarTextBright,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(thickness = 1.dp, color = SidebarBorder)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                when (pane.leaf.content) {
                    is FileBrowserContent -> MiniFileBrowserPane(pane.leaf.id, Modifier.fillMaxSize())
                    is GitContent -> MiniGitPane(pane.leaf.id, Modifier.fillMaxSize())
                    // Agent consoles preview through the terminal miniature —
                    // their byte stream is served like a PTY's.
                    is AgentContent -> MiniTerminalPane(pane.leaf.sessionId, Modifier.fillMaxSize())
                    else -> MiniTerminalPane(pane.leaf.sessionId, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * The trailing `⋮` button at the end of the tab dock that opens a dropdown of
 * the unlisted (hidden) tabs. Selecting one activates it, which surfaces it
 * temporarily among the visible tabs.
 *
 * Internal so [TabDock] (the strip's switcher-era successor) can reuse it.
 *
 * @param unlistedTabs the hidden tabs to list.
 * @param onSelect     invoked with the chosen tab id.
 */
@Composable
internal fun UnlistedTabsMenu(
    unlistedTabs: List<UnlistedTab>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = "Unlisted tabs",
            tint = SidebarTextSecondary,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(4.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (tab in unlistedTabs) {
                DropdownMenuItem(
                    text = {
                        Text(
                            tab.title.ifBlank { "(untitled)" },
                            color = SidebarTextBright,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(tab.id)
                    },
                )
            }
        }
    }
}

/**
 * The per-tab context menu (anchored to a tab chip). Mirrors [PaneContextMenu]
 * but with the tab-level actions: rename, the two listing toggles (tab strip /
 * sidebar — orthogonal flags, each labelled by its current state), and close
 * (the whole tab).
 *
 * Internal so [TabDock] (the strip's switcher-era successor) can reuse it.
 *
 * @param expanded     whether this tab's menu is open.
 * @param isHidden     whether the tab is currently hidden ("unlisted") from
 *   the tab strip; labels the strip toggle item.
 * @param isHiddenFromSidebar whether the tab is currently hidden from the
 *   sidebar tab tree; labels the sidebar toggle item.
 * @param closeEnabled whether "Close tab" is offered (false for the last tab).
 * @param onDismiss    dismiss without acting.
 * @param onRename     open the rename dialog.
 * @param onToggleHidden flip the hidden-from-tab-strip flag.
 * @param onToggleSidebarHidden flip the hidden-from-sidebar flag.
 * @param onClose      confirm + close the tab.
 */
@Composable
internal fun TabContextMenu(
    expanded: Boolean,
    isHidden: Boolean,
    isHiddenFromSidebar: Boolean,
    closeEnabled: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleSidebarHidden: () -> Unit,
    onClose: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem(Icons.Filled.Edit, "Rename…", onRename)
        // Wording mirrors the web/Electron overflow menu.
        MenuItem(
            if (isHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            if (isHidden) "Show in tab bar" else "Hide in tab bar",
            onToggleHidden,
        )
        MenuItem(
            if (isHiddenFromSidebar) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            if (isHiddenFromSidebar) "Show in side bar" else "Hide in side bar",
            onToggleSidebarHidden,
        )
        if (closeEnabled) {
            HorizontalDivider()
            MenuItem(Icons.Filled.Close, "Close tab", onClose, destructive = true)
        }
    }
}
