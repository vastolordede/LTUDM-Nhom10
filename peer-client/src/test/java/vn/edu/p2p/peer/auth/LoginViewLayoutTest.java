package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/** R88: the sign-in, sign-up and advanced setup areas must not overlap. */
class LoginViewLayoutTest {
    @Test
    void credentialsAreOnSeparateHiddenManagedFormsAndAdvancedConfigIsHidden() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/vn/edu/p2p/peer/view/login-view.fxml")) {
            assertNotNull(stream, "Login FXML resource missing");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = factory.newDocumentBuilder().parse(stream);
            Element login = find(doc, "loginForm");
            Element registration = find(doc, "registrationForm");
            Element advanced = find(doc, "advancedSettingsBox");
            assertEquals("false", registration.getAttribute("visible"));
            assertEquals("false", registration.getAttribute("managed"));
            assertEquals("false", advanced.getAttribute("visible"));
            assertEquals("false", advanced.getAttribute("managed"));
            assertTrue(hasDescendant(login, "usernameField"));
            assertTrue(hasDescendant(login, "passwordField"));
            assertFalse(hasDescendant(login, "confirmPasswordField"));
            assertTrue(hasDescendant(registration, "registerUsernameField"));
            assertTrue(hasDescendant(registration, "registerPasswordField"));
            assertTrue(hasDescendant(registration, "confirmPasswordField"));
            assertTrue(hasDescendant(advanced, "importTrackerKeyButton"));
            assertTrue(hasDescendant(advanced, "listeningPortField"));
        }
    }

    private static Element find(Document doc, String fxId) {
        var all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element node = (Element) all.item(i);
            if (fxId.equals(node.getAttribute("fx:id"))) return node;
        }
        fail("Missing form/field: " + fxId);
        return null;
    }

    private static boolean hasDescendant(Element parent, String fxId) {
        var descendants = parent.getElementsByTagName("*");
        for (int i = 0; i < descendants.getLength(); i++) {
            if (fxId.equals(((Element) descendants.item(i)).getAttribute("fx:id"))) return true;
        }
        return false;
    }
}
