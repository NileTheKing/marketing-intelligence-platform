package com.axon.core_service.service;

import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntry;
import com.axon.core_service.repository.CampaignActivityEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignActivityEntryRetryService {

    private final CampaignActivityEntryRepository campaignActivityEntryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSingleEntryInNewTransaction(CampaignActivityEntry entry) {
        campaignActivityEntryRepository.save(entry);
    }
}
