@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckSectionRule
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Root of the dashboard-grid prototype (aspect-engine tickets 09 and 18). A [HorizontalPager] of
 * fixed fake pages - HOME plus two fake aspect pages - each a [PrototypeGridPage] (stage-2 true
 * 2D grid, on every page since 2026-08-23's second feel-test pass - the stage-1 reorderable
 * column served its side-by-side comparison purpose and was deleted), a page indicator, and a
 * trailing `+` stub page. All state is in-memory ([mutableStateListOf]/[mutableStateOf]) - there
 * is no engine table behind this (ticket 08's real one is a later ticket), so nothing here
 * persists across a process death; that is deliberate, this composable answers a FEEL question,
 * not a persistence one.
 */
@Composable
fun PrototypeDashboardRoot() {
    val pages = remember {
        mutableStateListOf(
            PrototypeFixtures.homePage(),
            PrototypeFixtures.fleetPage(),
            PrototypeFixtures.ledgerPage(),
        )
    }
    // +1 for the trailing "+" stub page - it is a real page in the pager (so swiping to it is the
    // same gesture as any other page transition) but renders its own content, not a widget column.
    val pageCount = pages.size + 1
    val pagerState = rememberPagerState(pageCount = { pageCount })
    var editMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "LEGION // PROTOTYPE: DASHBOARD GRID",
                style = LegionType.stamp,
                color = LocalLegionSemantics.current.chromeText,
            )
            if (pagerState.currentPage < pages.size) {
                DeckButton(
                    text = if (editMode) "DONE" else "EDIT",
                    onClick = { editMode = !editMode },
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { pageIndex ->
            if (pageIndex < pages.size) {
                val page = pages[pageIndex]
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    DeckSectionRule(label = page.name)
                    // Every page is stage 2 (2026-08-23, second feel-test pass) - the deleted
                    // stage-1 reorderable column served its side-by-side comparison purpose once
                    // Kevin confirmed HOME's grid mechanics on the A25.
                    PrototypeGridPage(
                        widgets = page.widgets,
                        editMode = editMode,
                        onEnterEditMode = { editMode = true },
                    )
                }
            } else {
                AddPageStub()
            }
        }

        PageIndicator(pagerState.currentPage, pageCount)
    }
}

/** The trailing `+` page - launcher-style "add an aspect page" stub. Ticket 09 question 4: "where
 *  does 'new aspect' live" - answered here as its own pager page rather than a floating button, so
 *  it is reachable by the same swipe gesture as every other page. Non-functional: tapping it does
 *  nothing except say so, since standing up a real aspect-creation flow is out of this ticket's
 *  scope (aspect-engine ticket 16/19, not 09). */
@Composable
private fun AddPageStub() {
    val sem = LocalLegionSemantics.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .border(1.dp, sem.chromeDim),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.headlineMedium, color = sem.chromeText)
        }
        Spacer(Modifier.padding(top = 12.dp))
        Text("ADD AN ASPECT PAGE", style = LegionType.stamp, color = sem.faint)
        Text(
            "stub only - not wired (ticket 09 is mechanics, not aspect creation)",
            style = MaterialTheme.typography.bodySmall,
            color = sem.ghost,
        )
    }
}

/** Page indicator - a dot row, current page filled amber, everything else outlined. The trailing
 *  `+` page gets its own dot like any other page, per the "reachable by swipe" answer above. */
@Composable
private fun PageIndicator(current: Int, count: Int) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { i ->
            val filled = i == current
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .combinedClickable(onClick = {})
                    .let {
                        if (filled) it.background(MaterialTheme.colorScheme.primary) else it.border(1.dp, sem.faint)
                    },
            )
        }
    }
}
