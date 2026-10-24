package com.dizipal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DizipalProvider : MainAPI() {
    // Başlangıç adresimiz. Kapansa bile kod yeni adresi tarayarak bulacaktır.
    override var mainUrl = "https://dizipal2143.com"
    override var name = "Dizipal"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var isDomainResolved = false

    /**
     * EN ÖNEMLİ KISIM: Redirect ve Güncel Domain Bulucu
     * Eğer mevcut adres çalışmıyorsa (ör: dizipal2143 kapandıysa),
     * numarayı artırarak (2144, 2145...) güncel ve çalışan adresi bulur
     * ve HTTP 301/302 yönlendirmelerini (redirect) yakalar.
     */
    private suspend fun resolveDomain() {
        if (isDomainResolved) return

        try {
            // 1. Aşama: Mevcut URL'ye istek at, otomatik yönlendirme (redirect) varsa yakala
            val initialResponse = app.get(mainUrl, allowRedirects = true, timeout = 5)
            if (initialResponse.isSuccessful) {
                // Yönlendirme yapıldıysa yeni adresi kaydet (Sondaki / işaretini temizle)
                mainUrl = Regex("""(https?://[^/]+)""").find(initialResponse.url)?.value ?: mainUrl
                isDomainResolved = true
                return
            }
        } catch (e: Exception) {
            // Site tamamen kapalıysa (Timeout/DNS Hatası), 2. Aşamaya geç
        }

        // 2. Aşama: Adres ölmüşse domain numarasını artırarak tarama yap
        val currentNumberStr = Regex("""\d+""").find(mainUrl)?.value
        if (currentNumberStr != null) {
            val currentNumber = currentNumberStr.toInt()
            
            // Mevcut numaradan itibaren 10 adres ileriye kadar kontrol et (Ör: 2143 -> 2153)
            for (i in 1..10) {
                val testNumber = currentNumber + i
                val testUrl = mainUrl.replace(currentNumberStr, testNumber.toString())
                
                try {
                    val response = app.get(testUrl, allowRedirects = true, timeout = 3)
                    if (response.isSuccessful) {
                        mainUrl = Regex("""(https?://[^/]+)""").find(response.url)?.value ?: testUrl
                        isDomainResolved = true
                        println("Yeni Dizipal Adresi Bulundu: $mainUrl")
                        return
                    }
                } catch (e: Exception) {
                    // Bu adres de kapalı, bir sonrakine geç
                    continue
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "/diziler" to "Son Eklenen Diziler",
        "/filmler" to "Son Eklenen Filmler"
    )

    override async fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain() // Sayfa yüklenmeden önce adresin güncel olduğundan emin ol
        
        val url = "$mainUrl${request.data}/page/$page/"
        val document = app.get(url).document
        
        // Farklı tema ihtimallerine karşı geniş CSS seçicileri (Stabilite için)
        val items = document.select("article, .film-box, .movie-item, .post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Başlık, Link ve Afiş için en yaygın WordPress/Film teması etiketleri
        val title = this.selectFirst(".title, h2, h3, .entry-title")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override async fun search(query: String): List<SearchResponse> {
        resolveDomain()
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        
        return document.select("article, .film-box, .movie-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override async fun load(url: String): LoadResponse {
        resolveDomain()
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Bilinmeyen İçerik"
        val poster = fixUrlNull(document.selectFirst(".poster img, .single-media img, .cover img")?.attr("src"))
        val description = document.selectFirst(".description, .entry-content p, .plot")?.text()?.trim()

        val isTvSeries = url.contains("/dizi/")

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            // Sezon ve bölümleri kapsayan geniş seçici
            document.select(".episodes-list a, .bolumler a, ul.episodes li a").forEach { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@forEach
                val epName = ep.text().trim()
                
                episodes.add(Episode(data = epHref, name = epName))
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override async fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // 1. YÖNTEM: Sayfadaki tüm iframeleri (video oynatıcıları) bul
        val iframes = document.select("iframe").mapNotNull { it.attr("src") }
        
        // 2. YÖNTEM: Alternatif kaynak butonlarındaki (server) data-embed vb. linkleri bul
        val embedLinks = document.select("[data-embed], [data-src], .server-list a").mapNotNull { 
            it.attr("data-embed").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("href") }
        }.filter { it.contains("http") }

        val allVideoLinks = (iframes + embedLinks).distinct()

        if (allVideoLinks.isEmpty()) return false

        // Bulunan tüm video linklerini Cloudstream'in dahili çözücülerine (Vidmoly, Streamtape vb.) gönder
        allVideoLinks.forEach { link ->
            val fixedLink = fixUrlNull(link) ?: return@forEach
            // Extractor'ı güvenli (try-catch) bir blokta çağırıyoruz ki biri hata verirse diğeri çalışsın
            try {
                loadExtractor(fixedLink, subtitleCallback, callback)
            } catch (e: Exception) {
                println("Extractor Hatası: ${e.message}")
            }
        }

        return true
    }
}
