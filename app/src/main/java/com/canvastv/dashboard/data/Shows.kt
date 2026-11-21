package com.canvastv.dashboard.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.canvastv.dashboard.model.Tile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A streaming service: the web addresses its show links use, and its TV app(s), best first. */
data class StreamingService(val name: String, val hosts: List<String>, val packages: List<String>)

/**
 * Show shortcuts. A show is saved as the link to it on the service's website (what you get from
 * "Share" or the address bar). Opening it hands that link to the service's TV app, which shows
 * that show's page, the same way a link from a phone or Google TV's own search does.
 */
object Shows {
    val SERVICES = listOf(
        StreamingService("Netflix", listOf("netflix.com"), listOf("com.netflix.ninja", "com.netflix.mediaclient")),
        StreamingService("YouTube", listOf("youtube.com", "youtu.be"), listOf("com.google.android.youtube.tv", "com.google.android.youtube")),
        StreamingService("Disney+", listOf("disneyplus.com"), listOf("com.disney.disneyplus")),
        StreamingService("Hulu", listOf("hulu.com"), listOf("com.hulu.livingroomplus", "com.hulu.plus")),
        StreamingService("Prime Video", listOf("primevideo.com", "amazon.com", "amazon.co.uk", "amazon.ca"), listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")),
        StreamingService("Max", listOf("max.com", "hbomax.com"), listOf("com.wbd.stream", "com.hbo.hbonow")),
        StreamingService("Paramount+", listOf("paramountplus.com"), listOf("com.cbs.ott", "com.cbs.app")),
        StreamingService("Peacock", listOf("peacocktv.com"), listOf("com.peacocktv.peacockandroid")),
        StreamingService("Apple TV", listOf("tv.apple.com"), listOf("com.apple.atve.androidtv.appletv")),
        StreamingService("Plex", listOf("plex.tv"), listOf("com.plexapp.android")),
        StreamingService("Crunchyroll", listOf("crunchyroll.com"), listOf("com.crunchyroll.crunchyroid")),
        StreamingService("Tubi", listOf("tubitv.com"), listOf("com.tubitv")),
        StreamingService("Pluto TV", listOf("pluto.tv"), listOf("tv.pluto.android")),
        StreamingService("Twitch", listOf("twitch.tv"), listOf("tv.twitch.android.app")),
        StreamingService("Spotify", listOf("open.spotify.com"), listOf("com.spotify.tv.android", "com.spotify.music")),
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun serviceFor(link: String): StreamingService? {
        val h = hostOf(normalize(link))
        return SERVICES.firstOrNull { s -> s.hosts.any { h == it || h.endsWith(".$it") } }
    }

    /** "netflix.com/title/123" → "https://netflix.com/title/123". */
    fun normalize(link: String): String = link.trim().let { if (it.isEmpty() || "://" in it) it else "https://$it" }

    /** Netflix's title number from a title or watch link. */
    fun netflixId(link: String): String? = Regex("""netflix\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:title|watch)/(\d+)""").find(link)?.groupValues?.get(1)

    /** Pure-Kotlin host parsing so it works in unit tests too. */
    internal fun hostOf(link: String): String =
        Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)""").find(link)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.").orEmpty()

    /** The app that will open [tile]: the one chosen in Configure, else the service's installed app. */
    fun appFor(ctx: Context, tile: Tile): String? {
        tile.cfg("pkg").takeIf { it.isNotBlank() }?.let { return it }
        return serviceFor(tile.cfg("link"))?.packages?.firstOrNull { Apps.isInstalled(ctx, it) }
    }

    /** Ways to open the show, most specific first. */
    fun intents(link: String, pkg: String?): List<Intent> {
        val url = normalize(link)
        val out = mutableListOf<Intent>()
        val nf = netflixId(url)
        if (nf != null) {
            // Netflix's TV app opens a title page from its web link, given a "source" hint.
            out += Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/title/$nf")).putExtra("source", "30").also { if (pkg != null) it.setPackage(pkg) }
            out += Intent(Intent.ACTION_VIEW, Uri.parse("nflx://www.netflix.com/title/$nf")).also { if (pkg != null) it.setPackage(pkg) }
        } else if (pkg != null) {
            out += Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
        }
        // Let Android pick whichever app claims the link.
        out += Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        return out
    }

    /** Opens the show. Falls back to just opening its app. Returns false if nothing worked. */
    fun open(ctx: Context, tile: Tile): Boolean {
        val link = tile.cfg("link")
        val pkg = appFor(ctx, tile)
        if (link.isNotBlank()) {
            for (i in intents(link, pkg)) {
                // Don't hand the show to a web browser: on a TV that's never what's wanted.
                if (i.`package` == null) {
                    val target = ctx.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName
                    if (target == null || target == "android" || isBrowser(target)) continue
                }
                try {
                    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                } catch (e: ActivityNotFoundException) {
                    continue
                } catch (e: SecurityException) {
                    Log.w("CanvasTV", "Show link refused", e)
                }
            }
        }
        return pkg != null && Apps.launch(ctx, pkg)
    }

    private fun isBrowser(pkg: String) = listOf("chrome", "browser", "firefox", "webview", "puffin", "tvbro").any { pkg.contains(it, true) }

    /** Reads a show page's title and picture from its Open Graph tags, as {"title":…, "image":…}. */
    suspend fun fetchInfo(link: String): String {
        val html = httpRequest(
            normalize(link),
            headers = mapOf(
                // Several services only include the tags for ordinary browsers.
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
                "Accept-Language" to "en-US,en;q=0.8",
            ),
        )
        val (title, image) = parseOpenGraph(html)
        if (title == null && image == null) error("No picture found on that page")
        return buildJsonObject {
            put("title", JsonPrimitive(title.orEmpty()))
            put("image", JsonPrimitive(image.orEmpty()))
        }.toString()
    }

    fun parseInfo(text: String?): Pair<String, String> = runCatching {
        val o = json.parseToJsonElement(text!!).jsonObject
        o["title"]!!.jsonPrimitive.content to o["image"]!!.jsonPrimitive.content
    }.getOrDefault("" to "")
}

/** og:title and og:image (or twitter:image) from a page, with HTML entities decoded. */
fun parseOpenGraph(html: String): Pair<String?, String?> {
    val metas = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html.take(400_000)).map { it.value }.toList()
    fun attr(tag: String, name: String) =
        Regex("""\b$name\s*=\s*(["'])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(tag)?.groupValues?.get(2)
    fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
        metas.firstOrNull { (attr(it, "property") ?: attr(it, "name"))?.equals(k, true) == true }?.let { attr(it, "content") }
    }?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }
    val title = meta("og:title", "twitter:title")
        ?: Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }
    val image = meta("og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src")
    return cleanTitle(title) to image
}

/** "Watch Severance | Apple TV+" → "Severance". */
fun cleanTitle(t: String?): String? = t
    ?.replace(Regex("""^(Watch|Stream)\s+""", RegexOption.IGNORE_CASE), "")
    ?.replace(Regex("""\s*[|–—-]\s*(Netflix|Hulu|Disney\+|Prime Video|Max|Paramount\+|Peacock|Apple TV\+?|YouTube|Crunchyroll|Tubi|Plex).*$""", RegexOption.IGNORE_CASE), "")
    ?.replace(Regex("""\s+\((TV Series|Official Site)\)$""", RegexOption.IGNORE_CASE), "")
    ?.trim()?.takeIf { it.isNotEmpty() }

private fun decodeEntities(s: String): String = s
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
    .replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
