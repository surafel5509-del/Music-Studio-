#!/usr/bin/env python3
"""Decodes FLAC files written by our encoder with libFLAC (via soundfile) and checks sample count, bit depth,
MD5 in STREAMINFO vs decoded PCM, and signal accuracy.
usage: verify_flac.py <flac_tool binary> <scratch dir> [<dir with ws_cmp_* files from test_export>]   (needs: pip install soundfile numpy)"""
import sys, subprocess, hashlib, numpy as np, soundfile as sf, os
fails=0
for ch in (1,2):
  for bits in (16,24):
    for mode in (0,1,2,3):
      for frames in (1, 100, 4096, 4097, 50000):
        p=f"{sys.argv[2]}/t_{ch}_{bits}_{mode}_{frames}.flac"
        rc=subprocess.run([sys.argv[1],p,str(ch),str(bits),str(frames),str(mode)]).returncode
        if rc: print("encoder rc",rc,ch,bits,mode,frames); fails+=1; continue
        data,sr=sf.read(p,dtype='int32',always_2d=True)
        info=sf.info(p)
        ok = data.shape==(frames,ch) and sr==44100 and info.subtype==("PCM_16" if bits==16 else "PCM_24")
        shift=32-bits
        pcm=(data>>shift).astype('<i4')
        bps=bits//8
        inter=pcm.reshape(-1).astype('<i4').tobytes()
        b=bytearray()
        arr=np.frombuffer(inter,dtype=np.uint8).reshape(-1,4)[:,:bps]
        md5=hashlib.md5(arr.tobytes()).digest()
        hdr=open(p,'rb').read(42)
        ok = ok and hdr[26:42]==md5
        total=int.from_bytes(hdr[18:26],'big')&0xFFFFFFFFF
        ok = ok and total==frames
        if mode==0 and frames>1000:
            exp=0.5*np.sin(2*np.pi*440*np.arange(frames)/44100.0)
            full=(2**(bits-1)-1)
            err=np.abs(pcm[:,0]/full-exp[:frames]).max()
            ok = ok and err<2e-3
        if mode==3:  # square: value must be +-full
            ok = ok and np.abs(pcm).max()==2**(bits-1)-1
        if not ok: print("FAIL",ch,bits,mode,frames); fails+=1
        os.remove(p)
if len(sys.argv)>3:
    for bits in (16,24):
        for ch in (1,2):
            base=f"{sys.argv[3]}/ws_cmp_{bits}_{ch}"
            if not os.path.exists(base+".flac"): print("missing",base); fails+=1; continue
            a,sa=sf.read(base+".flac",dtype='int32',always_2d=True)
            w,sw=sf.read(base+".wav",dtype='int32',always_2d=True)
            same = sa==sw==44100 and a.shape==w.shape and np.array_equal(a>>(32-bits), w>>(32-bits))
            print(f"export FLAC vs WAV {bits}-bit {ch}ch: {'identical samples' if same else 'MISMATCH'} ({a.shape[0]} frames)")
            if not same: fails+=1
print("FLAC verification (libsndfile/libFLAC decode, MD5, frame CRCs): failures =",fails)
sys.exit(1 if fails else 0)
