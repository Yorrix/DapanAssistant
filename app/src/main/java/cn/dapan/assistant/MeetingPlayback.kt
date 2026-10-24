package cn.dapan.assistant

import android.content.Context
import android.media.*
import androidx.compose.runtime.*

/** One player per meeting screen. Pausing retains the position; loading another clip cancels preparation. */
class MeetingPlayback(context:Context,private val onError:(String)->Unit){
 private val audio=context.getSystemService(AudioManager::class.java)
 private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
 private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener{if(it<0)pause()}.build()
 private var player:MediaPlayer?=null
 var clipId by mutableStateOf<String?>(null);private set
 var position by mutableLongStateOf(0L);private set
 var duration by mutableLongStateOf(0L);private set
 var playing by mutableStateOf(false);private set
 var ready by mutableStateOf(false);private set
 var speed by mutableFloatStateOf(1f);private set
 fun play(clip:Clip,at:Long?=null){
  if(clipId==clip.id&&ready){if(at!=null)seek(at);resume();return}
  stop();val p=MediaPlayer();player=p;clipId=clip.id;duration=clip.durationMs()
  try{
   p.setAudioAttributes(attributes);p.setDataSource(clip.audio.path)
   p.setOnPreparedListener{if(player===it){ready=true;duration=it.duration.toLong();seek(at?:0);resume()}}
   p.setOnCompletionListener{if(player===it){position=duration;playing=false;audio.abandonAudioFocusRequest(focus)}}
   p.setOnErrorListener{mp,_,_->if(player===mp){stop();onError("无法播放这段录音，请确认录音已结束且文件完整")};true}
   p.prepareAsync()
  }catch(e:Exception){stop();onError("无法打开录音")}
 }
 fun toggle(clip:Clip){if(clipId==clip.id&&ready&&playing)pause()else play(clip)}
 fun pause(){if(!ready){if(player!=null)stop();return};runCatching{player?.pause()};playing=false;tick();audio.abandonAudioFocusRequest(focus)}
 private fun resume(){if(!ready)return;if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){onError("其他应用正在使用音频，请稍后播放");return};try{if(position>=duration)seek(0);player?.playbackParams=PlaybackParams().setSpeed(speed);player?.start();playing=true}catch(e:Exception){onError("播放失败，请重新打开录音");stop()}}
 fun seek(at:Long){if(!ready)return;position=at.coerceIn(0,duration);runCatching{player?.seekTo(position,MediaPlayer.SEEK_CLOSEST)}}
 fun changeSpeed(value:Float){speed=value;if(ready)runCatching{player?.playbackParams=PlaybackParams().setSpeed(value);if(!playing)player?.pause()}}
 fun tick(){if(ready)runCatching{position=player?.currentPosition?.toLong()?:0}}
 fun stop(){val old=player;player=null;runCatching{old?.release()};audio.abandonAudioFocusRequest(focus);clipId=null;ready=false;playing=false;position=0;duration=0}
}
