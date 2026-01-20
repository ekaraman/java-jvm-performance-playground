import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PerfPlayground:
 * - CPU-intensive workload (prime calculation)
 * - Allocation & GC pressure (short-lived objects)
 * - Lock contention (synchronized hot lock)
 * - Latency measurement (p50 / p95 / p99)
 *
 * Purpose:
 * A diagnostic playground to practice JVM performance analysis
 * using tools like jstat, jstack, jcmd and Java Flight Recorder.
 */

public class PerfPlayground {

    static final class Config {
        int durationSec = 30;         // total test duration in seconds
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
                                       // number of worker threads
        int payloadKB = 64;           // size of the allocated byte[] per task (in KB)
        int retainEvery = 200;        // retain every Nth payload to create old-gen pressure
        int primeLimit = 20_000;      // CPU workload intensity (higher = more CPU usage)
        int queueCapacity = 50_000;   // capacity of the work queue
        boolean contention = true;    // enable/disable lock contention
    }

    // Simple latency histogram (microseconds)
    static final class Latency {
        final long[] samples;
        final AtomicLong idx = new AtomicLong(0);
        Latency(int maxSamples) { samples = new long[maxSamples]; }
        void recordMicros(long us) {
            long i = idx.getAndIncrement();
            if (i < samples.length) samples[(int) i] = us;
        }
        long count() { return Math.min(idx.get(), samples.length); }

        long percentile(double p) {
            int n = (int) count();
            if (n <= 0) return 0;
            long[] copy = new long[n];
            System.arraycopy(samples, 0, copy, 0, n);
            java.util.Arrays.sort(copy);
            int pos = (int) Math.ceil(p * n) - 1;
            pos = Math.max(0, Math.min(pos, n - 1));
            return copy[pos];
        }
    }

    // Retain some payloads to create old-generation pressure
    static final class Retainer {
        final List<byte[]> keep = new ArrayList<>();
        void maybeRetain(byte[] b, long i, int every) {
            if (every > 0 && (i % every) == 0) keep.add(b);
        }
    }

    static final Object HOT_LOCK = new Object();
    static long lockedCounter = 0;

    static boolean isPrime(int x) {
        if (x <= 1) return false;
        if (x % 2 == 0) return x == 2;
        for (int i = 3; i * i <= x; i += 2) {
            if (x % i == 0) return false;
        }
        return true;
    }

    static int countPrimes(int limit) {
        int c = 0;
        for (int i = 2; i <= limit; i++) if (isPrime(i)) c++;
        return c;
    }

    static void busyCpu(int primeLimit) {
        // CPU workload (consume the result to prevent JIT optimizations)
        int primes = countPrimes(primeLimit);
        if (primes == 0) System.out.print(""); // no-op
    }

    static void contentionWork(boolean enabled) {
        if (!enabled) return;
        synchronized (HOT_LOCK) {
            lockedCounter++;
        }
    }

    static void allocationWork(int payloadKB, Random r) {
        // Short-lived allocation to create GC pressure
        byte[] b = new byte[payloadKB * 1024];
        // Touch the memory to ensure pages are actually allocated
        for (int i = 0; i < b.length; i += 4096) b[i] = (byte) r.nextInt(256);
    }

    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        for (String a : args) {
            if (a.startsWith("--duration=")) cfg.durationSec = Integer.parseInt(a.split("=",2)[1]);
            else if (a.startsWith("--threads=")) cfg.threads = Integer.parseInt(a.split("=",2)[1]);
            else if (a.startsWith("--payloadKB=")) cfg.payloadKB = Integer.parseInt(a.split("=",2)[1]);
            else if (a.startsWith("--retainEvery=")) cfg.retainEvery = Integer.parseInt(a.split("=",2)[1]);
            else if (a.startsWith("--primeLimit=")) cfg.primeLimit = Integer.parseInt(a.split("=",2)[1]);
            else if (a.startsWith("--contention=")) cfg.contention = Boolean.parseBoolean(a.split("=",2)[1]);
        }

        System.out.println("=== PerfPlayground ===");
        System.out.println("durationSec=" + cfg.durationSec +
                " threads=" + cfg.threads +
                " payloadKB=" + cfg.payloadKB +
                " retainEvery=" + cfg.retainEvery +
                " primeLimit=" + cfg.primeLimit +
                " contention=" + cfg.contention);
        System.out.println("PID=" + ProcessHandle.current().pid());

