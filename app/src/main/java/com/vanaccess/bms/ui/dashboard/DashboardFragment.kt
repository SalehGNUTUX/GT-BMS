// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.dashboard

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.chip.Chip
import com.vanaccess.bms.ble.BleService
import com.vanaccess.bms.ble.BmsBasicInfo
import com.vanaccess.bms.ble.ProtectionFlag
import com.vanaccess.bms.ble.SmartBmsState
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.databinding.FragmentBatteryBinding
import com.vanaccess.bms.ui.battery.BatteryScreen
import com.vanaccess.bms.databinding.FragmentDashboard123bmsBinding
import kotlin.math.abs

class DashboardFragment : Fragment() {

    private var _batBinding: FragmentBatteryBinding? = null
    private var _bmsBinding: FragmentDashboard123bmsBinding? = null
    private var cellChartInitialized = false
    private lateinit var prefs: AppPreferences

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        prefs = AppPreferences(requireContext())
        return if (prefs.is123Bms) {
            _bmsBinding = FragmentDashboard123bmsBinding.inflate(inflater, container, false)
            _bmsBinding!!.root
        } else {
            _batBinding = FragmentBatteryBinding.inflate(inflater, container, false)
            _batBinding!!.root
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (_bmsBinding != null) {
            setup123BmsChart()
            observe123BmsData()
        } else {
            BatteryScreen(this, _batBinding!!, prefs).setup()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 123SmartBMS Dashboard
    // ══════════════════════════════════════════════════════════════════════════

    private fun observe123BmsData() {
        BleService.connectionState.observe(viewLifecycleOwner) { state ->
            updateConnectionBadge123(state)
        }

        BleService.smartBmsState.observe(viewLifecycleOwner) { state ->
            if (state != null) update123BmsDashboard(state)
        }
    }

    private fun updateConnectionBadge123(state: BleService.ConnectionState) {
        val binding = _bmsBinding ?: return
        val (text, colorRes) = when (state) {
            BleService.ConnectionState.CONNECTED -> "Connected" to "#4CAF50"
            BleService.ConnectionState.CONNECTING -> "Connecting..." to "#FF9800"
            BleService.ConnectionState.RECONNECTING -> "Reconnecting..." to "#FF9800"
            BleService.ConnectionState.SCANNING -> "Scanning..." to "#2196F3"
            BleService.ConnectionState.DISCONNECTED -> "Disconnected" to "#F44336"
        }
        binding.tvConnectionStatus.text = text
        binding.tvConnectionStatus.setTextColor(Color.parseColor(colorRes))
    }

    private fun update123BmsDashboard(state: SmartBmsState) {
        val binding = _bmsBinding ?: return
        val useCelsius = prefs.temperatureUnit == "C"

        // SOC gauge
        if (state.soc >= 0) {
            binding.progressSoc.progress = state.soc
            binding.tvSocPercent.text = "${state.soc}%"
            binding.tvSocLabel.text = when {
                state.soc >= 80 -> "Excellent"
                state.soc >= 60 -> "Good"
                state.soc >= 40 -> "Fair"
                state.soc >= 20 -> "Low"
                else -> "Critical"
            }
        }

        // Status
        binding.tvBatteryStatus.text = state.statusText
        binding.tvBatteryStatus.setTextColor(Color.parseColor(when (state.statusText) {
            "Charging" -> "#4CAF50"; "Discharging" -> "#2196F3"; else -> "#9E9E9E"
        }))

        // Energy stored
        if (state.energyStored > 0) {
            binding.tvEnergyStored.text = "${state.energyStored} Wh stored"
        }

        // Voltage
        if (!state.packVoltage.isNaN()) {
            binding.tvVoltage.text = "%.2f V".format(state.packVoltage)
        }

        // Current & Power
        val netI = if (state.netCurrent.isNaN()) 0.0 else state.netCurrent
        binding.tvCurrent.text = "%.2f A".format(abs(netI))
        binding.tvPower.text = "%.1f W".format(state.powerWatts)

        val charging = netI > 0.1
        val discharging = netI < -0.1
        val arrowText = when {
            charging -> "▲"; discharging -> "▼"; else -> "─"
        }
        val arrowColor = Color.parseColor(when {
            charging -> "#4CAF50"; discharging -> "#F44336"; else -> "#9E9E9E"
        })
        binding.tvCurrentArrow.text = arrowText
        binding.tvCurrentArrow.setTextColor(arrowColor)
        binding.tvCurrentDirection.text = when {
            charging -> "Charging"; discharging -> "Discharging"; else -> "Net Current"
        }
        binding.tvCurrentDirection.setTextColor(arrowColor)
        binding.tvPowerArrow.text = arrowText
        binding.tvPowerArrow.setTextColor(arrowColor)
        binding.tvPowerDirection.text = when {
            charging -> "Charging"; discharging -> "Discharging"; else -> "Power"
        }
        binding.tvPowerDirection.setTextColor(arrowColor)

        // Dual current detail
        val i1 = if (state.current1.isNaN()) 0.0 else state.current1
        val i2 = if (state.current2.isNaN()) 0.0 else state.current2
        binding.tvCurrentDetail.text = "I1: %.2fA / I2: %.2fA".format(i1, i2)

        // Temperature range
        if (state.tempLow != Int.MIN_VALUE) {
            val lo = state.tempLow.toDouble()
            val hi = state.tempHigh.toDouble()
            if (useCelsius) {
                binding.tvTempRange.text = "%d - %d°C".format(state.tempLow, state.tempHigh)
            } else {
                binding.tvTempRange.text = "%d - %d°F".format(
                    (lo * 9 / 5 + 32).toInt(), (hi * 9 / 5 + 32).toInt()
                )
            }
            binding.tvTempDetail.text = "Lo: #${state.tempLowCell}  Hi: #${state.tempHighCell}"
        }

        // Cell voltages chart
        val voltages = state.cellVoltagesList()
        if (voltages.isNotEmpty()) {
            updateCellChartCommon(binding.barChartCells, binding.tvCellDelta, binding.tvMinCell, binding.tvMaxCell, voltages)
        }

        // Cell delta from V line (more accurate than chart since it's always current)
        if (!state.cellVLow.isNaN() && !state.cellVHigh.isNaN()) {
            val delta = (state.cellVHigh - state.cellVLow) * 1000
            binding.tvCellDelta.text = "%.1f mV".format(delta)
            binding.tvCellDelta.setTextColor(Color.parseColor(when {
                delta > 50 -> "#F44336"; delta > 20 -> "#FF9800"; else -> "#4CAF50"
            }))
            binding.tvMinCell.text = "Lo: %.3fV (#%d)".format(state.cellVLow, state.cellLowNum)
            binding.tvMaxCell.text = "Hi: %.3fV (#%d)".format(state.cellVHigh, state.cellHighNum)
        }

        // Energy card
        binding.tvEnergyStoredDetail.text = "${state.energyStored} Wh"
        binding.tvTodayConsumed.text = "${state.eTodayConsumed} Wh"
        binding.tvTotalCollected.text = "${state.totalCollected} Wh"
        binding.tvTotalConsumed.text = "${state.totalConsumed} Wh"

        // Status chips
        binding.chipChargeAllowed.isChecked = state.chargeAllowed
        binding.chipDischargeAllowed.isChecked = state.dischargeAllowed

        // Alert flags
        val alerts = state.alertFlags
        binding.chipGroupAlerts.removeAllViews()
        if (alerts.isEmpty()) {
            val chip = Chip(requireContext())
            chip.text = "No alerts"
            chip.setChipBackgroundColorResource(com.google.android.material.R.color.material_dynamic_secondary90)
            chip.isClickable = false
            binding.chipGroupAlerts.addView(chip)
        } else {
            alerts.forEach { flag ->
                val chip = Chip(requireContext())
                chip.text = flag.name
                chip.setTextColor(Color.WHITE)
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))
                chip.isClickable = false
                binding.chipGroupAlerts.addView(chip)
            }
        }
    }

