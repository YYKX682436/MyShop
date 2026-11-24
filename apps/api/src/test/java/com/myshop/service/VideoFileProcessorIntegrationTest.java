package com.myshop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VideoFileProcessorIntegrationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void attachedCoverBeforeRealVideoIsNeverChosenForRemuxOrConversion() throws Exception {
        UploadStorage storage = new UploadStorage(directory.resolve("uploads").toString());
        storage.initialize();
        VideoFileProcessor processor = new VideoFileProcessor(storage, mapper, 60);
        for (String codec : List.of("libx264", "mpeg4")) {
            Path input = inputWithCoverFirst(codec);
            JsonNode streams = probe(input).path("streams");
            assertEquals("video", streams.get(0).path("codec_type").asText());
            assertEquals(1, streams.get(0).path("disposition").path("attached_pic").asInt());
            assertEquals("video", streams.get(1).path("codec_type").asText());
            assertEquals(0, streams.get(1).path("disposition").path("attached_pic").asInt());
            assertEquals(codec.equals("libx264") ? "h264" : "mpeg4", streams.get(1).path("codec_name").asText());

            VideoFileProcessor.Result result = processor.process(input, UUID.randomUUID());
            Path output = storage.resolve(result.storagePath());
            assertTrue(Files.isRegularFile(output));
            assertEquals(Files.size(output), result.sizeBytes());
            assertTrue(result.durationMs() >= 2500);
            JsonNode produced = probe(output);
            JsonNode actualVideo = null;
            boolean hasAac = false;
            for (JsonNode stream : produced.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())
                        && stream.path("disposition").path("attached_pic").asInt() == 0) actualVideo = stream;
                if ("audio".equals(stream.path("codec_type").asText())
                        && "aac".equals(stream.path("codec_name").asText())) hasAac = true;
            }
            assertNotNull(actualVideo, "the actual tutorial video must survive");
            assertEquals("h264", actualVideo.path("codec_name").asText());
            assertEquals(320, actualVideo.path("width").asInt());
            assertEquals(180, actualVideo.path("height").asInt());
            assertTrue(hasAac);
            assertNotEquals(seekFrameHash(output, "0.2"), seekFrameHash(output, "2.2"),
                    "seeking must decode different frames from the tutorial");
        }
    }

    private Path inputWithCoverFirst(String codec) throws Exception {
        Path base = directory.resolve(codec + "-base.mp4");
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15:duration=3",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3",
                "-c:v", codec));
        if (codec.equals("libx264")) command.addAll(List.of("-preset", "ultrafast", "-crf", "23"));
        else command.addAll(List.of("-q:v", "3"));
        command.addAll(List.of("-threads", "2", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "64k",
                "-movflags", "+faststart", "-shortest", "-y", base.toString()));
        run(command);

        BufferedImage coverImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = coverImage.createGraphics();
        for (int y = 0; y < 128; y++) {
            graphics.setColor(new Color(y, 60, 180 - y)); graphics.drawLine(0, y, 127, y);
        }
        graphics.setColor(Color.WHITE); graphics.drawString("COVER", 30, 60); graphics.dispose();
        Path cover = directory.resolve(codec + "-cover.jpg");
        assertTrue(ImageIO.write(coverImage, "jpeg", cover.toFile()));

        Path muxed = directory.resolve(codec + "-cover-last.mp4");
        run(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-i", cover.toString(), "-i", base.toString(), "-map", "0:v:0", "-map", "1:v:0", "-map", "1:a:0",
                "-c", "copy", "-disposition:v:0", "attached_pic", "-metadata:s:v:0", "title=Cover",
                "-movflags", "+faststart", "-y", muxed.toString()));
        Path reordered = directory.resolve(codec + "-cover-first.mp4");
        moveCoverMetadataBeforeTracks(muxed, reordered);
        return reordered;
    }

    /**
     * FFmpeg's MP4 muxer puts covr metadata after tracks even with cover first in
     * -map. Move the unchanged udta box before trak, retaining the original
     * moov length and mdat offsets, to exercise the real demuxer edge case.
     */
    private static void moveCoverMetadataBeforeTracks(Path input, Path output) throws IOException {
        byte[] bytes = Files.readAllBytes(input);
        List<Box> top = boxes(bytes, 0, bytes.length);
        List<Box> movies = top.stream().filter(box -> box.type().equals("moov")).toList();
        if (movies.size() != 1) throw new IOException("Expected one moov box");
        Box moov = movies.getFirst();
        List<Box> children = boxes(bytes, moov.offset() + moov.header(), moov.offset() + moov.size());
        List<Box> headers = children.stream().filter(box -> box.type().equals("mvhd")).toList();
        List<Box> metadata = children.stream().filter(box -> box.type().equals("udta")).toList();
        if (headers.size() != 1 || metadata.size() != 1 || children.stream().filter(box -> box.type().equals("trak")).count() < 2)
            throw new IOException("Unexpected generated MP4 structure");
        List<Box> reordered = new ArrayList<>();
        reordered.add(headers.getFirst()); reordered.add(metadata.getFirst());
        children.stream().filter(box -> !box.type().equals("mvhd") && !box.type().equals("udta")).forEach(reordered::add);
        ByteArrayOutputStream result = new ByteArrayOutputStream(bytes.length);
        result.write(bytes, 0, moov.offset() + moov.header());
        for (Box box : reordered) result.write(bytes, box.offset(), box.size());
        result.write(bytes, moov.offset() + moov.size(), bytes.length - moov.offset() - moov.size());
        if (result.size() != bytes.length) throw new IOException("MP4 length changed");
        Files.write(output, result.toByteArray());
    }

    private static List<Box> boxes(byte[] bytes, int offset, int limit) throws IOException {
        List<Box> result = new ArrayList<>();
        while (offset < limit) {
            if (offset + 8 > limit) throw new IOException("Truncated MP4 box");
            ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            long size = Integer.toUnsignedLong(header.getInt(offset));
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            int headerSize = 8;
            if (size == 1) {
                if (offset + 16 > limit) throw new IOException("Truncated extended MP4 box");
                size = header.getLong(offset + 8);
                headerSize = 16;
            } else if (size == 0) size = limit - offset;
            if (size < headerSize || size > limit - offset) throw new IOException("Invalid MP4 box length");
            result.add(new Box(offset, (int) size, headerSize, type));
            offset += (int) size;
        }
        return result;
    }

    private record Box(int offset, int size, int header, String type) { }

    private JsonNode probe(Path source) throws Exception {
        return mapper.readTree(run(List.of("ffprobe", "-v", "error", "-show_entries",
                "format=duration:stream=index,codec_type,codec_name,width,height:stream_disposition=attached_pic",
                "-of", "json", source.toString())));
    }

    private String seekFrameHash(Path source, String seconds) throws Exception {
        String output = run(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-ss", seconds,
                "-i", source.toString(), "-map", "0:v:0", "-frames:v", "1", "-f", "framehash", "-"));
        String frame = output.lines().filter(line -> !line.startsWith("#") && line.contains(",")).findFirst()
                .orElseThrow(() -> new IOException("No video frame decoded after seek"));
        return frame.substring(frame.lastIndexOf(',') + 1).trim();
    }

    private String run(List<String> command) throws Exception {
        Path log = Files.createTempFile(directory, "video-test-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("Video test command timed out");
            }
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
}
