package top.m24h.sonycamloc

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.ViewDataBinding
import top.m24h.android.DataBindActivity

/**
 * adjust layout for system bars
 */
open class AppActivity<B:ViewDataBinding> (layoutId:Int)
    : DataBindActivity<B> (layoutId) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener( binding.root ) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}

@SuppressLint("ClickableViewAccessibility") // no need to perform click
fun setDownUpListener(view: View, action: (isDown: Boolean) -> Unit) {
    view.setOnTouchListener { _, motionEvent ->
        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> action(true)
            MotionEvent.ACTION_UP -> action(false)
        }
        false
    }
}

fun test(s:String) {Log.e("test",s)}


