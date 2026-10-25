from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Config:
    root: Path
    sources: dict[str, dict]
    interests: list[dict] = field(default_factory=list)
    ranking: dict = field(default_factory=dict)
    storage: dict = field(default_factory=dict)
    web: dict = field(default_factory=dict)
    llm: dict = field(default_factory=dict)
    push: dict = field(default_factory=dict)

    @property
    def db_path(self) -> Path:
        return self.root / self.storage.get("path", "data/catchup.db")


def load_config(path: str | Path) -> Config:
    path = Path(path).resolve()
    raw = json.loads(path.read_text())
    return Config(root=path.parent, sources=raw.get("sources", {}), interests=raw.get("interests", []),
                  ranking=raw.get("ranking", {}), storage=raw.get("storage", {}), web=raw.get("web", {}),
                  llm=raw.get("llm", {}), push=raw.get("push", {}))


def load_env(path: Path) -> None:
    """把 .env 里的 KEY=VALUE 读进环境变量；已存在的真实环境变量优先。"""
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())
