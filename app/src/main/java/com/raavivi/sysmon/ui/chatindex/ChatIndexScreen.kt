package com.raavivi.sysmon.ui.chatindex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.ChatOpenRequest
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.rememberContainerViewModel

/**
 * More > Server > Chat index (admin): what chat search has indexed and the private-words glossary, as four tabs:
 * Overview, Browse (chat > sessions > topics > sub-topics, a node's card and messages), Lexicon and Storage.
 * Back steps up a Browse drill-down before it leaves. [onOpenChat] opens the WhatsApp chat at a card's moment.
 * Everything shown is chat content and is never logged.
 */
@Composable
fun ChatIndexScreen(onBack: () -> Unit, onOpenChat: (ChatOpenRequest) -> Unit) {
    val vm = rememberContainerViewModel { ChatIndexViewModel(it) }
    val st = vm.state
    val snackbar = remember { SnackbarHostState() }

    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            snackbar.showSnackbar(msg)
            vm.message = null
        }
    }

    BackHandler(enabled = st.handlesBack) { st.back() }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(title = "Chat index", actions = {
            IconButton(onClick = st::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            IconButton(onClick = { if (!st.back()) onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })
        if (st.off) {
            Column(Modifier.padding(16.dp)) {
                SectionCard(title = "Chat search is off") {
                    CiStateBlock(
                        title = CHAT_SEARCH_OFF_TEXT,
                        hint = "Chat search is a server feature; switch it on in More > Server > Features.",
                        actionLabel = "Check again",
                        onAction = st::refresh,
                    )
                }
            }
        } else {
            TabRow(selectedTabIndex = st.tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
                CiTab.entries.forEach { t ->
                    Tab(
                        selected = st.tab == t,
                        onClick = { st.select(t) },
                        text = { Text(t.label, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when (st.tab) {
                    CiTab.OVERVIEW -> OverviewTab(st)
                    CiTab.BROWSE -> BrowseTab(st.browse, onOpenChat)
                    CiTab.LEXICON -> LexiconTab(st.lexicon)
                    CiTab.STORAGE -> StorageTab(st)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.CenterHorizontally))
    }
}

/** The index's counts as three groups of tiles: the numbers lead, their labels sit under them. */
@Composable
private fun OverviewTab(st: ChatIndexState) {
    val o = st.overview
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val data = o.value
        when {
            o.phase == LoadPhase.LOADING || o.phase == LoadPhase.IDLE -> CiLoading()
            o.phase != LoadPhase.READY || data == null -> CiStateBlock(
                title = "Could not load the overview",
                hint = o.error,
                actionLabel = "Retry",
                onAction = o::load,
                isError = true,
            )
            else -> {
                if (!data.indexExists) {
                    CiStateBlock(
                        title = NO_INDEX_TEXT,
                        hint = "Start indexing from the WhatsApp search view; its counts appear here.",
                    )
                }
                for (group in overviewGroups(data)) CountsCard(group)
            }
        }
    }
}
