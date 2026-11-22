package com.wygtv.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class OmdbTest {
    private static JSONObject heat() throws Exception {
        return new JSONObject().put("Response", "True").put("Title", "Heat").put("Year", "1995").put("Rated", "R").put("Runtime", "170 min")
                .put("Genre", "Action, Crime, Drama").put("Director", "Michael Mann").put("Actors", "Al Pacino, Robert De Niro")
                .put("Plot", "A group of high-end professional thieves...").put("Poster", "https://m.media-amazon.com/heat.jpg")
                .put("imdbRating", "8.3").put("imdbID", "tt0113277").put("Metascore", "76").put("totalSeasons", "N/A")
                .put("Ratings", new JSONArray().put(new JSONObject().put("Source", "Internet Movie Database").put("Value", "8.3/10"))
                        .put(new JSONObject().put("Source", "Rotten Tomatoes").put("Value", "88%")));
    }

    @Test public void readsRatingsAndDetails() throws Exception {
        JSONObject m = Omdb.fromOmdb(heat());
        assertEquals("8.3", m.getString("imdb_rating"));
        assertEquals("88%", m.getString("rt"));
        assertEquals("76", m.getString("metascore"));
        assertEquals("170", m.getString("runtime"));
        assertEquals("R", m.getString("cert"));
        assertEquals("tt0113277", m.getString("imdb"));
        assertFalse(m.has("seasons"));
    }

    @Test public void noMatchIsNull() throws Exception {
        assertNull(Omdb.fromOmdb(new JSONObject().put("Response", "False").put("Error", "Movie not found!")));
        assertNull(Omdb.fromOmdb(null));
    }

    @Test public void mergeAddsRatingsAndOnlyFillsGaps() throws Exception {
        JSONObject meta = new JSONObject().put("title", "Heat").put("plot", "TMDb plot").put("rating", "7.9").put("rating_source", "TMDb");
        JSONObject m = Omdb.merge(meta, Omdb.fromOmdb(heat()));
        assertEquals("TMDb plot", m.getString("plot"));
        assertEquals("Michael Mann", m.getString("director"));
        assertEquals("Director", m.getString("director_label"));
        assertEquals("8.3", m.getString("imdb_rating"));
        assertEquals("7.9", m.getString("rating"));
        assertTrue(m.has("omdb"));
    }
}
