#!/usr/bin/env python3
"""Extract and validate Rufus setup wrappers from an unpacked official PE."""
import argparse
import hashlib
import pathlib
import struct

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('executable',type=pathlib.Path,help='Official Rufus v4.16, unpacked with UPX -d')
parser.add_argument('--output',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parent.parent/'app/src/main/assets/rufus')
args=parser.parse_args()
raw=args.executable.read_bytes()
u16=lambda offset:struct.unpack_from('<H',raw,offset)[0]
u32=lambda offset:struct.unpack_from('<I',raw,offset)[0]
pe=u32(0x3c)
if raw[:2]!=b'MZ' or raw[pe:pe+4]!=b'PE\0\0':raise SystemExit('Invalid PE executable')
opt=pe+24
sections=opt+u16(pe+20)
for i in range(u16(pe+6)):
    if raw[sections+40*i:sections+40*i+8].startswith(b'UPX'):
        raise SystemExit('Unpack the executable using UPX -d before extraction')
def offset(rva,length=1):
    for i in range(u16(pe+6)):
        section=sections+40*i
        va,size,start=u32(section+12),u32(section+16),u32(section+20)
        if va<=rva and rva+length<=va+size and start+rva-va+length<=len(raw):
            return start+rva-va
    raise ValueError('Resource is outside the raw PE sections')
directory=opt+(112 if u16(opt)==0x20b else 96)
root=offset(u32(directory+16))
def entries(relative):
    start=root+relative
    return [(u32(start+16+8*i),u32(start+20+8*i)) for i in range(u16(start+12)+u16(start+14))]
resources=dict(entries(dict(entries(0))[10]&0x7fffffff))
wrappers=[]
for resource,name,wanted in [
    (503,'setup_x64.exe','11df838dc69378187e1e1aaf32d34384157642d07096c6e49c1d0e7375634544'),
    (504,'setup_arm64.exe','14bd07f559513890a0f6565df3927392b4fe6b8e6fc3f5e832e9d69c8b7bb7eb'),
]:
    data_entry=entries(resources[resource]&0x7fffffff)[0][1]&0x7fffffff
    rva,length=u32(root+data_entry),u32(root+data_entry+4)
    start=offset(rva,length)
    payload=raw[start:start+length]
    if hashlib.sha256(payload).hexdigest()!=wanted:raise SystemExit('SHA-256 mismatch: '+name)
    wrappers.append((name,payload))
args.output.mkdir(parents=True,exist_ok=True)
for name,payload in wrappers:
    (args.output/name).write_bytes(payload)
    print(name,hashlib.sha256(payload).hexdigest())
