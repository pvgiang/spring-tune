#!/usr/bin/env bash
# Moves every install line from SpringTune FROM to TO in one command, and refuses while TO is not on Maven Central.
#
#   scripts/release/sweep-install-version.sh FROM TO [--dry-run] [--skip-central] [ROOT]
#
#   FROM, TO        release numbers (X.Y.Z), TO above FROM. Example: 0.4.1 0.4.2
#   --skip-central  do not ask Maven Central (only for tests on a copy; CI uses it with --dry-run)
#   --dry-run       ROOT is a copy of the repository (required, and not this checkout): the sweep writes there only
#   ROOT            the tree to sweep; default: this repository
#
# Order: (1) Central: springtune-check-cli and springtune-spring-boot-starter TO poms on repo1.maven.org. 200 for
# both: go on. 404: exit 1, "TO is not on Maven Central yet: press Publish". Anything else (429, 5xx, no answer):
# exit 1, "could not check Central (HTTP n), re-run". Never a silent skip. (2) README captures: the README shows CLI
# and build-plugin outputs (docs/readme-runs/); they must be re-captured with TO before the README may say TO
# (docs/readme-runs/check-runs.txt names "SpringTune TO:" and docs/readme-runs/build-plugins-TO.txt exists).
# Missing: exit 1 (a warning with --dry-run). (3) Rewrites, all in ROOT:
#   - install strings in the files scripts/check-install-strings.sh scans, using its templates (so whatever the
#     guard would flag for FROM is moved to TO; prose naming the version of a measurement matches no template);
#   - "the vFROM tag defaults to CLI P" comments next to `version:` inputs -> "the vTO tag defaults to CLI FROM"
#     (for TO below the first stamped release 0.4.3; from 0.4.3 on, tags default to their own CLI);
#   - README and demo README output headers: "SpringTune FROM:", "captured with FROM", "**SpringTune FROM**",
#     "springtune:FROM:check", the release-notes link, "(current release: FROM)";
#   - action.yml's default CLI, through stamp-action-version.sh TO;
#   - .github/workflows/ci.yml: FROM -> TO on lines marked "# sweep: current", the version on lines marked
#     "# sweep: from" set to FROM (the stamped-copy job must run a release other than main's default);
#   - scripts/content/check-claims.sh: the EXPECTED= line only (default version of the claims check);
#   - scripts/check-install-strings.sh: FROM appended to STALE_VERSIONS.
# (4) Every changed line is printed. (5) ROOT's check-install-strings.sh and check-claims.sh must pass; the sweep
# exits 1 otherwise. Review the printed lines before committing: a measurement written as an install string (a
# jar name in a provenance note) is rewritten too, and must then be put back as prose.
#
# Tested by scripts/release/test-sweep.sh (CI job "Release scripts"). Exit 2 on usage errors.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd -P)"
STAMPED_SINCE=0.4.3   # first release whose tag is stamped by release.yml (iteration-7 brief row 2)

usage() { echo "usage: sweep-install-version.sh FROM TO [--dry-run] [--skip-central] [ROOT]" >&2; exit 2; }
dry=false; skip_central=false; pos=()
for a in "$@"; do
  case "$a" in
    --dry-run) dry=true ;;
    --skip-central) skip_central=true ;;
    -*) usage ;;
    *) pos+=("$a") ;;
  esac
done
[ "${#pos[@]}" -ge 2 ] && [ "${#pos[@]}" -le 3 ] || usage
FROM="${pos[0]#v}"; TO="${pos[1]#v}"
ROOT="${pos[2]:-$REPO}"
for v in "$FROM" "$TO"; do [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "sweep: '$v' is not a release number X.Y.Z" >&2; exit 2; }; done
[ "$(printf '%s\n%s\n' "$FROM" "$TO" | sort -V | tail -1)" = "$TO" ] && [ "$FROM" != "$TO" ] \
  || { echo "sweep: TO ($TO) must be above FROM ($FROM)" >&2; exit 2; }
[ -d "$ROOT" ] || { echo "sweep: no such directory: $ROOT" >&2; exit 2; }
ROOT="$(cd "$ROOT" && pwd -P)"
if $dry && [ "${#pos[@]}" -lt 3 ]; then echo "sweep: --dry-run needs ROOT, a copy of the repository" >&2; exit 2; fi
if $dry && [ "$ROOT" = "$REPO" ]; then echo "sweep: --dry-run writes to ROOT, so ROOT must be a copy, not $REPO" >&2; exit 2; fi
for f in README.md action.yml .github/workflows/ci.yml scripts/content/check-claims.sh scripts/check-install-strings.sh \
    scripts/release/stamp-action-version.sh; do
  [ -f "$ROOT/$f" ] || { echo "sweep: $ROOT has no $f" >&2; exit 2; }
