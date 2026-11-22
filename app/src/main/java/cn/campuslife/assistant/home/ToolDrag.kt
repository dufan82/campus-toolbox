package cn.campuslife.assistant.home

import android.view.DragEvent
import android.view.View
import cn.campuslife.assistant.ui.Ui

/** Local drag only; fixed shortcuts never register as sources or targets. */
internal class ToolDrag(private val drop: (String,String)->Boolean) {
    private data class Payload(val owner:ToolDrag,val id:String)

    fun bind(card:View,id:String) {
        card.setOnLongClickListener {
            card.startDragAndDrop(null,View.DragShadowBuilder(card),Payload(this,id),0)
        }
        val normal=card.foreground
        val highlight=Ui.shape(card.context,0x2035b6a0,14,Ui.teal)
        card.setOnDragListener { _,event ->
            if(event.action==DragEvent.ACTION_DRAG_ENDED) {
                card.foreground=normal
                return@setOnDragListener true
            }
            val source=(event.localState as? Payload)?.takeIf { it.owner===this && it.id!=id }
                ?: return@setOnDragListener false
            when(event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> card.foreground=highlight
                DragEvent.ACTION_DRAG_EXITED -> card.foreground=normal
                DragEvent.ACTION_DROP -> {
                    card.foreground=normal
                    return@setOnDragListener drop(source.id,id)
                }
            }
            true
        }
    }
}
