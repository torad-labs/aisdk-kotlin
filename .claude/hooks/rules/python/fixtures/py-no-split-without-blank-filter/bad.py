def cmd_add(files: str) -> list[str]:
    # "".split(",") is [""], so an omitted --files persists a list holding one empty string.
    return [f.strip() for f in files.split(",")]
