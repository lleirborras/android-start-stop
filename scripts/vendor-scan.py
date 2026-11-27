#!/usr/bin/env python3 -I
"""Finds stop-start settings in Topway vendor builds. Static only: nothing is installed or run.

Usage: python3 -I scripts/vendor-scan.py <apk dir> <work dir> [<index dir>]

Reads every APK in <apk dir>. A build is a candidate when a string resource or a preference key mentions
stop-start and its code can reach the MCU (a TWUtil subclass). Candidates are decompiled with jadx, and every
setting whose title or key mentions stop-start is listed with the code that handles it. Writes
<work dir>/scan.json and <work dir>/report.md. Builds already in scan.json are skipped. Needs jadx on PATH and
aapt from the Android SDK build-tools.
"""
import json
import os
import re
import subprocess
import sys
import zipfile

TEXT = re.compile(r'start.?stop|stop.?start|start and stop|auto.?stop|idle.?stop|idling.?stop|启停|怠速', re.I)
KEY = re.compile(r'start_?stop|stop_?start|start_and_stop|auto_?stop|idle_?stop|idling_?stop|startstop', re.I)
# Fault and warning texts, and the info screens' idle counters: shown, never set.
NOT_SETTING = re.compile(r'error|warn|brake|idle_speed|idle_slow|cumulative|psa_37_|psa_49_|hybrid_info', re.I)
TWUTIL = b'Landroid/tw/john/TWUtil;'
CALL = re.compile(r'\.[a-zA-Z]{1,3}\(\s*\d+\s*,\s*[a-zA-Z0-9]+\s*,|put\(|K0\(|write\(')
SEND = re.compile(r'public int (\w+)\(int i, int i2, int i3\) \{\s*return write\(1281, i, 2, '
                  r'new byte\[\]\{\(byte\) i2, \(byte\) i3\}\);')
AAPT = os.path.join(os.environ.get('ANDROID_HOME', os.path.expanduser('~/Library/Android/sdk')),
                    'build-tools', '34.0.0', 'aapt')


def strings(apk):
    """Names of string resources whose text mentions stop-start, without fault texts."""
    out = subprocess.run([AAPT, 'dump', '--values', 'resources', apk], capture_output=True, text=True,
                         errors='replace').stdout
    found, name = set(), None
    for line in out.split('\n'):
        m = re.search(r':string/([A-Za-z0-9_]+):', line)
        if m:
            name = m.group(1)
        elif name and '(string' in line and TEXT.search(line):
            found.add(name)
    return sorted(n for n in found if not NOT_SETTING.search(n))


def reaches_mcu(apk):
    with zipfile.ZipFile(apk) as z:
        return any(TWUTIL in z.read(n) for n in z.namelist() if n.endswith('.dex'))


def java_files(src):
    for root, _, files in os.walk(os.path.join(src, 'sources')):
        for f in files:
            if f.endswith('.java'):
                yield os.path.join(root, f)


def settings(src, names):
    """Preference keys whose title is one of names, or whose key mentions stop-start."""
    keys = {}
    xml = os.path.join(src, 'resources', 'res', 'xml')
    if not os.path.isdir(xml):
        return keys
    for f in sorted(os.listdir(xml)):
        text = open(os.path.join(xml, f), encoding='utf8', errors='replace').read()
        for el in re.findall(r'<[A-Za-z.]+\s[^>]*>', text):
            key = re.search(r'android:key="([^"]+)"', el)
            title = re.search(r'android:title="@string/([^"]+)"', el)
            if not key:
                continue
            k, t = key.group(1), title.group(1) if title else ''
            if (t in names or KEY.search(k)) and not NOT_SETTING.search(k):
                keys[k] = {'xml': f, 'title': t}
    return keys


def handlers(src, key):
    """Lines that mention the key in quotes, and the send calls just after them."""
    out = []
    for p in java_files(src):
        lines = open(p, encoding='utf8', errors='replace').read().split('\n')
        for i, line in enumerate(lines):
            if '"' + key + '"' not in line:
                continue
            near = [ln.strip()[:200] for ln in lines[i:i + 14] if CALL.search(ln)]
            out.append({'file': os.path.relpath(p, src), 'line': i + 1, 'text': line.strip()[:200], 'near': near[:5]})
    return out


