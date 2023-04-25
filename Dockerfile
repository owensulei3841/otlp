FROM ubuntu:22.04

MAINTAINER lei su <kevinsu3841@gmail.com>

RUN apt-get update && apt-get install -y wget

# Set the working directory for the container
WORKDIR /app

# Download and install the OpenTelemetry Collector
RUN wget https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.75.0/otelcol-contrib_0.75.0_linux_amd64.tar.gz && \
    tar -zxvf otelcol-contrib_0.75.0_linux_amd64.tar.gz && \
    chmod +x otelcol-contrib

# Copy the config.yaml file to the container
COPY config.yaml /app/config.yaml

# Copy the SpringBoot project to the container
COPY target/observability-poc-1.0.jar /app/observability-poc-1.0.jar

# Expose the port that the SpringBoot project will be running on
EXPOSE 8080

# Start the OpenTelemetry Collector and the SpringBoot project
CMD ./otelcol-contrib --config /app/config.yaml && \
    java -jar observability-poc-1.0.jar
