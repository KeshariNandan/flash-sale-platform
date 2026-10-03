#!/usr/bin/env python3
"""
Build the Woolf project report (.docx) from the official template plus the
chapter content files in docs/report/content/.

Usage:
    .venv/bin/python docs/report/build_report.py \
        --template docs/report/template/woolf_template.docx \
        --out docs/report/out/Flash-Sale-Platform-Project-Report.docx

Content markup (a deliberately small Markdown subset):
    #! Title            unnumbered top-level section (Abstract)
    # Title             numbered section  -> "1. Title" (resets table/figure counters)
    ## Title            subsection (left aligned, bold, 14pt)
    ### Title           sub-subsection (left aligned, bold, 12pt)
    Table: caption      caption ABOVE the table that follows (auto "Table 2.01: ...")
    | a | b |           table rows; first row is the header
    ![caption](path)    figure with caption BELOW (auto "Figure 2.01: ...")
    ```                 fenced code block (Courier New 10, single spaced)
    - item / 1. item    bullet / numbered list
    **bold**, `code`    inline formatting
    PAGEBREAK           manual page break

Formatting applied (Woolf format guidelines):
    Times New Roman; 14pt bold headings, 12pt body; body justified with 1.5
    line spacing; lists/bibliography single spaced; margins 1.25" L/R, 1" T/B.
    Tables/figures numbered <section>.<two-digit sequence>.
"""
from __future__ import annotations

import argparse
import copy
import re
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_COLOR_INDEX
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor

FONT = "Times New Roman"
CODE_FONT = "Courier New"
BODY_PT = 12
HEAD_PT = 14
FILL_RE = re.compile(r"<[^<>]{3,80}>")

TITLE_PLACEHOLDERS = {
    "<Full Name of the Student>": "[TO FILL: Full Name of the Student]",
    "<Month of Submission like June, 2024>": "[TO FILL: Month, Year of Submission]",
    "<Registered Scaler Email ID>": "[TO FILL: Registered Scaler Email ID]",
    "DD/MM/YYYY <Date of Submission>": "[TO FILL: DD/MM/YYYY]",
    "< Project Module start date >": "[TO FILL: module start date]",
    "< Module end date >": "[TO FILL: module end date]",
    "<Full Name of the Candidate>": "[TO FILL: Full Name of the Candidate]",
    "<Signature of the Candidate>": "[TO FILL: Signature]",
    "XX Month 20XX": "[TO FILL: DD Month YYYY]",
}

ACKNOWLEDGMENT = (
    "I would like to thank my family for their patience and encouragement during the "
    "months in which evenings and weekends were given to this programme. I am grateful to "
    "my thesis supervisor, Naman Bhalla, for framing the capstone around real engineering "
    "trade-offs rather than a checklist, and to the Scaler Neovarsity instructors and "
    "teaching assistants whose classes on low-level design, databases, caching, messaging "
    "and cloud deployment shaped the architecture presented here. I also thank my mentors "
    "and peers in the cohort for the design reviews and the honest questions that improved "
    "this work, and my colleagues at work whose production incidents taught me why the "
    "invariants in this project matter. [TO FILL: add any specific names you wish to thank]"
)


# --------------------------------------------------------------------------- #
# low-level docx helpers
# --------------------------------------------------------------------------- #
def set_run_font(run, size=BODY_PT, bold=None, italic=None, name=FONT, color_black=True):
    run.font.name = name
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    for attr in ("w:ascii", "w:hAnsi", "w:cs", "w:eastAsia"):
        rfonts.set(qn(attr), name)
    run.font.size = Pt(size)
    if bold is not None:
        run.font.bold = bold
    if italic is not None:
        run.font.italic = italic
    if color_black:
        run.font.color.rgb = RGBColor(0, 0, 0)


def para_format(p, align=WD_ALIGN_PARAGRAPH.JUSTIFY, spacing=1.5, before=0, after=4, keep_next=False):
    pf = p.paragraph_format
    pf.alignment = align
    pf.line_spacing = spacing
    pf.space_before = Pt(before)
    pf.space_after = Pt(after)
    pf.keep_with_next = keep_next


