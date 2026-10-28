package com.flux.manga.ui.pages

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private data class FavoriteTarget(val category: Int?, val note: String)
internal data class EhCommentDraft(val id: String?, val original: String, val text: String)
private data class CommentTarget(val draft: EhCommentDraft, val previousIds: Set<String>)
internal data class EhInteractionState(val busy: Boolean = false, val remote: EhRemoteFavorite? = null,
    val category: Int? = null, val note: String = "", val dirty: Boolean = false, val uncertain: Boolean = false,
    val comments: EhComments? = null, val all: Boolean = false, val query: String = "", val shown: Int = 40,
    val message: String? = null, val draft: EhCommentDraft? = null, val reviewed: Boolean = false)

internal class EhInteractionModel(app: Application, private val session: WorldSession, private val source: String,
    private val manga: String, private val favorites: Boolean) : AndroidViewModel(app) {
    val site = GallerySite.forSource(source)
    private val identity = session.identity.value
    private val store = EhStore(app, session, site)
    private var job: Job? = null
    private var client: EhClient? = null
    private var pending: FavoriteTarget? = null
    private var commentPending: CommentTarget? = null
    private var votePending: Pair<String, Int>? = null
    val state = MutableStateFlow(EhInteractionState())
    private fun active() = store.active() && session.accepts(identity)
    private fun foreground() = active() && session.state.value.foreground
    private suspend fun <T> api(action: suspend (EhClient) -> T): T {
        val settings = store.settings()
        if (!site.accepts(source) || (source != EH_SOURCE && (settings.account == null || site.source(settings.account) != source)))
            throw java.io.IOException("请登录原 ${site.label} 账号")
        if (!foreground()) throw CancellationException()
        return EhClient(session, if (source == EH_SOURCE) null else settings.account, viewModelScope, site).use {
            client = it
            try { action(it) } finally { client = null }
        }
    }
    private fun run(action: suspend () -> Unit) {
        if (!foreground() || state.value.busy) return
        state.update { it.copy(busy = true, message = null) }
        job = viewModelScope.launch {
            try { action(); ensureActive() }
            catch (_: CancellationException) { if (active()) state.update { it.copy(message = if (it.uncertain) "已停止等待；网站可能已更新，请先重新读取。" else "读取已停止，内容与草稿保留。") } }
            catch (error: Exception) { if (active()) state.update { it.copy(message = if (it.uncertain) "${ehFailure(error, site)}。操作结果待确认，请先刷新网站状态。" else ehFailure(error, site)) } }
            finally { if (active()) state.update { it.copy(busy = false) } }
        }
    }
    fun resumed() {
        if (favorites) { if (state.value.remote == null || state.value.uncertain) refresh() }
        else if (state.value.comments == null || state.value.uncertain) refresh()
    }
    fun refresh() = run {
        if (favorites) api { publish(it.remoteFavorite(manga)) }
        else api { api ->
            val comments = api.comments(manga, state.value.all || state.value.uncertain)
            if (!foreground()) throw CancellationException()
            state.update { it.copy(comments = comments, shown = 40) }
            if (state.value.uncertain) reconcileComment(api, comments)
        }
    }
    private fun publish(current: EhRemoteFavorite) {
        if (!foreground()) throw CancellationException()
        val expected = pending
        val matched = expected != null && current.category == expected.category && (expected.category == null || current.note == expected.note)
        pending = null
        state.update { old -> old.copy(remote = current, uncertain = false,
            category = if (old.dirty && !matched) old.category else current.category,
            note = if (old.dirty && !matched) old.note else current.note,
            dirty = old.dirty && !matched,
            message = if (matched) "已重新读取并确认网站收藏。本机收藏与进度保持。"
                else if (expected != null) "当前网站状态与提交内容不同，草稿保留。请核对后再决定是否提交。" else "已读取当前网站收藏，编辑草稿保持。") }
    }
    fun category(value: Int?) { if (!state.value.busy && !state.value.uncertain) state.update { it.copy(category = value, dirty = true) } }
    fun note(value: String) { if (!state.value.busy && !state.value.uncertain) state.update { it.copy(note = value.take(250), dirty = true) } }
    fun discard() { state.value.remote?.let { current -> state.update { it.copy(category = current.category, note = current.note, dirty = false) } } }
    fun submit() {
        val snapshot = state.value
        if (snapshot.remote == null || !snapshot.dirty || snapshot.uncertain) return
        val target = FavoriteTarget(snapshot.category, snapshot.note)
        run { api { api ->
            // Re-read before changing a remote record, including after an interrupted previous write.
            val latest = api.remoteFavorite(manga)
            if (latest.category != snapshot.remote.category || latest.note != snapshot.remote.note) {
                publish(latest); state.update { it.copy(message = "网站收藏已在别处变化，草稿保留；请核对新状态后重新确认。") }
            } else {
                pending = target; state.update { it.copy(uncertain = true) }
                api.writeRemoteFavorite(manga, target.category, target.note)
                publish(api.remoteFavorite(manga))
            }
        } }
    }
    fun all() { if (!state.value.busy) { state.update { it.copy(all = true) }; refresh() } }
    fun newComment() {
        if (!state.value.busy && !state.value.uncertain && state.value.draft == null && state.value.comments?.canPost == true)
            state.update { it.copy(draft = EhCommentDraft(null, "", "")) }
    }
    fun editComment(comment: EhComment) {
        if (state.value.draft != null || state.value.uncertain || !comment.editable) return
        run { api { api ->
            val current = api.comments(manga, true).entries.firstOrNull { it.id == comment.id }
            if (current?.editable != true) throw java.io.IOException("此评论当前不可编辑")
            val original = api.editableComment(manga, comment.id)
            if (!foreground()) throw CancellationException()
            state.update { it.copy(draft = EhCommentDraft(comment.id, original, original)) }
        } }
    }
    fun commentText(value: String) {
        if (!state.value.busy && !state.value.uncertain) state.update { it.copy(draft = it.draft?.copy(text = value.take(10_000))) }
    }
    fun discardComment() {
        if (!state.value.busy && !state.value.uncertain) state.update { it.copy(draft = null) }
    }
    fun submitComment() {
        val draft = state.value.draft ?: return
        if (draft.text.isBlank() || draft.text.length > 10_000 || state.value.uncertain) return
        run { api { api ->
            val before = api.comments(manga, true)
            if (draft.id == null) {
                if (!before.canPost || before.more) throw java.io.IOException("当前页面未提供可确认的评论发布入口")
            } else {
                if (before.entries.firstOrNull { it.id == draft.id }?.editable != true) throw java.io.IOException("此评论当前不可编辑")
                val original = api.editableComment(manga, draft.id)
                if (original != draft.original) {
                    state.update { it.copy(comments = before, message = "网站原评论已变化，未提交。请保留草稿内容，放弃本次编辑后重新读取原文。") }
                    return@api
                }
            }
            commentPending = CommentTarget(draft, before.entries.map { it.id }.toSet())
            state.update { it.copy(uncertain = true, reviewed = false, all = true) }
            val result = api.postComment(manga, draft.id, draft.text)
            if (!foreground()) throw CancellationException()
            state.update { it.copy(comments = result) }
            reconcileComment(api, api.comments(manga, true))
        } }
    }
    fun voteComment(comment: EhComment, direction: Int) {
        if (state.value.uncertain || (direction != 1 && direction != -1)) return
        run { api { api ->
            val comments = api.comments(manga, true)
            val current = comments.entries.firstOrNull { it.id == comment.id }
                ?: throw java.io.IOException("评论已不可用")
            if (!(if (direction == 1) current.voteUp else current.voteDown)) throw java.io.IOException("当前账号没有此投票权限")
            if (current.vote == direction) {
                state.update { it.copy(comments = comments, message = "网站已经记录此投票，未再次提交。") }; return@api
            }
            votePending = comment.id to direction
            state.update { it.copy(uncertain = true, reviewed = false) }
            val result = api.voteComment(manga, comment.id, direction)
            if (!foreground()) throw CancellationException()
            votePending = null
            state.update { old -> old.copy(uncertain = false, comments = old.comments?.copy(entries = old.comments.entries.map {
                if (it.id == result.id) it.copy(score = "Score: ${result.score}", vote = result.vote) else it
            }), message = "网站投票回执：${when (result.vote) { 1 -> "赞同"; -1 -> "反对"; else -> "未投票" }}，当前评分 ${result.score}。") }
        } }
    }
    private suspend fun reconcileComment(api: EhClient, comments: EhComments) {
        if (!foreground()) throw CancellationException()
        state.update { it.copy(comments = comments) }
        val target = commentPending
        if (target != null) {
            val candidates = comments.entries.filter { it.editable &&
                (if (target.draft.id != null) it.id == target.draft.id else it.id !in target.previousIds) }
            for (entry in candidates) {
                if (api.editableComment(manga, entry.id).replace("\r\n", "\n") == target.draft.text.replace("\r\n", "\n")) {
                    if (!foreground()) throw CancellationException()
                    commentPending = null
                    state.update { it.copy(draft = null, uncertain = false, reviewed = false, message = "已重新读取并核对网站评论。") }; return
                }
            }
        }
        val vote = votePending
        if (vote != null && comments.entries.any { it.id == vote.first && it.vote == vote.second }) {
            votePending = null
            state.update { it.copy(uncertain = false, reviewed = false, message = "已重新读取并确认网站投票状态。") }; return
        }
        state.update { it.copy(reviewed = true, message = "已读取网站结果，但尚不能确认刚才的操作。请核对评论后再继续；草稿保留，不会自动重发。") }
    }
    fun acknowledgeReview() {
        if (state.value.busy || !state.value.reviewed) return
        commentPending = null; votePending = null
        state.update { it.copy(uncertain = false, reviewed = false, message = "已结束待确认状态。再次提交需要重新确认；请避免重复发布。") }
    }
    fun query(value: String) { state.update { it.copy(query = value.take(200), shown = 40) } }
    fun more() { state.update { it.copy(shown = it.shown + 40) } }
    fun stop() { job?.cancel(); client?.close(); client = null }
    override fun onCleared() { stop() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EhInteractionPage(session: WorldSession, source: String, manga: String, favorites: Boolean,
    back: () -> Unit, settings: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model: EhInteractionModel = viewModel(factory = viewModelFactory { initializer { EhInteractionModel(app, session, source, manga, favorites) } })
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current; val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var commentConfirm by remember { mutableStateOf(false) }
    var voteConfirm by remember { mutableStateOf<Pair<EhComment, Int>?>(null) }
    var reviewConfirm by remember { mutableStateOf(false) }
    var leaveConfirm by remember { mutableStateOf(false) }
    val leave = {
        if (!favorites && (state.draft != null || state.uncertain || state.busy)) leaveConfirm = true else back()
    }
    BackHandler(!favorites && (state.draft != null || state.uncertain || state.busy), leave)
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.resumed() }
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            confirm = false; commentConfirm = false; voteConfirm = null; reviewConfirm = false; leaveConfirm = false; model.stop()
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
    val matches = remember(state.comments, state.query) {
        state.comments?.entries.orEmpty().filter { state.query.isBlank() || it.body.contains(state.query.trim(), true) || it.author.contains(state.query.trim(), true) }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading(if (favorites) "网站收藏" else "画廊评论", model.site.label, leave) }
        item {
            if (state.busy) { RequestLoading(); TextButton(model::stop) { Text("停止等待") } }
            state.message?.let { RequestFeedback(it) }
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { TextButton(feedbackAttempt(model::refresh), enabled = !state.busy) { Text(if (favorites) "重新读取网站状态" else "刷新评论") }; TextButton(settings) { Text("图源账号") } }
        }
        if (favorites) {
            if (source == EH_SOURCE) item { Text("匿名作品不能写入网站收藏。请登录后从账号来源重新打开作品；原匿名书目保持。") }
            state.remote?.let { remote ->
                item { ReferencePanel {
                    Text("网站当前：" + (remote.category?.let { id -> remote.folders.firstOrNull { it.id == id.toString() }?.title } ?: "未收藏"))
                    Text("当前备注：${remote.note.ifBlank { "无" }}")
                    Text("仅修改 ${model.site.label} 网站收藏，本机收藏保持不变。")
                } }
                item { FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    remote.folders.forEach { folder -> FilterChip(state.category?.toString() == folder.id, { model.category(folder.id.toInt()) },
                        enabled = !state.busy && !state.uncertain, label = { Text(folder.title) }) }
                    FilterChip(state.category == null, { model.category(null) }, enabled = !state.busy && !state.uncertain, label = { Text("从网站取消收藏") })
                } }
                item { OutlinedTextField(state.note, model::note, Modifier.fillMaxWidth(), label = { Text("网站备注（最多 250 字）") },
                    enabled = !state.busy && !state.uncertain && state.category != null)
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        ReferenceButton({ confirm = true }, enabled = state.dirty && !state.busy && !state.uncertain) { Text("提交网站更改") }
                        TextButton(model::discard, enabled = !state.busy && !state.uncertain && state.dirty) { Text("放弃草稿") }
                    }
                }
            }
        } else {
            if (state.uncertain && state.reviewed) item {
                TextButton({ reviewConfirm = true }, enabled = !state.busy) { Text("已核对网站结果，继续编辑") }
            }
            state.draft?.let { draft -> item { ReferencePanel {
                Text(if (draft.id == null) "发布新评论" else "编辑原评论", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(draft.text, model::commentText, Modifier.fillMaxWidth(),
                    label = { Text("评论内容") }, minLines = 4,
                    supportingText = { Text("${draft.text.length} / 10000 · 原有格式标记会随内容提交") },
                    enabled = !state.busy && !state.uncertain)
                Text("草稿仅在当前页面保留；离开或进程结束不会自动发布。", style = MaterialTheme.typography.bodySmall)
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    ReferenceButton({ commentConfirm = true }, enabled = !state.busy && !state.uncertain && draft.text.isNotBlank() &&
                        (draft.id == null || draft.text != draft.original)) { Text(if (draft.id == null) "发布评论" else "保存评论修改") }
                    TextButton(model::discardComment, enabled = !state.busy && !state.uncertain) { Text("放弃草稿") }
                }
            } } }
            state.comments?.let { comments ->
                item { Text(comments.title, style = MaterialTheme.typography.titleMedium)
                    Text("已返回 ${comments.entries.size} 条评论${if (comments.more) "，还有未展开内容" else ""}")
                    Text("仅显示文字，不加载评论图片或外链。")
                    if (comments.canPost) TextButton(model::newComment,
                        enabled = !state.busy && !state.uncertain && state.draft == null) { Text("写评论") }
                    else Text(if (source == EH_SOURCE) "当前为匿名来源；请从账号来源打开作品后评论。" else "当前页面未提供评论发布入口。",
                        style = MaterialTheme.typography.bodySmall)
                    if (comments.more) TextButton(model::all, enabled = !state.busy) { Text("读取全部评论") }
                    ReferenceSearchField(state.query, model::query, "筛选已载入评论或作者", Modifier.fillMaxWidth())
                }
                if (matches.isEmpty()) item { Text(if (comments.entries.isEmpty()) "当前页面没有评论" else "已载入评论中没有匹配结果") }
                itemsIndexed(matches.take(state.shown), key = { index, comment -> "$index:${comment.id}" }) { _, comment -> ReferencePanel {
                    Text(comment.author.ifBlank { "未署名" }, style = MaterialTheme.typography.titleMedium)
                    Text(comment.posted, style = MaterialTheme.typography.bodySmall)
                    Text(comment.body)
                    if (comment.score.isNotBlank()) Text(comment.score, style = MaterialTheme.typography.bodySmall)
                    if (comment.edited.isNotBlank()) Text(comment.edited, style = MaterialTheme.typography.bodySmall)
                    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (comment.editable) TextButton({ model.editComment(comment) },
                            enabled = !state.busy && !state.uncertain && state.draft == null) { Text("编辑评论") }
                        if (comment.voteUp) TextButton({ voteConfirm = comment to 1 },
                            enabled = !state.busy && !state.uncertain && comment.vote != 1) { Text(if (comment.vote == 1) "已赞同" else "赞同") }
                        if (comment.voteDown) TextButton({ voteConfirm = comment to -1 },
                            enabled = !state.busy && !state.uncertain && comment.vote != -1) { Text(if (comment.vote == -1) "已反对" else "反对") }
                    }
                } }
                if (matches.size > state.shown) item { TextButton(feedbackAttempt(model::more)) { Text("显示更多已载入评论") } }
            }
        }
    }
    if (confirm) ReferenceDialog(onDismissRequest = { confirm = false }, title = { Text("确认修改网站收藏？") },
        text = { Text(if (state.category == null) "将从 ${model.site.label} 网站移除此收藏及其备注。本机书目、收藏与进度保持。"
            else "将保存到所选网站分类，并以当前草稿更新网站备注。本机记录保持。") },
        confirmButton = { TextButton(feedbackAttempt({ confirm = false; model.submit() })) { Text("确认提交") } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
    if (commentConfirm) ReferenceDialog(onDismissRequest = { commentConfirm = false },
        title = { Text(if (state.draft?.id == null) "确认发布评论？" else "确认修改原评论？") },
        text = { Text("将以当前 ${model.site.label} 账号提交编辑框中的内容，网站读者可以看到。") },
        confirmButton = { TextButton({ commentConfirm = false; model.submitComment() }, enabled = !state.busy && !state.uncertain) { Text("确认提交") } },
        dismissButton = { TextButton({ commentConfirm = false }) { Text("继续编辑") } })
    voteConfirm?.let { (comment, direction) -> ReferenceDialog(onDismissRequest = { voteConfirm = null },
        title = { Text("确认${if (direction == 1) "赞同" else "反对"}评论？") },
        text = { Text("将向 ${model.site.label} 提交对 ${comment.author.ifBlank { "未署名作者" }} 评论的投票。结果以网站回执为准。") },
        confirmButton = { TextButton({ voteConfirm = null; model.voteComment(comment, direction) }, enabled = !state.busy && !state.uncertain) { Text("确认投票") } },
        dismissButton = { TextButton({ voteConfirm = null }) { Text("取消") } }) }
    if (reviewConfirm) ReferenceDialog(onDismissRequest = { reviewConfirm = false }, title = { Text("已核对网站结果？") },
        text = { Text("刚才的评论可能已发布，请先刷新确认，避免重复发布。草稿保留。") },
        confirmButton = { TextButton({ reviewConfirm = false; model.acknowledgeReview() }) { Text("已核对，继续") } },
        dismissButton = { TextButton({ reviewConfirm = false }) { Text("返回核对") } })
    if (leaveConfirm) ReferenceDialog(onDismissRequest = { leaveConfirm = false }, title = { Text("离开评论页？") },
        text = { Text("未提交草稿将不再保留。正在提交或结果待确认的操作可能已在网站生效，返回后请先刷新核对。") },
        confirmButton = { TextButton({ leaveConfirm = false; model.stop(); back() }) { Text("离开") } },
        dismissButton = { TextButton({ leaveConfirm = false }) { Text("留在此页") } })
}
