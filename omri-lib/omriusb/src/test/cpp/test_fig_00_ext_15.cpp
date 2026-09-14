/*
 * Host unit tests for the FIG 0/15 (DAB EWS) decoder — pure C++, no Android/NDK.
 *
 * Build & run:
 *   g++ -std=c++11 -I../../main/cpp test_fig_00_ext_15.cpp ../../main/cpp/fig_00_ext_15.cpp -o /tmp/t \
 *     && /tmp/t
 *
 * Every vector is hand-packed bit-for-bit from ETSI TS 104 089 Annex E, so this test IS the
 * conformance check for the decoder — independent of any DAB hardware or the omri build.
 */

#include "fig_00_ext_15.h"

#include <cstdio>
#include <cstdlib>
#include <vector>

static int g_failures = 0;

#define CHECK(cond)                                                             \
    do {                                                                        \
        if (!(cond)) {                                                          \
            std::printf("  FAIL: %s  (line %d)\n", #cond, __LINE__);            \
            ++g_failures;                                                       \
        }                                                                       \
    } while (0)

using V = std::vector<uint8_t>;

// Header byte helper: C/N, OE, P/D flags + extension 15 (0x0F) in the low 5 bits.
static uint8_t hdr(bool cn, bool oe, bool pd) {
    return (cn ? 0x80 : 0) | (oe ? 0x40 : 0) | (pd ? 0x20 : 0) | 0x0F;
}

int main() {
    using Form = Fig_00_Ext_15::Form;
    using Phase = Fig_00_Ext_15::Phase;
    using Stage = Fig_00_Ext_15::Stage;

    // 1) Heartbeat: header only, C/N=1, OE=0 (clause 6.3.2). size == 1.
    {
        std::printf("Test 1: heartbeat\n");
        Fig_00_Ext_15 f(V{hdr(true, false, false)});
        CHECK(f.getForm() == Form::HEARTBEAT);
        CHECK(f.isHeartbeat());
        CHECK(f.isValid());
        CHECK(!f.hasStatus());
        CHECK(!f.isTruncated());
        std::printf("  %s\n", f.description().c_str());
    }

    // 2) Trigger, tuned ensemble, no location codes (whole-ensemble area).
    //    Id: Phase=Trigger(01) SubChId=5(000101)=0x45; Status: Last=1 Stage=L1Start(000) IId=3=0x83.
    {
        std::printf("Test 2: trigger, whole-ensemble\n");
        Fig_00_Ext_15 f(V{hdr(false, false, false), 0x45, 0x83});
        CHECK(f.getForm() == Form::TRIGGER);
        CHECK(!f.isOtherEnsembleAlert());
        CHECK(f.getPhase() == Phase::TRIGGER);
        CHECK(f.getSubChId() == 5);
        CHECK(f.hasStatus());
        CHECK(f.isLastOfAlertGroup());
        CHECK(f.getStage() == Stage::LEVEL1_START);
        CHECK(f.getIncidentId() == 3);
        CHECK(f.getLocationCodes().empty());
        CHECK(!f.isTest());
        CHECK(!f.isTruncated());
        std::printf("  %s\n", f.description().c_str());
    }

    // 3) Trigger + one location code, SCF=0, NumDigits=2 (even, no padding). Stage=Test.
    //    Id: 01 001010 = 0x4A (SubChId 10). Status: Last=1 Stage=Test(111) IId=0 = 0xF0.
    //    Loc: NFF=0 Zone=11 -> 00 001011 = 0x0B; SCF=0 NumDig=2 Digit1=4 -> 0 010 0100 = 0x24;
    //         otherDigits {3,9} -> 0011 1001 = 0x39.
    {
        std::printf("Test 3: trigger + 1 loc code (2 digits), TEST stage\n");
        Fig_00_Ext_15 f(V{hdr(false, false, false), 0x4A, 0xF0, 0x0B, 0x24, 0x39});
        CHECK(f.getForm() == Form::TRIGGER);
        CHECK(f.getSubChId() == 10);
        CHECK(f.getStage() == Stage::TEST);
        CHECK(f.isTest());
        CHECK(f.getIncidentId() == 0);
        CHECK(f.getLocationCodes().size() == 1);
        if (f.getLocationCodes().size() == 1) {
            const auto& lc = f.getLocationCodes()[0];
            CHECK(lc.nff == 0);
            CHECK(lc.zone == 11);
            CHECK(!lc.subCodesPresent);
            CHECK(lc.numDigits == 2);
            CHECK(lc.digit1 == 4);
            CHECK(lc.otherDigits.size() == 2);
            CHECK(lc.otherDigits[0] == 3);
            CHECK(lc.otherDigits[1] == 9);
        }
        CHECK(!f.isTruncated());
        std::printf("  %s\n", f.description().c_str());
    }

    // 4) Sustain: Id field only (no status, no location). Phase=Sustain(10) SubChId=7 -> 0x87.
    {
        std::printf("Test 4: sustain\n");
        Fig_00_Ext_15 f(V{hdr(false, false, false), 0x87});
        CHECK(f.getForm() == Form::SUSTAIN);
        CHECK(f.getPhase() == Phase::SUSTAIN);
        CHECK(f.getSubChId() == 7);
        CHECK(!f.hasStatus());
        CHECK(f.getLocationCodes().empty());
        CHECK(!f.isTruncated());
        std::printf("  %s\n", f.description().c_str());
    }

    // 5) Pre-trigger with start second. Id: Phase=Pre(00) SubChId=1 -> 0x01; Rfa=00 Sec=63 -> 0x3F;
    //    Status: Last=0 Stage=L1Start IId=1 -> 0x01.
    {
        std::printf("Test 5: pre-trigger + start second\n");
        Fig_00_Ext_15 f(V{hdr(false, false, true), 0x01, 0x3F, 0x01});
        CHECK(f.getForm() == Form::PRE_TRIGGER);
        CHECK(f.getPhase() == Phase::PRE_TRIGGER);
        CHECK(f.getSubChId() == 1);
        CHECK(f.hasStartSecond());
        CHECK(f.getStartSecond() == 63);
        CHECK(f.hasStatus());
        CHECK(!f.isLastOfAlertGroup());
        CHECK(f.getStage() == Stage::LEVEL1_START);
        CHECK(f.getIncidentId() == 1);
        CHECK(f.isDiscardHalf()); // P/D=1 set above
        CHECK(!f.isTruncated());
        std::printf("  %s\n", f.description().c_str());
    }

    // 6) Other-ensemble (OE=1) trigger. EId=0x100C; Status: Last=1 Stage=L2Start(100) IId=2 -> 0xC2.
    {
        std::printf("Test 6: other-ensemble trigger\n");
        Fig_00_Ext_15 f(V{hdr(false, true, false), 0x10, 0x0C, 0xC2});
        CHECK(f.getForm() == Form::TRIGGER);
        CHECK(f.isOtherEnsembleAlert());
        CHECK(f.hasEnsembleId());
        CHECK(f.getEnsembleId() == 0x100C);
        CHECK(f.getPhase() == Phase::TRIGGER);
        CHECK(f.hasStatus());
        CHECK(f.getStage() == Stage::LEVEL2_START);
        CHECK(f.getIncidentId() == 2);
        CHECK(f.isLastOfAlertGroup());
        CHECK(!f.isTruncated());
        std::printf("  %s\n", f.description().c_str());
    }

    // 7) Location code with sub-codes + odd NumDigits (padding). SubChId=0.
    //    Status: Last=1 Stage=L1Repeat(010) IId=5 -> 0xA5.
    //    Loc: NFF=1 Zone=1 -> 01 000001 = 0x41; SCF=1 NumDig=1 Digit1=2 -> 1 001 0010 = 0x92;
    //         otherDigits {7}=0111 + pad 0000 -> 0x70; subCodes=0xABCD.
    {
        std::printf("Test 7: loc code with sub-codes + padding\n");
        Fig_00_Ext_15 f(V{hdr(false, false, false), 0x40, 0xA5, 0x41, 0x92, 0x70, 0xAB, 0xCD});
        CHECK(f.getForm() == Form::TRIGGER);
        CHECK(f.getSubChId() == 0);
        CHECK(f.getStage() == Stage::LEVEL1_REPEAT);
        CHECK(f.getIncidentId() == 5);
        CHECK(f.getLocationCodes().size() == 1);
        if (f.getLocationCodes().size() == 1) {
            const auto& lc = f.getLocationCodes()[0];
            CHECK(lc.nff == 1);
            CHECK(lc.zone == 1);
            CHECK(lc.subCodesPresent);
            CHECK(lc.numDigits == 1);
            CHECK(lc.digit1 == 2);
            CHECK(lc.otherDigits.size() == 1);
            CHECK(lc.otherDigits[0] == 7);
            CHECK(lc.subCodes == 0xABCD);
        }
        CHECK(!f.isTruncated());
        std::printf("  %s\n", f.description().c_str());
    }

    // 8) Truncated location code: Test 3 with the final otherDigits byte removed.
    {
        std::printf("Test 8: truncated location code\n");
        Fig_00_Ext_15 f(V{hdr(false, false, false), 0x4A, 0xF0, 0x0B, 0x24});
        CHECK(f.getForm() == Form::TRIGGER);
        CHECK(f.hasStatus());          // status decoded fine
        CHECK(f.getLocationCodes().empty()); // the partial code is dropped
        CHECK(f.isTruncated());        // and flagged
    }

    // 9) Invalid: OE=1 with too-few Id bytes (ficparser guarantees figLength>0, so the empty case
    //    cannot occur — the base class dereferences figData[0] regardless — and is not tested here).
    {
        std::printf("Test 9: invalid input (short OE Id)\n");
        Fig_00_Ext_15 shortOe(V{hdr(false, true, false), 0x10}); // needs 16 EId bits, only 8 present
        CHECK(shortOe.getForm() == Form::INVALID);
        CHECK(!shortOe.isValid());
    }

    // 10) Two location codes back to back (NFF chaining within one FIG).
    //     Id: Trigger SubChId=2 -> 01 000010 = 0x42. Status: Last=0 Stage=L1Start IId=0 -> 0x00.
    //     Loc A: NFF=1 Zone=5 -> 01 000101 = 0x45; SCF=0 NumDig=0 Digit1=3 -> 0 000 0011 = 0x03.
    //     Loc B: NFF=0 Zone=6 -> 00 000110 = 0x06; SCF=0 NumDig=0 Digit1=9 -> 0 000 1001 = 0x09.
    {
        std::printf("Test 10: two location codes\n");
        Fig_00_Ext_15 f(V{hdr(false, false, false), 0x42, 0x00, 0x45, 0x03, 0x06, 0x09});
        CHECK(f.getForm() == Form::TRIGGER);
        CHECK(f.getLocationCodes().size() == 2);
        if (f.getLocationCodes().size() == 2) {
            CHECK(f.getLocationCodes()[0].nff == 1);
            CHECK(f.getLocationCodes()[0].zone == 5);
            CHECK(f.getLocationCodes()[0].numDigits == 0);
            CHECK(f.getLocationCodes()[0].digit1 == 3);
            CHECK(f.getLocationCodes()[1].nff == 0);
            CHECK(f.getLocationCodes()[1].zone == 6);
            CHECK(f.getLocationCodes()[1].digit1 == 9);
        }
        CHECK(!f.isTruncated());
        std::printf("  %s\n", f.description().c_str());
    }

    std::printf("\n%s\n", g_failures == 0 ? "ALL TESTS PASSED" : "TESTS FAILED");
    return g_failures == 0 ? 0 : 1;
}
