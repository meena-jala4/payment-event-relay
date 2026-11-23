package nz.paymentrelay.fraud;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import nz.paymentrelay.payment.FraudDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads the vendor's XML without being fussy about it.
 * - Namespace-aware: matches on namespace URI and local name, so a change of the "fs:" prefix doesn't break us.
 * - Only looks up the elements we need, so any new elements the vendor adds are ignored.
 * - Doesn't trust the HTTP status: an &lt;Error&gt; body is an error even with HTTP 200.
 * - XXE protection is switched on, since this is XML from a third party.
 */
@Component
public class FraudResponseParser {

    public static final String NS = "urn:vendor:fraudscreen:v2";
    private static final Logger log = LoggerFactory.getLogger(FraudResponseParser.class);

    private final DocumentBuilderFactory factory;

    public FraudResponseParser() {
        try {
            factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot configure XML parser", e);
        }
    }

    public ScreeningResult parse(String xml) {
        Document doc = read(xml);

        Element error = first(doc.getDocumentElement(), "Error");
        if (error != null) {
            return new ScreeningResult.ProviderError(
                    text(error, "Code"),
                    text(error, "Message"),
                    // if the vendor leaves out Retryable we assume it can be retried: a few extra retries cost less
                    // than dead-lettering a fraud check
                    !"false".equalsIgnoreCase(text(error, "Retryable")));
        }

        Element result = first(doc.getDocumentElement(), "Result");
        if (result == null) {
            throw new FraudProviderException("Vendor response had neither <Result> nor <Error>");
        }
        String decisionText = text(result, "Decision");
        FraudDecision decision = toDecision(decisionText);
        String score = text(result, "RiskScore");

        List<ScreeningResult.Reason> reasons = new ArrayList<>();
        Element reasonsEl = first(result, "Reasons");
        if (reasonsEl != null) {
            NodeList nodes = reasonsEl.getElementsByTagNameNS(NS, "Reason");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element r = (Element) nodes.item(i);
                reasons.add(new ScreeningResult.Reason(r.getAttribute("code"), r.getTextContent().trim()));
            }
        }
        Element header = first(doc.getDocumentElement(), "Header");
        String requestRef = header == null ? null : text(header, "RequestRef");

        return new ScreeningResult.Decision(requestRef, decision,
                score == null || score.isBlank() ? null : new BigDecimal(score.trim()), reasons);
    }

    /**
     * Fails safe: a decision we don't recognise (say the vendor adds "HOLD") becomes REVIEW,
     * so a person looks at it instead of us quietly accepting it.
     */
    private static FraudDecision toDecision(String value) {
        if (value == null) {
            throw new FraudProviderException("Vendor response missing <Decision>");
        }
        try {
            return FraudDecision.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            log.warn("Unknown fraud decision '{}' from vendor, treating as REVIEW", value);
            return FraudDecision.REVIEW;
        }
    }

    private Document read(String xml) {
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // throw instead of printing parse errors to stderr
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new FraudProviderException("Unparseable vendor response: " + e.getMessage(), e);
        }
    }

    private static Element first(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS(NS, localName);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String text(Element parent, String localName) {
        Element el = first(parent, localName);
        return el == null ? null : el.getTextContent().trim();
    }
}