def add_bookmark(paragraph, name, bm_id):
    start = OxmlElement("w:bookmarkStart")
    start.set(qn("w:id"), str(bm_id))
    start.set(qn("w:name"), name)
    end = OxmlElement("w:bookmarkEnd")
    end.set(qn("w:id"), str(bm_id))
    p = paragraph._p
    # bookmark must wrap the runs: insert after pPr (if any), append end
    ppr = p.find(qn("w:pPr"))
    if ppr is not None:
        ppr.addnext(start)
    else:
        p.insert(0, start)
    p.append(end)


def add_field(paragraph, instr, placeholder="0", size=BODY_PT, bold=False):
    """Insert a complex field (TOC, PAGEREF ...) into the paragraph."""
    def fld(kind):
        r = paragraph.add_run()
        set_run_font(r, size=size, bold=bold)
        fc = OxmlElement("w:fldChar")
        fc.set(qn("w:fldCharType"), kind)
        r._r.append(fc)
        return r

    fld("begin")
    r = paragraph.add_run()
    set_run_font(r, size=size, bold=bold)
    it = OxmlElement("w:instrText")
    it.set(qn("xml:space"), "preserve")
    it.text = f" {instr} "
    r._r.append(it)
    fld("separate")
    t = paragraph.add_run(placeholder)
    set_run_font(t, size=size, bold=bold)
    fld("end")


def add_inline_runs(p, text, size=BODY_PT, bold_all=False):
    """Handle **bold** and `code` inline markup."""
    tokens = re.split(r"(\*\*[^*]+\*\*|`[^`]+`)", text)
    for tok in tokens:
        if not tok:
            continue
        if tok.startswith("**") and tok.endswith("**"):
            r = p.add_run(tok[2:-2])
            set_run_font(r, size=size, bold=True)
        elif tok.startswith("`") and tok.endswith("`"):
            r = p.add_run(tok[1:-1])
            set_run_font(r, size=size - 1, bold=bold_all, name=CODE_FONT)
        else:
            r = p.add_run(tok)
            set_run_font(r, size=size, bold=bold_all)


def set_cell_shading(cell, hex_fill):
    tcpr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), hex_fill)
    tcpr.append(shd)


def set_table_borders(table):
    tbl = table._tbl
    tblpr = tbl.tblPr
    borders = OxmlElement("w:tblBorders")
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        el = OxmlElement(f"w:{edge}")
        el.set(qn("w:val"), "single")
        el.set(qn("w:sz"), "4")
        el.set(qn("w:space"), "0")
        el.set(qn("w:color"), "000000")
        borders.append(el)
    tblpr.append(borders)


TEXT_WIDTH_IN = 6.0   # 8.5in page minus 1.25in margins each side


def set_col_widths(table, widths_in):
    """Fixed layout with explicit column widths (Word ignores widths otherwise)."""
    table.autofit = False
    tblpr = table._tbl.tblPr
    layout = OxmlElement("w:tblLayout")
    layout.set(qn("w:type"), "fixed")
    tblpr.append(layout)
    # compact cell padding (top/bottom 1pt, left/right 4pt)
    mar = OxmlElement("w:tblCellMar")
    for side, val in (("top", 20), ("bottom", 20), ("left", 80), ("right", 80)):
        el = OxmlElement(f"w:{side}")
        el.set(qn("w:w"), str(val))
        el.set(qn("w:type"), "dxa")
        mar.append(el)
    tblpr.append(mar)
    tblw = tblpr.find(qn("w:tblW"))
    if tblw is None:
        tblw = OxmlElement("w:tblW")
        tblpr.append(tblw)
    tblw.set(qn("w:type"), "dxa")
    tblw.set(qn("w:w"), str(int(sum(widths_in) * 1440)))
    grid = table._tbl.tblGrid
    for gc, w in zip(grid.findall(qn("w:gridCol")), widths_in):
        gc.set(qn("w:w"), str(int(w * 1440)))
    for row in table.rows:
        for cell, w in zip(row.cells, widths_in):
            cell.width = Inches(w)


