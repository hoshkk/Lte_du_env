# KT Debug Viewer (Lte_du_env)

단말기 System/Debug Screen(IMEI/MDN/Band/RSRP/RSRQ/SINR/NR Information 등)을 모사한
Android 앱입니다. 실제 단말의 `TelephonyManager`에서 **실시간(약 1.5초 주기)으로 진짜 PCI/
RSRP/RSRQ/SINR 값**을 읽어 보여주고, GPS 기반으로 내 주변 실제 장비/PCI를 찾아볼 수
있습니다.

## 실제 데이터 vs 표시 불가 항목 (중요)

이 앱은 더 이상 임의의 값을 만들어내지 않습니다. `LiveCellInfoProvider`가
`android.telephony.TelephonyManager.allCellInfo`로 **실제 서빙 셀 정보를 읽어** 화면에
그대로 보여주고, 값이 바뀌면 폰 화면도 같이 바뀝니다.

다만 Android는 일반 앱(비루팅/비시스템 앱)에게 아래 값을 **원천적으로 제공하지 않습니다.**
제조사의 숨겨진 엔지니어 모드만 접근 가능한 베이스밴드 내부 값이라 Google이 의도적으로
막아둔 영역이라, 화면에는 가짜 숫자 대신 **"-"** 로 표시됩니다:

- TxPwr, TxPusch, TxPucch, RB, MCS, MOD(QAM), BLER, DRX, ANT/Diff, AvgRSRP/AvgRSRQ
- RRC 상태, ESM CAUSE, GUTI (코어망 내부 식별자, 앱에는 아예 안 알려줌)
- 실제 IMEI (Android 10+ 에서는 일반 앱이 절대 못 읽음 — OS 정책)
- MDN(내 번호)은 `getLine1Number()`로 시도하지만 대부분 기기/통신사에서 비어 있어 "N/A"로
  나올 수 있습니다.

실제로 얻는 값: **PCI, TAC, EARFCN/NR-ARFCN, band(추정 포함), bandwidth, RSRP, RSRQ,
RSSI(API 29+), SINR** — 이 정도가 Android 공개 API의 한계입니다.

## 구성

- **Kotlin + Jetpack Compose**, 단일 모듈(`app`) Android 프로젝트.
- `app/src/main/assets/lte.json`, `5g.json`, `repeater.json` : KT 장비조회 앱(Ktserch.apk)에
  내장돼 있던 실제 장비 DB를 그대로 사용합니다. PCI(`pc`/`p9`/`p18`/`p21`)로 인덱싱해서,
  디버그 화면에 표시된 (실제) PCI가 어떤 사이트/장비(이름, 종류, DU 그룹, 주소, 좌표)인지
  조회하고, GPS 좌표로 주변 장비를 검색합니다.
- `LiveCellInfoProvider`가 실제 `CellInfoLte`/`CellInfoNr`를 파싱해 PCI/RSRP/RSRQ/SINR을
  제공합니다 (위 "표시 불가 항목" 참고).

## 밴드 목록

`KtBandCatalog`에 아래 5개만 들어있습니다 (요청하신 목록 그대로):

- LTE Band 3, 10MHz (1.8GHz)
- LTE Band 3, 20MHz (1.8GHz)
- LTE Band 8 (900MHz)
- LTE Band 1 (2.1GHz)
- NR n78 (3.5GHz, 5G)

Band 3는 대역폭이 다른 두 항목(10MHz/20MHz)이 목록에 각각 따로 나옵니다. **일반 앱은
모뎀을 특정 밴드로 강제 접속시킬 수 없으므로**, 디버그 화면은 선택한 밴드로 실제 잡혀
있으면 그 값을, 아니면 지금 실제로 잡고 있는 다른 밴드의 값을 안내 문구와 함께 보여줍니다.

## 화면

1. **밴드 목록** (`BandListScreen`) — 위 5개 밴드 목록. 체크박스로 여러 밴드를 선택하면 비교
   화면으로 이동하고, "GPS로 내 주변 PCI/장비 보기" 버튼으로 주변 검색 화면으로 갑니다.
2. **디버그 화면** (`DebugScreen`) — 전화 상태 + 위치 권한을 받으면 약 1.5초마다 실제 셀
   정보를 다시 읽어 화면을 갱신합니다. 실제 PCI로 조회한 장비 정보 카드도 함께 보여주고,
   위치를 알면 동일 PCI 후보 중 가장 가까운 곳을 "가장 가까움"으로 표시합니다.
3. **비교 화면** (`CompareScreen`) — 선택한 밴드 중 지금 실제로 잡히는 것만 PCI/RSRP/SINR/
   RSRQ/장비명이 나오고, 안 잡히는 밴드는 "(안 잡힘)"으로 표시됩니다.
4. **내 주변 PCI/장비** (`NearbyScreen`) — 위치 권한을 받아 GPS로 현재 위치를 구하고,
   `EquipmentRepository.nearby()`로 실제 LTE/5G/중계기 DB에서 반경(250m/500m/1km/2km) 안의
   장비를 거리순으로 보여줍니다.

## 알아둘 점 / 한계

- **밴드 번호/주파수 표는 공개적으로 알려진 KT 스펙트럼 정보를 참고한 것으로, KT 내부 RF
  설계 문서에서 가져온 값이 아닙니다.** 실제 운용 판단에 쓰기 전에 RF/엔지니어링팀 자료로
  재검증하세요 (`KtBandCatalog` 주석 참고). Band 1의 대역폭(20MHz)은 확인 안 된 기본값이고,
  band는 API 29+ 에서는 기기가 알려준 값을, 그 이하에서는 EARFCN 범위로 추정한 값입니다.
- 장비 DB(`assets/*.json`)에는 밴드 번호가 들어있지 않아서, 장비 조회는 밴드와 무관하게
  전체 LTE/5G/중계기 DB에서 PCI로만 검색합니다.
- 동일 PCI를 여러 사이트가 재사용하므로 PCI 조회 결과가 여러 건일 수 있습니다.
- 위치 권한을 거부하면 디버그/비교/주변검색 화면 모두 권한 허용 버튼만 표시됩니다. 실내/
  GPS 신호가 약한 곳에서는 위치를 못 가져올 수 있습니다.
- **실제 KT 장비 인프라 데이터(주소/좌표/장비ID)가 `assets/`에 그대로 들어있습니다.** 이
  저장소는 비공개(private)라는 전제로 그대로 커밋했습니다 — 공개 저장소로 옮기거나 외부에
  공유할 계획이 있다면 반드시 데이터를 빼거나 마스킹하세요.
- 이 세션이 돌아가는 샌드박스는 `dl.google.com`(Android SDK/Google Maven)을 네트워크
  정책으로 막고 있어 로컬에서 직접 빌드/검증할 수 없었습니다. 대신
  `.github/workflows/android-build.yml`이 push할 때마다 GitHub Actions에서 실제로
  `./gradlew assembleDebug`를 돌려 디버그 APK를 아티팩트로 올립니다 — Actions 탭에서
  받아 설치해 확인하세요. TelephonyManager 관련 코드는 실제 Android SDK/기기가 있는
  환경에서 컴파일된 적은 있어도, 실제 폰에 설치해 검증해보지는 못했습니다.

## 다음에 이어서 하면 좋은 것

- `TelephonyCallback`(API 31+)/`PhoneStateListener`로 폴링 대신 이벤트 기반 업데이트.
- 장비 DB를 앱 내장 대신 서버/DB 연동으로 교체 (최신 데이터 반영).
- 주변 검색 결과를 지도(OSM 등)에 마커로 표시.
