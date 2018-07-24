
/*
 * Copyright (C) 2017 The Android Open Source Project
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
 */

package com.android.bluetooth.btservice;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hid.HidHostService;
import com.android.bluetooth.pan.PanService;
import com.android.bluetooth.ba.BATService;
import com.android.internal.R;

import java.util.HashSet;
import java.util.List;

// Describes the phone policy
//
// The policy should be as decoupled from the stack as possible. In an ideal world we should not
// need to have this policy talk with any non-public APIs and one way to enforce that would be to
// keep this file outside the Bluetooth process. Unfortunately, keeping a separate process alive is
// an expensive and a tedious task.
//
// Best practices:
// a) PhonePolicy should be ALL private methods
//    -- Use broadcasts which can be listened in on the BroadcastReceiver
// b) NEVER call from the PhonePolicy into the Java stack, unless public APIs. It is OK to call into
// the non public versions as long as public versions exist (so that a 3rd party policy can mimick)
// us.
//
// Policy description:
//
// Policies are usually governed by outside events that may warrant an action. We talk about various
// events and the resulting outcome from this policy:
//
// 1. Adapter turned ON: At this point we will try to auto-connect the (device, profile) pairs which
// have PRIORITY_AUTO_CONNECT. The fact that we *only* auto-connect Headset and A2DP is something
// that is hardcoded and specific to phone policy (see autoConnect() function)
// 2. When the profile connection-state changes: At this point if a new profile gets CONNECTED we
// will try to connect other profiles on the same device. This is to avoid collision if devices
// somehow end up trying to connect at same time or general connection issues.
class PhonePolicy {
    private static final boolean DBG = true;
    private static final String TAG = "BluetoothPhonePolicy";

    // Message types for the handler (internal messages generated by intents or timeouts)
    private static final int MESSAGE_PROFILE_CONNECTION_STATE_CHANGED = 1;
    private static final int MESSAGE_PROFILE_INIT_PRIORITIES = 2;
    private static final int MESSAGE_CONNECT_OTHER_PROFILES = 3;
    private static final int MESSAGE_ADAPTER_STATE_TURNED_ON = 4;
    private static final int MESSAGE_AUTO_CONNECT_PROFILES = 50;

    // Timeouts
    private static final int AUTO_CONNECT_PROFILES_TIMEOUT= 500;
    @VisibleForTesting static int sConnectOtherProfilesTimeoutMillis = 6000; // 6s
    private static final int MESSAGE_PROFILE_ACTIVE_DEVICE_CHANGED = 5;

    private final AdapterService mAdapterService;
    private final ServiceFactory mFactory;
    private final Handler mHandler;
    private final HashSet<BluetoothDevice> mHeadsetRetrySet = new HashSet<>();
    private final HashSet<BluetoothDevice> mA2dpRetrySet = new HashSet<>();
    private final HashSet<BluetoothDevice> mConnectOtherProfilesDeviceSet = new HashSet<>();

