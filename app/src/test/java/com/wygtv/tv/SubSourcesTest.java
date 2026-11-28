package com.wygtv.tv;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class SubSourcesTest {
    @Test public void movieQueryFromProviderTitle() {
        SubSources.Query q = SubSources.queryFor("vod", "EN - Inception (2010) 4K", null, null, null);
        assertEquals("Inception", q.title);
        assertEquals("2010", q.year);
        assertFalse(q.series);
    }

    @Test public void episodeQueryUsesSeriesNameAndNumbers() {
        SubSources.Query q = SubSources.queryFor("series", "Breaking Bad S02E05 - Breakage", "Breaking Bad", "2", "5");
        assertTrue(q.series);
        assertEquals("Breaking Bad", q.title);
        assertEquals("2", q.season);
        assertEquals("5", q.episode);
    }

    @Test public void episodeNumbersFoundInTitle() {
        SubSources.Query q = SubSources.queryFor("series", "The Office S03E10", null, "", "");
        assertEquals("The Office", q.title);
        assertEquals("3", q.season);
        assertEquals("10", q.episode);
    }

    @Test public void openSubtitlesAnswer() throws Exception {
        JSONObject o = new JSONObject("{\"data\":[{\"attributes\":{\"language\":\"en\",\"download_count\":1234,\"hearing_impaired\":true,"
                + "\"release\":\"Inception.2010.1080p\",\"files\":[{\"file_id\":42,\"file_name\":\"x.srt\"}]}}]}");
        List<SubSources.Result> r = SubSources.parseOpenSubtitles(o);
        assertEquals(1, r.size());
        assertEquals("42", r.get(0).id);
        assertTrue(r.get(0).hearingImpaired);
        assertTrue(r.get(0).label().contains("1k downloads"));
    }

    @Test public void openSubtitlesParametersAreSorted() throws Exception {
        SubSources.Query q = SubSources.queryFor("vod", "Inception (2010)", null, null, null);
        String u = SubSources.openSubtitlesUrl(q, "en");
        assertTrue(u, u.endsWith("/subtitles?languages=en&query=inception&type=movie&year=2010"));
    }

    @Test public void subdlAnswer() throws Exception {
        JSONObject o = new JSONObject("{\"status\":true,\"subtitles\":[{\"release_name\":\"Show.S01E02\",\"language\":\"EN\",\"url\":\"/subtitle/1-2.zip\",\"hi\":false}]}");
        List<SubSources.Result> r = SubSources.parseSubdl(o);
        assertEquals("https://dl.subdl.com/subtitle/1-2.zip", r.get(0).url);
        assertEquals("en", r.get(0).lang);
    }

    @Test public void freeLibraryAnswer() throws Exception {
        JSONObject o = new JSONObject("{\"subtitles\":[{\"id\":\"1\",\"lang\":\"eng\",\"url\":\"https://subs5.strem.io/en/download/file/1\","
                + "\"subtitleFileName\":\"Show.S01E02.srt\",\"movieReleaseName\":\"Show.S01E02.720p\"},"
                + "{\"id\":\"2\",\"lang\":\"pob\",\"url\":\"https://subs5.strem.io/en/download/file/2\"},{\"id\":\"3\",\"lang\":\"fre\",\"url\":\"\"}]}");
        List<SubSources.Result> r = SubSources.parseFree(o);
        assertEquals(2, r.size());
        assertEquals("en", r.get(0).lang);
        assertEquals("Show.S01E02.srt", r.get(0).fileName);
        assertEquals("pt", r.get(1).lang);
        assertEquals(1, SubSources.onlyLanguage(r, "en").size());
        assertEquals(2, SubSources.onlyLanguage(r, "ja").size());
    }

    @Test public void freeLibraryAddresses() throws Exception {
        SubSources.Query q = SubSources.queryFor("series", "Breaking Bad S01E02", null, "", "");
        assertEquals("https://v3-cinemeta.strem.io/catalog/series/top/search=Breaking%20Bad.json", SubSources.cinemetaUrl(q));
        assertEquals("https://opensubtitles-v3.strem.io/subtitles/series/tt0903747:1:2.json", SubSources.freeUrl("tt0903747", q));
        SubSources.Query m = new SubSources.Query(); m.title = "Heat"; m.year = "1995";
        assertEquals("https://opensubtitles-v3.strem.io/subtitles/movie/tt0113277.json", SubSources.freeUrl("tt0113277", m));
    }

    @Test public void cinemetaPicksSameNameAndYear() throws Exception {
        JSONObject o = new JSONObject("{\"metas\":[{\"id\":\"tt1\",\"name\":\"Heat Wave\",\"releaseInfo\":\"2020\"},"
                + "{\"id\":\"tt2\",\"name\":\"Heat\",\"releaseInfo\":\"1986\"},{\"id\":\"tt3\",\"name\":\"Heat\",\"releaseInfo\":\"1995\"}]}");
        SubSources.Query q = new SubSources.Query(); q.title = "Heat"; q.year = "1995";
        assertEquals("tt3", SubSources.pickImdb(o, q));
        q.year = "";
        assertEquals("tt2", SubSources.pickImdb(o, q));
        q.title = "Nothing like it";
        assertEquals("tt1", SubSources.pickImdb(o, q));
    }

    @Test public void threeLetterLanguages() {
        assertEquals("de", SubSources.twoLetter("ger"));
        assertEquals("cs", SubSources.twoLetter("cze"));
        assertEquals("zh", SubSources.twoLetter("zht"));
        assertEquals("en", SubSources.twoLetter("en"));
        assertEquals("xyz", SubSources.twoLetter("xyz"));
    }

    @Test public void wantedLanguageSortsFirst() {
        List<SubSources.Result> list = new ArrayList<>();
        SubSources.Result fr = new SubSources.Result(); fr.lang = "fr"; fr.downloads = 900;
        SubSources.Result en = new SubSources.Result(); en.lang = "en"; en.downloads = 5;
        list.add(fr); list.add(en);
        SubSources.sort(list, "en");
        assertSame(en, list.get(0));
    }

    @Test public void zipPicksTheEpisode() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry("Show.S01E01.srt")); z.write("1\n00:00:01,000 --> 00:00:02,000\nOne\n".getBytes()); z.closeEntry();
            z.putNextEntry(new ZipEntry("Show.S01E02.srt")); z.write("1\n00:00:01,000 --> 00:00:02,000\nTwo\n".getBytes()); z.closeEntry();
            z.putNextEntry(new ZipEntry("readme.txt")); z.write("hello".getBytes()); z.closeEntry();
        }
        assertTrue(SubSources.isZip(b.toByteArray()));
        SubSources.Query q = SubSources.queryFor("series", "Show S01E02", null, null, null);
        String[] name = new String[1];
        byte[] d = SubSources.unzip(b.toByteArray(), q, name);
        assertEquals("Show.S01E02.srt", name[0]);
        assertTrue(new String(d, StandardCharsets.UTF_8).contains("Two"));
    }

    @Test public void kindAndEncoding() {
        assertEquals("srt", SubSources.extensionOf("https://x/y/Movie.EN.SRT?token=1"));
        assertEquals("vtt", SubSources.sniff("WEBVTT\n\n00:01.000 --> 00:02.000\nHi".getBytes(StandardCharsets.UTF_8)));
        assertEquals("srt", SubSources.sniff("1\r\n00:00:01,000 --> 00:00:02,000\r\nHi".getBytes(StandardCharsets.UTF_8)));
        byte[] latin = "Ça va très bien".getBytes(Charset.forName("windows-1252"));
        assertEquals("Ça va très bien", new String(SubSources.toUtf8(latin, "fr"), StandardCharsets.UTF_8));
        assertEquals("Hé", new String(SubSources.toUtf8(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'H', (byte) 0xC3, (byte) 0xA9}, "fr"), StandardCharsets.UTF_8));
        assertTrue(SubSources.matchesEpisode("show.1x02.srt", "1", "2"));
        assertFalse(SubSources.matchesEpisode("Show.S01E12.srt", "1", "2"));
    }
}
