# Lte_du_env — Spectrum Check

사진 속 휴대용 스펙트럼 분석기(5G/LTE 밴드, VSWR, DTF, Cable Loss 화면)를 본떠 만든 안드로이드 앱입니다.
중계기/기지국의 TX·RX 스펙트럼을 현장에서 확인하기 위한 용도로 설계했습니다.

## 기능

- **Spectrum**: KT 밴드 프리셋(LTE B1/B3/B8, NR n78) 선택, TX/RX 전환(수신할 다운링크/업링크 주파수만
  바뀝니다 — 실제로 전파를 내보내는 송신 기능은 없습니다), 마커 M1~M5, 피크 서치, 화면 탭으로 마커 배치
- **VSWR**: 대역 내 리턴로스/VSWR 스윕 트레이스
- **DTF (Distance to Fault)**: 케이블 길이에 따른 반사(불량 지점) 트레이스
- **Cable Loss**: 케이블 길이 입력 후 손실(dB, dB/100m) 측정

## 데이터 소스

앱은 `RepeaterDataSource` 인터페이스(`app/src/main/java/com/lteduenv/spectrum/data/RepeaterDataSource.kt`)
뒤에서 두 가지 구현을 제공합니다.

1. **SimulatedRepeaterDataSource** (기본값) — 하드웨어 없이도 앱을 바로 사용해볼 수 있도록 노이즈
   플로어 + 가상 캐리어/반사 패턴을 실시간으로 생성합니다. **실측 데이터가 아닙니다.**
2. **HttpRepeaterDataSource** — 중계기/기지국 장비가 노출하는 HTTP API를 폴링해 실제 TX/RX
   스펙트럼·VSWR·DTF 값을 가져오는 뼈대(stub) 구현입니다. Settings에서 데이터 소스를
   "Repeater HTTP"로 바꾸고 Base URL을 입력하면 이 소스로 전환됩니다.
3. **UsbSdrDataSource** — USB SDR 동글을 USB OTG로 연결해 **실제 스펙트럼**을 잡습니다.
   **RTL2832U(RTL-SDR)와 HackRF One을 둘 다 지원**하며, 어느 쪽을 꽂았는지 자동으로 인식합니다.
   Settings에서 "USB SDR"을 선택하고 **Connect USB SDR** 버튼을 누르면 동글을 찾아 USB 권한을
   요청합니다. 둘 다 수신 전용 동글이라 VSWR/DTF/Cable Loss는 이 모드에서도 시뮬레이션 데이터로
   남습니다. 연동에 필요한 하드웨어와 배선(커플러 → 어댑터 → 동글 → 폰)은 아래 "USB SDR 연동"
   절을 참고하세요.

`HttpRepeaterDataSource`가 기대하는 JSON 스키마는 파일 상단 주석에 정리되어 있습니다. 실제 장비의
API 문서를 받으면 요청 경로와 파싱 로직을 그 스펙에 맞게 조정하면 됩니다. VSWR/DTF는 실제로는
방향성 커플러나 VNA 같은 RF 측정 하드웨어가 있어야 측정 가능한 값이므로, 폰 단독으로는 측정할 수
없고 장비 쪽 API나 별도 하드웨어 연동이 필요합니다.

## USB SDR 연동

두 가지 동글을 지원하고, `UsbSdrDataSource`가 연결된 장치의 USB vendor/product ID를 보고 자동으로
구분합니다.

### RTL-SDR (RTL2832U)

