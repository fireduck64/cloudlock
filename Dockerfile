FROM debian:stable AS build

ARG DEBIAN_FRONTEND=noninteractive
RUN mkdir -p /usr/share/man/man1
RUN apt-get update
RUN apt-get install -y gnupg git default-jdk-headless curl
RUN echo "deb [arch=amd64] http://storage.googleapis.com/bazel-apt stable jdk1.8" > /etc/apt/sources.list.d/bazel.list
RUN curl https://bazel.build/bazel-release.pub.gpg | apt-key add -
RUN apt-get update
RUN apt-get install -y bazel

COPY .git /cloudlock/.git

WORKDIR /cloudlock
RUN git checkout .
RUN bazel build :all :CloudLock_deploy.jar

FROM sigp/lighthouse:latest AS run

RUN apt-get update
RUN mkdir -p /usr/share/man/man1
RUN apt-get install -y --no-install-recommends default-jre-headless
COPY --from=build /cloudlock/bazel-bin/CloudLock_deploy.jar /cloudlock/
COPY script/cloudlock /usr/local/bin


