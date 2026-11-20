# Bestway BWBM BLE protocol (0x7F frames)

Documented for interoperability from the behaviour of the vendor Android app (`bluetooth.BMS`, v3.10.0)
and verified against a real **BWBM-631M-23S40A** (23S LiFePO₄). Implementation:
[`BwbmDriver.kt`](../app/src/main/java/com/vanaccess/bms/ble/drivers/BwbmDriver.kt),
[`BwbmExtras.kt`](../app/src/main/java/com/vanaccess/bms/ble/drivers/BwbmExtras.kt).
GT-BMS only uses the **read** commands.

## GATT

| | UUID |
|---|---|
| Service | `0000ffe0-0000-1000-8000-00805f9b34fb` |
| Write + Notify (same characteristic) | `0000ffe1-0000-1000-8000-00805f9b34fb` |

The vendor app requests MTU 247 and enables notifications (CCCD `0x2902` = `01 00`).
Advertised names start with `BWBM` (the vendor scanner also accepts `BW`, `JDY`, `DOBT`, `ZXW`, `OPP`, `DL`).

## Frame

```
7F  ADDR  02  LEN  CMD  DATA…  CS
```

- `ADDR` = `0x10`
- `LEN` = total frame length including `7F` and `CS`
- `CS` = `(0x100 − Σ bytes[0 … LEN−2]) & 0xFF` — all bytes sum to 0 mod 256

Replies use the same header and echo `CMD`. Long replies arrive split over several notifications and must be
reassembled by `LEN`. Notifications are occasionally duplicated, so verify `CS` and resync on the next `7F 10 02`.
The BMS answers one request at a time, so send at most one request about every 500 ms.

## Read commands

| Request | CMD | Reply |
|---|---|---|
| `7F 10 02 06 12 57` | 0x12 | Realtime data |
| `7F 10 02 06 20 49` | 0x20 | ASCII: `<model> H:v21 F:V25_R18_…` → HW 2.1, FW 2.5_R18_… |
| `7F 10 02 06 21 48` | 0x21 | ASCII BMS serial number |
| `7F 10 02 06 25 44` | 0x25 | ASCII `<pack name> <pack serial>` |
| `7F 10 02 06 22 47` | 0x22 | Clock: r5…r10 = BCD `YY MM DD hh mm ss` |
| `7F 10 02 06 19 50` | 0x19 | Parameters (LEN = 0x66) |
| `7F 10 02 07 23 00 45` / `… 23 01 44` | 0x23 | History: first / next record |

## 0x12 realtime data (little-endian)

`N` = cell count, `nb` = balance bytes (1 if N≤8, 2 if ≤16, 3 if ≤24, 4 if ≤32).

| Offset | Type | Field |
|---|---|---|
| r5 … r8 | bits | Status / alarm bytes 0–3 |
| r9–10 | int16 | Current × 0.1 A |
| r11 | u8 | N |
| r12 + 2i | u16 | Cell i voltage, mV |
| r[12+2N] | nb bytes | Balance bitmap |
| a = 12+2N+nb | u8 | T = cell temperature probes, then T × int8 °C |
| b = a+T+1 | u8 | M = MOS temperature probes, then M × int8 °C |
| c = b+M+1 | u16 | Cycle count |
| c+2 | u16 | Remaining capacity × 0.1 Ah |
| c+4 | u16 | Full capacity × 0.1 Ah |
| c+6 | bits | `0x80` discharge MOS on, `0x40` charge MOS on, `0x10` heating |

There is no pack-voltage or SOC field: **pack voltage = Σ cells**, **SOC = remaining / full**
(the vendor app does the same; verified 76.64 V / 67 % against it).
Current sign is not explicit; GT-BMS uses status bits r5.0 (charging) / r5.4 (discharging).

### Status bits

| Byte | Bits |
|---|---|
| r5 | 0 charging · 1 charge over-current · 3 charge MOS fault · 4 discharging · 5 discharge over-current · 6 discharge short circuit · 7 discharge MOS fault |
| r6 | 0 cell sense line open · 1 cell temp sensor open · 2 MOS temp sensor open · 3 AFE fault · 4 standby (history: battery full) · 5 cell low voltage · 6 pack over-voltage · 7 pack under-voltage |
| r7 | 0 MOS over-temp · 1 MOS temp difference · 2 charge over-temp · 3 discharge over-temp · 4 charge under-temp · 5 discharge under-temp · 6 charge temp difference · 7 discharge temp difference |
| r8 | 0 charging disabled by command · 1 discharging disabled by command · 4 thermal runaway warning · 5 cell voltage difference too high · 6 low-voltage charge inhibit · 7 deep under-voltage |

## 0x23 history

Send `23 00` for the first record, then `23 01` for each next one (the BMS keeps the pointer).

| Offset | Field |
|---|---|
| r5 | 1 = record, 2 = last record |
| r6 … r11 | BCD `YY MM DD hh mm ss` |
| r14 … r17 | Status bytes (same table as r5…r8 above) |
| r20 … | Same layout as realtime r11 onwards (N, cells, balance, temps, cycles, capacities) |

## 0x19 parameters (LEN 0x66)

u8 unless noted, u16 little-endian.

| Offset | Field | Scale |
|---|---|---|
| r5–r20 | MOS/charge/discharge temperature protect & release (r13–r16 signed) | °C |
| r21, r22 | Cell impedance empty / full | mΩ |
| r23 | Design capacity (u16) | × 0.1 Ah |
| r25, r27, r29, r31 | Pack OV protect/release, UV protect/release (u16) | × 0.01 V |
| r33 … r57 | Cell OV/UV levels, balance start/diff, SOC correction, shutdown, charge-inhibit (u16) | mV |
| r59, r63 | Charge / discharge over-current activation (u16) | × 0.1 A |
| r61, r65 | Over-current thresholds (vendor formula, see `BwbmExtras.kt`) | A |
| r67, r68 | Secondary over-current / short-circuit trigger voltage | mV |
| r69, r71 | Secondary over-current delay (ms) / short-circuit delay (µs) | |
| r81, r83 | Valid current threshold low / high (u16) | × 0.1 A |
| r85 | Current sense resistor (u16) | µΩ |
| r87, r88, r89 | Cells, cell temp sensors, MOS temp sensors | |
| r92 | Bits: 0 sleep · 1 charging handshake · 2 heating · 7 external temp sensor | |

## Write commands (not used by GT-BMS)

The vendor app also has: `0x30` set clock, `0x33` write parameters, `0x41` MOS on/off, `0x43` forced
start/heating, `0x52` restart / firmware-upgrade mode, `0x51` shutdown, `0x31` clear history.
GT-BMS is deliberately read-only.
