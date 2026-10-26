"""Run the opt-in JVM evaluator through a bounded localhost proxy. No key ever reaches Gradle.

Budget ledger persists across runs. Worst-case high-peak official Flash prices, ignoring cache
discounts when unknown; reserve full request/output before starting a billable call. No retries
or ambiguous-response refunds. No real app/user data, no server writes, no Android mutations.
"""
import argparse,json,os,re,secrets,subprocess,threading,time,urllib.request,urllib.error
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path

root=Path(__file__).resolve().parents[1];out=root/'validation/ai-audit';out.mkdir(parents=True,exist_ok=True)
parser=argparse.ArgumentParser();parser.add_argument('--round',default='live-1');parser.add_argument('--filter',default='');parser.add_argument('--budget',type=float,default=1.0);parser.add_argument('--max-output',type=int,default=4500);parser.add_argument('--cases');parser.add_argument('--output-dir');parser.add_argument('--build-root',default=str(root/'.build/eval'));args=parser.parse_args()
if args.output_dir:out=Path(args.output_dir).resolve();out.mkdir(parents=True,exist_ok=True)
assert 256<=args.max_output<=4500
ledger_path=out/'usage.json';ledger=json.loads(ledger_path.read_text()) if ledger_path.exists() else {'cost_upper_cny':0,'calls':[]}
lock=threading.Lock();token=secrets.token_urlsafe(32)
key=os.environ.get('DEEPSEEK_API_KEY','').strip()
if not key.startswith('sk-'):raise RuntimeError('Set DEEPSEEK_API_KEY for the opt-in paid evaluation')

source=(root/'app/src/main/java/cn/dapan/assistant/AssistantGlobalTools.kt').read_text(encoding='utf-8')
globals={};parts=list(re.finditer(r'"([^"\n]+)" to listOf\(',source))
for i,m in enumerate(parts):
 segment=source[m.end():parts[i+1].start() if i+1<len(parts) else len(source)]
 rows=[]
 for t in re.finditer(r'surfaceTool\("([^"\n]+)","([^"\n]+)","([^"\n]*)"(?:,(false|true))?\)',segment):
  rows.append(dict(op=t[1],description=t[2],fields=t[3].split(',') if t[3] else [],writes=t[4]!='false'))
 globals[m[1]]=rows
# The theme description interpolates enum labels and contains Kotlin quotes. Keep that one
# descriptor's exact fields; the JVM still checks against the production tool contract.
if not any(r['op']=='theme' for r in globals.get('设置',[])):
 globals.setdefault('设置',[]).append(dict(op='theme',description='外观独立设置：mode=system/light/dark；shape形状，palette配色，style旧风格。单改明暗不会改变配色和形状，不得修改权限或密钥。',fields=['mode','shape','palette','style'],writes=True))
assert sum(map(len,globals.values()))==source.count('surfaceTool('),'Incomplete global capability export'
(out/'global-operations.json').write_text(json.dumps(globals,ensure_ascii=False),encoding='utf-8')

class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  if self.headers.get('X-Evaluation-Token')!=token:self.send_error(403);return
  payload=self.rfile.read(int(self.headers['Content-Length']))
  body=json.loads(payload)
  if body.get('model')!='deepseek-flash' or body.get('max_tokens',0)>4500:self.send_error(400);return
  # UTF-8 bytes upper-bound token count, plus chat framing. Cached discount only after actual usage.
  input_bound=len(json.dumps(body['messages'],ensure_ascii=False).encode())+1000
  reserve=(input_bound*2+body['max_tokens']*8)/1e6
  with lock:
   if ledger['cost_upper_cny']+reserve>args.budget:self.send_error(402,'Evaluation budget limit');return
   ledger['cost_upper_cny']+=reserve
   row={'round':args.round,'cost_upper_cny':reserve,'status':'reserved'};ledger['calls'].append(row)
   ledger_path.write_text(json.dumps(ledger,indent=2))
  try:
   request=urllib.request.Request('https://api.deepseek.com/chat/completions',data=payload,headers={'Authorization':'Bearer '+key,'Content-Type':'application/json','Accept':'text/event-stream'})
   with urllib.request.urlopen(request,timeout=180) as response: raw=response.read()
   usage=None
   for line in raw.decode().splitlines():
    if line.startswith('data: ') and line[6:]!='[DONE]':
     frame=json.loads(line[6:])
     if frame.get('usage'):usage=frame['usage']
   with lock:
    if usage:
     hit=usage.get('prompt_cache_hit_tokens',0);miss=usage.get('prompt_cache_miss_tokens',usage.get('prompt_tokens',0)-hit)
     cost=(hit*.04+miss*2+usage.get('completion_tokens',0)*8)/1e6
     ledger['cost_upper_cny']+=cost-reserve;row.update(cost_upper_cny=cost,usage=usage,status='complete')
    else:row['status']='no_usage_reserved'
    ledger_path.write_text(json.dumps(ledger,indent=2))
   self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
  except Exception as e:
   with lock:row['status']=type(e).__name__;ledger_path.write_text(json.dumps(ledger,indent=2))
   self.send_error(502,'Upstream request failed')

server=ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
env=os.environ.copy();env.update(DAPAN_EVAL_URL=f'http://127.0.0.1:{server.server_port}/',DAPAN_EVAL_TOKEN=token,DAPAN_EVAL_CASES=str(Path(args.cases).resolve() if args.cases else out/'cases.jsonl'),DAPAN_EVAL_GLOBALS=str(out/'global-operations.json'),DAPAN_EVAL_RESULTS=str(out/(args.round+'.jsonl')),DAPAN_EVAL_FILTER=args.filter)
env['DAPAN_EVAL_MAX_OUTPUT']=str(args.max_output)
try:
 with (out/(args.round+'.log')).open('w',encoding='utf-8') as log:
  result=subprocess.run(['cmd.exe','/d','/c','gradlew.bat',':app:testDebugUnitTest','--tests','cn.dapan.assistant.AssistantLiveEvaluationTest','--rerun','-PdapanBuildRoot='+args.build_root,'--offline','--console=plain','--no-daemon'],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
 print(json.dumps({'round':args.round,'exit':result.returncode,'calls':len(ledger['calls']),'cost_upper_cny':round(ledger['cost_upper_cny'],6)}))
finally:
 server.shutdown();server.server_close();key='';env.pop('DAPAN_EVAL_TOKEN',None)
