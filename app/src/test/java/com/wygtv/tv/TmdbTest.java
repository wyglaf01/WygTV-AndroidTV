package com.wygtv.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TmdbTest {
    private static void clean(String raw, String title, String year) {
        String[] c = Tmdb.cleanTitle(raw);
        assertEquals(raw, title, c[0]);
        assertEquals(raw, year, c[1]);
    }

    @Test public void cleansProviderTitles() {
        clean("EN - The Batman (2022) 4K", "The Batman", "2022");
        clean("|EN| Dune: Part Two [2024] FHD", "Dune: Part Two", "2024");
        clean("[4K] Oppenheimer - 2023", "Oppenheimer", "2023");
        clean("Blade Runner 2049 (2017)", "Blade Runner 2049", "2017");
        clean("1917", "1917", "");
        clean("Breaking Bad", "Breaking Bad", "");
        clean("FR: Amélie (2001) MULTI", "Amélie", "2001");
        clean("The.Matrix.1999.1080p", "The Matrix", "1999");
    }

    @Test public void providerInfoBecomesMeta() throws Exception {
        JSONObject item = new JSONObject().put("name", "EN - Heat (1995)").put("stream_icon", "http://img/heat.jpg").put("rating", "8");
        JSONObject answer = new JSONObject().put("info", new JSONObject()
                .put("plot", "A cop and a thief.").put("cast", "Al Pacino, Robert De Niro").put("director", "Michael Mann")
                .put("genre", "Crime").put("releasedate", "1995-12-15").put("duration", "02:50:00").put("rating", "8.3")
                .put("tmdb_id", "949").put("backdrop_path", new JSONArray().put("https://img/back.jpg")));
        JSONObject m = Tmdb.fromProvider(item, answer);
        assertEquals("Heat", m.getString("title"));
        assertEquals("1995", m.getString("year"));
        assertEquals("170", m.getString("runtime"));
        assertEquals("8.3", m.getString("rating"));
        assertEquals("949", m.getString("tmdb_id"));
        assertEquals("http://img/heat.jpg", m.getString("poster"));
        assertEquals("https://img/back.jpg", m.getString("backdrop"));
        assertEquals("Michael Mann", m.getString("director"));
    }

    @Test public void emptyProviderInfoStillHasTitleAndYear() throws Exception {
        JSONObject m = Tmdb.fromProvider(new JSONObject().put("name", "Up (2009)"), new JSONObject().put("info", new JSONArray()));
        assertEquals("Up", m.getString("title"));
        assertEquals("2009", m.getString("year"));
        assertFalse(m.has("rating"));
        assertFalse(m.has("tmdb_id"));
    }

    @Test public void tmdbMovieBecomesMeta() throws Exception {
        JSONObject t = new JSONObject().put("id", 949).put("title", "Heat").put("release_date", "1995-12-15").put("runtime", 170)
                .put("vote_average", 7.94).put("vote_count", 7000).put("overview", "Obsessive master thief...").put("tagline", "A Los Angeles crime saga")
                .put("poster_path", "/p.jpg").put("backdrop_path", "/b.jpg").put("imdb_id", "tt0113277")
                .put("genres", new JSONArray().put(new JSONObject().put("name", "Crime")).put(new JSONObject().put("name", "Drama")))
                .put("credits", new JSONObject()
                        .put("cast", new JSONArray().put(new JSONObject().put("name", "Al Pacino")).put(new JSONObject().put("name", "Robert De Niro")))
                        .put("crew", new JSONArray().put(new JSONObject().put("name", "Michael Mann").put("job", "Director")).put(new JSONObject().put("name", "X").put("job", "Editor"))))
                .put("release_dates", new JSONObject().put("results", new JSONArray().put(new JSONObject().put("iso_3166_1", "US")
                        .put("release_dates", new JSONArray().put(new JSONObject().put("certification", "R"))))));
        JSONObject m = Tmdb.fromTmdb(t, false);
        assertEquals("7.9", m.getString("rating"));
        assertEquals("TMDb", m.getString("rating_source"));
        assertEquals("Crime, Drama", m.getString("genres"));
        assertEquals("Al Pacino, Robert De Niro", m.getString("cast"));
        assertEquals("Michael Mann", m.getString("director"));
        assertEquals("R", m.getString("cert"));
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", m.getString("poster"));
        assertEquals("tt0113277", m.getString("imdb"));
        assertEquals("949", m.getString("tmdb_id"));
    }

    @Test public void tmdbSeriesUsesCreatorsAndSeasons() throws Exception {
        JSONObject t = new JSONObject().put("id", 1396).put("name", "Breaking Bad").put("first_air_date", "2008-01-20")
                .put("episode_run_time", new JSONArray().put(47)).put("number_of_seasons", 5)
                .put("created_by", new JSONArray().put(new JSONObject().put("name", "Vince Gilligan")))
                .put("content_ratings", new JSONObject().put("results", new JSONArray().put(new JSONObject().put("iso_3166_1", "US").put("rating", "TV-MA"))));
        JSONObject m = Tmdb.fromTmdb(t, true);
        assertEquals("Created by", m.getString("director_label"));
        assertEquals("Vince Gilligan", m.getString("director"));
        assertEquals("5", m.getString("seasons"));
        assertEquals("47", m.getString("runtime"));
        assertEquals("TV-MA", m.getString("cert"));
    }

    @Test public void mergeKeepsProviderTitleAndFillsTheRest() throws Exception {
        JSONObject p = new JSONObject().put("title", "Heat").put("plot", "short").put("cast", "Pacino").put("rating", "8.3").put("rating_source", "provider");
        JSONObject t = new JSONObject().put("title", "Heat (TMDb)").put("plot", "long plot").put("rating", "7.9").put("rating_source", "TMDb").put("poster", "https://x/p.jpg");
        JSONObject m = Tmdb.merge(p, t);
        assertEquals("Heat", m.getString("title"));
        assertEquals("long plot", m.getString("plot"));
        assertEquals("Pacino", m.getString("cast"));
        assertEquals("7.9", m.getString("rating"));
        assertEquals("TMDb", m.getString("source"));
    }

    @Test public void pickMatchesYearOrExactTitle() throws Exception {
        JSONArray results = new JSONArray()
                .put(new JSONObject().put("id", 1).put("title", "Dune").put("release_date", "1984-12-14"))
                .put(new JSONObject().put("id", 2).put("title", "Dune").put("release_date", "2021-09-15"))
                .put(new JSONObject().put("id", 3).put("title", "Dune Drifter").put("release_date", "2020-10-01"));
        assertEquals(2, Tmdb.pick(results, "Dune", "2021", false).getInt("id"));
        assertEquals(1, Tmdb.pick(results, "Dune", "", false).getInt("id"));
        assertNull(Tmdb.pick(results, "Dune", "2000", false));
        assertNull(Tmdb.pick(new JSONArray(), "Dune", "", false));
    }

    @Test public void parsesRuntimesAndRatings() {
        assertEquals(112, Tmdb.minutes("01:52:00"));
        assertEquals(95, Tmdb.minutes("95 min"));
        assertEquals(0, Tmdb.minutes(""));
        assertEquals("7.5", Tmdb.rating("7.5"));
        assertEquals("", Tmdb.rating("0"));
        assertEquals("", Tmdb.rating("N/A"));
    }

    @Test public void findsYoutubeTrailerKeys() throws Exception {
        assertEquals("dQw4w9WgXcQ", Tmdb.youtubeKey("dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", Tmdb.youtubeKey("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3"));
        assertEquals("dQw4w9WgXcQ", Tmdb.youtubeKey("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals("", Tmdb.youtubeKey(""));
        assertEquals("", Tmdb.youtubeKey("not a trailer"));
        JSONObject answer = new JSONObject().put("info", new JSONObject().put("youtube_trailer", "abcdefghijk"));
        assertEquals("abcdefghijk", Tmdb.fromProvider(new JSONObject().put("name", "Heat"), answer).optString("trailer"));
    }

    @Test public void picksOfficialTmdbTrailer() throws Exception {
        JSONArray r = new JSONArray()
                .put(new JSONObject().put("site", "YouTube").put("type", "Teaser").put("key", "teaser00001").put("official", true))
                .put(new JSONObject().put("site", "Vimeo").put("type", "Trailer").put("key", "vimeo"))
                .put(new JSONObject().put("site", "YouTube").put("type", "Trailer").put("key", "fanmade0001").put("official", false))
                .put(new JSONObject().put("site", "YouTube").put("type", "Trailer").put("key", "official001").put("official", true));
        assertEquals("official001", Tmdb.trailerFrom(new JSONObject().put("results", r)));
        assertEquals("", Tmdb.trailerFrom(null));
    }
}
