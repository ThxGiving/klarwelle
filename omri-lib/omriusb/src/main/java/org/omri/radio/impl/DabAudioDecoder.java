package org.omri.radio.impl;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import org.omri.radio.Radio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import de.irt.dabaudiodecoderplugininterface.IDabPluginCallback;
import de.irt.dabaudiodecoderplugininterface.IDabPluginInterface;

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

class DabAudioDecoder {

	private final String TAG = "DabAudioDecoder";

	private final int BUFFER_TIMEOUT = 1000;

	//DAB ASCTy
	private int DAB_CODEC_MP2 = 0;
	private int DAB_CODEC_AAC = 63;

	private final String[] DAB_MIME = {"audio/unknown", "audio/mpeg-l2" /*"audio/mpeg"*/, "audio/mp4a-latm"};

	private MediaCodec mMediaCodec = null;
	private MediaFormat mMediaFormat = null;

	private ByteBuffer[] mInputBuffers = null;
	private ByteBuffer[] mOutputBuffers = null;
	private MediaCodec.BufferInfo mBufferInfo = null;

	private Thread mDecodeThread = null;
	private volatile boolean mDecode = false;

	private volatile DabDecoderCallback mCallback = null;

	private boolean mHasBuiltInMpegDec = false;
	private boolean mHasMpegDecPlug = false;

	private int mConfCodec = 0;
	private int mConfSampling = 0;
	private int mConfChans = 0;
	private boolean mConfSbr = false;
	private boolean mConfPs = false;

	private ConcurrentLinkedQueue<byte[]> mDataQ = new ConcurrentLinkedQueue<>();

	// Mid-stream self-heal: how many consecutive in-place codec rebuilds to attempt before giving
	// up. Reset to 0 on any successful PCM output, so only a rapid burst of failures (a genuinely
	// broken stream) trips the limit — a lone glitch from weak reception heals silently.
	private int mCodecErrorCount = 0;
	private static final int MAX_CODEC_REBUILDS = 5;

	// Codec reuse across DAB service switches: a switch to a same-format service flushes THIS running
	// codec instead of releasing + recreating it (faster, and avoids the teardown/rebuild race). The
	// flush itself is done on the decode thread (MediaCodec isn't thread-safe) via this flag.
	private volatile boolean mFlushRequested = false;

	/** True if this running AAC codec already matches the wanted format — then it can just be flushed. */
	boolean matches(int dabCodec, int samplingRate, int channelCnt, boolean sbr, boolean ps) {
		return mMediaCodec != null && mDecode &&
			mConfCodec == dabCodec && mConfSampling == samplingRate &&
			mConfChans == channelCnt && mConfSbr == sbr && mConfPs == ps;
	}

	/** Reuse this codec for the next service: drop pending input and flush (on the decode thread). */
	void flushForReuse() {
		mDataQ.clear();
		mFlushRequested = true;
	}

	DabAudioDecoder() {
		if(DEBUG)Log.d(TAG, "Creating new decoder instance");

		if(!mHasBuiltInMpegDec) {
			mHasMpegDecPlug = mpegDecPluginInstalled2();
			if(DEBUG)Log.d(TAG, "MPEG DEcoder service bound: " + mHasMpegDecPlug);
		}
	}

	int getConfChans() {
		return mConfChans;
	}

	int getConfCodec() {
		return mConfCodec;
	}

	int getConfSampling() {
		return mConfSampling;
	}

	boolean getConfSbr() {
		return mConfSbr;
	}

	boolean getConfPs() {
		return mConfPs;
	}

	//Not used because most on-board codecs don't decode correctly
	private boolean hasMpegL2Codec() {
		for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
			MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

			if(codecInfo.isEncoder()) {
				//dont need encoder
				continue;
			}

			if(DEBUG)Log.d(TAG, "AvailableCodec Name: " + codecInfo.getName());
			String[] supportedTypes = codecInfo.getSupportedTypes();
			for(String supType : supportedTypes) {
				if(DEBUG)Log.d(TAG, "AvailableCodec SupportedType: " + supType);
				if(supType.equalsIgnoreCase(DAB_MIME[1])) {
					if(DEBUG)Log.d(TAG, "Found MPEG L2 Codec...yippie!");
					return true;
				}
			}
		}

