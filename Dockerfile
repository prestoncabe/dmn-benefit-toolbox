# Use Kogito builder image with Java 17
FROM quay.io/kiegroup/kogito-swf-builder-nightly:latest AS builder

WORKDIR $KOGITO_HOME

# Copy the Maven configuration and source code
COPY ./pom.xml ./
COPY ./src ./src

# Set JAVA_HOME and PATH for Java 17
ENV JAVA_HOME=/usr/lib/jvm/java-17
ENV PATH=$JAVA_HOME/bin:$PATH

# Build the application
RUN $MAVEN_HOME/bin/mvn clean package -Dmaven.compiler.release=17

# Use Kogito runtime image with Java 17
FROM quay.io/kiegroup/kogito-runtime-jvm-nightly:latest

ENV RUNTIME_TYPE quarkus
ENV JAVA_HOME=/usr/lib/jvm/java-17
ENV PATH=$JAVA_HOME/bin:$PATH

# Copy the built application
COPY --from=builder /home/kogito/target/quarkus-app/lib/ $KOGITO_HOME/bin/lib/
COPY --from=builder /home/kogito/target/quarkus-app/*.jar $KOGITO_HOME/bin
COPY --from=builder /home/kogito/target/quarkus-app/app/ $KOGITO_HOME/bin/app/
COPY --from=builder /home/kogito/target/quarkus-app/quarkus/ $KOGITO_HOME/bin/quarkus/