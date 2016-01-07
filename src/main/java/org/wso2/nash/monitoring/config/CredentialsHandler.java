package org.wso2.nash.monitoring.config;

/**
 * Created by nathasha on 1/4/16.
 */

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;

/**
 * Parse XML document to retrieve username and password of user
 */
public class CredentialsHandler {

    private static String username;
    private static String password;
    Document doc;

    /**
     *
     * @throws ParserConfigurationException
     * @throws IOException
     * @throws SAXException
     */
    public CredentialsHandler() throws ParseXMLException {
        /*exit the current directory*/
        File userDir = new File(System.getProperty("user.dir"));
        String parentDir = userDir.getAbsoluteFile().getParent();

        File xmlFile = new File(parentDir+ "/conf/credentials.xml");
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = null;
        try {
            dBuilder = dbFactory.newDocumentBuilder();
        }  catch (ParserConfigurationException e) {
            throw new ParseXMLException("Error while creating dBuilder",e);
        }

        try {
            doc = dBuilder.parse(xmlFile);
        } catch (SAXException e) {
            throw new ParseXMLException("Parsing failed",e);
        } catch (IOException e) {
            throw new ParseXMLException("Parsing failed",e);
        }

        doc.getDocumentElement().normalize();
    }

    /**
     * Parse username of user from XML document
     *
     * @return username
     * @throws ParserConfigurationException
     * @throws IOException
     * @throws SAXException
     */
    public String getUsername() {

        NodeList nList = doc.getElementsByTagName("user");
        for (int temp = 0; temp < nList.getLength(); temp++) {

            Node nNode = nList.item(temp);

            if (nNode.getNodeType() == Node.ELEMENT_NODE) {

                Element eElement = (Element) nNode;
                username = eElement.getElementsByTagName("userName").item(0).getTextContent();

            }
        }
        return username;
    }

    /**
     * Parse Password from XML document
     *
     * @return Password of the user
     * @throws ParserConfigurationException
     * @throws IOException
     * @throws SAXException
     */
    public String getPassword() {

        NodeList nList = doc.getElementsByTagName("user");
        for (int temp = 0; temp < nList.getLength(); temp++) {

            Node nNode = nList.item(temp);

            if (nNode.getNodeType() == Node.ELEMENT_NODE) {

                Element eElement = (Element) nNode;
                password = eElement.getElementsByTagName("password").item(0).getTextContent();

            }
        }
        return password;
    }
}
