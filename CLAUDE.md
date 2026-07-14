
# Axon — Agent Guide (AGENTS.md / CLAUDE.md, 동일 내용)

> 이 파일은 매 세션 로드된다. **라우터로 유지 — 상세는 아래 링크 문서에.** 여기 다 넣지 말 것.

## 프로젝트 한 줄
**Axon** = ① 선착순(FCFS) 스파이크 트래픽 수용·판정 + ② 거기서 나온 행동 로그 → 마케팅 통계/대시보드. (entry=수신부 `:8081`, core=처리부 `:8080`, Redis/Kafka/MySQL/Elasticsearch)

## 먼저 읽을 것 (진입 순서)
1. **시스템 지도** → `docs/architecture-map.md` (서비스·이벤트 흐름·토픽·스코프)
2. **문서 목록/권위** → `docs/plan/document-map.md` (topic당 정본 확인)
3. **발견·결정(왜)** → memory 인덱스 (자동 로드됨)
- **충돌 시 코드가 이긴다.** `reference`/`legacy`/`draft` 문서는 코드 대조 전까지 미신뢰.

## 스코프 경계
- 이 레포 = 개발/고도화. 자소서·포폴 산출물은 별도(obsidian).
- **결제(payment) 내부 로직 = 다른 개발자 담당** → 고도화는 앞단 게이트로만.

---

## 행동 지침 (Behavioral guidelines)

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## 5. Documentation Lifecycle

**Documents become stale faster than code. Mark their role explicitly.**

When creating or editing project documents:
- Check for existing documents on the same topic before creating a new one.
- Prefer updating the existing active document over creating another overlapping plan.
- If a new planning document is created under `docs/plan`, register it in `docs/plan/document-map.md`.
- If an older document is replaced, do not delete it silently. Mark it as `legacy` or `superseded` at the top and point to the replacement.
- Keep old documents when they are useful history, but do not let them look like current source of truth.

Use these document states:
- `active`: current source for implementation or strategy.
- `reference`: background or design context; verify against code before reuse.
- `legacy`: old backlog or outdated plan; do not use as current truth without rechecking.
- `draft`: tentative and not yet accepted.
- `superseded`: replaced by another document; include the replacement path.

When using documents as evidence:
- Prefer `active` documents.
- Treat `reference`, `legacy`, and `draft` documents as untrusted until verified against code and current T-files.
- If documents conflict, report the conflict instead of silently choosing one.
- For portfolio/resume claims, verify facts against code, tests, or latest active notes before strengthening the wording.

When finishing work that touched docs, report:
- new documents created
- documents updated
- documents marked `legacy` or `superseded`
- any document/code fact conflict that remains unresolved

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
