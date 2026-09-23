from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class AnalysisOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    recommendation: Literal["RETRY_RECOMMENDED", "MANUAL_INVESTIGATION", "NO_RETRY"]
    confidence: float = Field(ge=0.0, le=1.0)
    summary: str = Field(min_length=1, max_length=2000)
    evidence: list[str] = Field(min_length=1, max_length=10)
    operator_next_step: str = Field(min_length=1, max_length=1000)


class ClaimedCase(BaseModel):
    caseId: int
    dispatchId: int
    executionId: int
    status: str
    failureCategory: str
    failureReason: str | None = None
    analysisClaimToken: str
    analysisClaimExpiresAt: str | None = None
    analysisAttemptCount: int
    operatorGuidance: str | None = None
    slackMessageTs: str | None = None
    factSnapshot: dict[str, Any] | None = None


class SlackInteraction(BaseModel):
    user_id: str
    action_id: str
    case_id: int
    feedback: str | None = None
    trigger_id: str | None = None
    dedupe_key: str | None = None
