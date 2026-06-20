"""Result contract for repo-local hook modules."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

ResultKind = Literal["block", "warn", "pass"]


@dataclass(frozen=True)
class HookResult:
    kind: ResultKind
    payload: str
    module_name: str
