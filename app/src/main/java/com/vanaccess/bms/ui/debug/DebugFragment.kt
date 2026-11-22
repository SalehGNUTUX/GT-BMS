// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.vanaccess.bms.ble.BleService
import com.vanaccess.bms.databinding.FragmentDebugBinding
import com.vanaccess.bms.ui.MainActivity

class DebugFragment : Fragment() {

    private var _binding: FragmentDebugBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDebugBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnDebugBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        setupButtons()
        observeData()
    }

    private fun setupButtons() {
        binding.btnCopyGatt.setOnClickListener {
            val text = BleService.gattDump.value?.takeIf { it.isNotBlank() }
                ?: "No GATT data yet — connect to a device first"
            copyToClipboard("GATT Dump", text)
        }

        binding.btnSendRaw.setOnClickListener {
            val hex = binding.etHexInput.text?.toString() ?: ""
            if (hex.isBlank()) {
                Toast.makeText(requireContext(), "Enter hex bytes first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val service = (activity as? MainActivity)?.getBleService()
            if (service == null) {
                Toast.makeText(requireContext(), "Service not bound", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!service.sendRawBytes(hex)) {
                Toast.makeText(
                    requireContext(),
                    "Send failed — not connected or no write characteristic",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnShareLog.setOnClickListener {
            val gatt = BleService.gattDump.value ?: "(no GATT data)"
            val packets = BleService.rawPacketLog.value
                ?.joinToString("\n\n") ?: "(no packets)"
            val report = "=== GATT DUMP ===\n$gatt\n\n=== PACKET LOG ===\n$packets"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report)
                putExtra(Intent.EXTRA_SUBJECT, "PowerQueen BMS Debug Log")
            }
            startActivity(Intent.createChooser(intent, "Share Debug Log"))
        }

        binding.btnClearLog.setOnClickListener {
            BleService.rawPacketLog.postValue(emptyList())
        }
    }

    private fun observeData() {
        BleService.activeServiceUuid.observe(viewLifecycleOwner) { uuid ->
            binding.tvActiveService.text = "Service: ${uuid ?: "none"}"
        }
        BleService.activeWriteUuid.observe(viewLifecycleOwner) { uuid ->
            binding.tvActiveWrite.text = "Write char: ${uuid ?: "none"}"
        }
        BleService.activeNotifyUuid.observe(viewLifecycleOwner) { uuid ->
            binding.tvActiveNotify.text = "Notify char: ${uuid ?: "none"}"
        }
        BleService.gattDump.observe(viewLifecycleOwner) { dump ->
            binding.tvGattDump.text = dump?.takeIf { it.isNotBlank() }
                ?: "No GATT data — connect to a device first"
        }
        BleService.rawPacketLog.observe(viewLifecycleOwner) { log ->
            binding.tvPacketLog.text = if (log.isNullOrEmpty()) {
                "No packets received yet"
            } else {
                log.joinToString("\n\n")
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
