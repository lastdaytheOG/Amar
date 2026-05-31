package com.amar.vault.agent.validation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.amar.vault.agent.perception.MatchStrategy
import com.amar.vault.agent.perception.UiSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Phase 7A — Pickup Validation.
 *
 * After destination selection succeeds in step 6, validate that the Rapido
 * pickup_text matches the user's actual GPS location. Flag suspicious pickups
 * but do NOT auto-correct (correction is Phase 7B).
 *
 * Suspicious = city mismatch OR distance > 500m.
 * Unavailable = GPS off / no permission / no last fix. Skip silently.
 */
object PickupValidator {

    private const val TAG = "PickupValidator"
    private const val DISTANCE_THRESHOLD_METERS = 500f
    private const val LAST_KNOWN_MAX_AGE_MS = 30_000L
    private const val FRESH_FIX_TIMEOUT_MS = 5_000L

    sealed class Result {
        object Ok : Result()
        data class Suspicious(
            val reason: String,
            val pickupAddress: String,
            val pickupLocality: String?,
            val gpsLocality: String?,
            val distanceMeters: Float?
        ) : Result()
        data class Unavailable(val reason: String) : Result()
    }

    /**
     * Extract pickup address string from snapshot via resource-id = "pickup_text".
     */
    fun extractPickupAddress(snapshot: UiSnapshot): String? {
        val matching = snapshot.elements.filter {
            it.resourceId?.contains("pickup", ignoreCase = true) == true ||
                    it.contentDesc?.contains("pickup location", ignoreCase = true) == true
        }
        android.util.Log.i(TAG, "extractPickupAddress: snapshot has ${snapshot.elements.size} elements; pickup-related count=${matching.size}")

        val node = snapshot.findFirst("pickup_text", MatchStrategy.RESOURCE_ID) ?: return null
        val raw = node.contentDesc ?: node.text ?: return null
        var s = raw.trim()
        val prefix = "Pickup Location is "
        if (s.startsWith(prefix, ignoreCase = true)) s = s.substring(prefix.length).trim()
        val suffix = ". Double tap to change"
        if (s.endsWith(suffix, ignoreCase = true)) s = s.substring(0, s.length - suffix.length).trim()
        return s.ifBlank { null }
    }

    /**
     * Extract city name from a comma-separated Indian-format address.
     */
    fun extractLocality(address: String): String? {
        val parts = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        // Drop trailing country ONLY if it matches a known country name/code.
        val knownCountries = setOf("india", "in", "bharat")
        val withoutCountry = if (parts.size > 1 &&
            parts.last().lowercase() in knownCountries
        ) {
            parts.dropLast(1)
        } else parts

        // Find the rightmost segment with NO digits (i.e. not state+pin).
        // That's the city.
        return withoutCountry.reversed().firstOrNull { seg ->
            seg.isNotBlank() && !seg.any { it.isDigit() }
        }
    }

    /**
     * Main entry point.
     */
    suspend fun validate(context: Context, snapshot: UiSnapshot): Result {
        val emptyPickupHints = listOf(
            "can't find you", "cant find you", "can\u2019t find you",
            "enter your pickup", "set pickup", "add pickup"
        )
        val hasEmptyPickupBanner = snapshot.elements.any { el ->
            val tx = el.text?.lowercase() ?: ""
            val cd = el.contentDesc?.lowercase() ?: ""
            emptyPickupHints.any { hint -> tx.contains(hint) || cd.contains(hint) }
        }
        if (hasEmptyPickupBanner) {
            return Result.Suspicious(
                reason = "pickup_field_empty",
                pickupAddress = "",
                pickupLocality = null,
                gpsLocality = null,
                distanceMeters = null
            )
        }

        val pickupAddr = extractPickupAddress(snapshot)
            ?: return Result.Unavailable("pickup_text node not found or empty")
        val pickupLoc = extractLocality(pickupAddr)
        android.util.Log.i(TAG, "pickup address='$pickupAddr' locality='$pickupLoc'")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.Unavailable("location_permission_not_granted")
        }

        val gpsFix = fetchGpsLocation(context)
            ?: return Result.Unavailable("no_gps_fix_available")

        val gpsLoc = reverseGeocodeLocality(context, gpsFix)

        if (pickupLoc != null && gpsLoc != null &&
            !pickupLoc.equals(gpsLoc, ignoreCase = true)
        ) {
            return Result.Suspicious(
                reason = "city_mismatch",
                pickupAddress = pickupAddr,
                pickupLocality = pickupLoc,
                gpsLocality = gpsLoc,
                distanceMeters = null
            )
        }

        val pickupLatLon = forwardGeocode(context, pickupAddr)
        if (pickupLatLon != null) {
            val dist = gpsFix.distanceTo(pickupLatLon)
            if (dist > DISTANCE_THRESHOLD_METERS) {
                return Result.Suspicious(
                    reason = "distance_too_far",
                    pickupAddress = pickupAddr,
                    pickupLocality = pickupLoc,
                    gpsLocality = gpsLoc,
                    distanceMeters = dist
                )
            }
        }

        return Result.Ok
    }

    @SuppressLint("MissingPermission")
    internal suspend fun fetchGpsLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val candidates = listOfNotNull(
            try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Throwable) { null },
            try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Throwable) { null }
        )
        val fresh = candidates
            .filter { System.currentTimeMillis() - it.time <= LAST_KNOWN_MAX_AGE_MS }
            .maxByOrNull { it.time }
        if (fresh != null) return fresh

        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    val provider = when {
                        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                        else -> { if (cont.isActive) cont.resume(null); return@suspendCancellableCoroutine }
                    }
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            try { lm.removeUpdates(this) } catch (_: Throwable) {}
                            if (cont.isActive) cont.resume(loc)
                        }
                        override fun onProviderDisabled(p: String) {}
                        override fun onProviderEnabled(p: String) {}
                        @Deprecated("API <29")
                        override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    }
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    cont.invokeOnCancellation { try { lm.removeUpdates(listener) } catch (_: Throwable) {} }
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    internal fun reverseGeocodeLocality(context: Context, location: Location): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addrs = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            addrs?.firstOrNull()?.locality
                ?: addrs?.firstOrNull()?.subAdminArea
                ?: addrs?.firstOrNull()?.adminArea
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "reverse geocode failed: ${t.message}")
            null
        }
    }



    private fun forwardGeocode(context: Context, address: String): Location? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addrs = geocoder.getFromLocationName(address, 1)
            val first = addrs?.firstOrNull() ?: return null
            Location("forward_geocode").apply {
                latitude = first.latitude
                longitude = first.longitude
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "forward geocode failed: ${t.message}")
            null
        }
    }
}