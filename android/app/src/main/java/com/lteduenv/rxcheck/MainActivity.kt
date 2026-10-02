package com.lteduenv.rxcheck

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lteduenv.rxcheck.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val vm: MeasureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.running) {
                if (state.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4C8DFF), secondary = Color(0xFFF5C542))) {
                MainScreen(
                    state = state,
                    onStart = vm::start,
                    onStop = vm::stop,
                    onMode = { vm.selectMode(it) },
                    onBand = { vm.selectBand(it) },
                    onApply = vm::apply,
                    onHoldToggle = { vm.apply(state.settings.copy(maxHold = !state.settings.maxHold)) },
                    onResetHold = vm::resetHold,
                    onSaveBaseline = vm::saveBaseline,
                    onClearBaseline = vm::clearBaseline,
                    onExport = ::share,
                    onDismissError = vm::dismissError,
                )
            }
        }
    }

    private fun share() {
        val file = runCatching { vm.exportCsv() }.getOrNull()
        if (file == null) {
            Toast.makeText(this, "내보낼 완료 스윕이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "CSV 공유"))
    }
}