def wrappers(src):
    found = set()
    for p in java_files(src):
        text = open(p, encoding='utf8', errors='replace').read()
        if 'extends TWUtil' in text:
            found |= set(SEND.findall(text))
    return sorted(found)


def names(index):
    """Build id to car selections, from the chooser's tree.json and res.json when present."""
    try:
        tree = json.load(open(os.path.join(index, 'tree.json')))
        res = json.load(open(os.path.join(index, 'res.json')))
    except (OSError, ValueError):
        return {}
    label = {}
    for k, lst in tree.items():
        for n in lst:
            label[k.split('|', 1)[1] + '_' + n['configId']] = n['name']
    by = {}
    for k, lst in res.items():
        p = k.split('|', 1)[1].split('_')
        text = ' / '.join(label.get('_'.join(p[:i]), '?') for i in range(2, len(p) + 1))
        for e in lst:
            if e.get('sourceId'):
                by.setdefault(e['sourceId'], set()).add(text)
    return {k: sorted(v) for k, v in by.items()}


def scan(apks, work, f):
    path = os.path.join(apks, f)
    try:
        texts = strings(path)
        mcu = reaches_mcu(path)
    except (zipfile.BadZipFile, OSError) as e:
        return {'error': str(e)}
    entry = {'strings': texts, 'mcu': mcu, 'settings': {}, 'wrappers': []}
    if not mcu:
        return entry
    src = os.path.join(work, 'src', f[:-4])
    if not texts:
        # Keys alone, for builds without a matching string: cheap check before decompiling.
        with zipfile.ZipFile(path) as z:
            raw = b''.join(z.read(n) for n in z.namelist() if n.startswith('res/xml/'))
        if not re.search(rb'(?i)start_?stop|stop_?start|start_and_stop|idle_?stop|startstop', raw):
            return entry
    if not os.path.isdir(src):
        subprocess.run(['jadx', '-q', '-d', src, path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    entry['wrappers'] = wrappers(src)
    for k, v in settings(src, set(texts)).items():
        v['code'] = handlers(src, k)
        entry['settings'][k] = v
    return entry


def main():
    apks, work = sys.argv[1], sys.argv[2]
    index = sys.argv[3] if len(sys.argv) > 3 else os.path.join(apks, 'index')
    os.makedirs(work, exist_ok=True)
    state_path = os.path.join(work, 'scan.json')
    state = json.load(open(state_path)) if os.path.exists(state_path) else {}
    todo = [f for f in sorted(os.listdir(apks)) if f.endswith('.apk') and f not in state]
    for i, f in enumerate(todo):
        state[f] = scan(apks, work, f)
        if i % 50 == 49:
            json.dump(state, open(state_path, 'w'), indent=1, sort_keys=True)
            print('%d/%d' % (i + 1, len(todo)), flush=True)
    json.dump(state, open(state_path, 'w'), indent=1, sort_keys=True)

    cars = names(index)
    found = {f: e for f, e in state.items() if e.get('settings')}
    shown = sum(1 for e in state.values() if e.get('strings') and not e.get('settings'))
    with open(os.path.join(work, 'report.md'), 'w') as r:
        r.write('# Stop-start candidates\n\n%d APKs scanned, %d new this run. %d with a stop-start setting; %d more '
                'only mention stop-start in text, with no setting.\n\n' % (len(state), len(todo), len(found), shown))
        for f, e in sorted(found.items()):
            sel = cars.get('_'.join(f[:-4].split('_')[:-1]), [])
            r.write('## %s\n\nSelections: %d\n\n' % (f, len(sel)))
            for s in sel[:8]:
                r.write('- %s\n' % s)
            if len(sel) > 8:
                r.write('- ... %d more\n' % (len(sel) - 8))
            r.write('\nSend wrappers, write(1281, cmd, 2, {id, v}): %s\n\n' % (', '.join(e['wrappers']) or 'none found'))
            for k, v in e['settings'].items():
                r.write('### `%s` (%s, title `%s`)\n\n' % (k, v['xml'], v['title']))
                for h in v['code'][:6]:
                    r.write('- `%s:%d` %s\n' % (h['file'], h['line'], h['text']))
                    for n in h['near']:
                        r.write('  - `%s`\n' % n)
                r.write('\n')
    print('%d APKs scanned, %d new, %d with a stop-start setting; report in %s'
          % (len(state), len(todo), len(found), os.path.join(work, 'report.md')))


if __name__ == '__main__':
    main()
