"""Logbook rows: app compact format ("tx") -> report.html decodeTax in Chromium -> must equal the car screen's cells.
usage: python3 tax_roundtrip.py <TaxCodecProof output file> <path to docs/report.html>"""
import json, sys
from playwright.sync_api import sync_playwright

lines = open(sys.argv[1], encoding='utf-8').read().strip().splitlines()
tx, expected = json.loads(lines[0]), json.loads(lines[1])
with sync_playwright() as p:
    b = p.chromium.launch()
    fails = 0
    for tz in ['Asia/Seoul', 'America/Los_Angeles', 'Pacific/Kiritimati']:   # the phone's time zone must not matter
        ctx = b.new_context(timezone_id=tz); pg = ctx.new_page()
        pg.goto('file://' + sys.argv[2])
        got = pg.evaluate('(tx) => decodeTax(tx)', tx)
        bad = [(i, g, e) for i, (g, e) in enumerate(zip(got, expected)) if g != e]
        ok = len(got) == len(expected) and not bad
        print(('  ✔ ' if ok else '  ✘ ') + f'휴대폰 시간대 {tz}: {len(expected)}행 모든 칸 일치' + ('' if ok else f'  {bad[:2]}'))
        fails += 0 if ok else 1
        ctx.close()
    b.close()
blank = sum(1 for r in expected if r[4] == '')
print(f'  (참고) 직접 기재 행 {blank}건, 앞 운행 뒤 계기판 차이 표시 {sum(1 for r in expected if "앞 운행 뒤" in r[7])}건, 다른 사용자 {sum(1 for r in expected if r[1] != "영업팀 홍길동")}건')
print('결과:', '통과' if fails == 0 else f'실패 {fails}')
