package io.deepseekharness.mobile.companion

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.overlay.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PetFacePropsDeviceTest {
    @Test fun expressionsChangeFacePixelsAndPropsRemainVisibleAtSmallOverlaySize() {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
        val out=File(app.getExternalFilesDir(null),"p79-face-props").apply{mkdirs()}
        fun load(id:Int)=BitmapFactory.decodeResource(app.resources,id,BitmapFactory.Options().apply{inSampleSize=4;inScaled=false})
        val frame=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);val canvas=Canvas(frame)
        val faces=listOf(PetAction.IDLE,PetAction.THINK,PetAction.WORK,PetAction.ASK,PetAction.CELEBRATE,PetAction.SETBACK,
            PetAction.TOUCH,PetAction.NOTICE,PetAction.OBSERVE,PetAction.CONTROL,PetAction.PAUSED,PetAction.ERROR)
        val names=listOf("放松 · 微笑","思考 · 抿嘴","专注 · 收眉","疑惑 · 小圆嘴","开心 · 笑眼","受挫 · 皱眉",
            "亲近 · 脸红眨眼","提醒 · 明亮","观察 · 凝视","操控 · 专心","暂停 · 平静坚定","异常 · 担忧汗滴")
        val sheet=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888);val board=Canvas(sheet)
        val background=Color.rgb(17,31,47);board.drawColor(background)
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(222,239,245);textSize=19f}
        val pixels=mutableMapOf<PetAction,IntArray>()
        CompanionPetDrawable(load(R.drawable.whale_maid_open_v2),load(R.drawable.whale_maid_blink_v2),load(R.drawable.whale_maid_body_v3)).use {pet ->
            pet.phonePalette(0xff6dbec5.toInt(),0xff264552.toInt())
            faces.forEachIndexed {i,action ->
                val p=PetActing.pose(action,if(action==PetAction.TOUCH) 410 else 900)
                // Identical body and head transforms isolate facial pixels from silhouette differences.
                pet.setPose(PetPose(face=p.face,wink=p.wink));pet.blinkAmount=0f
                canvas.drawColor(background);pet.setBounds(0,0,256,256);pet.draw(canvas)
                val captured=IntArray(100*54);frame.getPixels(captured,0,100,75,76,100,54);pixels[action]=captured
                val x=i%4*250;val y=i/4*200
                board.drawText(names[i],x+15f,y+29f,label)
                board.drawBitmap(frame,Rect(75,76,175,130),Rect(x+15,y+45,x+235,y+164),Paint(Paint.FILTER_BITMAP_FLAG))
            }
            fun changed(a:PetAction,b:PetAction):Int = pixels.getValue(a).indices.count {i ->
                val x=pixels.getValue(a)[i];val y=pixels.getValue(b)[i]
                kotlin.math.abs(Color.red(x)-Color.red(y))+kotlin.math.abs(Color.green(x)-Color.green(y))+kotlin.math.abs(Color.blue(x)-Color.blue(y))>35
            }
            assertTrue("happy and sad must change the actual face",changed(PetAction.CELEBRATE,PetAction.SETBACK)>150)
            assertTrue("focus cannot reuse the relaxed face",changed(PetAction.WORK,PetAction.IDLE)>45)
            assertTrue("concern must be visible",changed(PetAction.ERROR,PetAction.IDLE)>45)
            File(out,"face-closeups.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)}
            val side=(88*app.resources.displayMetrics.density).toInt();val cell=side+24
            val small=Bitmap.createBitmap(cell*5,(side+44)*4,Bitmap.Config.ARGB_8888);val smallCanvas=Canvas(small)
            smallCanvas.drawColor(background);label.textSize=16f
            PetAction.entries.forEachIndexed {i,action ->
                val x=i%5*cell;val y=i/5*(side+44)
                smallCanvas.drawText(action.name,x+12f,y+23f,label)
                pet.setBounds(x+12,y+31,x+12+side,y+31+side)
                pet.setPose(PetActing.pose(action,if(action==PetAction.TOUCH) 410 else 900,.8f));pet.draw(smallCanvas)
            }
            File(out,"actual-88dp-states.png").outputStream().use{small.compress(Bitmap.CompressFormat.PNG,100,it)};small.recycle()
            inst.sendStatus(0,android.os.Bundle().apply {
                putInt("actual88dpPixels",side);putInt("happySadFaceChangedPixels",changed(PetAction.CELEBRATE,PetAction.SETBACK))
                putInt("focusRestFaceChangedPixels",changed(PetAction.WORK,PetAction.IDLE))
            })
        }
        frame.recycle();sheet.recycle()
    }
}
