package com.axon.core_service.domain.dto.llm;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DashboardQueryResponse {
    private String answer; // Natural language answer
    private Object data; // Structured data used for the answer
    private String queryIntent; // Debug info: what the system understood
    private Map<String, Object> metadata; // Tool calls and data scope used to generate the answer

    public DashboardQueryResponse(String answer, Object data, String queryIntent) {
        this(answer, data, queryIntent, Map.of());
    }
}
