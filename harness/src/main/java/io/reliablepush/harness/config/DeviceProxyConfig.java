package io.reliablepush.harness.config;

import io.reliablepush.harness.devices.DeviceProxy;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The device-traffic proxy: it listens only when {@code harness.device-proxy-target} is set (compose sets it). */
@Configuration(proxyBeanMethods = false)
class DeviceProxyConfig {

    @Bean(initMethod = "start", destroyMethod = "close")
    DeviceProxy deviceProxy(HarnessProperties properties) {
        URI target = properties.deviceProxyTarget();
        if (target == null) {
            return DeviceProxy.disabled();
        }
        int port = target.getPort() > 0 ? target.getPort() : 80;
        return new DeviceProxy(properties.proxyListenPort(), target.getHost(), port);
    }
}