`app/src/main/java/com/virginiaprivacy/sdr/`에는 RTL2832U/R820T 튜너를 제어하는 코드가 들어있습니다.
[Virginia Privacy Coalition의 `sdr` 프로젝트](https://github.com/virginiaprivacycoalition/sdr)에서
가져온 것으로 **GPL-2.0 라이선스**이며, 자세한 출처·수정 내역·라이선스 영향은 그 디렉터리의
`NOTICE.md`/`LICENSE.txt`를 참고하세요. USB 연결 자체(안드로이드 `UsbManager`/`UsbDeviceConnection`
글루 코드, `RtlSdrUsbController.kt`)는 이 프로젝트에서 새로 작성한 코드입니다.

- 주파수 범위: 대략 24MHz~1.7GHz (칩/모델에 따라 편차 있음)
- 표시 대역폭: 한 번에 약 2.4MHz (샘플레이트 고정값)

### HackRF One

`HackRfController.kt`는 [Great Scott Gadgets의 공식 오픈소스 호스트 드라이버
`libhackrf`](https://github.com/greatscottgadgets/hackrf)
(`host/libhackrf/src/hackrf.c`/`.h`, BSD 계열 라이선스)에서 실제 USB 벤더 리퀘스트 번호·페이로드
포맷을 확인한 뒤 안드로이드 `UsbManager`/`UsbDeviceConnection`로 직접 새로 작성한 코드입니다 (HackRF
프로토콜은 제어 전송 몇 개로 충분히 단순해서, RTL-SDR 때와 달리 외부 코드를 그대로 가져다 쓸 필요는
없었습니다).

- 주파수 범위: 1MHz~6GHz (LTE 전대역 + 5G n78 포함)
- 표시 대역폭: 한 번에 8MHz (샘플레이트 고정값, `HackRfController.SAMPLE_RATE_HZ`)
- LNA/VGA 게인은 코드에 고정값(24dB/20dB)으로 넣어뒀습니다 — 실제 하드웨어로 테스트하면서 신호가
  너무 세거나 약하면 이 값을 조정해야 할 수 있습니다
- **Preamp**: HackRF의 전단 브로드밴드 AMP(약 +14dB)를 Settings에서 켜고 끌 수 있습니다 — 계측기의
  "Preamp" 토글과 같은 개념으로, 약한 신호 감도를 높이는 대신 근처에 강한 신호가 있으면 클리핑될 수
  있습니다. RTL-SDR은 튜너를 항상 자동 게인으로 돌리기 때문에 이 토글이 영향을 주지 않습니다.
- 송신(TX) 기능은 없습니다. HackRF One 하드웨어 자체는 송수신 겸용이지만, 이 앱은 수신(RX)만
  구현했습니다 — 무선국 수검처럼 장비에 케이블로 물려 공인 계측을 하는 용도는 애초에 HackRF
  같은 비교정 소비자용 SDR로는 커버할 수 없는 영역이라 필요하지 않다고 판단했습니다.

### 공통 준비물

- SDR 동글 (RTL-SDR 또는 HackRF One 중 하나)
- USB(폰) ↔ 동글 단자에 맞는 OTG 케이블 (동글마다 USB-A/USB-B/Micro-USB 등 다름)
- 중계기/기지국의 커플러 모니터링 포트에서 동글 입력까지 연결할 RF 케이블 + 커넥터 변환 어댑터
  (예: N-type to SMA)
- 필요 시 RF 감쇠기(어테뉴에이터) — 소비자용 동글은 입력 파워 보호 회로가 약하므로 과전력 유입을
  막기 위해 권장

**하드웨어 없이도 안전한 이유**: 동글이 연결되지 않았거나 USB 권한이 없으면 이 소스는 그냥
에러 메시지만 상태 표시줄에 띄우고, 시뮬레이션 모드는 평소대로 계속 동작합니다.

**검증 범위**: 이 환경에는 실제 RTL-SDR/HackRF 하드웨어가 없어서, 두 드라이버 모두 실제 장치로
동작을 검증하지 못했습니다. USB 프로토콜은 각 프로젝트의 실제 오픈소스를 읽고 맞춘 것이지만,
실기 연결 시 문제가 있으면 로그/증상을 알려주시면 바로 고칠 수 있습니다.

## 빌드

### GitHub Actions로 APK 받기 (로컬에 Android Studio가 없어도 가능)

이 저장소에는 `.github/workflows/build-apk.yml`이 포함되어 있어 push할 때마다 자동으로 디버그
APK를 빌드합니다. GitHub 저장소의 **Actions** 탭 → 해당 워크플로 실행 → **Artifacts** 에서
`spectrum-check-debug-apk`를 내려받으면 됩니다.

### 로컬 빌드

Android Studio (또는 Android SDK + JDK 17)가 설치되어 있다면:

```bash
./gradlew assembleDebug
```

생성된 APK는 `app/build/outputs/apk/debug/app-debug.apk` 에 위치합니다.

## 실제 장비 연동을 위해 필요한 정보

- 중계기/기지국 모니터링 API의 인증 방식, 엔드포인트, 응답 스키마
- VSWR/DTF 측정을 수행하는 하드웨어(방향성 커플러, VNA 모듈 등)와의 연동 방식
- 현장에서 사용할 주파수 밴드 목록 (현재 프리셋 외 추가/수정 필요 시 `BandPresets.kt` 수정)
