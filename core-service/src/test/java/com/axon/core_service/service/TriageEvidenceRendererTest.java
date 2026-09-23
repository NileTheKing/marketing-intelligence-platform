package com.axon.core_service.service;

import com.axon.core_service.exception.BusinessConflictException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriageEvidenceRendererTest {

    private final TriageEvidenceRenderer renderer = new TriageEvidenceRenderer();

    @Test
    void rendersOnlyFactsSupportedByTheCoreSnapshot() {
        Map<String, Object> facts = Map.of(
                "dispatchContext", Map.of("failureReason", "HTTP 500 응답"),
                "actionFailureHistory", Map.of("totalFailures", 3),
                "executionDispatchHistory", List.of(Map.of("dispatchId", 1)),
                "operatorFeedback", Map.of("source", "operator", "content", "관리자 확인 결과: endpoint 200 확인")
        );

        assertThat(renderer.render(facts, List.of(
                "CURRENT_DELIVERY_FAILURE",
                "RECENT_ACTION_FAILURES",
                "EXECUTION_DISPATCH_HISTORY",
                "OPERATOR_CONFIRMED_RECOVERY"
        ))).containsExactly(
                "이번 전달은 HTTP 500 응답 사유로 최종 실패했습니다.",
                "최근 30일 같은 액션의 최종 실패는 3건입니다.",
                "이 실행의 발송 이력 1건을 함께 확인했습니다.",
                "관리자가 외부 대상의 확인 결과를 입력했습니다."
        );
    }

    @Test
    void rejectsUnsupportedOrUnconfirmedEvidenceReferences() {
        Map<String, Object> facts = Map.of(
                "dispatchContext", Map.of("failureReason", "timeout"),
                "actionFailureHistory", Map.of("totalFailures", 1),
                "executionDispatchHistory", List.of()
        );

        assertThatThrownBy(() -> renderer.render(facts, List.of("MADE_UP_FACT")))
                .isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> renderer.render(facts, List.of("OPERATOR_CONFIRMED_RECOVERY")))
                .isInstanceOf(BusinessConflictException.class);
    }
}
