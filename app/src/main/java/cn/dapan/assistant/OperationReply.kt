package cn.dapan.assistant

/** Deliberately exact phrases: a correction such as “可以，但换成…” must still go to the model. */
enum class OperationReply { APPROVE, REJECT, UNDO;
 companion object{
  fun parse(text:String):OperationReply?=when(text.replace(Regex("[\\s，。！？,.!?]"),"")){
   "确认","执行","确认执行","可以","对","对的","对没有问题","没问题","没有问题","好的","好的执行","就这样","没问题执行"->APPROVE
   "取消","放弃","算了","不用了","别改了","取消这次操作","放弃这次更改"->REJECT
   "撤销","撤销刚才的操作","撤销刚才的修改","撤销上一步","撤销这次操作"->UNDO
   else->null
  }
 }
}
