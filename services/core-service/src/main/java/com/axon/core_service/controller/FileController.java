package com.axon.core_service.controller;

import com.axon.core_service.service.file.ImageStorageService;
import com.axon.core_service.service.file.InvalidImageUploadException;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final ImageStorageService imageStorageService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(Map.of("url", imageStorageService.store(file)));
        } catch (InvalidImageUploadException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        } catch (IOException exception) {
            log.error("Failed to store campaign image", exception);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "파일 업로드 중 오류가 발생했습니다."));
        }
    }
}
