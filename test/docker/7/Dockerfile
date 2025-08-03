FROM lucee/lucee:7.0.0.317-SNAPSHOT-nginx-tomcat11.0-jdk21-temurin-noble

# "@LUCEEDEBUG_JAR" replaced programmatically with filename
ENV LUCEEDEBUG_JAR /build/@LUCEEDEBUG_JAR
ENV SETENV_FILE /usr/local/tomcat/bin/setenv.sh

#RUN apt-get update
#RUN apt-get -y install vim

# build up catalina opts to include jdwp and luceedebug
RUN echo export CATALINA_OPTS='"''$CATALINA_OPTS' -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:9999'"' >> ${SETENV_FILE}
RUN echo export CATALINA_OPTS='"''$CATALINA_OPTS' -javaagent:${LUCEEDEBUG_JAR}=jdwpHost=localhost,jdwpPort=9999,cfHost=0.0.0.0,cfPort=10000,jarPath=${LUCEEDEBUG_JAR}'"' >> ${SETENV_FILE}
