package com.axon.core_service.controller;

import com.axon.core_service.domain.dto.campaign.CampaignRequest;
import com.axon.core_service.domain.dto.campaign.CampaignResponse;
import com.axon.core_service.service.CampaignService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CampaignController 단위 테스트
 *
 * standaloneSetup 방식으로 Security 컨텍스트 없이 컨트롤러 로직만 격리 검증.
 */
class CampaignControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MockMvc mockMvc;
    private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        campaignService = Mockito.mock(CampaignService.class);
        CampaignController controller = new CampaignController(campaignService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("캠페인 생성 요청 → 서비스 호출 및 생성된 캠페인 반환")
    void createCampaign_returnsCreatedCampaign() throws Exception {
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withNano(0);
        LocalDateTime endAt = startAt.plusDays(7);

        CampaignRequest request = CampaignRequest.builder()
                .name("Black Friday")
                .targetSegmentId(42L)
                .rewardType("COUPON")
                .rewardPayload("{\"amount\":1000}")
                .startAt(startAt)
                .endAt(endAt)
                .build();

        CampaignResponse response = CampaignResponse.builder()
                .id(1L)
                .name(request.getName())
                .targetSegmentId(request.getTargetSegmentId())
                .rewardType(request.getRewardType())
                .rewardPayload(request.getRewardPayload())
                .startAt(startAt)
                .endAt(endAt)
                .build();

        Mockito.when(campaignService.createCampaign(Mockito.any(CampaignRequest.class))).thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/campaign")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(response.getId()))
                .andExpect(jsonPath("$.name").value(request.getName()))
                .andExpect(jsonPath("$.rewardType").value(request.getRewardType()));

        ArgumentCaptor<CampaignRequest> captor = ArgumentCaptor.forClass(CampaignRequest.class);
        verify(campaignService).createCampaign(captor.capture());
        CampaignRequest capturedRequest = captor.getValue();
        assertEquals(request.getName(), capturedRequest.getName());
        assertEquals(request.getTargetSegmentId(), capturedRequest.getTargetSegmentId());
        assertEquals(request.getRewardPayload(), capturedRequest.getRewardPayload());
    }

    @Test
    @DisplayName("전체 캠페인 조회 → 목록 반환")
    void getCampaigns_returnsList() throws Exception {
        CampaignResponse response = CampaignResponse.builder()
                .id(1L)
                .name("Black Friday")
                .targetSegmentId(42L)
                .rewardType("COUPON")
                .rewardPayload("{\"amount\":1000}")
                .build();

        Mockito.when(campaignService.getCampaigns()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/campaign"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(response.getId()))
                .andExpect(jsonPath("$[0].name").value(response.getName()));

        verify(campaignService).getCampaigns();
    }

    @Test
    @DisplayName("캠페인 단건 조회 → 해당 캠페인 반환")
    void getCampaign_returnsMatchingCampaign() throws Exception {
        Long campaignId = 5L;
        CampaignResponse response = CampaignResponse.builder()
                .id(campaignId)
                .name("Spring Sale")
                .rewardType("DISCOUNT")
                .build();

        Mockito.when(campaignService.getCampaign(campaignId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/campaign/{id}", campaignId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(campaignId))
                .andExpect(jsonPath("$.name").value("Spring Sale"));

        verify(campaignService).getCampaign(campaignId);
    }
}
