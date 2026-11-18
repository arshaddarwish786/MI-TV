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

        key(R.id.power, KeyEvent.KEYCODE_POWER, false);
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
        AtvRemote r = remote;
        if (r != null && r.isOpen()) return true;
        if (host == null || pairingActive) return false;
        setStatus(YELLOW, "Connecting to " + tvName + "…");
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
            setStatus(RED, "Can't reach " + tvName + ". Is it on, and on the same Wi‑Fi?");
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
