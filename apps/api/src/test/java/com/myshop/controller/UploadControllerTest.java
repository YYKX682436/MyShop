package com.myshop.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.junit.jupiter.api.condition.EnabledIf;
import com.myshop.service.impl.IntegrationPostgres;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnabledIf(value = "postgresAvailable", disabledReason = "Disposable PostgreSQL required")
class UploadControllerTest {

    private static final Path UPLOAD_DIRECTORY = createUploadDirectory();
    private static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    static boolean postgresAvailable() { return POSTGRES.available(); }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("upload.path", UPLOAD_DIRECTORY::toString);
        registry.add("upload.url-prefix", () -> "/api/uploads");
    }

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM media_references");
        jdbc.update("DELETE FROM media_assets");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        try (var files = Files.walk(UPLOAD_DIRECTORY)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.equals(UPLOAD_DIRECTORY)) Files.deleteIfExists(file);
            }
        }
    }

    @AfterAll
    static void removeUploadDirectory() throws IOException {
        POSTGRES.close();
        if (Files.exists(UPLOAD_DIRECTORY)) {
            try (var paths = Files.walk(UPLOAD_DIRECTORY)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    @Test
    void jpegAndPngAreWrittenAndCanBeReadThroughStaticResourceRoute() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVR4nGP4z8DQAMIMMAYAOOgF/REzMMkAAAAASUVORK5CYII=");
        var jpeg = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        assertUploadedAndReadable("valid.jpg", "image/jpeg", jpeg.toByteArray());
        assertUploadedAndReadable("valid.png", "image/png", png);
    }

    @Test
    void webpAvifAndHeicAreDecodedThroughTheActualUploadEndpoint() throws Exception {
        byte[] webp = java.util.Base64.getDecoder().decode("UklGRjwAAABXRUJQVlA4IDAAAAAwAgCdASoCAAIAAUAmJaACdLoB+AH4AAYeAAD+8U2v/Vn00Iv7SP/1v/qCA/SAAAA=");
        assertUploadedAndReadable("valid.webp", "image/webp", webp);
        try (var avif = getClass().getResourceAsStream("/media-formats/fox.avif");
             var heic = getClass().getResourceAsStream("/media-formats/fox-derived.heic");
             var p3 = getClass().getResourceAsStream("/media-formats/fox-p3-nclx.heic")) {
            assertNotNull(avif); assertNotNull(heic); assertNotNull(p3);
            byte[] unspecifiedAvif = avif.readAllBytes();
            assertUploadedAndReadable("fox.avif", "image/avif", unspecifiedAvif);
            JsonNode reusedAvif = upload("fox-again.avif", "image/avif", unspecifiedAvif);
            assertTrue(reusedAvif.path("reused").asBoolean()); assertEquals("color_unspecified", reusedAvif.path("reason").asText());
            byte[] unknownColor = heic.readAllBytes();
            assertUploadedAndReadable("fox.heic", "image/heic", unknownColor);
            JsonNode reused = upload("fox-again.heic", "image/heic", unknownColor);
            assertTrue(reused.path("reused").asBoolean()); assertEquals("color_unspecified", reused.path("reason").asText());
            assertUploadedAndReadable("p3.heic", "image/heic", p3.readAllBytes());
        }
    }

    @Test
    void transparentAndLongImagesRemainReadableThroughTheActualUploadEndpoint() throws Exception {
        var alpha = new java.awt.image.BufferedImage(800, 400, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        alpha.setRGB(100, 100, 0x77ff00ff);
        var encodedAlpha = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(alpha, "png", encodedAlpha);
        var alphaResponse = upload("alpha.png", "image/png", encodedAlpha.toByteArray());
        var decodedAlpha = decodeUpload(alphaResponse.path("url").asText());
        assertEquals(0, decodedAlpha.getRGB(500, 200) >>> 24);
        assertTrue((decodedAlpha.getRGB(100, 100) >>> 24) > 0);

        var tall = new java.awt.image.BufferedImage(600, 3600, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = tall.createGraphics(); graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 600, 3600);
        graphics.setColor(java.awt.Color.BLACK); graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 22));
        for (int y = 50; y < 3600; y += 100) graphics.drawString("Tutorial text " + y, 20, y);
        graphics.dispose();
        var encodedTall = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(tall, "png", encodedTall);
        var tallResponse = upload("long.png", "image/png", encodedTall.toByteArray());
        var decodedTall = decodeUpload(tallResponse.path("url").asText());
        assertEquals(600, decodedTall.getWidth()); assertEquals(3600, decodedTall.getHeight());
    }

    @Test
    void gifApngAndAnimatedWebpRemainAnimatedThroughActualUpload() throws Exception {
        byte[] gif = com.myshop.service.ImageProcessingTest.animatedGif();
        byte[] apng = com.myshop.service.ImageProcessingTest.animatedPng();
        byte[] webp = java.util.Base64.getDecoder().decode("UklGRtwAAABXRUJQVlA4WAoAAAACAAAAHwAAHwAAQU5JTQYAAAD/////AABBTk1GWAAAAAAAAAAAAB8AAB8AAMgAAAJWUDggQAAAAHADAJ0BKiAAIAA+bTSWR6QjIiEoCACADYllAMougH4AAEGUEAD+8JtD/8guWF1yNf/yA/5Af8gP/499jCgwAABBTk1GUAAAAAAAAAAAAB8AAB8AAMgAAABWUDggOAAAALQCAJ0BKiAAIAA+bTSWR4KAgAAA2JZQB2AACAoKAAD++R3b//5Af/5Af/5Af/yA//4hd7IDAAAA");
        for (var input : java.util.List.of(new Object[]{"animated.gif", "image/gif", gif},
                new Object[]{"animated.apng", "image/apng", apng}, new Object[]{"animated.webp", "image/webp", webp})) {
            byte[] original = (byte[]) input[2];
            JsonNode result = upload((String) input[0], (String) input[1], original);
            assertEquals("animation_preserved", result.path("reason").asText());
            for (String field : new String[]{"url", "thumbnail_url", "clear_url"}) {
                String url = result.path(field).asText();
                mockMvc.perform(get(url).contextPath("/api")).andExpect(status().isOk()).andExpect(content().bytes(original));
            }
        }
    }

    private JsonNode upload(String name, String mime, byte[] bytes) throws Exception {
        var result = mockMvc.perform(multipart("/api/upload/image").contextPath("/api")
                .file(new MockMultipartFile("file", name, mime, bytes))).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private java.awt.image.BufferedImage decodeUpload(String url) throws Exception {
        Path output = UPLOAD_DIRECTORY.resolve(url.substring("/api/uploads/".length()));
        Path decoded = Files.createTempFile(UPLOAD_DIRECTORY, ".decoded-image-", ".png");
        try {
            Process process = new ProcessBuilder("vips", "copy", output.toString(), decoded.toString()).redirectErrorStream(true).start();
            assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
            var image = javax.imageio.ImageIO.read(decoded.toFile()); assertNotNull(image); return image;
        } finally { Files.deleteIfExists(decoded); }
    }

    @Test
    void contentThatOnlyPretendsToBeAnImageIsRejected() throws Exception {
        MockMultipartFile fakeImage = new MockMultipartFile(
                "file", "fake.jpg", "image/jpeg", "not an image".getBytes());

        mockMvc.perform(multipart("/api/upload/image").contextPath("/api").file(fakeImage))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("内容与扩展名不匹配")));
    }

    @Test
    void nonImageContentTypeIsRejected() throws Exception {
        MockMultipartFile textFile = new MockMultipartFile(
                "file", "payload.txt", "text/plain", "plain text".getBytes());

        mockMvc.perform(multipart("/api/upload/image").contextPath("/api").file(textFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不支持的图片格式")));
    }

    @Test
    void oversizedImageIsRejectedWithAnActionableMessage() throws Exception {
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        oversized[0] = (byte) 0xff;
        oversized[1] = (byte) 0xd8;
        oversized[2] = (byte) 0xff;
        MockMultipartFile file = new MockMultipartFile("file", "large.jpg", "image/jpeg", oversized);

        mockMvc.perform(multipart("/api/upload/image").contextPath("/api").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("图片大小不能超过 10MB"));
    }

    @Test
    void corsAllowsHeadersUsedByTheWebClient() throws Exception {
        MockMvc securedMockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();

        securedMockMvc.perform(options("/api/upload/image")
                        .contextPath("/api")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers",
                                "Content-Type,Accept,X-Session-Token,X-CSRF-Token,X-Device-Id,X-Turnstile-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-CSRF-Token")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Device-Id")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Turnstile-Token")));
    }


    @Test
    void legacyUrlsWorkAndSymlinksAndHiddenMetadataCannotBeRead() throws Exception {
        String oldName = "11111111-1111-1111-1111-111111111111.png";
        byte[] bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVR4nGP4z8DQAMIMMAYAOOgF/REzMMkAAAAASUVORK5CYII=");
        Files.write(UPLOAD_DIRECTORY.resolve(oldName), bytes);
        mockMvc.perform(get("/api/uploads/" + oldName).contextPath("/api")).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        Path outside = Files.createTempFile("myshop-outside-", ".png");
        try {
            Files.write(outside, bytes);
            Files.createSymbolicLink(UPLOAD_DIRECTORY.resolve("22222222-2222-2222-2222-222222222222.png"), outside);
            mockMvc.perform(get("/api/uploads/22222222-2222-2222-2222-222222222222.png").contextPath("/api")).andExpect(status().isNotFound());
            Files.createDirectory(UPLOAD_DIRECTORY.resolve(".media-info"));
            Files.writeString(UPLOAD_DIRECTORY.resolve(".media-info/secret.txt"), "secret");
            mockMvc.perform(get("/api/uploads/.media-info/secret.txt").contextPath("/api")).andExpect(status().isNotFound());
        } finally { Files.deleteIfExists(outside); }
    }

    @Test
    void qrUploadRemainsDecodableAfterRealProcessing() throws Exception {
        var bits = new com.google.zxing.qrcode.QRCodeWriter().encode("myshop real upload QR", com.google.zxing.BarcodeFormat.QR_CODE, 320, 320);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(320, 320, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) image.setRGB(x, y, bits.get(x, y) ? 0xff000000 : 0xffffffff);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        byte[] bytes = png.toByteArray();
        var result = mockMvc.perform(multipart("/api/upload/image").contextPath("/api")
                        .file(new MockMultipartFile("file", "qr.png", "image/png", bytes))
                        .param("preserve_qr", "true"))
                .andExpect(status().isOk())
                .andReturn();
        String url = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("url").asText();
        mockMvc.perform(get(url).contextPath("/api")).andExpect(status().isOk());
        Path output = UPLOAD_DIRECTORY.resolve(url.substring("/api/uploads/".length()));
        Path decoded = Files.createTempFile(UPLOAD_DIRECTORY, ".decoded-qr-", ".png");
        try {
            Process process = new ProcessBuilder("vips", "copy", output.toString(), decoded.toString()).redirectErrorStream(true).start();
            assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
            var rendered = javax.imageio.ImageIO.read(decoded.toFile());
            int[] pixels = rendered.getRGB(0, 0, rendered.getWidth(), rendered.getHeight(), null, 0, rendered.getWidth());
            var reader = new com.google.zxing.MultiFormatReader();
            var decodedQr = reader.decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
                    new com.google.zxing.RGBLuminanceSource(rendered.getWidth(), rendered.getHeight(), pixels))));
            org.junit.jupiter.api.Assertions.assertEquals("myshop real upload QR", decodedQr.getText());
        } finally { Files.deleteIfExists(decoded); }
    }

    @Test
    void duplicateConcurrentUploadsReuseLiveAssetAndDeletedBytesGetANewAddress() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVR4nGP4z8DQAMIMMAYAOOgF/REzMMkAAAAASUVORK5CYII=");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = java.util.stream.IntStream.range(0, 4).mapToObj(index -> pool.submit(() -> {
                var result = mockMvc.perform(multipart("/api/upload/image").contextPath("/api")
                                .file(new MockMultipartFile("file", "same-" + index + ".png", "image/png", png)))
                        .andExpect(status().isOk()).andReturn();
                return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            })).toList();
            java.util.List<JsonNode> results = new java.util.ArrayList<>();
            for (var future : futures) results.add(future.get(90, java.util.concurrent.TimeUnit.SECONDS));
            String oldId = results.getFirst().path("asset_id").asText();
            var variants = java.util.List.of("url", "thumbnail_url", "clear_url");
            assertTrue(results.stream().allMatch(result -> result.path("asset_id").asText().equals(oldId)));
            for (String field : variants) {
                String oldUrl = results.getFirst().path(field).asText();
                assertFalse(oldUrl.isBlank(), field);
                assertTrue(results.stream().allMatch(result -> result.path(field).asText().equals(oldUrl)), field);
                mockMvc.perform(get(oldUrl).contextPath("/api")).andExpect(status().isOk());
            }
            assertTrue(results.stream().anyMatch(result -> result.path("reused").asBoolean()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE deleted_at IS NULL", Integer.class));
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/media")
                    .contextPath("/api").param("filename", oldId)).andExpect(status().isOk());
            for (String field : variants)
                mockMvc.perform(get(results.getFirst().path(field).asText()).contextPath("/api")).andExpect(status().isNotFound());
            var fresh = mockMvc.perform(multipart("/api/upload/image").contextPath("/api")
                    .file(new MockMultipartFile("file", "again.png", "image/png", png))).andExpect(status().isOk()).andReturn();
            var next = objectMapper.readTree(fresh.getResponse().getContentAsString()).path("data");
            assertNotEquals(oldId, next.path("asset_id").asText());
            for (String field : variants) {
                assertNotEquals(results.getFirst().path(field).asText(), next.path(field).asText(), field);
                mockMvc.perform(get(next.path(field).asText()).contextPath("/api")).andExpect(status().isOk());
            }
            assertFalse(next.path("reused").asBoolean());
        } finally { pool.shutdownNow(); }
    }

    private void assertUploadedAndReadable(String originalFilename, String contentType, byte[] bytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", originalFilename, contentType, bytes);

        MvcResult result = mockMvc.perform(multipart("/api/upload/image").contextPath("/api").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.url", startsWith("/api/uploads/")))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String url = body.path("data").path("url").asText();
        String filename = url.substring("/api/uploads/".length());
        Path target = UPLOAD_DIRECTORY.resolve(filename);
        byte[] stored = Files.readAllBytes(target);
        org.junit.jupiter.api.Assertions.assertEquals(stored.length, body.path("data").path("stored_size").asLong());
        String mime = jdbc.queryForObject("SELECT mime_type FROM media_assets WHERE display_path=?", String.class, filename);

        mockMvc.perform(get("/api/uploads/" + filename).contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andExpect(content().contentType(MediaType.parseMediaType(mime)))
                .andExpect(content().bytes(stored));
    }

    private static Path createUploadDirectory() {
        try {
            return Files.createTempDirectory("myshop-upload-controller-test-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
