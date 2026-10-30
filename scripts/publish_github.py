"""Publish only reviewed source files using the user's authenticated GitHub CLI."""
from pathlib import Path
import json, subprocess, os, sys, urllib.request, urllib.error

ROOT=Path(__file__).resolve().parents[1]
GH=ROOT/'.tools/gh/bin/gh.exe'
REPO='jesuscolbert/qr_Repositorio'
BRANCH='soboce-comedor'

def api(path, method='GET', payload=None):
    token_file=ROOT/'private/github-token.txt'
    token=os.environ.get('GH_TOKEN') or (token_file.read_text(encoding='utf-8-sig').strip() if token_file.exists() else '')
    if token:
        request=urllib.request.Request('https://api.github.com'+path,
            data=json.dumps(payload).encode('utf-8') if payload is not None else None,
            method=method,headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','Content-Type':'application/json','User-Agent':'SoboceComedor-deploy'})
        try:
            with urllib.request.urlopen(request,timeout=40) as response:
                data=response.read().decode('utf-8')
                return json.loads(data) if data else None
        except urllib.error.HTTPError as exc:
            raise RuntimeError('GitHub HTTP '+str(exc.code)+': '+json.loads(exc.read()).get('message','Request failed')) from None
    cmd=[str(GH),'api',path,'--method',method]
    if payload is not None:
        cmd+=['--input','-']
    proc=subprocess.run(cmd,input=json.dumps(payload) if payload is not None else None,text=True,encoding='utf-8',capture_output=True)
    if proc.returncode:
        raise RuntimeError(proc.stderr.strip())
    return json.loads(proc.stdout) if proc.stdout.strip() else None

def main():
    identity=api('/user')
    if identity['login']!='jesuscolbert':
        raise RuntimeError('Sign in as jesuscolbert before publishing.')
    base=api(f'/repos/{REPO}/git/ref/heads/main')['object']['sha']
    try:
        head=api(f'/repos/{REPO}/git/ref/heads/{BRANCH}')['object']['sha']
    except RuntimeError as exc:
        if '404' not in str(exc): raise
        head=api(f'/repos/{REPO}/git/refs','POST',{'ref':'refs/heads/'+BRANCH,'sha':base})['object']['sha']
    tree=api(f'/repos/{REPO}/git/commits/{head}')['tree']['sha']
    paths=[ROOT/p for p in ['.gitignore','settings.gradle','build.gradle','gradle.properties','README.md','.github/workflows/android.yml','scripts/import_roster.py','scripts/package_setup.py','scripts/publish_github.py','scripts/build_cloudflared.sh','scripts/provision_peers.py','scripts/package_installers.py']]
    paths += [p for folder in ['app','backend','tests'] for p in (ROOT/folder).rglob('*') if p.is_file() and not {'build','jniLibs','__pycache__'}.intersection(p.relative_to(ROOT).parts) and p.name != 'cloudflared-LICENSE.txt']
    entries=[{'path':p.relative_to(ROOT).as_posix(),'mode':'100644','type':'blob','content':p.read_text(encoding='utf-8')} for p in paths]
    new_tree=api(f'/repos/{REPO}/git/trees','POST',{'base_tree':tree,'tree':entries})['sha']
    if new_tree==tree:
        print(json.dumps({'unchanged':True,'sha':head,'branch':BRANCH}));return
    message=sys.argv[1] if len(sys.argv)>1 else 'Build SOBOCE meal control Android app with centralized QR validation'
    commit=api(f'/repos/{REPO}/git/commits','POST',{'message':message,'tree':new_tree,'parents':[head]})
    api(f'/repos/{REPO}/git/refs/heads/{BRANCH}','PATCH',{'sha':commit['sha'],'force':False})
    print(json.dumps({'sha':commit['sha'],'branch':BRANCH,'url':f'https://github.com/{REPO}/tree/{BRANCH}','files':len(entries)}))

if __name__=='__main__':main()
