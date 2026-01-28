// The package name defines the project's namespace and directory structure.
package com.espressif.espblemesh.ui.provisioning;

// Import statements bring in necessary classes from the Android SDK and other libraries.
import android.Manifest; // Used to request permissions like location and Bluetooth.
import android.bluetooth.le.ScanRecord; // Represents the advertisement record of a BLE device.
import android.bluetooth.le.ScanResult; // Represents a single BLE device found during a scan.
import android.content.ComponentName; // Identifier for a specific application component (like a Service).
import android.content.Intent; // Used to start new activities and pass data.
import android.graphics.Color; // Used for setting UI colors programmatically.
import android.os.Bundle; // Used to pass data between Android components and save state.
import android.os.IBinder; // Base interface for communication with a bound Service.
import androidx.annotation.NonNull; // Annotation indicating a parameter or return value can never be null.
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout; // A layout that supports a "swipe to refresh" gesture.
import androidx.appcompat.app.AlertDialog; // A standard dialog box.
import androidx.recyclerview.widget.RecyclerView; // A flexible view for displaying a list of items.
import androidx.appcompat.widget.Toolbar; // A standard app toolbar.
import android.view.Menu; // Interface for managing items in a menu.
import android.view.MenuItem; // Represents a single item in a Menu.
import android.view.View; // Basic building block for UI components.
import android.view.ViewGroup; // A view that can contain other views.
import android.widget.CheckBox; // A UI element that can be checked or unchecked.
import android.widget.CompoundButton; // Base class for checkable buttons like CheckBox.
import android.widget.EditText; // A UI element for text input.
import android.widget.TextView; // A UI element for displaying text.
import android.widget.Toast; // A small popup message for the user.

// Imports from the Espressif BLE Mesh SDK and this application's project files.
import com.espressif.blemesh.model.Node; // Data model for a node in the mesh network.
import com.espressif.blemesh.utils.MeshUtils; // Utility functions for the mesh SDK.
import com.espressif.espblemesh.R; // Auto-generated class holding resource IDs.
import com.espressif.espblemesh.constants.Constants; // App-specific constants.
import com.espressif.blemesh.user.MeshUser; // Singleton class to manage user and network data.
import com.espressif.blemesh.task.NodeDeleteTask; // A task for deleting a mesh node.
import com.espressif.espblemesh.ui.ServiceActivity; // Custom base Activity for managing service connections.
import com.espressif.espblemesh.utils.Utils; // Application-specific utility functions.

// Imports for handling lists and collections.
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

// Imports for asynchronous programming using RxJava.
import io.reactivex.Observable; // Emits a sequence of items.
import io.reactivex.Observer; // Receives items from an Observable.
import io.reactivex.android.schedulers.AndroidSchedulers; // RxJava scheduler that runs tasks on the Android main UI thread.
import io.reactivex.disposables.Disposable; // Represents a disposable resource, used to cancel a subscription.
import io.reactivex.schedulers.Schedulers; // RxJava schedulers for running tasks on different threads.

// Imports from Espressif's utility libraries.
import libs.espressif.app.PermissionHelper; // A helper class to simplify requesting Android permissions.
import libs.espressif.log.EspLog; // A logging utility.
import libs.espressif.utils.DataUtil; // Utilities for data conversion (e.g., bytes to hex string).
import libs.espressif.utils.TextUtils; // Utilities for string manipulation.

/**
 * ProvisionScanActivity is responsible for scanning for unprovisioned BLE Mesh devices.
 * It displays a list of nearby devices that are broadcasting the "Mesh Provisioning Service" UUID.
 * Users can select a device from this list to start the provisioning process.
 */
public class ProvisionScanActivity extends ServiceActivity {
    // --- Constants for Activity Results ---
    private static final int REQUEST_PERMISSION = 1;
    private static final int REQUEST_CONFIGURE = 2; // Request code for the provisioning configuration screen.

    // --- Constant for Menu Items ---
    private static final int MENU_FILTER = 0x10; // ID for the filter option in the toolbar menu.

