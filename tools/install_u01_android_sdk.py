#!/usr/bin/env python3
"""Install only the six byte-pinned official BASS SDK inputs already approved by F04."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile
from generated_bass_sdk_guard import MANIFEST,MANIFEST_SHA256,PATHS,ROOT,verify_generated_sdk

def main():
    raw=MANIFEST.read_bytes();assert hashlib.sha256(raw).hexdigest()==MANIFEST_SHA256
    manifest=json.loads(raw);assert set(manifest['files'])==PATHS
    with tempfile.TemporaryDirectory(prefix='u01-sdk-') as directory:
        archives={}
        for i,entry in enumerate(manifest['archives']):
            path=Path(directory)/(str(i)+'.zip')
            subprocess.run(['curl','-fL','--retry','3',entry['url'],'-o',str(path)],check=True)
            data=path.read_bytes();assert (len(data),hashlib.sha256(data).hexdigest())==(entry['bytes'],entry['sha256']),'SDK archive identity mismatch'
            archives[entry['url']]=path
        for name,entry in manifest['files'].items():
            with zipfile.ZipFile(archives[entry['archive_url']]) as archive:data=archive.read(entry['archive_member'])
            assert (len(data),hashlib.sha256(data).hexdigest())==(entry['bytes'],entry['sha256'])
            path=ROOT/name;assert not path.exists(),'unexpected existing generated SDK input'
            path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print(json.dumps(verify_generated_sdk(ROOT,PATHS),sort_keys=True))

if __name__=='__main__':main()
