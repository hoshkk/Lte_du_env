package com.lteduenv.ktdebug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lteduenv.ktdebug.data.EquipmentRepository
import com.lteduenv.ktdebug.data.MockDebugDataGenerator
import com.lteduenv.ktdebug.ui.BandKey
import com.lteduenv.ktdebug.ui.BandListScreen
import com.lteduenv.ktdebug.ui.CompareScreen
import com.lteduenv.ktdebug.ui.DebugScreen
import com.lteduenv.ktdebug.ui.NearbyScreen
import com.lteduenv.ktdebug.ui.Screen
import com.lteduenv.ktdebug.ui.theme.KtDebugViewerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val equipmentRepository = EquipmentRepository(applicationContext)
        val generator = MockDebugDataGenerator(equipmentRepository)

        setContent {
            KtDebugViewerTheme {
                KtDebugApp(equipmentRepository, generator)
            }
        }
    }
}

@Composable
private fun KtDebugApp(
    equipmentRepository: EquipmentRepository,
    generator: MockDebugDataGenerator
) {
    var screen by remember { mutableStateOf<Screen>(Screen.BandList) }
    var selectedForCompare by remember { mutableStateOf(setOf<BandKey>()) }

    when (val current = screen) {
        is Screen.BandList -> BandListScreen(
            selectedForCompare = selectedForCompare,
            onToggleSelect = { key ->
                selectedForCompare = if (key in selectedForCompare) {
                    selectedForCompare - key
                } else {
                    selectedForCompare + key
                }
            },
            onOpenDebug = { key -> screen = Screen.Debug(key) },
            onCompare = { screen = Screen.Compare(selectedForCompare.toList()) },
            onOpenNearby = { screen = Screen.Nearby }
        )

        is Screen.Debug -> DebugScreen(
            bandKey = current.bandKey,
            equipmentRepository = equipmentRepository,
            generator = generator,
            onBack = { screen = Screen.BandList }
        )

        is Screen.Compare -> CompareScreen(
            bandKeys = current.bandKeys,
            equipmentRepository = equipmentRepository,
            generator = generator,
            onBack = { screen = Screen.BandList }
        )

        is Screen.Nearby -> NearbyScreen(
            equipmentRepository = equipmentRepository,
            onBack = { screen = Screen.BandList }
        )
    }
}
