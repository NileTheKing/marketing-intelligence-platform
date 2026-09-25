import asyncio

from langgraph.checkpoint.memory import MemorySaver
from langchain_core.messages import AIMessage

from app.agent import TriageRuntime, build_read_only_tools
from app.config import Settings
from app.schemas import AnalysisOutput, ClaimedCase


class FakeCore:
    def __init__(self):
        self.saved = None
        self.saved_outputs = []
        self.saved_facts = []
        self.claim_count = 0
        self.decisions = []

    async def get_dispatch_context(self, dispatch_id):
        return {"dispatchId": dispatch_id, "actionId": 5, "failureReason": "Coupon not found for ID: 99"}

    async def get_action_failure_history(self, action_id, days):
        return {"actionId": action_id, "days": days, "totalFailures": 1, "byCategory": []}

    async def get_execution_dispatch_history(self, execution_id):
        return [{"executionId": execution_id, "dispatchId": 11, "status": "FAILED_FINAL"}]

    async def save_analysis(self, case_id, claim_token, facts, output):
        self.saved = output
        self.saved_outputs.append((case_id, claim_token, output))
        self.saved_facts.append(facts)
        return {"caseId": case_id, "status": "AWAITING_APPROVAL", "evidence": ["Core 확인 사실"]}

    async def claim(self, case_id):
        self.claim_count += 1
        return claimed_case(token=f"claim-{self.claim_count}", slack_message_ts="1700000000.000200")

    async def record_slack_message(self, case_id, message_ts):
        return {"caseId": case_id, "slackMessageTs": message_ts}

    async def decide(self, case_id, decision, user_id, reason=None):
        self.decisions.append((case_id, decision, user_id, reason))
        return {"caseId": case_id, "decision": decision}

    async def fail_analysis(self, case_id, claim_token, reason):
        raise AssertionError("deterministic analysis should not fail")


class FakeNotifier:
    def __init__(self):
        self.calls = []

    async def send(self, case, output, evidence, update=False):
        self.calls.append((case.caseId, case.analysisClaimToken, update))
        return "1700000000.000100"


def claimed_case(token="claim", slack_message_ts=None):
    return ClaimedCase(
        caseId=1, dispatchId=11, executionId=42, status="ANALYZING",
        failureCategory="INVALID_TARGET", failureReason="Coupon not found",
        analysisClaimToken=token, analysisAttemptCount=1,
        slackMessageTs=slack_message_ts,
    )


def test_only_declared_read_only_tools_are_exposed():
    tools = build_read_only_tools(FakeCore())
    assert {tool.name for tool in tools} == {
        "get_dispatch_context",
        "get_action_failure_history",
        "get_execution_dispatch_history",
    }


def test_invalid_target_uses_deterministic_route_without_llm():
    core = FakeCore()
    notifier = FakeNotifier()
    runtime = TriageRuntime(Settings(groq_api_key=""), core, notifier, MemorySaver())

    asyncio.run(runtime.process(claimed_case()))

    assert core.saved.recommendation == "NO_RETRY"
    assert core.saved.confidence == 1.0
    assert notifier.calls == [(1, "claim", False)]


def test_reanalysis_resumes_the_existing_thread_checkpoint_and_updates_message():
    async def scenario():
        core = FakeCore()
        notifier = FakeNotifier()
        runtime = TriageRuntime(Settings(groq_api_key=""), core, notifier, MemorySaver())
        config = {"configurable": {"thread_id": "1"}}

        await runtime.process(claimed_case())
        checkpoint = await runtime.graph.aget_state(config)
        assert checkpoint.next == ("await_operator",)

        await runtime.reanalyze(1, "쿠폰 ID가 최근에 변경됐는지 다시 확인해 주세요.")
        resumed_checkpoint = await runtime.graph.aget_state(config)
        assert resumed_checkpoint.next == ("await_operator",)
        assert core.saved_outputs == [
            (1, "claim", core.saved_outputs[0][2]),
            (1, "claim-1", core.saved_outputs[1][2]),
        ]
        assert notifier.calls == [
            (1, "claim", False),
            (1, "claim-1", True),
        ]
        assert core.saved_facts[1]["operatorFeedback"] == {
            "source": "operator",
            "content": "쿠폰 ID가 최근에 변경됐는지 다시 확인해 주세요.",
        }

    asyncio.run(scenario())


