from typing import Any

import httpx

from .config import Settings
from .schemas import AnalysisOutput, ClaimedCase


class CoreClient:
    """HTTP-only boundary to Core; this service never opens Core DB/Kafka/Redis connections."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None):
        self.settings = settings
        self.client = client or httpx.AsyncClient(timeout=20.0)

    def _headers(self) -> dict[str, str]:
        return {"X-Axon-Triage-Token": self.settings.core_triage_service_token}

    async def claim(self, case_id: int | None = None) -> ClaimedCase | None:
        params = {"caseId": str(case_id)} if case_id is not None else None
        response = await self.client.post(
            f"{self.settings.core_internal_base_url}/internal/v1/marketing-triage/cases/claim",
            params=params,
            headers=self._headers(),
        )
        if response.status_code == 204:
            return None
        response.raise_for_status()
        return ClaimedCase.model_validate(response.json())

    async def get_dispatch_context(self, dispatch_id: int) -> dict[str, Any]:
        return await self._get(f"/dispatches/{dispatch_id}/context")

    async def get_action_failure_history(self, action_id: int, days: int = 30) -> dict[str, Any]:
        return await self._get(f"/actions/{action_id}/failure-history", params={"days": min(days, 30)})

    async def get_execution_dispatch_history(self, execution_id: int) -> list[dict[str, Any]]:
        return await self._get(f"/executions/{execution_id}/dispatch-history")

    async def _get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        response = await self.client.get(
            f"{self.settings.core_internal_base_url}/internal/v1/marketing-triage{path}",
            params=params,
            headers=self._headers(),
        )
        response.raise_for_status()
        return response.json()

    async def save_analysis(self, case_id: int, claim_token: str, facts: dict[str, Any], output: AnalysisOutput) -> dict[str, Any]:
        response = await self.client.post(
            f"{self.settings.core_internal_base_url}/internal/v1/marketing-triage/cases/{case_id}/analysis",
            headers=self._headers(),
            json={
                "claimToken": claim_token,
                "factSnapshot": facts,
                "recommendation": output.recommendation,
                "confidence": output.confidence,
                "summary": output.summary,
                "evidence": output.evidence,
                "operatorNextStep": output.operator_next_step,
                "llmModel": self.settings.llm_model,
            },
        )
        response.raise_for_status()
        return response.json()

    async def decide(self, case_id: int, decision: str, user_id: str, reason: str | None = None) -> dict[str, Any]:
        response = await self.client.post(
            f"{self.settings.core_internal_base_url}/internal/v1/marketing-triage/cases/{case_id}/decision",
            headers=self._headers(),
            json={"decision": decision, "decidedBy": user_id, "reason": reason},
        )
        response.raise_for_status()
        return response.json()

    async def record_slack_message(self, case_id: int, message_ts: str) -> dict[str, Any]:
        response = await self.client.post(
            f"{self.settings.core_internal_base_url}/internal/v1/marketing-triage/cases/{case_id}/slack-message",
            headers=self._headers(),
            json={"messageTs": message_ts},
        )
        response.raise_for_status()
        return response.json()

    async def fail_analysis(self, case_id: int, claim_token: str, reason: str) -> dict[str, Any]:
        response = await self.client.post(
            f"{self.settings.core_internal_base_url}/internal/v1/marketing-triage/cases/{case_id}/analysis-failed",
            headers=self._headers(),
            json={"claimToken": claim_token, "reason": reason[:1000]},
        )
        response.raise_for_status()
        return response.json()