done

# (1) Maven Central.
if $skip_central; then
  echo "sweep: --skip-central: Maven Central NOT checked (test mode)"
else
  for artifact in springtune-check-cli springtune-spring-boot-starter; do
    url="https://repo1.maven.org/maven2/io/github/winkytek/springtune/$artifact/$TO/$artifact-$TO.pom"
    code="$(curl -sSI -o /dev/null -w '%{http_code}' --proto '=https' --retry 3 --max-time 60 "$url" 2> /dev/null || true)"
    code="${code:-000}"
    if [ "$code" = 404 ]; then
      echo "sweep: $TO is not on Maven Central yet: press Publish on central.sonatype.com (Publishing > Deployments), then re-run ($url: HTTP 404)" >&2
      exit 1
    elif [ "$code" != 200 ]; then
      echo "sweep: could not check Central (HTTP $code for $url), re-run" >&2
      exit 1
    fi
    echo "sweep: $artifact $TO is on Maven Central (HTTP 200)"
  done
fi

# (2) README captures.
missing=()
grep -qF "SpringTune $TO:" "$ROOT/docs/readme-runs/check-runs.txt" 2> /dev/null \
  || missing+=("docs/readme-runs/check-runs.txt does not name 'SpringTune $TO:' (scripts/readme/capture-check-runs.sh with springtune-cli-$TO.jar)")
[ -f "$ROOT/docs/readme-runs/build-plugins-$TO.txt" ] \
  || missing+=("docs/readme-runs/build-plugins-$TO.txt is missing (scripts/readme/capture-build-plugin-runs.sh $TO)")
if [ "${#missing[@]}" -gt 0 ]; then
  for m in "${missing[@]}"; do echo "sweep: README outputs not re-captured with $TO: $m" >&2; done
  if $dry; then
    echo "sweep: --dry-run: going on; a real sweep refuses until the captures exist" >&2
  else
    echo "sweep: re-capture first (docs/readme-runs/README.md), then re-run; nothing was changed" >&2
    exit 1
  fi
fi

# (3) Rewrites.
mapfile -t files < <(bash "$ROOT/scripts/check-install-strings.sh" --list-files "$ROOT")
mapfile -t templates < <(bash "$ROOT/scripts/check-install-strings.sh" --list-templates)
stamped=false
[ "$(printf '%s\n%s\n' "$TO" "$STAMPED_SINCE" | sort -V | tail -1)" = "$TO" ] && stamped=true

tfile="$(mktemp)"; printf '%s\n' "${templates[@]}" > "$tfile"
trap 'rm -f "$tfile"' EXIT
python3 -I - "$ROOT" "$FROM" "$TO" "$stamped" "$tfile" "${files[@]}" <<'PY'
import re
import sys

root, frm, to, stamped, tfile, *files = sys.argv[1:]
stamped = stamped == "true"
F = re.escape(frm)


def compile_template(t):
    pre, sep, post = t.partition("{V}")
    if not sep:
        return None
    # The templates are POSIX EREs; the ones used here are also valid Python regexes.
    return re.compile(f"(?<![0-9])({pre}){F}({post})" + ("(?![0-9])" if post == "" else ""))


templates = [c for c in (compile_template(t.rstrip("\n")) for t in open(tfile, encoding="utf-8")) if c]
lag = re.compile(rf"\bv{F}(`?(?: tag|'s tag)[^0-9\n]*?)(\d+\.\d+\.\d+)")
captures = [
    (re.compile(rf"SpringTune {F}:"), f"SpringTune {to}:"),
    (re.compile(rf"captured with {F}(?![0-9])"), f"captured with {to}"),
    (re.compile(rf"\*\*SpringTune {F}\*\*"), f"**SpringTune {to}**"),
    (re.compile(rf"\(SpringTune {F},"), f"(SpringTune {to},"),
    (re.compile(rf"springtune:{F}:check"), f"springtune:{to}:check"),
    (re.compile(rf"\[release notes\]\(docs/releases/v{F}\.md\)"), f"[release notes](docs/releases/v{to}.md)"),
    (re.compile(rf"\(current release: {F}\)"), f"(current release: {to})"),
]
link_text = re.compile(rf"\[v{F}( GitHub)? release\]")
notes_on_lag_line = re.compile(rf"releases/v{F}\.md")


