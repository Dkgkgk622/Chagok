import re,sys
src=open(sys.argv[1]).read()
put=set(re.findall(r'\bput(?:Opt)?\("([A-Za-z0-9_]+)"',src))
get=set(re.findall(r'\.(?:opt\w*|get\w*|has|isNull)\("([A-Za-z0-9_]+)"',src))
ALLOW={"version"}  # schema stamp, read only when migrating
a,b=sorted(put-get-ALLOW),sorted(get-put)
print("쓰기만 하고 읽지 않는 키:",a or "없음")
print("읽기만 하고 쓰지 않는 키:",b or "없음")
print(f"기록 키 {len(put)}개 / 읽기 키 {len(get)}개")
sys.exit(1 if (a or b) else 0)
