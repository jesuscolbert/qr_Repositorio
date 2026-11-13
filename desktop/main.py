"""Colbert Comedor: private Windows management viewer, no registration functions."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import queue
import sys
import threading
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
import urllib.error
from colbert_license import LicenseClient, LicenseBlocked
from configuration import read
from reports_client import ReportsClient, save_workbook

VERSION = '1.0.0'
BG, INK, MUTED, BLUE = '#f1f5fb', '#102039', '#536580', '#1468d4'
ALL = 'Todas las plantas'

class Viewer:
    def __init__(self, root, config, demo=False):
        self.root, self.config, self.demo = root, config, demo
        self.results, self.busy, self.generation, self.closed = queue.Queue(), False, 0, False
        self.last_report, self.clients = None, {}
        self.folder = Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'Colbert' / 'ComedorReportes'
        self.companies = {c['name']:c for c in config['companies']}
        root.title('Colbert · Reportes del comedor')
        root.geometry('1180x800'); root.minsize(950, 650); root.configure(bg=BG)
        style = ttk.Style(root); style.theme_use('clam')
        style.configure('.', font=('Segoe UI', 10))
        style.configure('TFrame', background=BG); style.configure('TLabel', background=BG, foreground=INK)
        style.configure('TButton', padding=(16, 10)); style.configure('Accent.TButton', background=BLUE, foreground='white')
        style.map('Accent.TButton', background=[('active','#0956bb'),('disabled','#9aabc2')])
        style.configure('Treeview', rowheight=36, borderwidth=0, font=('Segoe UI',10))
        style.configure('Treeview.Heading', background=INK, foreground='white', padding=(8,10), font=('Segoe UI',10,'bold'))
        header=tk.Frame(root,bg=INK,padx=28,pady=20);header.pack(fill='x')
        tk.Label(header,text='COLBERT / COMEDOR',bg=INK,fg='#9fe5c3',font=('Segoe UI',11,'bold')).pack(anchor='w')
        tk.Label(header,text='Reportes de entregas',bg=INK,fg='white',font=('Segoe UI',25,'bold')).pack(anchor='w',pady=(4,0))
        tk.Label(header,text='Consulta de SOBOCE y FRIGOR · Solo lectura',bg=INK,fg='#b9c7df',font=('Segoe UI',11)).pack(anchor='w')
        body=ttk.Frame(root,padding=(24,16));body.pack(fill='both',expand=True)
        filters=ttk.Frame(body);filters.pack(fill='x')
        self.company=tk.StringVar(value=next(iter(self.companies)));self.plant=tk.StringVar(value=ALL);self.period=tk.StringVar(value='Diario')
        self.date=tk.StringVar(value=(dt.datetime.now(dt.timezone.utc)-dt.timedelta(hours=4)).date().isoformat())
        for col,(title,var,values,width) in enumerate([('Empresa',self.company,list(self.companies),16),('Planta',self.plant,[ALL],28),('Período',self.period,['Diario','Mensual'],12)]):
            cell=ttk.Frame(filters);cell.grid(row=0,column=col,padx=(0,16),sticky='w');ttk.Label(cell,text=title).pack(anchor='w',pady=(0,5));box=ttk.Combobox(cell,textvariable=var,values=values,width=width,state='readonly');box.pack();box.bind('<<ComboboxSelected>>',lambda e:self.changed())
            if col==1:self.plant_box=box
        cell=ttk.Frame(filters);cell.grid(row=0,column=3,sticky='w');ttk.Label(cell,text='Fecha (AAAA-MM-DD)').pack(anchor='w',pady=(0,5));entry=ttk.Entry(cell,textvariable=self.date,width=15);entry.pack();entry.bind('<Return>',lambda e:self.changed());self.date.trace_add('write',lambda *args:self.invalidate_date())
        actions=ttk.Frame(body);actions.pack(fill='x',pady=(18,10))
        self.export_button=ttk.Button(actions,text='Exportar día a Excel (.xlsx)',style='Accent.TButton',command=self.export);self.export_button.pack(side='left')
        self.refresh_button=ttk.Button(actions,text='Actualizar reporte',command=self.refresh);self.refresh_button.pack(side='left',padx=12)
        ttk.Label(actions,text='Actualización automática cada 15 s').pack(side='right')
        self.status=tk.StringVar(value='Conectando al comedor…');ttk.Label(body,textvariable=self.status,wraplength=1080,foreground=MUTED).pack(fill='x',pady=(0,12))
        cards=ttk.Frame(body);cards.pack(fill='x');self.numbers=[]
        for n,title in enumerate(['Raciones','Trabajadores distintos','Importe registrado (Bs)','Por revisar']):
            frame=tk.Frame(cards,bg='white',padx=18,pady=14);frame.grid(row=0,column=n,sticky='ew',padx=(0,12 if n<3 else 0));cards.columnconfigure(n,weight=1);tk.Label(frame,text=title,bg='white',fg=MUTED,font=('Segoe UI',10)).pack(anchor='w');v=tk.StringVar(value='—');tk.Label(frame,textvariable=v,bg='white',fg=BLUE if n<3 else '#c7354c',font=('Segoe UI',27,'bold')).pack(anchor='w');self.numbers.append(v)
        self.meals=tk.StringVar(value='Desayuno —     Almuerzo —     Cena —     Dieta —');ttk.Label(body,textvariable=self.meals,font=('Segoe UI',12,'bold')).pack(anchor='w',pady=16)
        ttk.Label(body,text='Movimiento de entregas',font=('Segoe UI',15,'bold')).pack(anchor='w',pady=(0,10))
        table=ttk.Frame(body);table.pack(fill='both',expand=True)
        columns=('fecha','hora','nombre','comida','planta','operador','revision');self.tree=ttk.Treeview(table,columns=columns,show='headings',selectmode='browse')
        for c,title,width in zip(columns,['Fecha','Hora','Trabajador','Comida','Planta','Encargado','Revisión'],[100,75,230,100,160,160,130]):self.tree.heading(c,text=title);self.tree.column(c,width=width,minwidth=65,stretch=c in ('nombre','planta'))
        self.tree.tag_configure('alternate',background='#f1f6fd');self.tree.tag_configure('review',foreground='#b5223c')
        y=ttk.Scrollbar(table,orient='vertical',command=self.tree.yview);x=ttk.Scrollbar(table,orient='horizontal',command=self.tree.xview);self.tree.configure(yscrollcommand=y.set,xscrollcommand=x.set);self.tree.grid(row=0,column=0,sticky='nsew');y.grid(row=0,column=1,sticky='ns');x.grid(row=1,column=0,sticky='ew');table.rowconfigure(0,weight=1);table.columnconfigure(0,weight=1)
        self.footer=tk.StringVar(value='Los teléfonos sin sincronizar pueden tener entregas pendientes de aparecer.');ttk.Label(body,textvariable=self.footer,foreground=MUTED,wraplength=1080).pack(fill='x',pady=(12,0))
        root.protocol('WM_DELETE_WINDOW',self.close);root.after(100,self.receive)
        if not demo:root.after(200,self.refresh);root.after(15000,self.poll)
        else:self.draw(demo_report())

    def clear(self):
        self.last_report=None
        for v in self.numbers:v.set('—')
        self.meals.set('Desayuno —     Almuerzo —     Cena —     Dieta —')
        self.tree.delete(*self.tree.get_children())

    def invalidate_date(self):
        self.generation+=1;self.clear();self.status.set('Fecha cambiada. Pulsa Actualizar reporte.');self.export_button.configure(state='disabled')

    def changed(self):
        self.generation+=1;self.clear()
        company=self.companies[self.company.get()]
        plants=[ALL]+company.get('plants',[]);self.plant_box.configure(values=plants)
        if self.plant.get() not in plants:self.plant.set(ALL)
        self.export_button.configure(text='Exportar '+('día' if self.period.get()=='Diario' else 'mes')+' a Excel (.xlsx)',state='disabled')
        self.status.set('Consultando la selección…');self.refresh()

    def selection(self):
        date=dt.date.fromisoformat(self.date.get()).isoformat()
        return dict(period='day' if self.period.get()=='Diario' else 'month',date=date,month=date[:7],plant='' if self.plant.get()==ALL else self.plant.get())

    def refresh(self):self.start(False)
    def export(self):
        try:s=self.selection()
        except ValueError:messagebox.showerror('Fecha inválida','Usa una fecha como 2026-11-12.');return
        path=filedialog.asksaveasfilename(title='Guardar Excel',defaultextension='.xlsx',filetypes=[('Libro de Excel','*.xlsx')],initialfile=self.company.get().lower()+'-consumos-'+(s['date'] if s['period']=='day' else s['month'])+'.xlsx')
        if path:self.start(True,path)

    def start(self,export,path=None):
        if self.busy or self.demo:return
        try:selection=self.selection()
        except ValueError:self.status.set('Fecha inválida. Usa AAAA-MM-DD.');return
        c=self.companies[self.company.get()];generation=self.generation;self.busy=True;self.export_button.configure(state='disabled');self.refresh_button.configure(state='disabled');self.status.set('Preparando Excel…' if export else 'Actualizando reporte…')
        def work():
            try:
                if c['id'] not in self.clients:self.clients[c['id']]=LicenseClient(c['license'],self.folder/(c['id']+'-license.json'))
                license=self.clients[c['id']];license.require_access();report=ReportsClient(c).request(selection,export)
                # Recheck after a long export before saving the protected report.
                license.require_access()
                if export:save_workbook(path,report)
                self.results.put((generation,report,None,export,path))
            except Exception as e:self.results.put((generation,None,e,export,path))
        threading.Thread(target=work,daemon=True).start()

    def receive(self):
        try:
            generation,report,error,export,path=self.results.get_nowait();self.busy=False;self.refresh_button.configure(state='normal')
            if generation!=self.generation:self.root.after(10,self.refresh)
            elif error:
                if isinstance(error,(LicenseBlocked,PermissionError)):self.clear()
                stamp=self.timestamp(self.last_report['serverTime']) if self.last_report else 'ninguno'
                message=str(error) if isinstance(error,(RuntimeError,ValueError,PermissionError)) else 'No se pudo conectar. Comprueba internet y que haya un encargado conectado.'
                self.status.set(message+' Último reporte recibido: '+stamp+'.');self.export_button.configure(state='disabled')
                if export:messagebox.showerror('No se pudo exportar',message)
            elif export:
                self.status.set('Excel guardado: '+Path(path).name);self.export_button.configure(state='normal');messagebox.showinfo('Excel guardado','El archivo .xlsx está listo.\n'+str(path))
            else:self.draw(report)
        except queue.Empty:pass
        if not self.closed:self.root.after(100,self.receive)

    @staticmethod
    def timestamp(ms):return dt.datetime.fromtimestamp(ms/1000,dt.timezone(dt.timedelta(hours=-4))).strftime('%d/%m/%Y %H:%M:%S')

    def draw(self,report):
        self.last_report=report;self.numbers[0].set(str(report['count']));self.numbers[1].set(str(report['workers']));self.numbers[2].set(f"{report['total']:,.2f}" if not report['unpriced'] else 'Tarifas pendientes');self.numbers[3].set(str(report['conflicts']))
        self.meals.set('     '.join(m.title()+'  '+str(report['byMeal'].get(m,0)) for m in ('desayuno','almuerzo','cena','dieta')))
        self.tree.delete(*self.tree.get_children())
        for i,r in enumerate(report['items']):self.tree.insert('', 'end', values=[r.get(c,'') for c in self.tree['columns']], tags=(('alternate',) if i%2 else ())+ (('review',) if r.get('revision') else ()))
        self.status.set(('DEMOSTRACIÓN · ' if self.demo else 'Conectado · ')+'Datos recibidos: '+self.timestamp(report['serverTime'])+' (Bolivia)')
        self.footer.set(f"Mostrando {len(report['items'])} de {report['count']} entregas. Excel incluye todas. Pueden faltar teléfonos sin sincronizar.")
        plants=[ALL]+report.get('plants',[]);self.companies[self.company.get()]['plants']=report.get('plants',[]);self.plant_box.configure(values=plants);self.export_button.configure(state='normal')

    def poll(self):
        if not self.closed:self.refresh();self.root.after(15000,self.poll)
    def close(self):self.closed=True;self.root.destroy()

def demo_report():
    return dict(ok=True,serverTime=1794495600000,count=128,workers=94,total=1568.5,unpriced=0,conflicts=1,byMeal=dict(desayuno=34,almuerzo=76,cena=0,dieta=18),plants=['Planta Norte','Planta Sur'],items=[dict(fecha='2026-11-12',hora='12:24:08',nombre='Ana Pérez · demostración',comida='almuerzo',planta='Planta Norte',operador='Encargado de prueba',revision=''),dict(fecha='2026-11-12',hora='12:23:42',nombre='Luis Gómez · demostración',comida='dieta',planta='Planta Norte',operador='Encargado de prueba',revision=''),dict(fecha='2026-11-12',hora='12:22:10',nombre='María Rojas · demostración',comida='almuerzo',planta='Planta Sur',operador='Encargado de prueba',revision='Posible duplicado')])

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--self-test',type=Path);parser.add_argument('--demo-screenshot',type=Path);args=parser.parse_args()
    if args.self_test:
        config=read(sys.executable);root=tk.Tk();root.withdraw();root.update();root.destroy();args.self_test.write_text(json.dumps({'ok':True,'version':VERSION,'companies':[c['id'] for c in config['companies']]}));return
    root=tk.Tk()
    if args.demo_screenshot:
        viewer=Viewer(root,{'companies':[{'name':'SOBOCE','id':'soboce'},{'name':'FRIGOR','id':'frigor'}]},demo=True)
        def shot():
            from PIL import ImageGrab
            root.update();ImageGrab.grab(bbox=(root.winfo_rootx(),root.winfo_rooty(),root.winfo_rootx()+root.winfo_width(),root.winfo_rooty()+root.winfo_height())).save(args.demo_screenshot);root.destroy()
        root.after(1000,shot)
    else:
        try:Viewer(root,read(sys.executable))
        except Exception as e:root.withdraw();messagebox.showerror('Colbert Comedor',str(e));root.destroy();return
    root.mainloop()

if __name__=='__main__':main()
