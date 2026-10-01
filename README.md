# Lte_du_env — RTL-SDR V4 간섭/스퓨리어스/SNR 간이 측정 도구

RTL-SDR Blog V4 동글로 무선 설비 주변을 현장에서 1차 스크리닝하는 파이썬 도구입니다.

| 기능 | 명령 | 결과 |
|---|---|---|
| 광대역 스캔 + 신호 검출 | `scan` | 노이즈 플로어, 검출 신호 목록(CW/광대역 구분), 그래프 |
| 스퓨리어스·누설 검출 | `spur` | 캐리어 대비 dBc, ACLR, 고조파/대칭쌍/동글 자체 스퓨리어스 표시, 기준 초과 판정 |
| SNR 간이 측정 | `snr` | 대역 전력, 잡음 전력, SNR |
| 간섭원 탐지 | `compare` | 기준(정상 상태) 스캔 대비 새로 생기거나 커진 신호 |
| 간헐 간섭 감시 | `monitor` | 반복 스캔 이벤트 로그(CSV) + max-hold 그래프 |

신호를 복조하지 않고 **전력 스펙트럼만** 측정합니다.

---

## 먼저 알아둘 한계

1. **절대 레벨(dBm)이 아니라 상대 레벨(dBFS)입니다.** RTL-SDR은 교정되지 않은 수신기이므로
   결과는 dBc(캐리어 대비), SNR, 기준 대비 증가량처럼 **상대값**으로 해석해야 합니다.
   전파법 기술기준(예: 스퓨리어스 허용치 dBm/대역폭) 적합성 판정에는 교정된 스펙트럼 분석기가
   필요합니다. 이 도구는 이상 징후를 찾는 **현장 스크리닝용**입니다.
2. **다이나믹레인지 약 45 dB(8-bit ADC).** 강한 캐리어 옆에서 −45 dBc보다 낮은 스퓨리어스는
   동글 자체 왜곡과 구분하기 어렵습니다. ACLR 3GPP 기준(45 dB급)은 "최소 이 정도"까지만 확인할 수
   있습니다. 이 경우 결과에 경고가 함께 출력됩니다.
3. **수신 범위 24 MHz ~ 1766 MHz.** KT 사용 대역 기준으로는 다음과 같습니다.

   | 대역 | 3GPP 범위 | V4로 측정 |
   |---|---|---|
   | LTE 900 MHz (B8) | DL 925–960 / UL 880–915 MHz | 가능 |
   | LTE 1.8 GHz (B3) | DL 1805–1880 / UL 1710–1785 MHz | **UL 1710–1766만 가능**, DL 불가 |
   | LTE 2.1 GHz (B1) | DL 2110–2170 / UL 1920–1980 MHz | 불가 |
   | 5G 3.5 GHz (n78) | 3.3–3.8 GHz | 불가 |

   1.8 GHz **상향(UL) 간섭** 감시에는 쓸 수 있습니다(기지국 수신 성능 저하의 주원인이
   상향 간섭인 경우가 많습니다). DL·고조파를 보려면 다운컨버터를 쓰거나 다른 SDR(HackRF,
   Airspy, PlutoSDR 등)을 써야 합니다.
4. **한 번에 보는 폭은 약 1.8 MHz입니다**(샘플레이트 2.4 MS/s의 중앙 75%). 그보다 넓은 구간은
   튜너를 옮겨 가며 스윕하고 이어 붙이므로, 아주 짧게 나타나는 버스트는 놓칠 수 있습니다.
   간헐 신호는 `monitor`(반복 스캔 + max-hold)로 잡으세요.
5. **기지국 근처는 과입력에 주의하세요.** 안테나 바로 옆이나 커플러 출력에서는 동글이 포화돼
   가짜 스퓨리어스(혼변조)가 생깁니다. 감쇠기(20~30 dB)를 쓰고, 아래의 이득 변화 확인을
   하세요. 동글 입력 최대 +10 dBm를 넘기면 손상될 수 있습니다.

---

## 설치

