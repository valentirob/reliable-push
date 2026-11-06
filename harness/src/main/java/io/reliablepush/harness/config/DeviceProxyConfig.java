package io.reliablepush.harness.config;

import io.reliablepush.harness.devices.DeviceProxy;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The device-traffic proxy: it listens only when {@code harness.device-proxy-target} is set (compose sets it), on
 * {@code harness.device-proxy-bind} (loopback by default).
 */
@Configuration(proxyBeanMethods = false)
class DeviceProxyConfig {

    @Bean(initMethod = "start", destroyMethod = "close")
    DeviceProxy deviceProxy(HarnessProperties properties) {
        URI target = properties.deviceProxyTarget();
        if (target == null) {
            return DeviceProxy.disabled();
        }
        if (!"http".equals(target.getScheme()) || target.getHost() == null) {
            throw new IllegalStateException("harness.device-proxy-target must be an http://host[:port] URL");
        }
        String host = target.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port = target.getPort() > 0 ? target.getPort() : 80;
        return new DeviceProxy(properties.proxyBindAddress(), properties.proxyListenPort(), host, port);
    }
}
