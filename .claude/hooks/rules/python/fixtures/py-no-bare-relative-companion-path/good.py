from pathlib import Path

BUDGET_FILE = Path(__file__).resolve().parents[3] / "data-class-budget.json"


def read_budget() -> dict[str, object]:
    return {}
