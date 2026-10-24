package cn.dapan.assistant

/** Card geometry, not event duration, controls the readable detail level.
 * Font scale participates in fitting; never shrink away the user's accessibility setting. */
internal data class CalendarTypography(
 val title:Float,val detail:Float,val line:Float,val splitTime:Boolean,val fullTime:Boolean,
 val titleLines:Int,val locationLines:Int,val noteLines:Int
) {
 companion object {
  fun fit(width:Float,height:Float,fontScale:Float=1f,hasLocation:Boolean=false,hasNotes:Boolean=false):CalendarTypography {
   val scale=fontScale.coerceAtLeast(.5f)
   val w=(width-10).coerceAtLeast(1f)/scale
   val h=(height-6).coerceAtLeast(1f)/scale
   val growth=minOf(((w-65)/150).coerceIn(0f,1f),((h-42)/110).coerceIn(0f,1f))
   val title=11f+9f*growth
   val detail=10f+4f*growth
   val line=detail+3f
   val split=w<detail*6.3f
   val timeHeight=line*(if(split)2 else 1)
   val full=h>=timeHeight+title+4
   if(!full)return CalendarTypography(title,detail,line,split,false,if(h>=line+title+3)1 else 0,0,0)
   var remaining=h-timeHeight-2
   val location=if(hasLocation&&remaining>=title+3+line+2)1 else 0
   remaining-=location*(line+2)
   val notes=hasNotes&&w>=105&&h>=112&&remaining>=title+3+line*2+4
   val titleLines=((remaining-(if(notes)line*2+6 else 0f))/(title+3)).toInt().coerceIn(1,if(w>=140)3 else 2)
   remaining-=titleLines*(title+3)+2
   return CalendarTypography(title,detail,line,split,true,titleLines,location,
    if(notes)((remaining-4)/line).toInt().coerceIn(0,8)else 0)
  }
 }
}

