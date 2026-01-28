// The package name defines the project's namespace and is crucial for organizing code.
package com.espressif.espblemesh.ui;

// Import statements bring in necessary classes from the Android SDK, third-party libraries,
// and other parts of this project.
import android.Manifest; // Required to request permissions like Location and Bluetooth at runtime.
import android.bluetooth.le.ScanResult; // Represents a single BLE device found during a scan.
import android.content.ComponentName; // An identifier for a specific application component (like a Service).
import android.content.Intent; // An object used to request an action from another app component (e.g., starting an Activity).
import android.os.Bundle; // Used to pass data between activities and to save an activity's state.
import android.os.IBinder; // The core part of a remote object mechanism for communicating with a bound Service.
import android.view.Menu; // Interface for managing the items in an options menu or context menu.
import android.view.MenuItem; // Represents a single item within a Menu.
import android.view.View; // The basic building block for all UI components.
import android.view.ViewGroup; // A special view that can contain other views (known as children).
import android.widget.Button; // A standard button UI element.
import android.widget.ImageView; // A view used to display images.
import android.widget.PopupMenu; // A modal menu that is anchored to a specific view.
import android.widget.TextView; // A view used to display text.
import android.widget.Toast; // A small, temporary popup message for the user.

import androidx.annotation.NonNull; // Annotation indicating a parameter or return value can never be null.
import androidx.appcompat.app.ActionBarDrawerToggle; // Used to create the "hamburger" icon for the navigation drawer.
import androidx.appcompat.app.AlertDialog; // A standard dialog box for alerts and confirmations.
import androidx.appcompat.widget.Toolbar; // A standard app toolbar, typically at the top of the screen.
import androidx.core.view.GravityCompat; // Helper for standard gravity values, used with the navigation drawer.
import androidx.drawerlayout.widget.DrawerLayout; // A layout that allows for a slide-out navigation panel from the edge of the screen.
import androidx.recyclerview.widget.RecyclerView; // A flexible and efficient view for displaying large sets of data in a list.
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout; // A layout that supports a "swipe to refresh" gesture.

// Imports from the Espressif BLE Mesh SDK.
import com.espressif.blemesh.model.Network; // Data model representing a mesh network.
import com.espressif.blemesh.model.Node; // Data model representing a single device (node) in a mesh network.
import com.espressif.blemesh.task.NetworkDeleteTask; // A pre-built task for deleting a mesh network.
import com.espressif.blemesh.user.MeshUser; // A singleton class that manages all user-related mesh data (networks, nodes, etc.).
import com.espressif.blemesh.utils.MeshUtils; // Utility functions provided by the mesh SDK.
import com.espressif.espblemesh.R; // Auto-generated class that holds all resource IDs (layouts, strings, colors, etc.).
import com.espressif.espblemesh.constants.Constants; // App-specific constants, like keys for passing data in Intents.
import com.espressif.espblemesh.ui.network.NetworkActivity; // The Activity for viewing/managing a specific network.
import com.espressif.espblemesh.ui.provisioning.ProvisionScanActivity; // The Activity for scanning and provisioning new devices.
import com.espressif.espblemesh.ui.settings.SettingsActivity; // The Activity for app settings.
import com.google.android.material.navigation.NavigationView; // The view that represents the slide-out navigation menu.

// Imports for handling lists and collections.
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Imports for asynchronous programming using the RxJava library.
import io.reactivex.Observable; // The core class for emitting a sequence of items.
import io.reactivex.Observer; // The class that subscribes to an Observable to receive items.
import io.reactivex.android.schedulers.AndroidSchedulers; // A scheduler that executes tasks on the Android main UI thread.
import io.reactivex.disposables.Disposable; // Represents a connection that can be cancelled.
import io.reactivex.schedulers.Schedulers; // A collection of standard schedulers for running tasks on different threads.

// Imports from Espressif's utility libraries.
import libs.espressif.app.PermissionHelper; // A helper class to simplify requesting Android runtime permissions.
import libs.espressif.log.EspLog; // A custom logging utility for debugging.
import libs.espressif.utils.DataUtil; // Utilities for data conversions, like bytes to a hexadecimal string.

