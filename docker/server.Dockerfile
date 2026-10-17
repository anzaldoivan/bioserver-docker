FROM openjdk:17.0.2-jdk-slim-bullseye

ARG SERVER_PATH
ARG RUN_FILE_PATH

COPY ./docker/deps/mysql-connector-j_8.0.32-1debian11_all.deb /tmp
COPY --chown=www-data:www-data --chmod=754 $RUN_FILE_PATH /var/www/run.sh
COPY --chown=www-data:www-data $SERVER_PATH /var/www/bioserver

WORKDIR /var/www

RUN dpkg --install /tmp/mysql-connector-j_8.0.32-1debian11_all.deb \
    && javac -cp /usr/share/java/mysql-connector-j-8.0.32.jar:. ./bioserver/*.java \
    && mkdir -p bin/bioserver \
    && mv bioserver/*.class bin/bioserver \
    && mkdir lib \
    && cp /usr/share/java/mysql-connector-j-8.0.32.jar lib/mysql-connector.jar \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fLsS --retry 3 https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar -o lib/sqlite-jdbc.jar \
    && curl -fLsS --retry 3 https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar -o lib/slf4j-api.jar \
    && apt-get purge -y curl \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/* \
    && rm /tmp/mysql-connector-j_8.0.32-1debian11_all.deb

ENTRYPOINT [ "sh", "-c", "/var/www/run.sh" ]



