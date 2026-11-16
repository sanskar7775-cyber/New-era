package com.example.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.example.data.model.DeviceTelemetry
import com.example.data.model.InstalledAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class DeviceController(private val context: Context) {

    private val appContext = context.applicationContext

    suspend fun getInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
        resolveInfos
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                    .ifBlank { info.activityInfo.packageName }
                val icon = try {
                    info.loadIcon(pm)
                } catch (_: Exception) {
                    null
                }
                InstalledAppInfo(
                    packageName = info.activityInfo.packageName,
                    appName = label,
                    icon = icon
                )
            }
            .sortedBy { it.appName.lowercase(Locale.ROOT) }
    }

    suspend fun getDeviceTelemetry(): DeviceTelemetry = withContext(Dispatchers.IO) {
        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
        val networkStatus = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi Connected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular Connected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet Connected"
            else -> "Online"
        }

        val stat = StatFs(Environment.getDataDirectory().path)
        val availBytes = stat.availableBytes
        val availGb = String.format(Locale.US, "%.1f GB", availBytes / (1024.0 * 1024.0 * 1024.0))

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 7
        val maxVol = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 10).coerceAtLeast(1)
        val volPct = (currentVol * 100) / maxVol

        val apps = getInstalledApps()

        DeviceTelemetry(
            deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            batteryPercent = batteryPct,
            isCharging = isCharging,
            networkStatus = networkStatus,
            availableStorageGb = availGb,
            mediaVolumePercent = volPct,
            installedAppCount = apps.size
        )
    }

    fun launchPackage(packageName: String): Result<String> {
        return try {
            val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
                Result.success("Launched $packageName")
            } else {
                Result.failure(IllegalStateException("App $packageName cannot be launched directly."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun launchAppByName(query: String): Result<String> {
        val normalized = query.trim().lowercase(Locale.ROOT)
        val apps = getInstalledApps()
        val exact = apps.firstOrNull { it.appName.lowercase(Locale.ROOT) == normalized }
        val partial = exact ?: apps.firstOrNull {
            it.appName.lowercase(Locale.ROOT).contains(normalized) ||
                it.packageName.lowercase(Locale.ROOT).contains(normalized)
        }
        return if (partial != null) {
            launchPackage(partial.packageName).map { "Opened ${partial.appName}" }
        } else {
            Result.failure(IllegalArgumentException("No installed app matching '$query' found."))
        }
    }

    fun toggleFlashlight(enabled: Boolean): Result<String> {
        return try {
            val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (flashCameraId != null) {
                cameraManager.setTorchMode(flashCameraId, enabled)
                Result.success(if (enabled) "Flashlight turned ON" else "Flashlight turned OFF")
            } else {
                Result.failure(IllegalStateException("No flashlight hardware detected on this device."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openSystemSettings(section: String): Result<String> {
        val action = when (section.lowercase(Locale.ROOT)) {
            "wifi", "wi-fi", "internet" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            Result.success("Opened $section settings")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun setTimer(seconds: Int, label: String): Result<String> {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
                putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "New Era Timer" })
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            Result.success("Timer set for $seconds seconds ($label)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openDialer(phoneNumber: String = ""): Result<String> {
        return try {
            val uri = if (phoneNumber.isBlank()) Uri.parse("tel:") else Uri.parse("tel:${Uri.encode(phoneNumber)}")
            val intent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            Result.success("Opened phone dialer")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openCalendarEvent(title: String = "New Era Event"): Result<String> {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            Result.success("Opened calendar event composer")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openBrowserUrl(url: String): Result<String> {
        return try {
            val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            Result.success("Opened $normalized")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