    private fun setup123BmsChart() {
        val binding = _bmsBinding ?: return
        binding.barChartCells.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(false)
            xAxis.textColor = Color.WHITE
            axisLeft.textColor = Color.WHITE
            axisRight.isEnabled = false
            xAxis.setDrawGridLines(false)
            axisLeft.setDrawGridLines(true)
            axisLeft.gridColor = Color.parseColor("#33FFFFFF")
            setBackgroundColor(Color.TRANSPARENT)
            axisLeft.axisMinimum = 2.8f
            axisLeft.axisMaximum = 3.7f
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shared cell chart logic
    // ══════════════════════════════════════════════════════════════════════════

    private fun updateCellChartCommon(
        chart: com.github.mikephil.charting.charts.BarChart,
        tvDelta: android.widget.TextView,
        tvMin: android.widget.TextView,
        tvMax: android.widget.TextView,
        voltages: List<Double>
    ) {
        if (voltages.isEmpty()) return
        val minV = voltages.minOrNull() ?: 0.0
        val maxV = voltages.maxOrNull() ?: 0.0

        val entries = voltages.mapIndexed { index, v ->
            BarEntry(index.toFloat(), v.toFloat())
        }
        val colors = voltages.map { v ->
            when {
                v >= maxV - 0.005 && maxV - minV > 0.02 -> Color.parseColor("#FF9800")
                v <= minV + 0.005 && maxV - minV > 0.02 -> Color.parseColor("#F44336")
                v < 3.0 -> Color.parseColor("#F44336")
                v < 3.2 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#4CAF50")
            }
        }
        val dataSet = BarDataSet(entries, "Cell Voltages").apply {
            setColors(colors)
            valueTextColor = Color.WHITE
            valueTextSize = 8f
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getBarLabel(barEntry: BarEntry) = "%.3f".format(barEntry.y)
            }
        }
        val labels = voltages.indices.map { "C${it + 1}" }
        chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        chart.xAxis.labelCount = voltages.size
        chart.data = BarData(dataSet).apply { barWidth = 0.7f }
        if (!cellChartInitialized) {
            cellChartInitialized = true
            chart.animateY(400)
        } else {
            chart.invalidate()
        }

        val delta = (maxV - minV) * 1000
        tvDelta.text = "%.1f mV".format(delta)
        tvDelta.setTextColor(Color.parseColor(when {
            delta > 50 -> "#F44336"; delta > 20 -> "#FF9800"; else -> "#4CAF50"
        }))
        tvMin.text = "Min: %.3f V".format(minV)
        tvMax.text = "Max: %.3f V".format(maxV)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _batBinding = null
        _bmsBinding = null
        cellChartInitialized = false
    }
}
