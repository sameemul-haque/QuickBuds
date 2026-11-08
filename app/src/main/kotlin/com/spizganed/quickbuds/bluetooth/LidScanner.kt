package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.IntentCompat
import com.spizganed.quickbuds.ui.ConnectPopup

/**
 * Lid-open detection from the buds' BLE advertisement.
 *
 * With the case closed the buds are silent. The moment the lid opens they advertise, about 2 s before the
 * classic (ACL) link to the phone comes up. Captured from OPPO Enco Buds2:
 *
 *   manufacturer data, company 0x079A (OPPO):
 *   10 48 06 3f 00 01 98 | 05 | bc 9a 78 56 34 12 | 00 | a1 a1 b5 02
 *   0                  6   7    8 ............ 13   14   15 16
 *                          |    the buds' public address, byte-reversed
 *                          state (05 / 00 / 04 / 01, not decoded yet)
 *                                                   14: 00 = not connected to a phone, 01 = connected
 *                                                   15, 16: one per bud; the first packets after the lid opens
 *                                                   come from each bud alone (00 a1 / a1 00)
 *
 * When the lid closes the buds send the same "not connected" advert for about 5 s, starting 4-6 s before the link to the
 * phone drops. The service ignores adverts while the link is up and for a moment after it drops (BudsService.earlyPopup).
 *
 * The scan is a PendingIntent scan with a hardware filter on the address and byte 14, so it costs nothing while the
 * buds are quiet and it keeps running when the app process is gone. Android ends it when Bluetooth turns off, so
 * [ensure] is called again from the service and the Bluetooth receivers.
 *
 * Only OPPO Enco Buds2 is captured. Another model may send a different layout or none: then the filter never
 * matches, nothing happens, and the popup shows on the slower ACL / RFCOMM signals instead. On Android 8-11 a
 * BLE scan also needs the location permission and location on; without them the same fallback applies.
 */
@SuppressLint("MissingPermission")
object LidScanner {

    const val COMPANY_OPPO = 0x079A
    private const val ADDR_AT = 8
    private const val CONN_AT = 14
    private const val TAG = "BudsConn"

    /** Android allows 5 scan starts per 30 s; one per minute is plenty, the scan stays registered. */
    private const val MIN_RESTART_MS = 60_000L
    @Volatile private var lastStart = 0L
    @Volatile private var lastAddress: String? = null

    private fun pending(c: Context) = PendingIntent.getBroadcast(
        c, 0, Intent(c, LidScanReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** The address as it appears in the advert: reversed bytes. Null for a malformed address. */
    fun addressBytes(address: String): ByteArray? = runCatching {
        address.split(":").map { it.toInt(16).toByte() }.takeIf { it.size == 6 }?.reversed()?.toByteArray()
    }.getOrNull()

    /** True for an advert of these buds that says "not connected to a phone": the lid just opened (or just closed). */
    fun isFreeAdvert(data: ByteArray, address: String): Boolean {
        val addr = addressBytes(address) ?: return false
        if (data.size <= CONN_AT) return false
        for (i in 0 until 6) if (data[ADDR_AT + i] != addr[i]) return false
        return data[CONN_AT].toInt() == 0
    }

    /** Start (or restart after a Bluetooth restart or a new buds address) the background scan. */
    fun ensure(context: Context, force: Boolean = false) {
        val c = context.applicationContext
        // No scan unless the popup can show: the switch is on and Display over other apps is allowed.
        if (!ConnectPopup.ready(c)) { stop(c); return }
        val address = BudsDevice.savedAddress(c) ?: return
        val now = System.currentTimeMillis()
        if (!force && address == lastAddress && now - lastStart < MIN_RESTART_MS) return
        val scanner = scanner(c) ?: return
        val addr = addressBytes(address) ?: return
        // Filter bytes 8..14: the address and "not connected". The data excludes the 2-byte company id.
        val data = ByteArray(CONN_AT + 1)
        val mask = ByteArray(CONN_AT + 1)
        for (i in 0 until 6) { data[ADDR_AT + i] = addr[i]; mask[ADDR_AT + i] = 0xFF.toByte() }
        data[CONN_AT] = 0; mask[CONN_AT] = 0xFF.toByte()
        val filter = ScanFilter.Builder().setManufacturerData(COMPANY_OPPO, data, mask).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try {
            val pi = pending(c)
            runCatching { scanner.stopScan(pi) }
            val rc = scanner.startScan(listOf(filter), settings, pi)
            lastStart = now; lastAddress = address
            Log.d(TAG, "[LID] scan started for ${address.takeLast(5)} rc=$rc")
        } catch (e: Exception) {
            Log.w(TAG, "[LID] scan start failed: $e")
        }
    }

    fun stop(context: Context) {
        val c = context.applicationContext
        runCatching { scanner(c)?.stopScan(pending(c)) }
        lastStart = 0L
    }

    private fun scanner(c: Context): BluetoothLeScanner? {
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bluetoothLeScanner
    }
}

/** Gets the scan results of [LidScanner] and starts the early popup. Runs even when the app process was gone. */
class LidScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val err = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (err != 0) { Log.w("BudsConn", "[LID] scan error $err"); return }
        val results = IntentCompat.getParcelableArrayListExtra(intent, BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
            ?: return
        val address = BudsDevice.savedAddress(context) ?: return
        val hit = results.any { r ->
            r.scanRecord?.getManufacturerSpecificData(LidScanner.COMPANY_OPPO)?.let { LidScanner.isFreeAdvert(it, address) } == true
        }
        if (!hit) return
        // One advert every ~35 ms: act on the first, then let the burst pass.
        val now = System.currentTimeMillis()
        if (now - lastHit < 2_000L) { lastHit = now; return }
        lastHit = now
        if (!BudsService.backgroundAllowed(context)) return
        Log.d("BudsConn", "[LID] lid advert seen")
        startNoThrow({
            context.startForegroundService(Intent(context, BudsService::class.java)
                .setAction(BudsService.ACTION_EARLY_POPUP)
                .putExtra(BudsService.EXTRA_FROM_BLE, true))
        }) { PacketLogger.error("Lid popup start", it) }
    }

    companion object {
        @Volatile private var lastHit = 0L
    }
}