### 1) V4 드라이버 (필수)
V4는 R828D 튜너를 쓰기 때문에 **rtl-sdr-blog 포크 드라이버**가 필요합니다. 기본 librtlsdr로는
제대로 동작하지 않습니다.

Linux (Ubuntu/Raspberry Pi):
```bash
sudo apt purge ^librtlsdr        # 기존 드라이버 제거
sudo apt install git cmake build-essential libusb-1.0-0-dev
git clone https://github.com/rtlsdrblog/rtl-sdr-blog
cd rtl-sdr-blog && mkdir build && cd build
cmake .. -DINSTALL_UDEV_RULES=ON && make && sudo make install
sudo cp ../rtl-sdr.rules /etc/udev/rules.d/ && sudo ldconfig
echo 'blacklist dvb_usb_rtl28xxu' | sudo tee /etc/modprobe.d/blacklist-rtl.conf
# 재부팅 후 확인: rtl_test -t  → "RTL-SDR Blog V4 Detected"
```
Windows: Zadig로 WinUSB 드라이버를 설치하고, rtl-sdr-blog 릴리스의 `rtlsdr.dll`을 파이썬 실행
경로(또는 PATH)에 둡니다.

### 2) 파이썬 패키지
```bash
pip install -r requirements.txt     # numpy, matplotlib, pyrtlsdr
```

### 3) 하드웨어 없이 먼저 해보기
모든 명령에 `--sim`을 붙이면 시뮬레이터(가상 LTE 캐리어 + 스퓨리어스 + 간섭원 + DC 누설)로
동작합니다. 직접 만든 시나리오는 `--sim examples/kt_900m_site.json`처럼 줍니다.
```bash
python -m rfscan spur --sim --carrier 940M --bw 9M --span 50M
```

---

## 사용법

주파수는 `940M`, `1.75G`, `200k`, `940000000` 형태로 입력합니다. 결과는 `results/`에
CSV(스펙트럼), JSON(분석 결과), PNG(그래프)로 저장됩니다.

### 공통 옵션
- `--gain 30` : 수신 이득(dB). **측정에는 고정 이득을 쓰세요**(`auto`는 비교 측정이 틀어집니다).
  같은 장소를 반복 비교할 때는 이득을 항상 같게 둡니다.
- `--ppm N` : 주파수 오차 보정(V4는 TCXO라 보통 0~1).
- `--nfft 2048` : RBW = 2.4 MHz / nfft ≈ 1.17 kHz. 좁은 스퓨리어스를 분리하려면 키우세요.
- `--samples 262144` : 스텝당 샘플 수. 늘리면 노이즈 플로어가 매끄러워집니다(느려짐).
- `--avg-sweeps N` / `--max-hold` : 여러 번 스윕해 평균 또는 최대값 유지.
- `--bias-tee` : 외부 LNA 전원 공급.

### 1. 스퓨리어스·누설 검출
```bash
# 900MHz 대역 10MHz LTE 캐리어(점유 9MHz) 주변 ±25MHz, -40dBc 초과를 위반으로 판정
python -m rfscan spur --carrier 940M --bw 9M --span 50M --limit-dbc -40
```
출력:
- 캐리어 전력, **ACLR**(하측/상측 인접 채널)
- 캐리어 대역 밖 신호마다 **dBc**와 플래그
  - `EXCEEDS_LIMIT`: 기준 초과
  - `HARMONIC_n`: 캐리어 n차 고조파 위치
  - `SYMMETRIC_PAIR`: 캐리어 기준 대칭 쌍(혼변조, IQ 불균형, 클럭 누설 의심)
  - `POSSIBLE_DONGLE_XTAL_SPUR`: 28.8 MHz(V4 기준 클럭) 배수 → 동글 자체 신호일 수 있음
  - `DONGLE_INTERNAL`: 50Ω 종단 스캔(아래)에도 보이는 신호 → 동글 내부 신호
- `--fail-on-violation`이면 위반이 있을 때 종료코드 1(스크립트 자동화용)

