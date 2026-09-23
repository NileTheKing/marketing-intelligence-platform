package com.axon.core_service.service;

import com.axon.core_service.domain.marketing.TriageEvidenceReference;
import com.axon.core_service.exception.BusinessConflictException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TriageEvidenceRenderer {

    public List<String> render(Map<String, Object> facts, List<String> references) {
        if (references == null || references.isEmpty()) {
            throw new BusinessConflictException("At least one triage evidence reference is required");
        }
        Map<String, Object> context = object(facts, "dispatchContext");
        List<String> rendered = new ArrayList<>();
        for (String referenceValue : references) {
            TriageEvidenceReference reference;
            try {
                reference = TriageEvidenceReference.valueOf(referenceValue);
            } catch (IllegalArgumentException exception) {
                throw new BusinessConflictException("Unsupported triage evidence reference");
            }
            rendered.add(switch (reference) {
                case CURRENT_DELIVERY_FAILURE -> currentFailure(context);
                case RECENT_ACTION_FAILURES -> recentFailures(object(facts, "actionFailureHistory"));
                case EXECUTION_DISPATCH_HISTORY -> executionDispatches(list(facts, "executionDispatchHistory"));
                case OPERATOR_CONFIRMED_RECOVERY -> operatorConfirmation(object(facts, "operatorFeedback"));
            });
        }
        return rendered;
    }

    private String currentFailure(Map<String, Object> context) {
        String reason = text(context, "failureReason");
        if (reason == null) {
            throw new BusinessConflictException("Current delivery failure evidence is unavailable");
        }
        return "이번 전달은 " + reason + " 사유로 최종 실패했습니다.";
    }

    private String recentFailures(Map<String, Object> history) {
        Number total = number(history, "totalFailures");
        if (total == null) {
            throw new BusinessConflictException("Recent action failure evidence is unavailable");
        }
        return "최근 30일 같은 액션의 최종 실패는 " + total.longValue() + "건입니다.";
    }

    private String executionDispatches(List<?> history) {
        if (history == null) {
            throw new BusinessConflictException("Previous dispatch history evidence is unavailable");
        }
        return "이 실행의 발송 이력 " + history.size() + "건을 함께 확인했습니다.";
    }

    private String operatorConfirmation(Map<String, Object> feedback) {
        String source = text(feedback, "source");
        String content = text(feedback, "content");
        if (!"operator".equals(source) || content == null || !content.startsWith("관리자 확인 결과:")) {
            throw new BusinessConflictException("Operator recovery confirmation evidence is unavailable");
        }
        return "관리자가 외부 대상의 확인 결과를 입력했습니다.";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new BusinessConflictException("Required triage fact is unavailable: " + key);
        }
        return (Map<String, Object>) map;
    }

    private List<?> list(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (!(value instanceof List<?> list)) {
            throw new BusinessConflictException("Required triage fact is unavailable: " + key);
        }
        return list;
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private Number number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number : null;
    }
}
