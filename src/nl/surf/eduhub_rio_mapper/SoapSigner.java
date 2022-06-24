package nl.surf.eduhub_rio_mapper;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.*;
import java.io.*;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class SoapSigner {
    private final String keystoreFileName;
    private final String keyAlias;
    private final String keystorePassword;
    private final String xmlInput;
    private final List<String> refs;

    public SoapSigner(String keystoreFileName, String keyAlias, String keystorePassword, String xmlInput, List<String> refs) {
        this.keystoreFileName = keystoreFileName;
        this.keyAlias = keyAlias;
        this.keystorePassword = keystorePassword;
        this.xmlInput = xmlInput;
        this.refs = refs;
    }

    private static KeyInfo getKeyInfo(KeyStore.PrivateKeyEntry keyEntry, XMLSignatureFactory fac) {
        X509Certificate cert = (X509Certificate) keyEntry.getCertificate();
        // Create the KeyInfo containing the X509Data.
        KeyInfoFactory kif = fac.getKeyInfoFactory();
        List<Object> x509Content = new ArrayList<>();
        x509Content.add(cert.getSubjectX500Principal().getName());
        x509Content.add(cert);
        X509Data xd = kif.newX509Data(x509Content);
        return kif.newKeyInfo(Collections.singletonList(xd));
    }

    private static String outputDocument(Document doc) throws TransformerException {
        // Output the resulting document.
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer trans = tf.newTransformer();
        StringWriter writer = new StringWriter();
        trans.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }

    // For entire document, reference should be ""
    public SignedInfo createSignedInfo(XMLSignatureFactory fac) throws InvalidAlgorithmParameterException, NoSuchAlgorithmException {
        DigestMethod digestMethod = fac.newDigestMethod(DigestMethod.SHA1, null);
        Transform transform = fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null);
        List<Transform> transforms = Collections.singletonList(transform);
        SignatureMethod signatureMethod = fac.newSignatureMethod(SignatureMethod.RSA_SHA1, null);
        List<Reference> refs = this.refs.stream().map(r -> fac.newReference(r, digestMethod, transforms, null, null)).collect(Collectors.toList());
        CanonicalizationMethod canonicalizationMethod = fac.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null);
        return fac.newSignedInfo(canonicalizationMethod, signatureMethod, refs);
    }

    private KeyStore.PrivateKeyEntry getKeyEntry() throws KeyStoreException, IOException, UnrecoverableEntryException, NoSuchAlgorithmException, CertificateException {
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(new FileInputStream(keystoreFileName), keystorePassword.toCharArray());
        return (KeyStore.PrivateKeyEntry) ks.getEntry(keyAlias, new KeyStore.PasswordProtection(keystorePassword.toCharArray()));
    }

    private Document sign(Document doc, XMLSignatureFactory fac, SignedInfo si, KeyStore.PrivateKeyEntry keyEntry, KeyInfo ki) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException, MarshalException, XMLSignatureException, CertificateEncodingException {
        // Mark elements as having type "id"
        XPath xpath = XPathFactory.newInstance().newXPath();
        XPathExpression expr = xpath.compile("//*[@Id]");
        NodeList nodes = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            System.out.println(el.getTagName());
            el.setIdAttribute("Id", true);
        }

        // Create a DOMSignContext and specify the RSA PrivateKey and
        // location of the resulting XMLSignature's parent element.
        // documentElement is Envelope, firstChild of Envelope is Header, firstChild of Header is Security.
        Element header = (Element) doc.getDocumentElement().getElementsByTagName("soapenv:Header").item(0);
        Element security = (Element) header.getElementsByTagName("wsse:Security").item(0);
        Element bst = (Element) security.getElementsByTagName("wsse:BinarySecurityToken").item(0);
        byte[] certificate = keyEntry.getCertificate().getEncoded();
        bst.setTextContent(Base64.getEncoder().encodeToString(certificate));

        DOMSignContext dsc = new DOMSignContext(keyEntry.getPrivateKey(), security);
        dsc.setDefaultNamespacePrefix("ds");

        // Create the XMLSignature, but don't sign it yet.
        XMLSignature signature = fac.newXMLSignature(si, ki);

        // Marshal, generate, and sign the enveloped signature.
        signature.sign(dsc);
        return doc;
    }

    public String signedXml() {
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        try {
            SignedInfo si = createSignedInfo(fac);
            KeyStore.PrivateKeyEntry keyEntry = getKeyEntry();
            KeyInfo ki = getKeyInfo(keyEntry, fac);

            // Instantiate the document to be signed.
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xmlInput)));

            Document signedDoc = sign(doc, fac, si, keyEntry, ki);
            return outputDocument(signedDoc);
        } catch (ParserConfigurationException | KeyStoreException | UnrecoverableEntryException |
                 InvalidAlgorithmParameterException | NoSuchAlgorithmException | MarshalException |
                 XPathExpressionException | SAXException | TransformerException | XMLSignatureException | IOException |
                 CertificateException e) {
            throw new RuntimeException(e);
        }
    }
}
