package com.eveningoutpost.dexdrip.services;

import static com.eveningoutpost.dexdrip.cgm.medtrum.Medtrum.getDeviceInfoStringFromLegacy;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.activeandroid.query.Select;
import com.eveningoutpost.dexdrip.BluetoothScan;
import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.g5model.BluetoothServices;
import com.eveningoutpost.dexdrip.g5model.Extensions;
import com.eveningoutpost.dexdrip.g5model.Transmitter;
import com.eveningoutpost.dexdrip.models.ActiveBluetoothDevice;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.Blukon;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.utilitymodels.StatusItem;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import lecho.lib.hellocharts.util.ChartUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.val;

/**
 * POCTech collector
 * Created by Evgeniy on 10/11/2024.
 */


public class PoctechCollectionService extends G5BaseService {

    public final static String TAG = PoctechCollectionService.class.getSimpleName();

    public static boolean keep_running = false;
    private static volatile String transmitterID;
    private static volatile String transmitterMAC;

    private final Object mLock = new Object();
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean scanning = false;
    private Handler handler = new Handler();
    private BluetoothGatt mGatt;
    public int max133RetryCounter = 0;
    private static final boolean delayOn133Errors = true; // add some delays with 133 errors





    @Override
    public void onCreate() {
        super.onCreate();
        if (keep_running) {
            UserError.Log.d(TAG, "Вызов метода onCreate()");
            initializeBluetoothAdapter();
            startScanning(20000);
        }

    }


    private void initializeBluetoothAdapter() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }
    }

    public void startScanning(long scanPeriod) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            UserError.Log.d(TAG, "Bluetooth не включен");
            return;
        }
        scanning = true;
        bluetoothLeScanner.startScan(scanCallback);

        handler.postDelayed(this::stopScanning, scanPeriod);
    }

    public void stopScanning() {
        if (scanning) {
            bluetoothLeScanner.stopScan(scanCallback);
            scanning = false;
            UserError.Log.d(TAG, "Сканирование остановлено");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            UserError.Log.d(TAG, "Вызов скана");
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            if (device.getName() != null && device.getName().equals(getTransmitterID())){
                UserError.Log.d(TAG, "Найдено устройство: " + device.getName() + ", Адрес: " + device.getAddress());
                stopScanning();
                connectToDevice(device);
            } else {
                UserError.Log.d(TAG, "Не нашёл твой трансмиттер");
            }
        }

        private synchronized void connectToDevice(BluetoothDevice device) {
            if (JoH.ratelimit("G5connect-rate", 2)) {

                UserError.Log.d(TAG, "connectToDevice() start");
                if (mGatt != null) {
                    UserError.Log.i(TAG, "BGatt isnt null, Closing.");
                    try {
                        mGatt.close();
                    } catch (NullPointerException e) {
                        // concurrency related null pointer
                    }
                    mGatt = null;
                }
                UserError.Log.i(TAG, "Request Connect");
                final BluetoothDevice mDevice = device;
                if (enforceMainThread()) {
                    Handler iHandler = new Handler(Looper.getMainLooper());
                    iHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            connectGatt(mDevice);
                        }
                    });
                } else {
                    connectGatt(mDevice);
                }

            } else {
                UserError.Log.d(TAG, "connectToDevice baulking due to rate-limit");
            }
        }

        private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    UserError.Log.d(TAG, "Успешно подключено к устройству");
                    ActiveBluetoothDevice.connected();
                    // Здесь можно запустить дальнейшие действия, например, начать обмен данными
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    UserError.Log.d(TAG, "Отключено от устройства");
                    // Обработка отключения, если необходимо
                }
            }

        };

        private synchronized void connectGatt(BluetoothDevice mDevice) {
            UserError.Log.i(TAG, "mGatt Null, connecting...");
            UserError.Log.i(TAG, "connectToDevice On Main Thread? " + isOnMainThread());
            lastState="Found, Connecting";
            setDevice(mDevice.getName(), mDevice.getAddress());
            if (delayOn133Errors && max133RetryCounter > 1) {
                // should we only be looking at disconnected 133 here?
                UserError.Log.e(TAG, "Adding a delay before connecting to 133 count of: " + max133RetryCounter);
                waitFor(600);
                UserError.Log.e(TAG, "connectGatt() delay completed");
            }
            lastState = "Connect";
            mGatt = mDevice.connectGatt(getApplicationContext(), false, gattCallback);
        }


        protected void waitFor(final int millis) {
            synchronized (mLock) {
                try {
                    UserError.Log.e(TAG, "waiting " + millis + "ms");
                    mLock.wait(millis);
                } catch (final InterruptedException e) {
                    UserError.Log.e(TAG, "Sleeping interrupted", e);
                }
            }
        }

        private boolean isOnMainThread() {
            return Looper.getMainLooper().getThread() == Thread.currentThread();
        }

        private boolean enforceMainThread() {
            SharedPreferences sharedPreferences =
                    PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            return sharedPreferences.getBoolean("run_G5_ble_tasks_on_uithread", false);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) {
                BluetoothDevice device = result.getDevice();
                UserError.Log.d(TAG, "Найдено устройство: " + device.getName() + ", Адрес: " + device.getAddress());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            UserError.Log.d(TAG, "Ошибка сканирования: " + errorCode);
        }
    };

    public static synchronized void setDevice(String name, String address) {
        ActiveBluetoothDevice btDevice;
        synchronized (ActiveBluetoothDevice.table_lock) {
            btDevice = new Select().from(ActiveBluetoothDevice.class)
                    .orderBy("_ID desc")
                    .executeSingle();
        }
        Pref.setString("last_connected_device_address", address);
        Blukon.clearPin();
        if (btDevice == null) {
            ActiveBluetoothDevice newBtDevice = new ActiveBluetoothDevice();
            newBtDevice.name = name;
            newBtDevice.address = address;
            newBtDevice.save();
        } else {
            btDevice.name = name;
            btDevice.address = address;
            btDevice.save();
        }
    }

    //Чтение модели трансмиттера из строки ввода в меню
    private static void init_tx_id() {
        val TXID_PREF = "poct_txid";
        val txid = Pref.getString(TXID_PREF, "NULL");
        val txid_filtered = txid.trim();
        transmitterID = txid_filtered;
        if (!txid.equals(txid_filtered)) {
            Pref.setString(TXID_PREF, txid_filtered);
            UserError.Log.wtf(TAG, "Had to fix invalid txid: :" + txid + ": -> :" + txid_filtered + ":");
        }
    }

    public static String getTransmitterID() {
        if (transmitterID == null) {
            init_tx_id();
        }
        return transmitterID;
    }


    // Вкладка состояния трансмиттера Poctech
    public static List<StatusItem> megaStatus() {
        final List<StatusItem> l = new ArrayList<>();
        l.add(new StatusItem("Phone Service State", lastState));
        l.add(new StatusItem("Transmitter ID", ActiveBluetoothDevice.btDeviceName()));
        l.add(new StatusItem("Transmitter MAC", ActiveBluetoothDevice.btDeviceAddresses()));
        l.add(new StatusItem("Iw (nA)", "пока null"));
        l.add(new StatusItem("Iс (nA)", "пока null"));
        l.add(new StatusItem("Battery (V)", "пока null"));
        return  l;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}