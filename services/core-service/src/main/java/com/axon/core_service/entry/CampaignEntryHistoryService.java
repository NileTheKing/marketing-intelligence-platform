package com.axon.core_service.entry;

import com.axon.core_service.entry.dto.CampaignEntryHistoryResponse;
import com.axon.core_service.exception.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignEntryHistoryService {
    private final CampaignActivityEntryRepository entryRepository;

    @Transactional(readOnly = true)
    public CampaignEntryHistoryResponse findByUserId(Long userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidRequestException("page must be non-negative and size must be between 1 and 100");
        }
        var pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id")));
        return CampaignEntryHistoryResponse.from(entryRepository.findHistoryByUserId(userId, pageable));
    }
}
