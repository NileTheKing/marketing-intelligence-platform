package com.axon.core_service.entry.dto;

import java.util.List;
import lombok.Getter;
import org.springframework.data.domain.Slice;

@Getter
public class CampaignEntryHistoryResponse {
    private final List<CampaignEntryHistoryItem> items;
    private final int page;
    private final int size;
    private final boolean hasNext;

    public CampaignEntryHistoryResponse(List<CampaignEntryHistoryItem> items, int page, int size, boolean hasNext) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.hasNext = hasNext;
    }

    public static CampaignEntryHistoryResponse from(Slice<CampaignEntryHistoryItem> slice) {
        return new CampaignEntryHistoryResponse(slice.getContent(), slice.getNumber(), slice.getSize(), slice.hasNext());
    }
}
