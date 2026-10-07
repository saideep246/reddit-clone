package com.redditclone.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

// A real S3 client (configured like StorageConfig's) talking to a tiny local HTTP server: proves the file transfer works end
// to end, and that the per-call timeout really stops a stalled transfer (the guarantee the job deadline relies on).
class StorageServiceHttpTest {

    private HttpServer server;
    private S3Client s3;
    private StorageService storage;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedContentType = new AtomicReference<>();
    private final AtomicReference<String> receivedDecodedLength = new AtomicReference<>();
    private volatile byte[] servedBody = new byte[0];
    private volatile boolean stallGet;
    private volatile boolean stallPut;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-r2");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
        s3 = S3Client.builder()
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .endpointOverride(URI.create("http://localhost:" + server.getAddress().getPort()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        storage = new StorageService(s3, mock(S3Presigner.class), "bucket");
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        s3.close();
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if ("PUT".equals(ex.getRequestMethod())) {
                if (stallPut) {
                    awaitRelease();          // never reads the body: the client's upload cannot finish
                } else {
                    byte[] raw = ex.getRequestBody().readAllBytes();
                    String payloadMode = ex.getRequestHeaders().getFirst("x-amz-content-sha256");
                    // Over plain HTTP the SDK signs the body in "aws-chunked" frames; a real S3 endpoint decodes them.
                    // (Over HTTPS, as with R2, the body is sent as is.) Either way the decoded bytes are what matter.
                    receivedBody.set(payloadMode != null && payloadMode.startsWith("STREAMING-") ? decodeAwsChunked(raw) : raw);
                    receivedContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
                    receivedDecodedLength.set(ex.getRequestHeaders().getFirst("x-amz-decoded-content-length"));
                }
                ex.getResponseHeaders().add("ETag", "\"abc\"");
                ex.sendResponseHeaders(200, -1);
            } else if (stallGet) {
                ex.sendResponseHeaders(200, 5_000_000);   // promises 5MB ...
                OutputStream out = ex.getResponseBody();
                out.write(new byte[1000]);                 // ... sends 1KB, then goes silent
                out.flush();
                awaitRelease();
            } else {
                ex.getResponseHeaders().add("Content-Type", "video/mp4");
                ex.sendResponseHeaders(200, servedBody.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(servedBody);
                }
            }
        } finally {
            ex.close();
        }
    }

    // "<hex size>;chunk-signature=...\r\n<data>\r\n" repeated, ended by a zero-size chunk.
    private static byte[] decodeAwsChunked(byte[] raw) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int pos = 0;
        while (pos < raw.length) {
            int lineEnd = pos;
            while (raw[lineEnd] != '\r') {
                lineEnd++;
            }
            String header = new String(raw, pos, lineEnd - pos, java.nio.charset.StandardCharsets.US_ASCII);
            int size = Integer.parseInt(header.substring(0, header.indexOf(';')), 16);
            pos = lineEnd + 2;
            if (size == 0) {
                break;
            }
            out.write(raw, pos, size);
            pos += size + 2;
        }
        return out.toByteArray();
    }

    private void awaitRelease() {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void downloadToFileWritesTheExactBytesToTheFile(@TempDir Path dir) throws IOException {
        servedBody = new byte[3 * 1024 * 1024];
        for (int i = 0; i < servedBody.length; i++) {
            servedBody[i] = (byte) (i * 31);
        }
        Path target = dir.resolve("input");

        storage.downloadToFile("u/1/clip.mp4", target, Duration.ofSeconds(30));

        assertArrayEquals(servedBody, Files.readAllBytes(target));
    }

    @Test
    void putFileSendsTheFileWithItsLengthAndContentType(@TempDir Path dir) throws IOException {
        byte[] content = new byte[2 * 1024 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 17);
        }
        Path file = Files.write(dir.resolve("output.mp4"), content);

        storage.putFile("u/1/clip.mp4-display.mp4", file, "video/mp4", Duration.ofSeconds(30));

        assertArrayEquals(content, receivedBody.get());
        assertEquals("video/mp4", receivedContentType.get());
        // the client knew the exact length up front (a file-backed body), rather than buffering to find out
        assertEquals(String.valueOf(content.length), receivedDecodedLength.get());
    }

    @Test
    void aStalledDownloadIsAbortedByTheCallTimeoutInsteadOfHangingTheWorker(@TempDir Path dir) {
        stallGet = true;
        long start = System.nanoTime();

        assertThrows(SdkClientException.class,
                () -> storage.downloadToFile("k", dir.resolve("input"), Duration.ofSeconds(2)));

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 15, "must give up near the timeout, not wait for the body");
    }

    @Test
    void aStalledUploadIsAbortedByTheCallTimeoutInsteadOfHangingTheWorker(@TempDir Path dir) throws IOException {
        stallPut = true;
        Path file = Files.write(dir.resolve("big.mp4"), new byte[24 * 1024 * 1024]);
        long start = System.nanoTime();

        assertThrows(SdkClientException.class, () -> storage.putFile("k", file, "video/mp4", Duration.ofSeconds(2)));

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 15, "must give up near the timeout, not wait for the server");
    }
}
