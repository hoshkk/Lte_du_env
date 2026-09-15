# Lte_du_env — Spectrum Check

사진 속 휴대용 스펙트럼 분석기(5G/LTE 밴드, VSWR, DTF, Cable Loss 화면)를 본떠 만든 안드로이드 앱입니다.
중계기/기지국의 TX·RX 스펙트럼을 현장에서 확인하기 위한 용도로 설계했습니다.

## 기능

- **Spectrum**: 5G/LTE 밴드 프리셋(5A, LTE B1/B3/B5/B7/B8, NR n78/n28) 선택, TX/RX 전환, 마커 M1~M5,
  피크 서치, 화면 탭으로 마커 배치
- **VSWR**: 대역 내 리턴로스/VSWR 스윕 트레이스
- **DTF (Distance to Fault)**: 케이블 길이에 따른 반사(불량 지점) 트레이스
- **Cable Loss**: 케이블 길이 입력 후 손실(dB, dB/100m) 측정

## 데이터 소스

앱은 `RepeaterDataSource` 인터페이스(`app/src/main/java/com/lteduenv/spectrum/data/RepeaterDataSource.kt`)
뒤에서 두 가지 구현을 제공합니다.

1. **SimulatedRepeaterDataSource** (기본값) — 하드웨어 없이도 앱을 바로 사용해볼 수 있도록 노이즈
   플로어 + 가상 캐리어/반사 패턴을 실시간으로 생성합니다. **실측 데이터가 아닙니다.**
2. **HttpRepeaterDataSource** — 중계기/기지국 장비가 노출하는 HTTP API를 폴링해 실제 TX/RX
   스펙트럼·VSWR·DTF 값을 가져오는 뼈대(stub) 구현입니다. 앱 우측 상단 톱니바퀴(Settings)에서
   "Use repeater HTTP API"를 켜고 Base URL을 입력하면 이 소스로 전환됩니다.

`HttpRepeaterDataSource`가 기대하는 JSON 스키마는 파일 상단 주석에 정리되어 있습니다. 실제 장비의
API 문서를 받으면 요청 경로와 파싱 로직을 그 스펙에 맞게 조정하면 됩니다. VSWR/DTF는 실제로는
방향성 커플러나 VNA 같은 RF 측정 하드웨어가 있어야 측정 가능한 값이므로, 폰 단독으로는 측정할 수
없고 장비 쪽 API나 별도 하드웨어 연동이 필요합니다.

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
