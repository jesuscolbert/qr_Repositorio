"""Populate the reviewed executive layout using only Python's standard library.

Input is the authenticated phone export, never the truncated on-screen preview.
Preserve typed dates, exact identifiers and pending prices. No macros or links.
"""
import base64
from collections import Counter, defaultdict
import datetime as dt
from decimal import Decimal
import io
import math
import re
import xml.etree.ElementTree as ET
import zipfile
from executive_template import DATA

S = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
C = 'http://schemas.openxmlformats.org/drawingml/2006/chart'
ET.register_namespace('x', S)
ET.register_namespace('c', C)
ET.register_namespace('r', 'http://schemas.openxmlformats.org/officeDocument/2006/relationships')
ET.register_namespace('a', 'http://schemas.openxmlformats.org/drawingml/2006/main')
NS = {'s': S, 'c': C}
MEALS = ('desayuno', 'almuerzo', 'cena', 'dieta')
HEADERS = ['Fecha','Hora','Identificador','Trabajador','Comida','Planta','Encargado','Importe (Bs)','Centro de costo','Registro','Origen','Revisión','Tarifa pendiente']

def tag(name): return '{'+S+'}'+name
def xml(node): return ET.tostring(node, encoding='utf-8', xml_declaration=True)

def values(root, shared=()):
    cells = {}
    for c in root.findall('.//s:sheetData/s:row/s:c', NS):
        if c.find(tag('f')) is not None:
            raise ValueError('El archivo de origen contiene fórmulas inesperadas.')
        t, v = c.get('t'), c.findtext(tag('v'))
        if t == 'inlineStr': v = ''.join(c.itertext())
        elif t == 's': v = shared[int(v)]
        elif v is not None and t not in ('str', 'inlineStr'): v = Decimal(v)
        cells[c.get('r')] = v
    return cells

def read_source(raw):
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        if sum(i.file_size for i in z.infolist()) > 128*1024*1024:
            raise ValueError('El reporte es demasiado grande. Selecciona un período menor.')
        shared = []
        if 'xl/sharedStrings.xml' in z.namelist():
            shared = [''.join(e.itertext()) for e in ET.fromstring(z.read('xl/sharedStrings.xml'))]
        context = values(ET.fromstring(z.read('xl/worksheets/sheet1.xml')), shared)
        cells = values(ET.fromstring(z.read('xl/worksheets/sheet2.xml')), shared)
    if [cells.get(chr(65+i)+'1') for i in range(13)] != HEADERS:
        raise ValueError('Formato de reporte incompatible. No se guardó un archivo incompleto.')
    indices = sorted({int(re.search(r'\d+$', ref)[0]) for ref in cells if re.search(r'\d+$', ref)})
    if len(indices)>100001: raise ValueError('Exporta como máximo 100.000 entregas por archivo.')
    records = []
    for n in indices:
        if n==1: continue
        row = [cells.get(chr(65+i)+str(n)) for i in range(13)]
        if all(x is None for x in row): continue
        if (not isinstance(row[0],Decimal) or not isinstance(row[1],Decimal)
            or not row[0].is_finite() or not row[1].is_finite()
            or not isinstance(row[2],str) or not row[2] or row[4] not in MEALS
            or row[12] not in ('Sí','No') or
            (row[12]=='No' and (not isinstance(row[7],Decimal) or not row[7].is_finite()))):
            raise ValueError('Una entrega tiene datos incompletos. No se generó un total parcial.')
        if row[12]=='Sí': row[7]=None
        records.append(row)
    return context, records

def cell(root, ref):
    data = root.find(tag('sheetData')); number=int(re.search(r'\d+$',ref)[0])
    row = next((r for r in data if int(r.get('r'))==number),None)
    if row is None:
        row=ET.SubElement(data,tag('row'),r=str(number),ht='26',customHeight='1')
    found=next((c for c in row if c.get('r')==ref),None)
    return found if found is not None else ET.SubElement(row,tag('c'),r=ref)

def put(root, ref, value, formula=None, style=None):
    c=cell(root,ref)
    if style is not None:c.set('s',style)
    write_value(c,value,formula)

def write_value(c,value,formula=None):
    for old in list(c):c.remove(old)
    c.attrib.pop('t',None)
    if value is None:return
    if formula:
        ET.SubElement(c,tag('f')).text=formula
        ET.SubElement(c,tag('v')).text=str(value)
    elif isinstance(value,(int,float,Decimal)):
        if not math.isfinite(value):raise ValueError('Importe inválido.')
        ET.SubElement(c,tag('v')).text=str(value)
    else:
        c.set('t','inlineStr')
        ET.SubElement(ET.SubElement(c,tag('is')),tag('t'),{'{http://www.w3.org/XML/1998/namespace}space':'preserve'}).text=str(value)

def ordered(root):
    data=root.find(tag('sheetData'))
    data[:]=sorted(data,key=lambda r:int(r.get('r')))
    def col(c):
        n=0
        for ch in re.match('[A-Z]+',c.get('r'))[0]:n=n*26+ord(ch)-64
        return n
    for row in data:row[:]=sorted(row,key=col)

