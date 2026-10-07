package com.redditclone.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// The video pipeline relies on these two methods to keep whole videos out of the Java heap. These tests are written to FAIL
// if someone swaps them for an in-memory variant:
//   downloadToFile: ResponseTransformer.toFile(...)  ->  toBytes() (or getObjectAsBytes)
//   putFile:        RequestBody.fromFile(...)        ->  fromBytes(Files.readAllBytes(...))
// They check behaviour a file-backed implementation has and a buffering one cannot, not SDK internals. (Real HTTP round
// trips are in StorageServiceHttpTest.)
class StorageServiceFileTransferTest {

    private S3Client s3;
    private StorageService storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        storage = new StorageService(s3, mock(S3Presigner.class), "media-bucket");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void downloadToFileUsesATransformerThatWritesTheStreamToTheTargetFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("input");

        storage.downloadToFile("u/1/clip.mp4", target, Duration.ofMinutes(3));

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        ArgumentCaptor<ResponseTransformer> transformer = ArgumentCaptor.forClass(ResponseTransformer.class);
        verify(s3).getObject(request.capture(), transformer.capture());
        verify(s3, never()).getObjectAsBytes(any(GetObjectRequest.class));
        assertEquals("media-bucket", request.getValue().bucket());
        assertEquals("u/1/clip.mp4", request.getValue().key());

        // Feed the captured transformer a response stream, the way the SDK would. A file transformer puts those bytes in
        // `target`; a toBytes() transformer returns them in memory and leaves no file behind.
        byte[] body = "pretend this is 200MB of video".getBytes(StandardCharsets.UTF_8);
        transformer.getValue().transform(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(body)));
        assertTrue(Files.exists(target), "the download transformer must write to the target file");
        assertEquals("pretend this is 200MB of video", Files.readString(target));
    }

    @Test
    void downloadToFileGivesTheCallAnApiTimeout() {
        storage.downloadToFile("k", Path.of("/nonexistent/input"), Duration.ofSeconds(90));

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(request.capture(), any(ResponseTransformer.class));
        assertEquals(Duration.ofSeconds(90), request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow());
    }

    @Test
    void putFileUploadsAFileBackedBodyThatIsReadWhenSentNotCopiedWhenCreated(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("output.mp4");
        Files.writeString(file, "AAAAAAAAAA");

        storage.putFile("u/1/clip.mp4-display.mp4", file, "video/mp4", Duration.ofMinutes(3));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertEquals("media-bucket", request.getValue().bucket());
        assertEquals("u/1/clip.mp4-display.mp4", request.getValue().key());
        assertEquals("video/mp4", request.getValue().contentType());
        assertEquals(10L, body.getValue().optionalContentLength().orElseThrow());
        assertEquals(Duration.ofMinutes(3), request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow());

        // The signature of a file-backed body: its bytes come from the file at the moment they are sent. A buffered body
        // (fromBytes(Files.readAllBytes(file))) took a snapshot when it was built and would still return the A's.
        Files.writeString(file, "BBBBBBBBBB");
        try (InputStream sent = body.getValue().contentStreamProvider().newStream()) {
            assertEquals("BBBBBBBBBB", new String(sent.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertTrue(Files.exists(file), "uploading must not consume or move the source file");
    }
}
