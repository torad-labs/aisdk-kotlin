from pathlib import Path

_API_DIR = Path(__file__).resolve().parents[3] / "api"


def _is_api_dump(path: Path) -> bool:
    return _API_DIR in path.parents and path.name.endswith(".api")
