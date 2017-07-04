Benchmarks here are generally in src/main and can be run with `benchmark/jmh:run`
within SBT.

See here for more details:
[SBT-JMH](https://github.com/ktoso/sbt-jmh)
[JMS-Samples](http://hg.openjdk.java.net/code-tools/jmh/file/tip/jmh-samples/src/main/java/org/openjdk/jmh/samples/)

## Flamegraphs

[Flamegraphs](http://www.brendangregg.com/flamegraphs.html) are a good way to
get a sense where most computation time is spent. You can create these graphs
for the benchmarks in the following way:

1. Run the bnechmark with the Java flight recorder with `sbt "benchmark/jmh:run -prof jmh.extras.JFR -i 1 -wi 1 LazyCachingPersistenceStoreBenchmark.*"`.
2. This will generate a `.jfr` file. This file can be transformed to a
   flamegraph with
   [jfr-flame-graph/](https://github.com/chrishantha/jfr-flame-graph).
