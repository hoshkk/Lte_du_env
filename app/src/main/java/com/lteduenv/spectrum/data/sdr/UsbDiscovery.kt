package com.lteduenv.spectrum.data.sdr

/** Re-read Android enumeration for at most 3 seconds; never retain a stale device. */
internal suspend fun <T> discoverUsb(
    snapshot: () -> List<T>,
    supported: (T) -> Boolean,
    describe: (T) -> String,
    pause: suspend () -> Unit,
    checkActive: () -> Unit
): T {
    for (attempt in 0..12) {
        checkActive()
        val all = snapshot()
        val matches = all.filter(supported)
        check(matches.size <= 1) { "RTL-SDR은 한 대만 연결하세요" }
        if (matches.size == 1) return matches.single()
        if (attempt == 12) {
            error(if (all.isEmpty()) "USB 인식 없음 (3초 확인). OTG 연결을 뺐다가 다시 연결하세요"
                  else "지원 RTL-SDR 없음 · 감지 USB: " + all.joinToString { describe(it) })
        }
        pause()
    }
    error("USB 검색 종료")
}
