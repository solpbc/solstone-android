#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
"""Render locale-qualified tile previews from the installed catalog-backed tile."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
from i18n import ROOT, preview_inputs

PACKAGE = 'app.solstone.observer.phone'
COMPONENT = PACKAGE + '/app.solstone.observer.wear.WatchTileService'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', help='Exclusive development watch; requires Android 13+, 480px, density 340, font scale 1.')
parser.add_argument('--frames', type=Path, required=True, help='Native frame and accessibility XML directory.')
parser.add_argument('--capture-only', action='store_true', help='Capture on the build host; convert these frames on another host with Pillow.')
args = parser.parse_args()
inputs = preview_inputs()
args.frames.mkdir(parents=True, exist_ok=True)

def adb(*command):
    result = subprocess.run([os.environ.get('ADB','adb'),'-s',args.serial,*command],text=True,capture_output=True,timeout=45,check=True)
    return result.stdout

def broadcast(action, *extras):
    result = adb('shell','am','broadcast','-a','com.google.android.wearable.app.'+action,*extras)
    if 'result=1' not in result: raise RuntimeError(result)
    return result

if args.serial:
    profile = {'size':adb('shell','wm','size').strip(), 'density':adb('shell','wm','density').strip(),
               'font_scale':adb('shell','settings','get','system','font_scale').strip(),
               'model':adb('shell','getprop','ro.product.model').strip(), 'sdk':adb('shell','getprop','ro.build.version.sdk').strip()}
    if '480x480' not in profile['size'] or '340' not in profile['density'] or profile['font_scale'] not in ['1.0','1'] or int(profile['sdk'])<33:
        raise RuntimeError('Use the documented preview device configuration: '+str(profile))
    prior=adb('shell','cmd','locale','get-app-locales',PACKAGE)
    saved_locales=re.search(r'\[(.*?)\]',prior).group(1)
    added=False
    try:
        added_result=broadcast('DEBUG_SURFACE','--es','operation','add-tile','--ecn','component',COMPONENT)
        added=True
        index=re.search(r'Index=\[(\d+)\]',added_result)
        if not index: raise RuntimeError(added_result)
        for tag,data in inputs.items():
            adb('shell','cmd','locale','set-app-locales',PACKAGE,'--locales',tag)
            adb('shell','input','keyevent','KEYCODE_WAKEUP')
            adb('shell','wm','dismiss-keyguard')
            launch=adb('shell','am','start','-W','-n',PACKAGE+'/app.solstone.observer.wear.WearMainActivity')
            if 'Status: ok' not in launch: raise RuntimeError(launch)
            adb('shell','input','keyevent','KEYCODE_WAKEUP')
            # The locale change can asynchronously return the host to its watch face.
            # Wait on the real tile words, not merely a successful launch broadcast.
            for attempt in range(3):
                broadcast('DEBUG_SYSUI','--es','operation','show-tile','--ei','index',index[1])
                adb('shell','uiautomator','dump','/data/local/tmp/catalog-preview.xml')
                adb('pull','/data/local/tmp/catalog-preview.xml',str(args.frames/(tag+'.xml')))
                tree=ET.parse(args.frames/(tag+'.xml'))
                words=' '.join(value for node in tree.iter('node') for key,value in node.attrib.items() if key in ['text','content-desc'])
                if all(s in words for s in data['strings'].values()): break
            else:
                raise RuntimeError('The selected tile is not rendered in '+tag+'; no preview accepted.')
            adb('shell','screencap','-p','/data/local/tmp/catalog-preview.png')
            adb('pull','/data/local/tmp/catalog-preview.png',str(args.frames/(tag+'.png')))
        (args.frames/'profile.json').write_text(json.dumps(profile,indent=2)+'\n')
    finally:
        adb('shell','cmd','locale','set-app-locales',PACKAGE,'--locales',saved_locales)
        if added: broadcast('DEBUG_SURFACE','--es','operation','remove-tile','--ecn','component',COMPONENT)
        adb('shell','input','keyevent','KEYCODE_HOME')

if args.capture_only:
    print('Captured native tile frames; conversion and snapshot are still required.')
    raise SystemExit(0)

from PIL import Image
import PIL
import shutil
output = ROOT/'i18n/previews'
# This directory is generated in full, including removal of withdrawn locales.
if output.exists(): shutil.rmtree(output)
images={}
for tag,data in inputs.items():
    tree=ET.parse(args.frames/(tag+'.xml'))
    words=' '.join(v for n in tree.iter('node') for k,v in n.attrib.items() if k in ['text','content-desc'])
    if not all(s in words for s in data['strings'].values()): raise RuntimeError('Frame language differs from catalog: '+tag)
    image=Image.open(args.frames/(tag+'.png'))
    if image.size!=(480,480): raise RuntimeError('Preview source is not 480x480: '+tag)
    image=image.convert('RGB').resize((400,400),Image.Resampling.LANCZOS)
    names=[f'res/drawable-{data["qualifier"]}-nodpi/wear_tile_preview.png']
    if tag=='en-US': names.append('res/drawable-nodpi/wear_tile_preview.png')
    for name in names:
        dest=output/name;dest.parent.mkdir(parents=True,exist_ok=True);image.save(dest,format='PNG')
        images[name]=hashlib.sha256(dest.read_bytes()).hexdigest()
(output/'manifest.json').write_text(json.dumps({'version':1,'inputs':{tag:d['sha256'] for tag,d in inputs.items()},
    'images':images,'renderer':'native Wear OS tile host; 480px to 400px, Lanczos','pillow':PIL.__version__},indent=2)+'\n')
print('Generated previews for '+', '.join(inputs)+'. Run make i18n to finish the snapshot.')
