import asyncio
import json
from collections import OrderedDict
from contextlib import asynccontextmanager
from typing import Any
from urllib.parse import parse_qs

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from .agent import TriageRuntime, create_checkpointer
from .config import Settings, get_settings
from .core_client import CoreClient
from .slack import extract_interaction, SlackNotifier, verify_slack_signature


class ServiceRuntime:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.core = CoreClient(settings)
        self.notifier = SlackNotifier(settings)
        self.checkpointer = create_checkpointer(settings.langgraph_checkpoint_mysql_url)
        self.triage = TriageRuntime(settings, self.core, self.notifier, self.checkpointer)
        self.stop = asyncio.Event()
        self.worker: asyncio.Task | None = None
        self.background_tasks: set[asyncio.Task] = set()
        self.interaction_keys: OrderedDict[str, None] = OrderedDict()

    def submit_interaction(self, dedupe_key: str | None, work: Any) -> bool:
        if dedupe_key:
            if dedupe_key in self.interaction_keys:
                close = getattr(work, "close", None)
                if close:
                    close()
                return False
        task = asyncio.create_task(work)
        if dedupe_key:
            self.interaction_keys[dedupe_key] = None
            self.interaction_keys.move_to_end(dedupe_key)
            if len(self.interaction_keys) > 1000:
                self.interaction_keys.popitem(last=False)
        self.background_tasks.add(task)
        task.add_done_callback(self.background_tasks.discard)
        task.add_done_callback(self._log_background_failure)
        return True

    @staticmethod
    def _log_background_failure(task: asyncio.Task) -> None:
        error = None if task.cancelled() else task.exception()
        if error:
            # Keep Slack's 3-second acknowledgement independent from LLM/Core latency.
            import logging
            logging.getLogger(__name__).error(
                "Slack background task failed", exc_info=(type(error), error, error.__traceback__)
            )

    async def poll(self) -> None:
        while not self.stop.is_set():
            try:
                case = await self.core.claim()
                if case is not None:
                    await self.triage.process(case)
            except Exception:
                # A failed case is recorded by TriageRuntime; the worker must remain available for the next case.
                pass
            try:
                await asyncio.wait_for(self.stop.wait(), timeout=self.settings.triage_poll_seconds)
            except asyncio.TimeoutError:
                pass

    async def close(self) -> None:
        self.stop.set()
        if self.worker:
            await self.worker
        if self.background_tasks:
            await asyncio.gather(*self.background_tasks, return_exceptions=True)
        await self.core.client.aclose()
        await self.notifier.client.aclose()
        close = getattr(self.checkpointer, "close", None)
        if close:
            close()


def _payload_from_body(raw: bytes, content_type: str) -> dict:
    if "application/json" in content_type:
        return json.loads(raw)
    encoded = parse_qs(raw.decode("utf-8"))
    return json.loads(encoded.get("payload", ["{}"]) [0])


def create_app(settings: Settings | None = None, runtime: ServiceRuntime | None = None) -> FastAPI:
    service_settings = settings or get_settings()
    service_runtime = runtime

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        nonlocal service_runtime
        owns_runtime = service_runtime is None
        if service_runtime is None:
            service_runtime = ServiceRuntime(service_settings)
            service_runtime.worker = asyncio.create_task(service_runtime.poll())
        yield
        if owns_runtime:
            await service_runtime.close()

    application = FastAPI(title="Axon AI Triage Service", lifespan=lifespan)

    @application.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @application.post("/slack/interactions")
    async def slack_interactions(request: Request):
        raw = await request.body()
        if not verify_slack_signature(service_settings, request.headers.get("X-Slack-Request-Timestamp"),
                                      request.headers.get("X-Slack-Signature"), raw):
            raise HTTPException(status_code=401, detail="Invalid Slack signature")
        payload = _payload_from_body(raw, request.headers.get("content-type", ""))
        try:
            interaction = extract_interaction(payload)
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        if interaction.user_id not in service_settings.allowed_user_ids:
            raise HTTPException(status_code=403, detail="Slack user is not allowlisted")

        if interaction.action_id == "approve":
            service_runtime.submit_interaction(
                interaction.dedupe_key,
                service_runtime.triage.decide(interaction.case_id, "APPROVE", interaction.user_id),
            )
            return JSONResponse({"text": "재실행 승인이 Core에 전달되었습니다."})
        if interaction.action_id == "close":
            service_runtime.submit_interaction(
                interaction.dedupe_key,
                service_runtime.triage.decide(interaction.case_id, "CLOSE", interaction.user_id),
            )
            return JSONResponse({"text": "triage case를 종료했습니다."})
        if interaction.action_id == "request_reanalysis":
            if not interaction.trigger_id:
                raise HTTPException(status_code=400, detail="Missing Slack trigger id")
            service_runtime.submit_interaction(
                interaction.dedupe_key,
                service_runtime.notifier.open_reanalysis_modal(interaction.trigger_id, interaction.case_id),
            )
            return JSONResponse({"response_type": "ephemeral", "text": "재분석 사유를 입력해 주세요."})
        if interaction.action_id == "reanalysis_modal":
            if not interaction.feedback:
                return JSONResponse({"text": "재분석 사유를 입력한 뒤 다시 제출해 주세요."}, status_code=400)
            service_runtime.submit_interaction(
                interaction.dedupe_key,
                service_runtime.triage.reanalyze(interaction.case_id, interaction.feedback),
            )
            return JSONResponse({"response_action": "clear"})
        raise HTTPException(status_code=400, detail="Unsupported Slack action")

    return application


app = create_app()
