package de.incentergy.iso11783.part10.geoserver.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

import javax.servlet.http.HttpServlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

import de.incentergy.iso11783.part10.geoserver.LazyLayerCreator;

import org.geoserver.catalog.Catalog;

public class CreateLayersServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String[] LAYER_NAMES = {"Partfield", "TimeLog", "Grid", "GuidancePattern"};

	private String jwtSecret = System.getenv("GEOSERVER_JWT_SECRET");

	@Autowired
	Catalog catalog;

	@Override
	public void init() throws ServletException {
		SpringBeanAutowiringSupport.processInjectionBasedOnCurrentContext(this);
	}

    public void doGet(HttpServletRequest request, HttpServletResponse response) 
	                     throws ServletException, IOException
    {
        String bearerToken = request.getHeader("Authorization").substring(7);

        DecodedJWT jwtPrincipal = validate(bearerToken);
        Claim arExternal_Id = jwtPrincipal.getClaim("ar_externalId");

        String filename = request.getParameter("filename");
        // String endpointId = request.getParameter("endpointId");

        String filenameProcessed = filename.replaceAll("-", "").replaceAll(".zip", "");

        for (String layerName: LAYER_NAMES) {
            LazyLayerCreator.checkOrSetUpGeoServerWorkspaceStoreAndLayer(
                catalog,
                "u" + arExternal_Id.asString(),
                layerName + "_" + filenameProcessed,
                bearerToken
            );
        }

		response.setContentType("application/json");
		PrintWriter out = response.getWriter();
		out.println("{\"status\": \"ok\"}");
	}

	private DecodedJWT validate(String bearerToken) throws IllegalArgumentException, UnsupportedEncodingException {
		Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
		JWTVerifier verifier = JWT.require(algorithm).build(); // Reusable verifier instance
		DecodedJWT jwt = verifier.verify(bearerToken);
		return jwt;
	}
}
