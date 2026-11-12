/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.returnyoutubedislike.ReturnYouTubeDislikeInfo;
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockSegment;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stream: SponsorBlock segments and Return YouTube Dislike data of a video.
 * <p>
 * Tubular's extractor fork kept these inside {@link StreamInfo}; Stream uses the upstream
 * NewPipeExtractor, so they live here, keyed by the video url (a small LRU map, because
 * StreamInfo objects get serialized and recreated).
 */
public final class StreamExtras {
    private static final int MAX_ENTRIES = 60;
    private static final Map<String, StreamExtras> STORE =
            new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, StreamExtras> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private final List<SponsorBlockSegment> sponsorBlockSegments = new ArrayList<>();
    @Nullable
    private ReturnYouTubeDislikeInfo rydInfo;

    private StreamExtras() {
    }

    /**
     * @param info the video
     * @return the extras of that video, created empty if there are none yet
     */
    @NonNull
    public static StreamExtras of(@NonNull final StreamInfo info) {
        synchronized (STORE) {
            StreamExtras extras = STORE.get(info.getUrl());
            if (extras == null) {
                extras = new StreamExtras();
                STORE.put(info.getUrl(), extras);
            }
            return extras;
        }
    }

    public synchronized SponsorBlockSegment[] getSponsorBlockSegments() {
        return sponsorBlockSegments.toArray(new SponsorBlockSegment[0]);
    }

    /**
     * Replaces the segments and links segments that follow each other within a second into
     * chains, so that they can be skipped in one go.
     *
     * @param segments the segments in chronological order
     */
    public synchronized void setSponsorBlockSegments(
            @Nullable final SponsorBlockSegment[] segments) {
        sponsorBlockSegments.clear();
        if (segments == null) {
            return;
        }
        for (final SponsorBlockSegment segment : segments) {
            segment.chain.clear();
        }

        int i = 0;
        while (i < segments.length) {
            final List<SponsorBlockSegment> chain = new ArrayList<>();
            chain.add(segments[i]);

            for (int j = i + 1; j < segments.length; j++) {
                if (segments[j].startTime < chain.get(chain.size() - 1).endTime + 1000) {
                    chain.add(segments[j]);
                } else {
                    for (final SponsorBlockSegment segment : chain) {
                        segment.chain.addAll(chain);
                    }
                    break;
                }
            }
            i += chain.size();
        }

        Collections.addAll(sponsorBlockSegments, segments);
    }

    public synchronized void addSponsorBlockSegment(final SponsorBlockSegment segment) {
        sponsorBlockSegments.add(segment);
    }

    public synchronized void removeSponsorBlockSegment(final String uuid) {
        SponsorBlockSegment target = null;
        for (final SponsorBlockSegment segment : sponsorBlockSegments) {
            if (segment.uuid.equals(uuid)) {
                target = segment;
                break;
            }
        }
        if (target != null) {
            sponsorBlockSegments.remove(target);
        }
    }

    @Nullable
    public synchronized ReturnYouTubeDislikeInfo getRydInfo() {
        return rydInfo;
    }

    public synchronized void setRydInfo(@Nullable final ReturnYouTubeDislikeInfo info) {
        this.rydInfo = info;
    }
}
