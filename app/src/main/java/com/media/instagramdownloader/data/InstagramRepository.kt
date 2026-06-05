package com.media.instagramdownloader.data

import com.media.instagramdownloader.model.Highlight
import com.media.instagramdownloader.model.MediaItem
import com.media.instagramdownloader.model.Post
import com.media.instagramdownloader.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * All Instagram fetching happens on-device. No backend server.
 * Uses Instagram's private mobile API (i.instagram.com/api/v1) with the
 * web app-id header. This is the most reliable path for PUBLIC accounts
 * without login: full post pagination, highlights tray, and reels media.
 * Undocumented endpoints; may break or rate-limit at any time.
 */
class InstagramRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /** Optional Instagram sessionid cookie. When set, requests are authenticated,
     *  which unlocks active stories + highlight media reliably.
     *  Falls back to [DEFAULT_SESSION_ID] (embedded) when nothing set at runtime. */
    @Volatile
    var sessionId: String? = null
        get() = field?.ifBlank { null } ?: DEFAULT_SESSION_ID.ifBlank { null }

    val isLoggedIn: Boolean get() = !sessionId.isNullOrBlank()

    companion object {
        /** Optional: paste your own Instagram sessionid here to embed it in the
         *  app (stories/highlights work without manual login). WARNING: it is one
         *  account's session — expires in days, may get that account banned, and is
         *  extractable from the APK. Leave empty to require runtime login instead. */
        const val DEFAULT_SESSION_ID = ""

        private const val IG_APP_ID = "936619743392459"
        // Desktop browser UA. The web private API (api/v1) serves PUBLIC data
        // anonymously only when called like the website: browser UA + app-id +
        // Origin/Referer + csrftoken. An Instagram app UA without login => 401.
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Posts + profile go through www (works anonymously for public).
        private const val WWW = "https://www.instagram.com"
        private const val WWW_API = "$WWW/api/v1"
        // Highlights/stories live on the iphone API host.
        private const val I_API = "https://i.instagram.com/api/v1"
        private const val WEB_PROFILE = "$WWW_API/users/web_profile_info/?username="
        private const val PAGE_SIZE = 12
    }

    @Volatile
    private var csrfToken: String? = null

    class IgException(message: String) : Exception(message)

    fun normalizeUsername(input: String): String {
        var s = input.trim()
        if (s.startsWith("@")) s = s.substring(1)
        if (s.contains("instagram.com")) {
            s = s.substringAfter("instagram.com/")
            s = s.substringBefore("?").substringBefore("#")
            s = s.trim('/').substringBefore('/')
        }
        return s.trim().lowercase()
    }

    /** Hits the homepage once to obtain an anonymous csrftoken cookie. */
    private fun ensureCsrf() {
        if (!csrfToken.isNullOrBlank()) return
        runCatching {
            val req = Request.Builder()
                .url("$WWW/")
                .header("User-Agent", WEB_UA)
                .header("Accept", "text/html")
                .build()
            client.newCall(req).execute().use { resp ->
                resp.headers("set-cookie").forEach { c ->
                    if (c.startsWith("csrftoken=")) {
                        csrfToken = c.substringAfter("csrftoken=").substringBefore(";")
                    }
                }
            }
        }
    }

    private fun cookieHeader(): String {
        val parts = ArrayList<String>()
        csrfToken?.let { parts.add("csrftoken=$it") }
        val sid = sessionId
        if (!sid.isNullOrBlank()) {
            val ds = sid.substringBefore("%3A").substringBefore(":")
            parts.add("sessionid=$sid")
            parts.add("ds_user_id=$ds")
        }
        return parts.joinToString("; ")
    }

    private fun get(url: String, referer: String = "$WWW/", body: RequestBody? = null): String {
        ensureCsrf()
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_UA)
            .header("x-ig-app-id", IG_APP_ID)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", WWW)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-ASBD-ID", "129477")
            .header("X-IG-WWW-Claim", "0")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
        if (body != null) builder.post(body)
        csrfToken?.let { builder.header("x-csrftoken", it) }
        val cookie = cookieHeader()
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)

        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 404) throw IgException("User not found.")
            if (resp.code == 429) throw IgException("Too many requests. Wait and try again.")
            if (resp.code == 401) throw IgException("Unauthorized (401). Instagram requires login for this content.")
            if (!resp.isSuccessful) throw IgException("Request failed (HTTP ${resp.code}).")
            return body
        }
    }

    suspend fun fetchProfile(usernameOrUrl: String): Profile = withContext(Dispatchers.IO) {
        val username = normalizeUsername(usernameOrUrl)
        if (username.isEmpty()) throw IgException("Invalid username.")

        val raw = get(WEB_PROFILE + username, referer = "$WWW/$username/")
        val user = runCatching {
            JSONObject(raw).getJSONObject("data").getJSONObject("user")
        }.getOrElse { throw IgException("Could not read data. Account may not exist.") }

        val isPrivate = user.optBoolean("is_private", false)
        val userId = user.optString("id")

        // First page of posts via the www v1 feed (works anonymously for public).
        var postsNotice: String? = null
        val firstPage = if (!isPrivate && userId.isNotBlank()) {
            try {
                fetchPostsPage(userId, username, null)
            } catch (e: Exception) {
                postsNotice = "Could not fetch all posts: ${e.message}"
                null
            }
        } else null

        // Fallback: posts embedded in web_profile_info if v1 feed failed.
        val posts = firstPage?.posts?.ifEmpty { parseWebTimeline(user) } ?: parseWebTimeline(user)
        val endCursor = firstPage?.endCursor

        val reelsPage = if (!isPrivate && userId.isNotBlank())
            runCatching { fetchReelsPage(userId, username, null) }.getOrNull() else null

        val highlights = if (!isPrivate) fetchHighlightsTray(userId) else emptyList()
        val storyItems = if (!isPrivate)
            runCatching { fetchReelMedia(userId) }.getOrDefault(emptyList()) else emptyList()

        Profile(
            username = user.optString("username", username),
            fullName = user.optString("full_name", ""),
            biography = user.optString("biography", ""),
            profilePicUrl = user.optString("profile_pic_url_hd")
                .ifBlank { user.optString("profile_pic_url") },
            isPrivate = isPrivate,
            isVerified = user.optBoolean("is_verified", false),
            postCount = user.optJSONObject("edge_owner_to_timeline_media")?.optLong("count") ?: 0L,
            followerCount = user.optJSONObject("edge_followed_by")?.optLong("count") ?: 0L,
            followingCount = user.optJSONObject("edge_follow")?.optLong("count") ?: 0L,
            highlights = highlights,
            posts = posts,
            reels = reelsPage?.posts ?: emptyList(),
            storyItems = storyItems,
            endCursor = endCursor,
            reelsCursor = reelsPage?.endCursor,
            userId = userId,
            postsNotice = postsNotice
        )
    }

    data class Page(val posts: List<Post>, val endCursor: String?)

    suspend fun loadMorePosts(userId: String, username: String, maxId: String): Page =
        withContext(Dispatchers.IO) { fetchPostsPage(userId, username, maxId) }

    suspend fun loadMoreReels(userId: String, username: String, maxId: String): Page =
        withContext(Dispatchers.IO) { fetchReelsPage(userId, username, maxId) }

    /** Profile reels via the clips endpoint (POST form). Anonymous for public. */
    private fun fetchReelsPage(userId: String, username: String, maxId: String?): Page {
        val form = FormBody.Builder()
            .add("target_user_id", userId)
            .add("page_size", "12")
            .apply { if (!maxId.isNullOrBlank()) add("max_id", maxId) }
            .build()
        val json = JSONObject(get("$WWW_API/clips/user/", referer = "$WWW/$username/", body = form))
        val items = json.optJSONArray("items")
        val out = ArrayList<Post>()
        if (items != null) for (i in 0 until items.length()) {
            val media = items.getJSONObject(i).optJSONObject("media") ?: continue
            v1ItemToPost(media)?.let { out.add(it) }
        }
        val paging = json.optJSONObject("paging_info")
        val next = if (paging?.optBoolean("more_available") == true)
            paging.optString("max_id").ifBlank { null } else null
        return Page(out, next)
    }

    private fun fetchPostsPage(userId: String, username: String, maxId: String?): Page {
        val url = buildString {
            append("$WWW_API/feed/user/$userId/?count=$PAGE_SIZE")
            if (!maxId.isNullOrBlank()) append("&max_id=$maxId")
        }
        val json = JSONObject(get(url, referer = "$WWW/$username/"))
        val items = json.optJSONArray("items")
        val out = ArrayList<Post>()
        if (items != null) for (i in 0 until items.length()) {
            v1ItemToPost(items.getJSONObject(i))?.let { out.add(it) }
        }
        val more = json.optBoolean("more_available", false)
        val next = if (more) json.optString("next_max_id").ifBlank { null } else null
        return Page(out, next)
    }

    /** Highlights tray: covers + reel ids. */
    private fun fetchHighlightsTray(userId: String): List<Highlight> = runCatching {
        if (userId.isBlank()) return emptyList()
        val url = "$I_API/highlights/$userId/highlights_tray/"
        val tray = JSONObject(get(url)).optJSONArray("tray") ?: return emptyList()
        val list = ArrayList<Highlight>(tray.length())
        for (i in 0 until tray.length()) {
            val node = tray.getJSONObject(i)
            val cover = node.optJSONObject("cover_media")
            val coverUrl = cover?.optJSONObject("cropped_image_version")?.optString("url")
                ?: firstCandidate(cover?.optJSONObject("image_versions2"))
                ?: ""
            list.add(
                Highlight(
                    id = node.optString("id"), // "highlight:123..."
                    title = node.optString("title", ""),
                    coverUrl = coverUrl
                )
            )
        }
        list
    }.getOrDefault(emptyList())

    /** Media inside a highlight (reelId = "highlight:..") or active story (reelId = userId). */
    suspend fun loadReelMedia(reelId: String): List<MediaItem> =
        withContext(Dispatchers.IO) { fetchReelMedia(reelId) }

    private fun fetchReelMedia(reelId: String): List<MediaItem> {
        val url = "$I_API/feed/reels_media/?reel_ids=" + java.net.URLEncoder.encode(reelId, "UTF-8")
        val json = JSONObject(get(url))
        val reels = json.optJSONObject("reels")
        val reel = reels?.optJSONObject(reelId)
            ?: run {
                // Some responses key by numeric id only; grab first reel.
                val keys = reels?.keys()
                if (keys != null && keys.hasNext()) reels.optJSONObject(keys.next()) else null
            }
        val items = reel?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<MediaItem>(items.length())
        for (i in 0 until items.length()) collectFromV1Item(items.getJSONObject(i), out)
        return out
    }

    // --- v1 media parsing ---------------------------------------------------

    /** Builds one Post (carousel becomes one tile with multiple items). */
    private fun v1ItemToPost(item: JSONObject): Post? {
        val items = ArrayList<MediaItem>()
        collectFromV1Item(item, items)
        if (items.isEmpty()) return null
        val id = item.optString("id").ifBlank { item.optString("pk") }
            .ifBlank { items.first().id }
        return Post(id = id, items = items)
    }

    private fun collectFromV1Item(item: JSONObject, out: MutableList<MediaItem>) {
        when (item.optInt("media_type", 1)) {
            8 -> { // carousel
                val children = item.optJSONArray("carousel_media")
                if (children != null) for (j in 0 until children.length()) {
                    out.add(toV1Media(children.getJSONObject(j)))
                }
            }
            else -> out.add(toV1Media(item))
        }
    }

    private fun toV1Media(node: JSONObject): MediaItem {
        val isVideo = node.optInt("media_type", 1) == 2
        val image = firstCandidate(node.optJSONObject("image_versions2")) ?: ""
        val video = if (isVideo) {
            node.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")
        } else null
        val id = node.optString("id").ifBlank { node.optString("pk") }
            .ifBlank { image.hashCode().toString() }
        return MediaItem(
            id = id,
            displayUrl = image,
            thumbnailUrl = image,
            isVideo = isVideo,
            videoUrl = video?.ifBlank { null }
        )
    }

    /** Picks the highest-res candidate from an image_versions2 object. */
    private fun firstCandidate(imageVersions2: JSONObject?): String? {
        val candidates = imageVersions2?.optJSONArray("candidates") ?: return null
        var best: String? = null
        var bestW = -1
        for (i in 0 until candidates.length()) {
            val c = candidates.getJSONObject(i)
            val w = c.optInt("width", 0)
            if (w > bestW) { bestW = w; best = c.optString("url") }
        }
        return best
    }

    // --- fallback: posts from web_profile_info (GraphQL shape) --------------

    private fun parseWebTimeline(user: JSONObject): List<Post> {
        val edges = user.optJSONObject("edge_owner_to_timeline_media")
            ?.optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<Post>(edges.length())
        for (i in 0 until edges.length()) {
            val node = edges.getJSONObject(i).optJSONObject("node") ?: continue
            val items = ArrayList<MediaItem>()
            val sidecar = node.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
            if (sidecar != null && sidecar.length() > 0) {
                for (j in 0 until sidecar.length()) {
                    sidecar.getJSONObject(j).optJSONObject("node")?.let { items.add(toWebMedia(it)) }
                }
            } else items.add(toWebMedia(node))
            if (items.isNotEmpty()) {
                out.add(Post(node.optString("id", items.first().id), items))
            }
        }
        return out
    }

    private fun toWebMedia(node: JSONObject): MediaItem {
        val display = node.optString("display_url")
        val isVideo = node.optBoolean("is_video", false)
        return MediaItem(
            id = node.optString("id", display.hashCode().toString()),
            displayUrl = display,
            thumbnailUrl = display,
            isVideo = isVideo,
            videoUrl = if (isVideo) node.optString("video_url").ifBlank { null } else null
        )
    }

    @Suppress("unused")
    private fun JSONArray.objects(): Sequence<JSONObject> =
        (0 until length()).asSequence().mapNotNull { optJSONObject(it) }
}