    // Logger for this class, used for debugging.
    private final EspLog mLog = new EspLog(getClass());

    // --- Member Variables for UI and Data ---
    private List<ScanResult> mBleList; // The data source for the list, holding discovered BLE devices.
    private BleAdapter mBleAdapter; // The adapter that binds the mBleList data to the RecyclerView.

    private SwipeRefreshLayout mRefreshLayout; // Handles the "pull-to-refresh" gesture.

    private UpdateThread mUpdateThread; // A background thread to periodically update the list of devices.

    // Variables to store the current filter settings.
    private int mFilterRssiMin = Integer.MIN_VALUE; // Minimum RSSI (signal strength) to show.
    private int mFilterRssiMax = Integer.MAX_VALUE; // Maximum RSSI to show.
    private String mFilterName; // Name filter for the device.
    private String mFilterUUID; // UUID filter for the device.

    /**
     * Called when the activity is first created. This is where initial setup happens.
     * @param savedInstanceState If the activity is being re-created, this bundle contains its previously saved state.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Set the user interface layout for this activity from res/layout/provision_scan_activity.xml.
        setContentView(R.layout.provision_scan_activity);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar); // Sets this toolbar as the app bar.
        setHomeAsUpEnable(true); // Show the "Up" button (back arrow) in the toolbar.

        // --- RecyclerView and Adapter Setup ---
        mBleList = new LinkedList<>(); // Use a LinkedList for the list of scan results.
        mBleAdapter = new BleAdapter(); // Initialize the custom adapter.
        RecyclerView bleRecyclerView = findViewById(R.id.recycler_view);
        bleRecyclerView.setAdapter(mBleAdapter); // Attach the adapter to the RecyclerView.

        // --- Swipe-to-Refresh Setup ---
        mRefreshLayout = findViewById(R.id.refresh_layout);
        mRefreshLayout.setColorSchemeResources(R.color.colorAccent); // Set the color of the refresh indicator.
        mRefreshLayout.setOnRefreshListener(this::refresh); // Set the method to call when the user swipes down.
        mRefreshLayout.setEnabled(false); // Disable it initially; it will be enabled when the BLE service is connected.

        // --- Runtime Permissions Request ---
        PermissionHelper mPermissionHelper = new PermissionHelper(this, REQUEST_PERMISSION);
        mPermissionHelper.requestAuthorities(new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION, // Required for BLE scanning.
                Manifest.permission.BLUETOOTH_SCAN,         // Required on Android 12+ for scanning.
                Manifest.permission.BLUETOOTH_CONNECT       // Required on Android 12+ for connecting.
        });
    }

    /**
     * Called when the connection to the background BLE service has been established.
     * @param name The component name of the connected service.
     * @param service The IBinder interface for communicating with the service.
     */
    @Override
    protected void onServiceConnected(ComponentName name, IBinder service) {
        super.onServiceConnected(name, service);

        // The service is now available. Start the background thread to update the device list.
        mUpdateThread = new UpdateThread();
        mUpdateThread.start();

        // Enable the pull-to-refresh gesture.
        mRefreshLayout.setEnabled(true);
    }

    /**
     * Called when the connection to the service is lost unexpectedly.
     * @param name The component name of the disconnected service.
     */
    @Override
    protected void onServiceDisconnected(ComponentName name) {
        super.onServiceDisconnected(name);

        // If the service disconnects, stop the update thread to prevent errors.
        mUpdateThread.interrupt();
    }

