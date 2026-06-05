package com.media.instagramdownloader.model

/** A single downloadable media item (post thumbnail/full or highlight cover). */
data class MediaItem(
    val id: String,
    val displayUrl: String,      // full-res image to download / preview
    val thumbnailUrl: String,    // grid thumbnail
    val isVideo: Boolean,
    val videoUrl: String? = null // direct mp4 when isVideo
) {
    /** URL that should actually be saved to gallery. */
    val downloadUrl: String get() = if (isVideo && videoUrl != null) videoUrl else displayUrl
    val isVideoDownload: Boolean get() = isVideo && videoUrl != null
}

/** One Instagram post = one grid tile. Carousels hold multiple [items]. */
data class Post(
    val id: String,
    val items: List<MediaItem>
) {
    val cover: MediaItem get() = items.first()
    val isCarousel: Boolean get() = items.size > 1
    val isVideo: Boolean get() = cover.isVideo
}

data class Highlight(
    val id: String,        // reel id, e.g. "highlight:1798..."
    val title: String,
    val coverUrl: String
)

data class Profile(
    val username: String,
    val fullName: String,
    val biography: String,
    val profilePicUrl: String,
    val isPrivate: Boolean,
    val isVerified: Boolean,
    val postCount: Long,
    val followerCount: Long,
    val followingCount: Long,
    val highlights: List<Highlight>,
    val posts: List<Post>,
    val reels: List<Post>,
    /** Active story media (empty if none / not accessible). */
    val storyItems: List<MediaItem>,
    /** next_max_id for paginating more posts; null if no more. */
    val endCursor: String?,
    /** max_id for paginating more reels; null if no more. */
    val reelsCursor: String?,
    /** Internal numeric user id, needed for pagination. */
    val userId: String,
    /** Non-null when full post fetch was blocked (shows reason to user). */
    val postsNotice: String? = null
)
