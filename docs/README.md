# Documentation and report material

## `report/` - Woolf project report pipeline

The project report is generated from Markdown chapters into the official Woolf
template so that text, tables and figures stay in version control and the
formatting rules (Times New Roman, 14/12 pt, 1.5 spacing, 1.25"/1" margins,
`Table n.nn` / `Figure n.nn` numbering) are applied by code rather than by hand.

```
report/
├── template/woolf_template.docx   # official template, untouched
├── content/0?_*.md                # one file per chapter, rendered in order
├── diagrams/*.mmd                 # Mermaid sources; render.sh -> *.png
├── build_report.py                # template + content -> out/*.docx
├── finalize_in_word.applescript   # Word: update TOC/fields, export PDF
└── out/                           # generated .docx / .pdf (git-ignored)
```

Build:

```bash
python3 -m venv .venv && .venv/bin/pip install python-docx
docs/report/diagrams/render.sh                      # needs: brew install mermaid-cli
.venv/bin/python docs/report/build_report.py \
    --template docs/report/template/woolf_template.docx \
    --out docs/report/out/Flash-Sale-Platform-Project-Report.docx
osascript docs/report/finalize_in_word.applescript \
    "$PWD/docs/report/out/Flash-Sale-Platform-Project-Report.docx" \
    "$PWD/docs/report/out/Flash-Sale-Platform-Project-Report.pdf"
```

Items marked `[TO FILL]` in the content are personal details and benchmark
numbers that must come from real runs (see `load-test/`); they are highlighted
in the generated document so none can slip through unnoticed.
