package vn.edu.p2p.peer.auth;

import javafx.scene.control.Dialog;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/** R88: existing FXML must expose the nodes used for centered login and modal setup. */
class LoginAdvancedPopupContractTest {
    @Test
    void formsAndSettingsCanBeCenteredAndReparentedWithoutDuplicateInputs() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/vn/edu/p2p/peer/view/login-view.fxml")) {
            assertNotNull(stream);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = factory.newDocumentBuilder().parse(stream);
            assertEquals("ScrollPane", doc.getDocumentElement().getNodeName());
            Element login = find(doc, "loginForm");
            Element registration = find(doc, "registrationForm");
            Element advanced = find(doc, "advancedSettingsBox");
            assertEquals("CENTER", ((Element) login.getParentNode()).getAttribute("alignment"));
            assertEquals("false", registration.getAttribute("visible"));
            assertEquals("false", registration.getAttribute("managed"));
            assertEquals("false", advanced.getAttribute("visible"));
            assertEquals("false", advanced.getAttribute("managed"));
            assertTrue(hasDescendant(advanced, "trackerHostField"));
            assertTrue(hasDescendant(advanced, "trackerPortField"));
            assertTrue(hasDescendant(advanced, "trackerTlsCheckBox"));
            assertTrue(hasDescendant(advanced, "importTrackerKeyButton"));
            assertNotNull(find(doc, "advancedSettingsToggleButton"));
            assertNotNull(find(doc, "statusLabel"));
        }
    }

    @Test
    void controllerKeepsTheAdvancedSettingsDialogAsItsOwnWindow() throws Exception {
        var field = LoginController.class.getDeclaredField("advancedSettingsDialog");
        assertEquals(Dialog.class, field.getType());
    }

    private static Element find(Document doc, String fxId) {
        var elements = doc.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element node = (Element) elements.item(i);
            if (fxId.equals(node.getAttribute("fx:id"))) return node;
        }
        fail("Missing field: " + fxId);
        return null;
    }

    private static boolean hasDescendant(Element parent, String fxId) {
        var children = parent.getElementsByTagName("*");
        for (int i = 0; i < children.getLength(); i++) {
            if (fxId.equals(((Element) children.item(i)).getAttribute("fx:id"))) return true;
        }
        return false;
    }
}
