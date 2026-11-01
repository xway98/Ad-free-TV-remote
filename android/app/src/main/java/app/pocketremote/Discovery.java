package app.pocketremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Deque;

/** Finds Android TVs and Google TVs on the Wi-Fi: they advertise themselves as _androidtvremote2._tcp. */
final class Discovery {
    interface Callback {
        void onTv(String name, String host);
    }

    private static final String TYPE = "_androidtvremote2._tcp.";

    private final NsdManager nsd;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Deque<NsdServiceInfo> queue = new ArrayDeque<>();
    private NsdManager.DiscoveryListener listener;
    private Callback callback;
    private boolean resolving;

    Discovery(Context c) {
        nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
    }

    void start(Callback cb) {
        if (nsd == null || listener != null) return;
        callback = cb;
        listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { listener = null; }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo i) {}

            @Override public void onServiceFound(final NsdServiceInfo info) {
                ui.post(() -> {
                    queue.add(info);
                    next();
                });
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            listener = null;
        }
    }

    void stop() {
        callback = null;
        queue.clear();
        if (nsd != null && listener != null) {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
            }
        }
        listener = null;
    }

    private void next() {
        if (resolving || queue.isEmpty() || nsd == null) return;
        resolving = true;
        NsdServiceInfo info = queue.poll();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) {
                    ui.post(() -> {
                        resolving = false;
                        next();
                    });
                }

                @Override public void onServiceResolved(final NsdServiceInfo i) {
                    ui.post(() -> {
                        resolving = false;
                        if (callback != null && i.getHost() != null) {
                            callback.onTv(i.getServiceName(), i.getHost().getHostAddress());
                        }
                        next();
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }
}
