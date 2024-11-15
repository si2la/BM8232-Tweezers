package ru.learn2prog.bm8232_tweezers;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();

        final float start_value = 0.6f; // start font size
        final float step = 0.1f; // step font size
        int size_coef; // font coefficient
        // font_size = (0,6 + 0,1 * size_coef), default = 0,6 + 0,1 * 4 = 1

        Resources res = getResources();
        // get font scale
        SharedPreferences settings = getSharedPreferences("BM8232AppSett", MODE_PRIVATE);
        try {
            size_coef = settings.getInt("size_coef", 4);
        }
        catch (Exception e) {
            size_coef = 4;
        }

        float font_value = start_value + size_coef * step;
        Configuration configuration = new Configuration(res.getConfiguration());
        configuration.fontScale = font_value;
        res.updateConfiguration(configuration, res.getDisplayMetrics());

    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment)getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status("USB device detected");
        }
        super.onNewIntent(intent);
    }

}
