package vn.edu.p2p.peer.view;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import vn.edu.p2p.peer.share.FolderController;
import vn.edu.p2p.peer.share.ShareController;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression gate for Gradle's actual JavaFX runtime resource classpath.
 * XML and reflection checks do not need a JavaFX display/toolkit.
 */
class ViewResourceClasspathTest {
    private static final String ROOT = "/vn/edu/p2p/peer/view/";
    private static final String FX = "http://javafx.com/fxml";

    @Test
    void folderTabFXMLIsPackagedAndItsHandlersExist() throws Exception {
        Document xml = verifyView("folder-view.fxml", FolderController.class);
        assertNotNull(findId(xml, "downloadAllButton"));
        assertNotNull(findId(xml, "downloadSelectedButton"));
    }

    @Test
    void shareTabFXMLOffersFolderSharing() throws Exception {
        Document xml = verifyView("share-view.fxml", ShareController.class);
        Element folderButton = findId(xml, "chooseFolderButton");
        assertNotNull(folderButton, "Packaged share-view.fxml must expose folder sharing");
        assertEquals("#onShareFolder", folderButton.getAttribute("onAction"));
    }

    private static Document verifyView(String filename, Class<?> controller) throws Exception {
        InputStream stream = ViewResourceClasspathTest.class.getResourceAsStream(ROOT + filename);
        assertNotNull(stream, filename + " is missing from Gradle's test/runtime classpath");
        Document xml;
        try (stream) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            xml = factory.newDocumentBuilder().parse(new InputSource(stream));
        }
        assertEquals(controller.getName(), xml.getDocumentElement().getAttributeNS(FX, "controller"));
        NodeList nodes = xml.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String id = el.getAttributeNS(FX, "id");
            if (!id.isBlank()) {
                assertDoesNotThrow(() -> controller.getDeclaredField(id),
                        () -> "Missing controller field for fx:id=" + id + " in " + filename);
            }
            String action = el.getAttribute("onAction");
            if (action.startsWith("#")) {
                String handler = action.substring(1);
                boolean exists = Arrays.stream(controller.getDeclaredMethods())
                        .map(Method::getName).anyMatch(handler::equals);
                assertTrue(exists, () -> "Missing handler " + handler + " in " + controller.getName());
            }
        }
        return xml;
    }

    private static Element findId(Document xml, String id) {
        NodeList nodes = xml.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (id.equals(el.getAttributeNS(FX, "id"))) return el;
        }
        return null;
    }
}
