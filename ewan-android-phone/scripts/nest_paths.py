"""Private per-user data paths for the portable Cat Diao skill."""

from __future__ import annotations

import os
import shutil
from pathlib import Path


LOCAL_APPDATA = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
OVERRIDE = os.environ.get("CATDIAO_NEST_HOME", "").strip()
# When the skill runs from the cat-diao repository, keep personal data in the
# repository's git-ignored data/ folder instead of an app-private directory.
REPO_ROOT = Path(__file__).resolve().parents[2]
REPO_DATA = REPO_ROOT / "data"
IN_REPO = (REPO_ROOT / ".git").exists() or REPO_DATA.is_dir()
if OVERRIDE:
    HOME = Path(OVERRIDE).expanduser().resolve()
elif IN_REPO:
    HOME = REPO_DATA
else:
    HOME = LOCAL_APPDATA / "猫叼小窝"
LEGACY_CONFIG = LOCAL_APPDATA / "手机直达"


def pairing_file() -> Path:
    return HOME / "connection.json"


def env_file() -> Path:
    return HOME / "collector.env"


def ledger_dir() -> Path:
    return HOME / "收藏台账"


def inbox_dir() -> Path:
    return HOME / "手机传来的文件"


def migrate_old_config() -> None:
    """Copy earlier local credentials on first use without embedding them in the skill."""
    HOME.mkdir(parents=True, exist_ok=True)
    for name, target in (("connection.json", pairing_file()),
                         ("collector.env", env_file())):
        old = LEGACY_CONFIG / name
        if old.is_file() and not target.exists():
            shutil.copy2(old, target)
