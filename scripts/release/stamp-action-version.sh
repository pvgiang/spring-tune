#!/usr/bin/env bash
# Sets the GitHub Action's default CLI version, so a tree that carries this action.yml runs CLI VERSION when a
# workflow passes no `version:` input.
#
#   scripts/release/stamp-action-version.sh VERSION [ACTION_YML]
#
# ACTION_YML defaults to action.yml at the repository root. Changes only the `version` input's `default:` line and,
# if it names a version as "e.g. X.Y.Z", that input's `description:` line. Idempotent: a file that is already
# stamped is left byte-for-byte unchanged (exit 0). Afterwards it checks that the new default is exactly VERSION and
# that no other line of the file changed; otherwise it restores the original and exits 1.
#
# Used by .github/workflows/release.yml (the release commit of a tag, iteration-7 brief row 2), by the
# `action-stamped` job in ci.yml (a stamped copy of the PR's Action), and by sweep-install-version.sh (main's
# default). Tested by scripts/release/test-stamp.sh. Exit 2 on usage errors.
set -euo pipefail

usage() { echo "usage: stamp-action-version.sh VERSION [ACTION_YML]" >&2; exit 2; }
[ "$#" -ge 1 ] && [ "$#" -le 2 ] || usage
VERSION="${1#v}"
FILE="${2:-$(cd "$(dirname "$0")/../.." && pwd)/action.yml}"
# The same shape action.yml accepts at run time: a release number, optionally with a pre-release suffix.
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "stamp: invalid version '$1'" >&2; exit 2; }
[ -f "$FILE" ] || { echo "stamp: no such file: $FILE" >&2; exit 2; }

python3 -I - "$FILE" "$VERSION" <<'PY'
import re
import sys

path, version = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8", newline="") as f:
    original = f.read()
lines = original.splitlines(keepends=True)


def fail(msg):
    print(f"stamp: {path}: {msg}", file=sys.stderr)
    sys.exit(1)


# The `version:` input: a two-space-indented key under the top-level `inputs:` block.
in_inputs, start = False, None
for i, line in enumerate(lines):
    if re.match(r"^inputs:\s*$", line):
        in_inputs = True
    elif in_inputs and re.match(r"^\S", line):
        break
    elif in_inputs and re.match(r"^  version:\s*$", line):
        start = i
        break
if start is None:
    fail("no `version:` input under `inputs:`")
end = start + 1
while end < len(lines) and (lines[end].startswith("    ") or not lines[end].strip()):
    end += 1

default_re = re.compile(r'^(    default:\s*)(["\']?)([^"\'\s#]*)(\2)(\s*(#.*)?)$')
desc_re = re.compile(r"(e\.g\. )\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?")
new = list(lines)
default_idx, desc_idx = None, None
for i in range(start + 1, end):
    body = lines[i].rstrip("\r\n")
    eol = lines[i][len(body):]
    m = default_re.match(body)
    if m:
        if default_idx is not None:
            fail("the `version` input has two `default:` lines")
        default_idx = i
        quote = m.group(2) or '"'
        new[i] = f"{m.group(1)}{quote}{version}{quote}{m.group(5)}{eol}"
    elif body.startswith("    description:"):
        desc_idx = i
        new[i] = desc_re.sub(lambda d: d.group(1) + version, body, count=1) + eol
if default_idx is None:
    fail("the `version` input has no `default:` line")

stamped = "".join(new)
if stamped == original:
    print(f"stamp: {path} already defaults to CLI {version} (unchanged)")
    sys.exit(0)

# Verify the result before writing: same number of lines, only the default (and description) line differ, and the
# default reads back as exactly VERSION.
after = stamped.splitlines(keepends=True)
changed = [i for i, (a, b) in enumerate(zip(lines, after)) if a != b]
allowed = {default_idx} | ({desc_idx} if desc_idx is not None else set())
if len(after) != len(lines) or not set(changed) <= allowed:
    fail(f"refusing: lines {[c + 1 for c in changed]} would change, only {sorted(a + 1 for a in allowed)} may")
readback = default_re.match(after[default_idx].rstrip("\r\n"))
if not readback or readback.group(3) != version:
    fail(f"refusing: the new default line reads {after[default_idx].strip()!r}, not {version}")

with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(stamped)
for i in changed:
    print(f"stamp: {path}:{i + 1}: {lines[i].strip()}  ->  {after[i].strip()}")
PY

# Independent read-back with the same awk the install-strings guard uses for main's default.
got="$(awk '/^inputs:/ {i=1; next} i && /^[^ ]/ {i=0} i && /^  version:/ {v=1; next} v && /^  [^ ]/ {v=0} v && /^    default:/ {gsub(/[" '"'"']|default:/, ""); print; exit}' "$FILE")"
if [ "$got" != "$VERSION" ]; then
  echo "stamp: $FILE: the version input's default reads '$got' after stamping, expected '$VERSION'" >&2
  exit 1
fi
echo "stamp: $FILE defaults to CLI $VERSION"
