import pytest

from app.schemas import AnalysisOutput


def test_analysis_output_rejects_unknown_recommendation_and_extra_fields():
    with pytest.raises(ValueError):
        AnalysisOutput(
            recommendation="AUTORETRY",
            confidence=0.9,
            summary="bad",
            evidence=["x"],
            operator_next_step="check",
        )

    with pytest.raises(ValueError):
        AnalysisOutput(
            recommendation="NO_RETRY",
            confidence=0.9,
            summary="bad",
            evidence=["x"],
            operator_next_step="check",
            extra="not allowed",
        )
