package com.myshop.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class UploadControllerTest {

    private static final Path UPLOAD_DIRECTORY = createUploadDirectory();

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("upload.path", UPLOAD_DIRECTORY::toString);
        registry.add("upload.url-prefix", () -> "/api/uploads");
    }

    @BeforeEach
    void setUp() throws IOException {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        try (var files = Files.walk(UPLOAD_DIRECTORY)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.equals(UPLOAD_DIRECTORY)) Files.deleteIfExists(file);
            }
        }
    }

    @AfterAll
    static void removeUploadDirectory() throws IOException {
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
    void legacyQrPreservationFlagCannotBypassStandardCompression() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(800, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(42);
        for (int y = 0; y < 800; y++) for (int x = 0; x < 800; x++) {
            int n = random.nextInt(12);
            image.setRGB(x, y, new java.awt.Color(210 + n, 220 + n, 230 + n).getRGB());
        }
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        byte[] bytes = png.toByteArray();
        var result = mockMvc.perform(multipart("/api/upload/image").contextPath("/api")
                        .file(new MockMultipartFile("file", "qr.png", "image/png", bytes))
                        .param("preserve_qr", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("compressed"))
                .andExpect(jsonPath("$.data.stored_size").value(org.hamcrest.Matchers.lessThan(bytes.length)))
                .andReturn();
        String url = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("url").asText();
        mockMvc.perform(get(url).contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
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
        org.junit.jupiter.api.Assertions.assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(stored)));

        mockMvc.perform(get("/api/uploads/" + filename).contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andExpect(content().contentType(MediaType.parseMediaType(contentType)))
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
