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
import com.vanaccess.bms.databinding.FragmentSmartSolarBinding

class SmartSolarFragment : Fragment() {

    private var _binding: FragmentSmartSolarBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSmartSolarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeData()
    }

    private fun observeData() {
        val prefs = AppPreferences(requireContext())

        BleService.smartSolarKeyError.observe(viewLifecycleOwner) { err ->
            if (err != null) {
                binding.tvSolarUpdated.text = "● Key error"
                binding.tvSolarUpdated.setTextColor(Color.parseColor("#F44336"))
            }
        }

        BleService.smartSolarData.observe(viewLifecycleOwner) { data ->
            val configured = prefs.smartSolarDeviceAddress?.isNotBlank() == true

            binding.cardSolarNotConfigured.visibility =
                if (!configured) View.VISIBLE else View.GONE
            binding.cardSolarMain.visibility =
                if (configured) View.VISIBLE else View.GONE

            if (!configured) {
                binding.tvSolarUpdated.text = "Not configured"
                binding.tvSolarUpdated.setTextColor(Color.parseColor("#9E9E9E"))
                return@observe
            }
            if (data == null) {
                if (BleService.smartSolarKeyError.value == null) {
                    binding.tvSolarUpdated.text = "● Waiting…"
                    binding.tvSolarUpdated.setTextColor(Color.parseColor("#FF9800"))
                }
                return@observe
            }

            binding.tvSolarUpdated.text = "● Active"
            binding.tvSolarUpdated.setTextColor(Color.parseColor("#4CAF50"))

            // Main card
            binding.tvSolarPower.text =
                if (data.solarPower != null) "${data.solarPower} W" else "--- W"
            binding.tvSolarChargeState.text = data.chargeStateText
            binding.tvSolarYield.text =
                if (data.yieldToday != null) "Yield today: %.0f Wh".format(data.yieldToday)
                else "Yield today: --- Wh"

            // Battery row
            binding.tvSolarBattVoltage.text =
                if (data.batteryVoltage != null) "%.1f V".format(data.batteryVoltage) else "--.- V"
            binding.tvSolarBattCurrent.text =
                if (data.batteryCurrent != null) "%.1f A".format(data.batteryCurrent) else "--.- A"
            binding.tvSolarLoadCurrent.text =
                if (data.loadCurrent != null) "%.1f A".format(data.loadCurrent) else "--.- A"

            // Status card
            if (data.error == 0) {
                binding.tvSolarError.text = "No error"
                binding.tvSolarError.setTextColor(requireContext().getColor(com.vanaccess.bms.R.color.accent_green))
            } else {
                binding.tvSolarError.text = "⚠ ${data.errorText}"
                binding.tvSolarError.setTextColor(Color.parseColor("#F44336"))
            }
            val name = prefs.smartSolarDeviceName?.takeIf { it.isNotBlank() }
                ?: prefs.smartSolarDeviceAddress ?: "Unknown"
            binding.tvSolarDeviceName.text = "Device: $name"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
