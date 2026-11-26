package com.raavivi.sysmon.ui.chatindex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.chatindex.ChatIndexError
import com.raavivi.sysmon.core.data.chatindex.ChatIndexRepository
import com.raavivi.sysmon.core.data.chatindex.chatIndexErrorOf
import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiCardRow
import com.raavivi.sysmon.core.model.CiFamily
import com.raavivi.sysmon.core.model.CiMessage
import com.raavivi.sysmon.core.model.CiNode
import com.raavivi.sysmon.core.model.CiNodesPage
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One open node or card: its card in full, its messages (paged) and, for a card the owner asked for, the Delete
 * card flow. A node opens with its messages at once; a card opens its messages once it names the topic node it
 * belongs to. Card and message text is private: never logged.
 */
class DetailState(
    private val repo: ChatIndexRepository,
    private val scope: CoroutineScope,
    /** The node being opened, or null when a card is. */
    val node: CiNode?,
    /** The card to read: the node's own, or the card being opened; null for a node without a card. */
    val cardId: Long?,
    onOff: () -> Unit,
    private val onDeleted: (cardId: Long) -> Unit,
) {
    val card = Loadable<CiCardFull>(scope, onOff, onLoaded = { openMessagesOfCard() }) { repo.card(cardId ?: 0) }

    /** The node whose messages show: the opened node, or the topic a card belongs to. */
    var messagesNode by mutableStateOf<Long?>(node?.id)
        private set
    val messages = PagedList<CiMessage>(scope, { it.rowid }, onOff) { offset ->
        val id = messagesNode ?: return@PagedList ApiResult.Ok(PageOf(emptyList(), null))
        when (val r = repo.nodeMessages(id, offset)) {
            is ApiResult.Ok -> ApiResult.Ok(PageOf(r.value.messages, r.value.nextOffset))
            is ApiResult.Err -> r
        }
    }

    var confirming by mutableStateOf(false)
        private set
    var deleting by mutableStateOf(false)
        private set
    var deleteError by mutableStateOf<String?>(null)
        private set
    private var closed = false

    init {
        if (cardId != null) loadCard()
        if (node != null) messages.reload()
    }

    fun loadCard() = card.load()

    /** A card opened on its own reads the messages of its topic once the card names it. */
    private fun openMessagesOfCard() {
        if (node != null) return
        val id = card.value?.nodeId ?: return
        if (messagesNode == id && messages.phase != LoadPhase.IDLE) return
        messagesNode = id
        messages.reload()
    }

    fun reloadMessages() = messages.reload()

    /** Delete card is offered only for a card the owner asked for. */
    val canDelete: Boolean get() = card.value?.let { canDeleteCard(it.origin) } == true

    fun askDelete() {
        if (!canDelete || deleting) return
        deleteError = null
        confirming = true
    }

    fun keepCard() {
        confirming = false
    }

    fun confirmDelete() {
        val c = card.value ?: return
        if (!canDeleteCard(c.origin) || deleting) return
        confirming = false
        deleting = true
        deleteError = null
        scope.launch {
            val r = repo.deleteCard(c.id)
            if (closed) return@launch
            deleting = false
            when (r) {
                is ApiResult.Ok -> onDeleted(c.id)
                is ApiResult.Err -> when (chatIndexErrorOf(r)) {
                    // Already gone is what was asked for.
                    ChatIndexError.Missing -> onDeleted(c.id)
                    ChatIndexError.NotManual -> deleteError = "Only a card you asked for can be deleted."
                    else -> deleteError = "The card could not be deleted. Try again."
                }
            }
        }
    }

    /** The detail was left: replies still in flight are dropped. */
    fun close() {
        closed = true
        card.reset()
        messages.cancel()
    }
}

/**
 * Browse: the chat list, a chat's sessions or cards, the nodes drilled into and the open node or card. The nav is
 * the pure [BrowseNav]; this applies it, reading each list once and keeping the lists already read while the user
 * steps back and forth. Chat names and content are private: never logged.
 */
