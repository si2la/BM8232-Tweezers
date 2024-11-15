package ru.learn2prog.bm8232_tweezers;

import static android.content.Context.MODE_PRIVATE;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

public class DevicesFragment extends ListFragment {

    static class ListItem {
        UsbDevice device;
        int port;
        UsbSerialDriver driver;

        ListItem(UsbDevice device, int port, UsbSerialDriver driver) {
            this.device = device;
            this.port = port;
            this.driver = driver;
        }
    }

    private final ArrayList<ListItem> listItems = new ArrayList<>();
    private ArrayAdapter<ListItem> listAdapter;
    private int baudRate = 115200;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        listAdapter = new ArrayAdapter<ListItem>(getActivity(), 0, listItems) {
            @NonNull
            @Override
            public View getView(int position, View view, @NonNull ViewGroup parent) {
                ListItem item = listItems.get(position);
                if (view == null)
                    view = getActivity().getLayoutInflater().inflate(R.layout.device_list_item, parent, false);
                TextView text1 = view.findViewById(R.id.text1);
                TextView text2 = view.findViewById(R.id.text2);
                if(item.driver == null)
                    text1.setText("<no driver>");
                else if(item.driver.getPorts().size() == 1)
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver",""));
                else
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver","")+", Port "+item.port);
                text2.setText(String.format(Locale.US, "Vendor %04X, Product %04X", item.device.getVendorId(), item.device.getProductId()));
                return view;
            }
        };
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setListAdapter(null);
        View header = getActivity().getLayoutInflater().inflate(R.layout.device_list_header, null, false);
        getListView().addHeaderView(header, null, false);
        setEmptyText("<no USB devices found>");
        ((TextView) getListView().getEmptyView()).setTextSize(18);
        setListAdapter(listAdapter);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_devices, menu);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if(id == R.id.refresh) {
            refresh();
            return true;
        }
//        else if (id ==R.id.baud_rate) {
//            final String[] baudRates = getResources().getStringArray(R.array.baud_rates);
//            int pos = java.util.Arrays.asList(baudRates).indexOf(String.valueOf(baudRate));
//            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
//            builder.setTitle("Baud rate");
//            builder.setSingleChoiceItems(baudRates, pos, new DialogInterface.OnClickListener() {
//                public void onClick(DialogInterface dialog, int item) {
//                    baudRate = Integer.parseInt(baudRates[item]);
//                    dialog.dismiss();
//                }
//            });
//            builder.create().show();
//            return true;
        else if (id == R.id.inc_font_size) {
            final float start_value = 0.6f; // start font size
            final float step = 0.1f; // step font size
            int size_coef; // font coefficient
            // font_size = (0,6 + 0,1 * size_coef), default = 0,6 + 0,1 * 4 = 1

            Resources res = getResources();
            // get font scale
            SharedPreferences settings = requireActivity().getSharedPreferences("BM8232AppSett", MODE_PRIVATE);
            try {
                size_coef = settings.getInt("size_coef", 4);
            }
            catch (Exception e) {
                size_coef = 4;
            }

            size_coef++;
            if (size_coef > 8) size_coef = 8;

            float new_font_value = start_value + size_coef * step;
            Configuration configuration = new Configuration(res.getConfiguration());
            configuration.fontScale = new_font_value;
            res.updateConfiguration(configuration, res.getDisplayMetrics());

            // save configuration
            SharedPreferences.Editor value_add = settings.edit();
            value_add.putInt("size_coef", size_coef);
            value_add.apply();

            return true;
        } else if (id == R.id.small_font_size) {
            final float start_value = 0.6f;
            final float step = 0.1f;
            int size_coef;

            Resources res = getResources();
            // get font scale
            SharedPreferences settings = requireActivity().getSharedPreferences("BM8232AppSett", MODE_PRIVATE);
            try {
                size_coef = settings.getInt("size_coef", 4);
            }
            catch (Exception e) {
                size_coef = 4;
            }

            size_coef--;
            if (size_coef < 0) size_coef = 0;

            float new_font_value = start_value + size_coef * step;
            Configuration configuration = new Configuration(res.getConfiguration());
            configuration.fontScale = new_font_value;
            res.updateConfiguration(configuration, res.getDisplayMetrics());

            // save configuration
            SharedPreferences.Editor value_add = settings.edit();
            value_add.putInt("size_coef", size_coef);
            value_add.apply();

            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    void refresh() {
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        UsbSerialProber usbDefaultProber = UsbSerialProber.getDefaultProber();
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();
        listItems.clear();
        for(UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbDefaultProber.probeDevice(device);
            if(driver == null) {
                driver = usbCustomProber.probeDevice(device);
            }
            if(driver != null) {
                for(int port = 0; port < driver.getPorts().size(); port++)
                    listItems.add(new ListItem(device, port, driver));
            } else {
                listItems.add(new ListItem(device, 0, null));
            }
        }
        listAdapter.notifyDataSetChanged();
    }

    @Override
    public void onListItemClick(@NonNull ListView l, @NonNull View v, int position, long id) {
        ListItem item = listItems.get(position-1);
        if(item.driver == null) {
            Toast.makeText(getActivity(), "no driver", Toast.LENGTH_SHORT).show();
        } else {
            Bundle args = new Bundle();
            args.putInt("device", item.device.getDeviceId());
            args.putInt("port", item.port);
            args.putInt("baud", baudRate);
            Fragment fragment = new TerminalFragment();
            fragment.setArguments(args);
            getParentFragmentManager().beginTransaction().replace(R.id.fragment, fragment, "terminal").addToBackStack(null).commit();
        }
    }

}
