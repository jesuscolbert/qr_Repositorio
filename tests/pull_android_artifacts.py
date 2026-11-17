"""Copy emulator fixtures without ADB sync's bulk-transfer/compression channel."""
from pathlib import Path
import io,re,subprocess,sys,tarfile,time

def main():
    name=sys.argv[1];target=Path(sys.argv[2]).resolve()
    assert re.fullmatch(r'[a-z0-9-]+',name)
    target.mkdir(parents=True,exist_ok=True)
    for attempt in range(3):
        result=subprocess.run(['adb','exec-out','tar','-C','/sdcard/Download','-cf','-',name],capture_output=True,timeout=60)
        if result.returncode==0 and 0<len(result.stdout)<64*1024*1024:
            try:
                # ADB can return success while its binary stream is truncated.
                with tarfile.open(fileobj=io.BytesIO(result.stdout),mode='r:') as check:check.getmembers()
                break
            except (tarfile.TarError,EOFError):pass
        print('Emulator copy retry: incomplete archive.',flush=True)
        time.sleep(1)
    assert result.returncode==0,'Could not retrieve emulator verification files'
    assert 0<len(result.stdout)<64*1024*1024
    with tarfile.open(fileobj=io.BytesIO(result.stdout),mode='r:') as archive:
        members=archive.getmembers()
        assert members
        for entry in members:
            assert entry.isfile() or entry.isdir()
            assert (target/entry.name).resolve().is_relative_to(target/name)
        archive.extractall(target,filter='data')
    print(f'Copied {name}: {len(members)} entries, {len(result.stdout)} bytes.',flush=True)

if __name__=='__main__':main()
