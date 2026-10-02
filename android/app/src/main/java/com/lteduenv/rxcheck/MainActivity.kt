package com.lteduenv.rxcheck

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
    private var pendingCsv: String? = null

    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val text = pendingCsv
        pendingCsv = null
        if (uri == null || text == null) return@registerForActivityResult
        runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("파일을 열 수 없습니다") }
            .onSuccess { toast("CSV 저장 완료") }
            .onFailure { toast("저장 오류: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.running) {
                if (state.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4C8DFF), secondary = Color(0xFFF5C542))) {
                MainScreen(state = state, vm = vm, onSaveCsv = ::save, onShareCsv = ::share)
            }
        }
    }

    /** Leaving the app releases the dongle; the screen keeps the last trace. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && vm.state.value.running) vm.stop()
    }

    private fun save() {
        val text = vm.csvText() ?: return toast("저장할 완료 스윕이 없습니다")
        pendingCsv = text
        saveCsv.launch(vm.csvFileName())
    }

    private fun share() {
        val file = runCatching { vm.csvFile() }.getOrNull() ?: return toast("공유할 완료 스윕이 없습니다")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "CSV 공유"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
