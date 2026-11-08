// Keep the wire payload until the Host acknowledges it. Images can exceed localStorage's quota.
async function mobileJournal(sessionId, operation, item) {
    const database = await new Promise((resolve, reject) => {
        const request = indexedDB.open("whale-main-pending", 1);
        request.onupgradeneeded = () => {
            const store = request.result.createObjectStore("requests", {keyPath:"id"});
            store.createIndex("session", "sessionId");
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });
    try {
        return await new Promise((resolve,reject) => {
            const tx = database.transaction("requests", operation === "list" ? "readonly" : "readwrite");
            const store = tx.objectStore("requests");
            const request = operation === "list" ? store.index("session").getAll(sessionId)
                : operation === "save" ? store.put({...item,sessionId}) : store.delete(item.id);
            tx.oncomplete = () => resolve(request.result);
            tx.onabort = () => reject(tx.error ?? new Error("未能保存待发消息"));
        });
    } finally {
        database.close();
    }
}
