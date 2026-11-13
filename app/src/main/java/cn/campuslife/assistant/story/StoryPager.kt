package cn.campuslife.assistant.story

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import cn.campuslife.assistant.ui.Ui
import kotlin.math.abs
import kotlin.math.roundToInt

/** Platform scrolling, with page snapping; vertical reading and system-edge gestures stay native. */
class StoryPager(context:Context):HorizontalScrollView(context) {
    private val strip=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL}
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f
    private var downY=0f
    private var vertical=false
    private var edge=false
    private var animation:ValueAnimator?=null
    var selected=0;private set
    var onSelected:((Int)->Unit)?=null
    init {
        isHorizontalScrollBarEnabled=false;overScrollMode=OVER_SCROLL_NEVER;isFillViewport=true
        addView(strip,LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.MATCH_PARENT))
        tag="story_pager";contentDescription="随记栏目，左右滑动或点击上方栏目切换"
    }
    fun addPage(view:android.view.View) {strip.addView(view,LinearLayout.LayoutParams(1,-1))}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w<=0)return
        animation?.cancel()
        for(i in 0 until strip.childCount)strip.getChildAt(i).layoutParams=LinearLayout.LayoutParams(w,-1)
        strip.layoutParams=LayoutParams(w*strip.childCount,-1)
        post {scrollTo(selected*width,0)}
    }
    fun select(index:Int,animate:Boolean=true) {
        val next=index.coerceIn(0,(strip.childCount-1).coerceAtLeast(0))
        selected=next;onSelected?.invoke(next);animation?.cancel()
        val target=width*next
        if(!animate || width==0 || !android.animation.ValueAnimator.areAnimatorsEnabled()) {scrollTo(target,0);return}
        animation=ValueAnimator.ofInt(scrollX,target).apply {
            duration=180;addUpdateListener {scrollTo(it.animatedValue as Int,0)};start()
        }
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            animation?.cancel();downX=event.x;downY=event.y;vertical=false
            edge=downX<Ui.dp(context,24) || downX>width-Ui.dp(context,24)
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        if(edge || vertical)return false
        if(event.actionMasked==MotionEvent.ACTION_MOVE) {
            val dx=abs(event.x-downX);val dy=abs(event.y-downY)
            if(dy>slop && dy>dx){vertical=true;return false}
            if(dx<=slop || dx<dy*1.3f)return false
        }
        return super.onInterceptTouchEvent(event)
    }
    override fun fling(velocityX:Int) {} // settle once; do not run a second platform fling after release
    override fun onTouchEvent(event:MotionEvent):Boolean {
        val handled=super.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
            val dx=event.x-downX
            val target=if(event.actionMasked==MotionEvent.ACTION_UP && abs(dx)>maxOf(Ui.dp(context,48).toFloat(),width*.16f))
                selected+if(dx<0)1 else -1 else (scrollX.toFloat()/width.coerceAtLeast(1)).roundToInt()
            select(target)
        }
        return handled
    }
    override fun onDetachedFromWindow(){animation?.cancel();super.onDetachedFromWindow()}
}
