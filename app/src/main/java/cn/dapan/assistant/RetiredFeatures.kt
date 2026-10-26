package cn.dapan.assistant
import android.content.Context
/** Compatibility entry point. Restored features must not be erased at launch. */
internal object RetiredFeatures {
 fun removed(name:String)=false
 fun clean(context:Context)=Unit
}
