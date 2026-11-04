package io.deepseekharness.mobile.overlay

import android.graphics.Rect
import kotlin.math.cos
import kotlin.math.sin

/** Bind-space pivots measured on the whale maid artwork. Children inherit the posed parent. */
internal enum class PetJoint(val x: Float, val y: Float) {
    ROOT(.49f,.82f), CHEST(.49f,.75f), HEAD(.48f,.49f),
    FIN_L(.20f,.31f), FIN_R(.75f,.27f), HAIR_L(.29f,.28f), HAIR_R(.67f,.27f),
    TAIL(.62f,.75f), TAIL_TIP(.77f,.65f),
    SHOULDER_L(.392f,.525f), SHOULDER_R(.584f,.522f),
    ELBOW_L(.414f,.59f), ELBOW_R(.558f,.59f), WRIST_L(.458f,.666f), WRIST_R(.524f,.665f),
    FOOT_L(.44f,.83f), FOOT_R(.543f,.83f),
}

internal class PetSkeleton(private val w: Float, private val h: Float) {
    internal class Bone {
        var cosine = 1f; var sine = 0f; var dx = 0f; var dy = 0f
        fun x(x: Float, y: Float) = cosine*x - sine*y + dx
        fun y(x: Float, y: Float) = sine*x + cosine*y + dy
    }
    val bones = Array(PetJoint.entries.size) { Bone() }
    private fun joint(id: PetJoint, parent: PetJoint, degrees: Float) {
        val p = bones[parent.ordinal]; val b = bones[id.ordinal]
        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians).toFloat(); val s = sin(radians).toFloat()
        b.cosine = p.cosine*c - p.sine*s
        b.sine = p.sine*c + p.cosine*s
        val x = id.x*w; val y = id.y*h
        b.dx = p.x(x,y) - b.cosine*x + b.sine*y
        b.dy = p.y(x,y) - b.sine*x - b.cosine*y
    }
    fun set(p: PetPose) {
        bones[PetJoint.ROOT.ordinal].dy = -p.lift*h
        joint(PetJoint.CHEST,PetJoint.ROOT,p.chest); joint(PetJoint.HEAD,PetJoint.CHEST,p.head)
        joint(PetJoint.FIN_L,PetJoint.HEAD,p.leftFin); joint(PetJoint.FIN_R,PetJoint.HEAD,p.rightFin)
        joint(PetJoint.HAIR_L,PetJoint.HEAD,p.hair); joint(PetJoint.HAIR_R,PetJoint.HEAD,-p.hair*.8f)
        joint(PetJoint.TAIL,PetJoint.ROOT,p.tailRoot); joint(PetJoint.TAIL_TIP,PetJoint.TAIL,p.tailTip)
        joint(PetJoint.SHOULDER_L,PetJoint.CHEST,p.leftShoulder); joint(PetJoint.SHOULDER_R,PetJoint.CHEST,p.rightShoulder)
        joint(PetJoint.ELBOW_L,PetJoint.SHOULDER_L,p.leftElbow); joint(PetJoint.ELBOW_R,PetJoint.SHOULDER_R,p.rightElbow)
        joint(PetJoint.WRIST_L,PetJoint.ELBOW_L,p.leftWrist); joint(PetJoint.WRIST_R,PetJoint.ELBOW_R,p.rightWrist)
        joint(PetJoint.FOOT_L,PetJoint.ROOT,p.feet); joint(PetJoint.FOOT_R,PetJoint.ROOT,-p.feet)
    }
}

