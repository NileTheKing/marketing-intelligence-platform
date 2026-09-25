import json
import re
from typing import Any, TypedDict

from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import StructuredTool
from langchain_openai import ChatOpenAI
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt

from .config import Settings
from .core_client import CoreClient
from .schemas import AnalysisOutput, ClaimedCase


class GraphState(TypedDict, total=False):
    case: dict[str, Any]
    feedback: str | None
    facts: dict[str, Any]
    output: dict[str, Any]
    saved: dict[str, Any]
    reanalysis: bool


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)


def build_read_only_tools(client: CoreClient) -> list[StructuredTool]:
    async def get_dispatch_context(dispatch_id: int) -> dict[str, Any]:
        return await client.get_dispatch_context(dispatch_id)

    async def get_action_failure_history(action_id: int, days: int = 30) -> dict[str, Any]:
        return await client.get_action_failure_history(action_id, min(days, 30))

    async def get_execution_dispatch_history(execution_id: int) -> list[dict[str, Any]]:
        return await client.get_execution_dispatch_history(execution_id)

    return [
        StructuredTool.from_function(
            coroutine=get_dispatch_context,
            name="get_dispatch_context",
            description="Read the bounded Core context for one failed dispatch.",
        ),
        StructuredTool.from_function(
            coroutine=get_action_failure_history,
            name="get_action_failure_history",
            description="Read up to 30 days of failure counts for one marketing action.",
        ),
        StructuredTool.from_function(
            coroutine=get_execution_dispatch_history,
            name="get_execution_dispatch_history",
            description="Read the bounded dispatch history for one execution.",
        ),
    ]


