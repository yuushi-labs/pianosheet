package com.yuushilabs.pianosheet.core.update

/** A newer release that the app can download and hand to the installer. */
data class UpdateInfo(
    /** The version to show, e.g. `0.4.5-phase2`. */
    val versionName: String,
    /** The `versionCode` the release notes declare, when they do. */
    val versionCode: Int?,
    /** The short "what changed" text shown in the dialog. */
    val notes: String,
    val apkUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val releaseUrl: String?,
)

/**
 * Whether the latest release is newer than the running build (spec: 自動アップ
 * デート).  Everything here is plain Kotlin so the comparison can be tested
 * against the payloads GitHub actually returns.
 */
object UpdateDecision {

    /**
     * The update to offer, or null when there is nothing newer to install:
     * same version, an older release, a draft/pre-release, or a release without
     * an APK attached.
     */
    fun decide(
        currentVersionName: String,
        currentVersionCode: Int,
        release: GithubRelease,
    ): UpdateInfo? {
        if (release.draft || release.prerelease) return null

        val apk = release.assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .filter { it.downloadUrl.startsWith("https://") }
            .maxByOrNull { it.sizeBytes }
            ?: return null
        if (apk.sizeBytes in 1..MIN_APK_BYTES) return null

        val declaredCode = declaredVersionCode(release.body)
            ?: versionCodeFromText(apk.name)
        val releaseVersion = AppVersion.parse(release.tagName)
            ?: AppVersion.parse(release.name)
        val currentVersion = AppVersion.parse(currentVersionName)

        // A tag such as `build-12` is not a version name; the release notes then
        // say which versionCode the APK carries.
        val newer = when {
            releaseVersion != null && currentVersion != null ->
                releaseVersion > currentVersion

            declaredCode != null -> declaredCode > currentVersionCode
            else -> false
        }
        if (!newer) return null

        return UpdateInfo(
            versionName = releaseVersion?.display
                ?: release.tagName?.removePrefix("v")
                ?: release.name
                ?: "build $declaredCode",
            versionCode = declaredCode,
            notes = summaryOf(release.body),
            apkUrl = apk.downloadUrl,
            apkFileName = apk.name,
            apkSizeBytes = apk.sizeBytes,
            releaseUrl = release.htmlUrl,
        )
    }

    /** `versionCode: 12`, `versionCode=12` or `versionCode 12` in the release notes. */
    fun declaredVersionCode(body: String?): Int? {
        val text = body ?: return null
        val match = Regex("""versionCode\s*[:=]?\s*(\d{1,9})""", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** `pianosheet-0.4.5-phase2-v12-debug.apk` carries its versionCode as `v12`. */
    private fun versionCodeFromText(text: String): Int? =
        Regex("""(?:^|[^a-z])v(\d{1,9})(?:[^0-9]|$)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The first few lines of the release notes, with the markdown decorations
     * removed, bounded so the dialog stays readable on a phone.
     */
    fun summaryOf(body: String?, maxLines: Int = MAX_NOTE_LINES, maxChars: Int = MAX_NOTE_CHARS): String {
        val lines = (body ?: "")
            .lineSequence()
            .map { it.trim() }
            .map { line ->
                line
                    .removePrefix(">")
                    .trim()
                    .trimStart('#', '-', '*', '+', ' ')
                    .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
                    .replace("**", "")
                    .replace("`", "")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            // A heading that only repeats the version number, and the machine
            // readable versionCode line, say nothing to the reader.
            .filterNot { it.matches(Regex("""v?\d+(\.\d+)*([- ]\w+)*""")) }
            .filterNot { it.matches(Regex("""(?i)versionCode\s*[:=]?\s*\d+""")) }
            .take(maxLines)
            .toList()
        val text = lines.joinToString("\n")
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
    }

    private const val MIN_APK_BYTES = 512L * 1024L
    private const val MAX_NOTE_LINES = 6
    private const val MAX_NOTE_CHARS = 400
}
