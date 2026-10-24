package cn.dapan.assistant

enum class UiShape(val label:String,val description:String,val radius:Float){
 GEEK("经典","标准圆角 · 胶囊工具",1f),
 VIVID("圆润","圆角卡片 · 轻投影",1.15f),
 JOURNAL("纸页","小圆角 · 细边框与衬线标题",.7f),
 COAST("利落","轻圆角 · 清爽平面",.9f),
 AURORA("柔和","大圆角 · 柔光与渐变细节",1.35f);
 companion object{
  fun parse(value:String)=entries.firstOrNull{it.name.equals(value,true)||it.label==value}
   ?:error("形状请选择${entries.joinToString("、"){it.label}}")
 }
}

enum class UiColorPalette(val label:String){
 DUT("大工蓝"),IRIS("鸢尾"),PAPER("暖棕"),COAST("海盐"),AURORA("紫翠");
 companion object{
  fun parse(value:String)=entries.firstOrNull{it.name.equals(value,true)||it.label==value}
   ?:error("配色请选择${entries.joinToString("、"){it.label}}")
 }
}

val UiStyle.shape get()=UiShape.valueOf(name)
val UiStyle.palette get()=when(this){
 UiStyle.GEEK->UiColorPalette.DUT;UiStyle.VIVID->UiColorPalette.IRIS;UiStyle.JOURNAL->UiColorPalette.PAPER
 UiStyle.COAST->UiColorPalette.COAST;UiStyle.AURORA->UiColorPalette.AURORA
}

data class AppearanceSelection(val shape:UiShape,val palette:UiColorPalette){
 val label get()="${shape.label} · ${palette.label}"
 fun updated(shape:UiShape?=null,palette:UiColorPalette?=null)=copy(shape=shape?:this.shape,palette=palette?:this.palette)
 companion object{
  fun restore(shape:String?,palette:String?,legacyStyle:String?):AppearanceSelection{
   val old=UiStyle.from(legacyStyle)
   return AppearanceSelection(UiShape.entries.firstOrNull{it.name==shape}?:old.shape,UiColorPalette.entries.firstOrNull{it.name==palette}?:old.palette)
  }
 }
}
