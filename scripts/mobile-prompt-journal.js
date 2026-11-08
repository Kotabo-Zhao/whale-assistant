// Keep the wire payload until the Host acknowledges it. Images can exceed localStorage's quota.
function mobileJournal(sessionId, operation, item) {
    return new Promise((resolve, reject) => {
        let database;
        let transaction;
        let finished = false;
        const finish = (error, value) => {
            if (finished) return;
            finished = true;
            clearTimeout(timer);
            database?.close();
            if (error) reject(error);
            else resolve(value);
        };
        const timer = setTimeout(() => {
            try { transaction?.abort(); } catch {}
            finish(new Error("本地待发记录读写超时，请重试。"));
        }, 10000);
        try {
            const open = indexedDB.open("whale-main-pending", 1);
            open.onerror = () => finish(open.error);
            open.onupgradeneeded = () => {
                if (finished) { open.transaction.abort(); return; }
                open.result.createObjectStore("requests", {keyPath:"id"}).createIndex("session", "sessionId");
            };
            open.onsuccess = () => {
                database = open.result;
                if (finished) { database.close(); return; }
                database.onversionchange = () => database.close();
                try {
                    transaction = database.transaction("requests", operation === "list" ? "readonly" : "readwrite");
                    const store = transaction.objectStore("requests");
                    const request = operation === "list" ? store.index("session").getAll(sessionId)
                        : operation === "save" ? store.put({...item,sessionId}) : store.delete(item.id);
                    transaction.oncomplete = () => finish(null, request.result);
                    transaction.onabort = () => finish(transaction.error ?? new Error("未能保存待发消息"));
                } catch (error) { finish(error); }
            };
        } catch (error) { finish(error); }
    });
}
