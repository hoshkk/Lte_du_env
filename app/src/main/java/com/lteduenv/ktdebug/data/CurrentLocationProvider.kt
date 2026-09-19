package com.lteduenv.ktdebug.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Thin wrapper over the fused location client for a single one-shot GPS fix. */
class CurrentLocationProvider(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? = suspendCancellableCoroutine { continuation ->
        val cancellationSource = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()

        client.getCurrentLocation(request, cancellationSource.token)
            .addOnSuccessListener { location -> if (continuation.isActive) continuation.resume(location) }
            .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }

        continuation.invokeOnCancellation { cancellationSource.cancel() }
    }
}
