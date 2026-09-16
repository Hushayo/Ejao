package com.ejao.proxy

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * ScrollView that also lets the user switch the app's tabs with a horizontal
 * swipe. Vertical scrolling is untouched: we only intercept a gesture once it is
 * clearly horizontal (moved further sideways than down/up, past a small slop),
 * at which point we fire [onSwipe] and consume the rest of the gesture so the
 * view does not also scroll vertically.
 *
 * direction = +1  -> swipe right  -> go to the previous tab
 * direction = -1  -> swipe left   -> go to the next tab
 */
class SwipeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ScrollView(context, attrs, defStyle) {

    private var startX = 0f
    private var startY = 0f
    private var swiped = false
    var onSwipe: ((direction: Int) -> Unit)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                swiped = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swiped) {
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > SWIPE_SLOP) {
                        swiped = true
                        onSwipe?.invoke(if (dx < 0) -1 else 1)
                    }
                }
            }
        }
        return if (swiped) true else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Once we've claimed a horizontal swipe, consume the rest of the gesture.
        return if (swiped) true else super.onTouchEvent(ev)
    }

    companion object {
        private const val SWIPE_SLOP = 80f
    }
}
