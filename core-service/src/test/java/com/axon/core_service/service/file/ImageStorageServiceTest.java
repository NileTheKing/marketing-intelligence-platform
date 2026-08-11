package com.axon.core_service.service.file;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageStorageServiceTest {

    @TempDir
    Path uploadDirectory;

    @Test
    void storesDecodedPngWithServerControlledExtension() throws Exception {
        ImageStorageService service = new ImageStorageService(uploadDirectory.toString());
        MockMultipartFile disguisedFile = new MockMultipartFile(
                "file", "campaign.html", "text/html", pngBytes());

        String url = service.store(disguisedFile);

        assertThat(url).startsWith("/uploads/").endsWith(".png");
        assertThat(Files.exists(uploadDirectory.resolve(url.substring("/uploads/".length())))).isTrue();
    }

    @Test
    void rejectsExecutableContentEvenWhenNamedAsImage() {
        ImageStorageService service = new ImageStorageService(uploadDirectory.toString());
        MockMultipartFile svg = new MockMultipartFile(
                "file",
                "campaign.png",
                "image/png",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes());

        assertThatThrownBy(() -> service.store(svg))
                .isInstanceOf(InvalidImageUploadException.class)
                .hasMessageContaining("JPG 또는 PNG");
        assertThat(uploadDirectory).isEmptyDirectory();
    }

    @Test
    void rejectsMalformedImageBytes() {
        ImageStorageService service = new ImageStorageService(uploadDirectory.toString());
        MockMultipartFile malformed = new MockMultipartFile(
                "file", "campaign.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        assertThatThrownBy(() -> service.store(malformed))
                .isInstanceOf(InvalidImageUploadException.class);
    }

    @Test
    void rejectsFileLargerThanFiveMegabytes() {
        ImageStorageService service = new ImageStorageService(uploadDirectory.toString());
        byte[] oversized = new byte[(int) ImageStorageService.MAX_FILE_SIZE_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "large.png", "image/png", oversized);

        assertThatThrownBy(() -> service.store(file))
                .isInstanceOf(InvalidImageUploadException.class)
                .hasMessageContaining("5MB");
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
