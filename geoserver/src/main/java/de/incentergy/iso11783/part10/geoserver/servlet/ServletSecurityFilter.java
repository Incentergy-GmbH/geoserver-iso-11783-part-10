package de.incentergy.iso11783.part10.geoserver.servlet;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.PublishedType;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.impl.DataStoreInfoImpl;
import org.geoserver.catalog.impl.LayerInfoImpl;
import org.geoserver.catalog.impl.WorkspaceInfoImpl;

import de.incentergy.iso11783.part10.geoserver.spring.SpringContext;

public class ServletSecurityFilter implements Filter {

    private static Logger log = Logger.getLogger(ServletSecurityFilter.class.getName());

    String jwtSecret = System.getenv("GEOSERVER_JWT_SECRET");
    String webDavRoot = System.getenv("GEOSERVER_WEBDAV_ROOT");

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
            // only do validation if we are in the webdav data folder
            if (!((HttpServletRequest) request).getRequestURI().startsWith("/web")) {
                String authHeaderVal = ((HttpServletRequest) request).getHeader("Authorization");
                log.fine("JWTAuthFilter.authHeaderVal: " + authHeaderVal);
                if (authHeaderVal != null && authHeaderVal.startsWith("Bearer")) {
                    try {
                        String bearerToken = authHeaderVal.substring(7);
                        DecodedJWT jwtPrincipal = validate(bearerToken);
                        Claim arExternal_Id = jwtPrincipal.getClaim("ar_externalId");
                        if (!arExternal_Id.isNull() && !((HttpServletRequest) request).getRequestURI()
                                .startsWith("/" + arExternal_Id.asString())) {
                            ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        } else {
                            log.fine("Success\n");

                            String layers = ((HttpServletRequest) request).getParameter("layers");
                            String typeName = ((HttpServletRequest) request).getParameter("typeName");

                            checkOrSetUpGeoServerWorkspaceStoreAndLayer(arExternal_Id.asString(),
                                    layers != null ? layers : typeName, bearerToken);

                            chain.doFilter(request, response);
                        }
                    } catch (Exception ex) {
                        log.log(Level.WARNING, "Failed setting security context", ex);
                        ex.printStackTrace();
                        ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

                    }
                } else {
                    log.info("Failed due to missing Authorization bearer token");
                    ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                }
            } else {
                chain.doFilter(request, response);
            }
        }

    }

    private void checkOrSetUpGeoServerWorkspaceStoreAndLayer(String workspaceName, String layerName, String bearerToken) {
        Catalog catalog = SpringContext.getBean(Catalog.class);
        WorkspaceInfo workspaceInfo = catalog.getWorkspaceByName(workspaceName);
        // check if workspace exists
        if (workspaceInfo == null) {
            // create workspace
            WorkspaceInfo workspace = new WorkspaceInfoImpl();
            workspace.setName(workspaceName);
            catalog.add(workspace);

            try {
                // create data store
                DataStoreInfo dataStoreInfo = new DataStoreInfoImpl(catalog, "ISOXML");
                dataStoreInfo.getConnectionParameters().put("isoxmlUrl", new URL(webDavRoot + workspaceName));
                dataStoreInfo.getConnectionParameters().put("authorization_header_bearer", bearerToken);
                dataStoreInfo.setEnabled(true);
                dataStoreInfo.setWorkspace(workspaceInfo);
                catalog.add(dataStoreInfo);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }

        LayerInfo layerInfo = catalog.getLayerByName(layerName);
        // check if layers exists
        if(layerInfo == null) {
            LayerInfo layer = new LayerInfoImpl();
            FeatureTypeInfo featureType = catalog.getFeatureTypeByName(layerName);
            layer.setResource(featureType);
            layer.setName(layerName.split(":")[1]);
            layer.setType(PublishedType.VECTOR);
            layer.setEnabled(true);
            catalog.add(layer);
        }
    }

    private DecodedJWT validate(String bearerToken) throws IllegalArgumentException, UnsupportedEncodingException {
		Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
		JWTVerifier verifier = JWT.require(algorithm).build(); // Reusable verifier instance
		DecodedJWT jwt = verifier.verify(bearerToken);
		return jwt;
	}

	@Override
	public void destroy() {

	}
    
}