def sweep_line(line, path):
    out = line
    if lag.search(out):
        out = lag.sub(lambda m: f"v{to}{m.group(1)}{to if stamped else frm}", out)
        out = notes_on_lag_line.sub(f"releases/v{to}.md", out)
    for rx in templates:
        out = rx.sub(lambda m: f"{m.group(1)}{to}{m.group(2)}", out)
    out = link_text.sub(lambda m: f"[v{to}{m.group(1) or ''} release]", out)
    if path in ("README.md", "examples/broken-config-ci/README.md"):
        for rx, rep in captures:
            out = rx.sub(rep, out)
    return out


def rewrite(path, fn):
    full = f"{root}/{path}"
    with open(full, encoding="utf-8", newline="") as f:
        lines = f.read().splitlines(keepends=True)
    new = [fn(l) for l in lines]
    if new != lines:
        with open(full, "w", encoding="utf-8", newline="") as f:
            f.write("".join(new))
        for i, (a, b) in enumerate(zip(lines, new)):
            if a != b:
                print(f"sweep: {path}:{i + 1}:\n    - {a.strip()}\n    + {b.strip()}")
    return sum(a != b for a, b in zip(lines, new))


total = 0
for path in files:
    if path == "action.yml":
        continue  # stamped below, by the script release.yml uses
    total += rewrite(path, lambda l, p=path: sweep_line(l, p))

# ci.yml: marked lines only.
ver = re.compile(r"(?<![0-9])\d+\.\d+\.\d+(?![0-9])")


def ci_line(line):
    if "# sweep: current" in line:
        return re.sub(rf"(?<![0-9]){F}(?![0-9])", to, line)
    if "# sweep: from" in line:
        return ver.sub(frm, line)
    return line


total += rewrite(".github/workflows/ci.yml", ci_line)

# check-claims.sh: its default expected version, nothing else.
claims = re.compile(r'^(EXPECTED="\$\{EXPECTED_VERSION:-)[0-9][0-9A-Za-z.+-]*(\}")')
n = rewrite("scripts/content/check-claims.sh", lambda l: claims.sub(lambda m: m.group(1) + to + m.group(2), l))
if n != 1 and f'EXPECTED_VERSION:-{to}}}"' not in open(f"{root}/scripts/content/check-claims.sh").read():
    sys.exit("sweep: scripts/content/check-claims.sh has no EXPECTED=\"${EXPECTED_VERSION:-X.Y.Z}\" line")
total += n


# check-install-strings.sh: FROM becomes stale.
def stale_line(line):
    m = re.match(r"^STALE_VERSIONS=\((.*)\)(\s*)$", line)
    if m:
        vs = m.group(1).split()
        if frm not in vs:
            vs.append(frm)
        return f"STALE_VERSIONS=({' '.join(vs)}){m.group(2)}"
    return re.sub(rf"^(# Releases whose install strings are stale\. )\S+( is current\.)", rf"\g<1>{to}\2", line)


total += rewrite("scripts/check-install-strings.sh", stale_line)
if f" {frm}" not in [l for l in open(f"{root}/scripts/check-install-strings.sh") if l.startswith("STALE_VERSIONS=(")][0]:
    sys.exit("sweep: could not add FROM to STALE_VERSIONS in scripts/check-install-strings.sh")
print(f"sweep: {total} line(s) rewritten outside action.yml")
PY

bash "$ROOT/scripts/release/stamp-action-version.sh" "$TO" "$ROOT/action.yml"

# (5) The guards on the swept tree.
echo "sweep: checking the result"
rc=0
bash "$ROOT/scripts/check-install-strings.sh" "$ROOT" || rc=1
env -u EXPECTED_VERSION bash "$ROOT/scripts/content/check-claims.sh" || rc=1
if [ "$rc" -ne 0 ]; then
  echo "sweep: the swept tree fails the guards above; fix by hand (the rewrites are printed above)" >&2
  exit 1
fi
$stamped || echo "sweep: note: tags before v$STAMPED_SINCE default to the previous CLI; snippets keep passing version: \"$TO\""
echo "sweep: $FROM -> $TO done in $ROOT$($dry && echo ' (dry run on a copy)'). Review the rewritten lines above before committing."
