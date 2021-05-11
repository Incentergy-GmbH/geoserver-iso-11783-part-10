package de.incentergy.iso11783.part10.geoserver.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.geoserver.catalog.Catalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

public class UploadFileServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
	private String uploadFolder = System.getenv("GEOSERVER_UPLOAD_FOLDER");
    private String jwtSecret = System.getenv("GEOSERVER_JWT_SECRET");

	@Autowired
	Catalog catalog;
    
	@Override
	public void init() throws ServletException {
		SpringBeanAutowiringSupport.processInjectionBasedOnCurrentContext(this);
	}

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        String bearerToken = request.getHeader("Authorization").substring(7);
        DecodedJWT jwtPrincipal = validate(bearerToken);
        Claim arExternal_Id = jwtPrincipal.getClaim("ar_externalId");

        Files.createDirectories(Paths.get(uploadFolder, arExternal_Id.asString()));

        try {
            List<FileItem> items = new ServletFileUpload(new DiskFileItemFactory()).parseRequest(request);
            for (FileItem item : items) {
                if (!item.isFormField()) {
                    // Process form file field (input type="file").
                    InputStream fileContent = item.getInputStream();
                    UUID uuid = UUID.randomUUID();

                    Path fullFilename = Paths.get(uploadFolder, arExternal_Id.asString(), uuid + ".zip");

                    File targetFile = new File(fullFilename.toString());

                    FileUtils.copyInputStreamToFile(fileContent, targetFile);

                    response.setContentType("application/json");
                    PrintWriter out = response.getWriter();
                    out.println("{\"uuid\": \"" + uuid + "\"}");
                    return;
                }
            }
        } catch (FileUploadException e) {
            throw new ServletException("Cannot parse multipart request.", e);
        }

        // ...
    }

    private DecodedJWT validate(String bearerToken) throws IllegalArgumentException, UnsupportedEncodingException {
		Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
		JWTVerifier verifier = JWT.require(algorithm).build(); // Reusable verifier instance
		DecodedJWT jwt = verifier.verify(bearerToken);
		return jwt;
	}
}
