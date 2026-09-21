package com.myshop.saleschannel.xianyu;

import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.service.UploadStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XianyuMediaMaterializerTest {

    @TempDir
    Path temporaryDirectory;

    private UploadStorage uploadStorage;
    private XianyuMediaMaterializer materializer;

    @BeforeEach
    void setUp() {
        uploadStorage = new UploadStorage(temporaryDirectory.resolve("uploads").toString());
        uploadStorage.initialize();
        materializer = new XianyuMediaMaterializer(uploadStorage);
    }

    @Test
    void readsManagedUploadsDirectlyAndValidatesImageBytes() throws Exception {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02};
        Files.write(uploadStorage.prepareManagedFile("products/cover.jpg"), jpeg);

        XianyuMediaMaterializer.MaterializedImage image = materializer.readImage("/api/uploads/products/cover.jpg");

        assertEquals("image/jpeg", image.contentType());
        assertEquals("cover.jpg", image.filename());
        assertArrayEquals(jpeg, image.content());
    }

    @Test
    void rejectsTraversalAndDoesNotReadFilesOutsideManagedUploadRoot() throws Exception {
        Path secret = temporaryDirectory.resolve("secret.jpg");
        byte[] content = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        Files.write(secret, content);

        assertThrows(IllegalArgumentException.class,
                () -> materializer.readImage("/api/uploads/../secret.jpg"));
        assertArrayEquals(content, Files.readAllBytes(secret));
    }

    @Test
    void rejectsNonHttpSchemesAndPrivateOrMetadataAddressesBeforeConnecting() {
        assertBlocked("file:///etc/passwd");
        assertBlocked("http://localhost/image.jpg");
        assertBlocked("http://127.0.0.1/image.jpg");
        assertBlocked("http://10.20.30.40/image.jpg");
        assertBlocked("http://169.254.169.254/latest/meta-data/");
        assertBlocked("http://[::1]/image.jpg");
    }

    @Test
    void remoteFetchConnectsToTheValidatedAddressAndPreservesTheUrlHost() throws Exception {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket listener = new ServerSocket(0, 1, loopback);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<String> capturedRequest = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    ByteArrayOutputStream request = new ByteArrayOutputStream();
                    InputStream input = socket.getInputStream();
                    int matched = 0;
                    byte[] endHeaders = {'\r', '\n', '\r', '\n'};
                    while (matched < endHeaders.length) {
                        int value = input.read();
                        if (value < 0) throw new IllegalStateException("HTTP request ended early");
                        request.write(value);
                        matched = value == endHeaders[matched] ? matched + 1 : value == endHeaders[0] ? 1 : 0;
                    }
                    var output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\n"
                            + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(jpeg);
                    output.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    return request.toString(StandardCharsets.US_ASCII);
                }
            });

            XianyuMediaMaterializer.Response response = materializer.fetchPinned(
                    URI.create("http://public.example.test:" + listener.getLocalPort() + "/photo.jpg?size=1"),
                    new InetAddress[]{loopback});

            assertEquals(200, response.status());
            assertArrayEquals(jpeg, response.body());
            String request = capturedRequest.get();
            org.junit.jupiter.api.Assertions.assertTrue(request.startsWith("GET /photo.jpg?size=1 HTTP/1.1"));
            org.junit.jupiter.api.Assertions.assertTrue(request.contains("Host: public.example.test:" + listener.getLocalPort()));
        }
    }

    private void assertBlocked(String url) {
        SalesChannelProviderClientException exception = assertThrows(
                SalesChannelProviderClientException.class, () -> materializer.readImage(url));
        assertEquals(SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                exception.getDisposition());
    }
}