/** Skin weights and source UVs are immutable; a frame updates only vertex positions. */
internal class PetSkin(
    val cols: Int, val rows: Int, region: Rect, private val w: Float, private val h: Float,
    arm: PetJoint? = null,
) {
    private val count = (cols + 1) * (rows + 1)
    val vertices = FloatArray(count * 2)
    private val rest = FloatArray(count * 2)
    private val joints = IntArray(count * 4)
    private val weights = FloatArray(count * 4)
    private val gaze = FloatArray(count)
    private val mouth = FloatArray(count)

    init {
        for (row in 0..rows) for (col in 0..cols) {
            val i = row*(cols+1)+col
            val px = region.left + region.width()*col.toFloat()/cols
            val py = region.top + region.height()*row.toFloat()/rows
            val x = px/w; val y = py/h
            rest[i*2] = px; rest[i*2+1] = py
            val binding = FloatArray(PetJoint.entries.size)
            fun blend(id: PetJoint, amount: Float) {
                if (amount <= 0) return
                for (j in binding.indices) binding[j] *= 1f-amount
                binding[id.ordinal] += amount
            }
            if (arm != null) {
                binding[arm.ordinal] = 1f
                blend(if (arm == PetJoint.ELBOW_L) PetJoint.WRIST_L else PetJoint.WRIST_R, smooth(.65f,.705f,y))
            } else {
                binding[PetJoint.ROOT.ordinal] = 1f
                blend(PetJoint.CHEST, 1f-smooth(.57f,.88f,y))
                blend(PetJoint.HEAD, 1f-smooth(.45f,.55f,y))
                val hairBand = smooth(.27f,.38f,y)*(1f-smooth(.48f,.55f,y))
                blend(PetJoint.HAIR_L,hairBand*(1f-smooth(.22f,.34f,x)))
                blend(PetJoint.HAIR_R,hairBand*smooth(.63f,.74f,x))
                val finBand = smooth(.20f,.30f,y)*(1f-smooth(.43f,.48f,y))
                blend(PetJoint.FIN_L,finBand*(1f-smooth(.13f,.225f,x)))
                blend(PetJoint.FIN_R,finBand*smooth(.715f,.81f,x))
                val tailBand = smooth(.42f,.55f,y)*(1f-smooth(.805f,.90f,y))
                blend(PetJoint.TAIL,tailBand*smooth(.60f,.74f,x))
                blend(PetJoint.TAIL_TIP,tailBand*smooth(.72f,.86f,x))
                val sleeves = smooth(.49f,.55f,y)*(1f-smooth(.60f,.64f,y))
                blend(PetJoint.SHOULDER_L,sleeves*smooth(.30f,.37f,x)*(1f-smooth(.43f,.48f,x)))
                blend(PetJoint.SHOULDER_R,sleeves*smooth(.50f,.55f,x)*(1f-smooth(.61f,.66f,x)))
                val legs = smooth(.82f,.91f,y)
                blend(PetJoint.FOOT_L,legs*(1f-smooth(.46f,.50f,x)))
                blend(PetJoint.FOOT_R,legs*smooth(.47f,.53f,x))
                val eyes = smooth(.30f,.35f,y)*(1f-smooth(.405f,.455f,y))
                gaze[i] = eyes*(oval(x,.37f,.10f)+oval(x,.55f,.10f))*.008f*w
                mouth[i] = oval(x,.485f,.063f)*oval(y,.443f,.029f)*(y-.423f)*h*.55f
            }
            val strongest = binding.indices.filter { binding[it] > 0 }.sortedByDescending { binding[it] }.take(4)
            val sum = strongest.sumOf { binding[it].toDouble() }.toFloat()
            for ((slot, joint) in strongest.withIndex()) {
                joints[i*4+slot] = joint; weights[i*4+slot] = binding[joint]/sum
            }
        }
    }
    fun update(skeleton: PetSkeleton, pose: PetPose) {
        for (i in 0 until count) {
            val x = rest[i*2] + gaze[i]*pose.gaze; val y = rest[i*2+1]+mouth[i]*pose.mouth
            var px = 0f; var py = 0f
            for (slot in 0..3) {
                val at = i*4+slot; val amount = weights[at]
                if (amount == 0f) break
                val b = skeleton.bones[joints[at]]
                px += amount*b.x(x,y); py += amount*b.y(x,y)
            }
            vertices[i*2] = px; vertices[i*2+1] = py
        }
    }
    companion object {
        private fun smooth(a: Float, b: Float, x: Float): Float {
            val t = ((x-a)/(b-a)).coerceIn(0f,1f)
            return t*t*(3f-2f*t)
        }
        private fun oval(x: Float, center: Float, radius: Float) = 1f-smooth(0f,radius,kotlin.math.abs(x-center))
    }
}
