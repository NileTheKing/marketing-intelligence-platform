import hashlib
import hmac
import json
import time
import asyncio
from collections import OrderedDict

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import ServiceRuntime, create_app


class FakeCore:
    async def decide(self, case_id, decision, user_id, reason=None):
        return {"caseId": case_id, "decision": decision}


class FakeNotifier:
    async def open_reanalysis_modal(self, trigger_id, case_id, mode):
        return None


class FakeTriage:
    def __init__(self):
        self.calls = []

    async def reanalyze(self, case_id, feedback):
        self.calls.append((case_id, feedback))

    async def decide(self, case_id, decision, user_id, reason=None):
        self.calls.append((case_id, decision, user_id, reason))


class FakeRuntime:
    def __init__(self):
        self.core = FakeCore()
        self.notifier = FakeNotifier()
        self.triage = FakeTriage()
        self.submitted = []
        self.tasks = set()
        self.interaction_keys = OrderedDict()

    def submit_interaction(self, dedupe_key, work):
        if dedupe_key in self.interaction_keys:
            work.close()
            return False
        task = asyncio.create_task(work)
        self.interaction_keys[dedupe_key] = None
        self.submitted.append((dedupe_key, work))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return True


def signed_headers(body: bytes):
    timestamp = str(int(time.time()))
    signature = "v0=" + hmac.new(
        b"secret", f"v0:{timestamp}:".encode() + body, hashlib.sha256
    ).hexdigest()
    return {
        "content-type": "application/json",
        "X-Slack-Request-Timestamp": timestamp,
        "X-Slack-Signature": signature,
    }


def test_slack_retry_header_does_not_drop_first_processing_and_dedupes_same_interaction():
    runtime = FakeRuntime()
    app = create_app(
        Settings(slack_signing_secret="secret", slack_allowed_user_ids="U_ADMIN"), runtime
    )
    payload = {
        "type": "view_submission",
        "user": {"id": "U_ADMIN"},
        "view": {
            "id": "V_RETRY", "callback_id": "request_investigation_modal", "private_metadata": "42",
            "state": {"values": {"feedback_block": {"feedback": {
                "type": "plain_text_input", "value": "재전송 테스트",
            }}}},
        },
    }
    body = json.dumps(payload).encode()
    headers = signed_headers(body)
    headers["X-Slack-Retry-Num"] = "1"

    with TestClient(app) as client:
        first = client.post("/slack/interactions", content=body, headers=headers)
        second = client.post("/slack/interactions", content=body, headers=headers)

    assert first.status_code == 200
    assert second.status_code == 200
    assert len(runtime.submitted) == 1


def test_view_submission_acknowledges_immediately_and_submits_background_reanalysis():
    runtime = FakeRuntime()
    app = create_app(
        Settings(slack_signing_secret="secret", slack_allowed_user_ids="U_ADMIN"), runtime
    )
    payload = {
        "type": "view_submission",
        "user": {"id": "U_ADMIN"},
        "view": {
            "id": "V123", "callback_id": "record_confirmation_modal", "private_metadata": "42",
            "state": {"values": {"feedback_block": {"feedback": {
                "type": "plain_text_input", "value": "사유",
            }}}},
        },
    }
    body = json.dumps(payload).encode()

    with TestClient(app) as client:
        response = client.post("/slack/interactions", content=body, headers=signed_headers(body))

    assert response.status_code == 200
    assert response.json() == {"response_action": "clear"}
    assert len(runtime.submitted) == 1


def test_background_submission_starts_once_and_deduplicates_slack_retry():
    async def scenario():
        runtime = object.__new__(ServiceRuntime)
        runtime.background_tasks = set()
        runtime.interaction_keys = OrderedDict()
        started = asyncio.Event()

        async def work():
            started.set()

        assert runtime.submit_interaction("view-V123", work())
        await asyncio.wait_for(started.wait(), timeout=1)

        async def duplicate_work():
            raise AssertionError("duplicate Slack retry must not start work")

        assert not runtime.submit_interaction("view-V123", duplicate_work())
        await asyncio.gather(*runtime.background_tasks, return_exceptions=True)

    asyncio.run(scenario())


def test_metrics_endpoint_is_available_without_operational_trace_export():
    runtime = FakeRuntime()
    app = create_app(Settings(), runtime)

    with TestClient(app) as client:
        response = client.get("/metrics")

    assert response.status_code == 200
    assert "axon_triage_cases_total" in response.text
