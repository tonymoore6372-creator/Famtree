package com.familytree.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream

sealed class SyncState {
    object Idle : SyncState()
    object Listening : SyncState()      // waiting for a relative to connect to us
    object Connecting : SyncState()     // we are connecting out to a relative
    object Connected : SyncState()
    object Exchanging : SyncState()
    data class Success(val peopleAdded: Int, val peopleUpdated: Int, val relationshipsAdded: Int) : SyncState()
    data class Error(val message: String) : SyncState()
}

/**
 * Handles peer-to-peer sync between two phones over classic Bluetooth (RFCOMM),
 * so two relatives standing near each other can merge family trees with no
 * internet connection required.
 *
 * One phone calls [startHosting] (discoverable, listening for a connection),
 * the other calls [connectToDevice] after picking the host from paired/scanned
 * devices. Once connected, both sides exchange their full [SyncPayload] and
 * merge via [com.familytree.app.data.FamilyTreeRepository.mergeSnapshot].
 */
class BluetoothSyncManager(
    private val context: Context,
    private val deviceId: String,
    private val onPayloadReceived: suspend (SyncPayload) -> com.familytree.app.data.MergeResult,
    private val buildLocalPayload: suspend () -> SyncPayload
) {
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun pairedDevices(): List<BluetoothDevice> {
        val bonded = adapter?.bondedDevices ?: emptySet()
        return bonded.toList()
    }

    fun isBluetoothAvailable(): Boolean = adapter != null && adapter.isEnabled

    /** Call on the device that will wait for the relative to connect to it. */
    @SuppressLint("MissingPermission")
    fun startHosting() {
        val bt = adapter ?: run {
            _state.value = SyncState.Error("Bluetooth is not available on this device")
            return
        }
        _state.value = SyncState.Listening
        scope.launch {
            try {
                serverSocket = bt.listenUsingRfcommWithServiceRecord(
                    FAMILY_TREE_SERVICE_NAME,
                    FAMILY_TREE_SERVICE_UUID
                )
                val socket = serverSocket?.accept() // blocks until a relative connects
                serverSocket?.close()
                if (socket != null) {
                    activeSocket = socket
                    _state.value = SyncState.Connected
                    exchangeData(socket)
                }
            } catch (e: IOException) {
                _state.value = SyncState.Error("Hosting failed: ${e.message}")
            }
        }
    }

    /** Call on the device that initiates the connection to a paired relative's phone. */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        _state.value = SyncState.Connecting
        scope.launch {
            try {
                adapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(FAMILY_TREE_SERVICE_UUID)
                socket.connect() // blocks until connected or throws
                activeSocket = socket
                _state.value = SyncState.Connected
                exchangeData(socket)
            } catch (e: IOException) {
                _state.value = SyncState.Error("Could not connect: ${e.message}")
            }
        }
    }

    /**
     * Both sides run the identical exchange sequence, so there's no
     * negotiation needed: each writes its payload as one line of JSON, then
     * reads one line of JSON back, then merges.
     */
    private suspend fun exchangeData(socket: BluetoothSocket) {
        _state.value = SyncState.Exchanging
        try {
            val output: OutputStream = socket.outputStream
            val reader = BufferedReader(InputStreamReader(socket.inputStream))

            val localPayload = buildLocalPayload()
            val localLine = SyncProtocol.encode(localPayload) + "\n"
            output.write(localLine.toByteArray(Charsets.UTF_8))
            output.flush()

            val remoteLine = withContext(Dispatchers.IO) { reader.readLine() }
                ?: throw IOException("Connection closed before data was received")

            val remotePayload = SyncProtocol.decode(remoteLine)
            val result = onPayloadReceived(remotePayload)

            _state.value = SyncState.Success(
                result.peopleAdded, result.peopleUpdated, result.relationshipsAdded
            )
        } catch (e: Exception) {
            _state.value = SyncState.Error("Sync failed: ${e.message}")
        } finally {
            closeConnection()
        }
    }

    fun closeConnection() {
        try { activeSocket?.close() } catch (_: IOException) {}
        try { serverSocket?.close() } catch (_: IOException) {}
        activeSocket = null
        serverSocket = null
    }

    fun reset() {
        closeConnection()
        _state.value = SyncState.Idle
    }
}