        final ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        if (tmx.isThreadCpuTimeSupported() && !tmx.isThreadCpuTimeEnabled()) {
            tmx.setThreadCpuTimeEnabled(true);
        }

        final AtomicLong submitted = new AtomicLong(0);
        final AtomicLong completed = new AtomicLong(0);
        final AtomicLong failed = new AtomicLong(0);

        // Limit the number of latency samples (to prevent unbounded growth)
        Latency lat = new Latency(2_000_000);

        BlockingQueue<Runnable> q = new ArrayBlockingQueue<>(cfg.queueCapacity);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                cfg.threads, cfg.threads,
                0L, TimeUnit.MILLISECONDS,
                q,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("worker-" + t.getId());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        Retainer retainer = new Retainer();
        Random rnd = new Random(42);

        long start = System.nanoTime();
        long end = start + Duration.ofSeconds(cfg.durationSec).toNanos();

        // Producer: sürekli iş üret
        Thread producer = new Thread(() -> {
            while (System.nanoTime() < end) {
                long submitTime = System.nanoTime();
                long n = submitted.incrementAndGet();

                Runnable task = () -> {
                    long t0 = System.nanoTime();
                    try {
                        // 1) CPU
                        busyCpu(cfg.primeLimit);

                        // 2) allocation + occasionally retain
                        allocationWork(cfg.payloadKB, rnd);

                        // Retain a small payload instead of regenerating it for the retain decision
                        byte[] retain = new byte[Math.max(1024, cfg.payloadKB * 256)];
                        retainer.maybeRetain(retain, n, cfg.retainEvery);

                        // 3) contention
                        contentionWork(cfg.contention);

                        completed.incrementAndGet();
                    } catch (Throwable th) {
                        failed.incrementAndGet();
                    } finally {
                        long t1 = System.nanoTime();
                        long us = (t1 - t0) / 1_000;
                        lat.recordMicros(us);
                    }
                };

                try {
                    pool.execute(task);
                } catch (RejectedExecutionException rex) {
                    failed.incrementAndGet();
                }

                // Small pacing to avoid being too aggressive (optional)
                // Thread.onSpinWait();
            }
        });
        producer.setName("producer");
        producer.start();

        // Reporter: intermediate report every 5 seconds
        Thread reporter = new Thread(() -> {
            long last = System.nanoTime();
            long lastCompleted = 0;
            while (System.nanoTime() < end) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                long now = System.nanoTime();
                long c = completed.get();
                long deltaC = c - lastCompleted;
                double sec = (now - last) / 1e9;
                double rps = deltaC / sec;

                Runtime rt = Runtime.getRuntime();
                long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                long total = rt.totalMemory() / (1024 * 1024);

                System.out.printf("t=%.1fs rps=%.0f completed=%d queue=%d heapUsed=%dMB/%dMB%n",
                        (now - start) / 1e9, rps, c, q.size(), used, total);

                last = now;
                lastCompleted = c;
            }
        });
        reporter.setName("reporter");
        reporter.setDaemon(true);
        reporter.start();

        producer.join();
        pool.shutdown();
        pool.awaitTermination(cfg.durationSec + 10L, TimeUnit.SECONDS);

        long totalTimeNs = System.nanoTime() - start;
        double totalSec = totalTimeNs / 1e9;

        long c = completed.get();
        long s = submitted.get();
        long f = failed.get();

        long p50 = lat.percentile(0.50);
        long p95 = lat.percentile(0.95);
        long p99 = lat.percentile(0.99);

        // CPU time (approximate total thread CPU time)
        long[] ids = tmx.getAllThreadIds();
        long cpuNs = 0;
        for (long id : ids) {
            long t = tmx.getThreadCpuTime(id);
            if (t > 0) cpuNs += t;
        }

        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long total = rt.totalMemory() / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);

        System.out.println("\n=== Summary ===");
        System.out.printf("submitted=%d completed=%d failed=%d%n", s, c, f);
        System.out.printf("throughput=%.0f ops/sec%n", c / totalSec);
        System.out.printf("latency_us p50=%d p95=%d p99=%d%n", p50, p95, p99);
        System.out.printf("cpu_time_total=%.2f sec (sum of threads)%n", cpuNs / 1e9);
        System.out.printf("heap_used=%dMB total=%dMB max=%dMB%n", used, total, max);
        System.out.printf("lockedCounter=%d retainedObjects=%d%n", lockedCounter, retainer.keep.size());
        System.out.println("Done.");
    }
}
