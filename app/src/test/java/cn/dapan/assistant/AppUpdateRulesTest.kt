package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateRulesTest {
 private fun feed()=JSONObject().put("schema",1).put("versionCode",94).put("versionName","0.64.0")
  .put("apkUrl","https://39.105.161.86/updates/dapan-94.apk").put("bytes",28_000_000)
  .put("sha256","a".repeat(64)).put("minSdk",26).put("abis",org.json.JSONArray(listOf("arm64-v8a"))).put("notes","权限与更新")
 private fun release()=AppUpdateRules.parse(feed().toString())
 private fun invalid(key:String,value:Any){assertThrows(Exception::class.java){AppUpdateRules.parse(feed().put(key,value).toString())}}
 @Test fun validRelease(){val r=release();assertEquals(94,r.code);assertEquals("dapan-94.apk",r.fileName)}
 @Test fun noUnencryptedOrForeignDownloads(){listOf("http://39.105.161.86/updates/dapan-94.apk","https://evil.test/updates/dapan-94.apk","https://39.105.161.86.evil.test/updates/dapan-94.apk","https://39.105.161.86@evil.test/updates/dapan-94.apk").forEach{invalid("apkUrl",it)}}
 @Test fun noPathTraversalOrRedirectParameters(){listOf("https://39.105.161.86/updates/../dapan-94.apk","https://39.105.161.86/updates/dapan-94.apk?url=https://evil.test","https://39.105.161.86/updates/dapan-94.apk#x","https://39.105.161.86/updates/dapan-93.apk").forEach{invalid("apkUrl",it)}}
 @Test fun boundedSizes(){listOf(0,-1,180_000_001).forEach{invalid("bytes",it)}}
 @Test fun requiresDigest(){invalid("sha256","");invalid("sha256","x".repeat(64));invalid("sha256","a".repeat(63))}
 @Test fun malformedMetadataRejected(){listOf("", "{}", "<html>error</html>",feed().toString().dropLast(1)).forEach{assertThrows(Exception::class.java){AppUpdateRules.parse(it)}}}
 @Test fun boundedMetadata(){invalid("notes","x".repeat(3001));assertThrows(Exception::class.java){AppUpdateRules.parse("x".repeat(65537))}}
 @Test fun schemaAndVersions(){invalid("schema",2);invalid("versionCode",0);invalid("versionName","next");invalid("minSdk",0)}
 @Test fun supportedArchitecturesOnly(){invalid("abis",org.json.JSONArray());invalid("abis",org.json.JSONArray(listOf("mips")));invalid("abis",org.json.JSONArray(listOf("x86","x86")))}
 @Test fun compatibleDevicesOnly(){assertTrue(AppUpdateRules.compatible(release(),33,listOf("arm64-v8a")));assertFalse(AppUpdateRules.compatible(release(),25,listOf("arm64-v8a")));assertFalse(AppUpdateRules.compatible(release(),37,listOf("x86_64")))}
 @Test fun successfulArchive(){AppUpdateRules.validateArchive(release(),"cn.dapan.assistant","cn.dapan.assistant",94,93,setOf("cert"),setOf("cert"))}
 @Test fun noWrongPackage(){assertThrows(Exception::class.java){AppUpdateRules.validateArchive(release(),"evil.app","cn.dapan.assistant",94,93,setOf("cert"),setOf("cert"))}}
 @Test fun noDowngradesOrReinstall(){listOf(94L,95L).forEach{installed->assertThrows(Exception::class.java){AppUpdateRules.validateArchive(release(),"p","p",94,installed,setOf("cert"),setOf("cert"))}}}
 @Test fun metadataAndApkVersionMustMatch(){assertThrows(Exception::class.java){AppUpdateRules.validateArchive(release(),"p","p",95,93,setOf("cert"),setOf("cert"))}}
 @Test fun noUnsignedOrForeignSignedApk(){listOf(emptySet(),setOf("other"),setOf("cert","other")).forEach{certs->assertThrows(Exception::class.java){AppUpdateRules.validateArchive(release(),"p","p",94,93,certs,setOf("cert"))}}}
 @Test fun oldSigningInformationMustExist(){assertThrows(Exception::class.java){AppUpdateRules.validateArchive(release(),"p","p",94,93,setOf("cert"),emptySet())}}
 @Test fun disabledMeansNoAutomaticTraffic(){assertFalse(AppUpdateRules.due(false,0,1_000_000))}
 @Test fun automaticChecksAreThrottled(){val now=100_000_000L;assertTrue(AppUpdateRules.due(true,0,now));assertFalse(AppUpdateRules.due(true,now-1000,now));assertTrue(AppUpdateRules.due(true,now-AppUpdateRules.CHECK_INTERVAL,now))}
 @Test fun clockRollbackDoesNotBlockUpdatesForever(){assertTrue(AppUpdateRules.due(true,100_000,1_000))}
 @Test fun firstPermissionUsesSystemPrompt(){assertFalse(PermissionDecisions.useSettings(false,false,false))}
 @Test fun temporaryDenialCanRequestAgain(){assertFalse(PermissionDecisions.useSettings(false,true,true))}
 @Test fun permanentDenialOpensSettings(){assertTrue(PermissionDecisions.useSettings(false,true,false))}
 @Test fun grantedOrLegacyPermissionsUseSettings(){assertTrue(PermissionDecisions.useSettings(true,false,false));assertTrue(PermissionDecisions.useSettings(false,false,false,true))}
}
