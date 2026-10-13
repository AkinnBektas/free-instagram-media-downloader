package com.media.instagramdownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.media.instagramdownloader.model.Highlight
import com.media.instagramdownloader.model.MediaItem
import com.media.instagramdownloader.model.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it); vm.clearToast() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Text(
                        if (state.selecting) "${state.selected.size} selected"
                        else "Instagram Media Downloader",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    if (state.selecting) {
                        IconButton(onClick = { vm.toggleSelectMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    }
                },
                actions = {
                    val p = state.profile
                    if (state.selecting) {
                        IconButton(onClick = { vm.toggleSelectAll() }) {
                            Icon(
                                if (state.allSelected) Icons.Default.Deselect
                                else Icons.Default.SelectAll,
                                contentDescription = "Select all"
                            )
                        }
                        IconButton(
                            enabled = state.selected.isNotEmpty() && !state.downloading,
                            onClick = { vm.downloadSelected() }
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download")
                        }
                    } else {
                        if (state.currentList.isNotEmpty()) {
                            IconButton(onClick = { vm.toggleSelectMode() }) {
                                Icon(Icons.Default.Checklist, contentDescription = "Select")
                            }
                        }
                        IconButton(onClick = { vm.openLogin() }) {
                            Icon(
                                if (state.loggedIn) Icons.Default.HowToReg else Icons.AutoMirrored.Filled.Login,
                                contentDescription = "Login",
                                tint = if (state.loggedIn) Color(0xFF4CAF50)
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchBar(
                query = state.query,
                onChange = vm::onQueryChange,
                onSearch = vm::search,
                loading = state.loading
            )
            if (state.downloadProgress != null) {
                val (done, total) = state.downloadProgress!!
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Downloading $done/$total", fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading -> CenterLoader()
                    state.error != null -> ErrorView(state.error!!)
                    state.profile != null -> ProfileContent(state, vm)
                    else -> EmptyHint()
                }
            }
        }
    }

    state.preview?.let { PreviewDialog(it, onClose = vm::closePreview, onDownload = vm::downloadOne) }

    if (state.showLogin) {
        LoginDialog(loggedIn = state.loggedIn, onSave = vm::saveSession, onClose = vm::closeLogin)
    }
}

@Composable
private fun LoginDialog(
    loggedIn: Boolean,
    onSave: (String) -> Unit,
    onClose: () -> Unit
) {
    var sid by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Instagram login (optional)") },
        text = {
            Column {
                Text(
                    "Active stories and highlights often require login. " +
                        "Open instagram.com in a browser → Developer Tools (F12) → " +
                        "Application/Storage → Cookies → copy the 'sessionid' value and paste it here.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = sid,
                    onValueChange = { sid = it },
                    singleLine = true,
                    placeholder = { Text("sessionid") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(sid) }) { Text("Save") }
        },
        dismissButton = {
            if (loggedIn) TextButton(onClick = { onSave("") }) { Text("Log out") }
            else TextButton(onClick = onClose) { Text("Cancel") }
        }
    )
}

@Composable
private fun SearchBar(
    query: String,
    onChange: (String) -> Unit,
    onSearch: () -> Unit,
    loading: Boolean
) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("username or profile link") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            shape = RoundedCornerShape(14.dp),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { onSearch() }
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Search
            )
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onSearch,
            enabled = !loading,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary)
        ) {
            if (loading) CircularProgressIndicator(
                Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp
            ) else Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
        }
    }
}

