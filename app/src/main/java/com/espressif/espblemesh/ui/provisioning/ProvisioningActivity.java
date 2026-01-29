// The package name defines the project's namespace and directory structure.
package com.espressif.espblemesh.ui.provisioning;

// Import statements for Android SDK classes.

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import com.espressif.blemesh.client.IMeshMessager;
import com.espressif.blemesh.client.IMeshProvisioner;
import com.espressif.blemesh.client.MeshGattClient;
import com.espressif.blemesh.client.callback.MeshGattCallback;
import com.espressif.blemesh.client.callback.MessageCallback;
import com.espressif.blemesh.client.callback.ProvisioningCallback;
import com.espressif.blemesh.constants.MeshConstants;
import com.espressif.blemesh.model.App;
import com.espressif.blemesh.model.Network;
import com.espressif.blemesh.model.Node;
import com.espressif.blemesh.model.message.custom.FastProvInfoSetMessage;
import com.espressif.blemesh.model.message.standard.AppKeyAddMessage;
import com.espressif.blemesh.model.message.standard.CompositionDataGetMessage;
import com.espressif.blemesh.user.MeshUser;
import com.espressif.blemesh.utils.MeshUtils;
import com.espressif.espblemesh.R;
import com.espressif.espblemesh.app.BaseActivity;
import com.espressif.espblemesh.constants.Constants;
import com.espressif.espblemesh.ui.settings.SettingsActivity;
import com.espressif.espblemesh.utils.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import libs.espressif.log.EspLog;
import libs.espressif.utils.TextUtils;

/**
 * ProvisioningActivity handles the user interface and logic for configuring and provisioning a single
 * unprovisioned device. This screen appears after a user selects a device from the ProvisionScanActivity.
 * It manages the entire step-by-step communication process with the device to add it securely to a mesh network.
 */
public class ProvisioningActivity extends BaseActivity {
    // Logger for this class, used for debugging.
    private final EspLog mLog = new EspLog(getClass());

    // --- Member Variables for Core Logic and Data ---
    private MeshUser mUser; // Singleton instance holding all mesh data.
    private ScanResult mScanResult; // The device selected from the previous screen.
    private MeshGattClient mMeshGattClient; // The client that manages the low-level GATT connection.
    private IMeshProvisioner mProvisioner; // The SDK object responsible for the provisioning state machine.
    private IMeshMessager mMessager; // The SDK object for sending messages AFTER provisioning is complete.
    private Node mNode; // The Node object representing the device after it's provisioned.
    private App mApp; // The application key to be added to the node.
    private Network mNetwork; // The network the device will be provisioned into.

    // --- UI Component Member Variables ---
    private View mProgressView; // The view that shows a progress bar and status messages.
    private Button mCancelBtn; // The button to cancel the provisioning process.
    private RecyclerView mRecyclerView; // The list view for displaying status messages.
    private MsgAdapter mMsgAdapter; // The adapter for the status message RecyclerView.
    private List<String> mMsgList; // The data source for the status messages.

    private View mConfigForm; // The initial form for configuring the device name and network.
    private EditText mDeviceNameET; // Input field for the node's name.
    private CheckBox mFastProvCB; // Checkbox to enable "Fast Provisioning" mode.
    private View mFastProvForm; // A sub-form for fast provisioning settings (e.g., number of devices).
    private EditText mFastProvCountET; // Input for how many subsequent devices to fast provision.
    private Spinner mNetworkSp; // Dropdown to select which mesh network to join.
    private List<Network> mNetworkList; // The list of available networks.
    private TextView mHintTV; // A text view to display hints or error messages.

    // --- State Flags ---
    // A flag to indicate if provisioning was successful. `volatile` ensures visibility across threads.
    private volatile boolean mProvResult = false;
    // A flag to indicate if the user has initiated provisioning. `volatile` ensures visibility across threads.
    private volatile boolean mWillProv = false;

