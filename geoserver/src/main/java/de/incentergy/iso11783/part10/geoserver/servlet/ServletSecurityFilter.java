package de.incentergy.iso11783.part10.geoserver.servlet;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

import de.incentergy.iso11783.part10.geoserver.LazyLayerCreator;

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
        throws IOException, ServletException 
    {
		if (!(request instanceof HttpServletRequest && response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;
        String contextPath = httpServletRequest.getContextPath();

        if (httpServletRequest.getMethod().equals("OPTIONS") ||
            httpServletRequest.getRequestURI().startsWith(contextPath + "/web") ||
            httpServletRequest.getRequestURI().startsWith(contextPath + "/j_spring_security_check")
        ) {
            chain.doFilter(request, response);
            return;
        }

        String authHeaderVal = httpServletRequest.getHeader("Authorization");
        log.info("JWTAuthFilter.authHeaderVal: " + authHeaderVal);
        if (authHeaderVal == null || !authHeaderVal.startsWith("Bearer")) {
            log.info("Failed due to missing Authorization bearer token");
            httpServletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        try {
            String bearerToken = authHeaderVal.substring(7);
            DecodedJWT jwtPrincipal = validate(bearerToken);
            Claim arExternal_Id = jwtPrincipal.getClaim("ar_externalId");

            String gwcURIRoot = contextPath + "/gwc/service/tms/1.0.0/";

            if (!httpServletRequest.getRequestURI().startsWith(gwcURIRoot)) {
                chain.doFilter(request, response);
                return;
            }

            if (!arExternal_Id.isNull() && 
                !httpServletRequest.getRequestURI().startsWith(gwcURIRoot + "u" + arExternal_Id.asString())
            ) {
                httpServletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            log.info("Success\n");
            // Example Url
            // http://localhost:8080/geoserver/gwc/service/tms/1.0.0/u60a6de07-49b4-476c-9361-654955898f55:Partfield_20210305T08_18_27_taskdata@EPSG%3A900913@pbf/14/8539/10995.pbf
            String pathInfo = httpServletRequest.getPathInfo();
            log.info("Path info: "+pathInfo);
            Matcher m = EXTRACT_WORKSPACE_AND_LAYER.matcher(pathInfo);
            if (m.matches()) {
                String workspaceName = m.group(2);
                String layerName = m.group(3);
                log.info("Variables: "+workspaceName+" "+layerName);
                LazyLayerCreator.checkOrSetUpGeoServerWorkspaceStoreAndLayer(catalog, workspaceName, layerName, bearerToken);
            }
            chain.doFilter(request, response);
        } catch (Exception ex) {
            log.log(Level.WARNING, "Failed setting security context", ex);
            ex.printStackTrace();
            ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

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
