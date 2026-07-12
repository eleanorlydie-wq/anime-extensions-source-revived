package eu.kanade.tachiyomi.animeextension.tr.animeler

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimelerFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, val pairs: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.getFirst(): R = first { it is R } as R

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String = (getFirst<R>() as QueryPartFilter).toQueryPart()

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> = (getFirst<R>() as CheckBoxFilterList).state
        .mapNotNull { checkbox ->
            if (checkbox.state) options.find { it.first == checkbox.name }?.second else null
        }

    class SortFilter : QueryPartFilter("Sırala", AnimelerFiltersData.SORT)
    class CategoryFilter : CheckBoxFilterList("Kategori", AnimelerFiltersData.CATEGORY)
    class TypeFilter : CheckBoxFilterList("Tip", AnimelerFiltersData.TYPE)
    class GenreFilter : CheckBoxFilterList("Tür", AnimelerFiltersData.GENRE)
    class YearFilter : CheckBoxFilterList("Yıl", AnimelerFiltersData.YEAR)
    class SeasonFilter : CheckBoxFilterList("Sezon", AnimelerFiltersData.SEASON)
    class StatusFilter : CheckBoxFilterList("Durum", AnimelerFiltersData.STATUS)

    val FILTER_LIST get() = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        CategoryFilter(),
        TypeFilter(),
        GenreFilter(),
        YearFilter(),
        SeasonFilter(),
        StatusFilter(),
    )

    data class FilterSearchParams(
        val sort: String = "popular",
        val category: List<String> = emptyList(),
        val type: List<String> = emptyList(),
        val genre: List<String> = emptyList(),
        val year: List<String> = emptyList(),
        val season: List<String> = emptyList(),
        val status: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<SortFilter>(),
            filters.parseCheckbox<CategoryFilter>(AnimelerFiltersData.CATEGORY),
            filters.parseCheckbox<TypeFilter>(AnimelerFiltersData.TYPE),
            filters.parseCheckbox<GenreFilter>(AnimelerFiltersData.GENRE),
            filters.parseCheckbox<YearFilter>(AnimelerFiltersData.YEAR),
            filters.parseCheckbox<SeasonFilter>(AnimelerFiltersData.SEASON),
            filters.parseCheckbox<StatusFilter>(AnimelerFiltersData.STATUS),
        )
    }

    private object AnimelerFiltersData {
        val SORT = arrayOf(
            Pair("Popüler", "popular"),
            Pair("En Yeni", "latest"),
            Pair("En Eski", "oldest"),
            Pair("Son Güncellenen", "updated"),
            Pair("Puan", "score"),
            Pair("Değerlendirme", "rating"),
            Pair("İsim (A-Z)", "title"),
        )

        val CATEGORY = arrayOf(
            Pair("Anime", "1"),
            Pair("Donghua", "2"),
            Pair("Comic", "3"),
        )

        val TYPE = arrayOf(
            Pair("TV", "tv"),
            Pair("Film", "movie"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special"),
            Pair("Music", "music"),
        )

        val SEASON = arrayOf(
            Pair("Kış", "winter"),
            Pair("İlkbahar", "spring"),
            Pair("Yaz", "summer"),
            Pair("Sonbahar", "fall"),
        )

        val STATUS = arrayOf(
            Pair("Yayında", "airing"),
            Pair("Tamamlandı", "finished"),
            Pair("Yakında", "upcoming"),
        )

        val YEAR = (2027 downTo 1990).map { Pair(it.toString(), it.toString()) }.toTypedArray()

        val GENRE = arrayOf(
            Pair("Aksiyon", "Action"),
            Pair("Yetişkin Karakterler", "Adult Cast"),
            Pair("Macera", "Adventure"),
            Pair("Anthropomorphic", "Anthropomorphic"),
            Pair("Deneysel", "Avant Garde"),
            Pair("Ödüllü", "Award Winning"),
            Pair("Boy Love", "Boys Love"),
            Pair("CGDCT", "CGDCT"),
            Pair("Childcare", "Childcare"),
            Pair("Dövüş Sporları", "Combat Sports"),
            Pair("Komedi", "Comedy"),
            Pair("Crossdressing", "Crossdressing"),
            Pair("Delinquents", "Delinquents"),
            Pair("Detective", "Detective"),
            Pair("Dram", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Educational", "Educational"),
            Pair("Erotica", "Erotica"),
            Pair("Fantezi", "Fantasy"),
            Pair("Gag Humor", "Gag Humor"),
            Pair("Girl Love", "Girls Love"),
            Pair("Gore", "Gore"),
            Pair("Gurme", "Gourmet"),
            Pair("Harem", "Harem"),
            Pair("Hentai", "Hentai"),
            Pair("High Stakes Game", "High Stakes Game"),
            Pair("Tarihi", "Historical"),
            Pair("Korku", "Horror"),
            Pair("Idols (Female)", "Idols (Female)"),
            Pair("Idols (Male)", "Idols (Male)"),
            Pair("İsekai", "Isekai"),
            Pair("Iyashikei", "Iyashikei"),
            Pair("Love Polygon", "Love Polygon"),
            Pair("Love Status Quo", "Love Status Quo"),
            Pair("Magical Sex Shift", "Magical Sex Shift"),
            Pair("Mahou Shoujo", "Mahou Shoujo"),
            Pair("Dövüş Sanatları", "Martial Arts"),
            Pair("Meka", "Mecha"),
            Pair("Medikal", "Medical"),
            Pair("Askeri", "Military"),
            Pair("Müzik", "Music"),
            Pair("Gizem", "Mystery"),
            Pair("Mitoloji", "Mythology"),
            Pair("Organize Suç", "Organized Crime"),
            Pair("Otaku Kültürü", "Otaku Culture"),
            Pair("Parodi", "Parody"),
            Pair("Performing Arts", "Performing Arts"),
            Pair("Pets", "Pets"),
            Pair("Psikolojik", "Psychological"),
            Pair("Yarış", "Racing"),
            Pair("Reenkarnasyon", "Reincarnation"),
            Pair("Reverse Harem", "Reverse Harem"),
            Pair("Romantik", "Romance"),
            Pair("Samuray", "Samurai"),
            Pair("Okul", "School"),
            Pair("Bilim Kurgu", "Sci-Fi"),
            Pair("Showbiz", "Showbiz"),
            Pair("Yaşamdan Kareler", "Slice of Life"),
            Pair("Uzay", "Space"),
            Pair("Spor", "Sports"),
            Pair("Strateji Oyunu", "Strategy Game"),
            Pair("Süper Güç", "Super Power"),
            Pair("Doğaüstü", "Supernatural"),
            Pair("Hayatta Kalma", "Survival"),
            Pair("Gerilim", "Suspense"),
            Pair("Takım Sporları", "Team Sports"),
            Pair("Gerilim (Thriller)", "Thriller"),
            Pair("Zaman Yolculuğu", "Time Travel"),
            Pair("Şehir Fantezisi", "Urban Fantasy"),
            Pair("Vampir", "Vampire"),
            Pair("Video Game", "Video Game"),
            Pair("Villainess", "Villainess"),
            Pair("Visual Arts", "Visual Arts"),
            Pair("İş Dünyası", "Workplace"),
        )
    }
}
