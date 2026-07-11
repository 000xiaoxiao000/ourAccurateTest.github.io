package com.oAT.web.config;

import org.apache.catalina.LifecycleListener;
import org.apache.catalina.startup.Tomcat;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Disables Tomcat APR/native initialization.
 *
 * <p>Newer JDKs warn when Tomcat Native loads a native library through
 * {@code System.load} unless the JVM is started with
 * {@code --enable-native-access=ALL-UNNAMED}. This application does not depend
 * on APR/native transport, so removing the APR lifecycle listener avoids the
 * warning without requiring operators to remember an extra JVM flag.</p>
 */
@Configuration
public class TomcatNativeConfig {

    @Bean
    public TomcatServletWebServerFactory tomcatServletWebServerFactory() {
        return new AprDisabledTomcatServletWebServerFactory();
    }

    /**
     * Keeps Spring Boot's normal Tomcat customization pipeline intact.
     *
     * <p>{@link WebServerFactoryCustomizer} beans are still applied to this
     * factory by Spring Boot after the bean is created.</p>
     */
    static class AprDisabledTomcatServletWebServerFactory extends TomcatServletWebServerFactory {
        @Override
        protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
            removeAprLifecycleListener(tomcat);
            return super.getTomcatWebServer(tomcat);
        }

        private void removeAprLifecycleListener(Tomcat tomcat) {
            if (tomcat == null || tomcat.getServer() == null) return;
            for (LifecycleListener listener : tomcat.getServer().findLifecycleListeners()) {
                if ("org.apache.catalina.core.AprLifecycleListener".equals(listener.getClass().getName())) {
                    tomcat.getServer().removeLifecycleListener(listener);
                }
            }
        }
    }
}
