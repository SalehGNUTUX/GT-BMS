// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.switchpanel

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.vanaccess.bms.ble.BleService
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.databinding.FragmentSwitchPanelBinding
import com.vanaccess.bms.databinding.ItemSwitchChannelBinding  // used as list element type
import com.vanaccess.bms.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SwitchPanelFragment : Fragment() {

    private var _binding: FragmentSwitchPanelBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** Per-channel view bindings indexed 0-7. */
    private lateinit var channels: List<ItemSwitchChannelBinding>

    /** True while we're programmatically updating toggle state (suppresses callbacks). */
    private var suppressToggleEvents = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSwitchPanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        // Bind the 8 included channel views
        channels = listOf(
            binding.ch1,
            binding.ch2,
            binding.ch3,
            binding.ch4,
            binding.ch5,
            binding.ch6,
            binding.ch7,
            binding.ch8,
        )

        // Setup channel names and toggle listeners
        for ((idx, ch) in channels.withIndex()) {
            val channelNum = idx + 1
            ch.chName.text = prefs.getSwitchPanelChannelName(channelNum)

            // Long-press name to rename
            ch.chName.setOnLongClickListener {
                showRenameDialog(channelNum)
                true
            }
            ch.chName.setOnClickListener { showRenameDialog(channelNum) }

            ch.chToggle.setOnCheckedChangeListener { _, isChecked ->
                if (suppressToggleEvents) return@setOnCheckedChangeListener
                toggleChannel(channelNum, isChecked)
            }
        }

        binding.btnSpAllOff.setOnClickListener {
            (activity as? MainActivity)?.getBleService()?.switchPanelPowerOff()
        }

        observeData()
    }

    private fun observeData() {
        val configured = prefs.switchPanelDeviceAddress?.isNotBlank() == true

        binding.cardSpNotConfigured.visibility = if (!configured) View.VISIBLE else View.GONE
        binding.cardSpStatus.visibility       = if (configured) View.VISIBLE else View.GONE
        for (ch in channels) ch.root.visibility = if (configured) View.VISIBLE else View.GONE
        binding.btnSpAllOff.visibility        = if (configured) View.VISIBLE else View.GONE

        if (!configured) {
            binding.tvSpUpdated.text = "Not configured"
            binding.tvSpUpdated.setTextColor(Color.parseColor("#9E9E9E"))
            return
        }

        val name = prefs.switchPanelDeviceName?.takeIf { it.isNotBlank() }
            ?: prefs.switchPanelDeviceAddress ?: "Unknown"
        binding.tvSpDeviceName.text = name

        BleService.switchPanelConnState.observe(viewLifecycleOwner) { state ->
            val (text, color) = when (state) {
                BleService.ConnectionState.CONNECTED    -> "● Connected"     to "#4CAF50"
                BleService.ConnectionState.CONNECTING   -> "● Connecting…"   to "#FF9800"
                BleService.ConnectionState.RECONNECTING -> "● Reconnecting…" to "#FF9800"
                else                                    -> "● Disconnected"  to "#F44336"
            }
            binding.tvSpUpdated.text = text
            binding.tvSpUpdated.setTextColor(Color.parseColor(color))
        }

        BleService.switchPanelData.observe(viewLifecycleOwner) { data ->
            if (data == null) return@observe

            binding.tvSpVoltage.text = "%.1f V".format(data.voltage)
            binding.tvSpDeviceState.text = if (data.isPoweredOn) "Active" else "Standby"

            // Update channel toggles without triggering send
            suppressToggleEvents = true
            for ((idx, ch) in channels.withIndex()) {
                val channelNum = idx + 1
                val on = data.isChannelOn(channelNum)
                ch.chToggle.isChecked = on
                ch.chIndicator.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        if (on) requireContext().getColor(com.vanaccess.bms.R.color.accent_green)
                        else    requireContext().getColor(com.vanaccess.bms.R.color.text_secondary)
                    )
            }
            suppressToggleEvents = false
        }
    }

    private fun toggleChannel(channelNum: Int, on: Boolean) {
        val service = (activity as? MainActivity)?.getBleService() ?: return
        val current = BleService.switchPanelData.value?.channelMask ?: 0
        val newMask = if (on) current or (1 shl (channelNum - 1))
                      else    current and (1 shl (channelNum - 1)).inv()
        service.setSwitchPanelChannels(newMask)
    }

    private fun showRenameDialog(channelNum: Int) {
        val current = prefs.getSwitchPanelChannelName(channelNum)
        val input = EditText(requireContext()).apply {
            setText(current)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            selectAll()
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename Channel $channelNum")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                prefs.setSwitchPanelChannelName(channelNum, name)
                channels[channelNum - 1].chName.text =
                    prefs.getSwitchPanelChannelName(channelNum)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
