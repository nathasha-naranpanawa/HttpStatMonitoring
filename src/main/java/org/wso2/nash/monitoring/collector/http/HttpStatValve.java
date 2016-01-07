package org.wso2.nash.monitoring.collector.http;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.wso2.carbon.databridge.agent.AgentHolder;
import org.wso2.carbon.databridge.agent.DataPublisher;
import org.wso2.carbon.databridge.agent.exception.DataEndpointAgentConfigurationException;
import org.wso2.carbon.databridge.agent.exception.DataEndpointAuthenticationException;
import org.wso2.carbon.databridge.agent.exception.DataEndpointConfigurationException;
import org.wso2.carbon.databridge.agent.exception.DataEndpointException;
import org.wso2.carbon.databridge.commons.Event;
import org.wso2.carbon.databridge.commons.exception.TransportException;
import org.wso2.carbon.databridge.commons.utils.DataBridgeCommonsUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import org.wso2.nash.monitoring.config.CredentialsHandler;
import org.wso2.nash.monitoring.config.ParseXMLException;
import org.wso2.nash.monitoring.config.StreamConfig;
import org.wso2.nash.monitoring.publisher.MonitoringPublisherException;
import org.wso2.nash.monitoring.publisher.http.EventBuilder;
import org.wso2.nash.monitoring.publisher.http.WebappMonitoringEvent;
import org.xml.sax.SAXException;
import ua_parser.Client;
import ua_parser.Parser;

import javax.servlet.ServletException;
import javax.servlet.http.HttpSession;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.security.Principal;
import java.util.Enumeration;
import java.util.regex.Pattern;

/**
 * Created by nathasha on 12/7/15.
 */

/**
 * Custom Tomcat valve to Publish server statistics data to Data Analytics Server
 */
public class HttpStatValve extends ValveBase {

    public static final String BACKSLASH = "/";
    public static final String WEBAPP = "webapp";
    private static final Log LOG = LogFactory.getLog(HttpStatValve.class);

    private volatile EventBuilder eventbuilder;

    private Parser uaParser = null;
    private Pattern pattern;

    DataPublisher dataPublisher;

    //Event stream name
    private String HTTP_STREAM ;
    //Event stream version
    private String STREAM_VERSION;
    String streamId;

    private static final int defaultThriftPort = 7611;
    private static final int defaultBinaryPort = 9611;

    public HttpStatValve() throws ParseXMLException {

        eventbuilder = new EventBuilder();

        LOG.debug("The HttpStatValve initialized.");

        //create dataPublisher to publish data
        dataPublisher = getDataPublisher();

        //retrieving stream name and version
        StreamConfig streamconfig = new StreamConfig();


        HTTP_STREAM = streamconfig.getStreamName();
        STREAM_VERSION = streamconfig.getStreamVersion();

        //generate streamID
        streamId = DataBridgeCommonsUtils.generateStreamId(HTTP_STREAM, STREAM_VERSION);
    }

    @Override
    public void invoke(Request request, Response response)  {

        Long startTime = System.currentTimeMillis();

        try {
            getNext().invoke(request, response);
        } catch (IOException e) {
            LOG.error("Invoke failed:" + e);
        } catch (ServletException e) {
            LOG.error("Invoke failed:" + e);
        }

        long responseTime = System.currentTimeMillis() - startTime;

        //Extracting the data from request and response and setting them to bean class
        WebappMonitoringEvent webappMonitoringEvent = prepareWebappMonitoringEventData(request, response, responseTime);

        //Time stamp of request initiated in the class
        webappMonitoringEvent.setTimestamp(startTime);

        if (LOG.isDebugEnabled()) {
            LOG.debug("publishing the HTTP Stat : " + webappMonitoringEvent);
        }

        try {

            //Event created
            Event event = eventbuilder.prepareEvent(streamId,webappMonitoringEvent);

            //Event published
            dataPublisher.publish(event);

        } catch (MonitoringPublisherException e) {
            LOG.error("Publishing failed:" + e);
        }

//        try {
//            dataPublisher.shutdown();
//        } catch (DataEndpointException e) {
//            LOG.error("Shutdown failed:" + e);
//        }
    }

    public static String getDataAgentConfigPath() {
        File filePath = new File("src" + File.separator + "main" + File.separator + "resources");
        if (!filePath.exists()) {
            filePath = new File("test" + File.separator + "resources");
        }
        if (!filePath.exists()) {
            filePath = new File("resources");
        }
        if (!filePath.exists()) {
            filePath = new File("test" + File.separator + "resources");
        }
        return filePath.getAbsolutePath() + File.separator + "data-agent-conf.xml";
    }


