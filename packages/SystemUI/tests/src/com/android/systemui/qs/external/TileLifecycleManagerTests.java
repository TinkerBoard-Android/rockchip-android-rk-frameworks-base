/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.qs.external;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.service.quicksettings.TileService;
import android.test.AndroidTestCase;
import android.util.ArraySet;
import android.util.Log;

public class TileLifecycleManagerTests extends AndroidTestCase {
    public static final String TILE_UPDATE_BROADCAST = "com.android.systemui.tests.TILE_UPDATE";
    public static final String EXTRA_CALLBACK = "callback";

    private HandlerThread mThread;
    private Handler mHandler;
    private TileLifecycleManager mStateManager;
    private final Object mBroadcastLock = new Object();
    private final ArraySet<String> mCallbacks = new ArraySet<>();
    private boolean mBound;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mThread = new HandlerThread("TestThread");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mStateManager = new TileLifecycleManager(mHandler, getContext(),
                new Intent(mContext, FakeTileService.class), new UserHandle(UserHandle.myUserId()));
        mCallbacks.clear();
        getContext().registerReceiver(mReceiver, new IntentFilter(TILE_UPDATE_BROADCAST));
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (mBound) {
            unbindService();
        }
        mThread.quit();
        getContext().unregisterReceiver(mReceiver);
    }

    public void testSync() {
        syncWithHandler();
    }

    public void testBind() {
        bindService();
        waitForCallback("onCreate");
    }

    public void testUnbind() {
        bindService();
        waitForCallback("onCreate");
        unbindService();
        waitForCallback("onDestroy");
    }

    public void testTileServiceCallbacks() {
        bindService();
        waitForCallback("onCreate");

        mStateManager.onTileAdded();
        waitForCallback("onTileAdded");
        mStateManager.onStartListening();
        waitForCallback("onStartListening");
        mStateManager.onClick(null);
        waitForCallback("onClick");
        mStateManager.onStopListening();
        waitForCallback("onStopListening");
        mStateManager.onTileRemoved();
        waitForCallback("onTileRemoved");

        unbindService();
    }

    public void testAddedBeforeBind() {
        mStateManager.onTileAdded();

        bindService();
        waitForCallback("onCreate");
        waitForCallback("onTileAdded");
    }

    public void testListeningBeforeBind() {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();

        bindService();
        waitForCallback("onCreate");
        waitForCallback("onTileAdded");
        waitForCallback("onStartListening");
    }

    public void testClickBeforeBind() {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onClick(null);

        bindService();
        waitForCallback("onCreate");
        waitForCallback("onTileAdded");
        waitForCallback("onStartListening");
        waitForCallback("onClick");
    }

    public void testListeningNotListeningBeforeBind() {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onStopListening();

        bindService();
        waitForCallback("onCreate");
        unbindService();
        waitForCallback("onDestroy");
        assertFalse(mCallbacks.contains("onStartListening"));
    }

    public void testNoClickOfNotListeningAnymore() {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onClick(null);
        mStateManager.onStopListening();

        bindService();
        waitForCallback("onCreate");
        unbindService();
        waitForCallback("onDestroy");
        assertFalse(mCallbacks.contains("onClick"));
    }

    public void testComponentEnabling() {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();

        PackageManager pm = getContext().getPackageManager();
        pm.setComponentEnabledSetting(new ComponentName(getContext(), FakeTileService.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

        bindService();
        assertTrue(mStateManager.mReceiverRegistered);

        pm.setComponentEnabledSetting(new ComponentName(getContext(), FakeTileService.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        waitForCallback("onCreate");
    }

    public void testKillProcess() {
        mStateManager.onStartListening();
        bindService();
        waitForCallback("onCreate");
        waitForCallback("onStartListening");

        getContext().sendBroadcast(new Intent(FakeTileService.ACTION_KILL));

        waitForCallback("onCreate");
        waitForCallback("onStartListening");
    }

    private void bindService() {
        mBound = true;
        mStateManager.setBindService(true);
    }

    private void unbindService() {
        mBound = false;
        mStateManager.setBindService(false);
    }

    private void waitForCallback(String callback) {
        for (int i = 0; i < 25; i++) {
            if (mCallbacks.contains(callback)) {
                mCallbacks.remove(callback);
                return;
            }
            synchronized (mBroadcastLock) {
                try {
                    mBroadcastLock.wait(500);
                } catch (InterruptedException e) {
                }
            }
        }
        if (mCallbacks.contains(callback)) {
            mCallbacks.remove(callback);
            return;
        }
        fail("Didn't receive callback: " + callback);
    }

    private void syncWithHandler() {
        final Object lock = new Object();
        synchronized (lock) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            });
            try {
                lock.wait(5000);
            } catch (InterruptedException e) {
            }
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mCallbacks.add(intent.getStringExtra(EXTRA_CALLBACK));
            synchronized (mBroadcastLock) {
                mBroadcastLock.notify();
            }
        }
    };

    public static class FakeTileService extends TileService {
        public static final String ACTION_KILL = "com.android.systemui.test.KILL";

        @Override
        public void onCreate() {
            super.onCreate();
            registerReceiver(mReceiver, new IntentFilter(ACTION_KILL));
            sendCallback("onCreate");
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            unregisterReceiver(mReceiver);
            sendCallback("onDestroy");
        }

        @Override
        public void onTileAdded() {
            sendCallback("onTileAdded");
        }

        @Override
        public void onTileRemoved() {
            sendCallback("onTileRemoved");
        }

        @Override
        public void onStartListening() {
            sendCallback("onStartListening");
        }

        @Override
        public void onStopListening() {
            sendCallback("onStopListening");
        }

        @Override
        public void onClick() {
            sendCallback("onClick");
        }

        private void sendCallback(String callback) {
            Log.d("TileLifecycleManager", "Relaying: " + callback);
            sendBroadcast(new Intent(TILE_UPDATE_BROADCAST)
                    .putExtra(EXTRA_CALLBACK, callback));
        }

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_KILL.equals(intent.getAction())) {
                    Process.killProcess(Process.myPid());
                }
            }
        };
    }
}