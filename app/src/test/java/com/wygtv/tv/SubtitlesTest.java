package com.wygtv.tv;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitlesTest {
    @Test public void closedCaptionsWithoutLanguage() {
        assertEquals("Closed captions", Subtitles.label(null, null, MimeTypes.APPLICATION_CEA608, 0, 0, 1));
    }
    @Test public void languageNamesAndFlags() {
        assertEquals("English (CC)", Subtitles.label("en", null, MimeTypes.APPLICATION_CEA608, 0, 0, 1));
        assertTrue(Subtitles.label("es", null, MimeTypes.TEXT_VTT, 0, C.SELECTION_FLAG_FORCED, 2).endsWith("(forced)"));
        assertEquals("Track 3", Subtitles.label(null, null, MimeTypes.APPLICATION_SUBRIP, 0, 0, 3));
        assertEquals("Director commentary", Subtitles.label("und", "Director commentary", MimeTypes.TEXT_VTT, 0, 0, 4));
    }
    @Test public void captionServicesAndPictureSubtitles() {
        assertEquals("Closed captions • CC3", Subtitles.label(null, "CC3", MimeTypes.APPLICATION_CEA608, 0, 0, 2));
        assertEquals("Closed captions • Digital CC", Subtitles.label(null, "Digital CC", MimeTypes.APPLICATION_CEA708, 0, 0, 3));
        assertEquals("German (DVB)", Subtitles.label("de", null, MimeTypes.APPLICATION_DVBSUBS, 0, 0, 1));
    }
    @Test public void subtitlesReadDuringUnpackingKeepTheirKind() {
        androidx.media3.common.Format f = new androidx.media3.common.Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.APPLICATION_PGS).setLanguage("fr").build();
        assertEquals(MimeTypes.APPLICATION_PGS, Subtitles.mimeOf(f));
        assertEquals("French (picture)", Subtitles.label(f, 1));
        assertTrue(Captions.isClosedCaption(new androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA708).build()));
        assertEquals(3, Captions.FORMATS.size());
    }
}
