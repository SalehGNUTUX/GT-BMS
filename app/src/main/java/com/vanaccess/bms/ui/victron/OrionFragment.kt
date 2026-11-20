// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.victron

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.vanaccess.bms.ble.BleService
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.databinding.FragmentOrionBinding

class OrionFragment : Fragment() {

    private var _binding: FragmentOrionBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeData()
    }

    private fun observeData() {
        val prefs = AppPreferences(requireContext())

        BleService.orionData.observe(viewLifecycleOwner) { data ->
            val configured = prefs.orionDeviceAddress?.isNotBlank() == true

            binding.cardOrionNotConfigured.visibility =
                if (!configured) View.VISIBLE else View.GONE
            binding.cardOrionMain.visibility =
                if (configured) View.VISIBLE else View.GONE

            if (!configured) {
                binding.tvOrionUpdated.text = "Not configured"
                binding.tvOrionUpdated.setTextColor(Color.parseColor("#9E9E9E"))
                return@observe
            }
            if (data == null) {
                binding.tvOrionUpdated.text = "● Waiting…"
                binding.tvOrionUpdated.setTextColor(Color.parseColor("#FF9800"))
                return@observe
            }

            binding.tvOrionUpdated.text = "● Active"
            binding.tvOrionUpdated.setTextColor(Color.parseColor("#4CAF50"))

            // State card
            binding.tvOrionState.text = data.deviceStateText
            val power = data.outputPower
            binding.tvOrionPower.text = if (power != null) "%.0f W".format(power) else "N/A"

            // Voltage row
            binding.tvOrionVin.text =
                if (data.inputVoltage  != null) "%.1f V".format(data.inputVoltage)  else "--.- V"
            binding.tvOrionVout.text =
                if (data.outputVoltage != null) "%.1f V".format(data.outputVoltage) else "--.- V"

            // Current card
            binding.tvOrionCurrent.text =
                if (data.outputCurrent != null) "%.1f A".format(data.outputCurrent) else "N/A"

            // Status card
            binding.tvOrionError.text  = "Error: ${data.error}"
            val name = prefs.orionDeviceName?.takeIf { it.isNotBlank() }
                ?: prefs.orionDeviceAddress ?: "Unknown"
            binding.tvOrionDeviceName.text = "Device: $name"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
