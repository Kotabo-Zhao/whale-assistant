package io.deepseekharness.mobile.companion

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.DeviceBridgeServer
import io.deepseekharness.mobile.DeviceControlState
import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.runtime.RuntimeHost
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.UUID

class ScreenExecutionJournalDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app=inst.targetContext
    private fun id()=UUID.randomUUID().toString()
    private fun failure(code:String,block:()->Unit) {
        val error=runCatching(block).exceptionOrNull();assertTrue(error.toString(),error is RuntimeFailure)
        assertEquals(code,(error as RuntimeFailure).code)
    }
    @Test fun durableBoundariesFenceOldExecutorsAndNeverPromoteObservationToSuccess() {
        val file=File(app.cacheDir,"journal-test-${id()}.db")
        val operation=id();val request=JSONObject().put("packageName","com.example.clock")
        val first=ScreenExecutionJournal(file,"old")
        try {
            assertTrue(first.begin(operation,"screenSequence",request));first.dispatch(operation,1,"tap")
            ScreenExecutionJournal(file,"new").use { second ->
                assertEquals("unknown",second.read(operation)!!.getString("state"))
                failure("SCREEN_OPERATION_INACTIVE"){first.injected(operation,1)}
                failure("SCREEN_RECOVERY_REQUIRED"){second.begin(id(),"screenSequence",request)}
                failure("SCREEN_RECOVERY_APP_MISMATCH"){second.reconcile(operation,JSONObject(),"com.example.other")}
                assertEquals(0,second.read(operation)!!.getLong("reconciledAt"))
                second.reconcile(operation,JSONObject().put("width",100).put("height",200),"com.example.clock")
                assertEquals("unknown",second.read(operation)!!.getString("state"))
                assertFalse(second.begin(operation,"screenSequence",request))
                val next=id();assertTrue(second.begin(next,"screenSequence",request))
                second.dispatch(next,1,"tap");second.injected(next,1);second.observed(next,1,JSONObject())
                second.finish(next,JSONObject().put("status","completed").put("completed",1))
                second.received(next)
                assertEquals("completed",second.read(next)!!.getString("state"))
                assertTrue(second.read(next)!!.getLong("clientReceivedAt")>0)
                val cancelled=id();second.cancelBeforeStart(cancelled)
                failure("SCREEN_SEQUENCE_CANCELLED"){second.begin(cancelled,"screenSequence",request)}
            }
        } finally {first.close();SQLiteDatabase.deleteDatabase(file)}
    }
    @Test fun failedIntentCommitDoesNotAdmitExecution() {
        val file=File(app.cacheDir,"journal-test-${id()}.db")
        try {
            ScreenExecutionJournal(file).use { ledger ->
                SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.execSQL("CREATE TRIGGER fail_intent BEFORE INSERT ON operations BEGIN SELECT RAISE(ABORT,'injected write failure'); END")
                    assertNotNull(runCatching{ledger.begin(id(),"screenAct",JSONObject())}.exceptionOrNull())
                    assertEquals(0,ledger.recent().getJSONArray("items").length())
                    db.execSQL("DROP TRIGGER fail_intent")
                    val operation=id();assertTrue(ledger.begin(operation,"screenAct",JSONObject()))
                    ledger.interrupted(operation,"BEFORE_DISPATCH")
                    assertEquals("stopped",ledger.read(operation)!!.getString("state"))
                    val uncertain=id();ledger.begin(uncertain,"screenAct",JSONObject());ledger.dispatch(uncertain,1,"tap")
                    db.execSQL("CREATE TRIGGER fail_failure BEFORE UPDATE ON operations WHEN NEW.state='unknown' BEGIN SELECT RAISE(ABORT,'failed failure write'); END")
                    assertNotNull(runCatching{ledger.interrupted(uncertain,"DISK_WRITE_FAILED")}.exceptionOrNull())
                    db.execSQL("DROP TRIGGER fail_failure")
                    failure("SCREEN_RECOVERY_REQUIRED"){ledger.begin(id(),"screenAct",JSONObject())}
                    assertEquals("unknown",ledger.read(uncertain)!!.getString("state"))
                }
            }
        } finally {SQLiteDatabase.deleteDatabase(file)}
    }
    private fun bridge():DeviceBridgeServer {
        PetControlDeviceUi.await("bridge") {RuntimeHost.deviceBridgeOrNull()!=null}
        return RuntimeHost.deviceBridgeOrNull() as DeviceBridgeServer
    }
    private fun command(name:String,param:String):JSONObject {
        val bridge=bridge();val token=PetControlDeviceUi.field(bridge,"token") as String
        return Socket("127.0.0.1",bridge.localPort).use { socket ->
            socket.soTimeout=40000
            val body=JSONObject().put("command",name).put("param",param).toString().toByteArray()
            socket.getOutputStream().apply {write(("POST /device-command HTTP/1.0\r\nAuthorization: Bearer $token\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray());write(body);flush()}
            JSONObject(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
        }
    }
    private fun value(name:String,request:JSONObject=JSONObject()):JSONObject {
        val response=command(name,request.toString());assertTrue(response.toString(),response.getBoolean("ok"));return JSONObject(response.getString("text"))
    }
    private fun startApp() {
        app.startActivity(android.content.Intent(app,io.deepseekharness.mobile.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        PetControlDeviceUi.await("main activity") {inst.uiAutomation.rootInActiveWindow?.packageName==app.packageName}
        DeviceControlState.resume()
    }
    private fun wakeAfterTeardown(wake:WorkerRuntimeWake) {
        PetControlDeviceUi.await("runtime teardown finished") {
            try {wake.wake();true} catch(error:RuntimeFailure) {if(error.code!="RUNTIME_CLOSING") throw error;false}
        }
    }
    /** Run this method, force-stop the APP from adb, then run verifyAfterRealProcessRestart. */
    @Test fun prepareRealTapWithLostReceipt() {
        val wake=WorkerRuntimeWake(app);val wm=app.getSystemService(WindowManager::class.java)
        val prefs=app.getSharedPreferences("test-screen-journal",0)
        var fixture:TextView?=null
        var fault:SQLiteDatabase?=null
        try {
            startApp();wakeAfterTeardown(wake);bridge();value("screenRecover")
            assertTrue(prefs.edit().putInt("effects",0).commit())
            inst.runOnMainSync {
                fixture=TextView(app).apply {
                    text="Journal tap";textSize=22f;gravity=Gravity.CENTER;setTextColor(android.graphics.Color.BLACK);setBackgroundColor(android.graphics.Color.WHITE)
                    setOnClickListener { val n=prefs.getInt("effects",0)+1;check(prefs.edit().putInt("effects",n).commit());text="Effect $n" }
                }
                wm.addView(fixture,WindowManager.LayoutParams(600,160,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.LEFT;x=80;y=400})
            }
            Thread.sleep(300)
            val xy=IntArray(2);inst.runOnMainSync {fixture!!.getLocationOnScreen(xy);xy[0]+=300;xy[1]+=80}
            val observation=value("screenObserve",JSONObject().put("ocr",true))
            val operation=id()
            val request=JSONObject().put("operationId",operation).put("packageName",app.packageName)
                .put("observationId",observation.getString("observationId")).put("action","tap").put("x",xy[0]).put("y",xy[1])
            fault=SQLiteDatabase.openDatabase(File(app.noBackupFilesDir,"screen-execution-v1.db").path,null,SQLiteDatabase.OPEN_READWRITE)
            fault!!.execSQL("CREATE TRIGGER fail_receipt BEFORE UPDATE ON steps WHEN NEW.state='injected' BEGIN SELECT RAISE(ABORT,'injected receipt failure'); END")
            val response=command("screenAct",request.toString());assertFalse(response.toString(),response.getBoolean("ok"))
            PetControlDeviceUi.await("real tap effect") {prefs.getInt("effects",0)==1}
            fault!!.execSQL("DROP TRIGGER fail_receipt")
            val repeated=value("screenAct",request)
            assertEquals("recorded",repeated.getString("status"));assertEquals("unknown",repeated.getJSONObject("record").getString("state"))
            val newObservation=value("screenObserve",JSONObject().put("ocr",true))
            val other=JSONObject(request.toString()).put("operationId",id()).put("observationId",newObservation.getString("observationId"))
            assertEquals("SCREEN_RECOVERY_REQUIRED",command("screenAct",other.toString()).getString("errorCode"))
            assertEquals(1,prefs.getInt("effects",0))
            File(app.getExternalFilesDir(null),"p77-recovery-case.json").writeText(JSONObject().put("request",request).put("pid",android.os.Process.myPid()).toString())
            inst.sendStatus(0,android.os.Bundle().apply {putInt("effectsBeforeRestart",1);putString("recoveryState","unknown")})
        } finally {
            runCatching{fault?.execSQL("DROP TRIGGER IF EXISTS fail_receipt")};fault?.close()
            inst.runOnMainSync {fixture?.let{runCatching{wm.removeView(it)}}};wake.close()
        }
    }
    @Test fun verifyAfterRealProcessRestart() {
        val wake=WorkerRuntimeWake(app)
        val fixture=File(app.getExternalFilesDir(null),"p77-recovery-case.json")
        val saved=JSONObject(fixture.readText());assertNotEquals(saved.getInt("pid"),android.os.Process.myPid())
        val request=saved.getJSONObject("request");val operation=request.getString("operationId")
        try {
            startApp();wakeAfterTeardown(wake);bridge()
            val repeated=value("screenAct",request)
            assertEquals("recorded",repeated.getString("status"));assertEquals("unknown",repeated.getJSONObject("record").getString("state"))
            assertEquals(1,app.getSharedPreferences("test-screen-journal",0).getInt("effects",0))
            val reconciled=value("screenRecover",JSONObject().put("operationId",operation))
            assertEquals("unknown",reconciled.getJSONObject("record").getString("state"))
            assertTrue(reconciled.getJSONObject("record").getLong("reconciledAt")>0)
            assertTrue(reconciled.has("observation"));assertTrue(reconciled.getBoolean("doNotReplay"))
            assertTrue(command("screenReceiptAck",operation).getBoolean("ok"))
            inst.sendStatus(0,android.os.Bundle().apply{putInt("effectsAfterRestart",1);putString("reconciledState","unknown");putBoolean("replayPrevented",true)})
        } finally {wake.close();fixture.delete();app.getSharedPreferences("test-screen-journal",0).edit().clear().commit()}
    }
}
