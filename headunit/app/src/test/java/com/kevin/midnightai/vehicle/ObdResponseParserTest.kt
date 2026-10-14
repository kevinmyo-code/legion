package com.kevin.midnightai.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ObdResponseParser] against canned ELM327 responses. These
 * verify the OBD data path end-to-end *minus the radio* - i.e. that whatever a
 * real adapter sends back is decoded correctly - which is the part that can be
 * checked without hardware. The Bluetooth connection itself requires a real
 * device + dongle to exercise.
 */
class ObdResponseParserTest {

    // --- isFailureResponse (drive-notes-2 ticket 02) --------------------------
    // ELM327 answers a dead K-line/ECU with real text, not a blank string - the
    // reinit trigger in ObdBluetoothManager.sendCommand needs to catch these too.

    @Test fun failure_response_blank_string() {
        assertTrue(ObdResponseParser.isFailureResponse(""))
        assertTrue(ObdResponseParser.isFailureResponse("   "))
    }

    @Test fun failure_response_no_data() {
        assertTrue(ObdResponseParser.isFailureResponse("NO DATA"))
        assertTrue(ObdResponseParser.isFailureResponse("SEARCHING...\r\rNO DATA"))
    }

    @Test fun failure_response_unable_and_stopped_and_can_error() {
        assertTrue(ObdResponseParser.isFailureResponse("UNABLE TO CONNECT"))
        assertTrue(ObdResponseParser.isFailureResponse("STOPPED"))
        assertTrue(ObdResponseParser.isFailureResponse("CAN ERROR"))
    }

    @Test fun failure_response_kline_bus_and_data_errors() {
        // Broadened vocabulary (drive-notes-2 ticket 02 second pass): a dead ISO
        // 9141-2 K-line answers with these, not "NO DATA".
        assertTrue(ObdResponseParser.isFailureResponse("BUS ERROR"))
        assertTrue(ObdResponseParser.isFailureResponse("BUS INIT: ...ERROR"))
        assertTrue(ObdResponseParser.isFailureResponse("BUS BUSY"))
        assertTrue(ObdResponseParser.isFailureResponse("DATA ERROR"))
        assertTrue(ObdResponseParser.isFailureResponse("FB ERROR"))
        assertTrue(ObdResponseParser.isFailureResponse("<RX ERROR"))
        assertTrue(ObdResponseParser.isFailureResponse("BUFFER FULL"))
    }

    @Test fun failure_response_real_data_is_not_a_failure() {
        assertTrue(!ObdResponseParser.isFailureResponse("41 05 7B"))
        assertTrue(!ObdResponseParser.isFailureResponse("12.6V"))
        // "41 00 ..." is the supported-PIDs reply the reinit probe checks for -
        // must NOT read as a failure, or recovery would always report false.
        assertTrue(!ObdResponseParser.isFailureResponse("41 00 BE 1F A8 13"))
        // A bare "?" is an unknown-command reply (our bug), not a bus failure.
        assertTrue(!ObdResponseParser.isFailureResponse("?"))
        // "SEARCHING..." alone is a handshake in progress, not a failure.
        assertTrue(!ObdResponseParser.isFailureResponse("SEARCHING..."))
    }

    // --- Coolant temp (mode 01, PID 05): value - 40 = Celsius ---------------

    @Test fun coolant_typical() {
        // 0x7B = 123, 123 - 40 = 83 C
        assertEquals(83, ObdResponseParser.coolantTempC("41 05 7B"))
    }

    @Test fun coolant_freezing_offset() {
        // 0x28 = 40, 40 - 40 = 0 C
        assertEquals(0, ObdResponseParser.coolantTempC("41 05 28"))
    }

    @Test fun coolant_with_searching_and_crlf_noise() {
        // Real adapters often emit a SEARCHING line and CR line endings.
        assertEquals(90, ObdResponseParser.coolantTempC("SEARCHING...\r41 05 82\r\r>"))
    }

    @Test fun coolant_with_command_echo() {
        // If ATE0 didn't take, the command is echoed before the data.
        assertEquals(83, ObdResponseParser.coolantTempC("0105\r41 05 7B"))
    }

