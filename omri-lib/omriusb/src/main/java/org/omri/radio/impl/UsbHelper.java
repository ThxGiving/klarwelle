package org.omri.radio.impl;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.util.Pair;

import org.omri.radioservice.RadioServiceDab;
import org.omri.radioservice.RadioServiceDabEdi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

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

public class UsbHelper {

	private static final String TAG = "UsbHelper";
	private static final String ACTION_USB_PERMISSION = "de.irt.usbhelper.USB_PERMISSION";

	private final Context mContext;

	private static UsbHelper mInstance = null;
	private static UsbHelperCallback mUsbCb = null;

	private UsbManager mUsbManager;
	private PendingIntent mUsbPermissionIntent;

	private HashMap<String, UsbDevice> mUsbDeviceList;

	static {
		System.loadLibrary("c++_shared");
		System.loadLibrary("fec");
		System.loadLibrary("irtdab");
	}

	private native void created();
	/** Tee native std::cout into this file so omri's logs can be read off the stick without adb. */
	private native void setLogFile(String path);
	/** Install a native crash handler that writes signal + backtrace to this file on SIGSEGV etc. */
	private native void installNativeCrashLogger(String path);
	private native void deviceDetached(String deviceName);
	private native void deviceAttached(TunerUsb usbDevice);
	private native void devicePermission(String deviceName, boolean granted);
	private native void startSrv(String deviceName, RadioServiceDab service);
	private native void stopSrv(String deviceName);
	private native void tuneFreq(String deviceName, long freq);
	private native void startServiceScan(String deviceName);
	private native void stopServiceScan(String deviceName);

	/* EdiStream (TunerEdistream stripped; raw byte[] stream kept for native compatibility) */
	private native void ediStreamData(byte[] ediData, int size);
	private native void ediFlushBuffer();

