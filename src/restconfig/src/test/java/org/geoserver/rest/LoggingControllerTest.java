/* (c) 2022 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.rest;

import static org.custommonkey.xmlunit.XMLAssert.assertXpathEvaluatesTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.logging.Level;
import net.sf.json.JSON;
import net.sf.json.JSONObject;
import org.geoserver.config.GeoServer;
import org.geoserver.config.LoggingInfo;
import org.geoserver.config.impl.LoggingInfoImpl;
import org.geoserver.ows.LocalWorkspace;
import org.geoserver.rest.catalog.CatalogRESTTestSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.w3c.dom.Document;

public class LoggingControllerTest extends CatalogRESTTestSupport {

    protected GeoServer geoServer;

    @Before
    public void init() {
        geoServer = getGeoServer();
        LoggingInfo loggingInfo = new LoggingInfoImpl();
        loggingInfo.setLocation("logs/geoserver-test.log");
        loggingInfo.setLevel("TEST_LOGGING.xml");
        loggingInfo.setStdOutLogging(true);

        geoServer.setLogging(loggingInfo);
    }

    @After
    public void reset() throws Exception {
        LocalWorkspace.remove();
    }

    @Test
    public void testGetLoggingAsJSON() throws Exception {
        JSON json = getAsJSON(RestBaseController.ROOT_PATH + "/logging.json");
        if (LOGGER.isLoggable(Level.FINE)) {
            print(json);
        }
        JSONObject jsonObject = (JSONObject) json;
        assertNotNull(jsonObject);
        JSONObject loggingInfo = jsonObject.getJSONObject("logging");
        assertNotNull(loggingInfo);
        assertEquals("TEST_LOGGING.xml", loggingInfo.get("level"));
        assertEquals("logs/geoserver-test.log", loggingInfo.get("location"));
        assertEquals(true, loggingInfo.get("stdOutLogging"));
    }

    @Test
    public void testGetLoggingAsXML() throws Exception {
        Document dom = getAsDOM(RestBaseController.ROOT_PATH + "/logging.xml");
        assertEquals("logging", dom.getDocumentElement().getLocalName());
        assertXpathEvaluatesTo("TEST_LOGGING.xml", "/logging/level", dom);
        assertXpathEvaluatesTo("logs/geoserver-test.log", "/logging/location", dom);
        assertXpathEvaluatesTo("true", "/logging/stdOutLogging", dom);
    }

    @Test
    public void testGetLoggingAsHTML() throws Exception {
        getAsDOM(RestBaseController.ROOT_PATH + "/logging.html", 200);
    }

    @Test
    public void testPutLoggingAsJSON() throws Exception {
        String inputJson =
                "{'logging':{"
                        + "    'level':'DEFAULT_LOGGING.xml',"
                        + "    'location':'logs/geoserver-test-2.log',"
                        + "    'stdOutLogging':false}}";
        MockHttpServletResponse response =
                putAsServletResponse(
                        RestBaseController.ROOT_PATH + "/logging", inputJson, "text/json");
        assertEquals(200, response.getStatus());
        JSON jsonMod = getAsJSON(RestBaseController.ROOT_PATH + "/logging.json");
        JSONObject jsonObject = (JSONObject) jsonMod;
        assertNotNull(jsonObject);
        JSONObject loggingInfo = jsonObject.getJSONObject("logging");
        assertNotNull(loggingInfo);
        assertEquals("DEFAULT_LOGGING.xml", loggingInfo.get("level"));
        assertEquals("logs/geoserver-test-2.log", loggingInfo.get("location"));
        assertEquals(false, loggingInfo.get("stdOutLogging"));
    }

    @Test
    public void testPutLoggingAsXML() throws Exception {
        String xml =
                "<logging> <level>DEFAULT_LOGGING.xml</level>"
                        + "<location>logs/geoserver-test-2.log</location>"
                        + "<stdOutLogging>false</stdOutLogging> </logging>";
        MockHttpServletResponse response =
                putAsServletResponse(RestBaseController.ROOT_PATH + "/logging", xml, "text/xml");
        assertEquals(200, response.getStatus());

        Document dom = getAsDOM(RestBaseController.ROOT_PATH + "/logging.xml");
        assertEquals("logging", dom.getDocumentElement().getLocalName());
        assertXpathEvaluatesTo("DEFAULT_LOGGING.xml", "/logging/level", dom);
        assertXpathEvaluatesTo("logs/geoserver-test-2.log", "/logging/location", dom);
        assertXpathEvaluatesTo("false", "/logging/stdOutLogging", dom);
    }
}
