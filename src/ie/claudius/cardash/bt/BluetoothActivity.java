package ie.claudius.cardash.bt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ie.claudius.cardash.Fonts;
import ie.claudius.cardash.M3;
import ie.claudius.cardash.Motion;
import ie.claudius.cardash.Prefs;
import ie.claudius.cardash.R;
import ie.claudius.cardash.Receivers;
import ie.claudius.cardash.Shapes;

/**
 * Search, pair and choose the OBD dongle without leaving CarDash: on many head units the only
 * Bluetooth settings are a vendor phone app that won't list a serial dongle.
 *
 * A search is classic discovery first, then a BLE scan. One after the other on purpose: a
 * low-latency BLE scan keeps the radio busy full-time and starves the classic inquiry, and the
 * classic one is what finds ELM327 dongles.
 *
 * PIN entry is left to the system's pairing dialog; this screen only says which PINs to try.
 */
public class BluetoothActivity extends Activity {

    private static final String TAG = "CarDash/BT";
    private static final int REQ_PERMISSIONS = 51, REQ_ENABLE = 52;
    private static final long CLASSIC_TIMEOUT_MS = 15000L;
    private static final long LE_SCAN_MS = 10000L;
    private static final long RENDER_MS = 700L;
    private static final long UNPAIR_TIMEOUT_MS = 5000L;
    /** Hidden extra on ACTION_BOND_STATE_CHANGED. */
    private static final String EXTRA_REASON = "android.bluetooth.device.extra.REASON";

    private enum Scan { IDLE, CLASSIC, LE }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final BtDevices list = new BtDevices();
    private BluetoothAdapter bt;
    private float density;
    private M3 m3;
    private LinearLayout column;
    private TextView scanButton;

