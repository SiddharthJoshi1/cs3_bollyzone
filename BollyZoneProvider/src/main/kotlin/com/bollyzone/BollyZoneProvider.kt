package com.bollyzone

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        "$mainUrl/series/page/"             to "Latest Episodes",
        "$mainUrl/category/sab-tv/page/"    to "SAB TV",
        "$mainUrl/category/sony-tv/page/"   to "Sony TV",
        "$mainUrl/category/star-plus/page/" to "Star Plus",
        "$mainUrl/category/colors-tv/page/" to "Colors TV",
        "$mainUrl/category/zee-tv/page/"    to "Zee TV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page/").document
        val items = document.select("li.ml-item, div.ml-item")
            .mapNotNull { it.toEpisodeSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ---------------------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.encodeUri()}").document
        return document.select("li.ml-item, div.ml-item")
            .mapNotNull { it.toEpisodeSearchResult() }
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
    //
    // Fully confirmed chain (all responses inspected):
    //
    //   1. bollyzone.to episode page
    //      → <a href="https://groundbanks.net/item.php?id=XXXXX">
    //
    //   2. groundbanks.net/item.php?id=XXXXX  [needs Referer: bollyzone.to]
    //      → <a class="button button1"
    //             href="https://route.freeshorturls.com/g/nflix/{TOKEN}">
    //
    //   3. The token in the freeshorturls URL is the SAME token used by the
    //      final player. So we can skip the redirect chain entirely and
    //      construct the embed URL directly:
    //        https://flow.tvlogy.to/{type}/{TOKEN}/
    //
    //   4. flow.tvlogy.to/{type}/{TOKEN}/ returns a plain HTML page with a
    //      Video.js config containing the .m3u8 stream URL:
    //
    //      var config = {
    //        sources: [{"src":"https://parrot.tvlogy.to/.../.../video.m3u8?token=...",
    //                   "type":"application/x-mpegURL", "label":"HD"}],
    //        ...
    //      }
    //
    //      The token in the .m3u8 URL is Base64(UserAgent||IP) — it is bound
    //      to the requesting device's UA and IP. This is fine for local playback
    //      since CloudStream fetches and plays from the same device.
    //
    // Strategy:
    //   Hop 1 → Fetch item.php (with Referer), extract freeshorturls button href
    //   Hop 2 → Parse token from freeshorturls URL path
    //   Hop 3 → GET flow.tvlogy.to/{type}/{token}/
    //   Hop 4 → Regex the .m3u8 src from the sources JSON in the page
    //   Hop 5 → Emit as ExtractorLink
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

        watchLinks.apmap { linkEl ->
            val groundbanksUrl = linkEl.attr("href").takeIf { it.isNotBlank() } ?: return@apmap

            val sourceLabel = linkEl.closest("tr")
                ?.select("td")
                ?.dropLast(1)
                ?.joinToString(" ") { it.text().trim() }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "BollyZone"

            try {
                // ── Hop 1: Fetch item.php → get the freeshorturls button href ──
                val itemPage = app.get(
                    groundbanksUrl,
                    referer = mainUrl,
                    headers = mapOf("User-Agent" to USER_AGENT)
                ).document

                // <a class="button button1" href="https://route.freeshorturls.com/g/nflix/TOKEN">
                val freeShortsUrl = itemPage
                    .selectFirst("a.button.button1, a.button1")
                    ?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@apmap

                // ── Hop 2: Extract type and token from freeshorturls path ──────
                // Pattern: https://route.freeshorturls.com/g/{type}/{token}
                // e.g.:    https://route.freeshorturls.com/g/nflix/4ttfIygLaxn4XkG
                val pathParts = freeShortsUrl.trimEnd('/').split('/')
                val token = pathParts.last()
                val type  = pathParts.dropLast(1).last() // "nflix"

                // ── Hop 3: GET the flow.tvlogy.to player page ─────────────────
                // No cookies needed — confirmed 200 OK without them
                val playerUrl  = "https://flow.tvlogy.to/$type/$token/"
                val playerPage = app.get(
                    playerUrl,
                    referer = groundbanksUrl,
                    headers = mapOf("User-Agent" to USER_AGENT)
                ).text

                // ── Hop 4: Extract .m3u8 URL from Video.js sources config ─────
                // var config = { sources: [{"src":"https://parrot.tvlogy.to/.../video.m3u8?token=..."}] }
                val m3u8Url = Regex(""""src"\s*:\s*"(https://[^"]+\.m3u8[^"]*)"""")
                    .find(playerPage)
                    ?.groupValues?.get(1)
                    ?: return@apmap

                // Also extract quality label if present ("HD", "FHD", etc.)
                val quality = Regex(""""label"\s*:\s*"([^"]+)"""")
                    .find(playerPage)
                    ?.groupValues?.get(1)
                    ?: sourceLabel

                // Extract video title from page <title> for display
                val videoTitle = Regex("""<title>([^<]+)</title>""")
                    .find(playerPage)
                    ?.groupValues?.get(1)
                    ?.removeSuffix(".mp4")
                    ?.trim()
                    ?: quality

                // ── Hop 5: Emit the stream link ───────────────────────────────
                callback(
                    newExtractorLink(
                        source  = "BollyZone ($quality)",
                        name    = videoTitle,
                        url     = m3u8Url,
                        referer = playerUrl
                    ) {
                        this.quality = when (quality.uppercase()) {
                            "FHD", "1080P" -> Qualities.P1080.value
                            "HD",  "720P"  -> Qualities.P720.value
                            "SD",  "480P"  -> Qualities.P480.value
                            else           -> Qualities.Unknown.value
                        }
                        this.isM3u8 = true
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
            .selectFirst("h2.cat-title, h1, .page-title, header h2")
            ?.text()?.trim()
            ?: categoryUrl
                .substringAfterLast("/category/")
                .trimEnd('/')
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }

        val posterUrl = firstPageDoc
            .selectFirst("li.ml-item img, div.ml-item img")
            ?.attr("src")

        val episodes = mutableListOf<Episode>()
        var pageNum = 1
        var hasMore = true

        while (hasMore) {
            val pageUrl = if (pageNum == 1) categoryUrl
                          else "${categoryUrl.trimEnd('/')}/page/$pageNum/"
            val pageDoc = if (pageNum == 1) firstPageDoc else app.get(pageUrl).document

            val cards = pageDoc.select("li.ml-item, div.ml-item")
            if (cards.isEmpty()) break

            cards.forEach { card ->
                val epUrl    = card.selectFirst("a")?.attr("href") ?: return@forEach
                val epTitle  = card.selectFirst("h2")?.text()?.trim()
                    ?: epUrl.substringAfterLast("/").replace("-", " ")
                val epPoster = card.selectFirst("img")?.attr("src")
                val dateStr  = epTitle.extractDateOrNull()

                episodes.add(newEpisode(epUrl) {
                    this.name        = epTitle
                    this.posterUrl   = epPoster
                    this.description = dateStr
                })
            }

            hasMore = pageDoc.selectFirst("a.next, .pagination a[rel=next]") != null
            pageNum++
        }

        return newTvSeriesLoadResponse(showTitle, categoryUrl, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
        }
    }

    private fun loadSingleEpisode(url: String, document: org.jsoup.nodes.Document): TvSeriesLoadResponse {
        val title  = document.selectFirst("h1")?.text()?.trim() ?: url
        val poster = document.selectFirst("img.film-poster-img, .poster img")?.attr("src")
        val episode = newEpisode(url) {
            this.name      = title
            this.posterUrl = poster
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
            this.posterUrl = poster
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val href   = selectFirst("a")?.attr("href") ?: return null
        val title  = selectFirst("h2")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.attr("src")
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
