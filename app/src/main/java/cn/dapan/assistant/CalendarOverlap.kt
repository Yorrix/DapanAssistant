package cn.dapan.assistant

enum class CalendarOverlapMode(val label:String,val explanation:String){
 SIDE_BY_SIDE("并排","重叠日程等分宽度，全部显示；较多时会出现窄列。"),
 STACK("叠放","保留较宽卡片，依次错开；点重叠卡片查看全部事项。"),
 SMART("智能折叠","宽度足够时并排；过窄时合成一张卡片，点开查看全部。")
}
data class CalendarOverlapGroup(val items:List<CalendarDayItem>){
 val start get()=items.minOf{it.body!!.startMinute}
 val end get()=items.maxOf{it.body!!.endMinute}
 val lanes get()=items.maxOf{it.lane.count}
 val ids get()=items.map{it.entry.id}
}
data class CalendarCardBounds(val left:Float,val width:Float)
object CalendarOverlap {
 fun groups(items:List<CalendarDayItem>):List<CalendarOverlapGroup>{
  val result=mutableListOf<CalendarOverlapGroup>();var current=mutableListOf<CalendarDayItem>();var end=-1f
  items.filter{it.body!=null}.sortedWith(compareBy<CalendarDayItem>{it.body!!.startMinute}.thenBy{it.entry.id}).forEach{item->
   val span=item.body!!
   if(current.isNotEmpty()&&span.startMinute>=end){result+=CalendarOverlapGroup(current.toList());current=mutableListOf();end=-1f}
   current+=item;end=maxOf(end,span.endMinute)
  }
  if(current.isNotEmpty())result+=CalendarOverlapGroup(current.toList())
  return result
 }
 fun folded(group:CalendarOverlapGroup,mode:CalendarOverlapMode,width:Float,fontScale:Float)=
  mode==CalendarOverlapMode.SMART&&group.lanes>1&&(width/group.lanes-4)<88*fontScale
 fun bounds(item:CalendarDayItem,group:CalendarOverlapGroup,mode:CalendarOverlapMode,width:Float):CalendarCardBounds{
  if(mode==CalendarOverlapMode.STACK&&group.lanes>1){
   val step=width*.28f/(group.lanes-1)
   return CalendarCardBounds(step*item.lane.index,width*.72f)
  }
  return CalendarCardBounds(width/item.lane.count*item.lane.index,width/item.lane.count)
 }
}
