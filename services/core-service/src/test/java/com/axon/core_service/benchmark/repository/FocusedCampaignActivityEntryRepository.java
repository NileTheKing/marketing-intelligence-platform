package com.axon.core_service.benchmark.repository;

import com.axon.core_service.repository.CampaignActivityEntryRepository;
import org.springframework.context.annotation.Profile;

/** Restricts the focused HTTP benchmark context to the history repository. */
@Profile("campaign-entry-history-benchmark")
public interface FocusedCampaignActivityEntryRepository extends CampaignActivityEntryRepository {
}