    /**
     * Called when the activity is first created.
     * @param savedInstanceState If the activity is being re-initialized, this bundle contains its previously saved state.
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Set the user interface layout for this activity.
        setContentView(R.layout.provisioning_activity);
        // Setup the toolbar.
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setHomeAsUpEnable(true); // Show the "Up" button (back arrow).

        // --- Initialize Data ---
        mUser = MeshUser.Instance; // Get the singleton instance of MeshUser.
        // Get the currently selected AppKey from settings.
        long usedAppKeyIndex = SettingsActivity.getUsedAppKeyIndex(this);
        mApp = mUser.getAppForKeyIndex(usedAppKeyIndex);
        // Get the ScanResult of the chosen device, passed from the previous activity.
        mScanResult = getIntent().getParcelableExtra(Constants.KEY_SCAN_RESULT);

        // --- Initialize UI Views ---
        mProgressView = findViewById(R.id.progress);
        mCancelBtn = findViewById(R.id.cancel_btn);
        mCancelBtn.setOnClickListener(v -> {
            // When Cancel is clicked, stop the provisioning intent and close the connection.
            mWillProv = false;
            closeGatt();
            showProgress(false); // Hide the progress view and show the config form again.
        });

        // Setup the RecyclerView for showing log messages.
        mRecyclerView = findViewById(R.id.recycler_view);
        mMsgList = new ArrayList<>();
        mMsgAdapter = new MsgAdapter();
        mRecyclerView.setAdapter(mMsgAdapter);

        // Setup the main configuration form.
        mConfigForm = findViewById(R.id.config_form);

        mDeviceNameET = findViewById(R.id.config_device_name);
        // Pre-fill the device name with its advertised BLE name.
        mDeviceNameET.setText(Utils.getBLEDeviceName(mScanResult));

        // Setup the Fast Provisioning checkbox and its associated form.
        mFastProvCB = findViewById(R.id.config_fast_prov_check);
        mFastProvForm = findViewById(R.id.config_fast_prov_form);
        mFastProvCountET = findViewById(R.id.config_fast_prov_count);
        mFastProvCB.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Show or hide the fast provisioning count input based on the checkbox state.
            if (isChecked) {
                mFastProvForm.setVisibility(View.VISIBLE);
            } else {
                mFastProvForm.setVisibility(View.GONE);
            }
        });
        mFastProvCB.setChecked(false); // Default to off.

        // Setup the network selection spinner.
        mNetworkSp = findViewById(R.id.config_netwok);
        mNetworkList = mUser.getNetworkList();
        // Sort the networks to ensure a consistent order.
        Collections.sort(mNetworkList, (o1, o2) -> {
            Long index1 = o1.getKeyIndex();
            Long index2 = o2.getKeyIndex();
            return index1.compareTo(index2);
        });
        // Create a list of names from the network objects for the spinner.
        // The list of network names for the spinner.
        List<String> mNetNameList = new ArrayList<>();
        for (Network network : mNetworkList) {
            mNetNameList.add(network.getName());
        }
        // Create an adapter and attach it to the spinner.
        ArrayAdapter<String> netAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                mNetNameList);
        netAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mNetworkSp.setAdapter(netAdapter);

        // Setup the "OK" button to start the process.
        // The "OK" button to start the provisioning process.
        Button mOKBtn = findViewById(R.id.config_ok_btn);
        mOKBtn.setOnClickListener(v -> {
            mWillProv = true; // Set the flag indicating the user wants to provision.
            connectGatt(); // Start the GATT connection process.
        });

        mHintTV = findViewById(R.id.hint_text);
    }

    /**
     * Called when the activity is being destroyed.
     * This is the final cleanup point.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Ensure we don't leave any pending operations or connections open.
        mWillProv = false;
        closeGatt();
    }

    /**
     * Helper method to add a message to the on-screen log and scroll to the bottom.
     * This must be run on the UI thread.
     * @param msg The message string to display.
     */
    private void updateMsg(String msg) {
        runOnUiThread(() -> {
            mMsgList.add(msg);
            mMsgAdapter.notifyItemInserted(mMsgList.size() - 1); // Efficiently update the list.
            mRecyclerView.scrollToPosition(mMsgList.size() - 1); // Auto-scroll to the latest message.
        });
    }

