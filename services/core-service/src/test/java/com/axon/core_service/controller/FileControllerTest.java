package com.axon.core_service.controller;

import com.axon.core_service.service.file.ImageStorageService;
import com.axon.core_service.service.file.InvalidImageUploadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileControllerTest {

    private ImageStorageService imageStorageService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        imageStorageService = mock(ImageStorageService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FileController(imageStorageService)).build();
    }

    @Test
    void invalidImageReturnsBadRequest() throws Exception {
        when(imageStorageService.store(any()))
                .thenThrow(new InvalidImageUploadException("JPG 또는 PNG 이미지만 업로드할 수 있습니다."));
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.svg", "image/svg+xml", "<svg/>".getBytes());

        mockMvc.perform(multipart("/api/v1/files/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("JPG 또는 PNG 이미지만 업로드할 수 있습니다."));
    }

    @Test
    void validImageReturnsPublicUrl() throws Exception {
        when(imageStorageService.store(any())).thenReturn("/uploads/generated.png");
        MockMultipartFile file = new MockMultipartFile(
                "file", "campaign.png", "image/png", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/uploads/generated.png"));
    }
}
