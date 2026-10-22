package com.axon.core_service.controller;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CampaignActivityHttpDbIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignActivityRepository campaignActivityRepository;

    private Long campaignId;

    @BeforeEach
    void setUp() {
        campaignId = campaignRepository.save(new Campaign("API integration campaign")).getId();
    }

    @Test
    void adminHttpRequestPersistsActivityToMysql() throws Exception {
        mockMvc.perform(post("/api/v1/campaigns/{campaignId}/activities", campaignId)
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("선착순 이벤트"));

        assertThat(campaignActivityRepository.findAllByCampaign_Id(campaignId))
                .singleElement()
                .satisfies(activity -> {
                    assertThat(activity.getCampaignId()).isEqualTo(campaignId);
                    assertThat(activity.getStatus()).isEqualTo(CampaignActivityStatus.DRAFT);
                });
    }

    @Test
    void invalidHttpRequestDoesNotPersistAnything() throws Exception {
        String invalidRequest = validRequest()
                .replace("2026-08-13T10:00:00", "2026-08-11T10:00:00");

        mockMvc.perform(post("/api/v1/campaigns/{campaignId}/activities", campaignId)
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        assertThat(campaignActivityRepository.findAllByCampaign_Id(campaignId)).isEmpty();
    }

    @Test
    void missingCampaignReturnsNotFoundAndDoesNotPersist() throws Exception {
        mockMvc.perform(post("/api/v1/campaigns/{campaignId}/activities", Long.MAX_VALUE)
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));

        assertThat(campaignActivityRepository.findAllByCampaign_Id(campaignId)).isEmpty();
    }

    @Test
    void rejectedStatusTransitionKeepsPersistedStatus() throws Exception {
        String body = mockMvc.perform(post("/api/v1/campaigns/{campaignId}/activities", campaignId)
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long activityId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("id").asLong();

        mockMvc.perform(patch("/api/v1/campaign-activities/{activityId}/status", activityId)
                        .param("status", "ENDED")
                        .with(user("1").roles("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BUSINESS_CONFLICT"));

        assertThat(campaignActivityRepository.findById(activityId).orElseThrow().getStatus())
                .isEqualTo(CampaignActivityStatus.DRAFT);
    }

    private String validRequest() {
        return """
                {
                  "name": "선착순 이벤트",
                  "limitCount": 100,
                  "status": "DRAFT",
                  "startDate": "2026-08-12T10:00:00",
                  "endDate": "2026-08-13T10:00:00",
                  "activityType": "FIRST_COME_FIRST_SERVE",
                  "price": 10000,
                  "quantity": 1
                }
                """;
    }
}
