package com.wygtv.tv;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.text.SubtitleParser;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Closed captions for Live TV and antenna channels.
 *
 * TV captions ride inside the video itself (CEA-608 "line 21" captions and the newer CEA-708 digital captions,
 * the same data a TV set shows with its CC button). Most streams don't announce which caption services they carry,
 * and on its own the player then only looks for the first CEA-608 service (CC1). WygTV looks for CC1, CC3 (often a
 * second language) and CEA-708 service 1 on every channel instead, in plain MPEG-TS streams, in HLS (.m3u8) streams and
 * in HDHomeRun antenna channels alike. When a stream does announce its captions, that list is used as it is.
 *
 * Other subtitle kinds need nothing extra here: HLS subtitle tracks (WebVTT) listed in the playlist and DVB subtitles
 * in MPEG-TS are found by the player itself and show up in the Subtitles list too.
 */
@OptIn(markerClass = UnstableApi.class)
final class Captions {
    private Captions() { }

    /** The caption services looked for in every stream that doesn't list its own. */
    static final List<Format> FORMATS = Collections.unmodifiableList(Arrays.asList(
            new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).setLabel("CC1").setId("wygtv-cc1").build(),
            new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(3).setLabel("CC3").setId("wygtv-cc3").build(),
            new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA708).setAccessibilityChannel(1).setLabel("Digital CC").setId("wygtv-708").build()));

    /** True for the closed-caption kinds that come inside the video. */
    static boolean isClosedCaption(@Nullable String mime) {
        if (mime == null) return false;
        return MimeTypes.APPLICATION_CEA608.equals(mime) || MimeTypes.APPLICATION_CEA708.equals(mime) || MimeTypes.APPLICATION_MP4CEA608.equals(mime)
                || mime.contains("cea-608") || mime.contains("cea-708");
    }

    /** True when the track is closed captions, also after the player has turned it into its own cue format. */
    static boolean isClosedCaption(Format f) {
        return f != null && (isClosedCaption(f.sampleMimeType) || isClosedCaption(f.codecs));
    }

    /**
     * HLS segments read with the caption list above when the playlist says nothing about captions. When the playlist
     * does list them (or says CLOSED-CAPTIONS=NONE), that answer is kept.
     */
    @OptIn(markerClass = UnstableApi.class)
    static final class HlsExtractors implements HlsExtractorFactory {
        private final DefaultHlsExtractorFactory inner = new DefaultHlsExtractorFactory(0, true);

        @Override
        public HlsMediaChunkExtractor createExtractor(Uri uri, Format format, @Nullable List<Format> muxedCaptionFormats,
                TimestampAdjuster timestampAdjuster, Map<String, List<String>> responseHeaders,
                ExtractorInput sniffingExtractorInput, PlayerId playerId) throws IOException {
            return inner.createExtractor(uri, format, muxedCaptionFormats == null ? FORMATS : muxedCaptionFormats,
                    timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId);
        }

        @Override public HlsExtractorFactory setSubtitleParserFactory(SubtitleParser.Factory f) { inner.setSubtitleParserFactory(f); return this; }
        @Override public HlsExtractorFactory experimentalParseSubtitlesDuringExtraction(boolean b) { inner.experimentalParseSubtitlesDuringExtraction(b); return this; }
        @Override public HlsExtractorFactory experimentalSetCodecsToParseWithinGopSampleDependencies(int codecs) { inner.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs); return this; }
        @Override public Format getOutputTextFormat(Format sourceFormat) { return inner.getOutputTextFormat(sourceFormat); }
    }

    /**
     * Every stream goes through the usual factory, except HLS (.m3u8): that is opened with {@link HlsExtractors} and
     * without "chunkless preparation". Chunkless preparation sets up the tracks from the playlist alone, before any
     * video is read, so captions that the playlist doesn't mention never appear. Reading the first piece of video
     * first lets the player find them. (An HLS movie with an added subtitle file still uses the usual factory.)
     */
    @OptIn(markerClass = UnstableApi.class)
    static final class SourceFactory implements MediaSource.Factory {
        private final MediaSource.Factory main;
        private final HlsMediaSource.Factory hls;

        SourceFactory(MediaSource.Factory main, DataSource.Factory data) {
            this.main = main;
            this.hls = new HlsMediaSource.Factory(data).setExtractorFactory(new HlsExtractors()).setAllowChunklessPreparation(false);
        }

        @Override public MediaSource.Factory setDrmSessionManagerProvider(DrmSessionManagerProvider p) {
            main.setDrmSessionManagerProvider(p);
            hls.setDrmSessionManagerProvider(p);
            return this;
        }

        @Override public MediaSource.Factory setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy p) {
            main.setLoadErrorHandlingPolicy(p);
            hls.setLoadErrorHandlingPolicy(p);
            return this;
        }

        @Override public int[] getSupportedTypes() { return main.getSupportedTypes(); }

        @Override public MediaSource createMediaSource(MediaItem item) {
            MediaItem.LocalConfiguration c = item.localConfiguration;
            if (c != null && c.subtitleConfigurations.isEmpty()
                    && Util.inferContentTypeForUriAndMimeType(c.uri, c.mimeType) == C.CONTENT_TYPE_HLS)
                return hls.createMediaSource(item);
            return main.createMediaSource(item);
        }
    }
}