    // Broadcast receiver for all changes to states of various profiles
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                errorLog("Received intent with null action");
                return;
            }
            switch (action) {
                case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_CONNECTION_STATE_CHANGED,
                            BluetoothProfile.HEADSET, -1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_CONNECTION_STATE_CHANGED,
                            BluetoothProfile.A2DP, -1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_CONNECTION_STATE_CHANGED,
                            BluetoothProfile.A2DP_SINK,-1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_ACTIVE_DEVICE_CHANGED,
                            BluetoothProfile.A2DP, -1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    // Only pass the message on if the adapter has actually changed state from
                    // non-ON to ON. NOTE: ON is the state depicting BREDR ON and not just BLE ON.
                    int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (newState == BluetoothAdapter.STATE_ON) {
                        mHandler.obtainMessage(MESSAGE_ADAPTER_STATE_TURNED_ON).sendToTarget();
                    }
                    break;
                case BluetoothDevice.ACTION_UUID:
                    mHandler.obtainMessage(MESSAGE_PROFILE_INIT_PRIORITIES, intent).sendToTarget();
                    break;
                default:
                    Log.e(TAG, "Received unexpected intent, action=" + action);
                    break;
            }
        }
    };

    @VisibleForTesting
    BroadcastReceiver getBroadcastReceiver() {
        return mReceiver;
    }

    // Handler to handoff intents to class thread
    class PhonePolicyHandler extends Handler {
        PhonePolicyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_PROFILE_INIT_PRIORITIES: {
                    Intent intent = (Intent) msg.obj;
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    Parcelable[] uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                    debugLog("Received ACTION_UUID for device " + device);
                    if (uuids != null) {
                        ParcelUuid[] uuidsToSend = new ParcelUuid[uuids.length];
                        for (int i = 0; i < uuidsToSend.length; i++) {
                            uuidsToSend[i] = (ParcelUuid) uuids[i];
                            debugLog("index=" + i + "uuid=" + uuidsToSend[i]);
                        }
                        processInitProfilePriorities(device, uuidsToSend);
                    }
                }
                break;

                case MESSAGE_PROFILE_CONNECTION_STATE_CHANGED: {
                    Intent intent = (Intent) msg.obj;
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device.getAddress().equals(BATService.mBAAddress)) {
                        Log.d(TAG," Update from BA, bail out");
                        break;
                    }
                    int prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                    int nextState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                    processProfileStateChanged(device, msg.arg1, nextState, prevState);
                }
                break;

                case MESSAGE_PROFILE_ACTIVE_DEVICE_CHANGED: {
                    Intent intent = (Intent) msg.obj;
                    BluetoothDevice activeDevice =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    processProfileActiveDeviceChanged(activeDevice, msg.arg1);
                }
                break;

                case MESSAGE_CONNECT_OTHER_PROFILES: {
                    // Called when we try connect some profiles in processConnectOtherProfiles but
                    // we send a delayed message to try connecting the remaining profiles
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    processConnectOtherProfiles(device);
                    mConnectOtherProfilesDeviceSet.remove(device);
                    break;
                }
                case MESSAGE_ADAPTER_STATE_TURNED_ON:
                    // Call auto connect when adapter switches state to ON
                    resetStates();
                    autoConnect();
                    break;
                case MESSAGE_AUTO_CONNECT_PROFILES: {
                    if (DBG) debugLog( "MESSAGE_AUTO_CONNECT_PROFILES");
                    autoConnectProfilesDelayed();
                    break;
                }
            }
        }
    }

    ;

    // Policy API functions for lifecycle management (protected)
    protected void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        mAdapterService.registerReceiver(mReceiver, filter);
    }

    protected void cleanup() {
        mAdapterService.unregisterReceiver(mReceiver);
        resetStates();
    }

    PhonePolicy(AdapterService service, ServiceFactory factory) {
        mAdapterService = service;
        mFactory = factory;
        mHandler = new PhonePolicyHandler(service.getMainLooper());
    }

    // Policy implementation, all functions MUST be private
    private void processInitProfilePriorities(BluetoothDevice device, ParcelUuid[] uuids) {
        debugLog("processInitProfilePriorities() - device " + device);
        HidHostService hidService = mFactory.getHidHostService();
        A2dpService a2dpService = mFactory.getA2dpService();
        A2dpSinkService a2dpSinkService = mFactory.getA2dpSinkService();
        HeadsetService headsetService = mFactory.getHeadsetService();
        PanService panService = mFactory.getPanService();
        HearingAidService hearingAidService = mFactory.getHearingAidService();

        // Set profile priorities only for the profiles discovered on the remote device.
        // This avoids needless auto-connect attempts to profiles non-existent on the remote device
        if ((hidService != null) && (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Hid)
                || BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Hogp)) && (
                hidService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED)) {
            hidService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }

        // If we do not have a stored priority for HFP/A2DP (all roles) then default to on.
        if ((headsetService != null) && ((BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.HSP)
                || BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Handsfree)) && (
                headsetService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED))) {
            headsetService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }

        if ((a2dpService != null) && (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AudioSink)
                || BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AdvAudioDist)) && (
                a2dpService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED)) {
            a2dpService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }

        if ((a2dpSinkService != null)
                && (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AudioSource)
                || BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AdvAudioDist)) && (
                a2dpSinkService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED)) {
            a2dpSinkService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }

        if ((panService != null) && (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.PANU) && (
                panService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED)
                && mAdapterService.getResources()
                .getBoolean(R.bool.config_bluetooth_pan_enable_autoconnect))) {
            panService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }

        if ((hearingAidService != null) && BluetoothUuid.isUuidPresent(uuids,
                BluetoothUuid.HearingAid) && (hearingAidService.getPriority(device)
                == BluetoothProfile.PRIORITY_UNDEFINED)) {
            debugLog("setting hearing aid profile priority for device " + device);
            hearingAidService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }
    }

    private void processProfileStateChanged(BluetoothDevice device, int profileId, int nextState,
            int prevState) {
        debugLog("processProfileStateChanged, device=" + device + ", profile=" + profileId + ", "
                + prevState + " -> " + nextState);
        if ((profileId == BluetoothProfile.A2DP) || (profileId == BluetoothProfile.HEADSET)
                || profileId == BluetoothProfile.A2DP_SINK) {
            if (nextState == BluetoothProfile.STATE_CONNECTED) {
                debugLog("processProfileStateChanged: isTwsDevice: " + mAdapterService.isTwsPlusDevice(device));
                switch (profileId) {
                    case BluetoothProfile.A2DP:
                        mA2dpRetrySet.remove(device);
                        if (mAdapterService.isTwsPlusDevice(device)) {
                             setAutoConnectForA2dpSink(device);
                        }
                        break;
                    case BluetoothProfile.HEADSET:
                        mHeadsetRetrySet.remove(device);
                        if (mAdapterService.isTwsPlusDevice(device)) {
                             setAutoConnectForHeadset(device);
                        }
                        break;
                    case BluetoothProfile.A2DP_SINK:
                        setAutoConnectForA2dpSource(device);
                        break;
                }
                connectOtherProfile(device);
            }
            if (prevState == BluetoothProfile.STATE_CONNECTING
                    && nextState == BluetoothProfile.STATE_DISCONNECTED) {
                HeadsetService hsService = mFactory.getHeadsetService();
                boolean hsDisconnected = hsService == null || hsService.getConnectionState(device)
                        == BluetoothProfile.STATE_DISCONNECTED;
                A2dpService a2dpService = mFactory.getA2dpService();
                boolean a2dpDisconnected = a2dpService == null
                        || a2dpService.getConnectionState(device)
                        == BluetoothProfile.STATE_DISCONNECTED;
                debugLog("processProfileStateChanged, device=" + device + ", a2dpDisconnected="
                        + a2dpDisconnected + ", hsDisconnected=" + hsDisconnected);
                if (hsDisconnected && a2dpDisconnected) {
                    removeAutoConnectFromA2dpSink(device);
                    removeAutoConnectFromHeadset(device);
                }
            }
        }
    }

    private void processProfileActiveDeviceChanged(BluetoothDevice activeDevice, int profileId) {
        debugLog("processProfileActiveDeviceChanged, activeDevice=" + activeDevice + ", profile="
                + profileId);
        switch (profileId) {
            // Tracking active device changed intent only for A2DP so that we always connect to a
            // single device after toggling Bluetooth
            case BluetoothProfile.A2DP:
                // Ignore null active device since we don't know if the change is triggered by
                // normal device disconnection during Bluetooth shutdown or user action
                if (activeDevice == null) {
                    warnLog("processProfileActiveDeviceChanged: ignore null A2DP active device");
                    return;
                }
                for (BluetoothDevice device : mAdapterService.getBondedDevices()) {
                    if (!mAdapterService.isTwsPlusDevice(activeDevice)) {
                        removeAutoConnectFromA2dpSink(device);
                        removeAutoConnectFromHeadset(device);
                    }
                }
                setAutoConnectForA2dpSink(activeDevice);
                setAutoConnectForHeadset(activeDevice);
                break;
        }
    }

    private void resetStates() {
        mHeadsetRetrySet.clear();
        mA2dpRetrySet.clear();
    }

    // Delaying Auto Connect to make sure that all clients
    // are up and running, specially BluetoothHeadset.
    public void autoConnect() {
        debugLog( "delay auto connect by 500 ms");
        if ((mHandler.hasMessages(MESSAGE_AUTO_CONNECT_PROFILES) == false) &&
            (mAdapterService.isQuietModeEnabled()== false)) {
            Message m = mHandler.obtainMessage(MESSAGE_AUTO_CONNECT_PROFILES);
            mHandler.sendMessageDelayed(m,AUTO_CONNECT_PROFILES_TIMEOUT);
        }
    }

    private void autoConnectProfilesDelayed() {
        if (mAdapterService.getState() != BluetoothAdapter.STATE_ON) {
            errorLog("autoConnect: BT is not ON. Exiting autoConnect");
            return;
        }

        if (!mAdapterService.isQuietModeEnabled()) {
            debugLog("autoConnect: Initiate auto connection on BT on...");
            //Remote Device Profiles
            autoConnectA2dpSink();
            final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
            if (bondedDevices == null) {
                errorLog("autoConnect: bondedDevices are null");
                return;
            }
            for (BluetoothDevice device : bondedDevices) {
                autoConnectHeadset(device);
                autoConnectA2dp(device);
            }
        } else {
            debugLog("autoConnect() - BT is in quiet mode. Not initiating auto connections");
        }
    }

    private void autoConnectA2dp(BluetoothDevice device) {
        final A2dpService a2dpService = mFactory.getA2dpService();
        if (a2dpService == null) {
            warnLog("autoConnectA2dp: service is null, failed to connect to " + device);
            return;
        }
        int a2dpPriority = a2dpService.getPriority(device);
        if (a2dpPriority == BluetoothProfile.PRIORITY_AUTO_CONNECT) {
            debugLog("autoConnectA2dp: connecting A2DP with " + device);
            a2dpService.connect(device);
        } else {
            debugLog("autoConnectA2dp: skipped auto-connect A2DP with device " + device
                    + " priority " + a2dpPriority);
        }
    }

    private void autoConnectHeadset(BluetoothDevice device) {
        final HeadsetService hsService = mFactory.getHeadsetService();
        if (hsService == null) {
            warnLog("autoConnectHeadset: service is null, failed to connect to " + device);
            return;
        }
        int headsetPriority = hsService.getPriority(device);
        if (headsetPriority == BluetoothProfile.PRIORITY_AUTO_CONNECT) {
            debugLog("autoConnectHeadset: Connecting HFP with " + device);
            hsService.connect(device);
        } else {
            debugLog("autoConnectHeadset: skipped auto-connect HFP with device " + device
                    + " priority " + headsetPriority);
        }
    }

    private void autoConnectA2dpSink() {
        A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
        if (a2dpSinkService == null) {
            errorLog("autoConnectA2dpSink, service is null");
            return;
        }
        BluetoothDevice bondedDevices[] =  mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            errorLog("autoConnectA2dpSink, bondedDevices are null");
            return;
        }

        for (BluetoothDevice device : bondedDevices) {
             int priority = a2dpSinkService.getPriority(device);
             debugLog("autoConnectA2dpSink, attempt auto-connect with device " + device
                     + " priority " + priority);
            if (priority == BluetoothProfile.PRIORITY_AUTO_CONNECT) {
                debugLog("autoConnectA2dpSink() - Connecting A2DP Sink with " + device.toString());
                a2dpSinkService.connect(device);
            }
        }
    }

    private void connectOtherProfile(BluetoothDevice device) {
        if (mAdapterService.isQuietModeEnabled()) {
            debugLog("connectOtherProfile: in quiet mode, skip connect other profile " + device);
            return;
        }
        if (mConnectOtherProfilesDeviceSet.contains(device)) {
            debugLog("connectOtherProfile: already scheduled callback for " + device);
            return;
        }
        mConnectOtherProfilesDeviceSet.add(device);
        Message m = mHandler.obtainMessage(MESSAGE_CONNECT_OTHER_PROFILES);
        m.obj = device;
        mHandler.sendMessageDelayed(m, sConnectOtherProfilesTimeoutMillis);
    }

    // This function is called whenever a profile is connected.  This allows any other bluetooth
    // profiles which are not already connected or in the process of connecting to attempt to
    // connect to the device that initiated the connection.  In the event that this function is
    // invoked and there are no current bluetooth connections no new profiles will be connected.
    private void processConnectOtherProfiles(BluetoothDevice device) {
        debugLog("processConnectOtherProfiles, device=" + device);
        if (mAdapterService.getState() != BluetoothAdapter.STATE_ON) {
            warnLog("processConnectOtherProfiles, adapter is not ON " + mAdapterService.getState());
            return;
        }
        HeadsetService hsService = mFactory.getHeadsetService();
        A2dpService a2dpService = mFactory.getA2dpService();
        PanService panService = mFactory.getPanService();
        A2dpSinkService a2dpSinkService = mFactory.getA2dpSinkService();

        boolean a2dpConnected = false;
        boolean hsConnected = false;

        boolean atLeastOneProfileConnectedForDevice = false;
        boolean allProfilesEmpty = true;
        List<BluetoothDevice> a2dpConnDevList = null;
        List<BluetoothDevice> a2dpSinkConnDevList = null;
        List<BluetoothDevice> hsConnDevList = null;
        List<BluetoothDevice> panConnDevList = null;

        if (hsService != null) {
            hsConnDevList = hsService.getConnectedDevices();
            allProfilesEmpty &= hsConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= hsConnDevList.contains(device);
        }
        if (a2dpService != null) {
            a2dpConnDevList = a2dpService.getConnectedDevices();
            allProfilesEmpty &= a2dpConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= a2dpConnDevList.contains(device);
        }
        if (a2dpSinkService != null) {
            a2dpSinkConnDevList = a2dpSinkService.getConnectedDevices();
            allProfilesEmpty &= a2dpSinkConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= a2dpSinkConnDevList.contains(device);
        }
        if (panService != null) {
            panConnDevList = panService.getConnectedDevices();
            allProfilesEmpty &= panConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= panConnDevList.contains(device);
        }

        if (!atLeastOneProfileConnectedForDevice) {
            // Consider this device as fully disconnected, don't bother connecting others
            debugLog("processConnectOtherProfiles, all profiles disconnected for " + device);
            mHeadsetRetrySet.remove(device);
            mA2dpRetrySet.remove(device);
            if (allProfilesEmpty) {
                debugLog("processConnectOtherProfiles, all profiles disconnected for all devices");
                // reset retry status so that in the next round we can start retrying connections
                resetStates();
            }
            return;
        }

        if(a2dpConnDevList != null && !a2dpConnDevList.isEmpty()) {
            for (BluetoothDevice a2dpDevice : a2dpConnDevList)
            {
                if(a2dpDevice.equals(device))
                {
                    a2dpConnected = true;
                }
            }
        }

        if(hsConnDevList != null && !hsConnDevList.isEmpty()) {
            for (BluetoothDevice hsDevice : hsConnDevList)
            {
                if(hsDevice.equals(device))
                {
                    hsConnected = true;
                }
            }
        }

        // This change makes sure that we try to re-connect
        // the profile if its connection failed and priority
        // for desired profile is ON.
        debugLog("HF connected for device : " + device + " " +
                (hsConnDevList == null ? false :hsConnDevList.contains(device)));
        debugLog("A2DP connected for device : " + device + " " +
                (a2dpConnDevList == null ? false :a2dpConnDevList.contains(device)));
        debugLog("A2DPSink connected for device : " + device + " " +
                (a2dpSinkConnDevList == null ? false :a2dpSinkConnDevList.contains(device)));

        if (hsService != null) {
            if ((hsConnDevList.isEmpty() || !(hsConnDevList.contains(device)))
                    && (!mHeadsetRetrySet.contains(device))
                    && (hsService.getPriority(device) >= BluetoothProfile.PRIORITY_ON)
                    && (hsService.getConnectionState(device)
                               == BluetoothProfile.STATE_DISCONNECTED)
                    && (a2dpConnected || (a2dpService != null &&
                        a2dpService.getPriority(device) == BluetoothProfile.PRIORITY_OFF))) {

                debugLog("Retrying connection to HS with device " + device);
                int maxConnections = mAdapterService.getMaxConnectedAudioDevices();

                if (!hsConnDevList.isEmpty() && maxConnections == 1) {
                    Log.v(TAG,"HFP is already connected, ignore");
                    return;
                }

                // proceed connection only if a2dp is connected to this device
                // add here as if is already overloaded
                if (a2dpConnDevList.contains(device) ||
                     (hsService.getPriority(device) >= BluetoothProfile.PRIORITY_ON)) {
                    debugLog("Retrying connection to HS with device " + device);
                    mHeadsetRetrySet.add(device);
                    hsService.connect(device);
                } else {
                    debugLog("do not initiate connect as A2dp is not connected");
                }
            }
        }

        if (a2dpService != null) {
            if ((a2dpConnDevList.isEmpty() || !(a2dpConnDevList.contains(device)))
                    && (!mA2dpRetrySet.contains(device))
                    && (a2dpService.getPriority(device) >= BluetoothProfile.PRIORITY_ON)
                    && (a2dpService.getConnectionState(device)
                               == BluetoothProfile.STATE_DISCONNECTED)
                    && (hsConnected || (hsService != null &&
                        hsService.getPriority(device) == BluetoothProfile.PRIORITY_OFF))) {
                debugLog("Retrying connection to A2DP with device " + device);
                int maxConnections = mAdapterService.getMaxConnectedAudioDevices();

                if (!a2dpConnDevList.isEmpty() && maxConnections == 1) {
                    Log.v(TAG,"a2dp is already connected, ignore");
                    return;
                }

                // proceed connection only if HFP is connected to this device
                // add here as if is already overloaded
                if (hsConnDevList.contains(device) ||
                    (a2dpService.getPriority(device) >= BluetoothProfile.PRIORITY_ON)) {
                    debugLog("Retrying connection to A2DP with device " + device);
                    mA2dpRetrySet.add(device);
                    a2dpService.connect(device);
                } else {
                    debugLog("do not initiate connect as HFP is not connected");
                }
            }
        }
        if (panService != null) {
            // TODO: the panConnDevList.isEmpty() check below should be removed once
            // Multi-PAN is supported.
            if (panConnDevList.isEmpty() && (panService.getPriority(device)
                    >= BluetoothProfile.PRIORITY_ON) && (panService.getConnectionState(device)
                    == BluetoothProfile.STATE_DISCONNECTED)) {
                debugLog("Retrying connection to PAN with device " + device);
                panService.connect(device);
            }
        }
        // Connect A2DP Sink Service if HS is connected
        if (a2dpSinkService != null) {
            List<BluetoothDevice> sinkConnDevList = a2dpSinkService.getConnectedDevices();
            if (sinkConnDevList.isEmpty() &&
                    (a2dpSinkService.getPriority(device) >= BluetoothProfile.PRIORITY_ON) &&
                    (a2dpSinkService.getConnectionState(device) ==
                            BluetoothProfile.STATE_DISCONNECTED) &&
                    (hsConnected || (hsService != null &&
                         hsService.getPriority(device) == BluetoothProfile.PRIORITY_OFF))) {
                debugLog("Retrying connection for A2dpSink with device " + device);
                a2dpSinkService.connect(device);
            }
        }

    }

    private void setProfileAutoConnectionPriority(BluetoothDevice device, int profileId,
            boolean autoConnect) {
        debugLog("setProfileAutoConnectionPriority: device=" + device + ", profile=" + profileId
                + ", autoConnect=" + autoConnect);
        switch (profileId) {
            case BluetoothProfile.HEADSET: {
                HeadsetService hsService = mFactory.getHeadsetService();
                if (hsService == null) {
                    warnLog("setProfileAutoConnectionPriority: HEADSET service is null");
                    break;
                }
                removeAutoConnectFromDisconnectedHeadsets(hsService);
                if (autoConnect) {
                    hsService.setPriority(device, BluetoothProfile.PRIORITY_AUTO_CONNECT);
                }
                break;
            }
            case BluetoothProfile.A2DP: {
                A2dpService a2dpService = mFactory.getA2dpService();
                if (a2dpService == null) {
                    warnLog("setProfileAutoConnectionPriority: A2DP service is null");
                    break;
                }
                removeAutoConnectFromDisconnectedA2dpSinks(a2dpService);
                if (autoConnect) {
                    a2dpService.setPriority(device, BluetoothProfile.PRIORITY_AUTO_CONNECT);
                }
                break;
            }
            case BluetoothProfile.A2DP_SINK: {
                A2dpSinkService a2dpSinkService = mFactory.getA2dpSinkService();
                if (a2dpSinkService != null) {
                    if (BluetoothProfile.PRIORITY_AUTO_CONNECT != a2dpSinkService.getPriority(
                            device)) {
                        adjustOtherSourcePriorities(a2dpSinkService, a2dpSinkService.getConnectedDevices());
                        a2dpSinkService.setPriority(device, BluetoothProfile.PRIORITY_AUTO_CONNECT);
                    }
                }
                break;
            }
            default:
                Log.w(TAG, "Tried to set AutoConnect priority on invalid profile " + profileId);
                break;
        }
    }
    /**
     * Set a device's headset profile priority to PRIORITY_AUTO_CONNECT if device support that
     * profile
     *
     * @param device device whose headset profile priority should be PRIORITY_AUTO_CONNECT
     */
    private void setAutoConnectForHeadset(BluetoothDevice device) {
        HeadsetService hsService = mFactory.getHeadsetService();
        if (hsService == null) {
            warnLog("setAutoConnectForHeadset: HEADSET service is null");
            return;
        }
        if (hsService.getPriority(device) >= BluetoothProfile.PRIORITY_ON) {
            debugLog("setAutoConnectForHeadset: device " + device + " PRIORITY_AUTO_CONNECT");
            hsService.setPriority(device, BluetoothProfile.PRIORITY_AUTO_CONNECT);
        }
    }

    /**
     * Set a device's A2DP profile priority to PRIORITY_AUTO_CONNECT if device support that profile
     *
     * @param device device whose headset profile priority should be PRIORITY_AUTO_CONNECT
     */
    private void setAutoConnectForA2dpSink(BluetoothDevice device) {
        A2dpService a2dpService = mFactory.getA2dpService();
        if (a2dpService == null) {
            warnLog("setAutoConnectForA2dpSink: A2DP service is null");
            return;
        }
        if (a2dpService.getPriority(device) >= BluetoothProfile.PRIORITY_ON) {
            debugLog("setAutoConnectForA2dpSink: device " + device + " PRIORITY_AUTO_CONNECT");
            a2dpService.setPriority(device, BluetoothProfile.PRIORITY_AUTO_CONNECT);
        }
    }

    /**
     * Set device A2DP SINK priority to PRIORITY_AUTO_CONNECT if role is A2DP Sink
     */
    private void setAutoConnectForA2dpSource(BluetoothDevice device) {
        A2dpSinkService a2dpSinkService = mFactory.getA2dpSinkService();
        if (a2dpSinkService == null) {
            warnLog("setAutoConnectForA2dpSink: A2DP service is null");
            return;
        }
        if (a2dpSinkService.getPriority(device) >= BluetoothProfile.PRIORITY_ON) {
            debugLog("setAutoConnectForA2dpSink: device " + device + " PRIORITY_AUTO_CONNECT");
            a2dpSinkService.setPriority(device, BluetoothProfile.PRIORITY_AUTO_CONNECT);
        }
    }

    /**
     * Remove PRIORITY_AUTO_CONNECT from all headsets and set headset that used to have
     * PRIORITY_AUTO_CONNECT to PRIORITY_ON
     *
     * @param device device whose PRIORITY_AUTO_CONNECT priority should be removed
     */
    private void removeAutoConnectFromHeadset(BluetoothDevice device) {
        HeadsetService hsService = mFactory.getHeadsetService();
        if (hsService == null) {
            warnLog("removeAutoConnectFromHeadset: HEADSET service is null");
            return;
        }
        if (hsService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT) {
            debugLog("removeAutoConnectFromHeadset: device " + device + " PRIORITY_ON");
            hsService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }
    }

    /**
     * Remove PRIORITY_AUTO_CONNECT from all A2DP sinks and set A2DP sink that used to have
     * PRIORITY_AUTO_CONNECT to PRIORITY_ON
     *
     * @param device device whose PRIORITY_AUTO_CONNECT priority should be removed
     */
    private void removeAutoConnectFromA2dpSink(BluetoothDevice device) {
        A2dpService a2dpService = mFactory.getA2dpService();
        if (a2dpService == null) {
            warnLog("removeAutoConnectFromA2dpSink: A2DP service is null");
            return;
        }
        if (a2dpService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT) {
            debugLog("removeAutoConnectFromA2dpSink: device " + device + " PRIORITY_ON");
            a2dpService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }
    }

    private void adjustOtherSourcePriorities(
            A2dpSinkService a2dpSinkService, List<BluetoothDevice> connectedDeviceList) {
        for (BluetoothDevice device : mAdapterService.getBondedDevices()) {
            if (a2dpSinkService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT
                    && !connectedDeviceList.contains(device)) {
                a2dpSinkService.setPriority(device, BluetoothProfile.PRIORITY_ON);
            }
        }
    }

    private void removeAutoConnectFromDisconnectedHeadsets(HeadsetService hsService) {
        List<BluetoothDevice> connectedDeviceList = hsService.getConnectedDevices();
        for (BluetoothDevice device : mAdapterService.getBondedDevices()) {
            if (hsService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT
                    && !connectedDeviceList.contains(device)) {
                debugLog("removeAutoConnectFromDisconnectedHeadsets, device " + device
                        + " PRIORITY_ON");
                hsService.setPriority(device, BluetoothProfile.PRIORITY_ON);
            }
        }
    }

    private void removeAutoConnectFromDisconnectedA2dpSinks(A2dpService a2dpService) {
        List<BluetoothDevice> connectedDeviceList = a2dpService.getConnectedDevices();
        for (BluetoothDevice device : mAdapterService.getBondedDevices()) {
            if (a2dpService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT
                    && !connectedDeviceList.contains(device)) {
                debugLog("removeAutoConnectFromDisconnectedA2dpSinks, device " + device
                        + " PRIORITY_ON");
                a2dpService.setPriority(device, BluetoothProfile.PRIORITY_ON);
            }
        }
    }

    private static void debugLog(String msg) {
        if (DBG) {
            Log.i(TAG, msg);
        }
    }

    private static void warnLog(String msg) {
        Log.w(TAG, msg);
    }

    private static void errorLog(String msg) {
        Log.e(TAG, msg);
    }
}
