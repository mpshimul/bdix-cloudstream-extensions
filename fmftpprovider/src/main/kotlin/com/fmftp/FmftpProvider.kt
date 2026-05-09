package com.fmftp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

@Suppress("UNCHECKED_CAST")
class FmftpProvider : MainAPI() {
    override var name = "Fmftp BDIX"
    override var lang = "bn"
    override var mainUrl = "https://fmftp.net"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val apiUrl = "$mainUrl/api/movies?limit=2000&sort=release_date"
    private val mapper = jacksonObjectMapper()

    companion object {
        val movieStore = mutableMapOf<String, MovieData>()
        var cachedMovies: List<SearchResponse>? = null
    }

    data class MovieData(
        val title: String,
        val streamUrl: String,
        val poster: String,
        val backdrop: String,
        val plot: String,
        val year: Int?,
        val rating: Double?,
        val genres: List<String>,
        val cast: List<String>,
        val library: String
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept" to "application/json"
    )

    private suspend fun fetchAllMovies(): List<SearchResponse> {
        cachedMovies?.let { return it }
        return try {
            val response = app.get(apiUrl, headers = headers).text
            val json = mapper.readValue<Map<String, Any>>(response)
            val movies = json["data"] as? List<Map<String, Any>> ?: return emptyList()

            val results = movies.mapNotNull { movie ->
                val id = movie["id"]?.toString() ?: return@mapNotNull null
                val title = movie["title"] as? String ?: return@mapNotNull null
                val year = (movie["year"] as? String)?.toIntOrNull()
                val plot = movie["overview"] as? String ?: ""
                val rating = (movie["online_rating"] as? Number)?.toDouble()
                val genreStr = movie["genre"] as? String ?: ""
                val genres = genreStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val castsStr = movie["casts"] as? String ?: ""
                val castList = castsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val relativeUrl = movie["url"] as? String ?: ""
                val streamUrl = "$mainUrl$relativeUrl"
                val libraryObj = movie["Library"] as? Map<String, Any>
                val libraryName = libraryObj?.get("name") as? String ?: "Unknown"

                // Handle poster and backdrop: could be absolute or relative
                var poster = movie["poster_url"] as? String ?: ""
                if (poster.isNotEmpty() && !poster.startsWith("http")) {
                    poster = if (poster.startsWith("/")) "$mainUrl$poster" else "$mainUrl/$poster"
                }
                var backdrop = movie["backdrop_url"] as? String ?: ""
                if (backdrop.isNotEmpty() && !backdrop.startsWith("http")) {
                    backdrop = if (backdrop.startsWith("/")) "$mainUrl$backdrop" else "$mainUrl/$backdrop"
                }

                val detailUrl = "http://fmftp.local/$id"
                movieStore[detailUrl] = MovieData(
                    title, streamUrl, poster, backdrop, plot, year, rating, genres, castList, libraryName
                )

                newMovieSearchResponse(title, detailUrl, TvType.Movie, false) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
            cachedMovies = results
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allMovies = fetchAllMovies()
        if (allMovies.isEmpty()) return newHomePageResponse(emptyList())

        // Group movies by library name
        val grouped = allMovies.groupBy { movie ->
            movieStore[movie.url]?.library ?: "Unknown"
        }

        val lists = mutableListOf<HomePageList>()

        // 1. Latest Movies (first 30 items of all)
        val latest = allMovies.take(30)
        if (latest.isNotEmpty()) lists.add(HomePageList("Latest Movies", latest))

        // 2. Hollywood
        grouped["Hollywood"]?.let { lists.add(HomePageList("Hollywood Movies", it)) }
        // 3. Bollywood
        grouped["Bollywood"]?.let { lists.add(HomePageList("Bollywood Movies", it)) }
        // 4. Hindi dubbed
        grouped["Hindi dubbed"]?.let { lists.add(HomePageList("Hindi Dubbed Movies", it)) }
        // 5. Indian Bangla
        grouped["Indian Bangla"]?.let { lists.add(HomePageList("Bangla Movies", it)) }
        // 6. Animation
        grouped["Animation"]?.let { lists.add(HomePageList("Animation Movies", it)) }

        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val all = fetchAllMovies()
        return all.filter { it.name?.contains(query, ignoreCase = true) == true }
    }

    override suspend fun load(url: String): LoadResponse {
        val movie = movieStore[url] ?: throw Error("Movie not found")
        return newMovieLoadResponse(movie.title, url, TvType.Movie, movie.streamUrl) {
            this.plot = movie.plot
            this.year = movie.year
            this.posterUrl = movie.poster
            this.backgroundPosterUrl = movie.backdrop
            val tagsList = mutableListOf<String>()
            tagsList.addAll(movie.genres)
            if (movie.cast.isNotEmpty()) {
                tagsList.add("Cast: ${movie.cast.joinToString(", ")}")
            }
            if (tagsList.isNotEmpty()) this.tags = tagsList
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
}