package com.axon.core_service.entry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axon.core_service.exception.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

class CampaignEntryHistoryServiceTest {
    private CampaignActivityEntryRepository entryRepository;
    private CampaignEntryHistoryService service;

    @BeforeEach
    void setUp() {
        entryRepository = mock(CampaignActivityEntryRepository.class);
        service = new CampaignEntryHistoryService(entryRepository);
    }

    @Test
    void rejectsInvalidPageAndSize() {
        assertThatThrownBy(() -> service.findByUserId(1L, -1, 20))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.findByUserId(1L, 0, 0))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.findByUserId(1L, 0, 101))
                .isInstanceOf(InvalidRequestException.class);
    }
}
