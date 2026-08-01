import sys
from pathlib import Path


def main() -> int:
    check_only = "--check" in sys.argv
    target = Path(sys.argv[-1])
    before = target.read_text(encoding="utf-8")
    target.write_text(before.replace("a", "b"), encoding="utf-8")
    if check_only:
        target.write_text(before, encoding="utf-8")
        return 1
    return 0
