package com.axon.core_service.repository;

import com.axon.core_service.domain.campaign.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    @Query("SELECT DISTINCT campaign FROM Campaign campaign " +
            "LEFT JOIN FETCH campaign.campaignActivities")
    List<Campaign> findAllWithActivities();

    /**
 * Finds a campaign by its name.
 *
 * @param name the campaign name to search for
 * @return an Optional containing the Campaign with the given name, or empty if none exists
 */
Optional<Campaign> findByName(String name);
}