def content_based_widths(rows, ncols, total=TEXT_WIDTH_IN, min_w=0.75):
    """Allocate widths proportional to the longest text in each column,
    dampened so a very long column does not starve the others."""
    import math
    lengths = []
    for j in range(ncols):
        longest = max((len(r[j]) for r in rows if j < len(r)), default=1)
        lengths.append(max(4, min(longest, 160)))
    weights = [math.sqrt(l) for l in lengths]
    raw = [total * w / sum(weights) for w in weights]
    # enforce minimum, then renormalise the remainder
    fixed = [max(min_w, w) for w in raw]
    scale = total / sum(fixed)
    return [round(w * scale, 2) for w in fixed]


def enable_update_fields_on_open(doc):
    settings = doc.settings.element
    if settings.find(qn("w:updateFields")) is None:
        uf = OxmlElement("w:updateFields")
        uf.set(qn("w:val"), "true")
        settings.append(uf)


# --------------------------------------------------------------------------- #
# content model
# --------------------------------------------------------------------------- #
class Builder:
    def __init__(self, doc: Document, content_root: Path):
        self.doc = doc
        self.content_root = content_root
        self.body = doc.element.body
        self.sectPr = self.body.find(qn("w:sectPr"))
        self.section_no = 0
        self.table_no = 0
        self.figure_no = 0
        self.bm_id = 100
        self.tables: list[tuple[str, str, str]] = []   # (number, title, bookmark)
        self.figures: list[tuple[str, str, str]] = []
        self.first_section = True
        self.current_section = ""

    # -- element placement: always before the final sectPr -------------------
    def _append(self, element):
        self.sectPr.addprevious(element)

    def new_paragraph(self, style=None):
        p = self.doc.add_paragraph(style=style)
        self._append(p._p)
        return p

    def new_table(self, rows, cols):
        t = self.doc.add_table(rows=rows, cols=cols)
        self._append(t._tbl)
        return t

    # -- blocks --------------------------------------------------------------
    def page_break(self):
        p = self.new_paragraph()
        p.add_run().add_break(WD_BREAK.PAGE)
        para_format(p, after=0)

    def section_heading(self, title, numbered=True):
        if not self.first_section:
            self.page_break()
        self.first_section = False
        self.current_section = title
        if numbered:
            self.section_no += 1
            self.table_no = 0
            self.figure_no = 0
            text = f"{self.section_no}. {title}"
        else:
            text = title
        p = self.new_paragraph(style="Heading 3")
        r = p.add_run(text)
        set_run_font(r, size=HEAD_PT, bold=True)
        para_format(p, align=WD_ALIGN_PARAGRAPH.LEFT, spacing=1.5, before=0, after=12, keep_next=True)

    def sub_heading(self, title, level):
        p = self.new_paragraph()
        r = p.add_run(title)
        set_run_font(r, size=HEAD_PT if level == 2 else BODY_PT, bold=True)
        para_format(p, align=WD_ALIGN_PARAGRAPH.LEFT, spacing=1.5, before=10 if level == 2 else 6, after=6, keep_next=True)

    def paragraph(self, text):
        p = self.new_paragraph()
        add_inline_runs(p, text)
        para_format(p)

    def list_item(self, text, numbered=False, index=1):
        p = self.new_paragraph()
        bullet = f"{index}. " if numbered else "\u2022 "
        r = p.add_run(bullet)
        set_run_font(r)
        add_inline_runs(p, text)
        # bibliography entries are single spaced (format guideline); other lists follow body spacing
        spacing = 1.0 if self.current_section == "References" else 1.5
        para_format(p, spacing=spacing, after=4 if spacing == 1.0 else 0)
        pf = p.paragraph_format
        pf.left_indent = Inches(0.4)
        pf.first_line_indent = Inches(-0.25)

    def code_block(self, lines):
        for line in lines:
            p = self.new_paragraph()
            r = p.add_run(line if line else " ")
            set_run_font(r, size=10, name=CODE_FONT)
            para_format(p, align=WD_ALIGN_PARAGRAPH.LEFT, spacing=1.0, after=0)
            p.paragraph_format.left_indent = Inches(0.3)
        # trailing gap
        gap = self.new_paragraph()
        para_format(gap, spacing=1.0, after=6)

    def caption(self, kind, number, title):
        p = self.new_paragraph()
        r = p.add_run(f"{kind} {number}: {title}")
        set_run_font(r, bold=True)
        para_format(p, align=WD_ALIGN_PARAGRAPH.CENTER, spacing=1.0, before=4, after=6, keep_next=(kind == "Table"))
        bm = f"{kind.lower()}_{number.replace('.', '_')}"
        self.bm_id += 1
        add_bookmark(p, bm, self.bm_id)
        return bm

    def table(self, title, rows):
        self.table_no += 1
        number = f"{self.section_no}.{self.table_no:02d}"
        bm = self.caption("Table", number, title)
        self.tables.append((number, title, bm))
        ncols = max(len(r) for r in rows)
        t = self.new_table(len(rows), ncols)
        t.alignment = WD_TABLE_ALIGNMENT.CENTER
        set_table_borders(t)
        set_col_widths(t, content_based_widths(rows, ncols))
        for i, row in enumerate(rows):
            for j in range(ncols):
                cell = t.cell(i, j)
                cell.text = ""
                p = cell.paragraphs[0]
                add_inline_runs(p, row[j] if j < len(row) else "", size=11, bold_all=(i == 0))
                para_format(p, align=WD_ALIGN_PARAGRAPH.LEFT, spacing=1.0, after=0)
                if i == 0:
                    set_cell_shading(cell, "D9D9D9")
        gap = self.new_paragraph()
        para_format(gap, spacing=1.0, after=6)

    def figure(self, title, path):
        img = (self.content_root.parent / path).resolve()
        if not img.exists():
            raise FileNotFoundError(img)
        self.figure_no += 1
        number = f"{self.section_no}.{self.figure_no:02d}"
        p = self.new_paragraph()
        run = p.add_run()
        # fit inside the text area: max 5.8in wide, max 5.2in tall (keeps tall
        # diagrams from consuming a whole page)
        from docx.image.image import Image as DocxImage
        meta = DocxImage.from_file(str(img))
        aspect = meta.px_height / meta.px_width
        width = Inches(5.8)
        if Inches(5.8) * aspect > Inches(4.3):
            width = int(Inches(4.3) / aspect)
        run.add_picture(str(img), width=width)
        para_format(p, align=WD_ALIGN_PARAGRAPH.CENTER, spacing=1.0, before=6, after=2, keep_next=True)
        bm = self.caption("Figure", number, title)
        self.figures.append((number, title, bm))

    # -- parser ----------------------------------------------------------------
    def render_file(self, path: Path):
        lines = path.read_text(encoding="utf-8").splitlines()
        i = 0
        pending_table_caption = None
        while i < len(lines):
            line = lines[i].rstrip()
            if not line.strip():
                i += 1
                continue
            if line.startswith("```"):
                block = []
                i += 1
                while i < len(lines) and not lines[i].startswith("```"):
                    block.append(lines[i].rstrip("\n"))
                    i += 1
                i += 1
                self.code_block(block)
                continue
            if line == "PAGEBREAK":
                self.page_break()
                i += 1
                continue
            if line.startswith("#! "):
                self.section_heading(line[3:].strip(), numbered=False)
            elif line.startswith("### "):
                self.sub_heading(line[4:].strip(), level=3)
            elif line.startswith("## "):
                self.sub_heading(line[3:].strip(), level=2)
            elif line.startswith("# "):
                self.section_heading(line[2:].strip(), numbered=True)
            elif line.startswith("Table: "):
                pending_table_caption = line[7:].strip()
            elif line.startswith("|"):
                rows = []
                while i < len(lines) and lines[i].strip().startswith("|"):
                    raw = lines[i].strip()
                    cells = [c.strip() for c in raw.strip("|").split("|")]
                    if not all(re.fullmatch(r":?-{2,}:?", c) for c in cells):
                        rows.append(cells)
                    i += 1
                self.table(pending_table_caption or "[TO FILL: caption]", rows)
                pending_table_caption = None
                continue
            elif line.startswith("!["):
                m = re.match(r"!\[(.*?)\]\((.*?)\)", line)
                self.figure(m.group(1), m.group(2))
            elif re.match(r"^\d+\. ", line):
                while i < len(lines) and re.match(r"^\d+\. ", lines[i]):
                    m = re.match(r"^(\d+)\. (.*)", lines[i])
                    self.list_item(m.group(2).strip(), numbered=True, index=int(m.group(1)))
                    i += 1
                continue
            elif line.startswith("- "):
                while i < len(lines) and lines[i].startswith("- "):
                    self.list_item(lines[i][2:].strip())
                    i += 1
                continue
            else:
                # merge soft-wrapped paragraph lines
                buf = [line.strip()]
                i += 1
                while i < len(lines) and lines[i].strip() and not re.match(
                    r"^(#|- |\d+\. |\||!\[|```|Table: |PAGEBREAK)", lines[i]
                ):
                    buf.append(lines[i].strip())
                    i += 1
                self.paragraph(" ".join(buf))
                continue
            i += 1


