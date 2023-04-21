#!/bin/bash

mvn clean package -Dmaven.test.skip=true

OTELCOL_FILE=otelcol
OTELCOL_CONTRIB_FILE=otelcol-contrib
  wget https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.75.0/otelcol_0.75.0_linux_amd64.tar.gz
  wget https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.75.0/otelcol-contrib_0.75.0_linux_amd64.tar.gz
  tar -zxvf otelcol_0.75.0_linux_amd64.tar.gz
  tar -zxvf otelcol-contrib_0.75.0_linux_amd64.tar.gz


export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=otlp
export OTEL_RESOURCE_ATTRIBUTES=service.name=observability-poc,service.version=1.0

java -jar target/observability-poc-1.0.jar
chmod +x otelcol-contrib
./otelcol-contrib --config=config.yaml