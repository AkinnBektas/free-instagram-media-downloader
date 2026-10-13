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
 * Posts come from the web client's GraphQL timeline query.
 * web_profile_info now answers 429 on the first call, session or not.
 * Undocumented endpoints; the doc_id can be retired at any time.
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
    private var sessionField: String? = null

    var sessionId: String?
        get() = sessionField?.ifBlank { null } ?: DEFAULT_SESSION_ID.ifBlank { null }
        set(value) {
            val clean = value?.let(::sanitizeSession)?.ifBlank { null }
            if (sessionField == clean) return
            sessionField = clean
            resetTransport()
        }

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
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // Posts + profile go through www (works anonymously for public).
        private const val WWW = "https://www.instagram.com"
        private const val WWW_API = "$WWW/api/v1"
        private const val PAGE_SIZE = 12
        // Persisted query for the profile posts grid. Retires when Instagram ships a new web build.
        private const val POSTS_DOC_ID = "29240983615539641"
        private const val POSTS_FRIENDLY =
            "PolarisProfilePostsTabContentQuery_connection"
        // Profile highlights tray. data.highlights.edges[].node
        private const val HIGHLIGHTS_DOC_ID = "9532867876840543"
        // Active story + highlight items. data.xdt_api__v1__feed__reels_media
        private const val REEL_GALLERY_DOC_ID = "26659189347081290"
    }

    private val transportLock = Any()
    private val cookies = LinkedHashMap<String, String>()
    @Volatile private var wwwClaim: String? = null
    @Volatile private var bootstrapped = false
    @Volatile private var bootstrapFor: String? = null

    class IgException(message: String) : Exception(message)

    private fun resetTransport() {
        synchronized(transportLock) {
            cookies.clear()
            wwwClaim = null
            bootstrapped = false
            bootstrapFor = null
        }
    }

    /** Accept a raw cookie value, or a full `sessionid=...;` string. */
    private fun sanitizeSession(raw: String): String {
        var s = raw.trim().trim('"').trim('\'')
        if (s.startsWith("sessionid=", ignoreCase = true)) {
            s = s.substringAfter('=').substringBefore(';').trim()
        } else {
            s = s.substringBefore(';').trim()
        }
        return s.trim('"').trim('\'')
    }

    private fun userIdFromSession(sid: String): String {
        val decoded = runCatching {
            java.net.URLDecoder.decode(sid, Charsets.UTF_8.name())
        }.getOrDefault(sid)
        return decoded.substringBefore(':').substringBefore("%3A").trim()
    }

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

    /**
     * Homepage once per session so csrftoken / mid / ig_did belong to that login.
     * An anonymous csrf mixed with sessionid makes Instagram answer 429 immediately.
     */
    private fun ensureBootstrap() {
        val sid = sessionId.orEmpty()
        val already = synchronized(transportLock) {
            bootstrapped && bootstrapFor == sid && !cookies["csrftoken"].isNullOrBlank()
        }
        if (already) return
        runCatching {
            var result = exchangeDocument("$WWW/")
            if (result.code == 429 && result.refreshed) {
                result = exchangeDocument("$WWW/")
            }
            synchronized(transportLock) {
                bootstrapped = !cookies["csrftoken"].isNullOrBlank() || result.code == 429
                bootstrapFor = sid
            }
        }
    }

    private fun applySessionLocked() {
        val sid = sessionId
        if (sid.isNullOrBlank()) {
            cookies.remove("sessionid")
            cookies.remove("ds_user_id")
            return
        }
        cookies["sessionid"] = sid
        val ds = userIdFromSession(sid)
        if (ds.isNotEmpty() && ds.all { it.isDigit() }) cookies["ds_user_id"] = ds
    }

    private fun cookieHeader(): String = synchronized(transportLock) {
        applySessionLocked()
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    /** Store Set-Cookie plus the rur / www-claim headers Instagram expects echoed back. */
    private fun absorb(resp: okhttp3.Response): Boolean {
        var changed = false
        synchronized(transportLock) {
            resp.headers("set-cookie").forEach { raw ->
                val pair = raw.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq <= 0) return@forEach
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.equals("sessionid", ignoreCase = true) && !sessionId.isNullOrBlank()) {
                    return@forEach
                }
                if (cookies[name] != value) {
                    cookies[name] = value
                    changed = true
                }
            }
            resp.header("ig-set-ig-u-rur")?.trim()?.removeSurrounding("\"")?.let { rur ->
                if (rur.isNotBlank() && cookies["rur"] != rur) {
                    cookies["rur"] = rur
                    changed = true
                }
            }
            resp.header("ig-set-ig-u-ds-user-id")?.let { id ->
                if (id.isNotBlank() && id.all { it.isDigit() }) cookies["ds_user_id"] = id
            }
            resp.header("x-ig-set-www-claim")?.trim()?.let { claim ->
                if (claim.isNotBlank() && claim != wwwClaim) {
                    wwwClaim = claim
                    changed = true
                }
            }
        }
        return changed
    }

    private data class HttpResult(val code: Int, val body: String, val refreshed: Boolean)

    private fun buildRequest(url: String, referer: String, body: RequestBody?): Request {
        val loggedIn = !sessionId.isNullOrBlank()
        val claim = synchronized(transportLock) { wwwClaim }
        val csrf = synchronized(transportLock) { cookies["csrftoken"] }
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
            .header("Sec-Fetch-Site", if (url.startsWith(WWW)) "same-origin" else "same-site")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
        if (body != null) builder.post(body)
        csrf?.let { builder.header("x-csrftoken", it) }
        // Claim "0" means logged-out. Sending it next to sessionid is what triggers 429.
        if (loggedIn) {
            if (!claim.isNullOrBlank() && claim != "0") builder.header("X-IG-WWW-Claim", claim)
        } else {
            builder.header("X-IG-WWW-Claim", claim?.takeIf { it.isNotBlank() } ?: "0")
        }
        val cookie = cookieHeader()
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return builder.build()
    }

    /** Browser-style page load. This is what sets mid / ig_did / csrftoken. */
    private fun exchangeDocument(url: String): HttpResult {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .apply {
                val cookie = cookieHeader()
                if (cookie.isNotBlank()) header("Cookie", cookie)
            }
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val refreshed = absorb(resp)
            return HttpResult(resp.code, text, refreshed)
        }
    }

    private fun exchange(url: String, referer: String, body: RequestBody?): HttpResult {
        client.newCall(buildRequest(url, referer, body)).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val refreshed = absorb(resp)
            return HttpResult(resp.code, text, refreshed)
        }
    }

    private fun get(url: String, referer: String = "$WWW/", body: RequestBody? = null): String {
        ensureBootstrap()
        var result = exchange(url, referer, body)
        // The first call with a session often comes back 403/429 and only then
        // sets rur + www-claim. Those are stored above; one retry uses them.
        if (result.code == 403 || result.code == 401 || result.code == 429) {
            result = exchange(url, referer, body)
        }
        if (result.code == 404) throw IgException("User not found.")
        if (result.code == 429) {
            val server = runCatching { JSONObject(result.body).optString("message") }.getOrNull()
            throw IgException(server?.takeIf { it.isNotBlank() }
                ?: "Too many requests. Wait and try again.")
        }
        if (result.code == 401) throw IgException("Unauthorized (401). Instagram requires login for this content.")
        if (result.code !in 200..299) throw IgException("Request failed (HTTP ${result.code}).")
        return result.body
    }

    suspend fun fetchProfile(usernameOrUrl: String): Profile = withContext(Dispatchers.IO) {
        val username = normalizeUsername(usernameOrUrl)
        if (username.isEmpty()) throw IgException("Invalid username.")

        val firstPage = fetchTimeline(username, null)
        val owner = firstPage.owner
        val userId = userPk(owner).ifBlank { userIdFromPosts(firstPage.posts) }
        val isPrivate = owner?.optBoolean("is_private", false) ?: false
        // is_private only means the account is locked to non-followers.
        // A saved session that follows them can still read posts and highlights.
        val loadLocked = userId.isNotBlank() && (!isPrivate || isLoggedIn)

        val reelsPage = if (loadLocked)
            runCatching { fetchReelsPage(userId, username, null) }.getOrNull() else null
        val highlights = if (loadLocked) fetchHighlightsTray(userId, username) else emptyList()
        val storyItems = if (loadLocked)
            runCatching { fetchReelMedia(userId) }.getOrDefault(emptyList()) else emptyList()

        Profile(
            username = owner?.optString("username").takeUnless { it.isNullOrBlank() } ?: username,
            fullName = owner?.optString("full_name").orEmpty(),
            biography = owner?.optString("biography").orEmpty(),
            profilePicUrl = owner?.optString("profile_pic_url").orEmpty(),
            isPrivate = isPrivate,
            isVerified = owner?.optBoolean("is_verified", false) ?: false,
            postCount = jsonLong(owner, "media_count"),
            followerCount = jsonLong(owner, "follower_count"),
            followingCount = jsonLong(owner, "following_count"),
            highlights = highlights,
            posts = firstPage.posts,
            reels = reelsPage?.posts ?: emptyList(),
            storyItems = storyItems,
            endCursor = firstPage.endCursor,
            reelsCursor = reelsPage?.endCursor,
            userId = userId,
            postsNotice = null
        )
    }

    data class Page(val posts: List<Post>, val endCursor: String?)

    private data class TimelinePage(
        val posts: List<Post>,
        val endCursor: String?,
        val owner: JSONObject?
    )

    suspend fun loadMorePosts(userId: String, username: String, maxId: String): Page =
        withContext(Dispatchers.IO) { fetchPostsPage(username, maxId) }

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

    private fun fetchPostsPage(username: String, maxId: String?): Page {
        val page = fetchTimeline(username, maxId)
        return Page(page.posts, page.endCursor)
    }

    /** Profile grid via the same persisted query the website uses. [after] is page_info.end_cursor. */
    private fun fetchTimeline(username: String, after: String?): TimelinePage {
        val data = JSONObject()
            .put("count", PAGE_SIZE)
            .put("include_reel_media_seen_timestamp", true)
            .put("include_relationship_info", true)
            .put("latest_besties_reel_media", true)
            .put("latest_reel_media", true)
        val variables = JSONObject()
            .put("after", if (after.isNullOrBlank()) JSONObject.NULL else after)
            .put("before", JSONObject.NULL)
            .put("data", data)
            .put("first", PAGE_SIZE)
            .put("include_multi_captions", true)
            .put("last", JSONObject.NULL)
            .put("username", username)
            .put("__relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider", true)
            .put("__relay_internal__pv__PolarisShortDramaEnabledrelayprovider", true)
            .put("__relay_internal__pv__PolarisReelsRecoDebugOverlayEnabledrelayprovider", false)
        val form = FormBody.Builder()
            .add("doc_id", POSTS_DOC_ID)
            .add("fb_api_req_friendly_name", POSTS_FRIENDLY)
            .add("variables", variables.toString())
            .build()
        val raw = get("$WWW/graphql/query", referer = "$WWW/$username/", body = form)
        val json = runCatching { JSONObject(raw) }.getOrElse {
            throw IgException("Instagram did not return post data. Check the session id.")
        }
        if (json.optString("status") == "fail") {
            throw IgException(json.optString("message").ifBlank { "Request failed." })
        }
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val msg = errors.optJSONObject(0)?.optString("message").orEmpty()
            throw IgException(msg.ifBlank { "Could not read posts." })
        }
        val conn = json.optJSONObject("data")
            ?.optJSONObject("xdt_api__v1__feed__user_timeline_graphql_connection")
            ?: throw IgException("Could not read posts. The account may be private or unavailable.")
        val edges = conn.optJSONArray("edges")
        val out = ArrayList<Post>()
        var owner: JSONObject? = null
        if (edges != null) for (i in 0 until edges.length()) {
            val rawNode = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            val node = rawNode.optJSONObject("media") ?: rawNode
            if (owner == null) owner = node.optJSONObject("user") ?: node.optJSONObject("owner")
            v1ItemToPost(node)?.let { out.add(it) }
        }
        val pageInfo = conn.optJSONObject("page_info")
        val next = if (pageInfo?.optBoolean("has_next_page") == true)
            pageInfo.optString("end_cursor").ifBlank { null } else null
        return TimelinePage(out, next, owner)
    }

    /** Media id is often `{mediaPk}_{userId}`. */
    private fun userIdFromPosts(posts: List<Post>): String {
        for (post in posts) {
            val tail = post.id.substringAfterLast('_', "")
            if (tail.length >= 5 && tail.all { it.isDigit() }) return tail
        }
        return ""
    }

    /** Numeric user id. Highlight and reel calls 404 if this is a media code. */
    private fun userPk(node: JSONObject?): String {
        if (node == null) return ""
        for (key in listOf("pk", "id", "pk_id")) {
            if (!node.has(key) || node.isNull(key)) continue
            val value = node.opt(key)?.toString()?.removeSuffix(".0")?.trim().orEmpty()
            if (value.isNotEmpty() && value.all { it.isDigit() }) return value
        }
        return ""
    }

    private fun asId(node: JSONObject?): String {
        if (node == null) return ""
        for (key in listOf("id", "pk", "code")) {
            if (!node.has(key) || node.isNull(key)) continue
            val value = node.opt(key)?.toString()?.removeSuffix(".0")?.trim().orEmpty()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    private fun jsonLong(node: JSONObject?, key: String): Long {
        if (node == null || !node.has(key) || node.isNull(key)) return 0L
        return when (val value = node.opt(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    /** Highlights tray: covers + reel ids. GraphQL first; the REST tray 403s with a web session. */
    private fun fetchHighlightsTray(userId: String, username: String): List<Highlight> {
        if (userId.isBlank()) return emptyList()
        val fromGql = runCatching { fetchHighlightsGraphql(userId, username) }.getOrDefault(emptyList())
        if (fromGql.isNotEmpty()) return fromGql
        return runCatching { fetchHighlightsRest(userId, username) }.getOrDefault(emptyList())
    }

    private fun fetchHighlightsGraphql(userId: String, username: String): List<Highlight> {
        val variables = JSONObject()
            .put("after", JSONObject.NULL)
            .put("before", JSONObject.NULL)
            .put("first", 50)
            .put("last", JSONObject.NULL)
            .put("max_highlights_to_fetch_on_pagination", JSONObject.NULL)
            .put("user_id", userId)
        val data = graphqlData(HIGHLIGHTS_DOC_ID, variables, "$WWW/$username/") ?: return emptyList()
        val edges = findEdges(data) ?: return emptyList()
        val list = ArrayList<Highlight>(edges.length())
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            val highlight = highlightFromNode(node) ?: continue
            list.add(highlight)
        }
        return list
    }

    private fun fetchHighlightsRest(userId: String, username: String): List<Highlight> {
        val url = "$WWW_API/highlights/$userId/highlights_tray/"
        val tray = parseIgJson(get(url, referer = "$WWW/$username/")).optJSONArray("tray") ?: return emptyList()
        val list = ArrayList<Highlight>(tray.length())
        for (i in 0 until tray.length()) {
            highlightFromNode(tray.getJSONObject(i))?.let { list.add(it) }
        }
        return list
    }

    private fun highlightFromNode(node: JSONObject): Highlight? {
        val rawId = asId(node).ifBlank { asId(node.optJSONObject("cover_media")) }
        val numeric = rawId.removePrefix("highlight:").substringBefore("_")
        if (numeric.isEmpty()) return null
        val cover = node.optJSONObject("cover_media")
        val coverUrl = cover?.optJSONObject("cropped_image_version")?.optString("url")
            ?: firstCandidate(cover?.optJSONObject("image_versions2"))
            ?: cover?.optString("thumbnail_src")?.ifBlank { null }
            ?: ""
        return Highlight(
            id = "highlight:$numeric",
            title = node.optString("title", ""),
            coverUrl = coverUrl
        )
    }

    /** Media inside a highlight (reelId = "highlight:..") or active story (reelId = userId). */
    suspend fun loadReelMedia(reelId: String): List<MediaItem> =
        withContext(Dispatchers.IO) { fetchReelMedia(reelId) }

    private fun fetchReelMedia(reelId: String): List<MediaItem> {
        val numeric = reelId.removePrefix("highlight:")
        val fromGql = runCatching { fetchReelGallery(numeric) }.getOrDefault(emptyList())
        if (fromGql.isNotEmpty()) return fromGql
        val restId = if (reelId.startsWith("highlight:")) reelId else numeric
        return runCatching { fetchReelMediaRest(restId) }.getOrDefault(emptyList())
    }

    /** First page of PolarisStoriesV3. [reelId] is a user id (story) or highlight id. */
    private fun fetchReelGallery(reelId: String): List<MediaItem> {
        if (reelId.isBlank()) return emptyList()
        val variables = JSONObject()
            .put("first", 20)
            .put("initial_reel_id", reelId)
            .put("last", JSONObject.NULL)
            .put("reel_ids", JSONArray().put(reelId))
        val data = graphqlData(REEL_GALLERY_DOC_ID, variables, "$WWW/") ?: return emptyList()
        val edges = data.optJSONObject("xdt_api__v1__feed__reels_media")?.optJSONArray("edges")
            ?: findEdges(data)
            ?: return emptyList()
        val out = ArrayList<MediaItem>()
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            val items = node.optJSONArray("items")
            if (items != null && items.length() > 0) {
                for (j in 0 until items.length()) collectFromV1Item(items.getJSONObject(j), out)
            } else {
                collectFromV1Item(node, out)
            }
        }
        return out
    }

    private fun fetchReelMediaRest(reelId: String): List<MediaItem> {
        val url = "$WWW_API/feed/reels_media/?reel_ids=" + java.net.URLEncoder.encode(reelId, "UTF-8")
        val json = parseIgJson(get(url))
        val reels = json.optJSONObject("reels")
        val reel = reels?.optJSONObject(reelId)
            ?: run {
                val keys = reels?.keys()
                if (keys != null && keys.hasNext()) reels.optJSONObject(keys.next()) else null
            }
        val items = reel?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<MediaItem>(items.length())
        for (i in 0 until items.length()) collectFromV1Item(items.getJSONObject(i), out)
        return out
    }

    private fun graphqlData(docId: String, variables: JSONObject, referer: String): JSONObject? {
        val form = FormBody.Builder()
            .add("doc_id", docId)
            .add("variables", variables.toString())
            .add("server_timestamps", "true")
            .build()
        val raw = get("$WWW/graphql/query", referer = referer, body = form)
        val json = runCatching { parseIgJson(raw) }.getOrNull() ?: return null
        if (json.optString("status") == "fail") return null
        return json.optJSONObject("data")
    }

    private fun parseIgJson(raw: String): JSONObject {
        val text = raw.trim().removePrefix("for (;;);").trim()
        return JSONObject(text)
    }

    /** First `edges` array under [root], a few levels down. */
    private fun findEdges(root: JSONObject): JSONArray? {
        fun walk(obj: JSONObject, depth: Int): JSONArray? {
            if (depth > 5) return null
            obj.optJSONArray("edges")?.let { if (it.length() > 0) return it }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val child = obj.optJSONObject(keys.next()) ?: continue
                walk(child, depth + 1)?.let { return it }
            }
            return null
        }
        return walk(root, 0)
    }

    // --- v1 media parsing ---------------------------------------------------

    /** Builds one Post (carousel becomes one tile with multiple items). */
    private fun v1ItemToPost(item: JSONObject): Post? {
        val items = ArrayList<MediaItem>()
        collectFromV1Item(item, items)
        if (items.isEmpty()) return null
        val id = asId(item).ifBlank { items.first().id }
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
        val isVideo = node.optInt("media_type", 0) == 2 || node.optBoolean("is_video", false)
        val image = firstCandidate(node.optJSONObject("image_versions2"))
            ?: node.optString("display_uri").ifBlank { null }
            ?: node.optString("display_url").ifBlank { null }
            ?: node.optString("thumbnail_src").ifBlank { null }
            ?: node.optString("thumbnail_url").ifBlank { null }
            ?: ""
        val video = if (isVideo) {
            node.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")
                ?: node.optString("video_url").ifBlank { null }
        } else null
        val id = asId(node).ifBlank { image.hashCode().toString() }
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