# --------------------------------------------------------------------------- #
# template surgery
# --------------------------------------------------------------------------- #
def replace_placeholders(doc):
    filled = []

    def fix_paragraph(p):
        text = p.text
        if not (FILL_RE.search(text) or "XX Month 20XX" in text or "DD/MM/YYYY" in text):
            return
        new = text
        for k, v in TITLE_PLACEHOLDERS.items():
            new = new.replace(k, v)
        new = FILL_RE.sub(lambda m: f"[TO FILL: {m.group(0).strip('<> ')}]", new)
        if new == text:
            return
        # keep first run formatting, drop the rest
        runs = p.runs
        if not runs:
            return
        first = runs[0]
        for r in runs[1:]:
            r._r.getparent().remove(r._r)
        first.text = new
        first.font.highlight_color = WD_COLOR_INDEX.YELLOW
        filled.append(new)

    for p in doc.paragraphs:
        fix_paragraph(p)
    for section in doc.sections:
        for part in (section.footer, section.header, section.first_page_footer, section.first_page_header):
            try:
                for p in part.paragraphs:
                    fix_paragraph(p)
            except Exception:
                pass
    return filled


def fill_acknowledgment(doc):
    for p in doc.paragraphs:
        if p.text.strip().startswith("[TO FILL: Insert a Paragraph within this Page"):
            for r in p.runs[1:]:
                r._r.getparent().remove(r._r)
            p.runs[0].text = ACKNOWLEDGMENT
            p.runs[0].font.bold = False
            p.runs[0].font.highlight_color = None
            set_run_font(p.runs[0])
            para_format(p)
            return True
    return False


