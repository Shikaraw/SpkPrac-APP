package com.spkprac.app.net

import com.spkprac.app.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            val url = "$base/repos/$owner/$repo/releases/latest"
            when (val result = fetchLatest(url)) {
                is UpdateCheck.Failed -> {
                    lastError = result.message
                    continue
                }

                else -> return result
            }
        }
        return UpdateCheck.Failed(lastError)
    }

    private fun fetchLatest(url: String): UpdateCheck {
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
                parseLatest(body)
            }
        } catch (e: Exception) {
            UpdateCheck.Failed(e.message)
        }
    }

    private fun parseLatest(body: String): UpdateCheck {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return UpdateCheck.Failed(e.message)
        }
        return try {
            val draft = root["draft"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val published = root["published_at"]?.jsonPrimitive?.contentOrNull
            val version = tag.removePrefix("v").ifBlank { "0.0.0" }

            if (draft) return UpdateCheck.UpToDate

            val apkAsset = runCatching {
                root["assets"]?.jsonArray?.firstOrNull { element ->
                    val name = element.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    name.endsWith(".apk", ignoreCase = true)
                }?.jsonObject
            }.getOrNull()

            val apkUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
            val apkSize = apkAsset?.get("size")?.jsonPrimitive?.contentOrNull?.toLongOrNull()

            if (AppVersion.compare(version, AppVersion.current) > 0) {
                UpdateCheck.Available(
                    UpdateInfo(
                        version = version,
                        tag = tag,
                        notes = notes,
                        releaseUrl = htmlUrl.ifBlank { AppVersion.RELEASES_PAGE },
                        apkUrl = apkUrl,
                        apkSize = apkSize,
                        publishedAt = published
                    )
                )
            } else {
                UpdateCheck.UpToDate
            }
        } catch (e: Exception) {
            UpdateCheck.Failed(e.message)
        }
    }
}
