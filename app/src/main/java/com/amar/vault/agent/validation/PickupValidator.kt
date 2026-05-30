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
     * The contentDesc wraps the address with a fixed prefix/suffix; we strip both.
     *
     * Example contentDesc:
     *   "Pickup Location is 86, Ashok Vihar, Arjun Nagar, Jaipur, Rajasthan 302015, India. Double tap to change"
     * Returns:
     *   "86, Ashok Vihar, Arjun Nagar, Jaipur, Rajasthan 302015, India"
     */
    fun extractPickupAddress(snapshot: UiSnapshot): String? {
        // Diagnostic: log all resourceIds containing "pickup" in current snapshot
        val matching = snapshot.elements.filter {
            it.resourceId?.contains("pickup", ignoreCase = true) == true ||
                    it.contentDesc?.contains("pickup location", ignoreCase = true) == true
        }
        android.util.Log.i(TAG, "extractPickupAddress: snapshot has ${snapshot.elements.size} elements; pickup-related count=${matching.size}")
        for (el in matching.take(5)) {
            android.util.Log.i(TAG, "  candidate: rid='${el.resourceId}' cd='${el.contentDesc?.take(60)}' text='${el.text?.take(60)}'")
        }
        // Diagnostic: when pickup-related = 0, dump everything to learn the screen state
        if (matching.isEmpty()) {
            for (el in snapshot.elements) {
                val rid = el.resourceId
                val cd = el.contentDesc?.take(80)
                val tx = el.text?.take(80)
                if (rid != null || cd != null || tx != null) {
                    android.util.Log.i(TAG, "  all-el: rid='$rid' cd='$cd' text='$tx'")
                }
            }
        }
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
     *
     * Format observed: "<house>, <colony>, <neighborhood>, <city>, <state> <pin>, India"
     * Strategy: drop final country segment, find segment that doesn't look like
     * state+pin (no digits), pick the rightmost such segment as the city.
     */
    fun extractLocality(address: String): String? {
        val parts = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        // Drop trailing country (India / IN / etc) — anything without digits and
        // shorter than 12 chars at the end.
        val withoutCountry = if (parts.size > 1 &&
            parts.last().length <= 12 &&
            !parts.last().any { it.isDigit() }
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
     * Main entry point. Reads pickup from snapshot, fetches GPS, reverse
     * geocodes, compares, returns structured result.
     */
    suspend fun validate(context: Context, snapshot: UiSnapshot): Result {
        // Phase 7A: detect empty-pickup state explicitly. Rapido shows a
        // banner like "Uh oh, we can't find you! Enter your pickup location"
        // when GPS fix never landed. In that case the booking can't proceed,
        // so we flag it as Suspicious (stop the flow) rather than Unavailable.
        // Includes both straight apostrophe (') and Unicode right-single-quote (\u2019).
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
            android.util.Log.w(TAG, "validate: empty-pickup banner detected; flagging as Suspicious")
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

        // Permission check (manifest declares; runtime grant required on API 23+).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.Unavailable("location_permission_not_granted")
        }

        val gpsFix = fetchGpsLocation(context)
            ?: return Result.Unavailable("no_gps_fix_available")
        android.util.Log.i(TAG, "gps fix lat=${gpsFix.latitude} lon=${gpsFix.longitude} age=${(System.currentTimeMillis() - gpsFix.time)/1000}s")

        val gpsLoc = reverseGeocodeLocality(context, gpsFix)
        android.util.Log.i(TAG, "gps locality='$gpsLoc'")

        // City mismatch check (highest signal)
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

        // Distance check. Need to geocode the pickup address to lat/lon first.
        val pickupLatLon = forwardGeocode(context, pickupAddr)
        if (pickupLatLon != null) {
            val dist = gpsFix.distanceTo(pickupLatLon)
            android.util.Log.i(TAG, "pickup-to-gps distance=${dist}m")
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
    private suspend fun fetchGpsLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        // Try last-known from GPS and Network providers, pick freshest.
        val candidates = listOfNotNull(
            try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Throwable) { null },
            try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Throwable) { null }
        )
        val fresh = candidates
            .filter { System.currentTimeMillis() - it.time <= LAST_KNOWN_MAX_AGE_MS }
            .maxByOrNull { it.time }
        if (fresh != null) return fresh

        // Last-known stale or absent. Request a single fresh fix.
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

    private fun reverseGeocodeLocality(context: Context, location: Location): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addrs = if (Build.VERSION.SDK_INT >= 33) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            }
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