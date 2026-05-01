package com.limelight.wifi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Periodically polls WiFi signal quality and link speed, reporting changes
 * to a callback for display in the stats overlay and transmission to the server.
 *
 * On Android 12+ (API 31) we use ConnectivityManager.NetworkCallback so we
 * receive RSSI / WifiInfo via the system without needing ACCESS_FINE_LOCATION
 * (which getConnectionInfo() now requires for non-system apps; without it,
 * RSSI=-127 and linkSpeed=-1 are returned and quality always falls to
 * QUALITY_CRITICAL, falsely tripping the smart-reconnect overlay).
 *
 * On older devices we fall back to the legacy poll path, but the polling
 * itself runs on a dedicated HandlerThread so the binder call never lands
 * on the UI thread (which previously caused frame jitter on lower-end
 * devices). Consumer callbacks are still posted to the main thread because
 * Game.java reads UI state from them.
 */
public class WifiMonitor {

    private static final long POLL_INTERVAL_MS = 500;

    public static final int QUALITY_CRITICAL = 0;
    public static final int QUALITY_POOR = 1;
    public static final int QUALITY_FAIR = 2;
    public static final int QUALITY_GOOD = 3;
    public static final int QUALITY_EXCELLENT = 4;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private Handler mainHandler;
    private Runnable pollRunnable;
    private WifiCallback callback;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean running;

    private int lastQuality = -1;

    public interface WifiCallback {
        void onWifiQualityChanged(int quality, int rssi, int linkSpeed);
    }

    public void start(Context context, WifiCallback callback) {
        if (running) {
            return;
        }

        this.callback = callback;
        this.running = true;

        Context appContext = context.getApplicationContext();

        try {
            wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        } catch (Exception e) {
            LimeLog.warning("Failed to get WifiManager: " + e.getMessage());
            return;
        }

        // HandlerThread keeps all binder/poll work off the UI thread.
        workerThread = new HandlerThread("WifiMonitor");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        mainHandler = new Handler(Looper.getMainLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: use NetworkCallback. WifiInfo arrives via
            // NetworkCapabilities.getTransportInfo() without requiring
            // ACCESS_FINE_LOCATION for the *current* network.
            try {
                connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            } catch (Exception e) {
                LimeLog.warning("Failed to get ConnectivityManager: " + e.getMessage());
            }

            if (connectivityManager != null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        if (!running || caps == null) {
                            return;
                        }
                        try {
                            int rssi;
                            int linkSpeed = -1;

                            // getSignalStrength() is API 29+ but only fully populated on API 30+.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                rssi = caps.getSignalStrength();
                            } else {
                                rssi = -127;
                            }

                            // getTransportInfo() returns WifiInfo for non-system apps on API 31+.
                            Object transportInfo = caps.getTransportInfo();
                            if (transportInfo instanceof WifiInfo) {
                                WifiInfo wifiInfo = (WifiInfo) transportInfo;
                                int wifiRssi = wifiInfo.getRssi();
                                if (wifiRssi != WifiManager.UNKNOWN_RSSI && wifiRssi != 0) {
                                    rssi = wifiRssi;
                                }
                                linkSpeed = wifiInfo.getLinkSpeed();
                            }

                            dispatchQuality(rssi, linkSpeed);
                        } catch (Exception e) {
                            LimeLog.warning("WiFi onCapabilitiesChanged failed: " + e.getMessage());
                        }
                    }
                };

                try {
                    connectivityManager.registerNetworkCallback(request, networkCallback, workerHandler);
                    return;
                } catch (Exception e) {
                    LimeLog.warning("Failed to register WiFi NetworkCallback: " + e.getMessage());
                    networkCallback = null;
                    // Fall through to legacy poll path.
                }
            }
        }

        // Legacy poll path (API < 31 or NetworkCallback registration failed).
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }

                try {
                    WifiInfo info = wifiManager.getConnectionInfo();
                    if (info != null) {
                        int rssi = info.getRssi();
                        int linkSpeed = info.getLinkSpeed();
                        dispatchQuality(rssi, linkSpeed);
                    }
                } catch (Exception e) {
                    LimeLog.warning("WiFi poll failed: " + e.getMessage());
                }

                if (running) {
                    workerHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                }
            }
        };

        workerHandler.post(pollRunnable);
    }

    /**
     * Compute quality and dispatch to the consumer on the main thread.
     * Skips dispatch when quality has not transitioned (lastQuality unchanged).
     * Bails out early if the JNI sendWifiQuality stub has not been wired yet
     * (the consumer callback still fires so the local overlay updates).
     */
    private void dispatchQuality(final int rssi, final int linkSpeed) {
        final int quality = calculateQuality(rssi, linkSpeed);

        // Only fire on transitions; the reconnect overlay polls fast enough
        // and we don't need duplicate same-quality calls.
        if (quality == lastQuality) {
            return;
        }
        lastQuality = quality;

        final WifiCallback cb = callback;
        if (cb == null) {
            return;
        }

        // Game.java reads UI state in the callback, so post to main.
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (running) {
                    cb.onWifiQualityChanged(quality, rssi, linkSpeed);
                }
            }
        });
    }

    public void stop() {
        running = false;

        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                LimeLog.warning("Failed to unregister WiFi NetworkCallback: " + e.getMessage());
            }
            networkCallback = null;
        }

        if (workerHandler != null) {
            workerHandler.removeCallbacksAndMessages(null);
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        workerHandler = null;
        mainHandler = null;
        lastQuality = -1;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Calculate WiFi quality level based on RSSI and link speed.
     */
    private static int calculateQuality(int rssi, int linkSpeed) {
        if (rssi > -50 && linkSpeed > 400) {
            return QUALITY_EXCELLENT;
        } else if (rssi > -60 && linkSpeed > 200) {
            return QUALITY_GOOD;
        } else if (rssi > -70 && linkSpeed > 100) {
            return QUALITY_FAIR;
        } else if (rssi > -75) {
            return QUALITY_POOR;
        } else {
            return QUALITY_CRITICAL;
        }
    }

    /**
     * Build the 4-byte WiFi quality payload to send to the server.
     * Format:
     *   [0] quality (0-4)
     *   [1] rssi (signed byte)
     *   [2-3] link_speed (uint16 little-endian, clamped to [0, 65535])
     *
     * Returns null if the JNI sendWifiQuality stub has not been wired yet,
     * so callers can skip the allocation entirely.
     */
    public static byte[] buildWifiQualityPayload(int quality, int rssi, int linkSpeed) {
        if (!MoonBridge.sendWifiQualityImplemented) {
            return null;
        }
        // WifiInfo.getLinkSpeed() returns -1 for "unknown"; without clamping
        // that would serialize as 65535 Mbps. Clamp into the uint16 domain.
        linkSpeed = Math.max(0, Math.min(65535, linkSpeed));
        byte[] payload = new byte[4];
        payload[0] = (byte) quality;
        payload[1] = (byte) rssi;
        payload[2] = (byte) (linkSpeed & 0xFF);
        payload[3] = (byte) ((linkSpeed >> 8) & 0xFF);
        return payload;
    }
}
