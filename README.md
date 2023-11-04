# geoserver-iso-11783-part-10

This project contains a build for geoserver. It depends on the following two projects:

 * https://github.com/Incentergy-GmbH/jaxb-iso-11783-part-10
 * https://github.com/Incentergy-GmbH/geo-tools-iso-11783-part-10

```
git clone --recurse-submodules https://github.com/Incentergy-GmbH/geoserver-iso-11783-part-10.git
cd geoserver-iso-11783-part-10
mvn install
cd geoserver 
export JAVA_TOOL_OPTIONS="-Dorg.slf4j.simpleLogger.log.org.eclipse.jetty.annotations.AnnotationParser=ERROR"
mvn jetty:run
# go to http://localhost:8080/web/
# Username: admin Password: geoserver
# You will find the ISOXML plugin under:
# Stores -> Add new Store -> ISOXML in Memory
```

## Debugging

```
# Debugger will run on port 4000 and start will wait until you connected
export MAVEN_OPTS='-Xdebug -Xrunjdwp:transport=dt_socket,address=4000,server=y,suspend=y'
mvn jetty:run
```

For enabling cors, using the same workspace all the time, JWT token checking and auto configuration based on requests you can use the tomcat-cors-security profile.

```
mvn -P tomcat-cors-security install
```

![](img/new-data-store.png)
