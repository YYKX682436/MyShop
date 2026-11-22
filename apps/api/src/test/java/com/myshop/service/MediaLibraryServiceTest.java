package com.myshop.service;

import com.myshop.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class MediaLibraryServiceTest {
    @TempDir Path temporary;

    @Test
    void listsOnlyManagedRootImagesAndSupportsSearchSortingAndQrFilter() throws Exception {
        UploadStorage storage = new UploadStorage(temporary.resolve("public").toString()); storage.initialize();
        var upload = new ImageUploadService(storage, new ImageProcessor(), "/api/uploads");
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVR4nGP4z8DQAMIMMAYAOOgF/REzMMkAAAAASUVORK5CYII=");
        String url = upload.uploadAdminImage(new MockMultipartFile("file", "商品封面.png", "image/png", png)).url();
        String old = "11111111-1111-1111-1111-111111111111.gif";
        Files.write(storage.directory().resolve(old), new byte[]{1});
        Files.setLastModifiedTime(storage.directory().resolve(old), FileTime.fromMillis(1000));
        Files.writeString(storage.directory().resolve("other.txt"), "private");
        Files.createDirectories(storage.directory().resolve("avatars"));
        Files.write(storage.directory().resolve("avatars/avatar-111.png"), png);
        Path outside = temporary.resolve("outside.png"); Files.write(outside, png);
        Files.createSymbolicLink(storage.directory().resolve("22222222-2222-2222-2222-222222222222.png"), outside);
        MediaLibraryService media = new MediaLibraryService(storage, "/api/uploads");
        assertEquals(2, media.list(1, 24, "", "desc").total());
        assertEquals("商品封面.png", media.list(1, 24, "封面", "desc").items().getFirst().name());
        assertTrue(media.list(1, 1, "", "asc").items().getFirst().url().endsWith(old));
        assertEquals(1, media.list(2, 1, "", "asc").items().size());
        assertFalse(storage.isSafePublicFile("../outside.png"));
        assertFalse(storage.isSafePublicFile("avatars/../" + old));
        assertFalse(storage.isSafePublicFile("22222222-2222-2222-2222-222222222222.png"));
        assertThrows(BusinessException.class, () -> media.list(1, 1000, "", "desc"));
    }
}
