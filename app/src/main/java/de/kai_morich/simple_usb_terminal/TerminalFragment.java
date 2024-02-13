package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.StringTokenizer;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }
    private enum BM8232_MODE { NONE, RLC_METER, U_F_DIODE, GENERATOR}
    private  BM8232_MODE bm8232_mode;
    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private ControlLines controlLines;

    // si * start
    private ExpandablePanel panel_RLC, panel_Ufd, panel_Gen;
    private TextView t_ufd_upp, t_ufd_ave, t_ufd_rms, t_ufd_f, t_ufd_t, t_ufd_d, t_ufd_n;
    private TextView t_gen_wave, t_gen_dummy, t_gen_freq, t_gen_plus, t_gen_minus;
    private TextView t_cl, t_z, t_r, t_eqs, t_qtg;
    private CheckBox cb_rlc_auto, cb_rlc_95, cb_rlc_1k, cb_rlc_10k, cb_rlc_95k, cb_rlc_160k;
    // si * finish

    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean controlLinesEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                }
            }
        };
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
        //bm8232_mode = BM8232_MODE.NONE;
        bm8232_mode = BM8232_MODE.RLC_METER;
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
        ContextCompat.registerReceiver(getActivity(), broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onStop() {
        getActivity().unregisterReceiver(broadcastReceiver);
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if(controlLinesEnabled && controlLines != null && connected == Connected.True)
            controlLines.start();
    }

    @Override
    public void onPause() {
        if(controlLines != null)
            controlLines.stop();
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setTextSize(15);
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        // * si
        t_ufd_f = view.findViewById(R.id.TextUfdF);
        t_ufd_t = view.findViewById(R.id.TextUfdT);
        t_ufd_d = view.findViewById(R.id.TextUfdD);
        t_ufd_n = view.findViewById(R.id.TextUfdN);
        t_ufd_ave = view.findViewById(R.id.TextUfdUave);
        t_ufd_rms = view.findViewById(R.id.TextUfdUrms);
        t_ufd_upp = view.findViewById(R.id.TextUfdUpp);

        t_gen_wave = view.findViewById(R.id.TextGenWave);
        t_gen_dummy = view.findViewById(R.id.TextGenDummy);
        t_gen_freq = view.findViewById(R.id.TextGenFrequency);
        t_gen_plus = view.findViewById(R.id.TextGenPlus);
        t_gen_minus = view.findViewById(R.id.TextGenMinus);

        t_cl = view.findViewById(R.id.TextCL);
        t_z = view.findViewById(R.id.TextZ);
        t_r = view.findViewById(R.id.TextR);
        t_eqs = view.findViewById(R.id.TextEqS);
        t_qtg = view.findViewById(R.id.TextQtg);

        cb_rlc_auto = view.findViewById(R.id.checkBoxAuto);
        cb_rlc_95 = view.findViewById(R.id.checkBox95Hz);
        cb_rlc_1k = view.findViewById(R.id.checkBox1kHz);
        cb_rlc_10k = view.findViewById(R.id.checkBox10kHz);
        cb_rlc_95k = view.findViewById(R.id.checkBox95kHz);
        cb_rlc_160k = view.findViewById(R.id.checkBox160kHz);
        // * si

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        controlLines = new ControlLines(view);

        // si ** start
        panel_RLC = view.findViewById(R.id.expandablePanelRLC);

        panel_RLC.setOnExpandListener(new ExpandablePanel.OnExpandListener() {
            public void onCollapse(View handle, View content) {
                Button btn_rlc = (Button)handle;
                btn_rlc.setText("RLC");

                panel_RLC.setCollapsedHeight(180);
            }
            public void onExpand(View handle, View content) {
                Button btn_rlc = (Button)handle;
                panel_RLC.setCollapsedHeight(60);
                btn_rlc.setText("<<");
                if (bm8232_mode != BM8232_MODE.RLC_METER) {
                    bm8232_mode = BM8232_MODE.RLC_METER;
                    send("rlc\r");
                }
            }
        });

        panel_Ufd = view.findViewById(R.id.expandablePanelUfd);

        panel_Ufd.setOnExpandListener(new ExpandablePanel.OnExpandListener() {
            public void onCollapse(View handle, View content) {
                Button btn = (Button)handle;
                btn.setText("Ufd");

                panel_Ufd.setCollapsedHeight(200);
            }
            public void onExpand(View handle, View content) {
                Button btn = (Button)handle;
                panel_Ufd.setCollapsedHeight(60);
                btn.setText("<<");
                if (bm8232_mode != BM8232_MODE.U_F_DIODE) {
                    bm8232_mode = BM8232_MODE.U_F_DIODE;
                    send("ufd\r");
                }
            }
        });

        panel_Gen = view.findViewById(R.id.expandablePanelGen);

        panel_Gen.setOnExpandListener(new ExpandablePanel.OnExpandListener() {
            public void onCollapse(View handle, View content) {
                Button btn_gen = (Button)handle;
                btn_gen.setText("Gen");

                panel_Gen.setCollapsedHeight(180);
            }
            public void onExpand(View handle, View content) {
                Button btn_gen = (Button)handle;
                panel_Gen.setCollapsedHeight(60);
                btn_gen.setText("<<");
                if (bm8232_mode != BM8232_MODE.GENERATOR) {
                    bm8232_mode = BM8232_MODE.GENERATOR;
                    send("gen\r");
                }
            }
        });

        cb_rlc_auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    if (bm8232_mode == BM8232_MODE.RLC_METER) send("auto\r");
                    cb_rlc_95.setChecked(false);
                    cb_rlc_1k.setChecked(false);
                    cb_rlc_10k.setChecked(false);
                    cb_rlc_95k.setChecked(false);
                    cb_rlc_160k.setChecked(false);
                }
                else {
                }
            }
        });

        cb_rlc_95.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    if (bm8232_mode == BM8232_MODE.RLC_METER) send("f95\r");
                    cb_rlc_auto.setChecked(false);
                    cb_rlc_1k.setChecked(false);
                    cb_rlc_10k.setChecked(false);
                    cb_rlc_95k.setChecked(false);
                    cb_rlc_160k.setChecked(false);
                }
                else {
                }
            }
        });

        cb_rlc_1k.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    if (bm8232_mode == BM8232_MODE.RLC_METER) send("f1k\r");
                    cb_rlc_auto.setChecked(false);
                    cb_rlc_95.setChecked(false);
                    cb_rlc_10k.setChecked(false);
                    cb_rlc_95k.setChecked(false);
                    cb_rlc_160k.setChecked(false);
                }
                else {
                }
            }
        });

        cb_rlc_10k.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    if (bm8232_mode == BM8232_MODE.RLC_METER) send("f10k\r");
                    cb_rlc_auto.setChecked(false);
                    cb_rlc_95.setChecked(false);
                    cb_rlc_1k.setChecked(false);
                    cb_rlc_95k.setChecked(false);
                    cb_rlc_160k.setChecked(false);
                }
                else {
                }
            }
        });

        cb_rlc_10k.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    if (bm8232_mode == BM8232_MODE.RLC_METER) send("f10k\r");
                    cb_rlc_auto.setChecked(false);
                    cb_rlc_95.setChecked(false);
                    cb_rlc_1k.setChecked(false);
                    cb_rlc_95k.setChecked(false);
                    cb_rlc_160k.setChecked(false);
                }
                else {
                }
            }
        });

        cb_rlc_95k.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    if (bm8232_mode == BM8232_MODE.RLC_METER) send("f95k\r");
                    cb_rlc_auto.setChecked(false);
                    cb_rlc_95.setChecked(false);
                    cb_rlc_1k.setChecked(false);
                    cb_rlc_10k.setChecked(false);
                    cb_rlc_160k.setChecked(false);
                }
                else {
                }
            }
        });

        cb_rlc_160k.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    if (bm8232_mode == BM8232_MODE.RLC_METER) send("f160k\r");
                    cb_rlc_auto.setChecked(false);
                    cb_rlc_95.setChecked(false);
                    cb_rlc_1k.setChecked(false);
                    cb_rlc_10k.setChecked(false);
                    cb_rlc_95k.setChecked(false);
                }
                else {
                }
            }
        });

        // si ** finish

        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        menu.findItem(R.id.controlLines).setChecked(controlLinesEnabled);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true);
            menu.findItem(R.id.backgroundNotification).setEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.controlLines) {
            controlLinesEnabled = !controlLinesEnabled;
            item.setChecked(controlLinesEnabled);
            if (controlLinesEnabled) {
                controlLines.start();
            } else {
                controlLines.stop();
            }
            return true;
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                } else {
                    showNotificationSettings();
                }
            }
            return true;
        } else if (id == R.id.sendBreak) {
            try {
                usbSerialPort.setBreak(true);
                Thread.sleep(100);
                status("send BREAK");
                usbSerialPort.setBreak(false);
            } catch (Exception e) {
                status("send BREAK failed: " + e.getMessage());
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        connect(null);
    }

    private void connect(Boolean permissionGranted) {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;
        if(device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(Constants.INTENT_ACTION_GRANT_USB);
            intent.setPackage(getActivity().getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status("Setting serial parameters failed: " + e.getMessage());
            }
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        controlLines.stop();
        service.disconnect();
        usbSerialPort = null;
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                //data = (str + newline).getBytes();
                data = str.getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (SerialTimeoutException e) {
            status("write timeout: " + e.getMessage());
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n');
            } else {
                String msg = new String(data);
                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if(spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                spn.append(TextUtil.toCaretString(msg, newline.length() != 0));

            }
        }
        receiveText.append(spn);

        // si * start
        //intermediate string for parsing
        //String msgi = spn.toString();
        String msgi;
        if (receiveText.length() > 90)
        msgi = receiveText.getText().subSequence(receiveText.length() - 90, receiveText.length()).toString();
        else msgi  = spn.toString();
        int l_ind = -1, f_ind = -1;

        if ( bm8232_mode == BM8232_MODE.U_F_DIODE ) {
            // Ufd message handler
            f_ind = msgi.indexOf("f=");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf("Hz");
            if ( f_ind >= 0 && l_ind >= 0 ) t_ufd_f.setText(msgi.substring(f_ind, f_ind + l_ind + 2));

            f_ind = msgi.indexOf("T=");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf("s"); else l_ind = -1;
            if ( f_ind >= 0 && l_ind >= 0 ) t_ufd_t.setText(msgi.substring(f_ind, f_ind + l_ind + 1));

            f_ind = msgi.indexOf("D=");
            if ( f_ind >= 0 ) t_ufd_d.setText(msgi.substring(f_ind, f_ind + 6));

            f_ind = msgi.indexOf("N=");
            if ( f_ind >= 0 ) t_ufd_n.setText(msgi.substring(f_ind, f_ind + 9));

            f_ind = msgi.indexOf("Uave"); // TODO can't find Uave=and_data, maybe try to find buffer size !!!???
            if ( f_ind >= 0 && f_ind + 11 < msgi.length() ) t_ufd_ave.setText(msgi.substring(f_ind, f_ind + 11) + "V");
//        f_ind = msgi.indexOf("V");
//        if ( f_ind >= 0 && f_ind < 10) t_ufd_ave.append(msgi.substring(0, f_ind));

            f_ind = msgi.indexOf("Urms");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf("V"); else l_ind = -1;
            if ( f_ind >= 0 && l_ind >= 0 ) t_ufd_rms.setText(msgi.substring(f_ind, f_ind + l_ind + 1));

            f_ind = msgi.indexOf("Up-p");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf("V"); else l_ind = -1;
            if ( f_ind >= 0 && l_ind >= 0 ) t_ufd_upp.setText(msgi.substring(f_ind, f_ind + l_ind + 1));

        }

        if ( bm8232_mode == BM8232_MODE.GENERATOR ) {
            // Gen message handler
            f_ind = msgi.indexOf("Frequency=");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf("Hz");
            if ( f_ind >= 0 && l_ind >= 0 ) t_gen_freq.setText(msgi.substring(f_ind, f_ind + l_ind + 2));
        }

        if ( bm8232_mode == BM8232_MODE.RLC_METER ) {
            // RLC message handler
            f_ind = msgi.indexOf("C=");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf(","); else l_ind = -1;
            if ( f_ind >= 0 && l_ind >= 0 ) t_cl.setText(msgi.substring(f_ind, f_ind + l_ind));

            f_ind = msgi.indexOf("L=");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf(","); else l_ind = -1;
            if ( f_ind >= 0 && l_ind >= 0 ) t_cl.setText(msgi.substring(f_ind, f_ind + l_ind));

            f_ind = msgi.indexOf("R=");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf(","); else l_ind = -1;
            if ( f_ind >= 0 && l_ind >= 0 ) t_r.setText(replaceOhm (msgi.substring(f_ind, f_ind + l_ind)) );
            //if ( l_ind >= 0 ) f_ind += l_ind;
            //if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf(","); //else l_ind = -1;
            //if ( f_ind >= 0 && l_ind >= 0 ) t_r.setText(msgi.substring(f_ind+2, f_ind + 9) );

            f_ind = msgi.indexOf("Z=");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf(","); else l_ind = -1;
            if ( f_ind >= 0 && l_ind >= 0 ) t_z.setText(replaceOhm (msgi.substring(f_ind, f_ind + l_ind)) );

            f_ind = msgi.indexOf("Q=");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf(","); else l_ind = -1;
            if ( f_ind >= 0 && l_ind >= 0 ) t_qtg.setText(msgi.substring(f_ind, f_ind + l_ind));

            f_ind = msgi.indexOf("tg=");
            if ( f_ind >= 0) l_ind = msgi.substring(f_ind).indexOf(","); else l_ind = -1;
            if ( f_ind >= 0 && l_ind >= 0 ) t_qtg.setText(msgi.substring(f_ind, f_ind + l_ind));

            f_ind = msgi.indexOf("Ser");
            if ( f_ind >= 0 ) t_eqs.setText("Ser");

            f_ind = msgi.indexOf("Par");
            if ( f_ind >= 0 ) t_eqs.setText("Par");

        }

    }

    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(Arrays.equals(permissions, new String[]{Manifest.permission.POST_NOTIFICATIONS}) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled())
            showNotificationSettings();
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
        if(controlLinesEnabled)
            controlLines.start();
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Handler mainLooper;
        private final Runnable runnable;
        private final LinearLayout frame;
        private final ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        ControlLines(View view) {
            mainLooper = new Handler(Looper.getMainLooper());
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks

            frame = view.findViewById(R.id.controlLines);
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status("set" + ctrl + " failed: " + e.getMessage());
            }
        }

        private void run() {
            if (connected != Connected.True)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getControlLines();
                rtsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RTS));
                ctsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CTS));
                dtrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DTR));
                dsrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DSR));
                cdBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CD));
                riBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RI));
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        void start() {
            frame.setVisibility(View.VISIBLE);
            if (connected != Connected.True)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getSupportedControlLines();
                if (!controlLines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CD))   cdBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.RI))   riBtn.setVisibility(View.INVISIBLE);
                run();
            } catch (IOException e) {
                Toast.makeText(getActivity(), "getSupportedControlLines() failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        void stop() {
            frame.setVisibility(View.GONE);
            mainLooper.removeCallbacks(runnable);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }
    }
    @NonNull
    static String replaceOhm(String str) {
        String tmp;
        // delete last odd symbol and add "Ω"
        tmp = str.substring(0, str.length() - 1) + "Ω";
        return tmp;
    }

}
