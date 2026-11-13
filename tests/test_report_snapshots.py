import copy,json,sys,tempfile,time,unittest,zipfile,io
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT/'desktop'),str(ROOT/'licensing/desktop'),str(ROOT/'tests')]
from test_executive_excel import fixture
from test_reports_desktop import config
from reports_client import mac,ReportUnavailable,save_workbook
from report_snapshots import ReportSession,SnapshotStore
from colbert_license import LicenseBlocked

SELECT=dict(period='month',date='2026-11-13',month='2026-11',plant='')
class Permission:
    def __init__(self,deny_at=0):self.calls=0;self.deny_at=deny_at
    def require_access(self):
        self.calls+=1
        if self.deny_at and self.calls>=self.deny_at:raise LicenseBlocked('Licencia pausada')
def signed(company,count=155,selection=None):
    _,report=fixture(count)
    report['plants']=['Planta Norte','Planta Centro','Planta Sur']
    nonce='c'*64;now=int(time.time()*1000)
    payload=json.dumps(dict(selection or SELECT,action='export'),separators=(',',':'))
    request=dict(time=now,nonce=nonce,payload=payload,signature=mac(company['reportKey'],f'request|{now}|{nonce}|{payload}'))
    reply=json.dumps(report,separators=(',',':'))
    return dict(request=request,response=json.dumps(dict(payload=reply,signature=mac(company['reportKey'],'reply|'+nonce+'|'+reply))))

class SnapshotsTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        self.company=config()['companies'][0];self.permission=Permission();self.session=ReportSession(self.company,self.permission,self.directory.name)
    def fetch(self,count=155):
        document=signed(self.company,count)
        with patch.object(self.session.client,'snapshot',return_value=document):return self.session.load(SELECT)
    def test_restart_offline_preserves_full_export_and_original_timestamp(self):
        live=self.fetch();self.assertFalse(live['_cached']);self.assertEqual(len(live['items']),100)
        restarted=ReportSession(self.company,Permission(),self.directory.name)
        with patch.object(restarted.client,'snapshot',side_effect=ReportUnavailable('Sin conexión')),patch('reports_client.time.time',return_value=time.time()+7200):
            cached=restarted.load(SELECT,True)
        self.assertTrue(cached['_cached']);self.assertEqual(cached['serverTime'],live['serverTime']);self.assertEqual(cached['count'],155)
        target=Path(self.directory.name)/'completo.xlsx';save_workbook(target,cached)
        with zipfile.ZipFile(target) as z:self.assertIn(b'A5:M160',z.read('xl/worksheets/sheet2.xml'))
    def test_wrong_company_selection_and_tampered_copy_are_rejected(self):
        self.fetch();path=self.session.store.path(SELECT)
        other=ReportSession(dict(self.company,reportKey='b'*64),Permission(),self.directory.name)
        with self.assertRaises(ValueError):other.store.get(SELECT)
        selection=dict(SELECT,plant='Planta Sur');other_path=self.session.store.path(selection);other_path.write_bytes(path.read_bytes())
        with self.assertRaises(ValueError):self.session.store.get(selection)
        document=json.loads(path.read_text());document['request']['time']+=1;path.write_text(json.dumps(document))
        with self.assertRaises(ValueError):self.session.store.get(SELECT)
    def test_current_license_is_required_for_live_and_cached_reports(self):
        self.fetch();denied=ReportSession(self.company,Permission(1),self.directory.name)
        with patch.object(denied.client,'snapshot') as network:
            with self.assertRaises(LicenseBlocked):denied.load(SELECT)
            network.assert_not_called()
        after_network=ReportSession(self.company,Permission(2),self.directory.name)
        with patch.object(after_network.client,'snapshot',side_effect=ReportUnavailable('offline')):
            with self.assertRaises(LicenseBlocked):after_network.load(SELECT)
    def test_server_license_rejection_and_bad_signature_never_fall_back(self):
        self.fetch()
        for failure in [LicenseBlocked('license'),ValueError('signature')]:
            with patch.object(self.session.client,'snapshot',side_effect=failure):
                with self.assertRaises(type(failure)):self.session.load(SELECT)
    def test_revocation_during_download_does_not_cache_new_data(self):
        session=ReportSession(self.company,Permission(2),self.directory.name)
        with patch.object(session.client,'snapshot',return_value=signed(self.company)):
            with self.assertRaises(LicenseBlocked):session.load(SELECT)
        self.assertFalse(session.store.path(SELECT).exists())
    def test_reconnection_replaces_copy_and_an_empty_report_is_valid(self):
        empty=self.fetch(0);self.assertEqual(empty['items'],[]);self.assertEqual(empty['count'],0)
        updated=self.fetch(160);self.assertFalse(updated['_cached']);self.assertEqual(updated['count'],160)
        self.assertEqual(self.session.store.get(SELECT)['count'],160)
    def test_no_cache_is_reported_honestly_and_bad_download_preserves_old_copy(self):
        with patch.object(self.session.client,'snapshot',side_effect=ReportUnavailable('offline')):
            with self.assertRaisesRegex(ReportUnavailable,'no hay una copia'):self.session.load(SELECT)
        self.fetch();before=self.session.store.path(SELECT).read_bytes()
        document=signed(self.company);reply=json.loads(document['response']);body=json.loads(reply['payload']);body['count']=900
        reply['payload']=json.dumps(body);reply['signature']=mac(self.company['reportKey'],'reply|'+document['request']['nonce']+'|'+reply['payload']);document['response']=json.dumps(reply)
        with patch.object(self.session.client,'snapshot',return_value=document):
            with self.assertRaises(ValueError):self.session.load(SELECT)
        self.assertEqual(self.session.store.path(SELECT).read_bytes(),before)
    def test_disk_failure_keeps_live_report_but_does_not_claim_copy_saved(self):
        with patch.object(self.session.client,'snapshot',return_value=signed(self.company)),patch.object(self.session.store,'put',side_effect=OSError('disk full')):
            report=self.session.load(SELECT)
        self.assertEqual(report['count'],155);self.assertFalse(report['_cached']);self.assertFalse(report['_cacheSaved'])

if __name__=='__main__':unittest.main()
