# KT Debug Viewer (Lte_du_env)

단말기 System/Debug Screen(IMEI/MDN/Band/RSRP/RSRQ/SINR/NR Information 등)을 모사한
Android 앱입니다. KT 밴드를 선택해 디버그 화면을 보거나, 여러 밴드를 동시에 비교하고,
GPS 기반으로 내 주변 실제 장비/PCI를 찾아볼 수 있습니다.

## 구성

- **Kotlin + Jetpack Compose**, 단일 모듈(`app`) Android 프로젝트.
- `app/src/main/assets/lte.json`, `5g.json`, `repeater.json` : KT 장비조회 앱(Ktserch.apk)에
  내장돼 있던 실제 장비 DB를 그대로 사용합니다. PCI(`pc`/`p9`/`p18`/`p21`)로 인덱싱해서,
  디버그 화면에 표시된 PCI가 실제로 어떤 사이트/장비(이름, 종류, DU 그룹, 주소, 좌표)인지
  조회하고, GPS 좌표로 주변 장비를 검색합니다.
- 신호 지표(RSRP/RSRQ/SINR/MCS/BLER 등)는 **모의(mock) 값**입니다. 실제 모뎀에서 값을
  읽어오는 기능은 아직 없습니다 — `MockDebugDataGenerator`가 그 자리를 채우고 있으니,
  나중에 실제 모뎀 연동으로 교체하면 됩니다.
- PCI 자체는 임의의 숫자가 아니라 `EquipmentRepository`가 실제 데이터셋에서 무작위로 뽑은
  값이라, 장비 조회 결과가 항상 실제 사이트와 매칭됩니다.

## 밴드 목록

`KtBandCatalog`에 아래 5개만 들어있습니다 (요청하신 목록 그대로):

- LTE Band 3, 10MHz (1.8GHz)
- LTE Band 3, 20MHz (1.8GHz)
- LTE Band 8 (900MHz)
- LTE Band 1 (2.1GHz)
- NR n78 (3.5GHz, 5G)

Band 3는 대역폭이 다른 두 항목(10MHz/20MHz)이 목록에 각각 따로 나옵니다.

## 화면

1. **밴드 목록** (`BandListScreen`) — 위 5개 밴드 목록. 체크박스로 여러 밴드를 선택하면 비교
   화면으로 이동하고, "GPS로 내 주변 PCI/장비 보기" 버튼으로 주변 검색 화면으로 갑니다.
2. **디버그 화면** (`DebugScreen`) — 원본 System/Debug Screen과 동일한 필드 배치(Band/BW,
   EARFCN, PCI, RSRP/RSRQ/RSSI/SINR, CQI/RI, TxPwr, MCS, DRX, NR Information, STATUS/GUTI
   등) + PCI로 조회한 실제 장비 정보 카드.
3. **비교 화면** (`CompareScreen`) — 선택한 밴드들의 밴드/PCI/RSRP/SINR/TxPwr(+RSRQ/장비명)를
   표로 비교.
4. **내 주변 PCI/장비** (`NearbyScreen`) — 위치 권한을 받아 GPS로 현재 위치를 구하고,
   `EquipmentRepository.nearby()`로 실제 LTE/5G/중계기 DB에서 반경(250m/500m/1km/2km) 안의
   장비를 거리순으로 보여줍니다. 각 항목에 실제 PCI(복수일 수 있음), 이름, 종류, 주소,
   거리가 표시됩니다.

## 알아둘 점 / 한계

- **밴드 번호/주파수 표는 공개적으로 알려진 KT 스펙트럼 정보를 참고한 것으로, KT 내부 RF
  설계 문서에서 가져온 값이 아닙니다.** 실제 운용 판단에 쓰기 전에 RF/엔지니어링팀 자료로
  재검증하세요 (`KtBandCatalog` 주석 참고). Band 1의 대역폭(20MHz)은 확인 안 된 기본값입니다.
- 장비 DB(`assets/*.json`)에는 밴드 번호가 들어있지 않아서, 디버그 화면의 "밴드 선택"은
  화면에 보이는 EARFCN/대역폭 등 모의 값에만 영향을 주고, PCI 조회는 밴드와 무관하게 전체
  LTE/5G/중계기 DB에서 검색합니다. **GPS 주변 검색은 밴드와 무관하게 위치 기반으로만
  동작합니다** (데이터셋에 밴드 정보가 없기 때문).
- 동일 PCI를 여러 사이트가 재사용하므로 PCI 조회 결과가 여러 건일 수 있습니다.
- 위치 권한(`ACCESS_FINE_LOCATION`)을 거부하면 "내 주변 PCI/장비" 화면에서 권한 허용
  버튼만 표시됩니다. 실내/GPS 신호가 약한 곳에서는 위치를 못 가져올 수 있습니다.
- **실제 KT 장비 인프라 데이터(주소/좌표/장비ID)가 `assets/`에 그대로 들어있습니다.** 이
  저장소는 비공개(private)라는 전제로 그대로 커밋했습니다 — 공개 저장소로 옮기거나 외부에
  공유할 계획이 있다면 반드시 데이터를 빼거나 마스킹하세요.
- 이 세션이 돌아가는 샌드박스는 `dl.google.com`(Android SDK/Google Maven)을 네트워크
  정책으로 막고 있어 로컬에서 직접 빌드/검증할 수 없었습니다. 대신
  `.github/workflows/android-build.yml`이 push할 때마다 GitHub Actions에서 실제로
  `./gradlew assembleDebug`를 돌려 디버그 APK를 아티팩트로 올립니다 — Actions 탭에서
  받아 설치해 확인하세요.

## 다음에 이어서 하면 좋은 것

- 실제 모뎀 값 연동 (`TelephonyManager`/`TelephonyCallback`, 또는 AT 커맨드)으로
  `MockDebugDataGenerator` 대체.
- 장비 DB를 앱 내장 대신 서버/DB 연동으로 교체 (최신 데이터 반영).
- 주변 검색 결과를 지도(OSM 등)에 마커로 표시.