def print_setup(root, landscape=False):
    prop=root.find(tag('sheetPr'))
    if prop is None:prop=ET.Element(tag('sheetPr'));root.insert(0,prop)
    ET.SubElement(prop,tag('pageSetUpPr'),fitToPage='1')
    margins=root.find(tag('pageMargins'))
    if margins is None:margins=ET.SubElement(root,tag('pageMargins'))
    margins.attrib.update(left='.3',right='.3',top='.4',bottom='.4',header='.15',footer='.15')
    setup=ET.Element(tag('pageSetup'),paperSize='1',orientation='landscape' if landscape else 'portrait',fitToWidth='1',fitToHeight='0')
    root.insert(list(root).index(margins)+1,setup)

def chart_data(raw, labels, counts, cat_formula, val_formula, horizontal=False):
    root=ET.fromstring(raw)
    for kind,items,formula in [('str',labels,cat_formula),('num',counts,val_formula)]:
        ref=root.find('.//c:'+kind+'Ref',NS);ref.find('{'+C+'}f').text=formula
        cache=ref.find('{'+C+'}'+kind+'Cache');cache.clear()
        if kind=='num':ET.SubElement(cache,'{'+C+'}formatCode').text='#,##0'
        ET.SubElement(cache,'{'+C+'}ptCount',val=str(len(items)))
        for i,value in enumerate(items):ET.SubElement(ET.SubElement(cache,'{'+C+'}pt',idx=str(i)),'{'+C+'}v').text=str(value)
    for axis in root.findall('.//c:valAx',NS):ET.SubElement(axis.find('{'+C+'}scaling'),'{'+C+'}min',val='0')
    if horizontal:
        root.find('.//c:barDir',NS).set('val','bar')
        root.find('.//c:catAx/c:axPos',NS).set('val','l')
        root.find('.//c:valAx/c:axPos',NS).set('val','b')
    return xml(root)

