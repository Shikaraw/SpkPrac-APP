package com.spkprac.app.net

import com.spkprac.app.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A published release that is newer than the running build. */
data class UpdateInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val apkSize: Long?,
    val publishedAt: String?
) {
    /** Direct download when possible, otherwise the GitHub release page. */
    val downloadUrl: String get() = apkUrl ?: releaseUrl
}

sealed interface UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Failed(val message: String?) : UpdateCheck
}

/** Version helpers plus the repository coordinates the updater talks to. */
object AppVersion {
    const val OWNER = "Shikaraw"
    const val REPO = "SpkPrac-APP"

    /** Release page shown in the browser. */
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    /** Current running version, e.g. "1.1.0". */
    val current: String get() = BuildConfig.VERSION_NAME

    /**
     * Compares two dotted version strings ("1.2.0" vs "1.10.0").
     * Returns a positive number when [a] is newer, negative when older, 0 when equal.
     */
    fun compare(a: String, b: String): Int {
        val left = a.removePrefix("v").split('.', '-', '+')
        val right = b.removePrefix("v").split('.', '-', '+')
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = left.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val r = right.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (l != r) return l - r
        }
        return 0
    }
}

/** How many releases are scanned per check. One page is enough for the release cadence. */
private const val RELEASE_PAGE_SIZE = 30

