#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
"""Native phone resources and a catalog-derived reader for shared JVM presenters."""
import json,re,xml.etree.ElementTree as ET

def string_pool_size(text):
    # Android's modified UTF-8 encodes supplementary code points in six bytes.
    return max(len(text.encode('utf-8'))+2*sum(ord(c)>0xffff for c in text),len(text.encode('utf-16-le'))//2)

def generate(root, native_text, quote):
    generated=root/'i18n/generated';res=generated/'android/android-phone'
    if not res.exists():return
    for folder in res.glob('values*'):
        if not folder.is_dir():continue
        qualifier=folder.name[7:]; tag=qualifier[2:].replace('+','-') if qualifier.startswith('b+') else qualifier.replace('-r','-')
        if folder.name in ('values','values-en-rUS'):
            p=folder/'strings.xml';p.write_text(p.read_text().replace('\u2068','').replace('\u2069',''))
        aliases=json.loads((root/'i18n/native-aliases.json').read_text())
        alias_dir=generated/'phone-native-aliases/res'/folder.name
        alias_dir.mkdir(parents=True,exist_ok=True)
        (alias_dir/'strings.xml').write_text('<resources>'+''.join('<string name="'+name+'">@string/m'+id.encode().hex()+'</string>' for name,id in sorted(aliases.items()))+'</resources>\n')
        (folder/'catalog_locale.xml').write_text('<resources><string name="phone_catalog_locale" translatable="false">'+(tag or 'en-US')+'</string></resources>\n')
    locales=[]
    for folder in sorted(res.glob('values-*')):
        q=folder.name[7:];locales.append(q[2:].replace('+','-') if q.startswith('b+') else q.replace('-r','-'))
    (res/'xml').mkdir(exist_ok=True)
    (res/'xml/phone_locales_config.xml').write_text('<locale-config xmlns:android="http://schemas.android.com/apk/res/android">'+''.join('<locale android:name="'+tag+'" />' for tag in locales)+'</locale-config>\n')
    (generated/'phone-locales.json').write_text(json.dumps(locales,indent=2)+'\n')
    push=generated/'push-locales/res'
    source=json.loads((root/'contracts/push-i18n/production/ir.json').read_text())
    for tag in source['locales']:
        parts=tag.split('-');q='b+'+'+'.join(parts) if len(parts)>2 or (len(parts)>1 and parts[1].isdigit()) else parts[0]+('-r'+parts[1] if len(parts)>1 else '')
        for folder in (['values'] if tag==source['source_locale'] else [])+['values-'+q]:
            d=push/folder;d.mkdir(parents=True,exist_ok=True)
            (d/'push_catalog_locale.xml').write_text('<resources><string name="push_catalog_locale" translatable="false">'+tag+'</string></resources>\n')
    elements={el.attrib['name']:el for el in ET.parse(res/'values/strings.xml').getroot()}
    # AAPT replaces overlong string-pool entries with STRING_TOO_LARGE.
    # Keep catalog-derived UTF-8 templates as native raw resources instead.
    oversized={el.attrib['name'] for folder in res.glob('values*') for el in ET.parse(folder/'strings.xml').getroot() if el.tag=='string' and string_pool_size(native_text(el.text or ''))>30000}
    for folder in res.glob('values*'):
        file=folder/'strings.xml'; tree=ET.parse(file); xml=tree.getroot()
        raw=res/('raw'+folder.name.removeprefix('values'))
        for el in list(xml):
            if el.attrib['name'] in oversized:
                raw.mkdir(exist_ok=True)
                (raw/(el.attrib['name']+'.txt')).write_text(native_text(el.text or ''),encoding='utf-8')
                xml.remove(el)
        if oversized:tree.write(file,encoding='unicode',xml_declaration=True)
    lines=[];native=[]
    for p in sorted((root/'i18n/catalog/meta').glob('*.json')):
        meta=json.loads(p.read_text());id=p.stem
        if 'android-phone' not in meta['bundles']:continue
        key='m'+id.encode().hex();sel=meta.get('selector',{});enum=sel.get('enum_values',[])
        keys=[(v,key+'_v'+v.encode().hex()) for v in enum] if enum else [('',key)]
        templates=[]
        for v,k in keys:
            el=elements[k]
            forms={x.attrib['quantity']:native_text(x.text or '') for x in el} if el.tag=='plurals' else {'other':native_text(el.text or '')}
            templates.append(quote(v)+' to mapOf('+', '.join(quote(q)+' to '+quote(t) for q,t in forms.items())+')')
            native.append('        '+quote(k)+' -> R.'+('raw' if k in oversized else 'plurals' if el.tag=='plurals' else 'string')+'.'+k)
        order=[a['name'] for a in meta['arguments']]
        args={a['name']:a for a in meta['arguments']}
        arg_code=', '.join('PhoneArgument('+quote(n)+', '+quote(args[n]['type'])+', '+quote(args[n].get('numeric_style',''))+')' for n in order)
        lines.append('    '+quote(id)+' to PhoneMessage('+quote(key)+', listOf('+arg_code+'), '+quote(sel.get('name',''))+', '+quote(sel.get('type',''))+', '+quote(sel.get('enum_default',''))+', setOf('+', '.join(quote(v) for v in enum)+'), mapOf('+', '.join(templates)+')),')
    out=generated/'phone';out.mkdir();(out/'jvm').mkdir();(out/'android').mkdir()
    header='// Generated by scripts/i18n_phone.py. Edit i18n/catalog/, then regenerate.\n'
    groups=[]
    for start in range(0,len(lines),60):
        name='phoneMessages'+str(start//60);groups.append(name+'()')
        (out/'jvm'/f'{name}.kt').write_text(header+'package app.solstone.core.i18n\n\ninternal fun '+name+'(): Map<String, PhoneMessage> = mapOf(\n'+'\n'.join(lines[start:start+60])+'\n)\n')
    (out/'jvm/PhoneMessages.kt').write_text(header+'package app.solstone.core.i18n\n\ninternal val phoneMessages = '+ ' + '.join(groups)+'\n')
    # Direct resource references keep every selected message through resource shrinking.
    groups=[]
    for start in range(0,len(native),80):
        name='phoneResource'+str(start//80);groups.append(name+'(key)')
        (out/'android'/f'{name}.kt').write_text(header+'package app.solstone.observer.formfactor.shared\n\ninternal fun '+name+'(key: String): Int = when(key) {\n'+'\n'.join(native[start:start+80])+'\n        else -> 0\n}\n')
    (out/'android/PhoneResources.kt').write_text(header+'package app.solstone.observer.formfactor.shared\n\ninternal fun phoneResource(key: String): Int = sequenceOf('+', '.join(groups)+').first { it != 0 }\n')
