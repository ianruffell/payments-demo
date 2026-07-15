# Thin GridGain image that adds a LifecycleBean exposing the OpenCensus metrics as a
# Prometheus endpoint on :9404 (spec 010). All OpenCensus/Prometheus runtime libs are already
# present in the base image under libs/ignite-opencensus/, so we only compile+add the bean.
FROM eclipse-temurin:17-jdk AS build
COPY --from=gridgain/ultimate:8.9.32 /opt/gridgain/libs /gglibs
WORKDIR /src
COPY PrometheusLifecycleBean.java .
RUN javac --release 11 -cp "/gglibs/*:/gglibs/ignite-opencensus/*" -d out PrometheusLifecycleBean.java \
 && jar cf /gg-prometheus-lifecycle.jar -C out .

FROM gridgain/ultimate:8.9.32
COPY --from=build /gg-prometheus-lifecycle.jar /opt/gridgain/libs/gg-prometheus-lifecycle.jar
