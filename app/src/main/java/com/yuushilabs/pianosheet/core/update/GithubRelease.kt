package com.yuushilabs.pianosheet.core.update

/** One file attached to a release; the APK is the one that ends in `.apk`. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String?,
)

/** The fields of `GET /repos/{owner}/{repo}/releases/latest` the app reads. */
data class GithubRelease(
    val tagName: String?,
    val name: String?,
    val body: String?,
    val prerelease: Boolean,
    val draft: Boolean,
    val htmlUrl: String?,
    val assets: List<ReleaseAsset>,
)

object GithubReleaseJson {

    /**
     * The release in [text], or null when the document is not a release at all
     * (a 404 body, an HTML error page, a truncated download).  Unpromoted
     * pre-releases are parsed here and dropped by [UpdateDecision].
     */
    fun parse(text: String?): GithubRelease? {
        val root = Json.parseOrNull(text ?: return null) ?: return null
        if (root !is JsonValue.Obj) return null
        val tagName = root["tag_name"]?.asString()
        val name = root["name"]?.asString()
        val assets = root["assets"]?.asArray().orEmpty().mapNotNull { item ->
            val assetName = item["name"]?.asString() ?: return@mapNotNull null
            val url = item["browser_download_url"]?.asString() ?: return@mapNotNull null
            ReleaseAsset(
                name = assetName,
                downloadUrl = url,
                sizeBytes = item["size"]?.asLong() ?: 0L,
                contentType = item["content_type"]?.asString(),
            )
        }
        if (tagName.isNullOrBlank() && assets.isEmpty()) return null
        return GithubRelease(
            tagName = tagName,
            name = name,
            body = root["body"]?.asString(),
            prerelease = root["prerelease"]?.asBoolean() ?: false,
            draft = root["draft"]?.asBoolean() ?: false,
            htmlUrl = root["html_url"]?.asString(),
            assets = assets,
        )
    }
}
