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
        try (var files = Files.list(UPLOAD_DIRECTORY)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
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
        assertUploadedAndReadable("valid.jpg", "image/jpeg", new byte[]{
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00, 0x01
        });
        assertUploadedAndReadable("valid.png", "image/png", new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00
        });
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
        org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, Files.readAllBytes(target));

        mockMvc.perform(get("/api/uploads/" + filename).contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType(contentType)))
                .andExpect(content().bytes(bytes));
    }

    private static Path createUploadDirectory() {
        try {
            return Files.createTempDirectory("myshop-upload-controller-test-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
