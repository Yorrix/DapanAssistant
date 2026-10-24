package cn.dapan.assistant

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*

object AppAppearance {
 var mode by mutableStateOf("system");private set
 private var selection by mutableStateOf(AppearanceSelection(UiShape.GEEK,UiColorPalette.DUT))
 val shape get()=selection.shape
 val palette get()=selection.palette
 val description get()=selection.label
 // Old integrations may still inspect a style; rendering uses shape and palette separately.
 val style get()=UiStyle.valueOf(shape.name)
 var motion by mutableStateOf(PageMotion.FADE);private set
 var petEnabled by mutableStateOf(false);private set
 var petAutoHide by mutableStateOf(true);private set
 var systemDark by mutableStateOf(false)
 val dark get()=mode=="dark"||(mode=="system"&&systemDark)
 fun load(context:Context){
  val pet=context.getSharedPreferences("companion-pet",Context.MODE_PRIVATE)
  petEnabled=pet.getBoolean("enabled",false);petAutoHide=pet.getBoolean("autoHide",true)
  val prefs=context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
  motion=PageMotion.from(prefs.getString("motion",null))
  mode=prefs.getString("theme","system").takeIf{it in listOf("system","light","dark")}?:"system"
  selection=AppearanceSelection.restore(prefs.getString("shape",null),prefs.getString("palette",null),prefs.getString("style",null))
  if(prefs.getString("shape",null)!=shape.name||prefs.getString("palette",null)!=palette.name)
   prefs.edit().putString("shape",shape.name).putString("palette",palette.name).apply()
 }
 fun setPetEnabled(context:Context,value:Boolean){check(context.getSharedPreferences("companion-pet",Context.MODE_PRIVATE).edit().putBoolean("enabled",value).commit()){ "小助手设置未保存" };petEnabled=value}
 fun setPetAutoHide(context:Context,value:Boolean){check(context.getSharedPreferences("companion-pet",Context.MODE_PRIVATE).edit().putBoolean("autoHide",value).commit()){ "小助手设置未保存" };petAutoHide=value}
 fun setMotion(context:Context,value:PageMotion){context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("motion",value.name).apply();motion=value}
 fun set(context:Context,value:String){mode=value;context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("theme",value).apply()}
 fun configure(context:Context,shape:UiShape?=null,palette:UiColorPalette?=null){
  val next=selection.updated(shape,palette)
  check(context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("shape",next.shape.name).putString("palette",next.palette.name).commit()){"外观设置未保存，请重试"}
  selection=next
 }
 fun setShape(context:Context,value:UiShape)=configure(context,shape=value)
 fun setPalette(context:Context,value:UiColorPalette)=configure(context,palette=value)
 fun setStyle(context:Context,value:UiStyle)=configure(context,value.shape,value.palette)
}
@Composable fun DapanTheme(content:@Composable ()->Unit){
 val system=isSystemInDarkTheme();val context=LocalContext.current
 val dark=AppAppearance.mode=="dark"||(AppAppearance.mode=="system"&&system)
 SideEffect{
  AppAppearance.systemDark=system
  (context as? ComponentActivity)?.let{val bars=androidx.core.view.WindowCompat.getInsetsController(it.window,it.window.decorView);bars.isAppearanceLightStatusBars=!dark;bars.isAppearanceLightNavigationBars=!dark}
 }
 val p=visualPalette(AppAppearance.palette,dark)
 val base=if(dark)darkColorScheme()else lightColorScheme()
 val colors=base.copy(primary=p.accent,onPrimary=p.onAccent,primaryContainer=p.accentSurface,onPrimaryContainer=p.ink,
  secondary=p.companion,onSecondary=p.onAccent,secondaryContainer=p.accentSurface,onSecondaryContainer=p.ink,
  background=p.paper,onBackground=p.ink,surface=p.card,onSurface=p.ink,surfaceVariant=p.soft,onSurfaceVariant=p.muted,
  surfaceContainer=p.card,surfaceContainerLowest=p.paper,surfaceContainerLow=p.paper,surfaceContainerHigh=p.soft,surfaceContainerHighest=p.soft,
  surfaceBright=p.card,surfaceDim=p.paper,surfaceTint=p.accent,inverseSurface=p.ink,inverseOnSurface=p.paper,inversePrimary=p.onAccent,outline=p.muted,outlineVariant=p.line)
 val typography=remember(AppAppearance.shape){
  val defaults=Typography();val t=defaults.copy(bodyLarge=defaults.bodyLarge.copy(fontSize=14.sp,lineHeight=20.sp),bodyMedium=defaults.bodyMedium.copy(fontSize=13.sp,lineHeight=18.sp),bodySmall=defaults.bodySmall.copy(fontSize=11.sp,lineHeight=15.sp))
  val family=when(AppAppearance.shape){UiShape.JOURNAL->androidx.compose.ui.text.font.FontFamily.Serif;else->androidx.compose.ui.text.font.FontFamily.SansSerif}
  t.copy(displayLarge=t.displayLarge.copy(fontFamily=family),displayMedium=t.displayMedium.copy(fontFamily=family),displaySmall=t.displaySmall.copy(fontFamily=family),headlineLarge=t.headlineLarge.copy(fontFamily=family),headlineMedium=t.headlineMedium.copy(fontFamily=family),headlineSmall=t.headlineSmall.copy(fontFamily=family),titleLarge=t.titleLarge.copy(fontFamily=family),titleMedium=t.titleMedium.copy(fontFamily=family),titleSmall=t.titleSmall.copy(fontFamily=family),bodyLarge=t.bodyLarge.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.SansSerif),bodyMedium=t.bodyMedium.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.SansSerif),bodySmall=t.bodySmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.SansSerif),labelLarge=t.labelLarge.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.SansSerif),labelMedium=t.labelMedium.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.SansSerif),labelSmall=t.labelSmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.SansSerif))
 }
 CompositionLocalProvider(androidx.compose.foundation.text.selection.LocalTextSelectionColors provides androidx.compose.foundation.text.selection.TextSelectionColors(p.accent,p.accent.copy(alpha=.18f))){
  MaterialTheme(colorScheme=colors,typography=typography,shapes=Shapes(extraSmall=AppShape(4.dp),small=AppShape(10.dp),medium=AppShape(14.dp),large=AppShape(18.dp),extraLarge=AppShape(24.dp)),content=content)
 }
}
@Composable fun ThemeChooser(){
 val c=LocalContext.current
 Column{
  Text("明暗模式",style=MaterialTheme.typography.titleSmall)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
   listOf("system" to "跟随系统","light" to "白天","dark" to "黑夜").forEach{(mode,label)->FilterChip(AppAppearance.mode==mode,{AppAppearance.set(c,mode)},label={Text(label,fontSize=12.sp)})}
  }
 }
}

val platformDialogTheme get()=if(AppAppearance.dark)android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

