"""Independent reader validation of a workbook produced on the Android emulator."""
import datetime
import sys
from openpyxl import load_workbook
w=load_workbook(sys.argv[1],data_only=False)
assert w.sheetnames==['Resumen','Entregas']
s=w['Entregas'];r=w['Resumen']
assert s.max_row==156 and s.max_column==13
assert s['A2'].value==datetime.datetime(2026,11,11)
assert s['B2'].value==datetime.time(12,5,9)
assert s['C2'].value=='0012345' and s['C2'].data_type=='s'
assert s['D3'].value.startswith('=HYPERLINK') and s['D3'].data_type=='s'
assert s['H2'].value==12.5 and s['H5'].value is None
assert s.freeze_panes=='A2' and s.auto_filter.ref=='A1:M156'
assert r['B6'].value==155 and r['B7'].value==1 and r['B8'].value==1925
assert sum(c.value or 0 for c in s['H'][1:])==r['B8'].value
assert not any(c.data_type=='f' for row in s for c in row)
print('XLSX verified: 155 rows, numeric dates/times/prices, text IDs, no injected formulas, totals reconcile.')
