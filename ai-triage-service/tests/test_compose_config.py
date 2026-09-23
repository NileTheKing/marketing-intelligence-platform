from pathlib import Path


ROOT = Path(__file__).parents[2]


def test_optional_ai_upstream_is_lazy_and_ai_service_has_no_host_port():
    compose = (ROOT / "compose.app.yml").read_text()
    nginx = (ROOT / "infrastructure/nginx/axon.conf").read_text()
    ai_service = compose.split("\n  ai-triage-service:", 1)[1].split("\n  entry-service:", 1)[0]

    assert 'profiles: ["ai"]' in ai_service
    assert "\n    ports:" not in ai_service
    assert "resolver 127.0.0.11" in nginx
    assert "set $ai_triage_upstream ai-triage-service:8000;" in nginx
    assert "proxy_pass http://$ai_triage_upstream;" in nginx
    assert "proxy_pass http://ai-triage-service:8000;" not in nginx
