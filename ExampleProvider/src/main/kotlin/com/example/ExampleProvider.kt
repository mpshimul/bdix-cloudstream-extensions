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

    companion object {
        val movieCache = mutableMapOf<String, Map<String, Any>>()
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept" to "application/json",
        "Referer" to mainUrl
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val allMovies = mutableListOf<Map<String, Any>>()

        for (page in 1..2) {
            try {
                val url = "$apiEndpoint?page=$page"
                val response = app.get(url, headers = headers).text
                val json = mapper.readValue<Map<String, Any>>(response)
                val movies = json["data"] as? List<Map<String, Any>> ?: break
                allMovies.addAll(movies)
                if (movies.size < 12) break
            } catch (e: Exception) {
                break
            }
        }

        val filtered = if (query.isNotBlank() && query != "a") {
            allMovies.filter { movie ->
                val title = movie["title"] as? String ?: ""
                title.contains(query, ignoreCase = true)
            }
        } else {
            allMovies
        }

        return filtered.mapNotNull { movie ->
            val id = movie["id"]?.toString() ?: return@mapNotNull null
            val title = movie["title"] as? String ?: return@mapNotNull null
            val poster = movie["poster_url"] as? String ?: ""
            val year = (movie["year"] as? String)?.toIntOrNull()

            // Cache the full movie data
            movieCache[id] = movie

            newMovieSearchResponse(title, id, TvType.Movie, false) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // url is the movie ID (e.g., "36531")
        val movie = movieCache[url] ?: throw Error("Movie not found. ID: $url")

        val title = movie["title"] as? String ?: "Unknown"
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

            val tags = mutableListOf<String>()
            if (director.isNotBlank()) tags.add("Director: $director")
            tags.addAll(genres)
            if (tags.isNotEmpty()) this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$apiEndpoint?page=$page"
        val response = app.get(url, headers = headers).text
        val json = mapper.readValue<Map<String, Any>>(response)
        val movies = json["data"] as? List<Map<String, Any>> ?: return newHomePageResponse(listOf())

        val list = movies.mapNotNull { movie ->
            val id = movie["id"]?.toString() ?: return@mapNotNull null
            val title = movie["title"] as? String ?: return@mapNotNull null
            val poster = movie["poster_url"] as? String ?: ""
            val year = (movie["year"] as? String)?.toIntOrNull()

            movieCache[id] = movie

            newMovieSearchResponse(title, id, TvType.Movie, false) {
                this.posterUrl = poster
                this.year = year
            }
        }

        return newHomePageResponse(listOf(HomePageList("Latest Movies", list)))
    }
}