/** Reads a string field defensively: a missing/null/wrong-typed value becomes "". */
private fun JsonObject.stringField(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

/** Reads an optional string field defensively: absent/null becomes null. */
private fun JsonObject.optStringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

/** Reads a long field defensively: absent/null/non-numeric becomes null. */
private fun JsonObject.longField(name: String): Long? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

/** Reads a boolean field defensively: absent/null/wrong-typed becomes false. */
private fun JsonObject.booleanField(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.booleanOrNull ?: false

/**
 * Checks GitHub Releases for a newer build.
 *
 * GitHub's API is frequently slow or unreachable from mainland China, so every endpoint is tried
 * twice: directly and through a mirror prefix. Whichever answers first wins.
 */
class UpdateChecker(
    private val owner: String = AppVersion.OWNER,
    private val repo: String = AppVersion.REPO
) {
    companion object {
        /** Public GitHub API. */
        const val BASE_DIRECT = "https://api.github.com"

        /** Mirror that proxies the GitHub API verbatim (verified reachable, unlike several others). */
        const val BASE_MIRROR = "https://gh-proxy.com/https://api.github.com"

        /** Prefix used to accelerate release-asset downloads through the same mirror. */
        const val ASSET_MIRROR = "https://gh-proxy.com/"

        /** Android release tags are plain `vX.Y.Z`; pre-release/build suffixes are tolerated. */
        private val VERSION_TAG = Regex("""^v?\d+\.\d+\.\d+(?:[-+].*)?$""")

        /**
         * Picks the newest Android release out of a GitHub `GET /releases` array (spec §2.2).
         *
         * The API returns the array newest-first, so the first element that passes every filter is
         * the candidate build:
         *  1. `draft == false`;
         *  2. `tag_name` starts with a lowercase `v`;
         *  3. `tag_name` does not start with `pc-v` (the Windows channel);
         *  4. `tag_name` carries an `x.y.z` version.
         *
         * Returns `null` when no element qualifies; callers treat that as "already up to date".
         * Pure function (no network, no Android types) so the filter can be reasoned about / verified.
         */
        internal fun pickLatestRelease(releases: JsonArray): JsonObject? {
            for (element in releases) {
                val release = element as? JsonObject ?: continue
                // 1. drafts are never published.
                if (release.booleanField("draft")) continue
                val tag = release.stringField("tag_name")
                // 2 + 3. Android channel only; `pc-v1.2.0` never starts with `v`, but exclude it
                // explicitly because a careless `tag.removePrefix("v")` would otherwise match it.
                if (!isAndroidReleaseTag(tag)) continue
                // 4. a tag must resolve to a comparable x.y.z version.
                if (parseVersion(tag) == null) continue
                return release
            }
            return null
        }

        /** True for Android release tags (`v*`) and false for the Windows ones (`pc-v*`). */
        internal fun isAndroidReleaseTag(tag: String): Boolean =
            tag.startsWith("v") && !tag.startsWith("pc-v")

        /** `"v1.2.3"` -> `"1.2.3"`; null when [tag] is not an x.y.z version tag. */
        internal fun parseVersion(tag: String): String? =
            if (VERSION_TAG.matches(tag)) tag.removePrefix("v") else null

        /** Rewrites a GitHub asset URL through the mirror when the user enabled it. */
        fun acceleratedDownloadUrl(info: UpdateInfo, useProxy: Boolean): String {
            val raw = info.apkUrl ?: return info.releaseUrl
            return if (useProxy && !raw.startsWith(ASSET_MIRROR)) ASSET_MIRROR + raw else raw
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * @param preferProxy puts the mirror first (for users who cannot reach the GitHub API at all).
     */
    suspend fun check(preferProxy: Boolean): UpdateCheck {
        val bases = if (preferProxy) listOf(BASE_MIRROR, BASE_DIRECT)
        else listOf(BASE_DIRECT, BASE_MIRROR)

        var lastError: String? = null
        for (base in bases) {
            // `/releases/latest` is ordered by publish time, and this repository also ships Windows
            // builds (`pc-v*`). Publish a PC release after an Android one and "latest" points at the
            // PC tag, so the Android client wrongly concluded it was up to date (v1.3.0 spec §2.1).
            // Fetch the list instead and pick the Android tag ourselves.
            val url = "$base/repos/$owner/$repo/releases?per_page=$RELEASE_PAGE_SIZE"
            when (val result = fetchReleases(url)) {
                is UpdateCheck.Failed -> {
                    lastError = result.message
                    continue
                }

                else -> return result
            }
        }
        return UpdateCheck.Failed(lastError)
    }

    private fun fetchReleases(url: String): UpdateCheck {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SpkPrac/${AppVersion.current}")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                // 404 => the repository has no published release yet, which simply means "up to date".
                if (response.code == 404) return UpdateCheck.UpToDate
                if (!response.isSuccessful) {
                    return UpdateCheck.Failed("HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                parseReleases(body)
            }
        } catch (e: Exception) {
            UpdateCheck.Failed(e.message)
        }
    }

    private fun parseReleases(body: String): UpdateCheck {
        val releases = try {
            json.parseToJsonElement(body).jsonArray
        } catch (e: Exception) {
            return UpdateCheck.Failed(e.message)
        }
        // No release matching the Android channel simply means "already up to date" (spec §2.2).
        val release = pickLatestRelease(releases) ?: return UpdateCheck.UpToDate
        return try {
            val tag = release.stringField("tag_name")
            val version = parseVersion(tag) ?: return UpdateCheck.UpToDate
            if (AppVersion.compare(version, AppVersion.current) <= 0) {
                return UpdateCheck.UpToDate
            }

            val apkAsset = runCatching {
                release["assets"]?.jsonArray?.firstOrNull { element ->
                    val name = (element as? JsonObject)?.stringField("name").orEmpty()
                    name.endsWith(".apk", ignoreCase = true)
                } as? JsonObject
            }.getOrNull()

            UpdateCheck.Available(
                UpdateInfo(
                    version = version,
                    tag = tag,
                    notes = release.stringField("body"),
                    releaseUrl = release.stringField("html_url")
                        .ifBlank { AppVersion.RELEASES_PAGE },
                    apkUrl = apkAsset?.optStringField("browser_download_url"),
                    apkSize = apkAsset?.longField("size"),
                    publishedAt = release.optStringField("published_at")
                )
            )
        } catch (e: Exception) {
            UpdateCheck.Failed(e.message)
        }
    }
}
