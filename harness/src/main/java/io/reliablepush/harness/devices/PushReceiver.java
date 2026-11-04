package io.reliablepush.harness.devices;

import java.util.Map;
import java.util.UUID;

/** Seam for "a push reached the user's devices"; implemented by {@link DeviceSimulator}. */
@FunctionalInterface
public interface PushReceiver {

    void receivePush(UUID trackingId, String appId, String userId, Map<UUID, DeviceBehaviour> perDevice);
}