class TriageRuntime:
    def __init__(self, settings: Settings, core: CoreClient, notifier: Any, checkpointer: Any):
        self.settings = settings
        self.core = core
        self.notifier = notifier
        self.checkpointer = checkpointer
        self.graph = self._build_graph(checkpointer)

    def _build_graph(self, checkpointer: Any):
        tools = build_read_only_tools(self.core)
        tool_map = {tool.name: tool for tool in tools}
        workflow = StateGraph(GraphState)

        async def load_context(state: GraphState) -> GraphState:
            case = ClaimedCase.model_validate(state["case"])
            context = await self.core.get_dispatch_context(case.dispatchId)
            history = await self.core.get_action_failure_history(context["actionId"], 30)
            dispatch_history = await self.core.get_execution_dispatch_history(case.executionId)
            facts: dict[str, Any] = {
                "dispatchContext": context,
                "actionFailureHistory": history,
                "executionDispatchHistory": dispatch_history,
            }
            if state.get("feedback"):
                facts["operatorFeedback"] = {
                    "source": "operator",
                    "content": state["feedback"],
                }
            return {"facts": facts}

        def route(state: GraphState) -> str:
            return "deterministic" if state["case"]["failureCategory"] == "INVALID_TARGET" else "llm"

        async def deterministic(state: GraphState) -> GraphState:
            return {"output": AnalysisOutput(
                recommendation="NO_RETRY",
                confidence=1.0,
                summary="대상 또는 쿠폰 설정 오류로 분류되어 같은 발송을 다시 실행해도 성공 가능성이 낮습니다.",
                evidence_refs=["CURRENT_DELIVERY_FAILURE"],
                operator_next_step="대상 또는 쿠폰 설정을 수정하고 필요하면 새 액션을 생성하세요.",
            ).model_dump()}

        async def llm(state: GraphState) -> GraphState:
            if not self.settings.groq_api_key:
                raise RuntimeError("GROQ_API_KEY is not configured")
            model_kwargs = {
                "api_key": self.settings.groq_api_key,
                "base_url": self.settings.groq_base_url,
                "model": self.settings.groq_model,
                "temperature": 0,
                "timeout": 30,
                "max_retries": 1,
            }
            tool_model = ChatOpenAI(**model_kwargs).bind_tools(tools)
            output_model = ChatOpenAI(**model_kwargs).with_structured_output(
                AnalysisOutput, method="json_schema"
            )
            system = ("You are the Axon DLQ failure triage agent. Use only the three provided read-only "
                       "Core tools and any operatorFeedback explicitly supplied in the facts. Do not invent IDs, "
                       "counts, or facts. Write every operator-facing field in clear Korean. Technical terms such "
                       "as Webhook, HTTP, DLT, and status codes may remain in English. "
                       "actionFailureHistory.totalFailures is an observed failure count, not a retry threshold. "
                       "dispatchContext.thresholdCount is a marketing-rule trigger condition and is never evidence "
                       "for a delivery failure or retry decision. One observed transient failure does not establish "
                       "a recurring incident. If the failure is TRANSIENT_DELIVERY, recommend manual investigation "
                       "until an operator explicitly confirms recovery. If operatorFeedback explicitly confirms that "
                       "the target recovered, RETRY_RECOMMENDED is allowed but never automatic. Never expose JSON "
                       "field names such as dispatchContext, actionFailureHistory, thresholdCount, byCategory, or "
                       "failureReason to the operator. Return only JSON matching this schema: "
                       '{"recommendation":"RETRY_RECOMMENDED|MANUAL_INVESTIGATION|NO_RETRY",'
                       '"confidence":0.0,"summary":"2-4 sentences",'
                       '"evidence_refs":["CURRENT_DELIVERY_FAILURE|RECENT_ACTION_FAILURES|EXECUTION_DISPATCH_HISTORY|OPERATOR_CONFIRMED_RECOVERY"],'
                       '"operator_next_step":"one or two checks"}. '
                       "evidence_refs are codes, not operator-facing sentences. Select only codes supported by the facts. "
                       "Select OPERATOR_CONFIRMED_RECOVERY only when operatorFeedback.source is operator and it explicitly confirms recovery. "
                       "Do not put numeric facts, HTTP status codes, identifiers, or internal field names in summary or operator_next_step; Core renders verified facts separately. "
                       "Use 관리자, never the English word operator. "
                       "Never approve, retry, or change infrastructure.")
            case = state["case"]
            prompt = (f"Triage case: {_json(case)}\nFacts already loaded: {_json(state['facts'])}\n"
                      f"Operator feedback for re-analysis: {state.get('feedback') or 'none'}")
            messages: list[Any] = [SystemMessage(content=system), HumanMessage(content=prompt)]
            for _ in range(3):
                response = await tool_model.ainvoke(messages)
                messages.append(response)
                calls = getattr(response, "tool_calls", None) or []
                if not calls:
                    break
                for call in calls:
                    name = call["name"]
                    if name not in tool_map:
                        raise RuntimeError(f"Unsupported tool requested: {name}")
                    result = await tool_map[name].ainvoke(call.get("args", {}))
                    messages.append(ToolMessage(content=_json(result), tool_call_id=call["id"]))
            else:
                raise RuntimeError("LLM exceeded the triage tool-call limit")

            # Tool calls are optional because bounded Core facts are loaded first. Always use
            # schema-constrained output for the operator-facing recommendation.
            output = AnalysisOutput.model_validate(await output_model.ainvoke(messages + [HumanMessage(content=(
                "Using only the supplied Core facts and tool results, return the final triage decision."
            ))]))
            if self._needs_operator_rewrite(output):
                output = AnalysisOutput.model_validate(await output_model.ainvoke([
                    SystemMessage(content=(
                        "Rewrite this triage result for a Korean operator. Preserve only supported facts and the "
                        "recommendation. Do not expose JSON field names, English word operator, IDs, counts, or "
                        "HTTP status codes. Do not call a single failure a recurring issue."
                    )),
                    HumanMessage(content=(
                        f"Facts: {_json(state['facts'])}\nDraft: {_json(output.model_dump())}"
                    )),
                ]))
            if self._needs_operator_rewrite(output):
                raise ValueError("Operator output exposes internal, English, or numeric facts")
            return {"output": output.model_dump()}

        async def save(state: GraphState) -> GraphState:
            output = AnalysisOutput.model_validate(state["output"])
            case = ClaimedCase.model_validate(state["case"])
            saved = await self.core.save_analysis(case.caseId, case.analysisClaimToken,
                                                   state["facts"], output)
            return {"saved": saved}

        async def notify(state: GraphState) -> GraphState:
            case = ClaimedCase.model_validate(state["case"])
            saved = state["saved"]
            message_ts = await self.notifier.send(
                case,
                AnalysisOutput.model_validate(state["output"]),
                saved.get("evidence") or [],
                update=state.get("reanalysis", False),
            )
            if message_ts:
                await self.core.record_slack_message(case.caseId, message_ts)
            return {}

        def await_operator(state: GraphState) -> GraphState:
            resume = interrupt({
                "caseId": state["case"]["caseId"],
                "threadId": str(state["case"]["caseId"]),
                "status": "AWAITING_APPROVAL",
            })
            if isinstance(resume, dict) and resume.get("action") == "reanalyze":
                return {
                    "case": resume["case"],
                    "feedback": resume.get("feedback"),
                    "reanalysis": True,
                }
            return {"reanalysis": False}

        def operator_route(state: GraphState) -> str:
            return "reanalyze" if state.get("reanalysis") else "end"

        workflow.add_node("load_context", load_context)
        workflow.add_node("deterministic", deterministic)
        workflow.add_node("llm", llm)
        workflow.add_node("save", save)
        workflow.add_node("notify", notify)
        workflow.add_node("await_operator", await_operator)
        workflow.add_edge(START, "load_context")
        workflow.add_conditional_edges("load_context", route)
        workflow.add_edge("deterministic", "save")
        workflow.add_edge("llm", "save")
        workflow.add_edge("save", "notify")
        workflow.add_edge("notify", "await_operator")
        workflow.add_conditional_edges("await_operator", operator_route,
                                       {"reanalyze": "load_context", "end": END})
        return workflow.compile(checkpointer=checkpointer)

    @staticmethod
    def _needs_operator_rewrite(output: AnalysisOutput) -> bool:
        text = " ".join([output.summary, output.operator_next_step])
        internal_names = (
            "dispatchContext", "actionFailureHistory", "thresholdCount", "byCategory",
            "failureReason", "operatorGuidance", "totalFailures",
        )
        return (not re.search(r"[가-힣]", text)
                or bool(re.search(r"\boperator\b|\d", text, flags=re.IGNORECASE))
                or any(name in text for name in internal_names))

    @staticmethod
    def _has_legacy_evidence(checkpoint_values: dict[str, Any]) -> bool:
        output = checkpoint_values.get("output")
        return isinstance(output, dict) and "evidence" in output and "evidence_refs" not in output

    async def _invoke(self, command: Any, case: ClaimedCase) -> None:
        config = {"configurable": {"thread_id": str(case.caseId)}}
        try:
            await self._ensure_checkpointer_connection()
            await self.graph.ainvoke(command, config)
        except Exception as error:
            try:
                await self.core.fail_analysis(case.caseId, case.analysisClaimToken, str(error))
            except Exception:
                # If Core already accepted the analysis but Slack failed, keep the
                # awaiting-approval result and do not overwrite it as a failure.
                pass
            raise

    async def _ensure_checkpointer_connection(self) -> None:
        connection = getattr(self.checkpointer, "conn", None)
        ping = getattr(connection, "ping", None)
        if ping:
            await ping(reconnect=True)

    async def process(self, case: ClaimedCase) -> None:
        state: GraphState = {
            "case": case.model_dump(by_alias=True),
            "reanalysis": False,
        }
        await self._invoke(state, case)

    async def reanalyze(self, case_id: int, feedback: str) -> None:
        case = await self.core.claim(case_id)
        if case is None:
            raise RuntimeError("Triage case is not available for re-analysis")
        config = {"configurable": {"thread_id": str(case.caseId)}}
        try:
            await self._ensure_checkpointer_connection()
            checkpoint = await self.graph.aget_state(config)
        except Exception as error:
            await self.core.fail_analysis(case.caseId, case.analysisClaimToken, str(error))
            raise
        if self._has_legacy_evidence(checkpoint.values):
            # Checkpoints contain only resumable graph state. Core remains the source
            # of truth for the case, so an old output schema can be safely rebuilt.
            await self.checkpointer.adelete_thread(str(case.caseId))
            await self._invoke({
                "case": case.model_dump(by_alias=True),
                "feedback": feedback,
                "reanalysis": True,
            }, case)
            return
        await self._invoke(Command(resume={
            "action": "reanalyze",
            "case": case.model_dump(by_alias=True),
            "feedback": feedback,
        }), case)

    async def decide(self, case_id: int, decision: str, user_id: str,
                     reason: str | None = None) -> dict[str, Any]:
        result = await self.core.decide(case_id, decision, user_id, reason)
        config = {"configurable": {"thread_id": str(case_id)}}
        checkpoint = await self.graph.aget_state(config)
        if checkpoint.next == ("await_operator",):
            await self.graph.ainvoke(Command(resume={"action": decision.lower()}), config)
        return result


async def create_checkpointer(database_url: str):
    try:
        import aiomysql
        from langgraph.checkpoint.mysql.aio import AIOMySQLSaver
    except ImportError as error:  # pragma: no cover - exercised only by a broken deployment
        raise RuntimeError("langgraph-checkpoint-mysql is required") from error
    connection = await aiomysql.connect(
        **AIOMySQLSaver.parse_conn_string(database_url),
        autocommit=True,
    )
    saver = AIOMySQLSaver(conn=connection)
    await saver.setup()
    return saver, connection
