package jp.co.tis.tc;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.TreeMap;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.android.future.usb.UsbAccessory;
import com.android.future.usb.UsbManager;

public class ADKSpikeActivity extends Activity implements Runnable {
    private static final String ACTION_USB_PERMISSION = "jp.co.tis.tc.ADKSpike.action.USB_PERMISSION";
    private UsbManager mUsbManager;
    UsbAccessory mAccessory;
    FileInputStream mInputStream;
    FileOutputStream mOutputStream;
    ParcelFileDescriptor mFileDescriptor;

    private boolean mPermissionRequestPending;
    private PendingIntent mPermissionIntent;

    private TextView mLightValue;
    private TextView mTemperatureValue;
    private TextView mPressureValue;

    private static final byte LED_SERVO_COMMAND = 2;
    private static final int MESSAGE_LIGHT = 3;
    private static final int MESSAGE_PRESS = 4;

    protected class LightMsg {
        private int light;

        public LightMsg(int lightValue) {
            this.light = lightValue;
        }

        public int getLight() {
            return light;
        }

    }

    protected class PressureMsg {
        private long pressure;

        public PressureMsg(long pressureValue) {
            this.pressure = pressureValue;
        }

        public long getPressure() {
            return this.pressure;
        }
    }

    protected class TemperatureMsg {
        private short temperature;

        public TemperatureMsg(short temperatureValue) {
            this.temperature = temperatureValue;
        }

