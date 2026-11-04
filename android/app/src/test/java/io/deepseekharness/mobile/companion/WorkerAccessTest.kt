package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class WorkerAccessTest {
    @Test fun onlyTheRequiredRolesAndSourcesAreGranted() {
        assertTrue(WorkerAccess.denial("read","both",false,false,"read-only")!!.contains("本地资料"))
        assertTrue(WorkerAccess.denial("read","both",false,true,"read-only")!!.contains("长期记忆"))
        assertNull(WorkerAccess.denial("read","both",true,true,"read-only"))
        assertNull(WorkerAccess.denial("read","none",false,false,"read-only"))
        assertNotNull(WorkerAccess.denial("create","none",true,true,"read-only"))
        assertNull(WorkerAccess.denial("create","none",false,false,"workspace-write"))
    }
}
