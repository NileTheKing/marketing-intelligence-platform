"""Check entry-document file links and the shared agent instructions, offline."""

from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[2]
ENTRY_DOCS = (
    "AGENTS.md",
    "CLAUDE.md",
    "docs/README.md",
    "docs/catalog.md",
    "docs/architecture-map.md",
    "scripts/README.md",
)


def main():
    errors = []
    if (ROOT / "AGENTS.md").read_bytes() != (ROOT / "CLAUDE.md").read_bytes():
        errors.append("AGENTS.md and CLAUDE.md differ")

    # These entry documents use inline Markdown links. Ignore fenced examples.
    for name in ENTRY_DOCS:
        path = ROOT / name
        fenced = False
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if line.lstrip().startswith(("```", "~~~")):
                fenced = not fenced
                continue
            if fenced:
                continue
            for target in re.findall(r"\]\(([^\s)]+)\)", line):
                link = urlsplit(target.strip("<>"))
                if link.scheme or link.netloc or not link.path:
                    continue
                destination = path.parent / unquote(link.path)
                if not destination.exists():
                    errors.append(f"{name}:{number}: missing link target: {target}")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Documentation navigation OK ({len(ENTRY_DOCS)} entry documents, agent guides match)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
