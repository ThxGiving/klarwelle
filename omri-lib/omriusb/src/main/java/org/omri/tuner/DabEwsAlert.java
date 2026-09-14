package org.omri.tuner;

/**
 * A decoded FIG 0/15 instance — DAB Emergency Warning System (EWS), ETSI TS 104 089. Delivered to
 * {@link TunerListener#tunerDabEwsAlert(Tuner, DabEwsAlert)} for every FIG 0/15 the tuned ensemble
 * carries, including the heartbeat form that merely marks the ensemble as EWS-capable.
 *
 * This is a thin, immutable carrier for the fields the native decoder surfaces. The receiver-side
 * policy (location-code matching, alert playback) lives above the omri library. The consumer brand
 * for EWS is "ASA" (Automatic Safety Alert).
 *
 * Licensed under the Apache License, Version 2.0.
 */
public class DabEwsAlert {

	/** Overall shape of the FIG 0/15 (mirrors the native decoder's Form enum). */
	public static final int FORM_INVALID = 0;
	public static final int FORM_HEARTBEAT = 1;
	public static final int FORM_PRE_TRIGGER = 2;
	public static final int FORM_TRIGGER = 3;
	public static final int FORM_SUSTAIN = 4;
	public static final int FORM_END = 5;

	/** EWS alert stage (mirrors the native Stage enum). {@link #STAGE_NONE} means no Status field. */
	public static final int STAGE_NONE = -1;
	public static final int STAGE_LEVEL1_START = 0;
	public static final int STAGE_LEVEL1_UPDATE = 1;
	public static final int STAGE_LEVEL1_REPEAT = 2;
	public static final int STAGE_LEVEL1_CRITICAL = 3;
	public static final int STAGE_LEVEL2_START = 4;
	public static final int STAGE_LEVEL2_UPDATE = 5;
	public static final int STAGE_LEVEL2_REPEAT = 6;
	public static final int STAGE_TEST = 7;

	private final int mForm;
	private final boolean mOtherEnsemble;
	private final int mIdValue;      // SubChId (tuned ensemble) or EId (other ensemble)
	private final int mStage;        // STAGE_* or STAGE_NONE
	private final int mIncidentId;   // -1 when no Status field
	private final boolean mTest;
	private final boolean mTruncated;
	private final String mDescription;
	private final String[] mLocationCodes;
	private final int mTunedEnsembleId;

	public DabEwsAlert(int form, boolean otherEnsemble, int idValue, int stage, int incidentId,
					   boolean test, boolean truncated, String description, String[] locationCodes,
					   int tunedEnsembleId) {
		mTunedEnsembleId = tunedEnsembleId;
		mForm = form;
		mOtherEnsemble = otherEnsemble;
		mIdValue = idValue;
		mStage = stage;
		mIncidentId = incidentId;
		mTest = test;
		mTruncated = truncated;
		mDescription = description != null ? description : "";
		mLocationCodes = locationCodes != null ? locationCodes : new String[0];
	}

	/** One of the {@code FORM_*} constants. */
	public int getForm() { return mForm; }

	/** True when this FIG only signals EWS participation (no active alert). */
	public boolean isHeartbeat() { return mForm == FORM_HEARTBEAT; }

	/** True when the alert audio is in another ensemble (OE flag set); then {@link #getEnsembleId()}
	 *  is valid instead of {@link #getSubChannelId()}. */
	public boolean isOtherEnsembleAlert() { return mOtherEnsemble; }

	/** Sub-channel carrying the alert audio in the tuned ensemble, or -1 for an other-ensemble alert. */
	public int getSubChannelId() { return mOtherEnsemble ? -1 : mIdValue; }

	/** EId of the ensemble carrying the alert audio, or -1 for a tuned-ensemble alert. */
	public int getEnsembleId() { return mOtherEnsemble ? mIdValue : -1; }

	/** One of the {@code STAGE_*} constants, or {@link #STAGE_NONE} when no Status field is present. */
	public int getStage() { return mStage; }

	/** Incident identifier (constant across an incident's stages), or -1 when no Status field. */
	public int getIncidentId() { return mIncidentId; }

	/** True for the Test stage — the ASA home-test, not a real emergency. */
	public boolean isTest() { return mTest; }

	/** True when the FIG was truncated mid-decode: the fields above are usable, the alert area may not. */
	public boolean isTruncated() { return mTruncated; }

	/** Human-readable one-line summary, as produced by the native decoder. */
	public String getDescription() { return mDescription; }

	/** The alert area as DAB location codes in "zone:digithex" form (e.g. "1:92c"), or empty for a
	 *  whole-ensemble alert. Additive; matched left-aligned per ETSI TS 104 089 §7.5.4. */
	public String[] getLocationCodes() { return mLocationCodes; }

	/** EId of the ensemble the tuner was on when this FIG 0/15 was received — i.e. an EWS-participating
	 *  ensemble. Lets a receiver record which ensembles support EWS during scanning or listening. */
	public int getTunedEnsembleId() { return mTunedEnsembleId; }

	@Override
	public String toString() { return mDescription; }
}
