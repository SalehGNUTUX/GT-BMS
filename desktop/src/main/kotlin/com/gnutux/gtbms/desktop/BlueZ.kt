// SPDX-License-Identifier: GPL-3.0-or-later
@file:Suppress("FunctionName")

package com.gnutux.gtbms.desktop

import com.vanaccess.bms.ble.BmsLink
import com.vanaccess.bms.ble.drivers.GattProfile
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.bluez.Adapter1")
@JvmSuppressWildcards
interface Adapter1 : DBusInterface {
    fun StartDiscovery()
    fun StopDiscovery()
    fun SetDiscoveryFilter(filter: Map<String, Variant<*>>)
}

@DBusInterfaceName("org.bluez.Device1")
interface Device1 : DBusInterface {
    fun Connect()
    fun Disconnect()
}

@DBusInterfaceName("org.bluez.GattCharacteristic1")
@JvmSuppressWildcards
interface GattCharacteristic1 : DBusInterface {
    fun StartNotify()
    fun StopNotify()
    fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>)
}

/**
 * The computer's Bluetooth through BlueZ, the Linux Bluetooth stack, on the system D-Bus:
 * no root needed. One connection is kept open: BlueZ ends a scan or notifications when the
 * program that asked for them goes away. Read-only use: only the driver's own read commands
 * are ever written (see core BmsPoller).
 */
class BlueZ private constructor(private val bus: DBusConnection, val adapterPath: String) : AutoCloseable {

    data class Found(val address: String, val name: String, val rssi: Int?, val path: String, val connected: Boolean, val services: List<String>)

    class NoAdapter : Exception("no Bluetooth adapter")

    private val adapter: Adapter1 get() = bus.getRemoteObject(BLUEZ, adapterPath, Adapter1::class.java)

    private fun objects(): Map<DBusPath, Map<String, Map<String, Variant<*>>>> =
        bus.getRemoteObject(BLUEZ, "/", ObjectManager::class.java).GetManagedObjects()

    /** Low-energy devices seen by BlueZ (during or after a scan). */
    fun devices(): List<Found> = objects().mapNotNull { (path, ifs) ->
        val d = ifs["org.bluez.Device1"] ?: return@mapNotNull null
        if (!path.path.startsWith("$adapterPath/")) return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        Found(d.str("Address") ?: return@mapNotNull null, d.str("Name") ?: d.str("Alias") ?: "",
            (d["RSSI"]?.value as? Number)?.toInt(), path.path, d["Connected"]?.value == true,
            (d["UUIDs"]?.value as? List<String>).orEmpty())
    }

    /** Scans for [seconds] (BLE only) and returns what answered, nearest first. */
    fun scan(seconds: Int, onUpdate: (List<Found>) -> Unit = {}): List<Found> {
        runCatching { adapter.SetDiscoveryFilter(mapOf("Transport" to Variant("le"), "DuplicateData" to Variant(false))) }
        runCatching { adapter.StartDiscovery() }
        try {
            repeat(seconds) { Thread.sleep(1000); onUpdate(seen()) }
        } finally { runCatching { adapter.StopDiscovery() } }
        return seen()
    }

    private fun seen() = devices().filter { it.rssi != null || it.connected }.sortedByDescending { it.rssi ?: -200 }

    /** A link to the battery at [address] using [profile]'s characteristics; throws if it cannot. */
    fun link(address: String, profile: GattProfile, timeoutMs: Long = 25_000): Link {
        val dev = devices().firstOrNull { it.address.equals(address, true) }
            ?: throw IllegalStateException("not seen: $address")
        val device = bus.getRemoteObject(BLUEZ, dev.path, Device1::class.java)
        if (!dev.connected) device.Connect()
        // Wait until BlueZ has read the battery's services
        val until = System.currentTimeMillis() + timeoutMs
        while (prop(dev.path, "org.bluez.Device1", "ServicesResolved") != true) {
            if (System.currentTimeMillis() > until) { runCatching { device.Disconnect() }; throw IllegalStateException("services not resolved") }
            Thread.sleep(250)
        }
        val chars = objects().filter { it.key.path.startsWith(dev.path + "/") }.mapNotNull { (p, ifs) ->
            ifs["org.bluez.GattCharacteristic1"]?.str("UUID")?.let { it.lowercase() to p.path }
        }
        val notify = chars.firstOrNull { it.first == profile.notify.lowercase() }?.second
        val write = chars.firstOrNull { it.first == profile.write.lowercase() }?.second
        if (notify == null || write == null) { runCatching { device.Disconnect() }; throw IllegalStateException("characteristics not found") }
        return Link(dev.path, notify, write)
    }

    /** Services the battery offers (after a connection), for choosing the driver. */
    fun services(address: String): List<String> = devices().firstOrNull { it.address.equals(address, true) }?.services.orEmpty()

    private fun prop(path: String, iface: String, name: String): Any? =
        runCatching { bus.getRemoteObject(BLUEZ, path, Properties::class.java).Get<Any>(iface, name) }.getOrNull()

    inner class Link(private val devicePath: String, private val notifyPath: String, private val writePath: String) : BmsLink {
        override var onNotify: ((ByteArray) -> Unit)? = null
        @Volatile var lost = false; private set
        private val writer = bus.getRemoteObject(BLUEZ, writePath, GattCharacteristic1::class.java)
        private val notifier = bus.getRemoteObject(BLUEZ, notifyPath, GattCharacteristic1::class.java)
        private val handler = DBusSigHandler<Properties.PropertiesChanged> { sig ->
            when (sig.path) {
                notifyPath -> (sig.propertiesChanged["Value"]?.value)?.let { v -> bytes(v)?.let { onNotify?.invoke(it) } }
                devicePath -> if (sig.propertiesChanged["Connected"]?.value == false) lost = true
            }
        }

        init {
            bus.addSigHandler(Properties.PropertiesChanged::class.java, handler)
            notifier.StartNotify()
        }

        override fun write(bytes: ByteArray) {
            if (lost) return
            // "command" = write without response where the battery allows it, as phones usually do
            runCatching { writer.WriteValue(bytes, mapOf("type" to Variant("command"))) }
                .onFailure { writer.WriteValue(bytes, emptyMap()) }
        }

        override fun close() {
            runCatching { notifier.StopNotify() }
            runCatching { bus.removeSigHandler(Properties.PropertiesChanged::class.java, handler) }
            runCatching { bus.getRemoteObject(BLUEZ, devicePath, Device1::class.java).Disconnect() }
        }
    }

    override fun close() { runCatching { bus.close() } }

    companion object {
        private const val BLUEZ = "org.bluez"

        /** Opens the system bus and finds the first adapter. */
        fun open(): BlueZ {
            val bus = DBusConnectionBuilder.forSystemBus().build()
            val path = runCatching {
                bus.getRemoteObject(BLUEZ, "/", ObjectManager::class.java).GetManagedObjects()
                    .entries.firstOrNull { it.value.containsKey("org.bluez.Adapter1") }?.key?.path
            }.getOrNull()
            if (path == null) { bus.close(); throw NoAdapter() }
            return BlueZ(bus, path)
        }

        private fun Map<String, Variant<*>>.str(k: String) = this[k]?.value as? String

        /** A D-Bus "ay" value, whichever way the library hands it over. */
        internal fun bytes(v: Any): ByteArray? = when (v) {
            is ByteArray -> v
            is List<*> -> ByteArray(v.size) { (v[it] as Number).toByte() }
            is Array<*> -> ByteArray(v.size) { (v[it] as Number).toByte() }
            else -> null
        }
    }
}