    @Test fun coolant_no_data_returns_null() {
        assertNull(ObdResponseParser.coolantTempC("NO DATA"))
        assertNull(ObdResponseParser.coolantTempC(""))
        assertNull(ObdResponseParser.coolantTempC("41 0C 1A F8")) // wrong PID echo
    }

    // --- RPM (mode 01, PID 0C): ((A*256)+B)/4 -------------------------------

    @Test fun rpm_typical() {
        // (0x1A*256 + 0xF8)/4 = (6656+248)/4 = 1726
        assertEquals(1726, ObdResponseParser.rpm("41 0C 1A F8"))
    }

    @Test fun rpm_idle() {
        // (0x0B*256 + 0xB8)/4 = (2816+184)/4 = 750
        assertEquals(750, ObdResponseParser.rpm("41 0C 0B B8"))
    }

    @Test fun rpm_needs_two_bytes() {
        assertNull(ObdResponseParser.rpm("41 0C 1A")) // only one data byte
        assertNull(ObdResponseParser.rpm("NO DATA"))
    }

    // --- DTC codes (mode 03) ------------------------------------------------

    @Test fun dtc_single_code() {
        // 43 01 33 -> P0133
        assertEquals(listOf("P0133"), ObdResponseParser.dtcCodes("43 01 33"))
    }

    @Test fun dtc_multiple_codes() {
        // 43 01 33 04 20 -> P0133, P0420
        assertEquals(listOf("P0133", "P0420"), ObdResponseParser.dtcCodes("43 01 33 04 20"))
    }

    @Test fun dtc_letter_prefixes() {
        // High two bits of the first byte select P/C/B/U:
        //   0x03->P0301, 0x43->C0312, 0x82->B0223, 0xC1->U0114
        assertEquals(
            listOf("P0301", "C0312", "B0223", "U0114"),
            ObdResponseParser.dtcCodes("43 03 01 43 12 82 23 C1 14"),
        )
    }

    @Test fun dtc_trailing_padding_zeros_ignored() {
        // 43 01 33 00 00 -> P0133 only (00 00 is empty padding)
        assertEquals(listOf("P0133"), ObdResponseParser.dtcCodes("43 01 33 00 00"))
    }

    @Test fun dtc_none_or_unavailable() {
        assertTrue(ObdResponseParser.dtcCodes("NO DATA").isEmpty())
        assertTrue(ObdResponseParser.dtcCodes("UNABLE TO CONNECT").isEmpty())
        assertTrue(ObdResponseParser.dtcCodes("").isEmpty())
        assertTrue(ObdResponseParser.dtcCodes("43 00 00").isEmpty()) // mode 03, zero codes
    }

    @Test fun dtc_with_crlf_and_prompt() {
        assertEquals(listOf("P0133"), ObdResponseParser.dtcCodes("43 01 33\r\r>"))
    }

    @Test fun dtc_multiframe_iso9141_each_line_reprefixed() {
        // ISO 9141-2 / KWP return one line per frame, each re-echoing "43".
        // The second "43" must be stripped, not decoded as code C0304.
        assertEquals(
            listOf("P0133", "P0420"),
            ObdResponseParser.dtcCodes("43 01 33\r43 04 20\r\r>"),
        )
    }

    @Test fun dtc_multiframe_more_than_three_codes() {
        // >3 codes span multiple frames; all must decode, none duplicated/bogus.
        assertEquals(
            listOf("P0301", "P0302", "P0303", "P0171", "P0420"),
            ObdResponseParser.dtcCodes(
                "43 03 01 03 02 03 03\r43 01 71 04 20 00 00\r\r>",
            ),
        )
    }

    // --- Battery voltage (ATRV) ---------------------------------------------

    @Test fun voltage_typical() {
        assertEquals(12.5, ObdResponseParser.batteryVoltage("12.5V")!!, 0.001)
    }

    @Test fun voltage_with_echo_and_prompt() {
        assertEquals(14.2, ObdResponseParser.batteryVoltage("ATRV\r14.2V\r\r>")!!, 0.001)
    }

    @Test fun voltage_no_v_suffix() {
        assertEquals(11.9, ObdResponseParser.batteryVoltage("11.9")!!, 0.001)
    }

