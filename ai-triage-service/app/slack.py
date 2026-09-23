import hashlib
import hmac
import time
from typing import Any

import httpx

from .config import Settings
from .schemas import AnalysisOutput, ClaimedCase, SlackInteraction


def extract_interaction(payload: dict[str, Any]) -> SlackInteraction:
    """Extract the two Slack interactive payload shapes without guessing fields."""
    user_id = payload.get("user", {}).get("id")
    payload_type = payload.get("type")
    if payload_type == "block_actions":
        action = (payload.get("actions") or [{}])[0]
        action_id = action.get("action_id")
        case_id = action.get("value")
        dedupe_key = payload.get("trigger_id") or payload.get("container", {}).get("message_ts")
        feedback = None
        trigger_id = payload.get("trigger_id")
    elif payload_type == "view_submission":
        view = payload.get("view", {})
        action_id = view.get("callback_id")
        case_id = view.get("private_metadata")
        feedback = None
        for block_values in (view.get("state", {}).get("values") or {}).values():
            for value in block_values.values():
                if value.get("type") == "plain_text_input" and value.get("value"):
                    feedback = value["value"].strip()
                    break
            if feedback:
                break
        dedupe_key = view.get("id") or view.get("hash")
        trigger_id = None
    else:
        raise ValueError(f"Unsupported Slack interaction type: {payload_type}")
    if not user_id or not action_id or case_id is None:
        raise ValueError("Slack interaction is missing user, action, or case")
    try:
        parsed_case_id = int(case_id)
    except (TypeError, ValueError) as error:
        raise ValueError("Slack interaction has an invalid case id") from error
    return SlackInteraction(user_id=user_id, action_id=action_id, case_id=parsed_case_id,
                            feedback=feedback, trigger_id=trigger_id, dedupe_key=dedupe_key)


class SlackNotifier:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None):
        self.settings = settings
        self.client = client or httpx.AsyncClient(timeout=15.0)

    async def send(self, case: ClaimedCase, output: AnalysisOutput,
                   facts: dict[str, Any], update: bool = False) -> str | None:
        if not self.settings.slack_bot_token or not self.settings.slack_channel_id:
            raise RuntimeError("SLACK_BOT_TOKEN and SLACK_CHANNEL_ID are required")
        text = (f"Axon 마케팅 DLQ triage case={case.caseId} dispatch={case.dispatchId} "
                f"category={case.failureCategory} recommendation={output.recommendation}\n"
                f"{output.summary}\n확인: {output.operator_next_step}")
        payload: dict[str, Any] = {
            "channel": self.settings.slack_channel_id,
            "text": text,
            "blocks": [
                {"type": "section", "text": {"type": "mrkdwn", "text": text}},
                {"type": "actions", "elements": [
                    {"type": "button", "text": {"type": "plain_text", "text": "재실행 승인"},
                     "style": "primary", "action_id": "approve", "value": str(case.caseId)},
                    {"type": "button", "text": {"type": "plain_text", "text": "재분석 요청"},
                     "action_id": "request_reanalysis", "value": str(case.caseId)},
                    {"type": "button", "text": {"type": "plain_text", "text": "종료"},
                     "style": "danger", "action_id": "close", "value": str(case.caseId)},
                ]},
            ],
        }
        if update:
            if not case.slackMessageTs:
                raise RuntimeError("Cannot update a triage message without its Slack ts")
            endpoint = "https://slack.com/api/chat.update"
            payload["ts"] = case.slackMessageTs
        else:
            endpoint = "https://slack.com/api/chat.postMessage"
        response = await self.client.post(
            endpoint,
            headers={"Authorization": f"Bearer {self.settings.slack_bot_token}"},
            json=payload,
        )
        response.raise_for_status()
        body = response.json()
        if not body.get("ok"):
            raise RuntimeError(f"Slack API request failed: {body.get('error', 'unknown_error')}")
        return body.get("ts") or case.slackMessageTs

    async def open_reanalysis_modal(self, trigger_id: str, case_id: int) -> None:
        if not self.settings.slack_bot_token:
            raise RuntimeError("SLACK_BOT_TOKEN is required for re-analysis feedback")
        response = await self.client.post(
            "https://slack.com/api/views.open",
            headers={"Authorization": f"Bearer {self.settings.slack_bot_token}"},
            json={
                "trigger_id": trigger_id,
                "view": {
                    "type": "modal",
                    "callback_id": "reanalysis_modal",
                    "private_metadata": str(case_id),
                    "title": {"type": "plain_text", "text": "재분석 요청"},
                    "submit": {"type": "plain_text", "text": "제출"},
                    "close": {"type": "plain_text", "text": "취소"},
                    "blocks": [{
                        "type": "input",
                        "block_id": "feedback_block",
                        "label": {"type": "plain_text", "text": "반려 사유"},
                        "element": {"type": "plain_text_input", "action_id": "feedback"},
                    }],
                },
            },
        )
        response.raise_for_status()
        if not response.json().get("ok", False):
            raise RuntimeError("Slack modal could not be opened")


def verify_slack_signature(settings: Settings, timestamp: str | None, signature: str | None,
                           body: bytes, now: float | None = None) -> bool:
    if not settings.slack_signing_secret or not timestamp or not signature:
        return False
    try:
        timestamp_value = int(timestamp)
    except ValueError:
        return False
    if abs((now or time.time()) - timestamp_value) > 300:
        return False
    base = f"v0:{timestamp}:".encode() + body
    digest = "v0=" + hmac.new(settings.slack_signing_secret.encode(), base, hashlib.sha256).hexdigest()
    return hmac.compare_digest(digest, signature)
