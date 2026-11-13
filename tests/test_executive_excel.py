import base64
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET
import zipfile
ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT/'desktop'),str(ROOT/'licensing/desktop')]
from executive_excel import executive_workbook,read_source,S,C,NS,HEADERS,write_value,tag
from reports_client import save_workbook

def fixture(count=155,plants=3,hostile=True):
    """Synthetic native-phone schema, with exact IDs and deliberately hostile text."""
    sheet=ET.Element(tag('worksheet'));data=ET.SubElement(sheet,tag('sheetData'))
    head=ET.SubElement(data,tag('row'),r='1')
    for i,label in enumerate(HEADERS):write_value(ET.SubElement(head,tag('c'),r=chr(65+i)+'1'),label)
    meals=('desayuno','almuerzo','cena','dieta');records=[]
    for i in range(count):
        row=[46338+i//12,0.5,f'{i%27:08}', '=HYPERLINK("https://invalid.test","texto")' if i==0 and hostile else 'Trabajador de prueba '+str(i%27+1),meals[i%4],['Planta Norte','Planta Centro','Planta Sur'][i%3] if plants==3 else 'Planta '+str(i%plants+1), 'Encargado de prueba', None if i==0 else 12.5,'CC de prueba',f'ID:{i}','Demostración','POSIBLE DUPLICADO' if i==1 else '', 'Sí' if i==0 else 'No']
        records.append(row);r=ET.SubElement(data,tag('row'),r=str(i+2))
        for col,value in enumerate(row):write_value(ET.SubElement(r,tag('c'),r=chr(65+col)+str(i+2)),value)
    summary=ET.Element(tag('worksheet'));sd=ET.SubElement(summary,tag('sheetData'))
    for n,value in [(3,'FRIGOR · DEMOSTRACIÓN'),(4,'Todas las plantas'),(5,'2026-11')]:
        write_value(ET.SubElement(ET.SubElement(sd,tag('row'),r=str(n)),tag('c'),r='B'+str(n)),value)
    report=dict(ok=True,period='month',month='2026-11',date='2026-11-13',serverTime=int(time.time()*1000),count=count,workers=min(count,27),total=max(0,count-1)*12.5,conflicts=int(count>1),unpriced=int(count>0),byMeal=dict(Counter(r[4] for r in records)))
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml','<Types/>');z.writestr('xl/workbook.xml','<workbook/>')
        z.writestr('xl/worksheets/sheet1.xml',ET.tostring(summary));z.writestr('xl/worksheets/sheet2.xml',ET.tostring(sheet))
    raw=out.getvalue();report.update(file=base64.b64encode(raw).decode(),sha256=hashlib.sha256(raw).hexdigest())
    return raw,report

def cells(root):return {c.get('r'):c for c in root.findall('.//s:sheetData/s:row/s:c',NS)}
def value(c):
    return c.findtext('s:v',namespaces=NS) if c.get('t')!='inlineStr' else ''.join(c.find('s:is',NS).itertext())

class ExecutiveTests(unittest.TestCase):
    def test_month_all_rows_identity_types_totals_and_charts(self):
        raw,report=fixture();result=executive_workbook(raw,report)
        with zipfile.ZipFile(io.BytesIO(result)) as z:
            self.assertIsNone(z.testzip());summary=ET.fromstring(z.read('xl/worksheets/sheet1.xml'));detail=ET.fromstring(z.read('xl/worksheets/sheet2.xml'))
            sc,dc=cells(summary),cells(detail)
            self.assertEqual(value(sc['B7']),'155');self.assertEqual(value(sc['E7']),'27');self.assertEqual(float(value(sc['H7'])),1925)
            self.assertEqual(value(dc['C6']),'00000000');self.assertEqual(dc['C6'].get('t'),'inlineStr')
            self.assertIsNone(dc['D6'].find('s:f',NS));self.assertTrue(value(dc['D6']).startswith('=HYPERLINK'))
            self.assertIsNone(dc['H6'].find('s:v',NS));self.assertEqual(value(dc['M6']),'Sí')
            self.assertIsNotNone(dc['M160']);self.assertEqual(detail.find('s:autoFilter',NS).get('ref'),'A5:M160')
            self.assertEqual(detail.find('s:sheetViews/s:sheetView/s:pane',NS).get('ySplit'),'5')
            self.assertEqual(len(detail.findall('s:conditionalFormatting',NS)),2)
            charts=[n for n in z.namelist() if re_chart(n)];self.assertEqual(len(charts),2)
            for name in charts:
                chart=ET.fromstring(z.read(name));numbers=chart.findall('.//c:numCache/c:pt/c:v',NS)
                self.assertEqual(sum(int(x.text) for x in numbers),155)
            self.assertEqual(z.read('xl/workbook.xml').count(b'Print_Area'),2)
            self.assertNotIn(b'externalLink',result)

    def test_empty_day_has_zero_values_and_valid_ranges(self):
        raw,report=fixture(0);report.update(period='day')
        result=executive_workbook(raw,report)
        with zipfile.ZipFile(io.BytesIO(result)) as z:
            sc=cells(ET.fromstring(z.read('xl/worksheets/sheet1.xml')))
            self.assertEqual(value(sc['B7']),'0');self.assertEqual(value(sc['D29']),'0')
            self.assertEqual(value(sc['H29']),'Sin entregas')

    def test_all_plants_retained_when_chart_shows_top_eight(self):
        raw,report=fixture(25,12);result=executive_workbook(raw,report)
        with zipfile.ZipFile(io.BytesIO(result)) as z:
            sc=cells(ET.fromstring(z.read('xl/worksheets/sheet1.xml')))
            self.assertEqual(sum(int(value(sc['L'+str(n)])) for n in range(29,41)),25)
            chart=ET.fromstring(z.read('xl/drawings/charts/chart2.xml'))
            self.assertEqual(len(chart.findall('.//c:numCache/c:pt',NS)),8)

    def test_mismatched_totals_rejected_without_overwriting_existing_file(self):
        raw,report=fixture()
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'out.xlsx';path.write_bytes(b'previous')
            with self.assertRaisesRegex(ValueError,'no coincide'):save_workbook(path,dict(report,count=154))
            self.assertEqual(path.read_bytes(),b'previous')

    def test_thousands_of_rows_and_last_record_survive(self):
        raw,report=fixture(2100);result=executive_workbook(raw,report)
        with zipfile.ZipFile(io.BytesIO(result)) as z:
            dc=cells(ET.fromstring(z.read('xl/worksheets/sheet2.xml')))
            self.assertEqual(value(dc['J2105']),'ID:2099')

def re_chart(path):return path.startswith('xl/drawings/charts/chart') and path.endswith('.xml')

if __name__=='__main__':unittest.main()
