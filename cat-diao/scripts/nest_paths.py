"""Private per-user data paths for the portable Cat Diao skill.

The nest directory is chosen by the user and remembered outside AppData, so a
store-sandboxed Python does not hide it. Migration only copies missing files.
It never deletes or overwrites anything already in either place.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
from pathlib import Path


LOCAL_APPDATA = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
REPO_ROOT = Path(__file__).resolve().parents[2]
REPO_DATA = REPO_ROOT / "data"
IN_REPO = (REPO_ROOT / ".git").exists() or REPO_DATA.is_dir()
LEGACY_CONFIG = LOCAL_APPDATA / "手机直达"


def config_file(user_home: Path | None = None) -> Path:
    return Path(user_home or Path.home()) / ".catdiao" / "config.json"


def read_nest_dir(user_home: Path | None = None) -> str:
    path = config_file(user_home)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return ""
    value = data.get("nest_dir") if isinstance(data, dict) else ""
    return value.strip() if isinstance(value, str) else ""


def write_nest_dir(dest: Path, user_home: Path | None = None) -> None:
    path = config_file(user_home)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps({"nest_dir": str(dest)}, ensure_ascii=False, indent=2) + "\n",
                         encoding="utf-8")
    os.replace(temporary, path)


def resolve_home(env: dict | None = None, user_home: Path | None = None,
                 repo_root: Path | None = None, local_appdata: Path | None = None) -> Path:
    env = os.environ if env is None else env
    override = (env.get("CATDIAO_NEST_HOME") or "").strip()
    if override:
        return Path(override).expanduser().resolve()
    configured = read_nest_dir(user_home)
    if configured:
        return Path(configured).expanduser().resolve()
    root = Path(repo_root or REPO_ROOT)
    appdata = Path(local_appdata or LOCAL_APPDATA)
    repo_data = root / "data"
    if (root / ".git").exists() or repo_data.is_dir():
        return repo_data
    return appdata / "猫叼小窝"


def nest_has_user_data(dest: Path) -> bool:
    return ((dest / "connection.json").is_file() or (dest / "collector.env").is_file()
            or (dest / "收藏台账").is_dir())


def copy_missing(source: Path, dest: Path) -> list[str]:
    """Copy files that are not already at dest. Never deletes or overwrites."""
    copied: list[str] = []
    if not source.exists():
        return copied
    dest.mkdir(parents=True, exist_ok=True)
    if source.is_file():
        target = dest / source.name
        if not target.exists():
            shutil.copy2(source, target)
            copied.append(source.name)
        return copied
    for item in source.rglob("*"):
        relative = item.relative_to(source)
        target = dest / relative
        if item.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        if item.is_file() and not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(item, target)
            copied.append(relative.as_posix())
    return copied


def legacy_sources(local_appdata: Path | None = None, repo_root: Path | None = None) -> list[Path]:
    appdata = Path(local_appdata or LOCAL_APPDATA)
    root = Path(repo_root or REPO_ROOT)
    sources = [appdata / "猫叼小窝", appdata / "手机直达", root / "data"]
    packages = appdata / "Packages"
    if packages.is_dir():
        sources.extend(sorted(packages.glob("OpenAI.Codex_*/LocalCache/Local/猫叼小窝")))
    return sources


def migrate_into(dest: Path, sources: list[Path] | None = None, force: bool = False) -> list[str]:
    """Copy missing user data into dest. Existing files on both sides stay untouched."""
    dest = Path(dest).expanduser().resolve()
    if nest_has_user_data(dest) and not force:
        return []
    copied: list[str] = []
    for source in sources if sources is not None else legacy_sources():
        source = Path(source)
        if not source.exists():
            continue
        try:
            if source.resolve() == dest:
                continue
        except OSError:
            continue
        copied.extend(copy_missing(source, dest))
    return copied


def migrate_old_config() -> None:
    """Copy earlier local credentials on first use without embedding them in the skill."""
    HOME.mkdir(parents=True, exist_ok=True)
    for name, target in (("connection.json", pairing_file()),
                         ("collector.env", env_file())):
        old = LEGACY_CONFIG / name
        if old.is_file() and not target.exists():
            shutil.copy2(old, target)


HOME = resolve_home()


def pairing_file() -> Path:
    return HOME / "connection.json"


def env_file() -> Path:
    return HOME / "collector.env"


def ledger_dir() -> Path:
    return HOME / "收藏台账"


def inbox_dir() -> Path:
    return HOME / "手机传来的文件"


def main() -> None:
    parser = argparse.ArgumentParser(description="Show or safely migrate the Cat Diao nest")
    parser.add_argument("--show", action="store_true")
    parser.add_argument("--migrate", help="Copy missing files into this nest; never delete")
    args = parser.parse_args()
    if args.show or not args.migrate:
        print(str(HOME))
        return
    copied = migrate_into(Path(args.migrate))
    print(json.dumps({"nest": str(Path(args.migrate).expanduser().resolve()),
                      "copied": len(copied)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
