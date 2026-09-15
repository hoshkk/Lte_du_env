# Lte_du_env — Spectrum Check

휴대용 스펙트럼 분석기를 본떠 만든 안드로이드 앱입니다. **현재는 LTE 리버스(UL, 단말→기지국)
스펙트럼만** 보는 용도로 범위를 좁혔습니다 — RTL-SDR(약 24MHz~1.7GHz)로 커버 가능한 주파수가
그쪽뿐이라서, 다운링크(DL)·VSWR·DTF·Cable Loss는 전부 뺐습니다 (이유는 아래 참고).

## 기능

- **Spectrum**: KT LTE 밴드 프리셋(B3(30M/20M)/B8 — 업링크 주파수만) 선택, 마커 M1~M5, 피크 서치,
  화면 탭으로 마커 배치, RBW/VBW 조절, Ref level offset으로 모니터 포트/케이블 손실 보정,
  Channel Power(적분 파워 + PSD) 토글
  - LTE B3(30M/20M)·B8 업링크 주파수는 KT MS2090A 계측기 교육자료(ROU UL 측정 절차)에서 확인한
    실제 채널 값입니다. 다운링크 값은 코드 주석에만 남겨뒀습니다 (RTL-SDR로는 못 잡으므로).
  - **RBW**: USB SDR 모드에서는 실제로 FFT 크기를 바꿔서 주파수 분해능에 반영됩니다 (좁게 잡을수록
    정밀하지만 화면 갱신이 느려짐). Simulated 소스는 FFT를 직접 하지 않아 영향이 없습니다.
  - **VBW**: RBW보다 좁게 주면 연속된 스윕 사이 지수이동평균(EMA)으로 트레이스를 스무딩합니다.
    VBW ≥ RBW(기본값)면 스무딩 없이 매 스윕 그대로 표시됩니다.

**왜 DL/VSWR/DTF/Cable Loss가 없는지**: DL(다운링크)은 KT B3 기준 1840~1845MHz라 RTL-SDR
한계(~1.7GHz)를 넘어가서 안 잡힙니다. VSWR/DTF/Cable Loss는 방향성 커플러나 VNA 같은 RF 측정
하드웨어가 있어야 측정 가능한 값이라, 수신 전용 SDR 동글로는 애초에 측정할 수 없는 영역입니다
(HackRF를 쓰더라도 마찬가지) — 예전엔 시뮬레이션 데이터로 화면만 보여줬지만, 실사용과 무관해서
아예 뺐습니다.

## 데이터 소스

앱은 `RepeaterDataSource` 인터페이스(`app/src/main/java/com/lteduenv/spectrum/data/RepeaterDataSource.kt`)
뒤에서 두 가지 구현을 제공합니다.

1. **SimulatedRepeaterDataSource** (기본값) — 하드웨어 없이도 앱을 바로 사용해볼 수 있도록 노이즈
   플로어(+ 가끔 나오는 간섭 스파이크)를 실시간으로 생성합니다. **실측 데이터가 아닙니다.**
2. **UsbSdrDataSource** — USB SDR 동글을 USB OTG로 연결해 **실제 스펙트럼**을 잡습니다.
   **RTL2832U(RTL-SDR)와 HackRF One을 둘 다 지원**하며, 어느 쪽을 꽂았는지 자동으로 인식합니다.
   Settings에서 "USB SDR"을 선택하고 **Connect USB SDR** 버튼을 누르면 동글을 찾아 USB 권한을
   요청합니다. 연동에 필요한 하드웨어와 배선(커플러 → 어댑터 → 동글 → 폰)은 아래 "USB SDR 연동"
   절을 참고하세요.

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
- **Auto gain / Manual gain**: Settings에 R820T/R828D 튜너의 게인을 제어하는 토글이 있습니다.
  Auto는 튜너 칩 자체의 AGC(LNA+Mixer 자동 게인)를 씁니다. Off로 끄면 1~10 슬라이더로 수동
  게인을 고정할 수 있습니다 — 실제 SK/KT 계측기 화면처럼 Attenuator/Preamp/AGC가 물리적으로
  분리된 스테이지는 아니고, RTL-SDR은 게인 축이 하나뿐이라 이 슬라이더 하나가 두 역할을 겸합니다:
  낮은 값은 강한 신호 옆에서 클리핑을 막는 Attenuator처럼, 높은 값은 약한 신호를 더 잘 잡는
  Preamp처럼 동작합니다.

  참고로 이 기능을 만들면서 vendored 드라이버(`com.virginiaprivacy.sdr`)의 게인 인덱스 계산식에
  나눗셈/곱셈 순서가 뒤바뀐 버그가 있는 걸 발견해서 같이 고쳤습니다 — 자세한 내용은
  `app/src/main/java/com/virginiaprivacy/sdr/NOTICE.md` 참고.

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
  있습니다. RTL-SDR에는 이 토글이 영향을 주지 않습니다 (대신 위 Auto/Manual gain을 쓰세요).
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

## 참고

- 현장에서 사용할 주파수 밴드 목록 (현재 프리셋 외 추가/수정 필요 시 `Models.kt`의
  `BandPresets.all` 수정)
- 나중에 더 넓은 대역(HackRF 등)으로 다운링크까지 보고 싶어지면, `BandPreset`에 다운링크
  주파수를 다시 넣고 `SweepConfig`에 방향 전환을 되살리면 됩니다 (커밋 히스토리에 이전 구현이
  남아있습니다).
