package com.axon.core_service.service;

import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntry;
import com.axon.core_service.repository.CampaignActivityEntryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignActivityEntryBatchPersistenceService {

    private final CampaignActivityEntryRepository campaignActivityEntryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveBatch(List<CampaignActivityEntry> entries) {
        campaignActivityEntryRepository.saveAllAndFlush(entries);
    }
}
