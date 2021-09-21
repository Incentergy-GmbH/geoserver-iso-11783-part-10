package de.incentergy.iso11783.part10.geoserver;

import java.io.IOException;
import java.io.Serializable;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.ProjectionPolicy;
import org.geoserver.catalog.PublishedType;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.impl.LayerInfoImpl;
import org.geoserver.gwc.GWC;
import org.geoserver.gwc.layer.GeoServerTileLayer;
import org.geoserver.security.decorators.ReadOnlyDataStore;
import org.geotools.data.DataAccess;
import org.geotools.feature.NameImpl;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.opengis.feature.Feature;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.Name;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import de.incentergy.iso11783.part10.geotools.ISO11783DataStore;

public class LazyLayerCreator {

	private static String webDavRoot = System.getenv("GEOSERVER_WEBDAV_ROOT");
    private static String uploadFolder = System.getenv("GEOSERVER_UPLOAD_FOLDER");

    private static Logger log = Logger.getLogger(LazyLayerCreator.class.getName());

	public static synchronized void checkOrSetUpGeoServerWorkspaceStoreAndLayer(
        Catalog catalog,
        String workspaceName,
        String layerName,
        String bearerToken
    ) {
		String workspaceNamespace = "https://www.isoxml-service.de/" + workspaceName;
		NamespaceInfo namespace = catalog.getFactory().createNamespace();
		namespace.setPrefix(workspaceName);
		namespace.setURI(workspaceNamespace);

		WorkspaceInfo workspaceInfo = catalog.getWorkspaceByName(workspaceName);

		// check if workspace exists
		if (workspaceInfo == null) {
			workspaceInfo = catalog.getFactory().createWorkspace();
			workspaceInfo.setName(workspaceName);
			// create workspace
			namespace.setIsolated(false);
			catalog.add(workspaceInfo);
			catalog.add(namespace);

			// create data store for WebDAV
			DataStoreInfo dataStoreInfo = catalog.getFactory().createDataStore();
			dataStoreInfo.setName("ISOXML");
			dataStoreInfo.getConnectionParameters().put("isoxmlUrl",
					LazyLayerCreator.webDavRoot + workspaceName.substring(1) + "/INCOMING/");
			dataStoreInfo.getConnectionParameters().put("authorization_header_bearer", bearerToken);
			dataStoreInfo.setEnabled(true);
			dataStoreInfo.setWorkspace(workspaceInfo);
			catalog.add(dataStoreInfo);

			// create data store for local files
			DataStoreInfo localDataStoreInfo = catalog.getFactory().createDataStore();
			localDataStoreInfo.setName("ISOXMLLocal");
			localDataStoreInfo.getConnectionParameters().put("isoxmlUrl",
                "file://" + uploadFolder + workspaceName.substring(1) + "/");
			localDataStoreInfo.getConnectionParameters().put("authorization_header_bearer", bearerToken);
			localDataStoreInfo.setEnabled(true);
			localDataStoreInfo.setWorkspace(workspaceInfo);
			catalog.add(localDataStoreInfo);
		} else {
			// Update bearer token in data store
			DataStoreInfo dataStoreInfo = catalog.getDataStoreByName(workspaceInfo, "ISOXML");
            Serializable currentBearer = dataStoreInfo.getConnectionParameters().get("authorization_header_bearer");
            if (!currentBearer.equals(bearerToken)) {
                try {
                    ReadOnlyDataStore dataStoreWrapper = (ReadOnlyDataStore)dataStoreInfo.getDataStore(null);
                    ISO11783DataStore dataStore = dataStoreWrapper.unwrap(ISO11783DataStore.class);
                    dataStore.updateBearerToken(bearerToken);
                } catch (IOException e) {
                    log.log(Level.WARNING, "Can't find the schema", e);
                }
                dataStoreInfo.getConnectionParameters().put("authorization_header_bearer", bearerToken);
                catalog.save(dataStoreInfo);
            }
		}
        Name fullLayerName = new NameImpl(workspaceNamespace, layerName);

		LayerInfo layerInfo = catalog.getLayerByName(fullLayerName);

		if (layerInfo != null) {
            return;
        }

        FeatureType featureType = null;
		DataStoreInfo dataStoreInfo = null;
		DataAccess<? extends FeatureType, ? extends Feature> dataAccess = null;
		try {
            dataStoreInfo = catalog.getDataStoreByName(workspaceInfo, "ISOXML");
			dataAccess = dataStoreInfo.getDataStore(null);

            Name nameWebDav = dataAccess.getNames().stream()
                .filter(name -> name.getLocalPart().equals(fullLayerName.getLocalPart()))
                .findAny().orElse(null);

            if (nameWebDav != null) {
                featureType = dataAccess.getSchema(fullLayerName);
            } else {
                dataStoreInfo = catalog.getDataStoreByName(workspaceInfo, "ISOXMLLocal");
                dataAccess = dataStoreInfo.getDataStore(null);

                Name nameLocal = dataAccess.getNames().stream()
                    .filter(name -> name.getLocalPart().equals(fullLayerName.getLocalPart()))
                    .findAny().orElse(null);

                if (nameLocal != null) {
                    featureType = dataAccess.getSchema(fullLayerName);
                } else {
                    log.log(Level.WARNING, "Can't find the schema");
                }
            }
		} catch (IOException e) {
			log.log(Level.WARNING, "Can't find the schema", e);
		}

        // create layer
        LayerInfo layer = new LayerInfoImpl();
        try {
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
