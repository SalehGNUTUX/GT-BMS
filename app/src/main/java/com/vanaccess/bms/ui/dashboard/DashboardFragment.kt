// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.databinding.FragmentBatteryBinding
import com.vanaccess.bms.ui.battery.BatteryScreen

/**
 * Battery destination. Every BMS type — 123SmartBMS included since 1.6 — uses the common
 * battery screen (Live + Cells / Reports / Info / History).
 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentBatteryBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBatteryBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        BatteryScreen(this, _binding!!, AppPreferences(requireContext())).setup()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