    /**
     * Called when an activity you launched returns a result.
     * @param requestCode The integer code you originally supplied to startActivityForResult().
     * @param resultCode The integer result code returned by the child activity (e.g., RESULT_OK).
     * @param data An Intent which can return result data to the caller.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONFIGURE:
                // This block executes when returning from the ProvisioningActivity screen.
                if (resultCode == RESULT_OK) {
                    // If provisioning was successful...
                    String nodeMac = data.getStringExtra(Constants.KEY_NODE_MAC);
                    // Tell the service to remove this device from the list of *unprovisioned* devices.
                    getService().removeProvision(nodeMac);
                    // Pass the successful result back to MainActivity.
                    setResult(RESULT_OK, data);
                    finish(); // Close this activity.
                }
                return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Initialize the contents of the Activity's standard options menu.
     * @param menu The options menu in which you place your items.
     * @return You must return true for the menu to be displayed.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Add a "Filter" item to the toolbar.
        menu.add(Menu.NONE, MENU_FILTER, 0, R.string.provision_scan_menu_filter)
                .setIcon(R.drawable.ic_filter_list_24dp) // Set the icon for the menu item.
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS); // Always show this item as an icon in the toolbar.
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * This hook is called whenever an item in your options menu is selected.
     * @param item The menu item that was selected.
     * @return boolean Return false to allow normal menu processing to proceed, true to consume it here.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_FILTER:
                // If the user taps the filter icon, show the filter settings dialog.
                showFilterDialog();
                return true; // The event is handled.
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Applies the current filter settings to the list of scanned devices and updates the UI.
     * @param scanResults The raw list of scan results from the BLE service.
     */
    private void updateProvision(List<ScanResult> scanResults) {
        mBleList.clear(); // Clear the existing list.
        for (ScanResult scanResult : scanResults) {
            // --- Apply RSSI (Signal Strength) Filter ---
            if (mFilterRssiMin != Integer.MIN_VALUE) {
                if (scanResult.getRssi() < mFilterRssiMin) {
                    continue; // Skip this device if its signal is too weak.
                }
            }

            if (mFilterRssiMax != Integer.MAX_VALUE) {
                if (scanResult.getRssi() > mFilterRssiMax) {
                    continue; // Skip this device if its signal is too strong (less common).
                }
            }

            // --- Apply Device Name Filter ---
            if (mFilterName != null) {
                String filterNameLC = mFilterName.toLowerCase();
                String deviceName = Utils.getBLEDeviceName(scanResult);
                if (deviceName == null || !deviceName.toLowerCase().contains(filterNameLC)) {
                    continue; // Skip if the device name doesn't contain the filter text.
                }
            }

            // --- Apply Device UUID Filter ---
            if (mFilterUUID != null) {
                String filterUUIDLc = mFilterUUID.toLowerCase();
                ScanRecord record = scanResult.getScanRecord();
                assert record != null;
                byte[] devUUID = MeshUtils.getProvisioningUUID(record.getBytes());
                assert devUUID != null;
                String devUUIDStr = DataUtil.bigEndianBytesToHexString(devUUID).toLowerCase();
                if (!devUUIDStr.contains(filterUUIDLc)) {
                    continue; // Skip if the device's UUID doesn't contain the filter text.
                }
            }

            mBleList.add(scanResult); // If the device passes all filters, add it to the list.
        }
        // Notify the adapter that the data set has changed, causing the RecyclerView to refresh.
        mBleAdapter.notifyDataSetChanged();
    }

