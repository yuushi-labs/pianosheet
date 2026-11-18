package com.yuushilabs.pianosheet.core.update

/**
 * A version name such as `0.4.5-phase2`, split so two of them can be ordered.
 *
 * Releases are tagged with the `versionName` the app was built with
 * (`v0.4.5-phase2`), so the update check compares the tag against
 * `BuildConfig.VERSION_NAME`.  A plain string comparison would put `0.4.10`
 * below `0.4.9`; this splits the dotted numbers from the stage suffix instead.
 */
data class AppVersion(
    val numbers: List<Int>,
    /** Everything after the numbers: `-phase2`, `-beta1`, or "" for a plain release. */
    val suffix: String,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val width = maxOf(numbers.size, other.numbers.size)
        for (index in 0 until width) {
            val mine = numbers.getOrElse(index) { 0 }
            val theirs = other.numbers.getOrElse(index) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return compareSuffix(suffix, other.suffix)
    }

    /** `0.4.5 > 0.4.4-phase2`, `0.4.5-phase2 == 0.4.5-phase2`, `0.4.5 < 0.4.6-rad1`. */
    val display: String
        get() = numbers.joinToString(".") + suffix

    companion object {

        /**
         * Reads a tag, a release name or a version name.  A leading `v`, the
         * repository name or any other prefix is skipped up to the first digit,
         * so `v0.4.5-phase2`, `pianosheet-0.4.5-phase2` and `0.4.5-phase2` are
         * the same version.  At least two dot-separated numbers are required, so
         * a date or a build counter (`build-12`, `2026-11-18`) is not mistaken
         * for a version name; [UpdateDecision] then falls back to a declared
         * `versionCode`.  Null when the text is not a version.
         */
        fun parse(raw: String?): AppVersion? {
            val text = raw?.trim().orEmpty()
            val start = text.indexOfFirst { it.isDigit() }
            if (start < 0) return null

            var index = start
            val numbers = ArrayList<Int>()
            while (true) {
                val numberStart = index
                while (index < text.length && text[index].isDigit()) index++
                if (numberStart == index) break
                numbers.add(text.substring(numberStart, index).toIntOrNull() ?: break)
                if (index < text.length && text[index] == '.' &&
                    index + 1 < text.length && text[index + 1].isDigit()
                ) {
                    index++
                } else {
                    break
                }
            }
            if (numbers.size < 2) return null
            return AppVersion(numbers, suffixFrom(text.substring(index)))
        }

        private fun suffixFrom(rest: String): String {
            val trimmed = rest.trim().trimStart('-', '_', '+', '.')
            return if (trimmed.isEmpty()) "" else "-$trimmed"
        }

        /**
         * A plain release outranks a stage of the same numbers (`0.5.0` >
         * `0.5.0-rc1`); between stages the stage name is compared, then its
         * number, so `phase2` < `phase10`.
         */
        private fun compareSuffix(mine: String, theirs: String): Int {
            val a = mine.trimStart('-')
            val b = theirs.trimStart('-')
            if (a.equals(b, ignoreCase = true)) return 0
            if (a.isEmpty()) return 1
            if (b.isEmpty()) return -1

            val minePrefix = a.takeWhile { !it.isDigit() }.lowercase()
            val theirsPrefix = b.takeWhile { !it.isDigit() }.lowercase()
            if (minePrefix != theirsPrefix) return minePrefix.compareTo(theirsPrefix)

            val mineNumber = a.drop(minePrefix.length).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            val theirsNumber = b.drop(theirsPrefix.length).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            if (mineNumber != theirsNumber) return mineNumber.compareTo(theirsNumber)
            return a.compareTo(b, ignoreCase = true)
        }
    }
}
