package top.m24h.sonycamloc

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.ViewDataBinding
import top.m24h.android.DataBindActivity
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

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

inline fun <T:Context, reified V> resourceLoader(id:Int?=null): ReadOnlyProperty<T, V> {
    return LazyResourceLoader<T, V>(V::class, id)
}
@Suppress("UNCHECKED_CAST")
class LazyResourceLoader<T:Context, V>(val cls:KClass<*>, val id:Int?=null) : ReadOnlyProperty<T, V> {
    var value : V? =null
    override fun getValue(thisRef:T, property: KProperty<*>): V {
         if (value==null) value=when(cls) {
            String::class -> thisRef.resources.getString(id?:Class.forName(thisRef.packageName+".R\$string").getField(property.name).get(null) as Int) as V
            Int::class -> thisRef.resources.getInteger(id?:Class.forName(thisRef.packageName+".R\$integer").getField(property.name).get(null) as Int) as V
            Boolean::class -> thisRef.resources.getBoolean(id?:Class.forName(thisRef.packageName+".R\$bool").getField(property.name).get(null) as Int) as V
            // following must be written as String in resource files
            Float::class -> thisRef.resources.getString(id?:Class.forName(thisRef.packageName+".R\$string").getField(property.name).get(null) as Int).toFloat() as V
            Double::class -> thisRef.resources.getString(id?:Class.forName(thisRef.packageName+".R\$string").getField(property.name).get(null) as Int).toDouble() as V
            Long::class -> thisRef.resources.getString(id?:Class.forName(thisRef.packageName+".R\$string").getField(property.name).get(null) as Int).toLong() as V
            else -> throw UnsupportedOperationException()
        }
        return value!!
    }
}


