package com.espressif.espblemesh.ui;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.espressif.blemesh.utils.MeshUtils;
import com.espressif.espblemesh.model.BleScanResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import libs.espressif.log.EspLog;

public class MainService extends Service {
    private static final long SCAN_RESULT_KEEP_TIME = 60_000L;
    private static final long SCAN_RESULT_CHECK_INTERVAL = 30_000L;

    private EspLog mLog = new EspLog(getClass());

    private boolean mBTEnable;
    private boolean mScanning;

    private BleReceiver mBleReceiver;

    private final Map<String, BleScanResult> mNodeScanMap = new HashMap<>();;
    private final Map<String, BleScanResult> mProvisionScanMap = new HashMap<>();
    private BleCallback mBleCallback;

    private long mLastScanResultCheckTime;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new MainServiceBinder();
    }

    public class MainServiceBinder extends Binder {
        public MainService getService() {
            return MainService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLog.d("onCreate()");
        mBleCallback = new BleCallback();

        mBTEnable = BluetoothAdapter.getDefaultAdapter().isEnabled();
        mBleReceiver = new BleReceiver();
        IntentFilter bleFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBleReceiver, bleFilter);

        mLastScanResultCheckTime = SystemClock.elapsedRealtime();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mLog.d("onDestroy()");
        unregisterReceiver(mBleReceiver);
        stopScanBle();
    }

    public boolean hasScanResult(String address) {
        return mNodeScanMap.get(address) != null;
    }

    public ScanResult getNodeScanResult(String address) {
        BleScanResult bsr = mNodeScanMap.get(address);
        return bsr == null ? null : bsr.getScanResult();
    }

    public List<ScanResult> getNodeScanList() {
        synchronized (mNodeScanMap) {
            Collection<BleScanResult> scans = mNodeScanMap.values();
            List<ScanResult> list = new ArrayList<>(scans.size());
            for (BleScanResult bleSR : scans) {
                list.add(bleSR.getScanResult());
            }
            return list;
        }
    }

    public List<ScanResult> getProvisionList() {
        synchronized (mProvisionScanMap) {
            Collection<BleScanResult> scans = mProvisionScanMap.values();
            List<ScanResult> list = new ArrayList<>(scans.size());
            for (BleScanResult bleSR : scans) {
                list.add(bleSR.getScanResult());
            }
            return list;
        }
    }

    public boolean isBTEnable() {
        return mBTEnable;
    }

    public boolean isScanning() {
        return mScanning;
    }

    public void removeProvision(String address) {
        mProvisionScanMap.remove(address);
    }

    public boolean startScanBle() {
        mLog.d("startScanBle() - Breadcrumb 1: Method entered."); //

        if (!mBTEnable) {
            mLog.w("startScanBle() - Breadcrumb 2: Bluetooth is disabled. Exiting."); //
            return false;
        }

        // Android 12+ (API 31+) Security Check
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                mLog.e("Breadcrumb 3: SCAN PERMISSION DENIED by System."); //
                return false;
            }
        }

        mLog.d("startScanBle() - Breadcrumb 4: Bluetooth scan permission confirmed."); //

        // Original Location check
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            mLog.e("Breadcrumb 5: LOCATION PERMISSION DENIED."); //
            return false;
        }

        if (mScanning) {
            mLog.d("startScanBle() - Breadcrumb 6: Scan is already running."); //
            return true;
        }

        mLog.d("startScanBle() - Breadcrumb 7: All permissions pass. Fetching scanner..."); //

        BluetoothLeScanner scanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();
        if (scanner != null) {
            mScanning = true;
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Changed to high-performance for testing
                    .build();

            mLog.d("startScanBle() - Breadcrumb 8: Calling startScan() now!"); //
            scanner.startScan(null, settings, mBleCallback);
            mLog.d("startScanBle() - Breadcrumb 9: success returned from system."); //
            return true;
        } else {
            mLog.w("startScanBle() - Breadcrumb 10: FATAL - Bluetooth scanner is NULL."); //
            return false;
        }
    }
    public void stopScanBle() {
        mLog.d("stopScanBle()");
        BluetoothLeScanner scanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();
        if (scanner != null) {
            scanner.stopScan(mBleCallback);
        }

        mScanning = false;
    }

    private class BleCallback extends ScanCallback {
        public void onScanResult(int callbackType, ScanResult result) {
            checkBleMesh(result);
        }

        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                checkBleMesh(result);
            }
        }

        public void onScanFailed(int errorCode) {
        }

        private void checkBleMesh(ScanResult result) {
            ScanRecord record = result.getScanRecord();
            if (record == null) {
                return;
            }

            byte[] data = record.getBytes();
            if (MeshUtils.isNetworkID(data)) {
                BleScanResult bleSR = new BleScanResult(result, SystemClock.elapsedRealtime());
                synchronized (mNodeScanMap) {
                    mNodeScanMap.put(result.getDevice().getAddress(), bleSR);
                }
                synchronized (mProvisionScanMap) {
                    mProvisionScanMap.remove(result.getDevice().getAddress());
                }
            } else if (MeshUtils.isMeshNodeIdentity(data)) {
                BleScanResult bleSR = new BleScanResult(result, SystemClock.elapsedRealtime());
                synchronized (mNodeScanMap) {
                    mNodeScanMap.put(result.getDevice().getAddress(), bleSR);
                }
                synchronized (mProvisionScanMap) {
                    mProvisionScanMap.remove(result.getDevice().getAddress());
                }
            } else if (MeshUtils.isMeshProvisioning(data)) {
                BleScanResult bleSR = new BleScanResult(result, SystemClock.elapsedRealtime());
                synchronized (mProvisionScanMap) {
                    mProvisionScanMap.put(result.getDevice().getAddress(), bleSR);
                }
                synchronized (mNodeScanMap) {
                    mNodeScanMap.remove(result.getDevice().getAddress());
                }
            }

            long currentTime = SystemClock.elapsedRealtime();
            if (currentTime - mLastScanResultCheckTime > SCAN_RESULT_CHECK_INTERVAL) {
                // Delete timeout node
                List<String> delNodeKeys = new ArrayList<>();
                synchronized (mNodeScanMap) {
                    for (Map.Entry<String, BleScanResult> entry : mNodeScanMap.entrySet()) {
                        if (currentTime - entry.getValue().getScanTime() > SCAN_RESULT_KEEP_TIME) {
                            delNodeKeys.add(entry.getKey());
                        }
                    }
                    for (String key : delNodeKeys) {
                        mNodeScanMap.remove(key);
                    }
                }

                // Delete timeout provision
                List<String> delProvisionKeys = new ArrayList<>();
                synchronized (mProvisionScanMap) {
                    for (Map.Entry<String, BleScanResult> entry : mProvisionScanMap.entrySet()) {
                        if (currentTime - entry.getValue().getScanTime() > SCAN_RESULT_KEEP_TIME) {
                            delProvisionKeys.add(entry.getKey());
                        }
                    }
                    for (String key : delProvisionKeys) {
                        mProvisionScanMap.remove(key);
                    }
                }

                mLastScanResultCheckTime = currentTime;
            }
        }
    }

    private class BleReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                return;
            }

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                mLog.d("Bluetooth state = " + state);
                switch (state) {
                    case BluetoothAdapter.STATE_ON: {
                        mBTEnable = true;
                        break;
                    }
                    default: {
                        stopScanBle();
                        mBTEnable = false;
                        mScanning = false;
                        mNodeScanMap.clear();
                        break;
                    }
                }
            }
        }
    }
}
