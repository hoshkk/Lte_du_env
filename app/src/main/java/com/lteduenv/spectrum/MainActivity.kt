package com.lteduenv.spectrum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lteduenv.spectrum.ui.AnalyzerColors
import com.lteduenv.spectrum.ui.SpectrumApp
import com.lteduenv.spectrum.ui.SpectrumCheckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            SpectrumCheckTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = AnalyzerColors.Background) {
                    SpectrumApp()
                }
            }
        }
    }
}