def find_paragraph(doc, text, style=None):
    """Find the first paragraph whose stripped text equals `text` (optionally with a given style)."""
    for idx, p in enumerate(doc.paragraphs):
        if p.text.strip() == text and (style is None or p.style.name == style):
            return idx, p
    raise ValueError(f"paragraph not found: {text}")


def remove_between(doc, start_idx, end_idx):
    """Remove paragraphs (start_idx, end_idx) exclusive of both."""
    paras = doc.paragraphs
    for p in paras[start_idx + 1:end_idx]:
        p._p.getparent().remove(p._p)


def prepare_front_matter_headings(doc):
    """The template already contains a real Word TOC (content control) that Word
    regenerates from Heading 2/3 styles; it is kept as-is. Only make the list
    headings bold as the format guidelines require."""
    for title in ("List of Tables", "List of Figures"):
        _, hp = find_paragraph(doc, title, style="Heading 2")
        for r in hp.runs:
            r.font.bold = True


def fill_list_table(doc, old_table, entries, label):
    """Replace the template's list table with a fresh 3-column table in place."""
    new = doc.add_table(rows=1 + len(entries), cols=3)
    old_table._tbl.addnext(new._tbl)
    old_table._tbl.getparent().remove(old_table._tbl)
    new.alignment = WD_TABLE_ALIGNMENT.CENTER
    set_table_borders(new)
    set_col_widths(new, (1.0, 4.0, 1.0))
    widths = (Inches(1.0), Inches(4.0), Inches(1.0))
    for c, txt, w in zip(new.rows[0].cells, (f"{label} No.", "Title", "Page No."), widths):
        c.width = w
        r = c.paragraphs[0].add_run(txt)
        set_run_font(r, bold=True)
        para_format(c.paragraphs[0], align=WD_ALIGN_PARAGRAPH.LEFT, spacing=1.0, after=2)
        set_cell_shading(c, "D9D9D9")
    for i, (number, title, bm) in enumerate(entries, start=1):
        c0, c1, c2 = new.rows[i].cells
        for c, w in zip((c0, c1, c2), widths):
            c.width = w
            para_format(c.paragraphs[0], align=WD_ALIGN_PARAGRAPH.LEFT, spacing=1.0, after=2)
        r = c0.paragraphs[0].add_run(number)
        set_run_font(r)
        r = c1.paragraphs[0].add_run(title)
        set_run_font(r)
        add_field(c2.paragraphs[0], f"PAGEREF {bm} \\h", placeholder="?")


