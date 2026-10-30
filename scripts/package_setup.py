from pathlib import Path
root=Path(__file__).resolve().parents[1]
text=(root/'backend/Code.gs').read_text(encoding='utf-8')+'\n'+(root/'private/ImportarPadron.gs').read_text(encoding='utf-8')+'\nfunction instalarComedor() { setup(); importRoster(); createFirstAdmin(); }\n'
(root/'private/InstalarComedor.gs').write_text(text,encoding='utf-8')
print('Single-file installer prepared locally with private roster.')
