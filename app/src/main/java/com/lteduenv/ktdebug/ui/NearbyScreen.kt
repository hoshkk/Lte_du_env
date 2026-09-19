package com.lteduenv.ktdebug.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lteduenv.ktdebug.data.CurrentLocationProvider
import com.lteduenv.ktdebug.data.EquipmentRepository
import com.lteduenv.ktdebug.model.NearbyResult

private val RADIUS_OPTIONS_METERS = listOf(250.0, 500.0, 1000.0, 2000.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    equipmentRepository: EquipmentRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val locationProvider = remember { CurrentLocationProvider(context) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPermission = granted }

    var radiusMeters by remember { mutableStateOf(RADIUS_OPTIONS_METERS[1]) }
    var isLoading by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var results by remember { mutableStateOf<List<NearbyResult>>(emptyList()) }
    var refreshCounter by remember { mutableStateOf(0) }

    LaunchedEffect(hasLocationPermission, refreshCounter) {
        if (!hasLocationPermission) return@LaunchedEffect
        isLoading = true
        val location = locationProvider.getCurrentLocation()
        currentLocation = location
        results = if (location != null) {
            equipmentRepository.nearby(location, radiusMeters)
        } else {
            emptyList()
        }
        isLoading = false
    }

    LaunchedEffect(radiusMeters, currentLocation) {
        val location = currentLocation ?: return@LaunchedEffect
        results = equipmentRepository.nearby(location, radiusMeters)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("내 주변 PCI/장비") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            if (!hasLocationPermission) {
                Text("주변 실제 장비/PCI를 보려면 위치 권한이 필요합니다.")
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("위치 권한 허용")
                }
                return@Scaffold
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RADIUS_OPTIONS_METERS.forEach { radius ->
                    FilterChip(
                        selected = radiusMeters == radius,
                        onClick = { radiusMeters = radius },
                        label = { Text(radiusLabel(radius)) }
                    )
                }
            }

            Button(
                onClick = { refreshCounter++ },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text("현재 위치로 새로고침")
            }

            when {
                isLoading -> Text("위치 확인 중...")
                currentLocation == null -> Text("현재 위치를 가져오지 못했습니다. GPS를 켜고 다시 시도하세요.")
                results.isEmpty() -> Text("반경 ${radiusLabel(radiusMeters)} 안에 등록된 장비가 없습니다.")
                else -> {
                    Text("반경 ${radiusLabel(radiusMeters)} 안 ${results.size}건 (가까운 순)")
                    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                        items(results) { result -> NearbyRow(result) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(result: NearbyResult) {
    val record = result.record
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text("${record.name} [${record.category}/${record.kind}]")
            Text("PCI: ${record.pciList.joinToString(", ")}")
            Text("거리: ${"%.0f".format(result.distanceMeters)}m")
            Text("주소: ${record.addressLine}")
        }
    }
}

private fun radiusLabel(meters: Double): String {
    if (meters < 1000.0) return "${meters.toInt()}m"
    val km = meters / 1000.0
    return if (km == km.toInt().toDouble()) "${km.toInt()}km" else "${km}km"
}
