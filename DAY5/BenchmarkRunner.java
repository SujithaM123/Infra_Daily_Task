package com.demo;

import com.demo.converters.JaxbConverter;
import com.demo.model.SampleData;
import com.demo.model.Transaction;
import com.sun.management.ThreadMXBean;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone micro-benchmark for this project's POJO-to-XML conversion:
 * {@link JaxbConverter#convert(Transaction)}. Uses only built-in JDK APIs.
 *
 * Compile (plain javac, after `mvn generate-sources` has produced the xjc classes):
 *   javac -d target/classes -cp "target/classes;%USERPROFILE%\.m2\repository\jakarta\xml\bind\jakarta.xml.bind-api\4.0.2\jakarta.xml.bind-api-4.0.2.jar;%USERPROFILE%\.m2\repository\org\glassfish\jaxb\jaxb-runtime\4.0.5\jaxb-runtime-4.0.5.jar" src/main/java/com/demo/BenchmarkRunner.java
 *
 * Easiest (Maven project, recommended):
 *   mvn -q compile
 *   mvn -q exec:java -Dexec.mainClass=com.demo.BenchmarkRunner -Dexec.classpathScope=compile -DargLine="-Xms512m -Xmx512m -XX:+UseG1GC"
 *
 * Or run directly once compiled, with a fixed heap:
 *   java -Xms512m -Xmx512m -XX:+UseG1GC -cp target/classes com.demo.BenchmarkRunner
 */
public final class BenchmarkRunner {

    private static final String METHOD_NAME = "JAXB";
    private static final int WARMUP_RUNS = 20_000;
    private static final int MEASURED_RUNS = 50_000;
    private static final int ROUNDS = 3;

    /** Prevents the JIT from eliminating the conversion as dead code. */
    private static volatile long sink = 0L;

    public static void main(String[] args) throws InterruptedException {
        JaxbConverter converter = new JaxbConverter();
        Transaction transaction = SampleData.normal();

        ThreadMXBean threadMXBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (threadMXBean.isThreadAllocatedMemorySupported() && !threadMXBean.isThreadAllocatedMemoryEnabled()) {
            threadMXBean.setThreadAllocatedMemoryEnabled(true);
        }
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        // --- Warm-up: not measured, just lets the JIT compile the hot path ---
        for (int i = 0; i < WARMUP_RUNS; i++) {
            byte[] xml = converter.convert(transaction);
            sink += xml.length;
        }

        // --- Measured rounds ---
        RoundResult[] rounds = new RoundResult[ROUNDS];
        byte[] sampleXml = null;

        for (int round = 0; round < ROUNDS; round++) {
            System.gc();
            Thread.sleep(200);

            long[] timesNanos = new long[MEASURED_RUNS];

            long allocBefore = threadMXBean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long gcCountBefore = sumGcCounts(gcBeans);
            long gcTimeBefore = sumGcTimeMillis(gcBeans);

            int xmlBytes = 0;
            for (int i = 0; i < MEASURED_RUNS; i++) {
                long start = System.nanoTime();
                byte[] xml = converter.convert(transaction);
                long elapsed = System.nanoTime() - start;

                sink += xml.length;
                timesNanos[i] = elapsed;
                xmlBytes = xml.length;
            }

            long allocAfter = threadMXBean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long gcCountAfter = sumGcCounts(gcBeans);
            long gcTimeAfter = sumGcTimeMillis(gcBeans);

            if (sampleXml == null) {
                sampleXml = converter.convert(transaction);
            }

            rounds[round] = RoundResult.from(
                    timesNanos,
                    allocAfter - allocBefore,
                    gcCountAfter - gcCountBefore,
                    gcTimeAfter - gcTimeBefore,
                    xmlBytes);
        }

        RoundResult median = medianByAverage(rounds);

        printTable(median);
        printSample(sampleXml);

        // keep the sink "used" so the JIT cannot prove it is dead
        if (sink == Long.MIN_VALUE) {
            System.out.println("unreachable: " + sink);
        }
    }

    private static long sumGcCounts(List<GarbageCollectorMXBean> beans) {
        long total = 0;
        for (GarbageCollectorMXBean bean : beans) {
            long count = bean.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    private static long sumGcTimeMillis(List<GarbageCollectorMXBean> beans) {
        long total = 0;
        for (GarbageCollectorMXBean bean : beans) {
            long time = bean.getCollectionTime();
            if (time > 0) {
                total += time;
            }
        }
        return total;
    }

    private static RoundResult medianByAverage(RoundResult[] rounds) {
        RoundResult[] sorted = Arrays.copyOf(rounds, rounds.length);
        Arrays.sort(sorted, (a, b) -> Double.compare(a.avgMicros, b.avgMicros));
        return sorted[sorted.length / 2];
    }

    private static void printTable(RoundResult r) {
        String header = String.format(
                "%-8s %10s %10s %10s %16s %14s %10s %10s %10s",
                "method", "avg_us", "p50_us", "p99_us", "conv/s", "alloc_kb/op", "gc_count", "gc_ms", "xml_bytes");
        String row = String.format(
                "%-8s %10.2f %10.2f %10.2f %16.0f %14.3f %10d %10d %10d",
                METHOD_NAME, r.avgMicros, r.p50Micros, r.p99Micros, r.conversionsPerSec,
                r.allocatedKbPerOp, r.gcCount, r.gcTimeMs, r.xmlBytes);

        System.out.println();
        System.out.println(header);
        System.out.println("-".repeat(header.length()));
        System.out.println(row);
        System.out.println();
    }

    private static void printSample(byte[] xml) {
        String text = new String(xml, StandardCharsets.UTF_8);
        String preview = text.substring(0, Math.min(150, text.length()));
        System.out.println("XML preview (first 150 chars):");
        System.out.println(preview);
    }

    /** Holds the computed metrics for one measured round. */
    private static final class RoundResult {
        final double avgMicros;
        final double p50Micros;
        final double p99Micros;
        final double conversionsPerSec;
        final double allocatedKbPerOp;
        final long gcCount;
        final long gcTimeMs;
        final int xmlBytes;

        private RoundResult(double avgMicros, double p50Micros, double p99Micros, double conversionsPerSec,
                             double allocatedKbPerOp, long gcCount, long gcTimeMs, int xmlBytes) {
            this.avgMicros = avgMicros;
            this.p50Micros = p50Micros;
            this.p99Micros = p99Micros;
            this.conversionsPerSec = conversionsPerSec;
            this.allocatedKbPerOp = allocatedKbPerOp;
            this.gcCount = gcCount;
            this.gcTimeMs = gcTimeMs;
            this.xmlBytes = xmlBytes;
        }

        static RoundResult from(long[] timesNanos, long allocatedBytes, long gcCount, long gcTimeMs, int xmlBytes) {
            int n = timesNanos.length;
            long sum = 0;
            for (long t : timesNanos) {
                sum += t;
            }
            double avgMicros = (sum / (double) n) / 1000.0;

            long[] sorted = Arrays.copyOf(timesNanos, n);
            Arrays.sort(sorted);
            double p50Micros = sorted[(int) (n * 0.50)] / 1000.0;
            double p99Micros = sorted[Math.min(n - 1, (int) (n * 0.99))] / 1000.0;

            double conversionsPerSec = 1_000_000_000.0 / (sum / (double) n);
            double allocatedKbPerOp = (allocatedBytes / 1024.0) / n;

            return new RoundResult(avgMicros, p50Micros, p99Micros, conversionsPerSec,
                    allocatedKbPerOp, gcCount, gcTimeMs, xmlBytes);
        }
    }
}
