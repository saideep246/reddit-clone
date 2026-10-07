package com.redditclone.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// The video pipeline relies on these two methods to keep whole videos out of the Java heap: the bucket is streamed to
// and from a file instead of get()/put(byte[]), which copy the whole object (at least twice) in memory.
class StorageServiceFileTransferTest {

    private S3Client s3;
    private StorageService storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        storage = new StorageService(s3, mock(S3Presigner.class), "media-bucket");
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadToFileStreamsTheObjectToAFileAndNeverReadsItAsBytes(@TempDir Path dir) {
        storage.downloadToFile("u/1/clip.mp4", dir.resolve("input"));

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(request.capture(), any(ResponseTransformer.class));
        assertEquals("media-bucket", request.getValue().bucket());
        assertEquals("u/1/clip.mp4", request.getValue().key());
        verify(s3, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    void putFileUploadsFromTheFileWithItsLengthAndContentType(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("output.mp4");
        Files.write(file, new byte[12_345]);

        storage.putFile("u/1/clip.mp4-display.mp4", file, "video/mp4");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertEquals("media-bucket", request.getValue().bucket());
        assertEquals("u/1/clip.mp4-display.mp4", request.getValue().key());
        assertEquals("video/mp4", request.getValue().contentType());
        assertEquals(12_345L, body.getValue().optionalContentLength().orElseThrow());
        assertTrue(Files.exists(file), "uploading must not consume or move the source file");
    }
}
