package io.deepseekharness.mobile.companion

/** A portrait conversation grows with its content; the composer always keeps its space. */
internal object PetChatHeight {
    fun controls(available:Int,mandatory:Int,density:Float):Int =
        minOf((208*density).toInt(),available/3,(available-mandatory-(64*density).toInt()).coerceAtLeast(1)).coerceAtLeast(1)

    fun total(available:Int,nearPet:Int,chrome:Int,natural:Int,mode:String,portrait:Boolean,keyboard:Boolean,tool:Boolean,density:Float):Int {
        val space=available.coerceAtLeast(1)
        val minimum=((if(tool) 160 else if(keyboard) 44 else 64)*density).toInt().coerceAtLeast(1)
        val minimumTotal=(chrome+minimum).coerceAtMost(space)
        val fraction=when {
            tool -> if(portrait) .72f else .86f
            mode=="small" -> .42f
            mode=="large" -> .86f
            portrait && keyboard -> .86f
            portrait -> .60f
            else -> .76f
        }
        var ceiling=(space*fraction).toInt()
        if(portrait && mode=="auto" && !keyboard && !tool) ceiling=minOf(ceiling,(480*density).toInt())
        if(mode=="auto" && nearPet>=minimumTotal) ceiling=minOf(ceiling,nearPet)
        val requested=if(mode=="auto" || tool) minOf(ceiling,chrome+natural.coerceAtLeast(minimum)) else ceiling
        return requested.coerceIn(minimumTotal,space)
    }
}
