# Admission Control — 설계 논의 (보류)

> **상태: 보류(parked). 지금 당장 구현 대상 아님.** 시스템 측면에서 언젠가 필요할 수 있으나, 현 시점 우선순위는 코드 가성비+포폴. 이 문서는 나중에 착수할 때를 위한 **설계 논의 기록**이다.
> **근거**: [`docs/devlog/dev-log-2026-07-10-fcfs-3000vu-bottleneck.md`](../devlog/dev-log-2026-07-10-fcfs-3000vu-bottleneck.md), memory `project_fcfs_loadtest_findings`.
> **개정**: 2026-07-12. 최초의 "3계층 방어/shed 우선" 프레이밍은 폐기하고 아래로 대체함.

---

## 왜 지금 안 하나 (전제)

- CPU 병목은 **entry 1.5 vCPU 고정**으로 큰 이득 소진. 2.1은 재실험 필요(보류).
- 웜업된 상태에서 entry/core/reservation 모두 큰 문제 없음. payment path만 orphan/499 소수(붕괴는 아님).
- FCFS 정원 초과분은 이미 처리됨 → **`EntryReservationService` Lua가 `SADD`+`INCR`+한도체크로 매진(`-2`)을 원자적 즉시 반환**, `EntryController`가 `410 Gone`/`409`로 정직하게 응답. **커머스 한정재고에선 "매진"이 진실이자 최종** → 대기열로 loser를 세울 이유 없음.

즉 "초과분 거절(shed)"은 이미 됨. 남는 건 **자원 보호(과부하 시 앱 안 녹게)** 뿐이고, 그건 지금 당장은 불필요.

---

## 핵심 개념 (재사용 부품)

- **bulkhead**: 동시 처리 중(in-flight) 요청 수를 N개로 묶는 것 = permit N개짜리 세마포어. "다 같이 느려지느니 N개씩 확실히 처리." 재고 게이트(비즈니스 규칙)와 별개인 **자원 보호** 장치.
- **shed vs queue**: 튕김(429 즉시) vs 대기(줄 세움). virtual thread라 **대기가 값싸므로**(OS 스레드 안 잡음) 순수 shed보다 **짧은 bounded wait**(`tryAcquire(1~3초)`, 클라 타임아웃보다 짧게)가 유리 — 마이크로버스트 흡수 + 재시도 폭풍 회피. 사실상 아주 작은 내부 큐. 대기시간=손잡이(0이면 shed, 늘리면 queue).
- **warm-up gate**: "Ready≠warmed". 콜드(meta 캐시 비었음 + JIT 미예열)면 스파이크 붕괴 → warmed 플래그 false면 503. **실운영에선 오픈 전 미리 예열해 플래그 켜두는 게 정석, gate는 "콜드로 문 여는 사고"용 백스톱.** 유저 거절은 사고 시 몇 초뿐(전원 붕괴 < 일부 몇 초 대기).

---

## 만약 구현한다면 (스코프)

| 파트 | 내용 | 판정 |
|---|---|---|
| **A. bulkhead** | entry-service in-process 세마포어(bounded-wait). `AdmissionControlFilter`, 인증 앞. 경로그룹별 permit. 429+Retry-After. Micrometer 메트릭 노출. | 실질 유일한 새 작업. 필요해질 때 여기부터. |
| **B. 결제 rate 게이트** | 당첨 800명이 결제(entry→core)로 몰릴 때 부하 평탄화. | **선측정 후 필요시.** 웜업 시 감당됐으므로 지금 불필요. |
| **C. 거절 이벤트** | 매진/거절 = 초과수요 신호 → 마케팅 통계. | **프론트 JS SDK 담당.** 백엔드는 보호대상이라 hot path에 publish 안 얹음. behavior→Kafka 파이프라인은 **이미 구축됨**(`BackendEventPublisher`는 성공=`reservation_approved`만 발행). 프론트가 실패 outcome 쏘는지 확인만. |
| **warm-up gate** | 콜드 오픈 백스톱. | 선택. 런북 "오픈 전 예열"로 대체 가능(과잉일 수 있음). |

### 멀티노드 (참고, 우리 무관)
- 단일 노드 → in-process 세마포어면 끝(노드 캡=전역 캡).
- 노드 N개 + 병목이 **노드별 자원(CPU/carrier)** → 노드당 캡이 정답(각자 자기 보호).
- 노드 N개 + 병목이 **공유 자원(core/DB)** → Redis 공유 카운터(분산 세마포어). **이미 쓰는 Lua `INCR`/`DECR` 패턴 재활용.**
- 우리는 수평확장 불가 → 해당 없음.

---

## 착수 시 원칙 (미래의 나에게)
- 판단은 **서버측 Prometheus histogram**으로만(client E2E p95는 네트워크 오염).
- 재측정: 외부 k6 + `PRELOAD_CAMPAIGN_META` + 재생성 후 다중 burst 웜업.
- payment/core 내부 로직은 스코프 밖(다른 개발자). admission은 전부 앞단 게이트로만.
- 결정: 파트 A부터, bounded-wait, permit 값은 실측 knee로만 확정(잠정값 문서 박제 금지).
