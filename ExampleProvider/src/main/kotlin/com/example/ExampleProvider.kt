package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

@Suppress("UNCHECKED_CAST")
class ExampleProvider : MainAPI() {
    override var name = "DhakaMovie BDIX"
    override var lang = "bn"
    override var mainUrl = "http://dhakamovie.com:8080"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val apiEndpoint = "$mainUrl/api/movies"
    private val mapper = jacksonObjectMapper()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept" to "application/json",
        "Referer" to mainUrl
    )

    // Store movie data temporarily between search and load
    private val movieCache = mutableMapOf<String, Map<String, Any>>()

    override suspend fun search(query: String): List<SearchResponse> {
        val allMovies = mutableListOf<Map<String, Any>>()

        // Fetch first 3 pages
        for (page in 1..3) {
            val url = "$apiEndpoint?page=$page"
            val response = app.get(url, headers = headers).text
            val json = mapper.readValue<Map<String, Any>>(response)
            val movies = json["data"] as? List<Map<String, Any>> ?: break
            allMovies.addAll(movies)
            if (movies.size < 12) break
        }

        // Filter by query
        val filtered = allMovies.filter { movie ->
            val title = movie["title"] as? String ?: ""
            title.contains(query, ignoreCase = true)
        }

        return filtered.mapNotNull { movie ->
            val id = movie["id"]?.toString() ?: return@mapNotNull null
            val title = movie["title"] as? String ?: return@mapNotNull null
            val poster = movie["poster_url"] as? String ?: ""
            val year = (movie["year"] as? String)?.toIntOrNull()

            // Cache the full movie data for later use in load()
            movieCache[id] = movie

            newMovieSearchResponse(title, "movie:$id", TvType.Movie, false) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // Extract ID from our custom URL scheme "movie:$id"
        val id = url.removePrefix("movie:")
        val movie = movieCache[id] ?: throw Error("Movie data not found in cache")

        val title = movie["title"] as? String ?: throw Error("No title")
        val plot = movie["overview"] as? String ?: ""
        val year = (movie["year"] as? String)?.toIntOrNull()
        val poster = movie["poster_url"] as? String ?: ""
        val backdrop = movie["backdrop_url"] as? String ?: ""
        val rating = (movie["rating"] as? String)?.toDoubleOrNull()
        val duration = (movie["runtime"] as? String)?.toIntOrNull()
        val director = movie["director"] as? String ?: ""
        val genresStr = movie["genres"] as? String ?: ""
        val genres = genresStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val streamUrl = movie["stream_url"] as? String ?: ""

        return newMovieLoadResponse(title, streamUrl, TvType.Movie, streamUrl) {
            this.plot = plot
            this.year = year
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.duration = duration
            if (director.isNotBlank()) {
                this.tags = listOf("Director: $director")
            }
            if (genres.isNotEmpty()) {
                this.genres = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data contains the direct stream URL from load()
        val streamUrl = data

        val quality = when {
            streamUrl.contains("1080") -> 1080
            streamUrl.contains("720") -> 720
            streamUrl.contains("480") -> 480
            else -> 0
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Direct",
                url = streamUrl
            ) {
                this.referer = mainUrl
                this.quality = quality
            }
        )
        return true
    }

    // Optional: Main page with categories
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$apiEndpoint?page=$page"
        val response = app.get(url, headers = headers).text
        val json = mapper.readValue<Map<String, Any>>(response)
        val movies = json["data"] as? List<Map<String, Any>> ?: return HomePageResponse(listOf())

        val list = movies.mapNotNull { movie ->
            val id = movie["id"]?.toString() ?: return@mapNotNull null
            val title = movie["title"] as? String ?: return@mapNotNull null
            val poster = movie["poster_url"] as? String ?: ""
            movieCache[id] = movie

            newMovieSearchResponse(title, "movie:$id", TvType.Movie, false) {
                this.posterUrl = poster
            }
        }

        return HomePageResponse(listOf(HomePageList("Latest Movies", list)))
    }
}