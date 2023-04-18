package tutorial.buildon.gcp.o11y;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongUpDownCounter;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import static tutorial.buildon.gcp.o11y.Constants.*;
import static java.lang.Runtime.*;

import javax.annotation.PostConstruct;

@RestController
public class ObservabilityAppController {

    private static final Logger log =
            LoggerFactory.getLogger(ObservabilityAppController.class);

    @Value("otel.traces.api.version")
    private String tracesApiVersion;

    @Value("otel.metrics.api.version")
    private String metricsApiVersion;

    private final Tracer tracer =
            GlobalOpenTelemetry.getTracer("io.opentelemetry.traces.observability-poc",
                    tracesApiVersion);

    private final Meter meter =
            GlobalOpenTelemetry.meterBuilder("io.opentelemetry.metrics.observability-poc")
                    .setInstrumentationVersion(metricsApiVersion)
                    .build();

    //   Execution Counter
    private LongCounter numberOfExecutions;

    //   Exception Counter
    private LongCounter exceptionCounter;

    // when call "/nestedSpan1" api, upAndDownCounter +1,
    // when call "/nestedSpan2" api, upAndDownCounter -1
    private LongCounter upAndDownCounter;

    private ObservableLongCounter asyncCounter;

    private static DoubleHistogram simpleSpanLatencyHistogram;

    private static DoubleHistogram nestedSpan1LatencyHistogram;

    private static DoubleHistogram nestedSpan2LatencyHistogram;

    private static ObservableLongUpDownCounter asyncUpDownCounter;

    @PostConstruct
    public void createMetrics() {
        /*
         * Instrument "unit" rule
         * https://opentelemetry.io/docs/reference/specification/metrics/api/#instrument-unit
         */
        numberOfExecutions =
                meter
                        .counterBuilder(NUMBER_OF_EXEC_NAME)
                        .setDescription(NUMBER_OF_EXEC_DESCRIPTION)
                        .setUnit("int")
                        .build();

        exceptionCounter =
                meter
                        .counterBuilder(NUMBER_OF_EXCEPTION_NAME)
                        .setDescription(NUMBER_OF_EXCEPTION_DESCRIPTION)
                        .setUnit("int")
                        .build();

        upAndDownCounter =
                meter
                        .counterBuilder(NUMBER_OF_UP_AND_DOWN_COUNTER_NAME)
                        .setDescription(NUMBER_OF_UP_AND_DOWN_COUNTER_DESCRIPTION)
                        .setUnit("int")
                        .build();

        // async counter
        asyncCounter = meter
                .counterBuilder(NUMBER_OF_ASYNC_COUNTER_NAME)
                .setDescription(NUMBER_OF_ASYNC_COUNTER_DESCRIPTION)
                .buildWithCallback(measurement -> {
                    measurement.record(getRuntime().availableProcessors(), Attributes.empty());
                });

        // latency histogram for simpleSpan api
        simpleSpanLatencyHistogram = meter
                .histogramBuilder(SIMPLE_SPAN_LATENCY_NAME)
                .setDescription(SIMPLE_SPAN_LATENCY_DESCRIPTION)
                .build();

        // latency histogram for nestedSpan1 api
        nestedSpan1LatencyHistogram = meter
                .histogramBuilder(NESTED_SPAN1_LATENCY_NAME)
                .setDescription(NESTED_SPAN1_LATENCY_DESCRIPTION)
                .build();

        // latency histogram for nestedSpan2 api
        nestedSpan2LatencyHistogram = meter
                .histogramBuilder(NESTED_SPAN2_LATENCY_NAME)
                .setDescription(NESTED_SPAN2_LATENCY_DESCRIPTION)
                .build();

        // async upDown counter
        asyncUpDownCounter = meter
                .upDownCounterBuilder(NUMBER_OF_ASYNC_UP_AND_DOWN_COUNTER_NAME)
                .setDescription(NUMBER_OF_ASYNC_UP_AND_DOWN_COUNTER_DESCRIPTION)
                .buildWithCallback(measurement -> {
                    measurement.record(new Random().nextInt(10000), Attributes.empty());
                });

        meter.histogramBuilder("http.response.latency")
                .setUnit("seconds")
                .setDescription("HTTP Response Latency")
                .build();

        // async Gauge
        meter
                .gaugeBuilder(HEAP_MEMORY_NAME)
                .setDescription(HEAP_MEMORY_DESCRIPTION)
                .setUnit("byte")
                .buildWithCallback(
                        r -> {
                            r.record(getRuntime().totalMemory() - getRuntime().freeMemory());
                        });

    }

    @RequestMapping(method = RequestMethod.GET, value = "/")
    public String hello() {
        return "Welcome to Observability POC";
    }