/**
 * MainActivity is the primary screen of the application.
 * It displays a list of the user's saved BLE Mesh networks and serves as the main navigation hub.
 * It extends ServiceActivity, which is a custom base class that manages the connection to a background service
 * (likely the EspBleMeshService for handling all BLE communications).
 */
public class MainActivity extends ServiceActivity {
    // --- Constants for Activity Results ---
    // These "request codes" are unique integers used to identify which sub-activity is returning a result
    // in the onActivityResult() method.
    private static final int REQUEST_PERMISSION = 0x11;
    private static final int REQUEST_PROVISION_SCAN = 0x12;
    private static final int REQUEST_NETWORK = 0x13;
    private static final int REQUEST_SETTINGS = 0x14;
    private static final int REQUEST_ADD_NETWORK = 0x15;

    // A logger instance for writing debug messages to the console (Logcat).
    private EspLog mLog = new EspLog(getClass());

    // --- Member Variables for Core Logic and UI ---

    // A singleton instance holding all mesh data for the current user.
    private MeshUser mUser;

    // UI components for displaying the list of networks.
    private SwipeRefreshLayout mRefreshLayout; // Handles the "pull-to-refresh" gesture to re-scan for devices.
    private RecyclerView mNetworkView; // The list view that efficiently displays networks.
    private List<Network> mNetworkList; // The data source (a list of Network objects) for the RecyclerView.
    private NetworkAdapter mNetworkAdapter; // The adapter that binds the Network data to the list items.

    // A dedicated thread for a special "fast provisioning" scan process.
    private ScanFastProvThread mScanFastProvThread;

    // UI components for navigation.
    private DrawerLayout mDrawer; // The root layout that contains the navigation drawer (side menu).
    private Button mFab; // The Floating Action Button, used here for starting the provisioning process.

    /**
     * Called when the activity is first created. This is where initial setup happens:
     * - Inflating the layout (creating views from XML).
     * - Getting references to UI elements.
     * - Setting up listeners for user interactions.
     * - Requesting necessary permissions.
     * @param savedInstanceState If the activity is being re-created, this bundle contains its previously saved state.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Set the user interface layout for this activity from the res/layout/main_activity.xml file.
        setContentView(R.layout.main_activity);

        // --- Toolbar and Navigation Drawer Setup ---
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar); // Sets this toolbar as the app bar for the activity.
        mDrawer = findViewById(R.id.drawer_layout);
        // Create the "hamburger" menu icon and link it to the DrawerLayout.
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, mDrawer, toolbar, 0, 0);
        mDrawer.addDrawerListener(toggle);
        toggle.syncState(); // Synchronize the icon's state with the drawer's open/closed state.
        setTitle(R.string.main_title); // Set the title in the toolbar.

        // Get the singleton instance of MeshUser. This class holds all persistent network data.
        mUser = MeshUser.Instance;

        // --- Navigation View (Side Menu) Setup ---
        NavigationView navigationView = findViewById(R.id.nav_view);
        // Set a listener to handle clicks on items in the navigation menu.
        navigationView.setNavigationItemSelectedListener(menuItem -> {
            int id = menuItem.getItemId(); // Get the unique ID of the clicked item.

            if (id == R.id.nav_add_network) {
                // User clicked "Add Network". Start the NetworkAddActivity.
                Intent intent = new Intent(MainActivity.this, NetworkAddActivity.class);
                startActivityForResult(intent, REQUEST_ADD_NETWORK);
                return true; // Indicates that the click has been handled.
            } else if (id == R.id.nav_provision) {
                // User clicked "Provision". Start the provisioning flow.
                gotoProvision();
                return true;
            } else if (id == R.id.nav_settings) {
                // User clicked "Settings". Start the SettingsActivity.
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivityForResult(intent, REQUEST_SETTINGS);
                return true;
            }

            return false; // The click was not handled here.
        });

        // --- RecyclerView and Adapter Setup ---
        mNetworkView = findViewById(R.id.recycler_view);
        mNetworkList = new ArrayList<>(); // Initialize the list to hold network data.
        mNetworkAdapter = new NetworkAdapter(); // Initialize the adapter that will manage the list items.

        // --- Swipe-to-Refresh Setup ---
        mRefreshLayout = findViewById(R.id.refresh_layout);
        mRefreshLayout.setColorSchemeResources(R.color.colorAccent); // Set the color of the spinning progress indicator.
        mRefreshLayout.setOnRefreshListener(this::refresh); // Set the method to call when the user swipes down.
        mRefreshLayout.setEnabled(false); // Disable it initially; it will be enabled when the BLE service is connected.

        // --- Floating Action Button (FAB) Setup ---
        mFab = findViewById(R.id.fab);
        mFab.setOnClickListener(v -> gotoProvision()); // Set a listener to start provisioning when clicked.

        // --- Runtime Permissions Request ---
        // Modern Android requires asking the user for sensitive permissions at runtime.
        PermissionHelper mPermissionHelper = new PermissionHelper(this, REQUEST_PERMISSION);
        mPermissionHelper.requestAuthorities(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, // Required for BLE scanning on most Android versions.
                Manifest.permission.BLUETOOTH_SCAN,       // Required for BLE scanning on Android 12+.
                Manifest.permission.BLUETOOTH_CONNECT     // Required for connecting to BLE devices on Android 12+.
        });
    }

    /**
     * Called when the activity is about to become visible to the user.
     */
    @Override
    protected void onResume() {
        super.onResume();
    }

