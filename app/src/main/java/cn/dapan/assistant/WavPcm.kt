package cn.dapan.assistant

import java.io.File
import java.io.RandomAccessFile

data class PcmRange(val offset:Long,val byteCount:Long) { val durationMs get()=byteCount*1000/32000 }
object WavPcm {
 fun inspect(file:File):PcmRange = RandomAccessFile(file,"r").use { input ->
  fun text(n:Int)=ByteArray(n).also{input.readFully(it)}.toString(Charsets.US_ASCII)
  fun uint()=Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
  fun ushort()=java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
  require(input.length()>=44 && text(4)=="RIFF"){"录音不是完整 WAV 文件"}
  uint();require(text(4)=="WAVE"){"录音格式不支持"}
  var validFormat=false;var range:PcmRange?=null
  while(input.filePointer+8<=input.length()) {
   val name=text(4);val length=uint();val start=input.filePointer
   require(length<=input.length()-start){"录音文件截断，无法完整转写"}
   when(name) {
    "fmt " -> {
     require(length>=16){"录音格式头损坏"}
     val codec=ushort();val channels=ushort();val rate=uint();val byteRate=uint();val align=ushort();val bits=ushort()
     require(codec==1&&channels==1&&rate==16000L&&byteRate==32000L&&align==2&&bits==16){"补转写暂支持单声道 16 kHz / 16 bit PCM 录音"}
     validFormat=true
    }
    "data" -> {require(length%2==0L){"录音采样长度异常"};range=PcmRange(start,length)}
   }
   if(validFormat&&range!=null)break
   input.seek(start+length+(length%2))
  }
  require(validFormat && range!=null && range!!.byteCount>0){"录音尚未完成或没有音频"}
  range!!
 }
}
