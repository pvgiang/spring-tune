#!/usr/bin/env python3
"""Prints one marked code snippet from a Markdown file.

    scripts/smoke/readme-snippet.py README.md NAME

A snippet is the first fenced code block after the line `<!-- snippet:NAME -->`. Exit 1 if the marker is missing,
appears more than once, or is not followed by a fenced block. Used by scripts/smoke/readme-snippets.sh, which builds
the README's install snippets in a fresh project.
"""
import re
import sys


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    path, name = argv[1], argv[2]
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    marker = f"<!-- snippet:{name} -->"
    at = [i for i, line in enumerate(lines) if line.strip() == marker]
    if len(at) != 1:
        print(f"{path}: expected exactly one '{marker}', found {len(at)}", file=sys.stderr)
        return 1
    i = at[0] + 1
    while i < len(lines) and not lines[i].strip():
        i += 1
    fence = re.match(r"^(\s*)(```+|~~~+)", lines[i]) if i < len(lines) else None
    if not fence:
        print(f"{path}: '{marker}' is not followed by a fenced code block", file=sys.stderr)
        return 1
    indent, ticks = fence.group(1), fence.group(2)
    body = []
    for line in lines[i + 1:]:
        if line.strip().startswith(ticks):
            print("\n".join(body))
            return 0
        body.append(line[len(indent):] if line.startswith(indent) else line)
    print(f"{path}: unterminated code block after '{marker}'", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
