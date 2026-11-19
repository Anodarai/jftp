import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class XmlFileFormatter {
    public static byte[] readAndFormat(Path path)
            throws IOException, ParserConfigurationException, TransformerException {
        byte[] contents = Files.readAllBytes(path);
        DocumentBuilderFactory parserFactory = DocumentBuilderFactory.newInstance();
        parserFactory.setNamespaceAware(true);
        parserFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder parser = parserFactory.newDocumentBuilder();
        parser.setErrorHandler(new DefaultHandler());

        Document document;
        try {
            document = parser.parse(new ByteArrayInputStream(contents));
        } catch (SAXException invalidXml) {
            return contents;
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream formatted = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(formatted));
        return formatted.toByteArray();
    }

    public static void main(String[] args)
            throws IOException, ParserConfigurationException, TransformerException {
        if (args.length != 1) {
            System.err.println("Usage: java XmlFileFormatter.java <file>");
            System.exit(2);
        }
        System.out.write(readAndFormat(Path.of(args[0])));
        System.out.flush();
    }
}
