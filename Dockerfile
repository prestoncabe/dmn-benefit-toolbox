FROM quay.io/kiegroup/kogito-swf-builder:latest AS builder

WORKDIR $KOGITO_HOME

COPY ./pom.xml ./
COPY ./src ./src

RUN $MAVEN_HOME/bin/mvn clean package

FROM quay.io/kiegroup/kogito-runtime-jvm:latest

ENV RUNTIME_TYPE quarkus

COPY --from=builder /home/kogito/target/quarkus-app/lib/ $KOGITO_HOME/bin/lib/
COPY --from=builder /home/kogito/target/quarkus-app/*.jar $KOGITO_HOME/bin
COPY --from=builder /home/kogito/target/quarkus-app/app/ $KOGITO_HOME/bin/app/
COPY --from=builder /home/kogito/target/quarkus-app/quarkus/ $KOGITO_HOME/bin/quarkus/