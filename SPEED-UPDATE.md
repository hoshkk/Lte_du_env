# Spectrum Check Fast 2.4.0

전체 대역 순차 스윕 개선. 기존 Fast 2.3.0과 패키지/서명이 같아 업데이트 설치 가능. 원래 Spectrum Check와는 별도 앱.

## 변경
- 기본 관측 대역: Center 909.3 / Span 15 MHz / RBW 목표 10 kHz.
- 빠른 측정 1.5M 버튼 제거. 기존 주파수/Span 설정 유지.
- CaptureGate가 주파수 변경 직전 캡처 차단, 명령 후 시간 보호구간(기본 80ms, 최초 최소 250ms) 동안 데이터 폐기, 추가 131072바이트 폐기 후 IQ 블록 조립. 튜닝마다 부분 조립 및 최신 프레임 초기화. 단편 수신의 절대 I/Q 바이트 순서 유지.
- 이 처리는 앱의 샘플 폐기 정책이며 RF 튜닝 완료 ACK가 아님. 외부 rtl_tcp 드라이버의 큐 지연 상한을 알 수 없으므로 이전 주파수 샘플이 절대 남지 않는다고 보장하지 않음. 실물 신호로 검증 필요.
- 측정 설정 FREQ/SPAN에서 스윕 대기 40–1000ms 입력 및 80/160/650ms 선택. 650ms도 RF 정확도 인증값이 아님.
- 넓은 대역은 이전 스윕을 회색으로 유지하고 갱신된 부분을 노란색으로 표시. Max Hold는 파란색으로 현재 파형과 동시 표시.
- 마지막 전체 스윕 소요 시간을 단조 시계로 측정해 ms 표시. 부분 스윕 시간과 구분.
- Channel Power는 마지막 완료 스윕으로 계산. 미완료 스윕으로 계산하지 않음.
- 단일 구간 화면 간 대기 33ms. 실제 FPS 보장 아님. UI가 밀리면 최신 프레임 우선.
- 프로필 저장 형식 v2, 기존 v1을 기본 대기 80ms로 읽기.

## 검증
2026-10-01 JDK17/Gradle8.7/SDK34: testDebugUnitTest assembleDebug 성공.
총 30개 테스트 실패0, 오류0. CaptureGate 시간/바이트 폐기, 재튜닝 중 부분 IQ 제거, 홀수 패킷 I/Q 정렬, 프로필 마이그레이션, TCP 모의 연속/전체대역 수신 확인.
4.8 MB/s 목표(48000바이트를 10ms 간격으로 송신, 운영체제 스케줄링에 따른 실제 속도 차이 있음) 모의 서버 첫 전체 스윕:
B8 Span15: 1206ms / 9구간
B3 Span25: 1860ms / 14구간
B3 Span30: 2117ms / 17구간
이 수치는 실제 RTL-SDR의 USB 지연이나 RF 튜닝 성능 검증이 아님. 짧은 버스트를 놓칠 수 있는 순차 스윕이며 전체 대역 동시 실시간 수신이 아님.
실물 하드웨어/폰 UI 검증 미완료. 현장에서는 알려진 신호의 주파수와 레벨을 80/650ms 및 기준 계측기로 비교해야 함.

## 빌드/서명
패키지 com.lteduenv.spectrum.speed, versionCode8, versionName2.4.0-rtl-v4.
./gradlew testDebugUnitTest assembleDebug
서명 키는 SpectrumCheck-Fast-signing-backup.zip에 별도 보관. 소스에는 포함하지 않음.
이전 VALIDATION.md/FIELD-PROFILES.md/README.md의 버전별 기록보다 이 문서가 이번 변경에 대해 우선함.
