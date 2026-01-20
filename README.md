# java-jvm-performance-playground
A minimal Java JVM performance playground for analyzing GC behavior, thread contention, CPU utilization, and latency using real runtime diagnostics tools (jstat, jstack, jcmd, JFR).

# Java Performance Playground

A minimal but realistic Java application designed to **practice JVM performance analysis and troubleshooting**, especially for **technical interviews**.

This project intentionally creates:
- CPU pressure
- High allocation / GC pressure
- Lock contention
- Latency variability (p50 / p95 / p99)

It is **not a benchmark**, but a **diagnostic playground** to demonstrate how you analyze Java runtime behavior in production-like scenarios.

---

## 🎯 Why this project exists

In real-world Java, DevOps, and SRE environments, engineers are frequently faced with questions such as:

- How do you debug a slow Java application in production?
- How do you analyze garbage collection behavior?
- How do you detect and diagnose thread contention?
- How do you reason about latency versus throughput under load?

This application provides a **controlled and repeatable environment** to explore these questions using **real runtime data and diagnostic tools**, rather than theoretical examples.

---

## 🧠 What this application simulates

Each task executed by the application includes:

1. **CPU-bound work**
   - Prime number calculation to generate real CPU load

2. **Memory allocation pressure**
   - Short-lived byte arrays to stress Young Generation
   - Optional object retention to push objects into Old Generation

3. **Thread contention**
   - A synchronized hot lock to create BLOCKED threads

4. **Latency measurement**
   - Records execution time per task
   - Reports p50 / p95 / p99 latency in microseconds

---

## 🏗️ How it works (high level)

- A producer continuously submits tasks into a bounded queue
- A fixed-size thread pool processes tasks
- Periodic runtime statistics are printed every 5 seconds
- Final summary is printed at the end of execution

---

## 🚀 Build & Run

### Compile
```bash
javac PerfPlayground.java

java PerfPlayground

## Run with custom parameters
java PerfPlayground \
  --duration=30 \
  --threads=8 \
  --payloadKB=128 \
  --retainEvery=150 \
  --primeLimit=30000 \
  --contention=true

⚙️ Configuration Parameters
Parameter	Description
duration	Test duration in seconds
threads	Worker thread count
payloadKB	Allocation size per task
retainEvery	Retain every Nth allocation to stress Old Gen
primeLimit	CPU workload intensity
contention	Enable / disable synchronized lock contention

🔬 Performance Analysis

GC & Heap Analysis
java -Xms512m -Xmx512m \
     -Xlog:gc*:file=gc.log:time,level,tags \
     PerfPlayground

jstat -gcutil <PID> 1s


What to observe:

Young vs Old GC frequency

Allocation rate

Pause times

Promotion pressure

Thread & Contention Analysis
jstack <PID>


What to observe:

BLOCKED threads

Monitor ownership

Lock contention patterns

Java Flight Recorder (JFR)
jcmd <PID> JFR.start name=pp settings=profile duration=30s filename=pp.jfr


Analyze with JDK Mission Control:

Allocation hot spots

GC pauses

Thread contention

Hot methods
