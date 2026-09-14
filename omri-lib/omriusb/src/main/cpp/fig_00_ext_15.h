/*
 * Copyright (C) 2026
 *
 * FIG 0/15 — DAB Emergency Warning System (EWS) information.
 *
 * This file is a part of the omri-usb library and follows its licence.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 */

#ifndef FIG_00_EXT_15_H
#define FIG_00_EXT_15_H

#include <cstdint>
#include <string>
#include <vector>

#include "fig_00.h"

/*
 * ETSI TS 104 089 V1.1.1 (2024-09) — DAB Emergency Warning System (EWS).
 * The EWS information is encoded in Extension 15 of FIG type 0 (FIG 0/15), defined in Annex E of
 * that standard (referred to below as "Annex E"). This is the consumer feature branded ASA
 * ("Automatic Safety Alert").
 *
 * FIG type 0 header flags (ETSI EN 300 401 clause 5.2.2.1) are reused with EWS-specific meaning:
 *   C/N flag -> SIV (database/heartbeat control): heartbeat form sets C/N = 1.
 *   OE  flag -> the alert audio is in the tuned ensemble (0) or another ensemble (1).
 *   P/D flag -> special: receiver sleep-mode synchronisation. 0 = Process (seconds count 0..29),
 *               1 = Discard (seconds count 30..59).
 *
 * Structure of the type 0 field (Annex E, Figure E.1):
 *
 *   [ Id field: 8 or 16 bits ][ Status field: 0 or 8 bits ][ Location code a ] ... [ Location code l ]
 *
 * The forms (clause 6.3):
 *   - Heartbeat : type 0 field is EMPTY (header only). Signals the ensemble participates in EWS.
 *   - Trigger   : Id + Status + zero or more Location codes. Receiver evaluates whether to play.
 *   - Pre-trigger: like Trigger, plus a start-second in the Id field (inter-ensemble timing only).
 *   - Sustain/End: Id field ONLY (no Status, no Location codes).
 *
 * This class is a strict, defensive decoder: it never reads past the supplied buffer and marks the
 * result invalid/truncated on malformed input rather than crashing (it decodes safety alerts).
 */
class Fig_00_Ext_15 : public Fig_00 {

public:
    // Overall shape of this FIG instance, derived from the flags/phase (clause 6.3).
    enum class Form : uint8_t {
        INVALID,      // empty or malformed buffer
        HEARTBEAT,    // C/N control form, empty type 0 field
        PRE_TRIGGER,  // OE=0, phase Pre-trigger
        TRIGGER,      // OE=0 phase Trigger, or OE=1 (other-ensemble trigger)
        SUSTAIN,      // OE=0, phase Sustain
        END           // OE=0, phase End
    };

    // Id-field Phase (Annex E), 2-bit. Only meaningful for the tuned ensemble (OE=0).
    enum class Phase : uint8_t {
        PRE_TRIGGER = 0,
        TRIGGER     = 1,
        SUSTAIN     = 2,
        END         = 3
    };

    // Status-field Stage (Annex E), 3-bit.
    enum class Stage : uint8_t {
        LEVEL1_START    = 0,
        LEVEL1_UPDATE   = 1,
        LEVEL1_REPEAT   = 2,
        LEVEL1_CRITICAL = 3,
        LEVEL2_START    = 4,
        LEVEL2_UPDATE   = 5,
        LEVEL2_REPEAT   = 6,
        TEST            = 7
    };

    // One DAB location code (Annex E + Annex F). Codes are additive; an empty list means the whole
    // ensemble coverage area. We keep the raw parsed fields — geographic resolution is done later.
    struct LocationCode {
        uint8_t nff = 0;                 // 2-bit: FIG 0/15 instances that still follow for this set
        uint8_t zone = 0;                // 6-bit: top-level zone (Annex F, 0..41)
        bool subCodesPresent = false;    // SCF
        uint8_t numDigits = 0;           // 3-bit: number of digits in otherDigits (0..5)
        uint8_t digit1 = 0;              // 4-bit: most significant digit
        std::vector<uint8_t> otherDigits;// each a 4-bit digit (size == numDigits)
        uint16_t subCodes = 0;           // 16-bit sub-area flags, present iff subCodesPresent
    };

public:
    explicit Fig_00_Ext_15(const std::vector<uint8_t>& figData);
    ~Fig_00_Ext_15() override;

    Form getForm() const { return m_form; }
    bool isValid() const { return m_form != Form::INVALID; }
    bool isHeartbeat() const { return m_form == Form::HEARTBEAT; }
    // True when the buffer ran short mid-decode: the fields decoded so far are still usable, but the
    // alert-area (location codes) may be incomplete. Never true for a clean heartbeat/alert.
    bool isTruncated() const { return m_truncated; }

    bool isOtherEnsembleAlert() const { return isOtherEnsemble(); } // OE flag (base)
    // P/D flag: false = Process (seconds 0..29), true = Discard (seconds 30..59). Fine-sync aid.
    bool isDiscardHalf() const { return isDataService(); }

    // Tuned-ensemble alert (OE = 0): the phase and the sub-channel carrying the alert audio.
    Phase getPhase() const { return m_phase; }
    uint8_t getSubChId() const { return m_subChId; }
    // Pre-trigger only: the seconds count at which the Trigger phase starts (special value 63 =
    // start at seconds count 0 with a 5 s Trigger). hasStartSecond() gates it.
    bool hasStartSecond() const { return m_hasStartSecond; }
    uint8_t getStartSecond() const { return m_startSecond; }

    // Other-ensemble alert (OE = 1): the EId of the ensemble carrying the alert audio.
    bool hasEnsembleId() const { return m_hasEid; }
    uint16_t getEnsembleId() const { return m_eid; }

    // Status field — present only in Pre-trigger/Trigger (clause 6.4.3).
    bool hasStatus() const { return m_statusPresent; }
    bool isLastOfAlertGroup() const { return m_last; }
    Stage getStage() const { return m_stage; }
    uint8_t getIncidentId() const { return m_incidentId; }
    // Convenience: the Test stage is used only for test activities (the ASA home-test).
    bool isTest() const { return m_statusPresent && m_stage == Stage::TEST; }

    const std::vector<LocationCode>& getLocationCodes() const { return m_locationCodes; }

    // Human-readable one-line summary for diagnostics/logging.
    std::string description() const;

private:
    void parseFigData(const std::vector<uint8_t>& figData);

private:
    const std::string m_logTag = {"[Fig_00_Ext_15]"};

    Form m_form = Form::INVALID;
    bool m_truncated = false;

    Phase m_phase = Phase::TRIGGER;
    uint8_t m_subChId = 0;
    bool m_hasStartSecond = false;
    uint8_t m_startSecond = 0;

    bool m_hasEid = false;
    uint16_t m_eid = 0;

    bool m_statusPresent = false;
    bool m_last = false;
    Stage m_stage = Stage::LEVEL1_START;
    uint8_t m_incidentId = 0;

    std::vector<LocationCode> m_locationCodes;
};

#endif // FIG_00_EXT_15_H
