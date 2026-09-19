# KT Debug Viewer (Lte_du_env)

단말기 System/Debug Screen(IMEI/MDN/Band/RSRP/RSRQ/SINR/NR Information 등)을 모사한
Android 앱입니다. LTE/NR 밴드를 선택해 디버그 화면을 보거나, 여러 밴드를 동시에 비교할 수
있습니다.

## 구성

- **Kotlin + Jetpack Compose**, 단일 모듈(`app`) Android 프로젝트.
- `app/src/main/assets/lte.json`, `5g.json`, `repeater.json` : KT 장비조회 앱(Ktserch.apk)에
  내장돼 있던 실제 장비 DB를 그대로 사용합니다. PCI(`pc`/`p9`/`p18`/`p21`)로 인덱싱해서,
  디버그 화면에 표시된 PCI가 실제로 어떤 사이트/장비(이름, 종류, DU 그룹, 주소, 좌표)인지
  조회합니다.
- 신호 지표(RSRP/RSRQ/SINR/MCS/BLER 등)는 **모의(mock) 값**입니다. 실제 모뎀에서 값을
  읽어오는 기능은 아직 없습니다 — `MockDebugDataGenerator`가 그 자리를 채우고 있으니,
  나중에 실제 모뎀 연동으로 교체하면 됩니다.
- PCI 자체는 임의의 숫자가 아니라 `EquipmentRepository`가 실제 데이터셋에서 무작위로 뽑은
  값이라, 장비 조회 결과가 항상 실제 사이트와 매칭됩니다.

## 화면

1. **밴드 목록** (`BandListScreen`) — KT LTE 밴드(1/3/7/8/41)와 NR 밴드(n78/n257) 목록.
   체크박스로 여러 밴드를 선택하면 비교 화면으로 이동합니다.
2. **디버그 화면** (`DebugScreen`) — 원본 System/Debug Screen과 동일한 필드 배치(Band/BW,
   EARFCN, RSRP/RSRQ/RSSI/SINR, CQI/RI, MCS, DRX, NR Information, STATUS/GUTI 등) +
   PCI로 조회한 실제 장비 정보 카드.
3. **비교 화면** (`CompareScreen`) — 선택한 밴드들의 PCI/RSRP/RSRQ/SINR/장비명을 표로 비교.

## 알아둘 점 / 한계

- **밴드 번호(1/3/8/41, n78/n257)와 주파수 표는 공개적으로 알려진 KT 스펙트럼 정보를 참고한
  것으로, KT 내부 RF 설계 문서에서 가져온 값이 아닙니다.** 실제 운용 판단에 쓰기 전에
  RF/엔지니어링팀 자료로 재검증하세요 (`KtBandCatalog` 주석 참고).
- 장비 DB(`assets/*.json`)에는 밴드 번호가 들어있지 않아서, "밴드 선택"은 화면에 보이는
  EARFCN/대역폭 등 모의 값에만 영향을 주고, 장비 조회는 밴드와 무관하게 PCI 기준으로 전체
  LTE/5G/중계기 DB에서 검색합니다.
- 동일 PCI를 여러 사이트가 재사용하므로 조회 결과가 여러 건일 수 있습니다. 기기 위치
  (`ACCESS_FINE_LOCATION`)를 넘기면 거리순 정렬을 지원하도록 `EquipmentRepository`에
  만들어 뒀지만, 현재 UI에서는 위치를 요청하지 않습니다 — 필요하면 `DebugScreen`에서
  `FusedLocationProviderClient`나 `LocationManager`로 위치를 받아 넘기면 됩니다.
- **실제 KT 장비 인프라 데이터(주소/좌표/장비ID)가 `assets/`에 그대로 들어있습니다.** 이
  저장소는 비공개(private)라는 전제로 그대로 커밋했습니다 — 공개 저장소로 옮기거나 외부에
  공유할 계획이 있다면 반드시 데이터를 빼거나 마스킹하세요.
- 이 샌드박스에는 Android SDK가 없어 실제 빌드(`./gradlew assembleDebug`)를 실행해보지
  못했습니다. Android Studio(또는 SDK가 설치된 환경)에서 열어 Gradle sync 후 빌드해
  확인해 주세요.

## 다음에 이어서 하면 좋은 것

- 실제 모뎀 값 연동 (`TelephonyManager`/`TelephonyCallback`, 또는 AT 커맨드)으로
  `MockDebugDataGenerator` 대체.
- 위치 권한 요청 및 거리순 정렬 UI 노출.
- 장비 DB를 앱 내장 대신 서버/DB 연동으로 교체 (최신 데이터 반영).
