/*
 * Copyright (C) 2026
 *
 * FIG 0/15 — DAB Emergency Warning System (EWS) information. See fig_00_ext_15.h.
 *
 * This file is a part of the omri-usb library and follows its licence (LGPL 2.1 or later).
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 */

#include "fig_00_ext_15.h"

#include <sstream>

namespace {

/*
 * Minimal MSB-first bit reader over a byte buffer, with strict bounds checking. Every multi-bit
 * read is gated by canRead() so the decoder can never step outside the FIG — essential here, this
 * decodes emergency alerts and must not fault on a corrupt or truncated FIB.
 */
class BitReader {
public:
    BitReader(const std::vector<uint8_t>& data, size_t startByte)
        : m_data(data), m_bitPos(startByte * 8) {}

    size_t bitsRemaining() const {
        const size_t total = m_data.size() * 8;
        return (m_bitPos >= total) ? 0 : (total - m_bitPos);
    }

    bool canRead(size_t nBits) const { return nBits <= bitsRemaining(); }

    // Reads up to 32 bits, MSB-first. Caller MUST have checked canRead(nBits) first.
    uint32_t read(size_t nBits) {
        uint32_t value = 0;
        for (size_t i = 0; i < nBits; ++i) {
            const size_t byteIdx = m_bitPos >> 3;
            const size_t bitInByte = 7 - (m_bitPos & 7);
            const uint32_t bit = (m_data[byteIdx] >> bitInByte) & 0x1u;
            value = (value << 1) | bit;
            ++m_bitPos;
        }
        return value;
    }

private:
    const std::vector<uint8_t>& m_data;
    size_t m_bitPos;
};

} // namespace

Fig_00_Ext_15::Fig_00_Ext_15(const std::vector<uint8_t>& figData) : Fig_00(figData) {
    parseFigData(figData);
}

Fig_00_Ext_15::~Fig_00_Ext_15() {}

