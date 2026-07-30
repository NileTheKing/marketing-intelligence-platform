# Portfolio Evidence Writing Guide

Status: active

## Purpose

Obsidian vault의 `T*.md`는 이력서 문장이 아니라, 회사별 포트폴리오를 다시 조립할 때 쓰는 원자 단위의 **증거 정본**이다. 결론 수치만 남기지 말고, 다른 에이전트가 "무엇을 관측하고 어떤 가설을 기각해 이 조치를 했는가"를 재구성할 수 있게 작성한다.

Vault root:

`/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼`

T 파일 목록과 주제는 `docs/plan/document-map.md`의 `Current Portfolio Source Notes`가 정본이다.

## T File Update Protocol

T 파일을 새로 쓰거나 수정하라는 요청을 받으면:

1. 대응하는 기존 T 파일, 현재 코드, `document-map`의 active 문서를 먼저 읽는다.
2. 사실이 바뀌었어도 과거 실험·결과를 삭제하지 않는다. `초기 진단`, `historical`, `N=1`, `현재 대표 결과`처럼 신뢰 수준과 환경을 분리한다.
3. 증거가 없는 개선 수치·원인·처리량을 만들지 않는다. 불명확하면 조건 또는 검증 필요 상태를 남긴다.
4. 외부 Obsidian 파일 변경 전에는 사용자가 요청한 범위인지 확인하고, 변경 후에는 어떤 T 파일을 어떻게 갱신했는지 보고한다.

## Required Evidence Shape

각 T 파일은 주제에 맞는 범위에서 아래를 보존한다.

| 항목 | 남겨야 할 내용 |
|---|---|
| 문제와 비즈니스 영향 | 왜 이 문제가 커머스 사용자·운영·데이터 정합성에 중요한가 |
| 환경과 시나리오 | 배포 환경, 트래픽 shape, users/VUs/limit, resource profile, warm-up 여부 |
| 관측 | 누가 무엇을 수집했는가. trace, metric, log, domain check의 역할 구분 |
| 가설과 기각 | 처음 후보, 비교 실험, 기각된 원인과 근거 |
| 조치 | 코드·설정·운영 절차 중 실제 반영한 변경 |
| 결과 | 같은 조건의 결과, 정합성 검증, 재현 횟수 |
| 한계와 다음 조건 | N=1, scrape 간격, diagnostic overhead, 아직 확인하지 않은 범위 |

성능/관측 T 파일은 특히 다음을 구분한다.

```text
Trace  = 요청 한 건의 호출 경로와 라이브러리 경계 시간
Metric = 시간에 따른 자원·큐·지연·오류의 상태
Log    = timeout, 5xx, 예외 같은 사건 원문
Domain check = Redis/DB 수량과 비동기 수렴의 실제 정합성
```

예를 들어 OTel/Jaeger 자동 trace, AOP+Micrometer diagnostic metric, Actuator/Prometheus system metric, k6/nginx/Spring log는 서로 대체하지 않는다. 각 도구가 답한 질문과, 그 결과가 다음 가설·조치로 어떻게 이어졌는지 적는다.

## Claim Boundaries

- `VU`, unique user, RPS/TPS를 서로 바꿔 쓰지 않는다.
- K2P/Kubernetes와 Oracle VM/Docker Compose 결과를 같은 before/after 표에 섞지 않는다.
- OTel/AOP diagnostic 수치와 agent-off headline 성능 수치를 분리한다.
- 단일 실행은 가능성 확인이지 capacity/SLA 확정이 아니다.
- "유일한 병목", "데이터 유실 0" 같은 절대 표현은 환경·시나리오·검증 범위를 붙인다.
- 클래스명·metric명은 증거를 재현하는 데 필요한 경우에만 쓰고, 먼저 역할과 판단을 자연어로 설명한다.

## Portfolio Reuse

T 파일에서 회사별 포트폴리오 문단을 만들 때는 결론만 압축하지 않는다. 최소한 `문제 → 관측 설계 → 가설 검증 → 조치 → 결과·한계` 흐름을 유지한다. 포트폴리오 독자에게는 역할 언어를 우선하고, 클래스·세부 metric명은 면접 후속 질문을 위한 보조 증거로 둔다.
