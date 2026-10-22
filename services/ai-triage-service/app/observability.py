import json
import logging
import logging.config
import time
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Iterator

from fastapi import FastAPI
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased
from prometheus_client import Counter, Histogram, make_asgi_app

from .config import Settings


_log_context: ContextVar[dict[str, int | str]] = ContextVar("log_context", default={})

TRIAGE_CASES = Counter(
    "axon_triage_cases_total",
    "Triage case lifecycle outcomes.",
    ["outcome"],
)
TRIAGE_TOOL_CALLS = Counter(
    "axon_triage_tool_calls_total",
    "Read-only Core tool calls by tool and outcome.",
    ["tool", "outcome"],
)
TRIAGE_TOOL_DURATION = Histogram(
    "axon_triage_tool_duration_seconds",
    "Read-only Core tool call duration.",
    ["tool"],
)
TRIAGE_LLM_DURATION = Histogram(
    "axon_triage_llm_duration_seconds",
    "LLM request duration by outcome.",
    ["outcome"],
)
TRIAGE_LLM_CALLS = Counter(
    "axon_triage_llm_calls_total",
    "LLM requests by outcome.",
    ["outcome"],
)
TRIAGE_SLACK_OPERATIONS = Counter(
    "axon_triage_slack_operations_total",
    "Slack API operations by operation and outcome.",
    ["operation", "outcome"],
)
TRIAGE_DECISIONS = Counter(
    "axon_triage_decisions_total",
    "Operator decisions accepted by Core.",
    ["decision"],
)


class JsonFormatter(logging.Formatter):
    """Keep searchable, non-sensitive correlation fields in every AI log."""

    def __init__(self, service: str, environment: str):
        super().__init__()
        self.service = service
        self.environment = environment

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "timestamp": self.formatTime(record, "%Y-%m-%dT%H:%M:%S"),
            "service": self.service,
            "environment": self.environment,
            "level": record.levelname.lower(),
            "event": getattr(record, "event", record.name),
            "message": record.getMessage(),
        }
        payload.update(_log_context.get())
        span_context = trace.get_current_span().get_span_context()
        if span_context.is_valid:
            payload["trace_id"] = f"{span_context.trace_id:032x}"
            payload["span_id"] = f"{span_context.span_id:016x}"
        if record.exc_info:
            payload["exception_type"] = record.exc_info[0].__name__
        return json.dumps(payload, ensure_ascii=False, default=str)


@contextmanager
def bind_log_context(**fields: int | str | None) -> Iterator[None]:
    current = _log_context.get()
    merged = {**current, **{key: value for key, value in fields.items() if value is not None}}
    token = _log_context.set(merged)
    try:
        yield
    finally:
        _log_context.reset(token)


def configure_logging(settings: Settings) -> None:
    logging.config.dictConfig({
        "version": 1,
        "disable_existing_loggers": False,
        "formatters": {
            "json": {
                "()": JsonFormatter,
                "service": settings.otel_service_name,
                "environment": settings.axon_observability_env,
            },
        },
        "handlers": {
            "console": {"class": "logging.StreamHandler", "formatter": "json"},
        },
        "root": {"level": "INFO", "handlers": ["console"]},
    })


def configure_observability(application: FastAPI, settings: Settings) -> TracerProvider | None:
    """Enable traces only in the operational Compose overlay, never in unit tests by default."""
    configure_logging(settings)
    application.mount("/metrics", make_asgi_app())
    if not settings.otel_exporter_otlp_endpoint:
        return None

    provider = TracerProvider(
        resource=Resource.create({
            "service.name": settings.otel_service_name,
            "deployment.environment": settings.axon_observability_env,
        }),
        sampler=ParentBased(TraceIdRatioBased(settings.otel_traces_sampler_arg)),
    )
    exporter = OTLPSpanExporter(
        endpoint=f"{settings.otel_exporter_otlp_endpoint.rstrip('/')}/v1/traces"
    )
    provider.add_span_processor(BatchSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    FastAPIInstrumentor.instrument_app(application, tracer_provider=provider)
    HTTPXClientInstrumentor().instrument(tracer_provider=provider)
    return provider


@contextmanager
def triage_span(name: str, **attributes: int | str) -> Iterator[trace.Span]:
    tracer = trace.get_tracer("axon.ai-triage")
    with tracer.start_as_current_span(name) as span:
        for key, value in attributes.items():
            span.set_attribute(key, value)
        try:
            yield span
        except Exception as error:
            span.record_exception(error)
            span.set_status(trace.Status(trace.StatusCode.ERROR, str(error)))
            raise


@contextmanager
def observe_duration(histogram: Histogram, **labels: str) -> Iterator[None]:
    started = time.perf_counter()
    try:
        yield
    finally:
        histogram.labels(**labels).observe(time.perf_counter() - started)
