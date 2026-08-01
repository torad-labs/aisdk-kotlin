import sys
from pathlib import Path


def main() -> int:
    target = Path(sys.argv[-1])
    # Writes the tree with no --check mode, so the gate mutates before it reports.
    target.write_text(target.read_text().replace("a", "b"), encoding="utf-8")
    return 0