    /**
     * Creates and displays the dialog for setting scan filters (RSSI, Name, UUID).
     */
    private void showFilterDialog() {
        // Build the dialog using a custom layout.
        AlertDialog filterDailog = new AlertDialog.Builder(this)
                .setTitle(R.string.provision_scan_menu_filter)
                .setView(R.layout.provision_scan_filter_dialog) // Inflate the custom layout for the dialog's content.
                .setNegativeButton(android.R.string.cancel, null) // "Cancel" button does nothing.
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // This code runs when the user clicks "OK".
                    AlertDialog alertDialog = (AlertDialog) dialog;
                    // Get references to all the input fields in the dialog.
                    CheckBox rssiMinCB = alertDialog.findViewById(R.id.filter_rssi_min_cb);
                    View rssiMinNeg = alertDialog.findViewById(R.id.filter_rssi_min_neg);
                    EditText rssiMinET = alertDialog.findViewById(R.id.filter_rssi_min_edit);
                    assert rssiMinCB != null && rssiMinNeg != null && rssiMinET != null;

                    CheckBox rssiMaxCB = alertDialog.findViewById(R.id.filter_rssi_max_cb);
                    View rssiMaxNeg = alertDialog.findViewById(R.id.filter_rssi_max_neg);
                    EditText rssiMaxET = alertDialog.findViewById(R.id.filter_rssi_max_edit);
                    assert rssiMaxCB != null && rssiMaxNeg != null && rssiMaxET != null;

                    CheckBox nameCB = alertDialog.findViewById(R.id.filter_name_cb);
                    EditText nameET = alertDialog.findViewById(R.id.filter_name_edit);
                    assert nameCB != null && nameET != null;

                    CheckBox uuidCB = alertDialog.findViewById(R.id.filter_uuid_cb);
                    EditText uuidET = alertDialog.findViewById(R.id.filter_uuid_edit);
                    assert uuidCB != null && uuidET != null;

                    // --- Read values from the dialog and update the filter member variables ---
                    if (rssiMinCB.isChecked()) {
                        String rssiMinText = rssiMinET.getText().toString();
                        mFilterRssiMin = TextUtils.isEmpty(rssiMinText) ? Integer.MIN_VALUE : -(Math.abs(Integer.parseInt(rssiMinText)));
                    } else {
                        mFilterRssiMin = Integer.MIN_VALUE; // Reset filter if unchecked.
                    }

                    if (rssiMaxCB.isChecked()) {
                        String rssiMaxText = rssiMaxET.getText().toString();
                        mFilterRssiMax = TextUtils.isEmpty(rssiMaxText) ? Integer.MAX_VALUE : -(Math.abs(Integer.parseInt(rssiMaxText)));
                    } else {
                        mFilterRssiMax = Integer.MAX_VALUE;
                    }

                    if (nameCB.isChecked()) {
                        String name = nameET.getText().toString();
                        mFilterName = TextUtils.isEmpty(name) ? null : name;
                    } else {
                        mFilterName = null;
                    }

                    if (uuidCB.isChecked()) {
                        String uuid = uuidET.getText().toString();
                        mFilterUUID = TextUtils.isEmpty(uuid) ? null : uuid;
                    } else {
                        mFilterUUID = null;
                    }
                })
                .show(); // Display the configured dialog.

        // --- Configure the initial state and listeners for the dialog's views ---
        CheckBox rssiMinCB = filterDailog.findViewById(R.id.filter_rssi_min_cb);
        View rssiMinNeg = filterDailog.findViewById(R.id.filter_rssi_min_neg);
        EditText rssiMinET = filterDailog.findViewById(R.id.filter_rssi_min_edit);
        assert rssiMinCB != null && rssiMinNeg != null && rssiMinET != null;

        CheckBox rssiMaxCB = filterDailog.findViewById(R.id.filter_rssi_max_cb);
        View rssiMaxNeg = filterDailog.findViewById(R.id.filter_rssi_max_neg);
        EditText rssiMaxET = filterDailog.findViewById(R.id.filter_rssi_max_edit);
        assert rssiMaxCB != null && rssiMaxNeg != null && rssiMaxET != null;

        CheckBox nameCB = filterDailog.findViewById(R.id.filter_name_cb);
        EditText nameET = filterDailog.findViewById(R.id.filter_name_edit);
        assert nameCB != null && nameET != null;

        CheckBox uuidCB = filterDailog.findViewById(R.id.filter_uuid_cb);
        EditText uuidET = filterDailog.findViewById(R.id.filter_uuid_edit);
        assert uuidCB != null && uuidET != null;

        // A single listener to handle showing/hiding input fields when a checkbox is toggled.
        CompoundButton.OnCheckedChangeListener checkListener = (buttonView, isChecked) -> {
            View[] stateViews = new View[0];
            if (buttonView == rssiMinCB) {
                stateViews = new View[]{rssiMinNeg, rssiMinET};
            } else if (buttonView == rssiMaxCB) {
                stateViews = new View[]{rssiMaxNeg, rssiMaxET};
            } else if (buttonView == nameCB) {
                stateViews = new View[]{nameET};
            } else if (buttonView == uuidCB) {
                stateViews = new View[]{uuidET};
            }

            for (View view : stateViews) {
                view.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        };
        rssiMinCB.setOnCheckedChangeListener(checkListener);
        rssiMaxCB.setOnCheckedChangeListener(checkListener);
        nameCB.setOnCheckedChangeListener(checkListener);
        uuidCB.setOnCheckedChangeListener(checkListener);
        // Initially hide all the filter input fields.
        rssiMinNeg.setVisibility(View.GONE);
        rssiMinET.setVisibility(View.GONE);
        rssiMaxNeg.setVisibility(View.GONE);
        rssiMaxET.setVisibility(View.GONE);
        nameET.setVisibility(View.GONE);
        uuidET.setVisibility(View.GONE);

        // --- Set the initial state of the dialog based on current filter values ---
        if (mFilterRssiMin != Integer.MIN_VALUE) {
            rssiMinCB.setChecked(true);
            rssiMinET.setText(String.valueOf(Math.abs(mFilterRssiMin)));
        }
        if (mFilterRssiMax != Integer.MAX_VALUE) {
            rssiMaxCB.setChecked(true);
            rssiMaxET.setText(String.valueOf(Math.abs(mFilterRssiMax)));
        }
        if (mFilterName != null) {
            nameCB.setChecked(true);
            nameET.setText(mFilterName);
        }
        if (mFilterUUID != null) {
            uuidCB.setChecked(true);
            uuidET.setText(mFilterUUID);
        }
    }

    /**
     * This method is called when the user performs the "swipe-to-refresh" gesture.
     * It initiates a new BLE scan.
     */
    private void refresh() {
        if (getService() != null) {
            // Tell the background service to start scanning for BLE devices.
            getService().startScanBle();
        }
        // Use RxJava to create a small delay before hiding the refresh indicator.
        // This provides a better user experience.
        Observable.just(300L)
                .subscribeOn(Schedulers.io()) // Use a background thread.
                .doOnNext(Thread::sleep) // Wait for 300 milliseconds.
                .observeOn(AndroidSchedulers.mainThread()) // Switch back to the UI thread.
                .subscribe(new Observer<Long>() {
                    @Override
                    public void onSubscribe(Disposable d) {
                    }

                    @Override
                    public void onNext(Long aLong) {
                    }

                    @Override
                    public void onError(Throwable e) {
                    }

                    @Override
                    public void onComplete() {
                        // After the delay, check if Bluetooth is still enabled.
                        if (!getService().isBTEnable()) {
                            Toast.makeText(ProvisionScanActivity.this,
                                            R.string.main_bt_disable_toast, Toast.LENGTH_SHORT)
                                    .show();
                        }
                        // Hide the refresh animation.
                        mRefreshLayout.setRefreshing(false);
                    }
                });
    }

    /**
     * A background thread that periodically fetches the latest list of discovered
     * unprovisioned devices from the service and updates the UI.
     */
    private class UpdateThread extends Thread {
        @Override
        public void run() {
            // This loop runs until the thread is interrupted (e.g., when the activity is destroyed).
            while (!isInterrupted()) {
                try {
                    // Wait for 1 second before the next update.
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    mLog.w("Scan UpdateThread is interrupted");
                    Thread.currentThread().interrupt(); // Preserve the interrupted status.
                    break; // Exit the loop.
                }

                // If the activity is paused (in the background), don't update the UI.
                if (isPaused()) {
                    continue;
                }

                // Get the list of devices that are ready to be provisioned from the service.
                List<ScanResult> scanResults = getService().getProvisionList();
                // Sort the list alphabetically by device name, then by MAC address for consistency.
                Collections.sort(scanResults, (o1, o2) -> {
                    String name1 = Utils.getBLEDeviceName(o1);
                    name1 = (name1 == null) ? "" : name1;
                    String name2 = Utils.getBLEDeviceName(o2);
                    name2 = (name2 == null) ? "" : name2;

                    int result = name1.compareTo(name2);

                    if (result == 0) {
                        String bssid1 = o1.getDevice().getAddress();
                        String bssid2 = o2.getDevice().getAddress();
                        result = bssid1.compareTo(bssid2);
                    }

                    return result;
                });

                // This logic appears to be for cleaning up stale node entries.
                // If a device is found in the unprovisioned list but already exists as a provisioned node
                // in the database (perhaps from a failed previous attempt), delete the stale entry.
                MeshUser user = MeshUser.Instance;
                for (ScanResult scanResult : scanResults) {
                    String address = scanResult.getDevice().getAddress();
                    Node node = user.getNodeForMac(address);
                    if (node != null) {
                        new NodeDeleteTask(address).run();
                    }
                }

                // Post a task to the UI thread to update the RecyclerView with the filtered list.
                runOnUiThread(() -> updateProvision(scanResults));
            }
        }
    }

    /**
     * ViewHolder for the RecyclerView. It holds references to the views for a single list item.
     * This improves performance by avoiding repeated `findViewById` calls.
     */
    private class BleHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        ScanResult scanResult; // The ScanResult data for this specific list item.

        // UI elements within the list item layout.
        TextView text1; // Top line of text (device name).
        TextView text2; // Bottom line of text (MAC address and RSSI).

        BleHolder(View itemView) {
            super(itemView);

            // Find the views within the inflated layout.
            text1 = itemView.findViewById(android.R.id.text1);
            text1.setTextColor(Color.BLACK);
            text2 = itemView.findViewById(android.R.id.text2);
            text2.setTextColor(Color.GRAY);

            // Set a click listener on the entire item view.
            itemView.setOnClickListener(this);
        }

        /**
         * Called when the user taps on this list item.
         */
        @Override
        public void onClick(View v) {
            // Stop scanning to conserve battery and improve connection stability.
            getService().stopScanBle();

            // Start the ProvisioningActivity to configure and provision this specific device.
            Intent intent = new Intent(ProvisionScanActivity.this, ProvisioningActivity.class);
            // Pass the selected ScanResult to the next activity.
            intent.putExtra(Constants.KEY_SCAN_RESULT, scanResult);
            startActivityForResult(intent, REQUEST_CONFIGURE);
        }
    }

    /**
     * The Adapter for the RecyclerView. This class is responsible for creating list items (ViewHolders)
     * and binding data (ScanResult objects) to them.
     */
    private class BleAdapter extends RecyclerView.Adapter<BleHolder> {

        /**
         * Called when RecyclerView needs a new ViewHolder to represent an item.
         */
        @NonNull
        @Override
        public BleHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Inflate a standard Android simple list item layout for the item view.
            View itemView = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            return new BleHolder(itemView);
        }

        /**
         * Called by RecyclerView to display the data at the specified position.
         */
        @Override
        public void onBindViewHolder(@NonNull BleHolder holder, int position) {
            // Get the ScanResult object for this position.
            ScanResult ble = mBleList.get(position);
            holder.scanResult = ble; // Associate the data with the ViewHolder.

            // --- Set the text for the UI elements ---
            // Try to get the device name from various sources in the advertisement data.
            String name = ble.getDevice().getName();
            if (name == null) {
                if (ble.getScanRecord() != null) {
                    name = ble.getScanRecord().getDeviceName();
                }
            }
            if (name == null) {
                name = "Unknow"; // Use a default name if none is found.
            }
            holder.text1.setText(name); // Set the device name.
            // Set the second line to show the MAC address and signal strength (RSSI).
            holder.text2.setText(String.format(Locale.ENGLISH, "%s %d", ble.getDevice().getAddress(), ble.getRssi()));
        }

        /**
         * Returns the total number of items in the data set held by the adapter.
         */
        @Override
        public int getItemCount() {
            return mBleList.size();
        }
    }
}