    /**
     * Called when the activity is being destroyed.
     * This is the final cleanup point.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Stop the fast provisioning scan thread if it's running to prevent memory leaks.
        stopScanFastProvThread();
    }

    // --- Service Connection Callbacks (from base ServiceActivity) ---

    /**
     * Called when the connection to the background BLE service has been established.
     * It is now safe to interact with the service.
     * @param name The component name of the connected service.
     * @param service The IBinder interface for communicating with the service.
     */
    @Override
    protected void onServiceConnected(ComponentName name, IBinder service) {
        super.onServiceConnected(name, service);

        // Now that the service is ready, enable UI elements that depend on it.
        mRefreshLayout.setEnabled(true);
        mNetworkView.setAdapter(mNetworkAdapter); // Connect the adapter to the RecyclerView.
        updateNetworkList(); // Load the network data and display it.
    }

    /**
     * Called when the connection to the service is lost unexpectedly.
     * @param name The component name of the disconnected service.
     */
    @Override
    protected void onServiceDisconnected(ComponentName name) {
        super.onServiceDisconnected(name);

        // Disable UI elements that require the service, as it is no longer available.
        mRefreshLayout.setEnabled(false);
    }

    /**
     * Called when an activity you launched returns a result.
     * @param requestCode The integer code you originally supplied to startActivityForResult().
     * @param resultCode The integer result code returned by the child activity (e.g., RESULT_OK).
     * @param data An Intent which can return result data to the caller.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Close the navigation drawer if it's open for a cleaner UI transition.
        if (mDrawer.isDrawerOpen(GravityCompat.START)) {
            mDrawer.closeDrawer(GravityCompat.START);
        }

        // Handle the result based on which activity is returning it.
        switch (requestCode) {
            case REQUEST_PROVISION_SCAN: {
                if (resultCode == RESULT_OK) { // Check if the provisioning was successful.
                    updateNetworkList(); // Refresh the network list as a new node might have been added.

                    // Check if this was a "fast provision" flow.
                    boolean fastProv = data.getBooleanExtra(Constants.KEY_FAST_FROV, false);
                    long netKeyIndex = data.getLongExtra(Constants.KEY_NETWORK_INDEX, -1);
                    if (fastProv) {
                        // If so, start a special process to find the newly provisioned node by its identity.
                        mRefreshLayout.setRefreshing(true); // Show a loading indicator.
                        setActivityEnable(false); // Disable UI to prevent user interaction.

                        String mac = data.getStringExtra(Constants.KEY_NODE_MAC);
                        Node node = mUser.getNodeForMac(mac);
                        Network network = mUser.getNetworkForKeyIndex(netKeyIndex);

                        // Start the dedicated thread to scan for this specific node.
                        stopScanFastProvThread(); // Stop any previous instance first.
                        mScanFastProvThread = new ScanFastProvThread();
                        mScanFastProvThread.node = node;
                        mScanFastProvThread.network = network;
                        mScanFastProvThread.start();
                    } else {
                        // If it was a regular provision and a network was involved, go to that network's detail screen.
                        if (netKeyIndex > 0) {
                            gotoNetworkActivity(netKeyIndex);
                        }
                    }
                }
                return; // Exit the method.
            }
            case REQUEST_ADD_NETWORK: {
                if (resultCode == RESULT_OK) { // Check if a new network was added successfully.
                    updateNetworkList(); // Refresh the list to show the new network.
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * This hook is called whenever an item in your options menu is selected.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.nav_provision) {
            // This appears to handle an options menu item, though the FAB and nav drawer are the primary triggers.
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // --- Navigation and UI Helper Methods ---

    /**
     * Starts the ProvisionScanActivity to scan for and provision new devices.
     */
    private void gotoProvision() {
        Intent intent = new Intent(this, ProvisionScanActivity.class);
        startActivityForResult(intent, REQUEST_PROVISION_SCAN);
    }

