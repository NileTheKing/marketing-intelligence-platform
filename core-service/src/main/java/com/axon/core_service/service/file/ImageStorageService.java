package com.axon.core_service.service.file;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImageStorageService {

    static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;
    static final long MAX_PIXEL_COUNT = 25_000_000L;

    private final Path uploadDirectory;

    public ImageStorageService(@Value("${axon.upload.directory:core-service/uploads}") String uploadDirectory) {
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file) throws IOException {
        ValidatedImage image = validate(file);
        Files.createDirectories(uploadDirectory);

        String filename = UUID.randomUUID() + image.extension();
        Path target = uploadDirectory.resolve(filename);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return "/uploads/" + filename;
    }

    private ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidImageUploadException("파일이 비어있습니다.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidImageUploadException("이미지는 5MB 이하만 업로드할 수 있습니다.");
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(file.getInputStream())) {
            if (input == null) {
                throw new InvalidImageUploadException("유효한 이미지 파일이 아닙니다.");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidImageUploadException("JPG 또는 PNG 이미지만 업로드할 수 있습니다.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String extension = extensionFor(reader.getFormatName());
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXEL_COUNT) {
                    throw new InvalidImageUploadException("이미지 해상도가 너무 큽니다.");
                }

                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new InvalidImageUploadException("이미지 내용을 읽을 수 없습니다.");
                }
                return new ValidatedImage(extension);
            } finally {
                reader.dispose();
            }
        } catch (InvalidImageUploadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new InvalidImageUploadException("유효한 이미지 파일이 아닙니다.", exception);
        }
    }

    private String extensionFor(String formatName) {
        return switch (formatName.toLowerCase(Locale.ROOT)) {
            case "jpeg", "jpg" -> ".jpg";
            case "png" -> ".png";
            default -> throw new InvalidImageUploadException("JPG 또는 PNG 이미지만 업로드할 수 있습니다.");
        };
    }

    private record ValidatedImage(String extension) {
    }
}
