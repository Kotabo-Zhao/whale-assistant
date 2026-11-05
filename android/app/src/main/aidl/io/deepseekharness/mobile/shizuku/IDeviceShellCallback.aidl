package io.deepseekharness.mobile.shizuku;

// Bounded synchronous chunks provide backpressure for screenshots and large output.
// An unacknowledged one-way burst can exhaust Binder's asynchronous buffer.
interface IDeviceShellCallback {
    void onOutput(String sessionId, in byte[] data);
    void onExit(String sessionId, int exitCode);
}
