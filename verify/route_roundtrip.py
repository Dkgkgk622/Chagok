"""Round trip: app-encoded route (RouteCodecProof output) -> report.html decodeRoute in Chromium -> compare.
usage: python3 route_roundtrip.py <proof output file> <path to docs/report.html>"""
import json, sys
from playwright.sync_api import sync_playwright

lines = open(sys.argv[1], encoding='utf-8').read().strip().splitlines()
enc = json.loads(lines[0])
pts = [l.split(',') for l in lines[1].split(';')]
evs = [l.split(',') for l in lines[2].split(';')]
dws = [l.split(',') for l in lines[3].split(';')]
fails = 0
def check(name, ok, detail=''):
    global fails
    print(('  ✔ ' if ok else '  ✘ ') + name + ('' if ok else '  ' + str(detail)))
    fails += 0 if ok else 1

with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page()
    pg.goto('file://' + sys.argv[2])
    r = pg.evaluate('(r) => decodeRoute(r)', enc)
    b.close()

check('점 개수', len(r['points']) == len(pts), (len(r['points']), len(pts)))
terr = max(abs(q['t'] - int(a[0])) for q, a in zip(r['points'], pts))
check(f'점 시각 오차 0.5초 이하 (긴 운행에서도 쌓이지 않음, 최대 {terr} ms)', terr <= 500, terr)
perr = max(max(abs(q['lat'] - float(a[1])), abs(q['lon'] - float(a[2]))) for q, a in zip(r['points'], pts))
check(f'점 위치 오차 0.00001도(약 1 m) 이하 (최대 {perr:.7f})', perr <= 0.0000051, perr)
check('점 속도·제한속도', all(q['speed'] == round(float(a[3])) and (q['limit'] == (None if a[4] == 'null' else int(a[4]))) for q, a in zip(r['points'], pts)))
for q, a in zip(r['events'], evs):
    la, lo = float(a[1]), float(a[2])
    check(f"이벤트 {q['code']}: 시각·위치·속도·지속", abs(q['t'] - int(a[0])) <= 500 and abs(q['lat'] - la) <= 0.0000051 and
          abs(q['lon'] - lo) <= 0.0000051 and q['speed'] == round(float(a[3])) and q['len'] == round((int(a[6]) - int(a[0])) / 1000 - 0.4999),
          (q, a))
for q, a in zip(r['dwells'], dws):
    check('1분 이상 정차: 시각·위치·시간', abs(q['t'] - int(a[0])) <= 500 and abs(q['lat'] - float(a[1])) <= 0.0000051 and q['len'] == int(a[3]), (q, a))
print('결과:', '통과' if fails == 0 else f'실패 {fails}')
