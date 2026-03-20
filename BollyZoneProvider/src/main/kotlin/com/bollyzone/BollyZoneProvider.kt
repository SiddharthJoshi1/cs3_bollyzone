package com.bollyzone

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class BollyZoneProvider : MainAPI() {

    override var mainUrl = "https://www.bollyzone.to"
    override var name = "BollyZone"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // ---------------------------------------------------------------------------
    // HOME PAGE
    // ---------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/series/page/"          to "Latest Episodes",
        "$mainUrl/tv-channels/#starplus" to "Star Plus",
        "$mainUrl/tv-channels/#sonytv"   to "Sony TV",
        "$mainUrl/tv-channels/#colorstv" to "Colors TV",
        "$mainUrl/tv-channels/#zeetv"    to "Zee TV",
        "$mainUrl/tv-channels/#sabtv"    to "SAB TV",
        "$mainUrl/tv-channels/#andtv"    to "And TV",
        "$mainUrl/tv-channels/#mtv"      to "MTV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data

        if (url.contains("#")) {
            // These are channel lists on a single static page, so there's no pagination.
            // If Cloudstream asks for page 2+, return an empty list to stop it from loading infinitely.
            if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

            val baseUrl = url.substringBefore("#")
            val anchor = url.substringAfter("#")
            val document = app.get(baseUrl).document

            // Find the carousel immediately following the header with the matching anchor name
            // e.g. <h2 class="Title"><a name="colorstv">...</a></h2> <div class="MovieListTop owl-carousel">...</div>
            val items = document.select("h2:has(a[name=$anchor]) + div.MovieListTop")
                .select(".TPostMv, .TPost, .ml-item")
                .mapNotNull { it.toEpisodeSearchResult() }
                .distinctBy { it.url }

            return newHomePageResponse(request.name, items, hasNext = false)

        } else {
            // Paginated "Latest Episodes" (/series/page/1/)
            val document = app.get("$url$page/").document

            val items = document.select(".TPostMv, .TPost, .ml-item")
                .mapNotNull { it.toEpisodeSearchResult() }
                .distinctBy { it.url }

            // Check if there is a "Next" button on the page to allow Cloudstream to load more
            val hasNext = document.selectFirst("a.next, .pagination a[rel=next]") != null

            return newHomePageResponse(request.name, items, hasNext)
        }
    }

    // ---------------------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        return document.select(".TPostMv, .TPost, .ml-item")
            .mapNotNull { it.toEpisodeSearchResult() }
            .distinctBy { it.url }
    }

    // ---------------------------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        return if (url.contains("/category/")) {
            loadShowPage(url)
        } else {
            val document = app.get(url).document
            val categoryUrl = document
                .selectFirst("a[href*='/category/']")
                ?.attr("href")

            if (categoryUrl != null) loadShowPage(categoryUrl)
            else loadSingleEpisode(url, document)
        }
    }

    // ---------------------------------------------------------------------------
    // LOAD LINKS
    // ---------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val watchLinks = document.select("a[href*='groundbanks.net']")
        if (watchLinks.isEmpty()) return false

        watchLinks.amap { linkEl ->
            val groundbanksUrl = linkEl.attr("href").takeIf { it.isNotBlank() } ?: return@amap

            // Extract the server name and quality from the OptionBx div
            val optionBox = linkEl.closest(".OptionBx")
            val serverName = optionBox?.selectFirst(".AAIco-dns")?.text()?.trim() ?: "BollyZone"
            val qualityLabel = optionBox?.selectFirst(".AAIco-equalizer")?.text()?.trim() ?: "HD"
            val sourceLabel = "$serverName $qualityLabel"

            try {
                // ── Hop 1: Fetch item.php → get the freeshorturls button href ──
                val itemPage = app.get(
                    groundbanksUrl,
                    referer = mainUrl,
                    headers = mapOf("User-Agent" to USER_AGENT) // MUST use enforced UA
                ).document

                val freeShortsUrl = itemPage
                    .selectFirst("a.button.button1, a.button1")
                    ?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@amap

                // ── Hop 2: Extract type and token from freeshorturls path ──────
                val pathParts = freeShortsUrl.trimEnd('/').split('/')
                val token = pathParts.last()
                val type = pathParts.dropLast(1).last()

                // ── Hop 3: GET the flow.tvlogy.to player page ─────────────────
                val playerUrl = "https://flow.tvlogy.to/$type/$token/"
                val playerPage = app.get(
                    playerUrl,
                    referer = groundbanksUrl,
                    headers = mapOf("User-Agent" to USER_AGENT) // MUST use enforced UA
                ).text

                // ── Hop 4: Extract .m3u8 URL from Video.js sources config ─────
                // Handled escaped slashes like https:\/\/parrot.tvlogy.to\/...
                val m3u8Url = Regex(""""src"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
                    .find(playerPage)
                    ?.groupValues?.get(1)
                    ?.replace("\\/", "/") // Unescape slashes!
                    ?: return@amap

                val qualityStr = Regex(""""label"\s*:\s*"([^"]+)"""")
                    .find(playerPage)
                    ?.groupValues?.get(1)
                    ?: qualityLabel

                val videoTitle = Regex("""<title>([^<]+)</title>""")
                    .find(playerPage)
                    ?.groupValues?.get(1)
                    ?.removeSuffix(".mp4")
                    ?.trim()
                    ?: qualityStr

                val qualityVal = when (qualityStr.uppercase()) {
                    "FHD", "1080P" -> Qualities.P1080.value
                    "HD", "720P" -> Qualities.P720.value
                    "SD", "480P" -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }

                // ── Hop 5: Emit the stream link ───────────────────────────────
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$sourceLabel - $videoTitle",
                        url = m3u8Url,
                    ) {
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                        this.referer = playerUrl
                        this.quality = qualityVal
                    }
                )

            } catch (e: Exception) {
                logError(e)
            }
        }

        return true
    }

    // ---------------------------------------------------------------------------
    // PRIVATE HELPERS
    // ---------------------------------------------------------------------------

    private suspend fun loadShowPage(categoryUrl: String): TvSeriesLoadResponse {
        val firstPageDoc = app.get(categoryUrl).document

        val showTitle = firstPageDoc
            .selectFirst("h1.Title, h2.Title, h1, .page-title")
            ?.text()?.trim()
            ?: categoryUrl
                .substringAfterLast("/category/")
                .trimEnd('/')
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }

        val posterUrl = firstPageDoc
            .selectFirst(".TPost img, .ml-item img")
            ?.let { img -> img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src") }

        val episodes = mutableListOf<Episode>()
        var pageNum = 1
        var hasMore = true

        // Prevent Cloudstream from timing out if a show has 50+ pages.
        val maxPagesToFetch = 4

        while (hasMore && pageNum <= maxPagesToFetch) {
            val pageUrl = if (pageNum == 1) categoryUrl
            else "${categoryUrl.trimEnd('/')}/page/$pageNum/"
            val pageDoc = if (pageNum == 1) firstPageDoc else app.get(pageUrl).document

            val cards = pageDoc.select(".TPostMv, .TPost, .ml-item")
            if (cards.isEmpty()) break

            val newEpisodes = cards.mapNotNull { card ->
                val aTag = card.selectFirst("a") ?: return@mapNotNull null
                val epUrl = aTag.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (epUrl == "#") return@mapNotNull null

                val epTitle = card.selectFirst("h2.Title, h2, h3, .title, .post-title")?.text()?.trim()
                    ?: aTag.text().trim().takeIf { it.isNotBlank() }
                    ?: aTag.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: epUrl.substringAfterLast("/").replace("-", " ")

                val imgTag = card.selectFirst("img")
                val epPoster = imgTag?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: imgTag?.attr("src")

                val dateStr = epTitle.extractDateOrNull()

                newEpisode(epUrl) {
                    this.name        = epTitle
                    this.posterUrl   = epPoster
                    this.description = dateStr
                }
            }.distinctBy { it.data } // Remove duplicate elements from nested classes

            episodes.addAll(newEpisodes)

            hasMore = pageDoc.selectFirst("a.next, .pagination a[rel=next]") != null
            pageNum++
        }

        return newTvSeriesLoadResponse(showTitle, categoryUrl, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
        }
    }

    private suspend fun loadSingleEpisode(url: String, document: org.jsoup.nodes.Document): TvSeriesLoadResponse {
        val title = document.selectFirst("h1.Title")?.text()?.trim() ?: url

        // Grab the high-res background poster if available, fallback to the center poster
        val poster = document.selectFirst(".Image img.TPostBg")?.attr("src")
            ?: document.selectFirst("center img")?.attr("src")

        val plot = document.selectFirst(".Description > p")?.text()?.trim()
        val genres = document.select(".Description p.Genre a").map { it.text().trim() }

        // Find recommendations from "More titles like this"
        val recommendations = document.select(".MovieListTop.Serie .TPostMv").mapNotNull {
            it.toEpisodeSearchResult()
        }

        val episode = newEpisode(url) {
            this.name      = title
            this.posterUrl = poster
            this.description = plot
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.recommendations = recommendations
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val aTag = selectFirst("a") ?: return null
        val href = aTag.attr("href").takeIf { it.isNotBlank() } ?: return null

        // Skip links that are just containers/placeholders
        if (href == "#") return null

        val title = selectFirst("h2.Title, h2, h3, h4, .title, .post-title")?.text()?.trim()
            ?: aTag.text().trim().takeIf { it.isNotBlank() }
            ?: aTag.attr("title").trim()

        if (title.isBlank()) return null

        // Check for lazy-loaded images (owl-lazy)
        val img = selectFirst("img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun String.extractDateOrNull(): String? =
        Regex("""\d{1,2}(?:st|nd|rd|th)\s+\w+\s+\d{4}""").find(this)?.value

    companion object {
        // User-Agent is important — flow.tvlogy.to encodes it into the .m3u8 token
        // so we must use the same UA for both fetching the player page and playback
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"
    }
}