    @RequestMapping(method = RequestMethod.GET, value = "/simpleSpan/{id}")
    public Response simpleSpan(@PathVariable(name = "id") int id) {
        long startTime = System.currentTimeMillis();
        Response response = buildResponse();
        // Creating a simpleSpan

        Span span = tracer.spanBuilder("/simpleSpan").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("http.method", "GET");
        span.setAttribute("http.url", "/simpleSpan");
        try (Scope scope = span.makeCurrent()) {
            if (response.isValid()) {
                log.info("The response is valid.");
            }
            Thread.sleep(new Random().nextInt(1000));
            if (id < 0) {
                numberOfExecutions.add(1);
                throw new Exception("Invalid id!");
            }
        } catch (Exception e) {
            exceptionCounter.add(1, Attributes.builder()
                    .put("exception-key-1", "exception-value-1")
                    .build());
        } finally {
            span.end();
        }

        long endTime = System.currentTimeMillis();
        simpleSpanLatencyHistogram.record(endTime - startTime);

        return response;
    }

    //   Here, we will create nested "span"s for nested operations
    @RequestMapping(method = RequestMethod.POST, value = "/nestedSpan1")
    public Response nestedSpan1() {
        long startTime = System.currentTimeMillis();

        Response response = new Response("nestedSpan1 Response");

        Span parentSpan = tracer.spanBuilder("/nestedSpan1").setSpanKind(SpanKind.CLIENT).startSpan();
        parentSpan.setAttribute("http.method", "POST");
        parentSpan.setAttribute("http.url", "/nestedSpan1");

        try {
            childOne(parentSpan);
            upAndDownCounter.add(1, Attributes.builder().put("api.url", "/nestedSpan1").build());
            Thread.sleep(new Random().nextInt(1000));
            numberOfExecutions.add(1);
        } catch (Exception e) {
            exceptionCounter.add(1, Attributes.builder()
                    .put("exception-key-2", "exception-value-2")
                    .build());
        } finally {
            parentSpan.end();
        }

        long endTime = System.currentTimeMillis();
        nestedSpan1LatencyHistogram.record(endTime - startTime);

        return response;
    }

    void childOne(Span parentSpan) {
        Span childSpan = tracer.spanBuilder("childSpan1")
                .setParent(Context.current().with(parentSpan))
                .startSpan();
        try {
            Thread.sleep(new Random().nextInt(200));
        } catch (Exception e) {
            exceptionCounter.add(1, Attributes.builder()
                    .put("exception-key-2", "exception-value-2")
                    .build());
            childSpan.setStatus(StatusCode.ERROR);
        } finally {
            childSpan.end();
        }
    }

    //  Here, we will create nested "span"s for nested operations
    //  this is second way for nested spans
    //  -- The OpenTelemetry API offers also an automated way to propagate the parent span
    //  on the current thread:
    // To link spans from remote processes, it is sufficient to set the Remote Context as parent.
    // example like :
    // Span childRemoteParent = tracer.spanBuilder("Child").setParent(remoteContext).startSpan();
    @RequestMapping(method = RequestMethod.POST, value = "/nestedSpan2")
    public Response parentSpan2() {
        long startTime = System.currentTimeMillis();

        Response response = new Response("nestedSpan2 Response");
        Span parentSpan = tracer.spanBuilder("/nestedSpan2").setSpanKind(SpanKind.CLIENT).startSpan();

        try (Scope scope = parentSpan.makeCurrent()) {
            childTwo();
            upAndDownCounter.add(-1, Attributes.builder().put("api.url", "/nestedSpan2").build());
            Thread.sleep(new Random().nextInt(300));
            numberOfExecutions.add(1);
        } catch (Exception e) {
            //
        } finally {
            parentSpan.end();
        }

        long endTime = System.currentTimeMillis();
        nestedSpan2LatencyHistogram.record(endTime - startTime);

        return response;
    }

    void childTwo() {
        Span childSpan = tracer.spanBuilder("childSpan2")
                // NOTE: setParent(...) is not required;
                // `Span.current()` is automatically added as the parent
                .startSpan();
        try (Scope scope = childSpan.makeCurrent()) {
            Thread.sleep(new Random().nextInt(200));
            // do stuff
        } catch (Exception e) {
            exceptionCounter.add(1, Attributes.builder()
                    .put("exception-key-2", "exception-value-2")
                    .build());
            childSpan.setStatus(StatusCode.ERROR);
        } finally {
            childSpan.end();
        }
    }

    @WithSpan
    private Response buildResponse() {
        return new Response("Hello World");
    }

    private record Response(String message) {
        private Response {
            Objects.requireNonNull(message);
        }

        private boolean isValid() {
            return true;
        }
    }

}