void Fig_00_Ext_15::parseFigData(const std::vector<uint8_t>& figData) {
    // figData is exactly the type 0 field (figLength bytes), starting at the C/N/OE/P/D/ext header
    // byte. The base class Fig_00 already decoded those flags from figData[0].
    if (figData.empty()) {
        m_form = Form::INVALID;
        return;
    }

    // Heartbeat form (clause 6.3.2 / Annex E): the type 0 field is empty, i.e. header only, so the
    // FIG length is 1. It marks the ensemble as EWS-participating; no alert is being described.
    if (figData.size() == 1) {
        m_form = Form::HEARTBEAT;
        return;
    }

    BitReader r(figData, 1); // skip the header byte; the EWS body follows
    const bool otherEnsemble = isOtherEnsemble(); // OE flag

    // ---- Id field (Annex E, clause 6.4.2 / 6.5.2) ----
    if (!otherEnsemble) {
        // Tuned ensemble: Phase (2b) + SubChId (6b); Pre-trigger adds Rfa (2b) + Sec (6b).
        if (!r.canRead(8)) { m_form = Form::INVALID; return; }
        m_phase = static_cast<Phase>(r.read(2));
        m_subChId = static_cast<uint8_t>(r.read(6));
        if (m_phase == Phase::PRE_TRIGGER) {
            if (!r.canRead(8)) { m_form = Form::PRE_TRIGGER; m_truncated = true; return; }
            (void) r.read(2);                              // Rfa — reserved, shall be 0
            m_startSecond = static_cast<uint8_t>(r.read(6));
            m_hasStartSecond = true;
        }
    } else {
        // Other ensemble: 16-bit EId. Only Trigger-phase signalling is provided (clause 6.3.1/6.5).
        if (!r.canRead(16)) { m_form = Form::INVALID; return; }
        m_eid = static_cast<uint16_t>(r.read(16));
        m_hasEid = true;
        m_phase = Phase::TRIGGER;
    }

    switch (m_phase) {
        case Phase::PRE_TRIGGER: m_form = Form::PRE_TRIGGER; break;
        case Phase::TRIGGER:     m_form = Form::TRIGGER;     break;
        case Phase::SUSTAIN:     m_form = Form::SUSTAIN;     break;
        case Phase::END:         m_form = Form::END;         break;
    }

    // Status and Location codes are present ONLY in Pre-trigger/Trigger phases (clauses 6.4.3,
    // 6.4.4). Sustain/End carry the Id field only.
    const bool describesAlert = (m_phase == Phase::PRE_TRIGGER || m_phase == Phase::TRIGGER);
    if (!describesAlert) {
        return;
    }

    // ---- Status field (8 bits): Last (1) + Stage (3) + Incident Id (4) ----
    if (!r.canRead(8)) { m_truncated = true; return; }
    m_last = r.read(1) != 0;
    m_stage = static_cast<Stage>(r.read(3));
    m_incidentId = static_cast<uint8_t>(r.read(4));
    m_statusPresent = true;

    // ---- Location codes (fill the rest of the FIG; Annex E / F) ----
    // Each code is octet-aligned with a minimum length of 16 bits (NFF+Zone+SCF+NumDigits+Digit1).
    // Parse codes while a full minimum header still fits; stop and flag truncation on a short tail.
    while (r.bitsRemaining() >= 16) {
        LocationCode lc;
        lc.nff = static_cast<uint8_t>(r.read(2));
        lc.zone = static_cast<uint8_t>(r.read(6));
        lc.subCodesPresent = r.read(1) != 0;
        lc.numDigits = static_cast<uint8_t>(r.read(3));
        lc.digit1 = static_cast<uint8_t>(r.read(4));

        const size_t otherBits = static_cast<size_t>(lc.numDigits) * 4; // NumDigits nibbles
        const size_t padBits = (lc.numDigits & 1u) ? 4 : 0;             // octet-align when odd
        const size_t subBits = lc.subCodesPresent ? 16 : 0;            // Sub-codes flag field
        if (!r.canRead(otherBits + padBits + subBits)) { m_truncated = true; break; }

        for (uint8_t i = 0; i < lc.numDigits; ++i) {
            lc.otherDigits.push_back(static_cast<uint8_t>(r.read(4)));
        }
        if (padBits) { (void) r.read(padBits); }
        if (subBits) { lc.subCodes = static_cast<uint16_t>(r.read(16)); }

        m_locationCodes.push_back(std::move(lc));
    }
}

std::string Fig_00_Ext_15::description() const {
    std::ostringstream os;
    os << "FIG0/15 EWS ";
    switch (m_form) {
        case Form::INVALID:     os << "INVALID"; return os.str();
        case Form::HEARTBEAT:   os << "HEARTBEAT (ensemble is EWS-capable)"; return os.str();
        case Form::PRE_TRIGGER: os << "PRE-TRIGGER"; break;
        case Form::TRIGGER:     os << "TRIGGER"; break;
        case Form::SUSTAIN:     os << "SUSTAIN"; break;
        case Form::END:         os << "END"; break;
    }
    if (m_hasEid) {
        os << " OE EId=0x" << std::hex << m_eid << std::dec;
    } else {
        os << " SubChId=" << static_cast<int>(m_subChId);
    }
    if (m_hasStartSecond) os << " startSec=" << static_cast<int>(m_startSecond);
    if (m_statusPresent) {
        static const char* stageNames[] = {
            "L1-Start", "L1-Update", "L1-Repeat", "L1-Critical",
            "L2-Start", "L2-Update", "L2-Repeat", "TEST"
        };
        os << " stage=" << stageNames[static_cast<uint8_t>(m_stage) & 0x7]
           << " incident=" << static_cast<int>(m_incidentId)
           << (m_last ? " last" : "");
    }
    if (!m_locationCodes.empty()) {
        os << " loc=" << m_locationCodes.size();
    } else if (m_statusPresent) {
        os << " loc=whole-ensemble";
    }
    if (m_truncated) os << " [TRUNCATED]";
    return os.str();
}
