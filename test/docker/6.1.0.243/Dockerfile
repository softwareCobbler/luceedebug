FROM lucee/lucee:6.1.0.243-light-nginx-tomcat9.0-jdk21-temurin-jammy

# "@LUCEEDEBUG_JAR" replaced programmatically with filename
ENV LUCEEDEBUG_JAR /build/@LUCEEDEBUG_JAR
ENV SETENV_FILE /usr/local/tomcat/bin/setenv.sh

RUN echo export CATALINA_OPTS='"''$CATALINA_OPTS' -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:9999'"' >> ${SETENV_FILE}
RUN echo export CATALINA_OPTS='"''$CATALINA_OPTS' -javaagent:${LUCEEDEBUG_JAR}=jdwpHost=localhost,jdwpPort=9999,cfHost=0.0.0.0,cfPort=10000,jarPath=${LUCEEDEBUG_JAR}'"' >> ${SETENV_FILE}