		return false;
	}

	private boolean mpegDecPluginInstalled() {
		if(DEBUG)Log.d(TAG, "Searching installed Codec Plugins!");

		PackageManager packageManager = ((RadioImpl)Radio.getInstance()).mContext.getPackageManager();
		List<ApplicationInfo> apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
		for(ApplicationInfo appInfo : apps) {
			if(appInfo.packageName.equalsIgnoreCase("de.irt.dabmpg123decoderplugin")) {
				if(DEBUG)Log.d(TAG, "Found MPEG L2 Codec Plugin...binding service!");
				return bindDecoderService();
			}
		}

		return false;
	}

	private boolean mpegDecPluginInstalled2() {
		PackageManager packageManager = ((RadioImpl)Radio.getInstance()).mContext.getPackageManager();
		if(packageManager != null) {
			List<PackageInfo> pkgs = packageManager.getInstalledPackages(PackageManager.GET_SERVICES);
			for(PackageInfo pkg : pkgs) {
				if(pkg != null && pkg.services != null) {
					for(ServiceInfo srvInfo : pkg.services) {
						if(srvInfo != null && srvInfo.name != null) {
							if (srvInfo.name.equalsIgnoreCase("de.irt.dabmpg123decoderplugin.Mpg123Decoder")) {
								if(DEBUG)Log.d(TAG, "Found MPEG L2 Codec Plugin...binding service!");
								return bindDecoderService2(pkg.packageName, srvInfo.name);
							}
						}
					}
				}
			}
		}

		return false;
	}

	private IDabPluginInterface mDecoderService;
	private DabDecoderServiceConnection mDecoderConnection;
	private boolean mDecoderServiceBound = false;
	private boolean bindDecoderService() {
		if(DEBUG)Log.d(TAG, "Binding service!");

		mDecoderConnection = new DabDecoderServiceConnection();
		final Intent srvIntent = new Intent("de.irt.dabmpg123decoderplugin.Mpg123Decoder");
		srvIntent.setPackage("de.irt.dabmpg123decoderplugin");

		Thread t = new Thread(){
			public void run(){
				boolean bindRet = ((RadioImpl)Radio.getInstance()).mContext.bindService(srvIntent, mDecoderConnection, Context.BIND_AUTO_CREATE);
			}
		};
		t.start();

		return ((RadioImpl)Radio.getInstance()).mContext.bindService(srvIntent, mDecoderConnection, Context.BIND_AUTO_CREATE);
	}

	private boolean bindDecoderService2(String packageName, String serviceName) {
		if(DEBUG)Log.d(TAG, "Binding service!");

		mDecoderConnection = new DabDecoderServiceConnection();
		final Intent srvIntent = new Intent(serviceName);
		srvIntent.setPackage(packageName);

		Thread t = new Thread(){
			public void run(){
				boolean bindRet = ((RadioImpl)Radio.getInstance()).mContext.bindService(srvIntent, mDecoderConnection, Context.BIND_AUTO_CREATE);
			}
		};
		t.start();

		return ((RadioImpl)Radio.getInstance()).mContext.bindService(srvIntent, mDecoderConnection, Context.BIND_AUTO_CREATE);
	}

	private void unbindDecoderService() {
		if(mDecoderServiceBound) {
			((RadioImpl)Radio.getInstance()).mContext.unbindService(mDecoderConnection);
			mDecoderServiceBound = false;
		}
	}

	class DabDecoderServiceConnection implements ServiceConnection {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			if(DEBUG)Log.d(TAG, "onServiceConnected: " + name.toString());

			mDecoderServiceBound = true;
			mDecoderService = IDabPluginInterface.Stub.asInterface(service);
			try {
				mDecoderService.setCallback(mDecSrvCallback);
			} catch(RemoteException remExc) {
				remExc.printStackTrace();
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			if(DEBUG)Log.d(TAG, "onServiceDisconnected: " + name.toString());
			mDecoderServiceBound = false;
			mDecoderService = null;
		}
	}

	private final IDabPluginCallback.Stub mDecSrvCallback = new IDabPluginCallback.Stub() {

		@Override
		public void decodedPcmData(byte[] pcmData) throws RemoteException {
			if(DEBUG)Log.d(TAG, "Decoderservice audiodata: " + pcmData.length);
			DabDecoderCallback cb = mCallback;
			if(cb != null) cb.decodedAudioData(pcmData, mOutputSampling, mOutputChannels);
		}
	};

	synchronized void setCodecCallback(DabDecoderCallback codecCallback) {
		mDataQ.clear();
		mCallback = codecCallback;
	}

	void feedData(byte[] audioData) {
		if(mConfCodec == DAB_CODEC_AAC || mHasBuiltInMpegDec) {
			mDataQ.offer(audioData);
		} else if(mHasMpegDecPlug) {
			try {
				if(mDecoderService != null && mDecoderServiceBound) {
					mDecoderService.enqueueEncodedData(audioData);
				} else {
					audioData = null;
				}
			} catch(RemoteException remExc) {
				if(DEBUG)Log.d(TAG, "DecoderService RemoteException: " + remExc.getMessage());
				if(DEBUG)remExc.printStackTrace();
			}
		}
	}

	void stopCodec() {
		stopDecodeThread();

		if(mMediaCodec != null) {
			if(DEBUG)Log.d(TAG, "Stopping MediaCodec");
			//mMediaCodec.flush();
			mMediaCodec.stop();
			mMediaCodec.release();
			mMediaCodec = null;
		} else {
			if(DEBUG)Log.w(TAG, "Stopping codec MediaCodec is null");
		}

		unbindDecoderService();

		for(DabAudioDecoderStateCallBack cb : mCodecStateCallbacks) {
			if(cb != null) {
				cb.codecStopped(this);
			}
		}
	}

	private void stopDecodeThread() {
		mDecode = false;

		if(mDecodeThread != null) {
			if(DEBUG)Log.d(TAG, "Stopping DecodeThread");

			if(mDecodeThread.isAlive()) {
				mDecodeThread.interrupt();
				try {
					mDecodeThread.join(2000);
				} catch(InterruptedException interExc) {
					if(DEBUG)Log.d(TAG, "InterruptedException while joining decodethread");
				}
			}
		}
	}

	boolean configure(int dabCodec, int samplingRate, int channelCnt, boolean sbr, boolean ps) {
		if(DEBUG)Log.d(TAG, "Configuring Codec: "+ dabCodec + " with: " + samplingRate + " kHz, " + channelCnt + " Channels and SBR: " + sbr);
		if(dabCodec == DAB_CODEC_MP2) {
			mOutputChannels = channelCnt;
			mOutputSampling = samplingRate;
		}

		if(DEBUG)Log.d(TAG, "Reconfiguring Decoder!");

		mDecode = false;
		if(mDecodeThread != null) {
			if(DEBUG)Log.d(TAG, "Stopping DecodeThread");

			if(mDecodeThread.isAlive()) {
				mDecodeThread.interrupt();
				try {
					mDecodeThread.join(2000);
				} catch(InterruptedException interExc) {
					if(DEBUG)Log.d(TAG, "InterruptedException while joining decodethread");
				}
			}
		}

		mDataQ.clear();

		if(mMediaCodec != null) {
			if(DEBUG)Log.d(TAG, "Stopping MediaCodec");

			mMediaCodec.stop();
			mMediaCodec.release();
			mMediaCodec = null;
		}

		mConfCodec = dabCodec;
		mConfSampling = samplingRate;
		mConfChans = channelCnt;
		mConfSbr = sbr;
		mConfPs = ps;

		if(mConfCodec == DAB_CODEC_AAC || mHasBuiltInMpegDec) {
			creatMediaFormat();

			if(mMediaCodec == null) {
				return false;
			}

			mDecodeThread = new Thread(DecoderRunnable);
			mDecodeThread.start();
		}


		mDataQ.clear();

		return true;
	}

	private void creatMediaFormat() {
		if(mConfCodec == DAB_CODEC_AAC) {
			mMediaFormat = MediaFormat.createAudioFormat(DAB_MIME[2], mConfSampling, mConfChans);
		}
		if(mConfCodec == DAB_CODEC_MP2) {
			mMediaFormat = MediaFormat.createAudioFormat(DAB_MIME[1], mConfSampling, mConfChans);
		}

		if(mConfCodec == DAB_CODEC_AAC) {
			byte[] ascBytes = null;

			if(mConfSbr) {
				if(!mConfPs) {
					if(DEBUG)Log.d(TAG, "Configuring ASC with SBR!");
					ascBytes = new byte[]{(byte) 0x2B, (byte)0x11, (byte)0x8A, (byte)0x00};
				} else {
					if(DEBUG)Log.d(TAG, "Configuring ASC with SBR and PS!");
					ascBytes = new byte[]{(byte) 0xEB, (byte) 0x11, (byte) 0x8A, (byte) 0x00};
				}
			} else {
				if(DEBUG)Log.d(TAG, "Configuring ASC without SBR!");
				ascBytes = new byte[]{(byte) 0x11, (byte)0x94, (byte)0x00, (byte)0x00};
			}

			if(mConfSampling == 32000) {
				if(DEBUG)Log.d(TAG, "Configuring ASC for 32 kHz!");
				ascBytes[0] = (byte)(ascBytes[0] + 1);
				if(mConfSbr) {
					if(DEBUG)Log.d(TAG, "Configuring ASC for 32 kHz and SBR!");
					ascBytes[1] = (byte)(ascBytes[1] + 1);
				}
			}

			if(mConfChans == 1) {
				if(DEBUG)Log.d(TAG, "Configuring ASC for Mono!");
				ascBytes[1] = (byte)(ascBytes[1] - 8);
			}

			ByteBuffer ascBuffer = ByteBuffer.wrap(ascBytes);
			mMediaFormat.setByteBuffer("csd-0", ascBuffer);
		}

		buildAndStartMediaCodec();
	}

	/**
	 * Creates, configures and starts an AAC {@link MediaCodec} from the already-populated
	 * {@link #mMediaFormat} and refreshes the input/output buffer handles. Extracted so the
	 * mid-stream self-heal in {@link #decode()} can rebuild the codec in place after a transient
	 * error, using the exact same format, without restarting the decode thread. On failure
	 * {@link #mMediaCodec} is left null.
	 */
	private void buildAndStartMediaCodec() {
		try {
			//
			for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
				MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

				if(codecInfo.isEncoder()) {
					//dont need encoder
					continue;
				}

				if(DEBUG)Log.d(TAG, "AvailableCodec Name: " + codecInfo.getName());

				if(codecInfo.getName().equals("OMX.google.aac.decoder")) {
					if(DEBUG)Log.d(TAG, "Found Google AAC decoder...choosing this one...");
					mMediaCodec = MediaCodec.createByCodecName(codecInfo.getName());
					break;
				}
			}

			if(mMediaCodec == null) {
				if(DEBUG)Log.d(TAG, "MediaCodec createByCodecName failed, falling back to createDecoderByType");
				mMediaCodec = MediaCodec.createDecoderByType(mMediaFormat.getString(MediaFormat.KEY_MIME));
			}
			//

		} catch(IOException ioExc) {
			if(DEBUG)ioExc.printStackTrace();
		}
		if(mMediaCodec != null) {
			if(DEBUG)if(Build.VERSION.SDK_INT >= 	Build.VERSION_CODES.KITKAT)Log.d(TAG, "MediaCodecName: " + mMediaCodec.getName());
			try {
				mMediaCodec.configure(mMediaFormat, null, null, 0);
				mMediaCodec.start();

				mInputBuffers = mMediaCodec.getInputBuffers();
				mOutputBuffers = mMediaCodec.getOutputBuffers();

				mBufferInfo = new MediaCodec.BufferInfo();
			} catch(IllegalStateException illStatExc) {
				if(DEBUG)illStatExc.printStackTrace();
				Log.e(TAG, "MediaCodec IllegalStateException: " + illStatExc.getMessage());
				try { mMediaCodec.release(); } catch (Throwable ignore) {}
				mMediaCodec = null;
			} catch(IllegalArgumentException illArgExc) {
				if(DEBUG)illArgExc.printStackTrace();
				Log.e(TAG, "MediaCodec IllegalArgumentException: " + illArgExc.getMessage());
				try { mMediaCodec.release(); } catch (Throwable ignore) {}
				mMediaCodec = null;
			}
		} else {
			if(DEBUG)Log.d(TAG, "Configuring MediaCodec is null!");
		}
	}

	void decodeData(byte[] encodedAudioData) {
		if(mConfCodec == DAB_CODEC_AAC) {
			mDataQ.offer(encodedAudioData);
		} else {
			try {
				if(mDecoderService != null) {
					mDecoderService.enqueueEncodedData(encodedAudioData);
				}
			} catch(RemoteException remExc) {

			}
		}
	}

	Runnable DecoderRunnable = new Runnable() {

		@Override
		public void run() {
			if(DEBUG)Log.d(TAG, "Starting DecodeThread");
			mDecode = true;
			decode();
		}
	};

	private void decode() {
		while(mDecode) {
		  try {
			if(mFlushRequested) {
				mFlushRequested = false;
				mMediaCodec.flush();        // service switch (same format): drop old audio, reuse codec
			}
			if(!mDataQ.isEmpty()) {
				int inbufIdx = mMediaCodec.dequeueInputBuffer(BUFFER_TIMEOUT);
				if (inbufIdx >= 0) {
					mInputBuffers[inbufIdx].clear();

					byte[] audioBuf = mDataQ.poll();
					mInputBuffers[inbufIdx].put(audioBuf);
					mMediaCodec.queueInputBuffer(inbufIdx, 0, audioBuf.length, 0, 0);
				}
			}

			int outbufIdx = mMediaCodec.dequeueOutputBuffer(mBufferInfo, BUFFER_TIMEOUT);
			switch (outbufIdx) {
				case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED: {
					mOutputBuffers = mMediaCodec.getOutputBuffers();
					break;
				}
				case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED: {
					MediaFormat format = mMediaCodec.getOutputFormat();
					mOutputSampling = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
					mOutputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

					if(DEBUG)Log.d(TAG, "Outputformat Changed: Sampling: " + mOutputSampling + " Chans: " + mOutputChannels);
					DabDecoderCallback cbf = mCallback;
					if(cbf != null) cbf.outputFormatChanged(mOutputSampling, mOutputChannels);
					break;
				}
				case MediaCodec.INFO_TRY_AGAIN_LATER: {

					break;
				}
				default: {
					ByteBuffer pcmBuffer = mOutputBuffers[outbufIdx];

					final byte[] pcmData = new byte[mBufferInfo.size];
					pcmBuffer.get(pcmData);
					pcmBuffer.clear();

					DabDecoderCallback cbd = mCallback;
					if(cbd != null) cbd.decodedAudioData(pcmData, mMediaCodec.getOutputFormat().getInteger(MediaFormat.KEY_SAMPLE_RATE), mMediaCodec.getOutputFormat().getInteger(MediaFormat.KEY_CHANNEL_COUNT));
					mMediaCodec.releaseOutputBuffer(outbufIdx, false);
					mCodecErrorCount = 0;   // sustained playback clears the self-heal strike count
					break;
				}
			}
		  } catch (IllegalStateException ise) {
			try { if(mMediaCodec != null) mMediaCodec.release(); } catch (Throwable ignore) {}
			mMediaCodec = null;

			if(!mDecode) {
				// Intentional teardown: a DAB service switch / shutdown set mDecode=false and released
				// the codec under us. Exit cleanly — the factory rebuilds a fresh decoder on the next
				// getDecoder(). (matches() already returns false now that mMediaCodec is null.)
				if(DEBUG)Log.w(TAG, "MediaCodec released during teardown, stopping decode: " + ise.getMessage());
				break;
			}

			// Genuine mid-stream error while still meant to be playing (typically a corrupt AAC access
			// unit from weak reception). Previously this killed the decode thread and left the audio
			// silent until the user manually switched stations. Instead self-heal: drop the poisoned
			// data, rebuild the codec in place from the same format, and keep decoding on this thread.
			// Bounded so a persistently broken stream can't spin in a tight rebuild loop.
			if(++mCodecErrorCount > MAX_CODEC_REBUILDS) {
				Log.e(TAG, "MediaCodec error limit (" + MAX_CODEC_REBUILDS + ") reached, stopping decode: " + ise.getMessage());
				mDecode = false;
				break;
			}
			Log.w(TAG, "MediaCodec error mid-stream, rebuilding in place (attempt " + mCodecErrorCount + "): " + ise.getMessage());
			mDataQ.clear();
			buildAndStartMediaCodec();
			if(mMediaCodec == null) {
				// Rebuild itself failed — give up cleanly rather than NPE on the next loop iteration.
				Log.e(TAG, "MediaCodec rebuild failed, stopping decode");
				mDecode = false;
				break;
			}
		  }
		}

		if(DEBUG)Log.d(TAG, "Decodethread ended");
	}

	private int mOutputChannels = 0;
	private int mOutputSampling = 0;
	interface DabDecoderCallback {
		void decodedAudioData(final byte[] pcmData, final int samplerate, final int channels);
		void outputFormatChanged(int sampleRate, int chanCnt);
	}

	private transient ArrayList<DabAudioDecoderStateCallBack> mCodecStateCallbacks = new ArrayList<>();
	void registerDabAudioDecoderStateCallBack(DabAudioDecoderStateCallBack stateCb) {
		if(!mCodecStateCallbacks.contains(stateCb)) {
			mCodecStateCallbacks.add(stateCb);
		}
	}

	void unregisterDabAudioDecoderStateCallBack(DabAudioDecoderStateCallBack stateCb) {
		mCodecStateCallbacks.remove(stateCb);
	}

	interface DabAudioDecoderStateCallBack {

		void codecStopped(DabAudioDecoder decoder);
	}
}
