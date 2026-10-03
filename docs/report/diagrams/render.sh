#!/usr/bin/env bash
# Render every *.mmd in this folder to PNG using mermaid-cli (brew install mermaid-cli).
# Uses the locally installed Google Chrome so no puppeteer download is needed.
set -euo pipefail
cd "$(dirname "$0")"

CFG="$(mktemp)"
cat > "$CFG" <<'JSON'
{"executablePath":"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome","args":["--no-sandbox"]}
JSON

for f in *.mmd; do
  out="${f%.mmd}.png"
  echo "rendering $f -> $out"
  if ! mmdc -p "$CFG" -i "$f" -o "$out" -b white -s 2 -w 1400 >/dev/null 2>"$out.err"; then
    echo "  FAILED: $(grep -m1 -E 'Error|error' "$out.err" || head -1 "$out.err")"
    failed=1
  fi
  rm -f "$out.err"
done
exit "${failed:-0}"
rm -f "$CFG"
