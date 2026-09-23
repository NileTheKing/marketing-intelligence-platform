from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    core_internal_base_url: str = "http://core-service:8080"
    core_triage_service_token: str = Field(default="", repr=False)
    triage_poll_seconds: float = 5.0
    langgraph_checkpoint_mysql_url: str = Field(
        default="mysql+pymysql://ai_triage:change-me@mysql:3306/axon_ai_triage",
        repr=False,
    )
    groq_api_key: str = Field(default="", repr=False)
    groq_base_url: str = "https://api.groq.com/openai/v1"
    groq_model: str = "openai/gpt-oss-20b"
    slack_bot_token: str = Field(default="", repr=False)
    slack_channel_id: str = ""
    slack_signing_secret: str = Field(default="", repr=False)
    slack_allowed_user_ids: str = ""

    @property
    def allowed_user_ids(self) -> set[str]:
        return {item.strip() for item in self.slack_allowed_user_ids.split(",") if item.strip()}

    model_config = {"env_file": ".env", "extra": "ignore"}


@lru_cache
def get_settings() -> Settings:
    return Settings()
