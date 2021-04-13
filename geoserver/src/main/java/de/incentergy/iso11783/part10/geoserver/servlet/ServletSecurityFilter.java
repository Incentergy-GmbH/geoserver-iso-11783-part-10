package de.incentergy.iso11783.part10.geoserver.servlet;

import static org.geoserver.gwc.GWC.tileLayerName;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.ProjectionPolicy;
import org.geoserver.catalog.PublishedType;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.impl.DataStoreInfoImpl;
import org.geoserver.catalog.impl.LayerInfoImpl;
import org.geoserver.catalog.impl.WorkspaceInfoImpl;
import org.geoserver.gwc.GWC;
import org.geoserver.gwc.layer.GeoServerTileLayer;
import org.geoserver.gwc.layer.GeoServerTileLayerInfo;
import org.geoserver.gwc.layer.GeoServerTileLayerInfoImpl;
import org.geotools.data.DataAccess;
import org.geotools.feature.NameImpl;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geowebcache.grid.GridSetBroker;
import org.geowebcache.mime.MimeException;
import org.geowebcache.mime.MimeType;
import org.opengis.feature.Feature;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.Name;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

public class ServletSecurityFilter implements Filter {

	private static Logger log = Logger.getLogger(ServletSecurityFilter.class.getName());

	String jwtSecret = System.getenv("GEOSERVER_JWT_SECRET");
	String webDavRoot = System.getenv("GEOSERVER_WEBDAV_ROOT");

	Pattern EXTRACT_WORKSPACE_AND_LAYER = Pattern.compile("(/gwc)?/service/tms/1\\.0\\.0/([^:]*):([^@]*)@.*");

