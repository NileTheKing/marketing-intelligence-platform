import asyncio
import hashlib
import hmac
import json
import time

import httpx

from app.config import Settings
from app.schemas import AnalysisOutput, ClaimedCase
from app.slack import SlackNotifier, extract_interaction, verify_slack_signature


def test_view_submission_extracts_case_id_and_feedback_from_nested_state():
    interaction = extract_interaction({
        "type": "view_submission",
        "user": {"id": "U_ADMIN"},
        "view": {
            "id": "V123",
            "callback_id": "reanalysis_modal",
            "private_metadata": "42",
            "state": {"values": {
                "feedback_block": {
                    "feedback": {
                        "type": "plain_text_input",
                        "value": "최근 변경된 쿠폰으로 다시 분석해 주세요.",
                    },
                },
            }},
        },
    })

    assert interaction.action_id == "reanalysis_modal"
    assert interaction.case_id == 42
    assert interaction.feedback == "최근 변경된 쿠폰으로 다시 분석해 주세요."
    assert interaction.dedupe_key == "V123"


def test_slack_signature_requires_fresh_signed_body_and_secret():
    settings = Settings(slack_signing_secret="secret")
    timestamp = str(int(time.time()))
    body = b"payload=%7B%7D"
    signature = "v0=" + hmac.new(
        b"secret", f"v0:{timestamp}:".encode() + body, hashlib.sha256
    ).hexdigest()

    assert verify_slack_signature(settings, timestamp, signature, body)
    assert not verify_slack_signature(settings, str(int(timestamp) - 301), signature, body)


def test_reanalysis_uses_chat_update_without_posting_a_new_message():
    requests = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"ok": True, "ts": "1700000000.000100"})

    async def scenario():
        settings = Settings(slack_bot_token="xoxb-test", slack_channel_id="C123")
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        notifier = SlackNotifier(settings, client)
        case = ClaimedCase(
            caseId=42, dispatchId=11, executionId=7, status="ANALYZING",
            failureCategory="INVALID_TARGET", analysisClaimToken="claim-2",
            analysisAttemptCount=2, slackMessageTs="1700000000.000100",
        )
        output = AnalysisOutput(
            recommendation="NO_RETRY", confidence=1.0, summary="재분석 결과",
            evidence=["대상 오류"], operator_next_step="대상 확인",
        )
        initial_case = case.model_copy(update={"slackMessageTs": None})
        assert await notifier.send(initial_case, output, {"dispatchContext": {}}) == "1700000000.000100"
        await notifier.send(case, output, {"dispatchContext": {}}, update=True)
        await client.aclose()

    asyncio.run(scenario())

    assert [request.url.path for request in requests] == [
        "/api/chat.postMessage", "/api/chat.update"
    ]
    payload = json.loads(requests[1].content)
    assert payload["channel"] == "C123"
    assert payload["ts"] == "1700000000.000100"