def truncate_body_from(doc, heading_text, style=None):
    idx, p = find_paragraph(doc, heading_text, style=style)
    body = doc.element.body
    start = p._p
    remove = False
    for child in list(body):
        if child is start:
            remove = True
        if remove and child.tag != qn("w:sectPr"):
            body.remove(child)


def apply_margins(doc):
    for s in doc.sections:
        s.left_margin = Inches(1.25)
        s.right_margin = Inches(1.25)
        s.top_margin = Inches(1.0)
        s.bottom_margin = Inches(1.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--template", required=True)
    ap.add_argument("--content", default=str(Path(__file__).parent / "content"))
    ap.add_argument("--out", required=True)
    ap.add_argument(
        "--update-fields-on-open",
        action="store_true",
        help="Ask Word to refresh TOC/page fields when the file is opened (shows a prompt). "
             "Not needed if finalize_in_word.applescript is run afterwards.",
    )
    args = ap.parse_args()

    content_root = Path(args.content)
    doc = Document(args.template)

    apply_margins(doc)
    filled = replace_placeholders(doc)
    fill_acknowledgment(doc)
    prepare_front_matter_headings(doc)

    # body: drop template guidance from "Applied Software Project" onwards
    truncate_body_from(doc, "Applied Software Project", style="Heading 2")

    b = Builder(doc, content_root)
    # chapter container heading kept as in template
    b.page_break()
    b.first_section = True
    hp = b.new_paragraph(style="Heading 2")
    r = hp.add_run("Applied Software Project")
    set_run_font(r, size=HEAD_PT, bold=True)
    para_format(hp, align=WD_ALIGN_PARAGRAPH.CENTER, spacing=1.5, after=12, keep_next=True)

    for f in sorted(content_root.glob("*.md")):
        b.render_file(f)

    # lists of tables / figures (template tables 0 and 1)
    list_tables = doc.tables[:2]   # capture before replacement shifts indices
    fill_list_table(doc, list_tables[0], b.tables, "Table")
    fill_list_table(doc, list_tables[1], b.figures, "Figure")

    if args.update_fields_on_open:
        enable_update_fields_on_open(doc)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    doc.save(out)

    words = sum(len(p.text.split()) for p in doc.paragraphs)
    print(f"saved {out}")
    print(f"sections={b.section_no} tables={len(b.tables)} figures={len(b.figures)} body_words~{words}")
    print("placeholders left to fill:")
    for f in filled:
        print("  -", f)


if __name__ == "__main__":
    main()