    private static String getProperty(String name, String def) {
        String result = System.getProperty(name);
        if (result == null || result.length() == 0 || result == "") {
            result = def;
        }
        return result;
    }


    private WebappMonitoringEvent prepareWebappMonitoringEventData(Request request,
                                                                   Response response,
                                                                   long responseTime) {

        WebappMonitoringEvent webappMonitoringEvent = new WebappMonitoringEvent();

        String requestedURI = request.getRequestURI();

        /*
        * Checks requested url null
        */
        if (requestedURI != null) {

            requestedURI = requestedURI.trim();
            String[] requestedUriParts = requestedURI.split(BACKSLASH);

           /*
            * If url start with /t/, the request comes to a tenant web app
            */
            if (requestedURI.startsWith("/t/")) {
                if (requestedUriParts.length >= 4) {
                    webappMonitoringEvent.setWebappName(requestedUriParts[4]);
                    webappMonitoringEvent.setWebappOwnerTenant(requestedUriParts[2]);
                }
            } else {
                webappMonitoringEvent.setWebappOwnerTenant(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
                if (!BACKSLASH.equals(requestedURI)) {
                    webappMonitoringEvent.setWebappName(requestedUriParts[1]);
                } else {
                    webappMonitoringEvent.setWebappName(BACKSLASH);
                }
            }

            String webappServletVersion = request.getContext().getEffectiveMajorVersion() + "." + request.getContext().getEffectiveMinorVersion();
            webappMonitoringEvent.setWebappVersion(webappServletVersion);

            String consumerName = extractUsername(request);
            webappMonitoringEvent.setUserId(consumerName);
            webappMonitoringEvent.setResourcePath(request.getPathInfo());
            webappMonitoringEvent.setRequestUri(request.getRequestURI());
            webappMonitoringEvent.setHttpMethod(request.getMethod());
            webappMonitoringEvent.setContentType(request.getContentType());
            webappMonitoringEvent.setResponseContentType(response.getContentType());
            webappMonitoringEvent.setResponseHttpStatusCode(response.getStatus());
            webappMonitoringEvent.setRemoteAddress(getClientIpAddress(request));
            webappMonitoringEvent.setReferrer(request.getHeader(WebappMonitoringPublisherConstants.REFERRER));
            webappMonitoringEvent.setRemoteUser(request.getRemoteUser());
            webappMonitoringEvent.setAuthType(request.getAuthType());
            webappMonitoringEvent.setCountry("-");
            webappMonitoringEvent.setResponseTime(responseTime);
            webappMonitoringEvent.setLanguage(request.getLocale().getLanguage());
            webappMonitoringEvent.setCountry(request.getLocale().getCountry());
            webappMonitoringEvent.setSessionId(extractSessionId(request));
            webappMonitoringEvent.setWebappDisplayName(request.getContext().getDisplayName());
            webappMonitoringEvent.setWebappContext(requestedURI);
            webappMonitoringEvent.setWebappType(WEBAPP);
            webappMonitoringEvent.setServerAddress(request.getServerName());
            webappMonitoringEvent.setServerName(request.getLocalName());
            webappMonitoringEvent.setRequestSizeBytes(request.getContentLength());
            webappMonitoringEvent.setResponseSizeBytes(response.getContentLength());
            parserUserAgent(request, webappMonitoringEvent);

        }
        return webappMonitoringEvent;
    }

    private String extractSessionId(Request request) {
        final HttpSession session = request.getSession(false);

        // CXF web services does not have a session id, because they are stateless
        return (session != null && session.getId() != null) ? session.getId() : "-";
    }

    private String extractUsername(Request request) {
        String consumerName;
        Principal principal = request.getUserPrincipal();
        if (principal != null) {
            consumerName = principal.getName();
        } else {
            consumerName = WebappMonitoringPublisherConstants.ANONYMOUS_USER;
        }
        return consumerName;
    }

    private void parserUserAgent(Request request, WebappMonitoringEvent webappMonitoringEvent) {
        String userAgent = request.getHeader(WebappMonitoringPublisherConstants.USER_AGENT);

        if (uaParser != null) {

            Client readableUserAgent = uaParser.parse(userAgent);

            webappMonitoringEvent.setUserAgentFamily(readableUserAgent.userAgent.family);
            webappMonitoringEvent.setUserAgentVersion(readableUserAgent.userAgent.major);
            webappMonitoringEvent.setOperatingSystem(readableUserAgent.os.family);
            webappMonitoringEvent.setOperatingSystemVersion(readableUserAgent.os.major);
            webappMonitoringEvent.setDeviceCategory(readableUserAgent.device.family);
        }
    }

    /*
    * Checks the remote address of the request. Server could be hiding behind a proxy or load balancer.
    * if we get only request.getRemoteAddr() will give only the proxy pr load balancer address.
    * For that we are checking the request forwarded address in the header of the request.
    */
    private String getClientIpAddress(Request request) {
        String ip = request.getHeader(WebappMonitoringPublisherConstants.X_FORWARDED_FOR);
        ip = tryNextHeaderIfIpNull(request, ip, WebappMonitoringPublisherConstants.PROXY_CLIENT_IP);
        ip = tryNextHeaderIfIpNull(request, ip, WebappMonitoringPublisherConstants.WL_PROXY_CLIENT_IP);
        ip = tryNextHeaderIfIpNull(request, ip, WebappMonitoringPublisherConstants.HTTP_CLIENT_IP);
        ip = tryNextHeaderIfIpNull(request, ip, WebappMonitoringPublisherConstants.HTTP_X_FORWARDED_FOR);

        if (ip == null || ip.length() == 0 || WebappMonitoringPublisherConstants.UNKNOWN.equalsIgnoreCase(ip)) {
            // Failed. remoteAddr is the only option
            ip = request.getRemoteAddr();
        }

        return ip;
    }

    // If the input param ip is invalid, it will return the value of the next header
    // as the output
    private String tryNextHeaderIfIpNull(Request request, String ip, String nextHeader) {
        if (ip == null || ip.length() == 0 || WebappMonitoringPublisherConstants.UNKNOWN.equalsIgnoreCase(ip)) {
            return request.getHeader(nextHeader);
        }
        return null;
    }

    private DataPublisher getDataPublisher() throws ParseXMLException {

        /*come out of the current directory*/
        File userDir = new File(System.getProperty("user.dir"));
        String parentDir = userDir.getAbsoluteFile().getParent();

        /*the resources folder is in tomcat*/
        System.setProperty("javax.net.ssl.trustStore", parentDir + "/resources/client-truststore.jks");
        System.setProperty("javax.net.ssl.trustStorePassword", "wso2carbon");

        //configuring the data agent
        AgentHolder.setConfigPath(getDataAgentConfigPath());

        String host = null;
        host = getLocalAddress().getHostAddress();


        //'type' denotes the type of data publisher that needs to be created
        String type = getProperty("type", "Thrift");
        int receiverPort = defaultThriftPort;
        if (type.equals("Binary")) {
            receiverPort = defaultBinaryPort;
        }
        int securePort = receiverPort + 100;

        //retrieving username and password
        CredentialsHandler credentials = new CredentialsHandler();
        String cred_username = credentials.getUsername();
        String cred_password = credentials.getPassword();

        String url = getProperty("url", "tcp://" + host + ":" + receiverPort);
        String authURL = getProperty("authURL", "ssl://" + host + ":" + securePort);
        String username = getProperty("username", cred_username);
        String password = getProperty("password", cred_password);

        //instantiating the datapublisher
        DataPublisher dataPublisher = null;
        try {
            dataPublisher = new DataPublisher(type, url, authURL, username, password);
        } catch (DataEndpointAgentConfigurationException e) {
            throw new ParseXMLException("Configuring Data Endpoint Agent failed",e);
        } catch (DataEndpointException e) {
            throw new ParseXMLException("Data Endpoint failed",e);
        } catch (DataEndpointConfigurationException e) {
            throw new ParseXMLException("Configuring Data Endpoint failed",e);
        } catch (DataEndpointAuthenticationException e) {
            throw new ParseXMLException("Data Endpoint Authentication failed",e);
        } catch (TransportException e) {
            throw new ParseXMLException("Transport failed",e);
        }

        return dataPublisher;
    }

    public static InetAddress getLocalAddress() throws ParseXMLException {
        Enumeration<NetworkInterface> ifaces = null;
        try {
            ifaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            throw new ParseXMLException("Connection failed",e);
        }
        while (ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();
            Enumeration<InetAddress> addresses = iface.getInetAddresses();

            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                    return addr;
                }
            }
        }
        try {
            return InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            throw new ParseXMLException("Unknown Local Host",e);
        }
    }
}