@Composable
private fun ProfileContent(state: UiState, vm: MainViewModel) {
    val p = state.profile!!
    val pager = rememberPagerState(initialPage = state.activeTab) { 2 }

    LaunchedEffect(pager.currentPage) { vm.setTab(pager.currentPage) }
    LaunchedEffect(state.activeTab) {
        if (pager.currentPage != state.activeTab) pager.animateScrollToPage(state.activeTab)
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pager.currentPage) {
            Tab(
                selected = pager.currentPage == 0,
                onClick = { vm.setTab(0) },
                text = { Text("POSTS") },
                icon = { Icon(Icons.Default.GridOn, contentDescription = null) }
            )
            Tab(
                selected = pager.currentPage == 1,
                onClick = { vm.setTab(1) },
                text = { Text("REELS") },
                icon = { Icon(Icons.Default.Movie, contentDescription = null) }
            )
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            val list = if (page == 1) p.reels else p.posts
            val cursor = if (page == 1) p.reelsCursor else p.endCursor
            MediaGrid(
                list = list,
                state = state,
                vm = vm,
                emptyText = if (page == 1) "No reels." else "No posts.",
                loadMoreEnabled = page == pager.currentPage && cursor != null,
                header = {
                    Column(Modifier.fillMaxWidth()) {
                        ProfileHeader(p, hasStory = p.storyItems.isNotEmpty(), onStory = vm::openStory)
                        if (p.highlights.isNotEmpty() || p.storyItems.isNotEmpty()) {
                            SectionLabel("HIGHLIGHTS")
                            Highlights(
                                highlights = p.highlights,
                                storyCover = if (p.storyItems.isNotEmpty()) p.profilePicUrl else null,
                                onStory = vm::openStory,
                                onHighlight = { h -> vm.openHighlight(h.id, h.title) }
                            )
                        }
                        if (p.isPrivate && p.posts.isEmpty() && p.reels.isEmpty() && p.highlights.isEmpty()) {
                            PrivateNotice()
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun MediaGrid(
    list: List<com.media.instagramdownloader.model.Post>,
    state: UiState,
    vm: MainViewModel,
    emptyText: String,
    loadMoreEnabled: Boolean,
    header: @Composable () -> Unit
) {
    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, loadMoreEnabled) {
        if (nearEnd && loadMoreEnabled) vm.loadMore()
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Header (profile + highlights) scrolls away with the grid.
        item(span = { GridItemSpan(maxLineSpan) }) { header() }

        if (list.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    Text(emptyText, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }

        items(list, key = { it.id }) { post ->
            MediaCell(
                post = post,
                selecting = state.selecting,
                selected = post.id in state.selected,
                onTap = { if (state.selecting) vm.toggleItem(post.id) else vm.openPost(post) },
                onLongPress = {
                    if (!state.selecting) vm.startSelectionWith(post.id) else vm.toggleItem(post.id)
                }
            )
        }
        if (state.loadingMore && loadMoreEnabled) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(p: Profile, hasStory: Boolean, onStory: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ringMod = if (hasStory)
                Modifier.size(84.dp).clip(CircleShape)
                    .background(storyBrush()).padding(3.dp)
            else Modifier.size(84.dp)
            Box(ringMod.let { if (hasStory) it.clickable { onStory() } else it }) {
                AsyncImage(
                    model = p.profilePicUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
            Spacer(Modifier.width(20.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(p.postCount, "posts")
                Stat(p.followerCount, "followers")
                Stat(p.followingCount, "following")
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("@${p.username}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (p.isVerified) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.Verified, contentDescription = null,
                    tint = Color(0xFF3897F0), modifier = Modifier.size(16.dp)
                )
            }
        }
        if (p.fullName.isNotBlank())
            Text(p.fullName, fontWeight = FontWeight.SemiBold)
        if (p.biography.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(p.biography, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Stat(value: Long, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(formatCount(value), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun Highlights(
    highlights: List<Highlight>,
    storyCover: String?,
    onStory: () -> Unit,
    onHighlight: (Highlight) -> Unit
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (storyCover != null) {
            item {
                CircleTile("Story", storyCover, ring = true) { onStory() }
            }
        }
        items(highlights.size) { i ->
            val h = highlights[i]
            CircleTile(h.title, h.coverUrl, ring = false) { onHighlight(h) }
        }
    }
}

@Composable
private fun CircleTile(title: String, cover: String, ring: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp).clickable { onClick() }
    ) {
        val mod = if (ring)
            Modifier.size(68.dp).clip(CircleShape).background(storyBrush()).padding(3.dp)
        else Modifier.size(68.dp)
        Box(mod) {
            AsyncImage(
                model = cover,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
                    .border(
                        1.5.dp,
                        if (ring) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        CircleShape
                    )
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title.ifBlank { " " }, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun MediaCell(
    post: com.media.instagramdownloader.model.Post,
    selecting: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    Box(
        Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .pointerInput(selecting) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            }
    ) {
        AsyncImage(
            model = post.cover.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (selecting && selected) 0.55f else 1f)
        )
        if (post.isCarousel) {
            Icon(
                Icons.Default.Collections, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
            )
        } else if (post.isVideo) {
            Icon(
                Icons.Default.PlayCircle, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
            )
        }
        if (selecting) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Black.copy(alpha = 0.25f))
            )
        }
    }
}

@Composable
private fun PreviewDialog(
    preview: Preview,
    onClose: () -> Unit,
    onDownload: (MediaItem) -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f))) {
            when {
                preview.loading -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center), color = Color.White
                )
                preview.items.isEmpty() -> Text(
                    "No content.", color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> {
                    val pager = rememberPagerState(
                        initialPage = preview.startIndex.coerceIn(0, preview.items.lastIndex)
                    ) { preview.items.size }
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                        val item = preview.items[page]
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            AsyncImage(
                                model = item.displayUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(vertical = 56.dp)
                            )
                            if (item.isVideo) {
                                Icon(
                                    Icons.Default.PlayCircle, contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                        }
                    }

                    val current = preview.items[pager.currentPage]
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${pager.currentPage + 1}/${preview.items.size}",
                            color = Color.White, fontSize = 13.sp
                        )
                        Spacer(Modifier.width(16.dp))
                        IconButton(
                            onClick = { onDownload(current) },
                            modifier = Modifier.size(52.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
                        }
                    }
                }
            }

            Text(
                preview.title, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
private fun PrivateNotice() {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text("This account is private. Media cannot be downloaded.", textAlign = TextAlign.Center)
    }
}

@Composable
private fun CenterLoader() =
    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun ErrorView(msg: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Text(msg, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Text(
            "Enter a username or profile link and tap search.\n" +
                "Tap media → preview. Long-press → multi-select → download from top right.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private fun storyBrush() = Brush.linearGradient(
    listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF))
)

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}