    private Scan scan = Scan.IDLE;
    /** Classic discovery is really running, so its "finished" broadcast is ours to act on. */
    private boolean discoveryStarted;
    private BluetoothLeScanner leScanner;
    private boolean scanAfterPermission;
    private boolean askedPermissions;
    private boolean searched;
    /** Last thing that happened (pairing, unpairing, a failure), shown above the lists. */
    private String message;
    /** Device this screen started pairing, and which of PINS it is on (ELM327 clones use 1234 or 0000). */
    private String pairingAddress;
    private int pinIndex;
    private static final String[] PINS = { "1234", "0000", "6789", "1111" };
    private String unpairing;
    private boolean touching, renderQueued;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        Motion.setCalm(Prefs.calmMotion(this));
        m3 = M3.fromSeed(Prefs.themeSeed(this));
        bt = BluetoothAdapter.getDefaultAdapter();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(m3.surface());
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        page.setPadding(pad, pad, pad, pad);
        scroll.addView(page);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), dp(16));
        TextView title = text(getString(R.string.bt_title), 26, m3.onSurface(), true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scanButton = pill(getString(R.string.bt_scan), true, new Runnable() {
            @Override
            public void run() {
                if (scan == Scan.IDLE) {
                    startSearch();
                } else {
                    stopSearch();
                    render();
                }
            }
        });
        header.addView(scanButton);
        header.addView(pill(getString(R.string.done), false, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }));
        page.addView(header);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        page.addView(column);
        setContentView(scroll);

        // Registered for the activity's whole life: the pairing dialog may stop us, and the
        // bond result must still arrive. The Bluetooth app is not the system UID, hence exported;
        // every one of these actions is a protected broadcast.
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        // Units whose vendor Bluetooth app shows no PIN dialog: answer legacy PIN requests for the
        // device this screen is pairing, before the (missing) system dialog would.
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        Receivers.register(this, receiver, f, true);

        askPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopSearch();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Receivers.unregister(this, receiver);
        main.removeCallbacksAndMessages(null);
    }

    /** Rows must not be rebuilt under a finger: hold renders until it lifts. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) touching = true;
        boolean handled = super.dispatchTouchEvent(e);
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) touching = false;
        return handled;
    }

    // ---- permissions -----------------------------------------------------------

    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 23) return missing;
        List<String> wanted = new ArrayList<>();
        // Discovery and BLE scans return nothing without location from API 23.
        wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            wanted.add("android.permission.BLUETOOTH_SCAN");
            wanted.add("android.permission.BLUETOOTH_CONNECT");
        }
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        return missing;
    }

    private void askPermissions() {
        List<String> missing = missingPermissions();
        if (missing.isEmpty() || askedPermissions) return;
        askedPermissions = true;
        requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQ_PERMISSIONS) return;
        askedPermissions = false;
        boolean search = scanAfterPermission;
        scanAfterPermission = false;
        if (search && missingPermissions().isEmpty()) startSearch();
        else render();
    }

    private boolean locationOff() {
        if (Build.VERSION.SDK_INT < 23) return false;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                return lm != null && !lm.isLocationEnabled();
            }
            return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF) == Settings.Secure.LOCATION_MODE_OFF;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- searching ---------------------------------------------------------------

    private boolean enabled() {
        try {
            return bt != null && bt.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    private void startSearch() {
        if (!missingPermissions().isEmpty()) {
            scanAfterPermission = true;
            askedPermissions = false;
            askPermissions();
            return;
        }
        if (!enabled()) {
            render();
            return;
        }
        stopSearch();
        message = null;
        searched = true;
        list.clearScan();
        scan = Scan.CLASSIC;
        boolean ok = false;
        try {
            // Someone else's discovery already running serves just as well.
            discoveryStarted = bt.isDiscovering();
            ok = discoveryStarted || bt.startDiscovery();
        } catch (RuntimeException e) {
            Log.w(TAG, "startDiscovery: " + e);
        }
        if (ok) {
            main.postDelayed(classicTimeout, CLASSIC_TIMEOUT_MS);
        } else {
            message = getString(R.string.bt_classic_failed);
            startLe();
        }
        render();
    }

    /** Some vendor stacks never send "discovery finished". */
    private final Runnable classicTimeout = new Runnable() {
        @Override
        public void run() {
            if (scan == Scan.CLASSIC) startLe();
        }
    };

    private final Runnable leTimeout = new Runnable() {
        @Override
        public void run() {
            stopSearch();
            render();
        }
    };

    private void startLe() {
        main.removeCallbacks(classicTimeout);
        cancelDiscovery();
        scan = Scan.IDLE;
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            try {
                leScanner = bt.getBluetoothLeScanner();
                if (leScanner != null) {
                    ScanSettings settings = new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                    leScanner.startScan(null, settings, leCallback);
                    scan = Scan.LE;
                    main.postDelayed(leTimeout, LE_SCAN_MS);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "BLE scan: " + e);
                leScanner = null;
            }
        }
        render();
    }

    private void cancelDiscovery() {
        try {
            if (bt != null && bt.isDiscovering()) bt.cancelDiscovery();
        } catch (RuntimeException e) {
            Log.w(TAG, "cancelDiscovery: " + e);
        }
    }

    private void stopSearch() {
        main.removeCallbacks(classicTimeout);
        main.removeCallbacks(leTimeout);
        if (scan == Scan.CLASSIC) cancelDiscovery();
        if (leScanner != null) {
            try {
                leScanner.stopScan(leCallback);
            } catch (RuntimeException e) {
                // Bluetooth went off underneath the scan.
                Log.w(TAG, "stopScan: " + e);
            }
            leScanner = null;
        }
        scan = Scan.IDLE;
    }

    private final ScanCallback leCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult r) {
            ScanRecord record = r.getScanRecord();
            add(r.getDevice(), record == null ? null : record.getDeviceName(), BtDevices.LE, r.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            message = getString(R.string.bt_le_failed, errorCode);
            stopSearch();
            render();
        }
    };

    private void add(BluetoothDevice dev, String name, int heardAs, int rssi) {
        if (dev == null) return;
        int type = BtDevices.UNKNOWN;
        try {
            if (name == null) name = dev.getName();
            type = dev.getType();
        } catch (SecurityException e) {
            Log.w(TAG, "no bluetooth permission");
        }
        // The system's own idea of the type wins; otherwise, how we heard it.
        list.found(dev.getAddress(), name, type == BtDevices.UNKNOWN ? heardAs : type, rssi);
        renderSoon();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                add(dev, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), BtDevices.CLASSIC,
                        rssi == Short.MIN_VALUE ? BtDevices.NO_RSSI : rssi);
            } else if (BluetoothDevice.ACTION_NAME_CHANGED.equals(action)) {
                String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (dev != null && list.get(dev.getAddress()) != null) {
                    add(dev, name, BtDevices.UNKNOWN, BtDevices.NO_RSSI);
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (dev != null) {
                    bondChanged(dev,
                            intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
                            intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                                    BluetoothDevice.BOND_NONE),
                            intent.getIntExtra(EXTRA_REASON, 0));
                }
            } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                if (answerPin(dev, intent) && isOrderedBroadcast()) abortBroadcast();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                if (scan == Scan.CLASSIC) discoveryStarted = true;
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (scan == Scan.CLASSIC && discoveryStarted) startLe();
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                if (!enabled()) stopSearch();
                render();
            }
        }
    };

    // ---- pairing ------------------------------------------------------------------

    private void pair(String address) {
        // Pairing during a search fails or times out on many stacks.
        stopSearch();
        BtDevices.Device d = list.get(address);
        try {
            BluetoothDevice dev = bt.getRemoteDevice(address);
            if (dev.getBondState() == BluetoothDevice.BOND_BONDING) {
                message = getString(R.string.bt_pairing, label(d, address));
            } else if (startBond(dev, true)) {
                message = getString(R.string.bt_pairing, label(d, address));
            } else {
                message = getString(R.string.bt_pair_not_started, label(d, address));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "createBond: " + e);
            message = getString(R.string.bt_pair_not_started, label(d, address));
        }
        render();
    }

    private void bondChanged(BluetoothDevice dev, int state, int before, int reason) {
        String address = dev.getAddress();
        String name = null;
        try {
            name = dev.getName();
        } catch (SecurityException e) {
            Log.w(TAG, "no bluetooth permission");
        }
        String label = name != null ? name : label(list.get(address), address);
        if (state == BluetoothDevice.BOND_BONDING) {
            message = getString(R.string.bt_pairing, label);
        } else if (state == BluetoothDevice.BOND_BONDED) {
            message = getString(R.string.bt_paired_now, label);
        } else if (address.equalsIgnoreCase(unpairing)) {
            unpairing = null;
            main.removeCallbacks(unpairTimeout);
            // Unpaired from here on purpose: stop pointing OBD at it.
            if (address.equalsIgnoreCase(Prefs.obdAddress(this))) Prefs.clearObdDongle(this);
            message = getString(R.string.bt_unpaired, label);
        } else if (before == BluetoothDevice.BOND_BONDING && address.equalsIgnoreCase(pairingAddress)
                && pinIndex + 1 < PINS.length && retryNextPin(dev)) {
            message = getString(R.string.bt_pairing, label) + "  (PIN " + PINS[pinIndex] + ")";
        } else if (before == BluetoothDevice.BOND_BONDING) {
            if (address.equalsIgnoreCase(pairingAddress)) pairingAddress = null;
            String why = BtDevices.bondFailure(reason);
            message = why == null ? getString(R.string.bt_pair_failed, label)
                    : getString(R.string.bt_pair_failed_why, label, why);
        }
        render();
    }

    private boolean startBond(BluetoothDevice dev, boolean fresh) {
        if (fresh) pinIndex = 0;
        pairingAddress = dev.getAddress();
        return dev.createBond();
    }

    private boolean retryNextPin(BluetoothDevice dev) {
        pinIndex++;
        try {
            return startBond(dev, false);
        } catch (RuntimeException e) {
            Log.w(TAG, "createBond retry: " + e);
            return false;
        }
    }

    /** Legacy PIN pairing (classic ELM327): set the PIN ourselves; numeric comparison stays with the system. */
    private boolean answerPin(BluetoothDevice dev, Intent intent) {
        if (dev == null || pairingAddress == null || !pairingAddress.equalsIgnoreCase(dev.getAddress())) return false;
        int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
        if (variant != BluetoothDevice.PAIRING_VARIANT_PIN && variant != 5 /* PAIRING_VARIANT_PIN_16_DIGITS */) return false;
        try {
            if (dev.setPin(PINS[pinIndex].getBytes("US-ASCII"))) {
                Log.i(TAG, "PIN " + PINS[pinIndex] + " sent to " + dev.getAddress());
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "setPin: " + e);
        }
        return false;
    }

    private void confirmUnpair(final String address, final String label) {
        int theme = Build.VERSION.SDK_INT >= 22 ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Dialog;
        new AlertDialog.Builder(this, theme)
                .setTitle(getString(R.string.bt_unpair_ask, label))
                .setPositiveButton(R.string.bt_unpair, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        unpair(address);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** removeBond() is hidden API: reflect, and say so plainly if the unit refuses. */
    private void unpair(String address) {
        stopSearch();
        boolean accepted = false;
        try {
            BluetoothDevice dev = bt.getRemoteDevice(address);
            Object result = BluetoothDevice.class.getMethod("removeBond").invoke(dev);
            accepted = Boolean.TRUE.equals(result);
        } catch (Exception e) {
            Log.w(TAG, "removeBond: " + e);
        }
        if (accepted) {
            unpairing = address;
            message = getString(R.string.bt_unpairing);
            main.postDelayed(unpairTimeout, UNPAIR_TIMEOUT_MS);
        } else {
            message = getString(R.string.bt_unpair_failed);
        }
        render();
    }

    private final Runnable unpairTimeout = new Runnable() {
        @Override
        public void run() {
            unpairing = null;
            message = getString(R.string.bt_unpair_failed);
            render();
        }
    };

    // ---- screen -------------------------------------------------------------------

    private final Runnable delayedRender = new Runnable() {
        @Override
        public void run() {
            renderQueued = false;
            if (touching) renderSoon();
            else render();
        }
    };

    /** Scan results arrive many times a second: coalesce them. */
    private void renderSoon() {
        if (renderQueued) return;
        renderQueued = true;
        main.postDelayed(delayedRender, RENDER_MS);
    }

    private void readBonded() {
        list.beginBonded();
        try {
            if (bt != null && bt.getBondedDevices() != null) {
                for (BluetoothDevice d : bt.getBondedDevices()) {
                    list.bonded(d.getAddress(), d.getName(), d.getType());
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "no bluetooth permission");
        }
        list.endBonded();
    }

    private void render() {
        if (isFinishing()) return;
        scanButton.setText(scan == Scan.IDLE ? R.string.bt_scan : R.string.bt_stop);
        column.removeAllViews();
        if (bt == null) {
            notice(getString(R.string.bt_none), null, null);
            return;
        }
        if (!enabled()) {
            notice(getString(R.string.bt_off), getString(R.string.bt_turn_on), new Runnable() {
                @Override
                public void run() {
                    turnOn();
                }
            });
            return;
        }
        readBonded();

        if (!missingPermissions().isEmpty()) {
            notice(getString(R.string.bt_no_permission), getString(R.string.bt_grant), new Runnable() {
                @Override
                public void run() {
                    askedPermissions = false;
                    askPermissions();
                }
            });
        }
        if (locationOff()) {
            notice(getString(R.string.bt_location_off), getString(R.string.bt_location_open), new Runnable() {
                @Override
                public void run() {
                    open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                }
            });
        }
        if (message != null) notice(message, null, null);

        String vehicle = Prefs.vehicleMode(this);
        if ("off".equals(vehicle) || "can".equals(vehicle)) {
            notice(getString(R.string.bt_obd_disabled), null, null);
        }

        String chosen = Prefs.obdAddress(this);
        BtDevices.Device chosenDevice = list.get(chosen);
        if (chosen != null && (chosenDevice == null || !chosenDevice.bonded)) {
            notice(getString(R.string.bt_chosen_unpaired, Prefs.obdName(this), chosen),
                    getString(R.string.bt_forget), new Runnable() {
                        @Override
                        public void run() {
                            Prefs.clearObdDongle(BluetoothActivity.this);
                            render();
                        }
                    });
        }

        LinearLayout card = section(R.string.bt_paired);
        List<BtDevices.Device> paired = list.paired(chosen);
        if (paired.isEmpty()) card.addView(empty(R.string.bt_paired_none));
        for (BtDevices.Device d : paired) card.addView(row(d, chosen));

        card = section(R.string.bt_nearby);
        if (scan != Scan.IDLE) {
            card.addView(empty(scan == Scan.CLASSIC ? R.string.bt_searching_classic
                    : R.string.bt_searching_le));
        }
        List<BtDevices.Device> nearby = list.nearby();
        if (nearby.isEmpty() && scan == Scan.IDLE) {
            card.addView(empty(searched ? R.string.bt_nearby_none : R.string.bt_nearby_idle));
        }
        for (BtDevices.Device d : nearby) card.addView(row(d, chosen));
    }

    private void turnOn() {
        if (open(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE)) return;
        try {
            bt.enable();
        } catch (SecurityException e) {
            Log.w(TAG, "enable: " + e);
        }
    }

    private boolean open(Intent i) {
        return open(i, -1);
    }

    /** Vendor ROMs drop system screens; a missing one must not crash us. */
    private boolean open(Intent i, int request) {
        try {
            startActivityForResult(i, request);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot open " + i.getAction() + ": " + e);
            return false;
        }
    }

    private String label(BtDevices.Device d, String address) {
        return d != null && d.name != null ? d.name : address;
    }

    private View row(final BtDevices.Device d, String chosen) {
        final boolean isChosen = d.address.equalsIgnoreCase(chosen);
        final String label = d.name != null ? d.name : getString(R.string.bt_unnamed);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(80));
        row.setPadding(dp(16), dp(10), dp(8), dp(10));
        int fill = isChosen ? m3.primaryContainer() : m3.surfaceContainerHigh();
        int on = isChosen ? m3.onPrimaryContainer() : m3.onSurface();
        row.setBackground(Shapes.round(density, fill, 20));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(label, 20, on, true));

        StringBuilder sub = new StringBuilder(d.address);
        sub.append("  ·  ").append(getString(d.type == BtDevices.DUAL ? R.string.bt_type_dual
                : d.type == BtDevices.LE ? R.string.bt_type_le
                : d.type == BtDevices.CLASSIC ? R.string.bt_type_classic : R.string.bt_type_unknown));
        if (d.rssi != BtDevices.NO_RSSI) sub.append("  ·  ").append(d.rssi).append(" dBm");
        words.addView(text(sub.toString(), 14, M3.withAlpha(on, 0xC0), false));

        String hint = null;
        if (isChosen) hint = getString(R.string.bt_chosen);
        else if (d.looksLikeObd()) hint = getString(R.string.bt_looks_obd);
        if (d.type == BtDevices.LE && (isChosen || d.looksLikeObd())) {
            hint = hint + "  ·  " + getString(R.string.bt_le_only);
        }
        if (hint != null) words.addView(text(hint, 14, isChosen ? on : m3.primary(), true));

        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wlp.rightMargin = dp(12);
        row.addView(words, wlp);

        if (d.bonded) {
            row.addView(pill(getString(isChosen ? R.string.bt_stop_using : R.string.bt_use_obd),
                    !isChosen && d.looksLikeObd(), new Runnable() {
                        @Override
                        public void run() {
                            if (isChosen) {
                                Prefs.clearObdDongle(BluetoothActivity.this);
                                message = getString(R.string.bt_cleared);
                            } else {
                                Prefs.setObdDongle(BluetoothActivity.this, d.address, label);
                                message = getString(R.string.bt_using, label);
                            }
                            render();
                        }
                    }));
            row.addView(pill(getString(R.string.bt_unpair), false, new Runnable() {
                @Override
                public void run() {
                    confirmUnpair(d.address, label);
                }
            }));
        } else {
            Runnable pair = new Runnable() {
                @Override
                public void run() {
                    pair(d.address);
                }
            };
            row.addView(pill(getString(R.string.bt_pair), d.looksLikeObd(), pair));
            clickable(row, pair);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    /** A message card, optionally with one button. */
    private void notice(String body, String action, Runnable onAction) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Shapes.round(density, m3.surfaceContainer(), 28));
        card.setPadding(dp(24), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wlp.rightMargin = dp(12);
        card.addView(text(body, 17, m3.onSurface(), false), wlp);
        if (action != null) card.addView(pill(action, true, onAction));
        column.addView(card, cardParams());
    }

    private LinearLayout section(int titleRes) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Shapes.round(density, m3.surfaceContainer(), 28));
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        TextView title = text(getString(titleRes), 18, m3.onSurface(), true);
        title.setPadding(dp(8), 0, dp(8), 0);
        card.addView(title);
        column.addView(card, cardParams());
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        return lp;
    }

    private TextView empty(int res) {
        TextView t = text(getString(res), 15, m3.onSurfaceVariant(), false);
        t.setPadding(dp(8), dp(10), dp(8), 0);
        return t;
    }

    private TextView text(String s, int sp, int colour, boolean display) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(display ? Fonts.display(this) : Fonts.body(this));
        t.setTextColor(colour);
        t.setTextSize(sp);
        return t;
    }

    private TextView pill(String label, boolean primary, Runnable action) {
        TextView t = text(label, 16, primary ? m3.onPrimaryContainer() : m3.onSurfaceVariant(), true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(56));
        t.setMinWidth(dp(96));
        t.setPadding(dp(20), dp(10), dp(20), dp(10));
        int fill = primary ? m3.primaryContainer() : m3.surfaceContainer();
        t.setBackground(Shapes.pill(density, fill, M3.withAlpha(t.getCurrentTextColor(), 0x40)));
        clickable(t, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        t.setLayoutParams(lp);
        return t;
    }

    private void clickable(View v, final Runnable action) {
        v.setClickable(true);
        Shapes.springy(v);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
