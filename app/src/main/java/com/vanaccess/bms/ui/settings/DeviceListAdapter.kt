// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vanaccess.bms.R
import com.vanaccess.bms.ble.ScannedDevice
import com.vanaccess.bms.ble.drivers.BmsDrivers

class DeviceListAdapter(
    private val onDeviceSelected: (ScannedDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

    private val devices = mutableListOf<ScannedDevice>()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_device_name)
        val tvAddress: TextView = itemView.findViewById(R.id.tv_device_address)
        val tvRssi: TextView = itemView.findViewById(R.id.tv_device_rssi)
        val ivSignal: ImageView = itemView.findViewById(R.id.iv_signal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        val ctx = holder.itemView.context
        holder.tvName.text = if (device.name.isNotBlank() && device.name != "Unknown") device.name
            else ctx.getString(R.string.lbl_unknown_device)
        val known = knownDriver(device)
        holder.tvAddress.text = if (known != null) "${device.address}  •  ✓ ${known.displayName}" else device.address
        holder.tvAddress.setTextColor(android.graphics.Color.parseColor(if (known != null) "#43D17A" else "#9BA3B8"))
        holder.tvRssi.text = "${device.rssi} dBm"

        // Signal strength icon color
        val signalColor = when {
            device.rssi > -60 -> android.graphics.Color.parseColor("#4CAF50")
            device.rssi > -80 -> android.graphics.Color.parseColor("#FF9800")
            else -> android.graphics.Color.parseColor("#F44336")
        }
        holder.tvRssi.setTextColor(signalColor)

        holder.itemView.setOnClickListener { onDeviceSelected(device) }
    }

    override fun getItemCount() = devices.size

    fun updateDevices(newDevices: List<ScannedDevice>) {
        devices.clear()
        // Sort: supported BMS first, then named devices, then by RSSI
        devices.addAll(newDevices.sortedWith(compareByDescending<ScannedDevice> { knownDriver(it) != null }
            .thenByDescending { it.name != "Unknown" && it.name.isNotBlank() }
            .thenByDescending { it.rssi }))
        notifyDataSetChanged()
    }

    /** Driver whose name prefix matches (ignoring the very short generic "BW"/"JK" prefixes). */
    private fun knownDriver(d: ScannedDevice) =
        if (d.name.length < 4) null else BmsDrivers.all.firstOrNull { it.matchesName(d.name) }

    fun clearDevices() {
        devices.clear()
        notifyDataSetChanged()
    }
}
