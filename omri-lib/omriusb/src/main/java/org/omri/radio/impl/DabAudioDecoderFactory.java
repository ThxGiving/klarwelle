package org.omri.radio.impl;

import android.util.Log;

import java.util.Vector;

import static org.omri.BuildConfig.DEBUG;

/**
 * Copyright (C) 2018 IRT GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Fabian Sattler, IRT GmbH
 */
class DabAudioDecoderFactory implements DabAudioDecoder.DabAudioDecoderStateCallBack {

	private final static String TAG = "DabAudioDecoderFactory";

	private static final DabAudioDecoderFactory mFactoryInstance = new DabAudioDecoderFactory();

	private Vector<DabAudioDecoder> mDecoderInstances = new Vector<>();
	// The decoder kept alive for reuse across service switches (recreated only on a format change).
	private DabAudioDecoder mShared = null;

	private DabAudioDecoderFactory() {
		//nada
	}

	static DabAudioDecoderFactory getInstance() {
		return mFactoryInstance;
	}

	DabAudioDecoder getDecoder(int dabCodec, int samplingRate, int channelCnt, boolean sbr, boolean ps) {
		// Reuse the running codec across DAB service switches when the format is identical — just
		// flush it, instead of the expensive release + recreate (faster switch, no teardown race).
		if(mShared != null && mShared.matches(dabCodec, samplingRate, channelCnt, sbr, ps)) {
			if(DEBUG)Log.d(TAG, "Reusing DabAudioDecoder (same format) — flush");
			mShared.flushForReuse();
			return mShared;
		}
		// Different format (or first use): drop the old shared codec and build a fresh one.
		if(mShared != null) {
			mShared.stopCodec();
			mShared = null;
		}
		DabAudioDecoder retDec = new DabAudioDecoder();
		if(retDec.configure(dabCodec, samplingRate, channelCnt, sbr, ps)) {
			retDec.registerDabAudioDecoderStateCallBack(this);
			mDecoderInstances.add(retDec);
			mShared = retDec;
			if(DEBUG)Log.d(TAG, "Current DabAudioDecoder instances: " + mDecoderInstances.size());
			return retDec;
		}

		if(DEBUG) Log.e(TAG, "Codec creation failed");
		return null;
	}

	void stopAll() {
		if(DEBUG)Log.d(TAG, "Stopping all running DabAudioDecoder instances...");
		mShared = null;
		// Iterate a copy: stopCodec() synchronously fires codecStopped() which removes from the list.
		for(DabAudioDecoder dec : new java.util.ArrayList<>(mDecoderInstances)) {
			dec.stopCodec();
		}
	}

	/**/
	@Override
	public void codecStopped(DabAudioDecoder decoder) {
		if(DEBUG)Log.d(TAG, "Removing stopped DabAudioDecoder");
		mDecoderInstances.remove(decoder);
		if(decoder == mShared) mShared = null;
	}
}
