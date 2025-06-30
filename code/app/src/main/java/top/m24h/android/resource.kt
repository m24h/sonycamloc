package top.m24h.android

import android.content.Context
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * get values from R class, which is automatically generated from resources value files
 * usage: val value_name:Value_type   by resourceLoader(value_id) // `value_id` can be ignored when `value_name` is used to find the resource value
 */
@Suppress("unused")
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