        public short getTemperature() {
            return this.temperature;
        }
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbAccessory accessory = UsbManager.getAccessory(intent);
                    if (intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        openAccessory(accessory);
                    } else {
                        Log.d("ADKSpike", "permission denied");
                    }
                    mPermissionRequestPending = false;
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                UsbAccessory accessory = UsbManager.getAccessory(intent);
                if (accessory != null && accessory.equals(mAccessory)) {
                    closeAccessory();
                }
            }
        }
    };

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mUsbManager = UsbManager.getInstance(this);
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(
                ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(mUsbReceiver, filter);
        if (getLastNonConfigurationInstance() != null) {
            mAccessory = (UsbAccessory) getLastNonConfigurationInstance();
            openAccessory(mAccessory);
        }

        Button rb = (Button) findViewById(R.id.button_right);
        Button lb = (Button) findViewById(R.id.button_left);
        OnClickListener ocl = new OnClickListener() {
            @Override
            public void onClick(View v) {
                byte commandTarget = (byte) (1 - 1 + 0x10);
                int viewId = v.getId();
                switch (viewId) {
                case R.id.button_right:
                    sendCommand(LED_SERVO_COMMAND, commandTarget,
                            (byte) (10 * 255));
                    break;
                case R.id.button_left:
                    sendCommand(LED_SERVO_COMMAND, commandTarget,
                            (byte) (-10 * 255));
                    break;
                }
            }
        };
        rb.setOnClickListener(ocl);
        lb.setOnClickListener(ocl);

        mLightValue = (TextView) findViewById(R.id.light_rawval_val);
        mTemperatureValue = (TextView) findViewById(R.id.temperature_val);
        mPressureValue = (TextView) findViewById(R.id.pressure_val);
    }

    @Override
    public void onResume() {
        super.onResume();
        Intent intet = getIntent();
        if (mInputStream != null && mOutputStream != null) {
            return;
        }

        UsbAccessory[] accessories = mUsbManager.getAccessoryList();
        UsbAccessory accessory = (accessories == null ? null : accessories[0]);
        if (accessory != null) {//    private void setTempAndPressVal(short temp, long press) {

            if (mUsbManager.hasPermission(accessory)) {
                openAccessory(accessory);
            } else {
                synchronized (mUsbReceiver) {
                    if (!mPermissionRequestPending) {
                        mUsbManager.requestPermission(accessory,
                                mPermissionIntent);
                        mPermissionRequestPending = true;
                    }
                }
            }
        } else {
            Toast.makeText(getApplicationContext(), "isNull", Toast.LENGTH_LONG);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        closeAccessory();
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mUsbReceiver);
        super.onDestroy();
    }

    private void openAccessory(UsbAccessory accessory) {
        mFileDescriptor = mUsbManager.openAccessory(accessory);
        if (mFileDescriptor != null) {
            mAccessory = accessory;
            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mInputStream = new FileInputStream(fd);
            mOutputStream = new FileOutputStream(fd);
            Thread thread = new Thread(null, this, "ADKSpike");
            thread.start();
            enableControls(true);
        } else {
            Log.d("ADKSpike", "accessory open fail");
        }
    }

    private void closeAccessory() {
        enableControls(false);
        try {
            if (mFileDescriptor != null) {
                mFileDescriptor.close();
            }
        } catch (IOException e) {
        } finally {
            mFileDescriptor = null;
            mAccessory = null;
        }
    }

    protected void enableControls(boolean enable) {
    }

    private int composeInt(byte hi, byte lo) {
        int val = (int) hi & 0xff;
        val *= 256;
        val += (int) lo & 0xff;
        return val;
    }

    private short composeShort(byte highByte, byte lowByte) {
        byte[] num = new byte[] { highByte, lowByte };
        short val = 0;
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(num));
        try {
            val = dis.readShort();
        } catch (IOException e) {
            Log.d("ADKSpike--composeShort", e.getMessage());
        }
        return val;
    }

    private int composeIntFromArduinoLong(byte highByte, byte middleByte1,
            byte middleByte2, byte lowByte) {
        byte[] num = new byte[] { highByte, middleByte1, middleByte2, lowByte };
        int val = 0;
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(num));
        try {
             val = dis.readInt();
        } catch (IOException e) {
            Log.d("ADKSpike--composeLong", e.getMessage());
        }
        return val;
    }

    public void run() {
        int ret = 0;
        byte[] buffer = new byte[16384];

        while (ret >= 0) {
            try {
                ret = mInputStream.read(buffer);
            } catch (IOException e) {
                break;
            }
            if (ret >= 2) {
                TemperatureMsg temp = new TemperatureMsg(composeShort(
                        buffer[0], buffer[1]));
                PressureMsg press = new PressureMsg(composeIntFromArduinoLong(
                        buffer[2], buffer[3], buffer[4], buffer[5]));
                Message m = Message.obtain(mHandler, MESSAGE_PRESS);
                TreeMap<String, Object> valueMap = new TreeMap<String, Object>();
                valueMap.put("temp", temp);
                valueMap.put("press", press);
                m.obj = valueMap;
                mHandler.sendMessage(m);
            }
        }
    }

    public void sendCommand(byte command, byte target, int value) {
        byte[] buffer = new byte[3];
        if (value > 255)
            value = 255;

        buffer[0] = command;
        buffer[1] = target;
        buffer[2] = (byte) value;
        if (mOutputStream != null && buffer[1] != -1) {
            try {
                mOutputStream.write(buffer);
            } catch (IOException e) {
                Log.d("ADKSpike", "write failed", e);
            }
        }
    }

    private void setLightValue(int rawLightValue) {
        mLightValue.setText(String.valueOf(rawLightValue));
    }

    private void setTempAndPressVal(short temp, long press) {
        mTemperatureValue.setText(String.valueOf((int)temp*0.1));
        mPressureValue.setText(String.valueOf(press));
    }

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_LIGHT:
                LightMsg lightMsg = (LightMsg) msg.obj;
                setLightValue(lightMsg.getLight());
                break;
            case MESSAGE_PRESS:
                TreeMap<String, Object> treeMap = (TreeMap) msg.obj;
                TemperatureMsg temp = (TemperatureMsg) treeMap.get("temp");
                PressureMsg press = (PressureMsg) treeMap.get("press");
                setTempAndPressVal(temp.getTemperature(), press.getPressure());
                break;
            }
        }
    };

}