class BrowseState(
    private val repo: ChatIndexRepository,
    private val scope: CoroutineScope,
    private val onOff: () -> Unit,
    private val onMessage: (String) -> Unit,
) {
    var nav by mutableStateOf(BrowseNav())
        private set

    val chats = PagedList<CiFamily>(scope, { it.id }, onOff) { offset ->
        when (val r = repo.families(offset)) {
            is ApiResult.Ok -> ApiResult.Ok(PageOf(r.value.families, r.value.nextOffset))
            is ApiResult.Err -> r
        }
    }

    private val nodeLists = HashMap<BrowseList, PagedList<CiNode>>()
    private val cardLists = HashMap<BrowseList, PagedList<CiCardRow>>()

    var detail by mutableStateOf<DetailState?>(null)
        private set

    /** The sessions or children list [nav] shows; null while it lists chats or cards. */
    val nodeList: PagedList<CiNode>? get() = nodeLists[nav.list]

    /** The chat's card list [nav] shows; null otherwise. */
    val cardList: PagedList<CiCardRow>? get() = cardLists[nav.list]

    /** The first read of the chat list, once (the tab's first open). */
    fun start() {
        if (chats.phase == LoadPhase.IDLE) chats.reload()
    }

    /** Forget every list and read the current one again (Refresh). */
    fun refresh() {
        nodeLists.values.forEach { it.cancel() }
        cardLists.values.forEach { it.cancel() }
        nodeLists.clear()
        cardLists.clear()
        chats.reload()
        ensureList(nav.list)
        detail?.let { d ->
            if (d.cardId != null) d.loadCard()
            if (d.messagesNode != null) d.reloadMessages()
        }
    }

    private fun ensureList(list: BrowseList) {
        when (list) {
            BrowseList.Chats -> Unit
            is BrowseList.Sessions -> nodeLists.getOrPut(list) {
                PagedList<CiNode>(scope, { it.id }, onOff) { offset -> nodes(repo.sessions(list.family, offset)) }.also { it.reload() }
            }
            is BrowseList.Children -> nodeLists.getOrPut(list) {
                PagedList<CiNode>(scope, { it.id }, onOff) { offset -> nodes(repo.children(list.parent, offset)) }.also { it.reload() }
            }
            is BrowseList.Cards -> cardLists.getOrPut(list) {
                PagedList<CiCardRow>(scope, { it.id }, onOff) { offset ->
                    when (val r = repo.cards(list.family, offset)) {
                        is ApiResult.Ok -> ApiResult.Ok(PageOf(r.value.cards, r.value.nextOffset))
                        is ApiResult.Err -> r
                    }
                }.also { it.reload() }
            }
        }
    }

    private fun nodes(r: ApiResult<CiNodesPage>): ApiResult<PageOf<CiNode>> = when (r) {
        is ApiResult.Ok -> ApiResult.Ok(PageOf(r.value.nodes, r.value.nextOffset))
        is ApiResult.Err -> r
    }

    /** Make [next] the position: read its list if it was not read yet, and open or close the detail. */
    private fun go(next: BrowseNav) {
        ensureList(next.list)
        if (next.detail != nav.detail) {
            detail?.close()
            detail = next.detail?.let(::makeDetail)
        }
        nav = next
    }

    private fun makeDetail(d: BrowseDetail): DetailState = when (d) {
        is BrowseDetail.Node -> DetailState(repo, scope, d.node, d.node.card?.id, onOff, ::cardDeleted)
        is BrowseDetail.Card -> DetailState(repo, scope, null, d.id, onOff, ::cardDeleted)
    }

    /** A card the owner asked for is gone: leave its view and read the lists again (their counts changed). */
    private fun cardDeleted(@Suppress("UNUSED_PARAMETER") id: Long) {
        onMessage("Card deleted")
        val back = nav.back()
        nodeLists.values.forEach { it.cancel() }
        cardLists.values.forEach { it.cancel() }
        nodeLists.clear()
        cardLists.clear()
        chats.reload()
        go(back)
    }

    fun openChat(f: CiFamily) = go(nav.intoFamily(f))
    fun setMode(m: BrowseMode) = go(nav.withMode(m))
    fun openNode(n: CiNode) = go(nav.openNode(n))
    fun openCard(c: CiCardRow) = go(nav.openCard(c))

    /** From a node's detail: list its children. */
    fun drill(n: CiNode) = go(nav.drill(n))

    /** A breadcrumb was tapped. */
    fun goTo(c: Crumb) = go(c.nav)

    /** One step back; false when Browse is at the chat list (Back should leave the screen). */
    fun back(): Boolean {
        if (!nav.canGoBack) return false
        go(nav.back())
        return true
    }
}
