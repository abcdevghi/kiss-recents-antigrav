package fr.neamar.kiss.utils;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Client for the privileged Recents Binder service attached to launch intents by {@code RecentsHook}.
 */
public final class RecentsBridgeClient {

    public static final String EXTRA_BRIDGE_BUNDLE = "fr.neamar.kiss.RECENTS_BRIDGE_BUNDLE";
    public static final String KEY_BRIDGE_BINDER = "bridge_binder";

    private static final String DESCRIPTOR = "fr.neamar.kiss.xposed.IRecentsBridge";
    private static final int TX_GET_RECENT_TASKS = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TX_START_TASK = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TX_REMOVE_TASK = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int TX_GET_TASK_ICON = IBinder.FIRST_CALL_TRANSACTION + 3;

    private static final int TX_START_TASK_POPUP = IBinder.FIRST_CALL_TRANSACTION + 4;
    private static final int TX_START_TASK_SPLIT = IBinder.FIRST_CALL_TRANSACTION + 5;
    private static final int TX_GET_TASK_SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 6;

    private static volatile IBinder bridgeBinder;

    private RecentsBridgeClient() {
    }

    public static void attachFromIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        Bundle bundle = intent.getBundleExtra(EXTRA_BRIDGE_BUNDLE);
        if (bundle == null) {
            return;
        }
        IBinder binder = bundle.getBinder(KEY_BRIDGE_BINDER);
        if (binder != null && binder.pingBinder()) {
            bridgeBinder = binder;
        }
    }

    public static boolean isAvailable() {
        IBinder binder = bridgeBinder;
        if (binder == null) {
            return false;
        }
        if (!binder.pingBinder()) {
            bridgeBinder = null;
            return false;
        }
        return true;
    }

    @Nullable
    public static List<RecentTasks.Task> getRecentTasks(int maxNum, int userId) {
        IBinder binder = bridgeBinder;
        if (binder == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(maxNum);
            data.writeInt(userId);
            if (!binder.transact(TX_GET_RECENT_TASKS, data, reply, 0)) {
                return null;
            }
            reply.readException();
            int count = reply.readInt();
            if (count < 0) {
                return null;
            }
            List<RecentTasks.Task> tasks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int taskId = reply.readInt();
                int taskUserId = reply.readInt();
                String packageName = reply.readString();
                String activityName = reply.readString();
                String label = reply.readString();
                long lastActiveTime = reply.readLong();
                boolean hasIcon = reply.readInt() != 0;
                if (packageName != null && !packageName.isEmpty() && activityName != null && !activityName.isEmpty()) {
                    tasks.add(new RecentTasks.Task(taskId, taskUserId, packageName, activityName, label, lastActiveTime, hasIcon));
                }
            }
            return tasks;
        } catch (RemoteException e) {
            bridgeBinder = null;
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static boolean startTask(int taskId, @Nullable Bundle options) {
        IBinder binder = bridgeBinder;
        if (binder == null || taskId <= 0) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(taskId);
            if (options != null) {
                data.writeInt(1);
                options.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            if (!binder.transact(TX_START_TASK, data, reply, 0)) {
                return false;
            }
            reply.readException();
            return reply.readInt() != 0;
        } catch (RemoteException e) {
            bridgeBinder = null;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static boolean startTaskPopup(int taskId, @Nullable Rect bounds, @Nullable Bundle options) {
        IBinder binder = bridgeBinder;
        if (binder == null || taskId <= 0) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(taskId);
            if (bounds != null) {
                data.writeInt(1);
                bounds.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            if (options != null) {
                data.writeInt(1);
                options.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            if (!binder.transact(TX_START_TASK_POPUP, data, reply, 0)) {
                return false;
            }
            reply.readException();
            return reply.readInt() != 0;
        } catch (RemoteException e) {
            bridgeBinder = null;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static boolean startTaskSplit(int taskId, @Nullable Bundle options) {
        IBinder binder = bridgeBinder;
        if (binder == null || taskId <= 0) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(taskId);
            if (options != null) {
                data.writeInt(1);
                options.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            if (!binder.transact(TX_START_TASK_SPLIT, data, reply, 0)) {
                return false;
            }
            reply.readException();
            return reply.readInt() != 0;
        } catch (RemoteException e) {
            bridgeBinder = null;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static boolean removeTask(int taskId) {
        IBinder binder = bridgeBinder;
        if (binder == null || taskId <= 0) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(taskId);
            if (!binder.transact(TX_REMOVE_TASK, data, reply, 0)) {
                return false;
            }
            reply.readException();
            return reply.readInt() != 0;
        } catch (RemoteException e) {
            bridgeBinder = null;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Nullable
    public static Bitmap getTaskIcon(int taskId) {
        IBinder binder = bridgeBinder;
        if (binder == null || taskId <= 0) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(taskId);
            if (!binder.transact(TX_GET_TASK_ICON, data, reply, 0)) {
                return null;
            }
            reply.readException();
            if (reply.readInt() != 0) {
                return Bitmap.CREATOR.createFromParcel(reply);
            }
            return null;
        } catch (RemoteException e) {
            bridgeBinder = null;
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Nullable
    public static Bitmap getTaskSnapshot(int taskId) {
        IBinder binder = bridgeBinder;
        if (binder == null || taskId <= 0) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(taskId);
            if (!binder.transact(TX_GET_TASK_SNAPSHOT, data, reply, 0)) {
                return null;
            }
            reply.readException();
            byte[] jpeg = reply.createByteArray();
            if (jpeg == null || jpeg.length == 0) {
                return null;
            }
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (RemoteException e) {
            bridgeBinder = null;
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
