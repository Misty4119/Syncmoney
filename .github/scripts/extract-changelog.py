from __future__ import annotations

import re
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: extract-changelog.py <version> <changelog>", file=sys.stderr)
        return 2

    version = sys.argv[1]
    changelog_path = Path(sys.argv[2])
    lines = changelog_path.read_text(encoding="utf-8").splitlines()
    heading = re.compile(rf"^## \[{re.escape(version)}\] - (.+)$")

    start = None
    date = None
    for index, line in enumerate(lines):
        match = heading.match(line)
        if match:
            start = index + 1
            date = match.group(1)
            break

    if start is None or date is None:
        print(f"CHANGELOG section for {version} was not found", file=sys.stderr)
        return 1

    end = len(lines)
    for index in range(start, len(lines)):
        if lines[index].startswith("## ["):
            end = index
            break

    body = "\n".join(lines[start:end]).strip()
    print(f"## Changelog ({date})")
    if body:
        print()
        print(body)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
