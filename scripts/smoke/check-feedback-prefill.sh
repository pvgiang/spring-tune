#!/usr/bin/env bash
# Checks a feedbackUrl produced by GET /actuator/tuning/report against the public recommendation-feedback issue form:
#   - template= names the form file (3-recommendation-feedback.yml),
#   - every query key other than template and title is a field id of that form,
#   - the URL is no longer than the limit the starter uses (TuningReport.MAX_URL_LENGTH).
# It does not check that GitHub renders the prefill: anonymous requests to issues/new get a 302 to the login page,
# so opening the URL in a logged-in browser stays a human step.
#
# Usage: check-feedback-prefill.sh [--form PATH] [FEEDBACK_URL]
#        (the URL is read from stdin when not given; --form or FEEDBACK_FORM uses a local copy of the form, offline)
# Exit:  0 all checks pass, 1 a check failed, 2 usage error or the form could not be fetched.
set -euo pipefail

FORM_URL="https://raw.githubusercontent.com/pvgiang/spring-tune/main/.github/ISSUE_TEMPLATE/3-recommendation-feedback.yml"
TEMPLATE="3-recommendation-feedback.yml"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
report_java="$here/../../springtune-spring-boot-autoconfigure/src/main/java/io/github/winkytek/springtune/boot/TuningReport.java"

form="${FEEDBACK_FORM:-}"
url=""
while [ $# -gt 0 ]; do
  case "$1" in
    --form) form="${2:?--form needs a path}"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) url="$1"; shift ;;
  esac
done
if [ -z "$url" ]; then
  url="$(cat)"
fi
url="$(printf '%s' "$url" | tr -d '\r\n')"
[ -n "$url" ] || { echo "usage: $0 [--form PATH] FEEDBACK_URL (or the URL on stdin)" >&2; exit 2; }

# The limit the code uses; 8000 if the source is not next to this script.
limit=8000
if [ -f "$report_java" ]; then
  found="$(sed -n 's/.*static final int MAX_URL_LENGTH = \([0-9][0-9]*\);.*/\1/p' "$report_java")"
  [ -n "$found" ] && limit="$found"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
if [ -n "$form" ]; then
  [ -f "$form" ] || { echo "form file not found: $form" >&2; exit 2; }
  cp "$form" "$tmp/form.yml"
  source_label="$form (local)"
else
  if ! curl -fsS --max-time 30 -o "$tmp/form.yml" "$FORM_URL"; then
    echo "could not fetch $FORM_URL; re-run, or pass --form .github/ISSUE_TEMPLATE/$TEMPLATE" >&2
    exit 2
  fi
  source_label="$FORM_URL"
fi

python3 -I - "$url" "$tmp/form.yml" "$TEMPLATE" "$limit" "$source_label" <<'PY'
import re
import sys
from urllib.parse import parse_qsl, urlsplit

url, form_path, template, limit, source = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5]
form = open(form_path, encoding="utf-8").read()
# Field ids of the form's body (the YAML is GitHub's issue-form schema: "id:" occurs only on body fields).
ids = re.findall(r"^\s+id:\s*['\"]?([A-Za-z0-9_-]+)['\"]?\s*$", form, re.M)
failures = []


def check(ok, message):
    print(("ok   " if ok else "FAIL ") + message)
    if not ok:
        failures.append(message)


print("form: " + source)
print("form field ids: " + ", ".join(ids))
check(bool(ids), "the form has field ids")
parts = urlsplit(url)
check(parts.scheme == "https" and parts.netloc == "github.com"
      and parts.path == "/pvgiang/spring-tune/issues/new", "URL is the repository's new-issue page (" + parts.path + ")")
query = parse_qsl(parts.query, keep_blank_values=True, strict_parsing=False)
keys = [k for k, _ in query]
check(len(keys) == len(set(keys)), "no query key repeats (" + ", ".join(keys) + ")")
values = dict(query)
check(values.get("template") == template, "template=" + str(values.get("template")) + " names the form " + template)
check("title" in values and values["title"].startswith("[recommendation] "),
      "title is present and starts with the form's \"[recommendation] \" prefix")
for key in keys:
    if key in ("template", "title"):
        continue
    check(key in ids, "query key " + key + " is a field id of the form")
check(len(url) <= limit, "URL length " + str(len(url)) + " <= " + str(limit) + " (TuningReport.MAX_URL_LENGTH)")
if failures:
    print(str(len(failures)) + " check(s) failed")
    sys.exit(1)
print("all checks passed; that GitHub renders the prefill is a human step (anonymous requests get a 302)")
PY
