// Runs while the connection module is evaluated, before gateway factories start.
// onPageFinished is too late for AbortSignal.any in Chromium 111.
if (typeof AbortSignal.any !== "function") {
    AbortSignal.any = function (signals) {
        const list = Array.from(signals);
        for (const signal of list) {
            if (!signal || typeof signal.aborted !== "boolean" ||
                typeof signal.addEventListener !== "function") {
                throw new TypeError("AbortSignal.any requires AbortSignals");
            }
        }
        const controller = new AbortController();
        for (const signal of list) {
            if (signal.aborted) {
                controller.abort(signal.reason);
                return controller.signal;
            }
        }
        const listeners = new Map();
        const abort = signal => {
            for (const [source, listener] of listeners) {
                source.removeEventListener("abort", listener);
            }
            listeners.clear();
            controller.abort(signal.reason);
        };
        for (const signal of list) {
            if (listeners.has(signal)) continue;
            const listener = () => abort(signal);
            listeners.set(signal, listener);
            signal.addEventListener("abort", listener, {once:true});
        }
        return controller.signal;
    };
}
