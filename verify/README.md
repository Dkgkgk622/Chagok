# 차곡 코드 검증 (verify/)

앱 빌드에는 포함되지 않는 검증용 파일입니다. (Gradle 소스셋 밖)

| 단계 | 파일 | 무엇을 증명하나 |
|---|---|---|
| L0 정적 점검 | `json_symmetry.py` | 저장(put)한 JSON 키와 불러오는(opt/get) 키가 1:1로 맞는지 |
| L1 실컴파일 | (Gradle 캐시의 실제 jar로 kotlinc + Compose 플러그인) | 타입·시그니처·Compose 규칙 오류 0 |
| L2 시나리오 | `BehaviorProof.kt` | 가상 주행(에뮬레이터 실측 로그 재현 포함)을 넣었을 때 트립·급가감속(구간당 1건)·과속·거리·경로·정차·놓친 운행·세무표·입력 이력·출발/도착 주변 가리기·경고등 기록과 정비 전후 비교가 기준대로 나오는지 (221개, 운행 중 기기 시계가 바뀌어도 운행이 유지되는지, 지도 첫 화면 범위(가로·대각선 도로)·끌기·확대·길 놓치지 않기·누를 수 있는 점·바탕 지도 상자 계산 포함) |
| L2 경로 왕복 | `RouteCodecProof.kt` + `jsonshim/` + `route_roundtrip.py` | 앱이 압축한 1시간 경로(초 단위가 아닌 시각)를 휴대폰 페이지의 해독기로 풀었을 때 시각 0.5초·위치 약 1m 이내, 속도·이벤트·정차가 그대로인지 (Chromium). `python3 route_roundtrip.py <RouteCodecProof 출력> ../docs/report.html` |
| L2 운행기록부 왕복 | `TaxCodecProof.kt` + `tax_roundtrip.py` | 운행기록부 360행(해를 넘는 사업연도, 23시대 운행, 빈칸 행, 계기판 차이, 다른 사용자, 따옴표 든 비고)을 휴대폰 압축 형식으로 보냈을 때 휴대폰 페이지가 차 화면과 모든 칸을 똑같이 복원하는지. 휴대폰 시간대 3곳(서울·LA·키리티마티)에서 확인. `java -Duser.timezone=Asia/Seoul …TaxCodecProofKt > out.txt; python3 tax_roundtrip.py out.txt ../docs/report.html` |

`navi-stub/`는 NaviHelper 3.0.5 jar가 아직 캐시에 없어서 샘플 앱의 시그니처를 옮겨 만든 **계약 스텁**입니다.
실제 jar가 생기면 스텁 대신 실제 jar로 다시 컴파일해야 증명이 완성됩니다.

`jsonshim/`은 JVM에서 org.json을 흉내 낸 **테스트 전용** 코드입니다(android.jar의 org.json은 빈 껍데기). 앱에는 들어가지 않습니다.

## QR 인식 모의 시험 (2026-11-08)
URL 1,800자(QR 31버전, 여백 포함 149칸)를 카메라처럼 축소·흐림·잡음을 넣어 zxing-cpp로 읽어 본 결과(16회 중 성공):
칸당 4px 7회, 5px 11회, 6px 13회, 7px 15회. 1,200자도 같은 칸 크기에서 거의 같은 결과라, 담는 양을 줄이기보다
QR을 크게(화면 높이의 85%까지) 또렷하게(정수 배율, 보간 없음, 여백 4칸) 그리도록 했습니다. 실제 휴대폰 카메라 확인은 별도로 필요합니다.

## 증명하지 못한 것
- 차 화면 바탕 지도(osmdroid) 표시와 손가락 조작: 이 환경에는 안드로이드 화면이 없어 에뮬레이터에서 확인 필요. 컴파일은 osmdroid 6.1.20 공식 소스(GitHub 태그)로 만든 jar로 확인
- 실제 OpenStreetMap 지도 조각 표시 (이 환경은 OSM 서버에 접속 불가: 가짜 지도 조각으로 Leaflet 동작만 확인, 지도 라이브러리를 못 받을 때의 대체 그림은 `route-page-fallback.png`)
- 실기기/에뮬레이터 런타임(권한 팝업, 서비스 수명, 실제 신호 주기)
- Compose 화면이 실제로 그려지는 모습
- org.json·Base64 실제 동작(JVM에선 android.jar가 스텁) — 웹 페이지 디코딩은 별도로 Python·Chromium에서 확인
