package com.media.instagramdownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.media.instagramdownloader.data.InstagramRepository
import com.media.instagramdownloader.download.MediaDownloader
import com.media.instagramdownloader.model.MediaItem
import com.media.instagramdownloader.model.Post
import com.media.instagramdownloader.model.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A media preview opened in a popup: a list of items + a start index + title. */
data class Preview(
    val title: String,
    val items: List<MediaItem>,
    val startIndex: Int = 0,
    val loading: Boolean = false
)

data class UiState(
    val query: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val profile: Profile? = null,
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    val downloading: Boolean = false,
    val downloadProgress: Pair<Int, Int>? = null,
    val toast: String? = null,
    val preview: Preview? = null,
    val loggedIn: Boolean = false,
    val showLogin: Boolean = false,
    /** 0 = posts, 1 = reels. */
    val activeTab: Int = 0
) {
    val currentList: List<Post>
        get() = if (activeTab == 1) profile?.reels.orEmpty() else profile?.posts.orEmpty()

    val allSelected: Boolean
        get() = currentList.isNotEmpty() && currentList.all { it.id in selected }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = InstagramRepository()
    private val downloader = MediaDownloader(app.applicationContext)
    private val prefs = app.getSharedPreferences("insta", Application.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val sid = prefs.getString("sessionid", null)
        repo.sessionId = sid
        _state.update { it.copy(loggedIn = !sid.isNullOrBlank()) }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    // --- login (optional sessionid) -----------------------------------------

    fun openLogin() = _state.update { it.copy(showLogin = true) }
    fun closeLogin() = _state.update { it.copy(showLogin = false) }

    fun saveSession(sessionId: String) {
        val sid = sessionId.trim()
        prefs.edit().putString("sessionid", sid.ifBlank { null }).apply()
        repo.sessionId = sid.ifBlank { null }
        _state.update {
            it.copy(
                showLogin = false,
                loggedIn = sid.isNotBlank(),
                toast = if (sid.isBlank()) "Logged out." else "Login saved."
            )
        }
        if (_state.value.profile != null) search()
    }

    fun search() {
        val q = _state.value.query
        if (q.isBlank()) return
        _state.update {
            it.copy(loading = true, error = null, profile = null,
                selecting = false, selected = emptySet(), preview = null)
        }
        viewModelScope.launch {
            runCatching { repo.fetchProfile(q) }
                .onSuccess { p ->
                    _state.update { it.copy(loading = false, profile = p, toast = p.postsNotice) }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "An error occurred.") }
                }
        }
    }

    fun setTab(tab: Int) = _state.update {
        if (it.activeTab == tab) it
        else it.copy(activeTab = tab, selected = emptySet(), selecting = false)
    }

    fun loadMore() {
        val s = _state.value
        val p = s.profile ?: return
        if (s.loadingMore) return
        val reels = s.activeTab == 1
        val cursor = (if (reels) p.reelsCursor else p.endCursor) ?: return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching {
                if (reels) repo.loadMoreReels(p.userId, p.username, cursor)
                else repo.loadMorePosts(p.userId, p.username, cursor)
            }
                .onSuccess { page ->
                    _state.update { cur ->
                        val curP = cur.profile ?: return@update cur.copy(loadingMore = false)
                        cur.copy(
                            loadingMore = false,
                            profile = if (reels)
                                curP.copy(reels = curP.reels + page.posts, reelsCursor = page.endCursor)
                            else
                                curP.copy(posts = curP.posts + page.posts, endCursor = page.endCursor)
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loadingMore = false, toast = "Could not load more: ${e.message}")
                    }
                }
        }
    }

    // --- selection ----------------------------------------------------------

    fun toggleSelectMode() = _state.update {
        if (it.selecting) it.copy(selecting = false, selected = emptySet())
        else it.copy(selecting = true)
    }

    fun startSelectionWith(id: String) =
        _state.update { it.copy(selecting = true, selected = setOf(id)) }

    fun toggleItem(id: String) = _state.update {
        val set = it.selected.toMutableSet()
        if (!set.add(id)) set.remove(id)
        it.copy(selected = set)
    }

    fun toggleSelectAll() = _state.update {
        if (it.allSelected) it.copy(selected = emptySet())
        else it.copy(selected = it.currentList.map { post -> post.id }.toSet())
    }

    // --- preview popup ------------------------------------------------------

    /** Opens a single post in the viewer; carousel slides shown one by one. */
    fun openPost(post: Post) {
        _state.update {
            it.copy(preview = Preview(
                title = if (post.isCarousel) "Post (${post.items.size})" else "Post",
                items = post.items
            ))
        }
    }

    fun openHighlight(reelId: String, title: String) {
        _state.update { it.copy(preview = Preview(title.ifBlank { "Highlight" }, emptyList(), 0, loading = true)) }
        viewModelScope.launch {
            runCatching { repo.loadReelMedia(reelId) }
                .onSuccess { items ->
                    _state.update {
                        if (items.isEmpty())
                            it.copy(preview = null, toast = "Could not access this content (login may be required).")
                        else it.copy(preview = Preview(title.ifBlank { "Highlight" }, items, 0))
                    }
                }
                .onFailure {
                    _state.update { it.copy(preview = null, toast = "Failed to load.") }
                }
        }
    }

    fun openStory() {
        val p = _state.value.profile ?: return
        if (p.storyItems.isEmpty()) return
        _state.update { it.copy(preview = Preview("Story", p.storyItems, 0)) }
    }

    fun closePreview() = _state.update { it.copy(preview = null) }

    fun clearToast() = _state.update { it.copy(toast = null) }

    // --- downloads ----------------------------------------------------------

    fun downloadSelected() {
        val s = _state.value
        val p = s.profile ?: return
        val all = p.posts + p.reels
        val items = all.filter { it.id in s.selected }.flatMap { it.items }
        downloadItems(items, clearSelection = true)
    }

    fun downloadOne(item: MediaItem) = downloadItems(listOf(item), clearSelection = false)

    private fun downloadItems(items: List<MediaItem>, clearSelection: Boolean) {
        if (items.isEmpty()) return
        _state.update { it.copy(downloading = true, downloadProgress = 0 to items.size) }
        viewModelScope.launch {
            val res = downloader.downloadAll(items) { done, total ->
                _state.update { it.copy(downloadProgress = done to total) }
            }
            _state.update {
                it.copy(
                    downloading = false,
                    downloadProgress = null,
                    selecting = if (clearSelection) false else it.selecting,
                    selected = if (clearSelection) emptySet() else it.selected,
                    toast = "Downloaded: ${res.success}" +
                        if (res.failed > 0) " · Failed: ${res.failed}" else ""
                )
            }
        }
    }
}