	private UsbHelper(Context context) {
		if(DEBUG)Log.d(TAG, "Contructing UsbHelper...");
		mContext = context.getApplicationContext();

		if(mContext != null) {
			mUsbManager = (UsbManager)mContext.getSystemService(Context.USB_SERVICE);
			// USB permission PendingIntents MUST stay mutable — the system writes EXTRA_DEVICE
			// into them. FLAG_IMMUTABLE here makes the extra arrive as null (NPE in onReceive).
			// A flag is only mandatory from API 31 (S), and there it must be FLAG_MUTABLE.
			int piFlags = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
					? PendingIntent.FLAG_MUTABLE : 0;
			// Android 14+ forbids a MUTABLE PendingIntent with an *implicit* Intent, so make it
			// explicit by targeting our own package. Mutability itself must stay (see above).
			Intent permissionIntent = new Intent(ACTION_USB_PERMISSION)
					.setPackage(mContext.getPackageName());
			mUsbPermissionIntent = PendingIntent.getBroadcast(mContext, 0, permissionIntent, piFlags);

			IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
			filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
			filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);

			// API 33+ (Android 14 enforced): dynamic receivers must declare export state.
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
				mContext.registerReceiver(mUsbBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
			} else {
				mContext.registerReceiver(mUsbBroadcastReceiver, filter);
			}
			created();
			// Mirror native std::cout into an app-internal file; the app copies it onto the stick.
			try {
				setLogFile(new java.io.File(mContext.getFilesDir(), "px6-omri.txt").getAbsolutePath());
			} catch (Throwable t) {
				// best-effort diagnostics only
			}
			// Native crash logger: write a native SIGSEGV/SIGABRT breadcrumb DIRECTLY to the USB stick
			// (or the primary external files dir) so it survives a process-killing crash without adb.
			try {
				java.io.File dir = pickCrashDir();
				if (dir != null) {
					installNativeCrashLogger(new java.io.File(dir, "px6-nativecrash.txt").getAbsolutePath());
				}
			} catch (Throwable t) {
				// best-effort diagnostics only
			}
		}
	}

	/** A writable external files dir for the native crash log — prefer a removable one (USB stick),
	 *  which is where diagnostics are read from, else the primary. Null if none is available. */
	private java.io.File pickCrashDir() {
		try {
			java.io.File[] dirs = mContext.getExternalFilesDirs(null);
			if (dirs != null) {
				// A removable volume (the USB stick) is the last entry; prefer it so the crash lands
				// straight on the stick.
				for (int i = dirs.length - 1; i >= 0; i--) {
					if (dirs[i] != null && dirs[i].exists()) return dirs[i];
				}
			}
		} catch (Throwable t) { /* fall through */ }
		return mContext.getExternalFilesDir(null);
	}

	public void scanUsbDevices() {
		if(mUsbManager != null) {
			mUsbDeviceList = mUsbManager.getDeviceList();
			Iterator<UsbDevice> udevIter = mUsbDeviceList.values().iterator();
			while(udevIter.hasNext()) {
				UsbDevice device = udevIter.next();
				String devName = device.getDeviceName();
				int devPid = device.getProductId();
				int devVid = device.getVendorId();
				int devIfCount = device.getInterfaceCount();

				if(DEBUG)Log.d(TAG, "DeviceName: " + devName);
				if(DEBUG)Log.d(TAG, "DevicePid: " + devPid);
				if(DEBUG)Log.d(TAG, "DeviceVid: " + devVid);
				if(DEBUG)Log.d(TAG, "DeviceIfCount: " + devIfCount);
			}
		}
	}

	List<UsbDevice> scanForSpecificDevices(List<Pair<Integer, Integer>> usbVendorDeviceIdParams) {
		ArrayList<UsbDevice> foundSpecificDevices = new ArrayList<>();

		if(mUsbManager != null) {
			mUsbDeviceList = mUsbManager.getDeviceList();

			for(UsbDevice dev : mUsbDeviceList.values()) {
				int devVenId = dev.getVendorId();
				int devPId = dev.getProductId();
				if(DEBUG)Log.d(TAG, " Found USB Device: VId: " + devVenId + " and PId: " + devPId);
				for(Pair<Integer, Integer> devVenPId : usbVendorDeviceIdParams) {
					if(DEBUG)Log.d(TAG, "Searching for USB VId: " + devVenPId.first + " and PId: " + devVenPId.second);
					if(devVenId == devVenPId.first && devPId == devVenPId.second) {
						if(DEBUG)Log.d(TAG, "Found specific device");
						foundSpecificDevices.add(dev);
					}
				}
			}
		}

		return foundSpecificDevices;
	}

	public static UsbHelper getInstance() {
		return mInstance;
	}

	public void startService(String deviceName, RadioServiceDab srv) {
		if(DEBUG)Log.d(TAG, "StartService on device: " + deviceName + " : " + srv.getServiceLabel());
		startSrv(deviceName, srv);
	}

	/* EdiStream */
	void ediStream(byte[] ediData, int size) {
		ediStreamData(ediData, size);
	}

	void flushEdiData() {
		ediFlushBuffer();
	}

	public void stopService(String deviceName) {
		stopSrv(deviceName);
	}

	public void tuneFrequencyKHz(String deviceName, long frequency) {
		tuneFreq(deviceName, frequency);
	}

	void startEnsembleScan(String deviceName) {
		startServiceScan(deviceName);
	}

	void stopEnsembleScan(String deviceName) {
		stopServiceScan(deviceName);
	}

	void attachDevice(TunerUsb dev) {
		deviceAttached(dev);
	}

	private boolean mPermissionPending = false;
	private UsbDevice mPendingPermissionDevice = null;

	private void requestPermission(UsbDevice device) {
		// Skip the dialog when permission is already granted — Android persists it for this (app,
		// device) pair once the user picks "always open" on the USB_DEVICE_ATTACHED association, so it
		// carries across app restarts. Without this check omri popped the dialog on every launch.
		if(mUsbManager != null && mUsbManager.hasPermission(device)) {
			if(DEBUG)Log.d(TAG, "Permission already granted for device: " + device.getDeviceName());
			devicePermission(device.getDeviceName(), true);
			return;
		}
		// Try to self-grant WITHOUT a dialog. UsbManager.grantPermission(device) is @SystemApi guarded
		// by the signature|privileged MANAGE_USB permission — a no-op that throws on a normal install,
		// but head-unit ROMs are often permissive (same reason CarManager reflection works). If it
		// takes, the per-boot USB dialog is gone for good; otherwise we fall through to the prompt.
		if(tryPrivilegedGrant(device)) {
			Log.i(TAG, "USB permission self-granted (privileged) for " + device.getDeviceName());
			devicePermission(device.getDeviceName(), true);
			return;
		}
		if(mPermissionPending) {
			mPendingPermissionDevice = device;
		} else if(mPromptedThisSession) {
			// Belt & suspenders: enumeration at init AND a later USB_DEVICE_ATTACHED broadcast can both
			// reach here. We already showed the dialog once this process — don't nag a second time.
			if(DEBUG)Log.d(TAG, "USB dialog already shown this session, not re-prompting");
		} else {
			if(DEBUG)Log.d(TAG, "Requesting permission for device: " + device.getDeviceName());

			mPermissionPending = true;
			mPromptedThisSession = true;
			mUsbManager.requestPermission(device, mUsbPermissionIntent);
		}
	}

	/** True once the runtime USB dialog has been shown in this process — at most one prompt per session. */
	private volatile boolean mPromptedThisSession = false;

	/** Reflectively call the @SystemApi UsbManager.grantPermission(device); returns true only if we
	 *  actually hold MANAGE_USB (system/privileged install or a permissive ROM). Silent on failure. */
	private boolean tryPrivilegedGrant(UsbDevice device) {
		if(mUsbManager == null) return false;
		try {
			java.lang.reflect.Method m = android.hardware.usb.UsbManager.class
					.getMethod("grantPermission", UsbDevice.class);
			m.invoke(mUsbManager, device);
			return mUsbManager.hasPermission(device);
		} catch(Throwable t) {
			if(DEBUG)Log.d(TAG, "privileged USB grant unavailable: " + t);
			return false;
		}
	}

	private UsbDeviceConnection openDevice(UsbDevice device) {
		if(DEBUG)Log.d(TAG, "Opening device: " + device.getDeviceName());
		try {
			return mUsbManager.openDevice(device);
		} catch(SecurityException secExc) {
			secExc.printStackTrace();
			return null;
		}

	}

	static void create(Context context, UsbHelperCallback cb) {
		if(mInstance == null) {
			mInstance = new UsbHelper(context);
			mUsbCb = cb;
		}
	}

	void removeDevice(UsbDevice remDev) {
		if(remDev != null) {
			deviceDetached(remDev.getDeviceName());
		}
	}

	private final BroadcastReceiver mUsbBroadcastReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
			String action = intent.getAction();
			// Defensive: without EXTRA_DEVICE every branch below would NPE and kill the app.
			if (device == null) {
				Log.w(TAG, "USB broadcast " + action + " without EXTRA_DEVICE - ignored");
				mPermissionPending = false;
				return;
			}
			synchronized (this) {
				if (ACTION_USB_PERMISSION.equals(action)) {
					if(DEBUG)Log.d(TAG, "Received Permission request: " + action);

					mPermissionPending = false;

					if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						if(DEBUG)Log.d(TAG, "permission granted for device " + device.getDeviceName());
						devicePermission(device.getDeviceName(), true);
					} else {
						if(DEBUG)Log.d(TAG, "permission denied for device " + device.getDeviceName());
						devicePermission(device.getDeviceName(), false);
					}

					if(device.equals(mPendingPermissionDevice)) {
						mPendingPermissionDevice = null;
					}

					if(mPendingPermissionDevice != null) {
						requestPermission(mPendingPermissionDevice);
					}
				}
				if (action.equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
					if(DEBUG)Log.d(TAG, "USB Device detached: " + device.getDeviceName());
					deviceDetached(device.getDeviceName());
					mUsbCb.UsbTunerDeviceDetached(device);
				}
				if (action.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
					if(DEBUG)Log.d(TAG, "USB Device attached: " + device.getDeviceName());

					mUsbCb.UsbTunerDeviceAttached(device);
				}
			}
		}
	};

	interface UsbHelperCallback {

		void UsbTunerDeviceAttached(UsbDevice attachedDevice);

		void UsbTunerDeviceDetached(UsbDevice detachedDevice);
	}
}
