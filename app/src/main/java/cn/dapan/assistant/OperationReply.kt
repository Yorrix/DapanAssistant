package cn.dapan.assistant

/** Deliberately exact phrases: a correction such as “可以，但换成…” must still go to the model. */
enum class OperationReply { APPROVE, REJECT, UNDO;
 companion object{
  fun parse(text:String):OperationReply? {
   val answer=text.replace(Regex("[\\s，。！？、,.!?]"),"")
   // ASR commonly repeats agreement. Only consume complete affirmative tokens: a
   // following correction, negation or extra instruction must still reach the model.
   if(answer.isNotEmpty()&&Regex("(?:(?:嗯|啊|呃|哦)*(?:确认执行|好的执行|没问题执行|对没有问题|没有问题|没问题|可以的|可以|对的|对|好的|好啊|好|行的|行|是的是的|是的|就这样|确认|执行)(?:嗯|啊|呀|吧)*)+").matches(answer))return APPROVE
   return when(answer){
   "确认","执行","确认执行","可以","可以的","对","对的","对没有问题","没问题","没有问题","好","好的","好啊","行","行的","好的执行","就这样","没问题执行"->APPROVE
   "取消","放弃","算了","不用了","别改了","取消这次操作","放弃这次更改"->REJECT
   "撤销","撤销刚才的操作","撤销刚才的修改","撤销上一步","撤销这次操作"->UNDO
   else->null
  }
  }
 }
}
