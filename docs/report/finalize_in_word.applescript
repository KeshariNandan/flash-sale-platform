-- Open the generated .docx in Microsoft Word, update the TOC and all fields
-- (page references in the lists of tables/figures), save, and export a PDF.
--
-- Usage: osascript docs/report/finalize_in_word.applescript <docx path> <pdf path>
-- Page count: mdls -name kMDItemNumberOfPages <pdf path>
on run argv
  set docPath to item 1 of argv
  set pdfPath to item 2 of argv
  set docName to do shell script "basename " & quoted form of docPath
  with timeout of 600 seconds
    tell application "Microsoft Word"
      activate
      set display alerts to false
      set d to missing value
      if (exists document docName) then
        set d to document docName
      else
        set d to open file name docPath
        delay 3
      end if
      set tocCount to 0
      repeat with t in (get tables of contents of d)
        update t
        set tocCount to tocCount + 1
      end repeat
      set fieldCount to 0
      repeat with f in (get fields of text object of d)
        try
          update field f
          set fieldCount to fieldCount + 1
        end try
      end repeat
      -- fields inside table cells are not always reached via the text object
      repeat with tb in (get tables of d)
        repeat with f in (get fields of text object of tb)
          try
            update field f
            set fieldCount to fieldCount + 1
          end try
        end repeat
      end repeat
      -- second TOC pass so entries reflect final pagination
      repeat with t in (get tables of contents of d)
        update t
      end repeat
      delay 1
      save d
      save as d file name pdfPath file format format PDF
      close d saving no
      return "toc=" & tocCount & " fields=" & fieldCount
    end tell
  end timeout
end run