def executive_workbook(raw, report):
    context, records=read_source(raw)
    count=len(records);workers=len({r[2] for r in records});pending=sum(r[12]=='Sí' for r in records)
    conflicts=sum(bool(r[11]) for r in records);total=sum((r[7] for r in records if r[7] is not None),Decimal(0))
    expected={'count':count,'workers':workers,'unpriced':pending,'conflicts':conflicts}
    if any(report.get(k)!=v for k,v in expected.items()) or abs(Decimal(str(report['total']))-total)>Decimal('.005'):
        raise ValueError('El detalle no coincide con el resumen recibido. Vuelve a actualizar el reporte.')
    meal=Counter(r[4] for r in records)
    if any(report.get('byMeal',{}).get(m,0)!=meal[m] for m in MEALS):
        raise ValueError('El desglose por comida no coincide con las entregas.')
    groups=defaultdict(list)
    for r in records:groups[r[5] or 'Sin planta'].append(r)
    plants=sorted(groups,key=lambda p:(-len(groups[p]),p.casefold()))
    with zipfile.ZipFile(io.BytesIO(base64.b64decode(DATA))) as z:parts={n:z.read(n) for n in z.namelist()}
    summary=ET.fromstring(parts['xl/worksheets/sheet1.xml']);detail=ET.fromstring(parts['xl/worksheets/sheet2.xml'])
    company=context.get('B3','Comedor');plant=context.get('B4','Todas las plantas');period=context.get('B5','')
    stamp=dt.datetime.fromtimestamp(report['serverTime']/1000,dt.timezone(dt.timedelta(hours=-4))).strftime('%d/%m/%Y %H:%M:%S')
    put(summary,'B3',f'{company} · {plant} · {"Día" if report["period"]=="day" else "Mes"}: {period}')
    put(summary,'B4',f'Datos recibidos: {stamp} (Bolivia)')
    end=max(6,count+5)
    put(summary,'B7',count,f'COUNT(Entregas!A6:A{end})');put(summary,'E7',workers)
    put(summary,'H7',total,f'SUM(Entregas!H6:H{end})');put(summary,'K7',conflicts,f'COUNTIF(Entregas!L6:L{end},"?*")')
    put(summary,'B10',f'{pending} entregas sin tarifa. El importe incluye solo tarifas registradas; no representa pagos cobrados. {conflicts} registros por revisar incluidos en los totales.')
    for idx,m in enumerate(MEALS,29):
        entries=[r for r in records if r[4]==m];amount=sum((r[7] for r in entries if r[7] is not None),Decimal(0))
        put(summary,f'C{idx}',meal[m],f'COUNTIF(Entregas!E6:E{end},B{idx})')
        put(summary,f'D{idx}',Decimal(meal[m])/count if count else 0,f'IF($B$7=0,0,C{idx}/$B$7)')
        put(summary,f'E{idx}',amount,f'SUMIF(Entregas!E6:E{end},B{idx},Entregas!H6:H{end})')
        put(summary,f'F{idx}',sum(r[12]=='Sí' for r in entries),f'COUNTIFS(Entregas!E6:E{end},B{idx},Entregas!M6:M{end},"Sí")')
    styles={c:cell(summary,c+'29').get('s') for c in ['H','L','M','N']}
    merges=summary.find(tag('mergeCells'))
    # Make room for every plant; no rows are silently dropped to fit the design.
    for node in list(merges):
        if node.get('ref')=='B38:N39':merges.remove(node)
    put(summary,'B38',None)
    for i in range(max(8,len(plants))):
        n=29+i
        if n>36:ET.SubElement(merges,tag('mergeCell'),ref=f'H{n}:K{n}')
        if i>=len(plants):
            for c in styles:put(summary,c+str(n),'Sin entregas' if i==0 and c=='H' else None,style=styles[c])
            continue
        p=plants[i];rows=groups[p];put(summary,f'H{n}',p,style=styles['H'])
        criterion=f'"="&SUBSTITUTE(SUBSTITUTE(SUBSTITUTE(H{n},"~","~~"),"*","~*"),"?","~?")' if p!='Sin planta' or any(r[5]=='Sin planta' for r in rows) else '""'
        put(summary,f'L{n}',len(rows),f'COUNTIF(Entregas!F6:F{end},{criterion})',styles['L'])
        put(summary,f'M{n}',sum((r[7] for r in rows if r[7] is not None),Decimal(0)),f'SUMIF(Entregas!F6:F{end},{criterion},Entregas!H6:H{end})',styles['M'])
        put(summary,f'N{n}',sum(r[12]=='Sí' for r in rows),f'COUNTIFS(Entregas!F6:F{end},{criterion},Entregas!M6:M{end},"Sí")',styles['N'])
        cell(summary,f'H{n}').set('s',styles['H'])
    footer=max(38,len(plants)+31);ET.SubElement(merges,tag('mergeCell'),ref=f'B{footer}:N{footer+1}')
    put(summary,f'B{footer}','Copia de los registros sincronizados. Pueden faltar entregas de teléfonos sin sincronizar. Trabajadores distintos corresponde al corte exportado.',style=cell(summary,'B35').get('s'))
    merges.set('count',str(len(merges)))
    chart1='xl/drawings/charts/chart1.xml';chart2='xl/drawings/charts/chart2.xml'
    parts[chart1]=chart_data(parts[chart1],[m.title() for m in MEALS],[meal[m] for m in MEALS],"'Resumen'!$B$29:$B$32","'Resumen'!$C$29:$C$32")
    shown=plants[:8] or ['Sin entregas'];last=28+len(shown)
    parts[chart2]=chart_data(parts[chart2],shown,[len(groups[p]) for p in shown],f"'Resumen'!$H$29:$H${last}",f"'Resumen'!$L$29:$L${last}",True)
    if len(plants)>8:parts[chart2]=parts[chart2].replace(b'Raciones por planta',b'8 plantas con m\xc3\xa1s entregas')
    data=detail.find(tag('sheetData'))
    row_styles=[[cell(detail,chr(65+i)+str(n)).get('s') for i in range(13)] for n in [6,7]]
    for row in list(data):
        if int(row.get('r'))>=6:data.remove(row)
    put(detail,'A3',f'Fuente: {company}, registros sincronizados. {plant}. {period}. Datos recibidos: {stamp} (Bolivia).')
    for n,record in enumerate(records,6):
        row=ET.SubElement(data,tag('row'),r=str(n),ht='23',customHeight='1')
        for i,value in enumerate(record):
            c=ET.SubElement(row,tag('c'),r=chr(65+i)+str(n),s=row_styles[(n-6)%2][i])
            write_value(c,value)
    if not records:ET.SubElement(data,tag('row'),r='6')
    filt=ET.Element(tag('autoFilter'),ref=f'A5:M{end}');detail.insert(list(detail).index(detail.find(tag('mergeCells'))),filt)
    for c in detail.findall(tag('conditionalFormatting')):
        col=c.get('sqref')[0];c.set('sqref',f'{col}6:{col}{end}')
    for root,area in [(summary,f'A1:N{footer+1}'),(detail,f'A1:M{end}')]:
        dimension=root.find(tag('dimension'))
        if dimension is not None:dimension.set('ref',area)
        ordered(root);print_setup(root,root is detail)
    # Keep detail printouts legible: operational columns on paper, audit fields still in Excel.
    book=ET.fromstring(parts['xl/workbook.xml']);names=ET.SubElement(book,tag('definedNames'))
    for idx,area in [(0,f"'Resumen'!$A$1:$N${footer+1}"),(1,f"'Entregas'!$A$1:$H${end}")]:
        ET.SubElement(names,tag('definedName'),name='_xlnm.Print_Area',localSheetId=str(idx)).text=area
    ET.SubElement(names,tag('definedName'),name='_xlnm.Print_Titles',localSheetId='1').text="'Entregas'!$5:$5"
    ET.SubElement(book,tag('calcPr'),calcMode='auto',fullCalcOnLoad='1')
    parts['xl/workbook.xml']=xml(book);parts['xl/worksheets/sheet1.xml']=xml(summary);parts['xl/worksheets/sheet2.xml']=xml(detail)
    output=io.BytesIO()
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
        for name,body in parts.items():z.writestr(name,body)
    return output.getvalue()
