# Third-party notices

## webrtlsdr (Apache License 2.0)

`core/src/main/kotlin/com/lteduenv/rxcheck/core/rtl/` (RtlCom, RtlSdr, R82xx) is a Kotlin
reimplementation whose register values, tuner initialization/calibration sequence and
multiplexer tables follow webrtlsdr 3.0.6 (https://github.com/jtarrio/webrtlsdr):

    Copyright 2024 Jacobo Tarrio Barreiro. All rights reserved.
    Copyright 2013 Google Inc. All rights reserved.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

Changes from webrtlsdr: ported to Kotlin over Android USB host APIs; I2C repeater kept
open between tuner accesses; unchanged tuner register writes skipped; PLL registers
0x14-0x16 written as one I2C burst; tuner init registers written as bursts; PLL lock
failure after the retry is reported instead of assumed locked.

## librtlsdr (GPL-2.0-or-later)

`R82xx.setBandwidth` (IF filter selection for the sample rate: register 0x0a/0x0b values,
IF low-pass corner table and IF frequency calculation) follows `r82xx_set_bandwidth` in
librtlsdr / rtl-sdr-blog (https://github.com/rtlsdrblog/rtl-sdr-blog):

    Copyright (C) 2013 Mauro Carvalho Chehab
    Copyright (C) 2013 Steve Markgraf
    Licensed under the GNU General Public License, version 2 or (at your option) any later version.

Because of this part, the app as a whole is distributed under GPL-3.0-or-later terms
(Apache-2.0 code may be combined into GPL-3.0 works). The unit test
`MergeFeaturesTest.ifBandwidthMatchesLibrtlsdr` checks the port against values produced
by the original C function.