    /**
     * Starts the NetworkActivity to show details for a specific network.
     * @param networkKeyIndex The unique identifier for the network to be displayed.
     */
    private void gotoNetworkActivity(long networkKeyIndex) {
        Intent intent = new Intent(MainActivity.this, NetworkActivity.class);
        // Pass the network's key index to the next activity so it knows which network to load.
        intent.putExtra(Constants.KEY_NETWORK_INDEX, networkKeyIndex);
        startActivityForResult(intent, REQUEST_NETWORK);
    }

    /**
     * Enables or disables major UI components to prevent user interaction during a blocking operation.
     * @param enable True to enable UI interaction, false to disable.
     */
    private void setActivityEnable(boolean enable) {
        mNetworkView.setEnabled(enable);
        mFab.setEnabled(enable);
        // Lock the navigation drawer closed when the UI is disabled.
        mDrawer.setDrawerLockMode(enable ? DrawerLayout.LOCK_MODE_UNDEFINED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
    }

    /**
     * Reloads the list of networks from the MeshUser data source and updates the RecyclerView.
     */
    private void updateNetworkList() {
        mNetworkList.clear(); // Clear the old data.
        mNetworkList.addAll(mUser.getNetworkList()); // Add all current networks from the user data model.
        // Sort the list to ensure a consistent and predictable order.
        Collections.sort(mNetworkList, (o1, o2) -> {
            Long keyIndex1 = o1.getKeyIndex();
            Long keyIndex2 = o2.getKeyIndex();
            return keyIndex1.compareTo(keyIndex2);
        });
        // Notify the adapter that the data has changed, so it redraws the list on screen.
        mNetworkAdapter.notifyDataSetChanged();
    }

    /**
     * This method is called by the SwipeRefreshLayout's listener.
     * It triggers a new BLE scan and handles the UI update.
     */
    private void refresh() {
        getService().startScanBle(); // Tell the background service to start scanning for BLE devices.
        // This RxJava chain creates a small delay before hiding the refresh indicator, giving the user
        // a better visual experience and time for the scan to start finding devices.
        Observable.just(300L) // Emit the value 300.
                .subscribeOn(Schedulers.io()) // Perform the next operation on a background I/O thread.
                .doOnNext(Thread::sleep) // Wait for 300 milliseconds.
                .observeOn(AndroidSchedulers.mainThread()) // Switch back to the main UI thread for UI operations.
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
                        // After the delay, check if Bluetooth is enabled.
                        if (!getService().isBTEnable()) {
                            Toast.makeText(MainActivity.this, R.string.main_bt_disable_toast, Toast.LENGTH_SHORT)
                                    .show();
                        }
                        // Hide the "pull-to-refresh" animation.
                        mRefreshLayout.setRefreshing(false);
                        // Notify the adapter to update its views, which might now show newly discovered nodes.
                        mNetworkAdapter.notifyDataSetChanged();
                    }
                });
    }

    /**
     * Stops and cleans up the thread used for the fast provisioning scan.
     */
    private void stopScanFastProvThread() {
        if (mScanFastProvThread != null) {
            mScanFastProvThread.interrupt(); // Signal the thread to stop.
            mScanFastProvThread = null;

            // Re-enable the UI and hide any loading indicators.
            mRefreshLayout.setRefreshing(false);
            setActivityEnable(true);
        }
    }

    /**
     * Shows a confirmation dialog before deleting a network.
     * @param netKeyIndex The index of the network to be deleted.
     */
    private void showDeleteNetworkDialog(long netKeyIndex) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.main_delete_network_dialog_message) // Set the confirmation message.
                .setNegativeButton(android.R.string.cancel, null) // The "Cancel" button does nothing.
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // When the user clicks "OK", execute the deletion.
                    new NetworkDeleteTask(netKeyIndex).run(); // Run the SDK's network deletion task.
                    updateNetworkList(); // Refresh the UI to remove the deleted network.
                })
                .show(); // Display the dialog.
    }

    // --- Inner Classes for RecyclerView ---

    /**
     * ViewHolder for the RecyclerView. It holds references to the views for a single list item.
     * This improves performance by avoiding repeated `findViewById` calls when scrolling.
     */
    private class NetHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        // A constant for the "Delete" menu item ID.
        static final int MENU_NET_DELETE = 0x11;

        // The Network data object associated with this list item.
        Network network;

        // UI elements within the list item's layout.
        TextView text1;
        ImageView infoIcon;

        NetHolder(View itemView) {
            super(itemView);

            // Find the views within the inflated layout once and store references to them.
            text1 = itemView.findViewById(R.id.text);
            infoIcon = itemView.findViewById(R.id.info_icon);

            // Set this class to handle click and long-click events on the item view.
            itemView.setOnClickListener(this);
            itemView.setOnLongClickListener(this);

            // Also handle clicks on the info icon separately.
            infoIcon.setOnClickListener(this);
        }

        /**
         * Handles short clicks on the list item or its info icon.
         */
        @Override
        public void onClick(View v) {
            if (v == infoIcon) {
                // If the user clicked the 'i' icon, show an information dialog.
                showNetworkInfo();
            } else {
                // If the user clicked anywhere else on the item, navigate to that network's detail screen.
                gotoNetworkActivity(network.getKeyIndex());
            }
        }

        /**
         * Handles long clicks on the list item, which brings up a context menu.
         */
        @Override
        public boolean onLongClick(View v) {
            // The default network (index 0) is protected and cannot be deleted.
            if (network.getKeyIndex() == Constants.NET_KEY_INDEX_DEFAULT) {
                return true; // Consume the event, but do nothing.
            }

            // Create and show a popup menu anchored to the clicked view.
            PopupMenu popupMenu = new PopupMenu(MainActivity.this, v);
            Menu menu = popupMenu.getMenu();
            // Add a "Delete" option to the menu.
            menu.add(Menu.NONE, MENU_NET_DELETE, 0, R.string.main_network_menu_delete);
            // Set a listener for menu item clicks.
            popupMenu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case MENU_NET_DELETE:
                        // If "Delete" is chosen, show the confirmation dialog.
                        showDeleteNetworkDialog(network.getKeyIndex());
                        return true;
                }
                return false;
            });
            popupMenu.show();

            return true; // Consume the long-click event.
        }

        /**
         * Gathers details about the network and displays them in an AlertDialog.
         */
        void showNetworkInfo() {
            List<String> nodeMacList = network.getNodeMacList();
            int nodeTotalCount = nodeMacList.size();
            int nodeScanCount = 0;
            // Count how many nodes in this network are currently visible in the BLE scan results.
            for (String mac : network.getNodeMacList()) {
                if (getService().hasScanResult(mac)) {
                    nodeScanCount++;
                }
            }
            String netkey = DataUtil.bigEndianBytesToHexString(network.getNetKey());

            // Format a message string with all the network details.
            String message = getString(R.string.main_network_info_message,
                    network.getName(), netkey, network.getKeyIndex(), nodeTotalCount, nodeScanCount);
            // Show the information in a simple dialog.
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.main_network_info_title)
                    .setMessage(message)
                    .show();
        }
    }

    /**
     * The Adapter for the RecyclerView. This class is responsible for:
     * 1. Creating new list items (ViewHolders).
     * 2. Binding data (Network objects) to those list items.
     * 3. Reporting the total number of items in the list.
     */
    private class NetworkAdapter extends RecyclerView.Adapter<NetHolder> {

        /**
         * Called when RecyclerView needs a new ViewHolder of the given type to represent an item.
         */
        @NonNull
        @Override
        public NetHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Inflate the XML layout for a single list item (R.layout.main_network_item).
            View itemView = getLayoutInflater().inflate(R.layout.main_network_item, parent, false);
            // Create and return a new NetHolder instance with the inflated view.
            return new NetHolder(itemView);
        }

        /**
         * Called by RecyclerView to display the data at the specified position.
         * This method updates the contents of the ViewHolder's itemView to reflect the item.
         */
        @Override
        public void onBindViewHolder(@NonNull NetHolder holder, int position) {
            // Get the Network object for the current list position.
            Network network = mNetworkList.get(position);
            // Associate this network data with the ViewHolder.
            holder.network = network;

            // Set the text of the TextView in the list item to the network's name.
            holder.text1.setText(network.getName());
        }

        /**
         * Returns the total number of items in the data set held by the adapter.
         */
        @Override
        public int getItemCount() {
            return mNetworkList.size();
        }
    }

    /**
     * A thread dedicated to scanning for a newly provisioned node using its "Node Identity" beacon.
     * This is used to confirm the node has successfully joined the network and is advertising correctly.
     */
    private class ScanFastProvThread extends Thread {
        Node node;
        Network network;

        boolean stop = false;

        /**
         * The main entry point for the thread's execution.
         */
        @Override
        public void run() {
            mLog.d("ScanFastProvThread start");
            execute();
            mLog.d("ScanFastProvThread end");
        }

        /**
         * Overrides the default interrupt method to also set our custom `stop` flag.
         * This ensures the loop in `execute()` will terminate.
         */
        @Override
        public void interrupt() {
            super.interrupt();
            stop = true;
        }

        /**
         * The core logic of the thread. It continuously scans for the specific node.
         */
        private void execute() {
            // Loop until the `stop` flag is set to true.
            while (!stop) {
                // Get all nodes currently visible in the BLE scan.
                List<ScanResult> nodeList = getService().getNodeScanList();
                for (ScanResult sr : nodeList) {
                    assert sr.getScanRecord() != null;
                    byte[] scanRecord = sr.getScanRecord().getBytes();
                    // Check if the advertisement packet indicates a Mesh Node Identity.
                    if (!MeshUtils.isMeshNodeIdentity(scanRecord)) {
                        continue; // If not, skip to the next device.
                    }

                    // Extract the Hash and Random values from the advertisement packet.
                    byte[][] hashRnd = MeshUtils.getNodeHashAndRandom(scanRecord);
                    assert hashRnd != null;
                    byte[] hash = hashRnd[0];
                    byte[] random = hashRnd[1];

                    // Locally calculate what the hash *should* be using the network's identity key and the node's address.
                    byte[] calcHash = MeshUtils.calcNodeHash(network.getIdentityKey(), random, node.getUnicastAddress());

                    // Compare the calculated hash with the hash from the advertisement.
                    if (DataUtil.equleBytes(hash, calcHash)) {
                        // If they match, we have found our node!
                        // Switch to the main UI thread to perform UI actions.
                        runOnUiThread(() -> {
                            stopScanFastProvThread(); // Stop this scanning process.

                            // Navigate to the network activity, passing along the scan result.
                            Intent intent = new Intent(MainActivity.this, NetworkActivity.class);
                            intent.putExtra(Constants.KEY_NETWORK_INDEX, network.getKeyIndex());
                            intent.putExtra(Constants.KEY_SCAN_RESULT, sr);
                            startActivityForResult(intent, REQUEST_NETWORK);
                        });
                        return; // Exit the execute method and terminate the thread.
                    }
                }

                // If the node wasn't found in this pass, wait a moment before trying again.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    mLog.w("ScanFastProvThread is interrupted");
                    Thread.currentThread().interrupt(); // Preserve the interrupted status.
                    return; // Exit the loop.
                }
            }
        }
    }
}
