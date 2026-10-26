# 개발 문서

## 시스템 이해

- [시스템 지도](architecture-map.md): 서비스 역할과 데이터 흐름
- [코드 탐색](architecture/domain-refactoring-map.md): 기능별 진입점과 주요 클래스
- [구매 후처리](architecture/fcfs-entry-purchase-transaction-boundary-handoff.md): 저장 순서, 트랜잭션과 실패 처리
- [행동 이벤트 계약](architecture/behavior-event-schema-v1.md): 수집 데이터와 사용자 식별
- [마케팅 실행과 전달 이력](architecture/marketing-action-execution-dispatch-history-plan.md)
- [마케팅 실패 분석과 재시도 승인](architecture/dlq-failure-triage-agent-v1-plan.md)

## 실행과 운영

- [Oracle Compose 실행 절차](operations/oracle-compose-baseline-runbook.md)
- [로그·메트릭·트레이스](operations/observability-correlation-v1-plan.md)
- [APM 진단 절차와 기록](operations/otel-jaeger-apm-plan.md)

## 실험과 개발 기록

- [Redis 선착순 판정 비교](experiments/dev-log-2026-10-21-redis-reservation-comparison.md): 개별 호출, WATCH, Lua

- [DB·JPA 개선과 JDBC 벌크 저장 적용](decisions/db-jpa-integrity-audit-2026-08-11.md)
- [장바구니 이탈 후보 조회와 쿠폰 발급 측정](experiments/abandoned-cart-coupon-candidate-query-v1-plan.md)
- [인프라·애플리케이션 병목 진단](experiments/dev-log-2026-07-10-fcfs-3000vu-bottleneck.md): nginx 연결 제한, CPU 할당과 부하 측정 조건

[전체 문서 목록](catalog.md)에서 나머지 기능 설계, 참고 자료와 과거 기록을 찾을 수 있다.
