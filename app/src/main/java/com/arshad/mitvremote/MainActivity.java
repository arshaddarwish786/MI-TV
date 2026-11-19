package com.arshad.mitvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int GREY = 0xFF888888, YELLOW = 0xFFFFB300, GREEN = 0xFF43A047, RED = 0xFFE53935;
    private static final String SERVICE_TYPE = "_androidtvremote2._tcp";

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private TextView status;
    private View dot;

    private volatile String host, tvName;
    private volatile ClientIdentity id;
    private volatile AtvRemote remote;
    private volatile AtvPairing pairing;
    private volatile boolean pairingActive;

    // TV discovery
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;
    private final List<String[]> found = new ArrayList<>();
    private ArrayAdapter<String> foundAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        host = prefs.getString("host", null);
        tvName = prefs.getString("name", "TV");
        status = findViewById(R.id.status);
        dot = findViewById(R.id.dot);

        View power = findViewById(R.id.power);
        power.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            powerPressed();
        });
        power.setOnLongClickListener(v -> {
            showMacDialog(false);
            return true;
        });
        key(R.id.input, KeyEvent.KEYCODE_TV_INPUT, false);
        key(R.id.settings, KeyEvent.KEYCODE_SETTINGS, false);
        key(R.id.up, KeyEvent.KEYCODE_DPAD_UP, true);
        key(R.id.down, KeyEvent.KEYCODE_DPAD_DOWN, true);
        key(R.id.left, KeyEvent.KEYCODE_DPAD_LEFT, true);
        key(R.id.right, KeyEvent.KEYCODE_DPAD_RIGHT, true);
        key(R.id.ok, KeyEvent.KEYCODE_DPAD_CENTER, false);
        key(R.id.back, KeyEvent.KEYCODE_BACK, false);
        key(R.id.home, KeyEvent.KEYCODE_HOME, false);
        key(R.id.menu, KeyEvent.KEYCODE_MENU, false);
        key(R.id.volUp, KeyEvent.KEYCODE_VOLUME_UP, true);
        key(R.id.volDown, KeyEvent.KEYCODE_VOLUME_DOWN, true);
        key(R.id.mute, KeyEvent.KEYCODE_VOLUME_MUTE, false);
        key(R.id.chUp, KeyEvent.KEYCODE_CHANNEL_UP, true);
        key(R.id.chDown, KeyEvent.KEYCODE_CHANNEL_DOWN, true);
        key(R.id.rew, KeyEvent.KEYCODE_MEDIA_REWIND, false);
        key(R.id.play, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false);
        key(R.id.ff, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, false);
        int[] digits = {R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4, R.id.n5, R.id.n6, R.id.n7, R.id.n8, R.id.n9};
        for (int i = 0; i < 10; i++) key(digits[i], KeyEvent.KEYCODE_0 + i, false);

        app(R.id.appYoutube, "https://www.youtube.com");
        app(R.id.appNetflix, "https://www.netflix.com/title");
        app(R.id.appPrime, "https://app.primevideo.com");

        findViewById(R.id.btnNumbers).setOnClickListener(v -> {
            View pad = findViewById(R.id.numpad);
            pad.setVisibility(pad.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        findViewById(R.id.btnTv).setOnClickListener(v -> showPicker());

        if (host == null) {
            setStatus(GREY, "Not connected");
            ui.post(this::showPicker);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (host != null) net.execute(this::ensureConnected);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopDiscovery();
        net.execute(() -> {
            AtvRemote r = remote;
            remote = null;
            if (r != null) r.close();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.execute(this::closePairing);
        net.shutdown();
    }

    /** Phone's own volume buttons control the TV while the app is open. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (host != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            sendKey(keyCode);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (host != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---------------- Buttons ----------------

    private void key(int viewId, int keyCode, boolean repeat) {
        View v = findViewById(viewId);
        if (!repeat) {
            v.setOnClickListener(x -> {
                x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                sendKey(keyCode);
            });
            return;
        }
        // Hold to repeat (arrows, volume, channel)
        final Runnable[] repeater = new Runnable[1];
        repeater[0] = () -> {
            sendKey(keyCode);
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            ui.postDelayed(repeater[0], 130);
        };
        v.setOnTouchListener((x, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    sendKey(keyCode);
                    ui.postDelayed(repeater[0], 450);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(repeater[0]);
                    break;
            }
            return false; // let the button show its pressed ripple
        });
        v.setOnClickListener(x -> { /* handled on touch */ });
    }

    private void app(int viewId, String link) {
        findViewById(viewId).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            net.execute(() -> {
                if (!ensureConnected()) return;
                try {
                    remote.openLink(link);
                } catch (Exception e) {
                    dropConnection();
                }
            });
        });
    }

    private void sendKey(int keyCode) {
        if (host == null) {
            showPicker();
            return;
        }
        net.execute(() -> {
            if (!ensureConnected()) return;
            try {
                remote.sendKey(keyCode);
            } catch (Exception e) {
                // Connection went stale (e.g. phone slept): reconnect once and retry
                dropConnection();
                if (ensureConnected()) {
                    try {
                        remote.sendKey(keyCode);
                    } catch (Exception ignored) {
                        dropConnection();
                    }
                }
            }
        });
    }

    // ---------------- Connection (runs on the net thread) ----------------

    private boolean ensureConnected() {
        return ensureConnected(false);
    }

    private boolean ensureConnected(boolean quiet) {
        AtvRemote r = remote;
        if (r != null && r.isOpen()) return true;
        if (host == null || pairingActive) return false;
        if (!quiet) setStatus(YELLOW, "Connecting to " + tvName + "…");
        try {
            if (id == null) id = ClientIdentity.loadOrCreate(getFilesDir());
            AtvRemote fresh = new AtvRemote(id, reason ->
                    setStatus(RED, "Disconnected. Tap any button to reconnect."));
            fresh.connect(host);
            remote = fresh;
            setStatus(GREEN, "Connected to " + tvName);
            return true;
        } catch (AtvRemote.NotPairedException e) {
            beginPairing();
            return false;
        } catch (Exception e) {
            if (!quiet) setStatus(RED, "Can't reach " + tvName + ". If it's off, tap ⏻ to wake it.");
            return false;
        }
    }

    private void dropConnection() {
        AtvRemote r = remote;
        remote = null;
        if (r != null) r.close();
    }

    private void beginPairing() {
        pairingActive = true;
        setStatus(YELLOW, "Pairing… a code will appear on your TV");
        try {
            closePairing();
            AtvPairing p = new AtvPairing(id);
            pairing = p;
            p.start(host, Build.MODEL == null ? "Phone" : Build.MODEL);
            ui.post(() -> showCodeDialog(null));
        } catch (Exception e) {
            pairingActive = false;
            closePairing();
            setStatus(RED, "Pairing failed: " + e.getMessage());
        }
    }

    private void closePairing() {
        AtvPairing p = pairing;
        pairing = null;
        if (p != null) p.close();
    }

    // ---------------- Power / Wake-on-LAN ----------------

    private volatile boolean waking;

    /** TV on (reachable): send POWER. TV in standby: wake it over the network. */
    private void powerPressed() {
        if (host == null) {
            showPicker();
            return;
        }
        net.execute(() -> {
            AtvRemote r = remote;
            if (r != null && r.isOpen()) {
                try {
                    r.sendKey(KeyEvent.KEYCODE_POWER);
                    return;
                } catch (Exception e) {
                    dropConnection();
                }
            }
            // Quick check: maybe it's on and we just weren't connected
            if (ensureConnected(true)) {
                try {
                    remote.sendKey(KeyEvent.KEYCODE_POWER);
                } catch (Exception ignored) {
                }
                return;
            }
            if (pairingActive) return;
            String mac = prefs.getString("mac", null);
            if (mac == null) {
                ui.post(() -> showMacDialog(true));
                return;
            }
            wake(mac);
        });
    }

    /** Runs on the net thread: sends magic packets, then waits for the TV to come up. */
    private void wake(String mac) {
        if (waking) return;
        waking = true;
        try {
            setStatus(YELLOW, "Waking " + tvName + "…");
            byte[] packet = magicPacket(mac);
            long until = System.currentTimeMillis() + 30000;
            int round = 0;
            while (System.currentTimeMillis() < until) {
                if (round < 6) sendMagic(packet);
                round++;
                sleep(2500);
                if (ensureConnected(true)) {
                    setStatus(GREEN, "Connected to " + tvName);
                    return;
                }
            }
            setStatus(RED, "The TV didn't wake up. Long-press ⏻ for help.");
            ui.post(() -> Toast.makeText(this,
                    "On the TV turn on Wake on WLAN (Developer options), then try again.",
                    Toast.LENGTH_LONG).show());
        } finally {
            waking = false;
        }
    }

    private void sendMagic(byte[] packet) {
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            List<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            try {
                InetAddress tv = InetAddress.getByName(host);
                targets.add(tv);
                if (tv instanceof Inet4Address) {
                    byte[] b = tv.getAddress();
                    b[3] = (byte) 255; // home networks are almost always /24
                    targets.add(InetAddress.getByAddress(b));
                }
            } catch (Exception ignored) {
            }
            for (InetAddress a : targets) {
                for (int port : new int[]{9, 7}) {
                    try {
                        sock.send(new DatagramPacket(packet, packet.length, a, port));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    static byte[] magicPacket(String mac) {
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        byte[] p = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
        for (int i = 0; i < 16; i++) System.arraycopy(m, 0, p, 6 + i * 6, 6);
        return p;
    }

    static String normalizeMac(String s) {
        String hex = s == null ? "" : s.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (hex.length() != 12) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) b.append(':');
            b.append(hex, i, i + 2);
        }
        return b.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void showMacDialog(boolean thenWake) {
        if (isFinishing()) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setHint("e.g. A4:5E:60:12:34:56");
        String saved = prefs.getString("mac", null);
        if (saved != null) input.setText(saved);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Turn the TV on from your phone")
                .setMessage("A TV in standby switches its Wi‑Fi off, so it must be set up once to listen for a wake-up signal:\n\n"
                        + "1. On the TV, open Settings → Device Preferences (or System) → About, and tap Build (Android TV OS build) 7 times to unlock Developer options.\n"
                        + "2. Go back, open Developer options, and turn on Wake on WLAN (and Wake on LAN if shown).\n"
                        + "3. In About → Status (or Network & Internet → your Wi‑Fi), note the Wi‑Fi MAC address and type it below.")
                .setView(wrap(input))
                .setPositiveButton(thenWake ? "Save and turn on" : "Save", (d, w) -> {
                    String mac = normalizeMac(input.getText().toString());
                    if (mac == null) {
                        Toast.makeText(this, "That doesn't look like a MAC address (12 letters/numbers).", Toast.LENGTH_LONG).show();
                        ui.post(() -> showMacDialog(thenWake));
                        return;
                    }
                    prefs.edit().putString("mac", mac).apply();
                    if (thenWake) net.execute(() -> wake(mac));
                    else Toast.makeText(this, "Saved. Tap ⏻ when the TV is off to turn it on.", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------- Dialogs ----------------

    private void showCodeDialog(String error) {
        if (isFinishing()) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(6)});
        input.setHint("e.g. 4F2A9C");
        input.setTextSize(24);
        input.setLetterSpacing(0.2f);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Enter the code shown on your TV")
                .setMessage(error != null ? error : "This is needed only once.")
                .setView(wrap(input))
                .setCancelable(false)
                .setPositiveButton("Pair", (d, w) -> {
                    String code = input.getText().toString();
                    net.execute(() -> {
                        try {
                            AtvPairing p = pairing;
                            if (p == null) throw new IllegalStateException("Pairing expired, try again");
                            if (p.finish(code)) {
                                closePairing();
                                pairingActive = false;
                                ensureConnected();
                            } else {
                                ui.post(() -> showCodeDialog("That code didn't match. Check the TV and try again."));
                            }
                        } catch (Exception e) {
                            pairingActive = false;
                            closePairing();
                            setStatus(RED, "Pairing failed. Tap any button to try again.");
                        }
                    });
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    pairingActive = false;
                    net.execute(this::closePairing);
                    setStatus(GREY, "Pairing cancelled");
                })
                .show();
        input.requestFocus();
    }

    private void showPicker() {
        if (isFinishing()) return;
        found.clear();
        List<String> labels = new ArrayList<>();
        foundAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);

        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Searching for TVs on your Wi‑Fi…")
                .setAdapter(foundAdapter, (di, which) -> {
                    String[] tv = found.get(which);
                    selectTv(tv[0], tv[1]);
                })
                .setNeutralButton("Enter IP", (di, w) -> showIpDialog())
                .setNegativeButton("Close", null)
                .create();
        d.setOnDismissListener(di -> stopDiscovery());
        d.show();
        startDiscovery(d);
    }

    private void showIpDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("192.168.1.5");
        if (host != null) input.setText(host);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("TV's IP address")
                .setMessage("On the TV: Settings → Network & Internet → your Wi‑Fi → IP address")
                .setView(wrap(input))
                .setPositiveButton("Connect", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) selectTv("TV", ip);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View wrap(View v) {
        FrameLayout f = new FrameLayout(this);
        int pad = (int) (22 * getResources().getDisplayMetrics().density);
        f.setPadding(pad, pad / 3, pad, 0);
        f.addView(v);
        return f;
    }

    private void selectTv(String name, String ip) {
        prefs.edit().putString("host", ip).putString("name", name).apply();
        tvName = name;
        host = ip;
        net.execute(() -> {
            dropConnection();
            closePairing();
            pairingActive = false;
            ensureConnected();
        });
    }

    // ---------------- Discovery ----------------

    private void startDiscovery(AlertDialog dialog) {
        stopDiscovery();
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        if (nsd == null) return;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {
                ui.post(() -> dialog.setTitle("Couldn't search. Use Enter IP."));
            }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                ui.post(() -> {
                    resolveQueue.add(s);
                    resolveNext(dialog);
                });
            }
            @Override public void onServiceLost(NsdServiceInfo s) { }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            discovery = null;
        }
        ui.postDelayed(() -> {
            if (dialog.isShowing() && found.isEmpty()) {
                dialog.setTitle("No TV found yet. Same Wi‑Fi? Or tap Enter IP.");
            }
        }, 8000);
    }

    private void resolveNext(AlertDialog dialog) {
        if (resolving || resolveQueue.isEmpty() || nsd == null) return;
        resolving = true;
        nsd.resolveService(resolveQueue.poll(), new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo s, int e) {
                ui.post(() -> {
                    resolving = false;
                    resolveNext(dialog);
                });
            }
            @Override public void onServiceResolved(NsdServiceInfo s) {
                InetAddress a = s.getHost();
                ui.post(() -> {
                    resolving = false;
                    if (a != null) {
                        String ip = a.getHostAddress();
                        boolean dup = false;
                        for (String[] f : found) if (f[1].equals(ip)) dup = true;
                        if (!dup) {
                            found.add(new String[]{s.getServiceName(), ip});
                            foundAdapter.add(s.getServiceName() + "   (" + ip + ")");
                            dialog.setTitle("Tap your TV");
                        }
                    }
                    resolveNext(dialog);
                });
            }
        });
    }

    private void stopDiscovery() {
        if (nsd != null && discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (Exception ignored) {
            }
        }
        discovery = null;
        resolveQueue.clear();
        resolving = false;
    }

    // ---------------- Status ----------------

    private void setStatus(int color, String text) {
        ui.post(() -> {
            status.setText(text);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            dot.setBackground(g);
            if (color == RED && text.startsWith("Pairing failed")) {
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            }
        });
    }
}
