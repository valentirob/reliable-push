package io.reliablepush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Guards against Gatling DEBUG logging leaking X-Api-Key / device tokens into the container logs. */
class LoggingConfigTest {

    @Test
    void responseLoggerIsOffAndRootIsWarn() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/logback-test.xml")) {
            assertThat(in).as("logback-test.xml on the test classpath").isNotNull();
            Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getDocumentElement();
            String responseLevel = null;
            NodeList loggers = root.getElementsByTagName("logger");
            for (int i = 0; i < loggers.getLength(); i++) {
                Element l = (Element) loggers.item(i);
                if (l.getAttribute("name").equals("io.gatling.http.engine.response")) {
                    responseLevel = l.getAttribute("level");
                }
            }
            assertThat(responseLevel).isIn("OFF", "WARN", "ERROR");
            Element rootLogger = (Element) root.getElementsByTagName("root").item(0);
            assertThat(rootLogger.getAttribute("level")).isIn("WARN", "ERROR");
        }
    }
}
