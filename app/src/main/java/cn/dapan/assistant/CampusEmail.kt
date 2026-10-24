package cn.dapan.assistant

internal object CampusEmail {
 val domains=listOf("mail.dlut.edu.cn","dlut.edu.cn")
 fun split(input:String,domain:String):Pair<String,String>{
  val value=input.trim()
  val suffix=value.substringAfterLast('@',"").lowercase()
  return if('@' in value&&suffix in domains)value.substringBeforeLast('@') to suffix else value to domain
 }
 fun address(prefix:String,domain:String):String?{
  val local=prefix.trim()
  if(domain !in domains||local.length !in 1..64||local.startsWith('.')||local.endsWith('.')||".." in local||
   !local.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+")))return null
  return "$local@$domain"
 }
}
