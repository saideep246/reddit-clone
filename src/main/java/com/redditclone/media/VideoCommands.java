package com.redditclone.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Builds the ffmpeg / ffprobe command lines. The encoding itself (H.264, preset veryfast, crf 26, AAC 128k, output
// width capped at 1280) is exactly what it was before; what is new is resource limiting: ffmpeg is told how many threads
// to use instead of one per visible CPU core (a container's CPU quota does not shrink the core count it sees), runs at
// lowered priority so web requests win when they compete for CPU, and prints errors only.
final class VideoCommands {

    static final int MAX_OUTPUT_WIDTH = 1280;

    private VideoCommands() {
    }

    // -threads before -i limits the decoder; -threads after -c:v limits the encoder; -filter_threads limits the scaler.
    static List<String> transcode(Path input, Path output, int encoderThreads, int niceLevel) {
        List<String> c = base(niceLevel);
        c.addAll(List.of("-y", "-threads", "1", "-filter_threads", "1", "-i", input.toString(),
                "-vf", "scale='min(" + MAX_OUTPUT_WIDTH + ",iw)':'-2'",
                "-c:v", "libx264", "-threads", String.valueOf(encoderThreads), "-preset", "veryfast", "-crf", "26",
                "-c:a", "aac", "-b:a", "128k", output.toString()));
        return c;
    }

    static List<String> thumbnail(Path input, Path output, int niceLevel) {
        List<String> c = base(niceLevel);
        c.addAll(List.of("-y", "-threads", "1", "-i", input.toString(),
                "-ss", "00:00:00.500", "-frames:v", "1", output.toString()));
        return c;
    }

    static List<String> probeDimensions(Path file) {
        return List.of("ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height", "-of", "csv=s=x:p=0", file.toString());
    }

    static List<String> probeDuration(Path file) {
        return List.of("ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "csv=s=,:p=0", file.toString());
    }

    private static List<String> base(int niceLevel) {
        List<String> c = new ArrayList<>();
        if (niceLevel > 0) {
            c.addAll(List.of("nice", "-n", String.valueOf(niceLevel)));
        }
        c.addAll(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error"));
        return c;
    }
}