    @Test fun voltage_unavailable_returns_null() {
        assertNull(ObdResponseParser.batteryVoltage(""))
        assertNull(ObdResponseParser.batteryVoltage("NO DATA"))
    }

    // --- dataBytes prefix handling ------------------------------------------

    @Test fun dataBytes_prefix_not_found() {
        assertNull(ObdResponseParser.dataBytes("7F 01 12", "41 05"))
    }

    @Test fun dataBytes_skips_to_prefix() {
        assertEquals(listOf(0x7B), ObdResponseParser.dataBytes("SEARCHING... 41 05 7B", "41 05"))
    }

    // --- Engine load (mode 01, PID 04): A*100/255 ----------------------------

    @Test fun load_half() {
        // 0x80 = 128, 128*100/255 = 50.196
        assertEquals(50.196, ObdResponseParser.engineLoadPct("41 04 80")!!, 0.001)
    }

    @Test fun load_full_and_none() {
        assertEquals(100.0, ObdResponseParser.engineLoadPct("41 04 FF")!!, 0.001)
        assertEquals(0.0, ObdResponseParser.engineLoadPct("41 04 00")!!, 0.001)
        assertNull(ObdResponseParser.engineLoadPct("NO DATA"))
    }

    // --- Fuel trims (mode 01, PIDs 06/07): (A-128)*100/128 -------------------

    @Test fun stft_neutral() {
        // 0x80 = 128 -> 0% (no correction)
        assertEquals(0.0, ObdResponseParser.shortFuelTrimPct("41 06 80")!!, 0.001)
    }

    @Test fun stft_adding_fuel() {
        // 0x90 = 144, (144-128)*100/128 = +12.5% (running lean, adding fuel)
        assertEquals(12.5, ObdResponseParser.shortFuelTrimPct("41 06 90")!!, 0.001)
    }

    @Test fun ltft_pulling_fuel() {
        // 0x70 = 112, (112-128)*100/128 = -12.5% (running rich, pulling fuel)
        assertEquals(-12.5, ObdResponseParser.longFuelTrimPct("41 07 70")!!, 0.001)
    }

    // --- Speed (mode 01, PID 0D): A km/h --------------------------------------

    @Test fun speed_highway() {
        // 0x64 = 100 km/h
        assertEquals(100, ObdResponseParser.speedKmh("41 0D 64"))
        assertEquals(0, ObdResponseParser.speedKmh("41 0D 00"))
        assertNull(ObdResponseParser.speedKmh("NO DATA"))
    }

    // --- Intake air temp (mode 01, PID 0F): A-40 ------------------------------

    @Test fun iat_typical() {
        // 0x3C = 60, 60-40 = 20 C
        assertEquals(20, ObdResponseParser.intakeAirTempC("41 0F 3C"))
        assertNull(ObdResponseParser.intakeAirTempC("41 05 3C")) // wrong PID
    }

    // --- MAF (mode 01, PID 10): (A*256+B)/100 g/s -----------------------------

    @Test fun maf_cruise() {
        // (0x0C*256 + 0x8F)/100 = (3072+143)/100 = 32.15 g/s
        assertEquals(32.15, ObdResponseParser.mafGramsPerSec("41 10 0C 8F")!!, 0.001)
    }

    @Test fun maf_needs_two_bytes() {
        assertNull(ObdResponseParser.mafGramsPerSec("41 10 0C"))
        assertNull(ObdResponseParser.mafGramsPerSec("NO DATA"))
    }

    // --- Fuel level (mode 01, PID 2F): A*100/255 -------------------------------

    @Test fun fuel_level_full_and_quarter() {
        assertEquals(100.0, ObdResponseParser.fuelLevelPct("41 2F FF")!!, 0.001)
        // 0x40 = 64, 64*100/255 = 25.098
        assertEquals(25.098, ObdResponseParser.fuelLevelPct("41 2F 40")!!, 0.001)
    }

    // --- Readiness (mode 01, PID 01) -------------------------------------------