def test_approve_and_close_resume_the_interrupt_to_end_on_the_same_thread():
    async def scenario(decision):
        core = FakeCore()
        runtime = TriageRuntime(Settings(groq_api_key=""), core, FakeNotifier(), MemorySaver())
        config = {"configurable": {"thread_id": "1"}}

        await runtime.process(claimed_case())
        assert (await runtime.graph.aget_state(config)).next == ("await_operator",)

        await runtime.decide(1, decision, "U_ADMIN", "operator reason")

        assert (await runtime.graph.aget_state(config)).next == ()
        assert core.decisions == [(1, decision, "U_ADMIN", "operator reason")]

    asyncio.run(scenario("APPROVE"))
    asyncio.run(scenario("CLOSE"))


def test_non_deterministic_triage_uses_groq_openai_compatible_client(monkeypatch):
    captured = {"kwargs": []}

    class FakeModel:
        def __init__(self, structured=False):
            self.structured = structured

        def bind_tools(self, tools):
            captured["tools"] = {tool.name for tool in tools}
            return self

        def with_structured_output(self, schema, method):
            captured["schema"] = schema
            captured["method"] = method
            return FakeModel(structured=True)

        async def ainvoke(self, messages):
            if self.structured:
                return {
                    "recommendation": "MANUAL_INVESTIGATION",
                    "confidence": 0.7,
                    "summary": "외부 전송 실패를 확인해야 합니다.",
                    "evidence_refs": ["CURRENT_DELIVERY_FAILURE"],
                    "operator_next_step": "대상 설정을 확인하세요.",
                }
            return AIMessage(content=(
                "Facts are sufficient for a structured recommendation."
            ))

    def fake_chat_openai(**kwargs):
        captured["kwargs"].append(kwargs)
        return FakeModel()

    monkeypatch.setattr("app.agent.ChatOpenAI", fake_chat_openai)
    core = FakeCore()
    case = claimed_case()
    case.failureCategory = "TRANSIENT_DELIVERY_FAILURE"
    runtime = TriageRuntime(Settings(groq_api_key="test-key"), core, FakeNotifier(), MemorySaver())

    asyncio.run(runtime.process(case))

    assert captured["kwargs"] == [{
        "api_key": "test-key",
        "base_url": "https://api.groq.com/openai/v1",
        "model": "openai/gpt-oss-20b",
        "temperature": 0,
        "timeout": 30,
        "max_retries": 1,
    }] * 2
    assert captured["schema"].__name__ == "AnalysisOutput"
    assert captured["method"] == "json_schema"
    assert captured["tools"] == {
        "get_dispatch_context",
        "get_action_failure_history",
        "get_execution_dispatch_history",
    }


def test_operator_rewrite_is_required_for_english_or_internal_field_names():
    english = {
        "recommendation": "MANUAL_INVESTIGATION",
        "confidence": 0.7,
        "summary": "The dispatchContext failed.",
        "evidence_refs": ["CURRENT_DELIVERY_FAILURE"],
        "operator_next_step": "Check it.",
    }
    korean = {
        "recommendation": "MANUAL_INVESTIGATION",
        "confidence": 0.7,
        "summary": "Webhook 전달 실패 원인을 확인해야 합니다.",
        "evidence_refs": ["RECENT_ACTION_FAILURES"],
        "operator_next_step": "수신 endpoint의 정상 응답을 확인하세요.",
    }

    assert TriageRuntime._needs_operator_rewrite(AnalysisOutput.model_validate(english))
    assert not TriageRuntime._needs_operator_rewrite(AnalysisOutput.model_validate(korean))
    assert TriageRuntime._needs_operator_rewrite(AnalysisOutput.model_validate({
        "recommendation": "MANUAL_INVESTIGATION",
        "confidence": 0.7,
        "summary": "operator가 HTTP 500 응답을 확인했습니다.",
        "evidence_refs": ["CURRENT_DELIVERY_FAILURE"],
        "operator_next_step": "관리자 확인이 필요합니다.",
    }))


def test_legacy_checkpoint_output_is_detected_before_reanalysis():
    assert TriageRuntime._has_legacy_evidence({"output": {"evidence": ["old"]}})
    assert not TriageRuntime._has_legacy_evidence({
        "output": {"evidence_refs": ["CURRENT_DELIVERY_FAILURE"]},
    })


def test_checkpointer_connection_is_reconnected_before_graph_work():
    class Connection:
        def __init__(self):
            self.reconnect = None

        async def ping(self, reconnect):
            self.reconnect = reconnect

    class Checkpointer:
        def __init__(self):
            self.conn = Connection()

    async def scenario():
        runtime = TriageRuntime(Settings(), FakeCore(), FakeNotifier(), MemorySaver())
        checkpointer = Checkpointer()
        runtime.checkpointer = checkpointer
        await runtime._ensure_checkpointer_connection()
        assert checkpointer.conn.reconnect is True

    asyncio.run(scenario())