    /**
     * Toggles the visibility of the progress view and the configuration form.
     * @param show True to show the progress view, false to show the configuration form.
     */
    private void showProgress(boolean show) {
        if (show) {
            mProgressView.setVisibility(View.VISIBLE);
            mCancelBtn.setVisibility(View.VISIBLE);

            mConfigForm.setVisibility(View.GONE);
        } else {
            mProgressView.setVisibility(View.GONE);
            mCancelBtn.setVisibility(View.GONE);

            mConfigForm.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Initiates the connection to the BLE device's GATT server.
     */
    private void connectGatt() {
        closeGatt(); // Ensure any previous connection is closed.

        mHintTV.setText(""); // Clear any previous hints.
        showProgress(true); // Show the progress view.

        // Get the selected network from the spinner.
        mNetwork = mNetworkList.get(mNetworkSp.getSelectedItemPosition());

        // Create and configure a new MeshGattClient instance.
        mMeshGattClient = new MeshGattClient(mScanResult.getDevice());
        mMeshGattClient.setAppAddr(Constants.APP_ADDRESS_DEFAULT); // Set the local address for this client.
        // Set the device's UUID, which is necessary for provisioning.
        mMeshGattClient.setDeviceUUID(MeshUtils.getProvisioningUUID(mScanResult.getScanRecord().getBytes()));
        mMeshGattClient.setGattCallback(new GattCallback()); // Set low-level GATT callbacks.
        mMeshGattClient.setMeshCallback(new MeshCB()); // Set mesh-specific callbacks.
        mMeshGattClient.connect(getApplicationContext()); // Start the connection attempt.
    }

    /**
     * Safely closes the GATT connection and cleans up the client object.
     */
    private void closeGatt() {
        if (mMeshGattClient != null) {
            mMeshGattClient.close();
            mMeshGattClient = null;
        }
    }

    /**
     * ViewHolder for the RecyclerView that displays status messages.
     */
    private static class MsgHolder extends RecyclerView.ViewHolder {
        TextView text1;

        MsgHolder(View itemView) {
            super(itemView);
            text1 = itemView.findViewById(android.R.id.text1);
        }
    }

    /**
     * Adapter for the status message RecyclerView.
     */
    private class MsgAdapter extends RecyclerView.Adapter<MsgHolder> {

        @NonNull
        @Override
        public MsgHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Inflate a simple list item layout for each message.
            View itemView = getLayoutInflater().inflate(android.R.layout.simple_list_item_1, parent, false);
            return new MsgHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull MsgHolder holder, int position) {
            String msg = mMsgList.get(position);
            holder.text1.setText(msg);
        }

        @Override
        public int getItemCount() {
            return mMsgList.size();
        }
    }

    /**
     * Handles mesh-specific callbacks from the MeshGattClient. This is where the main
     * provisioning state machine is driven.
     */
    private class MeshCB extends MeshGattCallback {
        /**
         * Called when the SDK has finished discovering the mesh services on the device.
         * @param code Result code (CODE_SUCCESS on success).
         * @param provisioner The IMeshProvisioner object to use for provisioning.
         */
        @Override
        public void onDiscoverDeviceServiceResult(int code, IMeshProvisioner provisioner) {
            super.onDiscoverDeviceServiceResult(code, provisioner);

            if (code == CODE_SUCCESS) {
                mProvisioner = provisioner;
                String nodeName = mDeviceNameET.getText().toString();
                // Get the selected network again to pass to the provisioner.
                Network network = mNetworkList.get(mNetworkSp.getSelectedItemPosition());
                mProvisioner.setProvisioningCallback(new ProvisioningCB());
                // Start the actual provisioning process.
                mProvisioner.provisioning(nodeName, network);
            } else {
                if (!mProvResult) {
                    updateMsg("Discover device service failed");
                }
            }
        }

        /**
         * Called after provisioning is complete and the app has reconnected to the device
         * as a provisioned node to discover its proxy services.
         * @param code Result code (CODE_SUCCESS on success).
         * @param messager The IMeshMessager object used to send messages to the now-provisioned node.
         */
        @Override
        public void onDiscoverNodeServiceResult(int code, IMeshMessager messager) {
            super.onDiscoverNodeServiceResult(code, messager);

            mLog.d("onDiscoverNodeServiceResult " + code);
            if (code == CODE_SUCCESS) {
                mMessager = messager;
                // Configure the messager with the correct network context.
                mMessager.setNetwork(mUser.getNetworkForKeyIndex(mNetwork.getKeyIndex()));
                mMessager.setMessageCallback(new MessageCB());

                // The first post-provisioning step is to add the application key.
                mLog.d("Request to add AppKey");
                updateMsg("Request to add AppKey");
                AppKeyAddMessage messageAppKeyAdd = new AppKeyAddMessage(mNode, mApp.getAppKey(), mApp.getKeyIndex(),
                        mMessager.getNetwork().getKeyIndex());
                messageAppKeyAdd.setPostCount(1); // How many times to try sending the message.
                mMessager.appKeyAdd(messageAppKeyAdd);
            } else {
                if (mProvResult) {
                    // Provisioning succeeded, but configuring the node failed.
                    // This is a "successful" outcome for now, as the node is on the network.
                    // The user can configure it later.
                    mLog.w("Discover node service failed");
                    Intent intent = new Intent();
                    intent.putExtra(Constants.KEY_NODE_MAC, mScanResult.getDevice().getAddress());
                    intent.putExtra(Constants.KEY_FAST_FROV, false);
                    intent.putExtra(Constants.KEY_NETWORK_INDEX, mMessager.getNetwork().getKeyIndex());
                    setResult(RESULT_OK, intent);
                    finish();
                }
            }
        }
    }

    /**
     * Handles callbacks for messages sent to the provisioned node (e.g., AppKey Add).
     */
    private class MessageCB extends MessageCallback {
        /**
         * Called when the node sends back a status for the AppKeyAdd message.
         */
        @Override
        public void onAppKeyStatus(int status, long netKeyIndex, long appKeyIndex) {
            mLog.d("onAppKeyStatus " + status);
            updateMsg("Add App key status is " + status);

            // After adding the AppKey, get the node's composition data (its capabilities).
            mLog.d("Request to ge CompositionData");
            updateMsg("Request to ge CompositionData");
            CompositionDataGetMessage message = new CompositionDataGetMessage(mNode, 0);
            message.setPostCount(1);
            mMessager.compositionDataGet(message);
        }

        /**
         * Called when the node confirms the fast provisioning settings.
         */
        @Override
        public void onFastProvStatus() {
            super.onFastProvStatus();

            // The node is now configured as a provisioner itself. The process is complete.
            mLog.d("onFastProvStatus");
            Intent intent = new Intent();
            intent.putExtra(Constants.KEY_NODE_MAC, mScanResult.getDevice().getAddress());
            intent.putExtra(Constants.KEY_FAST_FROV, true); // Indicate fast provisioning was enabled.
            intent.putExtra(Constants.KEY_NETWORK_INDEX, mMessager.getNetwork().getKeyIndex());
            setResult(RESULT_OK, intent);
            finish();
        }

        /**
         * Called when the node sends back its composition data.
         */
        @Override
        public void onCompositionDataStatus(int status, int page) {
            super.onCompositionDataStatus(status, page);

            mLog.d("onCompositionDataStatus " + status);
            updateMsg("Get composition data status is " + status);

            boolean willFastProv = mFastProvCB.isChecked();
            if (willFastProv) {
                // If the user checked "Fast Provisioning", configure the node to act as a provisioner.
                mLog.d("Request to FastProv");
                int provCount = TextUtils.isEmpty(mFastProvCountET.getText()) ? 100 :
                        Integer.parseInt(mFastProvCountET.getText().toString());
                byte[] devUUID = mMessager.getDeviceUUID();
                // Create and send the message with all the fast provisioning parameters.
                FastProvInfoSetMessage message = new FastProvInfoSetMessage(mNode, mApp);
                message.setProvCount(provCount);
                message.setUnicastAddressMin(0x0400L); // Set the starting address for new nodes.
                message.setPrimaryProvisionerAddress(mApp.getUnicastAddr());
                message.setMatchValue(new byte[]{devUUID[0], devUUID[1]});
                message.setGroupAddress(MeshConstants.ADDRESS_GROUP_MIN);
                message.setAction((1 << 7) | 1);
                mMessager.fastProvInfoSet(message);
            } else {
                // If not fast provisioning, the process is complete.
                mLog.d("finish");
                Intent intent = new Intent();
                intent.putExtra(Constants.KEY_NODE_MAC, mScanResult.getDevice().getAddress());
                intent.putExtra(Constants.KEY_FAST_FROV, false);
                intent.putExtra(Constants.KEY_NETWORK_INDEX, mMessager.getNetwork().getKeyIndex());
                setResult(RESULT_OK, intent);
                finish();
            }
        }
    }

    /**
     * Handles callbacks specifically for the provisioning process itself.
     */
    private class ProvisioningCB extends ProvisioningCallback {
        /**
         * Called if any step of the provisioning process fails.
         */
        @Override
        public void onProvisioningFailed(int code) {
            super.onProvisioningFailed(code);

            mProvResult = false;
            runOnUiThread(() -> {
                showProgress(false); // Go back to the configuration screen.
                if (code == -10) {
                    // Specific error code, likely indicating a BLE-level problem.
                    mHintTV.setText(R.string.provisioning_bluetooth_hint);
                }
            });

            mLog.w("onProvisioningFailed " + code);
            updateMsg("onProvisioningFailed: " + code);
        }

        /**
         * Called when the device has been successfully provisioned and assigned an address.
         */
        @Override
        public void onProvisioningSuccess(int code, Node node) {
            super.onProvisioningSuccess(code, node);

            mProvResult = true;
            // Use RxJava to perform a sequence of operations in the background.
            Observable.just(node)
                    .subscribeOn(Schedulers.io()) // Run on a background thread.
                    .doOnNext(n -> mUser.reload()) // Reload the user data from the database to include the new node.
                    .doOnNext(n -> Thread.sleep(500)) // Wait briefly for all data to settle.
                    .observeOn(AndroidSchedulers.mainThread()) // Switch to the UI thread for UI work.
                    .doOnNext(n -> {
                        String msg = "Provisioning success";
                        mLog.d(msg);
                        updateMsg(msg);

                        // Get the full Node object from our database.
                        mNode = mUser.getNodeForMac(n.getMac());

                        // Clean up the provisioner object.
                        mProvisioner.release();
                        mProvisioner = null;

                        // Set an empty result for now. A more detailed result will be set upon full completion.
                        setResult(RESULT_OK, new Intent());

                        // Now, reconnect to the node to configure it (add AppKey, etc.).
                        mLog.d("Discover node service");
                        mMeshGattClient.discoverGattServices();
                    })
                    .subscribe();
        }
    }

    /**
     * Handles low-level Bluetooth GATT events like connection state changes.
     */
    private class GattCallback extends BluetoothGattCallback {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED: {
                        String msg = "Gatt connected";
                        mLog.d(msg);
                        updateMsg(msg);
                        // The MeshGattClient will automatically start service discovery.
                        break;
                    }
                    case BluetoothProfile.STATE_CONNECTING: {
                        String msg = "Gatt connecting";
                        mLog.d(msg);
                        updateMsg(msg);
                        break;
                    }
                    case BluetoothProfile.STATE_DISCONNECTED: {
                        String msg = "Gatt disconnected";
                        mLog.d(msg);
                        updateMsg(msg);
                        // If we weren't expecting to disconnect, try to reconnect.
                        if (mWillProv) {
                            runOnUiThread(ProvisioningActivity.this::connectGatt);
                        }
                        break;
                    }
                    case BluetoothProfile.STATE_DISCONNECTING: {
                        String msg = "Gatt disconnecting";
                        mLog.d(msg);
                        updateMsg(msg);
                        break;
                    }
                }
            } else {
                mLog.w("onConnectionStateChange status=" + status);
                if (mWillProv) {
                    // If the connection failed, show an error and go back to the config screen.
                    String msg = "Gatt status is " + status;
                    updateMsg(msg);
                    runOnUiThread(() -> {
                        showProgress(false);
                        mHintTV.setText(R.string.provisioning_bluetooth_hint);
                    });
                }
            }
        }
    }
}