	@Autowired
	Catalog catalog;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		SpringBeanAutowiringSupport.processInjectionBasedOnCurrentContext(this);
		log.info("ServletSecurityFilter.init GEOSERVER_JWT_SECRET: "+jwtSecret+" GEOSERVER_WEBDAV_ROOT: "+webDavRoot);
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
			HttpServletRequest httpServletRequest = ((HttpServletRequest) request);
			if(httpServletRequest.getMethod().equals("OPTIONS")) {
			    chain.doFilter(request, response);
			    return;
			}
			String contextPath = httpServletRequest.getContextPath();
			// only do validation if we are in a workspace folder
			if (!httpServletRequest.getRequestURI().startsWith(contextPath + "/web")
					&& !httpServletRequest.getRequestURI().startsWith(contextPath + "/j_spring_security_check")) {
				String authHeaderVal = ((HttpServletRequest) request).getHeader("Authorization");
				log.info("JWTAuthFilter.authHeaderVal: " + authHeaderVal);
				if (authHeaderVal != null && authHeaderVal.startsWith("Bearer")) {
					try {
						String bearerToken = authHeaderVal.substring(7);
						DecodedJWT jwtPrincipal = validate(bearerToken);
						Claim arExternal_Id = jwtPrincipal.getClaim("ar_externalId");
						if (!arExternal_Id.isNull() && !httpServletRequest.getRequestURI()
								.startsWith(contextPath + "/" + arExternal_Id.asString())) {
							((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
						} else {
							log.info("Success\n");
							// Example Url
							// http://localhost:8080/geoserver/gwc/service/tms/1.0.0/u60a6de07-49b4-476c-9361-654955898f55:Partfield_20210305T08_18_27_taskdata@EPSG%3A900913@pbf/14/8539/10995.pbf
							if (httpServletRequest.getRequestURI().startsWith(contextPath + "/gwc")) {
								// e.g.
								// /service/tms/1.0.0/u60a6de07-49b4-476c-9361-654955898f55:Partfield_20210305T08_18_27_taskdata@EPSG%3A900913@pbf/14/8539/10995.pbf
								String pathInfo = httpServletRequest.getPathInfo();
								log.info("Path info: "+pathInfo);
								Matcher m = EXTRACT_WORKSPACE_AND_LAYER.matcher(pathInfo);
								if (m.matches()) {
									String workspaceName = m.group(2);
									String layerName = m.group(3);
									log.info("Variables: "+workspaceName+" "+layerName);
									checkOrSetUpGeoServerWorkspaceStoreAndLayer(workspaceName, layerName, bearerToken);
								}
							} else {
								String layers = ((HttpServletRequest) request).getParameter("layers");
								String typeName = ((HttpServletRequest) request).getParameter("typeName");

								checkOrSetUpGeoServerWorkspaceStoreAndLayer("u" + arExternal_Id.asString(),
										layers != null ? layers : typeName, bearerToken);

							}
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

	private synchronized void checkOrSetUpGeoServerWorkspaceStoreAndLayer(String workspaceName, String layerName,
			String bearerToken) {
		String workspaceNamespace = "https://www.isoxml-service.de/" + workspaceName;
		NamespaceInfo namespace = catalog.getFactory().createNamespace();
		namespace.setPrefix(workspaceName);
		namespace.setURI(workspaceNamespace);

		WorkspaceInfo workspaceInfo = catalog.getWorkspaceByName(workspaceName);
		if (workspaceName == null) {
			workspaceName = "null";
		}
		// check if workspace exists
		if (workspaceInfo == null) {
			workspaceInfo = catalog.getFactory().createWorkspace();
			workspaceInfo.setName(workspaceName);
			// create workspace
			namespace.setIsolated(false);
			catalog.add(workspaceInfo);
			catalog.add(namespace);

			// create data store
			DataStoreInfo dataStoreInfo = catalog.getFactory().createDataStore();
			dataStoreInfo.setName("ISOXML");
			dataStoreInfo.getConnectionParameters().put("isoxmlUrl",
					webDavRoot + workspaceName.substring(1) + "/INCOMING/");
			dataStoreInfo.getConnectionParameters().put("authorization_header_bearer", bearerToken);
			dataStoreInfo.setEnabled(true);
			dataStoreInfo.setWorkspace(workspaceInfo);
			catalog.add(dataStoreInfo);
		}
		DataStoreInfo dataStoreInfo = catalog.getDataStoreByName(workspaceInfo, "ISOXML");
		DataAccess<? extends FeatureType, ? extends Feature> dataAccess = null;
		try {
			dataAccess = dataStoreInfo.getDataStore(null);
		} catch (IOException e) {
			log.log(Level.WARNING, "Could not create workspace", e);
		}

        Name fullLayerName = new NameImpl(workspaceNamespace, layerName);

		LayerInfo layerInfo = catalog.getLayerByName(fullLayerName);
		// check if layers exists
		if (layerInfo == null) {
			LayerInfo layer = new LayerInfoImpl();
			FeatureType featureType;
			try {
				featureType = dataAccess.getSchema(fullLayerName);
				if (featureType != null) {
					FeatureTypeInfo featureTypeInfo = catalog.getFactory().createFeatureType();
					featureTypeInfo.setEnabled(true);
					featureTypeInfo.setName(layerName);
					featureTypeInfo.setEnabled(true);
					featureTypeInfo.setNamespace(catalog.getNamespaceByURI(workspaceNamespace));
					CoordinateReferenceSystem crs = CRS.decode("EPSG:4326");
					featureTypeInfo.setNativeCRS(crs);
					featureTypeInfo.setSRS("EPSG:4326");

                    ReferencedEnvelope re = dataAccess
                        .getFeatureSource(new NameImpl(workspaceNamespace, layerName))
                        .getBounds();
                    if (re == null) {
					    re = new ReferencedEnvelope(-180.0, 180.0, -90.0, 90.0, crs);
                    }
					featureTypeInfo.setNativeBoundingBox(re);
					featureTypeInfo.setLatLonBoundingBox(re);
					featureTypeInfo.setStore(dataStoreInfo);
					featureTypeInfo.setProjectionPolicy(ProjectionPolicy.NONE);
					catalog.add(featureTypeInfo);
					layer.setResource(featureTypeInfo);
					layer.setName(layerName);
					layer.setType(PublishedType.VECTOR);
					layer.setAdvertised(true);
					layer.setEnabled(true);
					catalog.add(layer);

					final GWC gwc = GWC.get();
					final boolean tileLayerExists = gwc.hasTileLayer(layer);
					if (tileLayerExists) {
						GeoServerTileLayer tileLayer = (GeoServerTileLayer) gwc
								.getTileLayerByName(workspaceName + ":" + layer.getName());
						tileLayer.getInfo().getMimeFormats().add("application/vnd.mapbox-vector-tile");
						gwc.save(tileLayer);
					}
				} else {
					log.warning("Did not find featureType: " + layerName);
				}
			} catch (IOException | FactoryException e) {
				log.log(Level.WARNING, "Could not create layer", e);
			}
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
