def cmd_add(files: str) -> list[str]:
    return [f.strip() for f in files.split(",") if f.strip()]
