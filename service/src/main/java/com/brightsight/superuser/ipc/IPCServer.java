/*
 * Copyright 2021 John "topjohnwu" Wu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.brightsight.superuser.ipc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.FileObserver;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import com.brightsight.superuser.Shell;
import com.brightsight.superuser.internal.Container;
import com.brightsight.superuser.internal.IRootIPC;
import com.brightsight.superuser.internal.UiThreadHandler;
import com.brightsight.superuser.internal.Utils;

import java.io.File;
import java.lang.reflect.Constructor;

import static com.brightsight.superuser.internal.IPCMain.getServiceName;
import static com.brightsight.superuser.ipc.IPCClient.BUNDLE_BINDER_KEY;
import static com.brightsight.superuser.ipc.IPCClient.INTENT_DEBUG_KEY;
import static com.brightsight.superuser.ipc.IPCClient.INTENT_EXTRA_KEY;
import static com.brightsight.superuser.ipc.IPCClient.LOGGING_ENV;
import static com.brightsight.superuser.ipc.RootService.TAG;

class IPCServer extends IRootIPC.Stub implements IBinder.DeathRecipient {

    private final ComponentName mName;
    private final RootService service;

    @SuppressWarnings("FieldCanBeLocal")
    private final FileObserver observer;  /* A strong reference is required */

    private IBinder mClient;
    private Intent mIntent;

    @SuppressWarnings("unchecked")
    IPCServer(Context context, ComponentName name) throws Exception {
        Utils.context = context;
        IBinder binder = HiddenAPIs.getService(getServiceName(name));
        if (binder != null) {
            // There was already a root service running
            IRootIPC ipc = IRootIPC.Stub.asInterface(binder);
            try {
                // Trigger re-broadcast
                ipc.broadcast();

                // Our work is done!
                System.exit(0);
            } catch (RemoteException e) {
                // Daemon dead, continue
            }
        }

        Shell.enableVerboseLogging = System.getenv(LOGGING_ENV) != null;

        mName = name;
        Class<RootService> clz = (Class<RootService>) Class.forName(name.getClassName());
        Constructor<RootService> constructor = clz.getDeclaredConstructor();
        constructor.setAccessible(true);
        service = constructor.newInstance();
        service.attach(context, this);
        service.onCreate();
        observer = new AppObserver(new File(context.getPackageCodePath()));
        observer.startWatching();

        broadcast();

        // Start main thread looper
        Looper.loop();
    }

    class AppObserver extends FileObserver {

        private String name;

        AppObserver(File path) {
            super(path.getParent(), CREATE|DELETE|DELETE_SELF|MOVED_TO|MOVED_FROM);
            Utils.log(TAG, "Start monitoring: " + path.getParent());
            name = path.getName();
        }

        @Override
        public void onEvent(int event, @Nullable String path) {
            if (event == DELETE_SELF || name.equals(path))
                UiThreadHandler.run(IPCServer.this::stop);
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // Small trick for stopping the service without going through AIDL
        if (code == LAST_CALL_TRANSACTION - 1) {
            stop();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    @Override
    public void broadcast() {
        Intent broadcast = IPCClient.getBroadcastIntent(mName, this);
        broadcast.addFlags(HiddenAPIs.FLAG_RECEIVER_FROM_SHELL());
        service.sendBroadcast(broadcast);
    }

    @Override
    public synchronized IBinder bind(Intent intent) {
        // ComponentName doesn't match, abort
        if (!mName.equals(intent.getComponent()))
            System.exit(1);

        if (intent.getBooleanExtra(INTENT_DEBUG_KEY, false)) {
            // ActivityThread.attach(true, 0) will set this to system_process
            HiddenAPIs.setAppName(service.getPackageName() + ":root");
            // For some reason Debug.waitForDebugger() won't work, manual spin lock...
            while (!Debug.isDebuggerConnected()) {
                try { Thread.sleep(200); }
                catch (InterruptedException ignored) {}
            }
        }

        try {
            Bundle bundle = intent.getBundleExtra(INTENT_EXTRA_KEY);
            mClient = bundle.getBinder(BUNDLE_BINDER_KEY);
            mClient.linkToDeath(this, 0);

            Container<IBinder> c = new Container<>();
            UiThreadHandler.runAndWait(() -> {
                if (mIntent != null) {
                    Utils.log(TAG, mName + " rebind");
                    service.onRebind(intent);
                } else {
                    Utils.log(TAG, mName + " bind");
                    mIntent = intent.cloneFilter();
                }
                c.obj = service.onBind(intent);
            });
            return c.obj;
        } catch (Exception e) {
            Utils.err(TAG, e);
            return null;
        }
    }

    @Override
    public synchronized void unbind() {
        Utils.log(TAG, mName + " unbind");
        mClient.unlinkToDeath(this, 0);
        mClient = null;
        UiThreadHandler.run(() -> {
            if (!service.onUnbind(mIntent)) {
                service.onDestroy();
                System.exit(0);
            } else {
                // Register ourselves as system service
                HiddenAPIs.addService(getServiceName(mName), this);
            }
        });
    }

    @Override
    public synchronized void stop() {
        Utils.log(TAG, mName + " stop");
        if (mClient != null) {
            mClient.unlinkToDeath(this, 0);
            mClient = null;
        }
        UiThreadHandler.run(() -> {
            service.onDestroy();
            System.exit(0);
        });
    }

    @Override
    public void binderDied() {
        Utils.log(TAG, "client binderDied");
        unbind();
    }
}