    @Test fun readiness_mil_on_with_incomplete_monitors() {
        // A=0x81: MIL on, 1 stored code.
        // B=0x07: misfire/fuel/components available, none incomplete.
        // C=0x65: catalyst, evap, O2 sensor, O2 heater available.
        // D=0x24: evap + O2 sensor incomplete.
        val r = ObdResponseParser.readiness("41 01 81 07 65 24")!!
        assertTrue(r.milOn)
        assertEquals(1, r.dtcCount)
        assertEquals(7, r.monitors.size)
        val byName = r.monitors.associateBy { it.name }
        assertTrue(byName.getValue("Misfire").complete)
        assertTrue(byName.getValue("Fuel system").complete)
        assertTrue(byName.getValue("Components").complete)
        assertTrue(byName.getValue("Catalyst").complete)
        assertTrue(!byName.getValue("Evap system").complete)
        assertTrue(!byName.getValue("O2 sensor").complete)
        assertTrue(byName.getValue("O2 heater").complete)
    }

    @Test fun readiness_all_clear() {
        // A=0x00: MIL off, no codes. B=0x07 all continuous complete.
        // C=0xE5 available, D=0x00 all complete.
        val r = ObdResponseParser.readiness("41 01 00 07 E5 00")!!
        assertTrue(!r.milOn)
        assertEquals(0, r.dtcCount)
        assertTrue(r.monitors.all { it.complete })
    }

    @Test fun readiness_needs_four_bytes() {
        assertNull(ObdResponseParser.readiness("41 01 81 07"))
        assertNull(ObdResponseParser.readiness("NO DATA"))
    }

    // --- Freeze frame (mode 02): optional frame-number byte --------------------

    @Test fun freezeFrame_with_frame_byte() {
        // "42 0C 00 1A F8" - the 00 is the frame number, data is 1A F8.
        assertEquals(
            listOf(0x1A, 0xF8),
            ObdResponseParser.freezeFrameBytes("42 0C 00 1A F8", "0C", 2),
        )
    }

    @Test fun freezeFrame_without_frame_byte() {
        // Some clones omit the frame echo entirely.
        assertEquals(
            listOf(0x1A, 0xF8),
            ObdResponseParser.freezeFrameBytes("42 0C 1A F8", "0C", 2),
        )
    }

    @Test fun freezeFrame_short_or_missing() {
        assertNull(ObdResponseParser.freezeFrameBytes("42 0C 1A", "0C", 2)) // one byte short
        assertNull(ObdResponseParser.freezeFrameBytes("NO DATA", "0C", 2))
        assertNull(ObdResponseParser.freezeFrameBytes("7F 02 12", "0C", 2)) // mode-02 unsupported
    }

    // --- Supported PIDs (mode 01, PID 00/20/40 bitmask) -----------------------

    @Test fun supported_pids_first_window_decodes_bits() {
        // "41 00 BE 1F A8 13": MSB of 0xBE (bit7) = PID 0x01.
        // 0xBE = 1011 1110 -> PIDs 01,03,04,05,06,07 (bit6=0 so no 02).
        val s = ObdResponseParser.supportedPids("41 00 BE 1F A8 13", 0x00)!!
        assertTrue(s.contains(0x01))
        assertTrue(!s.contains(0x02))
        assertTrue(s.contains(0x05)) // coolant
        assertTrue(s.contains(0x0C)) // rpm (0x1F -> 0C,0D,0E,0F set)
        assertTrue(s.contains(0x20)) // 0x13 LSB set -> next window marker present
    }

    @Test fun supported_pids_fuel_level_window() {
        // Fuel level is PID 0x2F, reported in the 0x20 window. Bit for 0x2F is
        // bit (0x2F-0x21)=14 from the MSB, i.e. byte C bit 1 -> use 0x00 02 00 00.
        val s = ObdResponseParser.supportedPids("41 20 00 02 00 00", 0x20)!!
        assertTrue(s.contains(0x2F))
        assertTrue(!s.contains(0x21))
    }

    @Test fun supported_pids_short_or_missing() {
        assertNull(ObdResponseParser.supportedPids("41 00 BE 1F", 0x00)) // <4 data bytes
        assertNull(ObdResponseParser.supportedPids("NO DATA", 0x00))
        assertNull(ObdResponseParser.supportedPids("41 20 00 02 00 00", 0x00)) // wrong window echo
    }
}
