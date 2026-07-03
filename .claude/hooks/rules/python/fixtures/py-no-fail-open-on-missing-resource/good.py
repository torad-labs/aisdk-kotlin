from pathlib import Path


def _missing_manifest_entries(manifest_path: Path, rules: list[object]) -> list[str]:
    rules_dir = manifest_path.resolve(strict=False).parent / "kotlin"
    if not rules_dir.is_dir():
        raise RuntimeError(f"canonical rules dir not found: {rules_dir}")
    return []


def validate_optional_resource(resource: Path) -> None:
    if not resource.exists():
        raise FileNotFoundError(resource)
    return None
