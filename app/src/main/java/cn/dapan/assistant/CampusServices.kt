package cn.dapan.assistant

import java.util.UUID

/** Bundled reference data transcribed from the user's supplied campus-service screenshots. */
object CampusServices {
 private fun service(key:String,name:String,hours:String="",location:String="",phone:String="",vararg extra:Pair<String,String>):NoteItem {
  val fields=listOf("开放时间" to hours,"地点" to location,"电话" to phone)+extra
  return NoteItem(id=UUID.nameUUIDFromBytes("dapan-campus-service/$key".toByteArray(Charsets.UTF_8)).toString(),module="信息查询",title=name,group="校园服务",
   detail=fields.filter{it.second.isNotBlank()}.joinToString("\n"){"${it.first}：${it.second}"},created=0L)
 }
 val defaults:List<NoteItem> = listOf(
  service("tmall","天猫超市","07:00–22:30","商业街","13342372590（马经理）"),
  service("hairdresser","理发店","09:00–21:00","商业街","15184257680（罗永利）"),
  service("fruit","水果店","08:00–22:30","商业街","19997888098（罗杰）"),
  service("printing","凌云图文","07:30–22:00","商业街","18342776003（吕经理）"),
  service("laundry","洗衣店","08:00–19:00","商业街","15541331162（李维莉）"),
  service("sf","顺丰快递","09:30–17:30","商业街","15904271633（刘福来）"),
  service("mobile","移动营业厅","09:00–17:00","商业街","18342787765（马岩）"),
  service("unicom","联通营业厅","09:00–17:00","商业街","18604273506（胡阳）"),
  service("telecom","电信营业厅","09:30–17:00","商业街","18009861118（王坤）"),
  service("bathhouse","公共澡堂","周一不营业；周二至周五 13:00–22:00；周六、周日 10:00–22:00","","",
   "入场截止" to "21:30 后不可进入",
   "设施" to "有隔间、淋浴，需自带拖鞋",
   "收费" to "资料称收费标准同宿舍内淋浴间，未提供具体价格",
   "备注" to "资料称宿舍大部分时间有热水；也可去 B01 健身房洗澡，具体开放安排未提供"),
  service("south-parcel","南门驿站","一般情况下每日 21:00 结束营业","南门","",
   "备注" to "资料未提供开始营业时间"),
  service("jd-parcel","京东快递房","每日 17:00 结束营业","","",
   "备注" to "资料注明京东次日达；未提供开始营业时间和具体地点"),
  service("clinic","医务室","原则上 24 小时开门；医生 22:00 后休息","H02","0427-2631120",
   "医生电话" to "15941759064",
   "护士电话" to "13841763351",
   "支付方式" to "玉兰卡、现金、医保；资料称暂不支持微信和支付宝",
   "提供服务" to "心电图、血常规、中暑处置、简单包扎、输液、开药等",
   "不提供" to "CT、核磁、手术、开病例（原图用词）、办理医保",
   "医保办理" to "G01"),
  service("service-hall","校区办事大厅","一般为周一至周五 08:00–11:30、13:30–17:00","G01","",
   "电话查询" to "对应官方网站查询",
   "备注" to "医务室资料提示：办理医保前往 G01"),
  service("general-affairs","总务部","一般为周一至周五 08:00–11:30、13:30–17:00","H03","",
   "电话查询" to "对应官方网站查询"),
  service("campus-alarm","校园报警电话",phone="0427-2631110"),
  service("security","保安处",phone="0427-2631078"),
  service("dormitory","宿舍楼管",phone="0427-26312xx",
   extra=arrayOf("号码说明" to "xx 为楼号；这是号码格式，不是完整可拨号码"))
 ).mapIndexed{index,note->note.copy(created=index.toLong())}

 data class Merge(val notes:List<NoteItem>,val installed:Set<String>)
 fun merge(existing:List<NoteItem>,installed:Set<String>):Merge {
  val cleaned=existing.map{note->if(note.module=="信息查询"&&informationSection(note)=="校园服务")note.copy(detail=note.detail.lines().filterNot{it.trimStart().matches(Regex("来源\\s*[:：].*"))}.joinToString("\n"))else note}
  val additions=defaults.filter{seed->seed.id !in installed&&cleaned.none{note->
   note.id==seed.id||(note.module=="信息查询"&&informationSection(note)=="校园服务"&&note.title.trim()==seed.title)
  }}
  return Merge(cleaned+additions,installed+defaults.map{it.id})
 }
}