### 2. 간섭원 탐지
```bash
# (a) 정상 상태(또는 간섭이 없을 때) 기준 스캔
python -m rfscan scan --start 880M --stop 915M --tag base_880_915
# (b) 문제 발생 시 비교: 기준보다 6dB 이상 올라간 구간을 보고
python -m rfscan compare --baseline results/base_880_915.csv --delta 6
# (c) 간헐 간섭: 반복 감시하며 이벤트를 CSV로 기록 (Ctrl+C로 종료)
python -m rfscan monitor --start 880M --stop 915M --baseline results/base_880_915.csv --interval 10
```
`scan`만 해도 노이즈 플로어보다 `--threshold`(기본 10 dB) 이상 높은 신호를 모두 나열합니다.
간섭원 방향을 찾을 때는 지향성 안테나(야기 등)를 돌려 가며 `snr` 또는 `scan`의 레벨 변화를
비교하세요.

### 3. SNR 간이 측정
```bash
python -m rfscan snr --freq 940M --bw 9M                           # 광대역 캐리어
python -m rfscan snr --freq 951.2M --bw 10k                        # CW/협대역
python -m rfscan snr --freq 940M --bw 9M --noise-ref 960M 965M     # 잡음 기준 구간 직접 지정
```
SNR = (대역 내 전력 − 잡음 전력) / 잡음 전력. 잡음은 기본적으로 대역 양옆 인접 구간 중 더
조용한 쪽에서 추정합니다. 인접 채널에 다른 캐리어가 있으면 `--noise-ref`로 빈 구간을
지정하세요. 수신기 잡음보다 낮은 외부 잡음은 측정할 수 없으므로 결과는 "수신 지점에서 이 동글로
본 SNR"입니다.

---

## 권장 측정 절차 (동글 자체 신호와 실제 신호 구분)

1. **50Ω 종단 스캔**: 안테나 대신 50Ω 터미네이터를 달고 같은 이득으로 스캔합니다.
   ```bash
   python -m rfscan scan --start 880M --stop 960M --gain 30 --tag term_30dB
   ```
   이후 `scan`/`spur`에 `--terminated results/term_30dB.csv`를 주면 동글 내부 신호에
   `DONGLE_INTERNAL`이 표시됩니다.
2. **이득 변화 확인(과입력 판별)**: 의심 스퓨리어스가 보이면 이득을 10 dB 낮추거나 감쇠기를
   추가해 다시 측정합니다. 실제 신호는 캐리어와 같은 양만큼 내려가 **dBc가 그대로**이고,
   동글에서 생긴 혼변조는 훨씬 많이 내려갑니다(입력 10 dB ↓ → 3차 혼변조 약 30 dB ↓). dBc가 크게 바뀌면 동글
   과입력입니다.
3. **같은 조건으로 반복**: 비교 측정은 위치, 안테나, 케이블, 이득, nfft를 고정합니다.

---

## 파일 구성
```
rfscan/
  source.py    RTL-SDR V4 입력(pyrtlsdr) + 시뮬레이터
  dsp.py       Welch PSD, 노이즈 플로어, 대역 전력
  sweep.py     튜너 스텝 스윕 + 스티칭(가장자리 롤오프, DC 누설 제외)
  analysis.py  신호 검출, 스퓨리어스/ACLR, SNR, 기준 비교
  report.py    CSV/JSON 저장, 그래프
  cli.py       명령행
examples/kt_900m_site.json  시뮬레이터 시나리오 예시
tests/                      pytest (하드웨어 불필요)
```
테스트: `pip install pytest && python -m pytest tests`

### 측정 정확도 메모
- 레벨은 Hann 창 기준으로 보정되어 CW 신호의 피크 bin 값이 신호 전력과 같고, 대역 전력은 bin
  합을 ENBW(1.5)로 나눠 구합니다. 시뮬레이터 테스트에서 dBc와 SNR은 ±1 dB 이내로 맞습니다.
- 튜닝 중심(DC)의 LO 누설 스파이크는 스텝을 반씩 겹쳐 찍고, 각 주파수를 DC에서 떨어진
  캡처 값으로 채워 제거합니다. 그래서 스캔 시간은 단순 스윕의 약 2배입니다(50 MHz 기준 수 초).
