package io.github.choganhq.choganplus;

import android.Manifest;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.charset.StandardCharsets;

/**
 * Two phones in one room play one game over Nearby Connections (#4).
 *
 * The page's Chogan.link (chogan's www/lib/chogan.js) owns the protocol: sequence numbers,
 * resending after a drop, version checks. This side only finds the other phone, keeps one
 * connection, and passes strings both ways as "link" events:
 * {type: connected|disconnected|message|error, id, data}.
 *
 * Until stop() it keeps looking: a dropped connection restarts advertising or discovery, so
 * two phones that walk out of range and back reconnect without a tap.
 */
@CapacitorPlugin(
    name = "ChoganNearby",
    permissions = {
        // Nearby needs different runtime permissions per Android version; only the alias for
        // the running version is requested, so the dialog never asks for something unused.
        @Permission(alias = "nearby33", strings = {
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES }),
        @Permission(alias = "nearby31", strings = {
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION }),
        @Permission(alias = "nearbyOld", strings = { Manifest.permission.ACCESS_FINE_LOCATION })
    }
)
public class NearbyPlugin extends Plugin {
    // One phone against one phone: the cluster strategies would let a third join a game for two
    private static final Strategy STRATEGY = Strategy.P2P_POINT_TO_POINT;

    private ConnectionsClient client;
    private boolean running = false;
    private boolean host = false;
    private String service = null;
    private String name = "";
    private String peer = null;

    private static String alias() {
        if (Build.VERSION.SDK_INT >= 33) return "nearby33";
        if (Build.VERSION.SDK_INT >= 31) return "nearby31";
        return "nearbyOld";
    }

    @Override
    public void load() {
        client = Nearby.getConnectionsClient(getContext());
    }

    @PluginMethod
    public void start(PluginCall call) {
        String role = call.getString("role", "host");
        String svc = call.getString("service", "");
        if (svc == null || svc.isEmpty()) { call.reject("service is required"); return; }
        stopAll();
        host = !"guest".equals(role);
        // The package name keeps Chogan Plus apart from any other app on Nearby, and the debug
        // build (".debug") apart from the store build, whose moves it might read differently.
        service = getContext().getPackageName() + "." + svc;
        name = call.getString("name", "");
        if (name == null || name.isEmpty()) name = "Chogan";
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias(), call, "permsDone");
            return;
        }
        begin(call);
    }

    @PermissionCallback
    private void permsDone(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            emit("error", null, "permission");
            call.reject("permission");
            return;
        }
        begin(call);
    }

    private void begin(PluginCall call) {
        running = true;
        look();
        call.resolve();
    }

    private void look() {
        if (!running || peer != null) return;
        if (host) {
            client.startAdvertising(name, service, lifecycle,
                    new AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
                .addOnFailureListener(e -> failed(e));
        } else {
            client.startDiscovery(service, discovery,
                    new DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
                .addOnFailureListener(e -> failed(e));
        }
    }

    private void failed(Exception e) {
        // Already advertising or discovering is the state we wanted, not an error
        String m = String.valueOf(e.getMessage());
        if (m.contains(String.valueOf(ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING))
                || m.contains(String.valueOf(ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING))) return;
        emit("error", null, m);
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String id, DiscoveredEndpointInfo info) {
            if (!running || peer != null || !service.equals(info.getServiceId())) return;
            client.requestConnection(name, id, lifecycle).addOnFailureListener(e -> failed(e));
        }

        @Override
        public void onEndpointLost(String id) { }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String id, ConnectionInfo info) {
            // A game for two: once paired, anyone else is turned away
            if (!running || peer != null) { client.rejectConnection(id); return; }
            client.acceptConnection(id, payloads);
        }

        @Override
        public void onConnectionResult(String id, ConnectionResolution result) {
            if (!result.getStatus().isSuccess()) { look(); return; }
            peer = id;
            if (host) client.stopAdvertising(); else client.stopDiscovery();
            emit("connected", id, null);
        }

        @Override
        public void onDisconnected(String id) {
            if (!id.equals(peer)) return;
            peer = null;
            emit("disconnected", id, null);
            look();
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String id, Payload p) {
            if (p.getType() != Payload.Type.BYTES || p.asBytes() == null) return;
            emit("message", id, new String(p.asBytes(), StandardCharsets.UTF_8));
        }

        @Override
        public void onPayloadTransferUpdate(String id, PayloadTransferUpdate u) { }
    };

    @PluginMethod
    public void send(PluginCall call) {
        String id = call.getString("id");
        String data = call.getString("data");
        if (id == null || data == null || !id.equals(peer)) { call.reject("not connected"); return; }
        client.sendPayload(id, Payload.fromBytes(data.getBytes(StandardCharsets.UTF_8)));
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopAll();
        call.resolve();
    }

    private void stopAll() {
        running = false;
        peer = null;
        if (client != null) {
            client.stopAdvertising();
            client.stopDiscovery();
            client.stopAllEndpoints();
        }
    }

    @Override
    protected void handleOnDestroy() {
        stopAll();
    }

    private void emit(String type, String id, String data) {
        JSObject ev = new JSObject();
        ev.put("type", type);
        if (id != null) ev.put("id", id);
        if (data != null) ev.put(type.equals("error") ? "message" : "data", data);
        notifyListeners("link", ev);
    }
}
