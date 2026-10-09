"""Real client/server lifecycle checks, sharing one small disposable scene."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--case', choices=['respawn','recovery'], required=True)
args = parser.parse_args()
run = root / 'build/gameplay-lifecycle'
server_home, client_home = run / 'server', run / 'client'
server_home.mkdir(parents=True, exist_ok=True)
if not client_home.exists():
    shutil.copytree(root / 'build/benchmarks/lod-square256-cache128/client', client_home)
shutil.copyfile(root / 'run/eula.txt', server_home / 'eula.txt')
(server_home / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25576\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\n', encoding='utf-8')
(server_home / 'config').mkdir(exist_ok=True)
(server_home / 'config/voxy_distant.toml').write_text('[server]\nradius=8\ngenerateMissingChunks=true\n[debug]\nenabled=true\nverbose=true\nintervalSeconds=2\n', encoding='utf-8')
(client_home / 'config/voxy_distant.toml').write_text('[client]\nreceiveServerLods=false\nradius=8\n[client.localGeneration]\nenabled=false\n', encoding='utf-8')
voxy=json.loads((client_home/'config/voxy-config.json').read_text())
voxy.update(enabled=True,ingest_enabled=False,section_render_distance=.25,service_threads=4)
(client_home/'config/voxy-config.json').write_text(json.dumps(voxy),encoding='utf-8')
(client_home/'options.txt').write_text('lang:zh_cn\nguiScale:2\nmaxFps:60\nrenderDistance:2\npauseOnLostFocus:false\ntutorialStep:none\n',encoding='utf-8')
out=run/('result-'+args.case+'-'+str(time.time_ns()));out.mkdir()
processes=[];logs=[]

def launch(kind,home):
    spec=json.loads((root/'build'/('smoke-launch.json' if kind=='server' else 'client-launch.json')).read_text())
    folders=spec['mod_classes'].split(';distant_smoke')[0]+f';distant_smoke%%{root / "build/classes/java/smoke"};distant_smoke%%{root / "build/resources/smoke"}'
    output=out/kind;output.mkdir()
    command=[a for a in spec['args'] if not a.startswith(('-Dfml.modFolders=','-Xmx','-Xms'))]
    command[1:1]=['-Xmx3G','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Dfml.modFolders='+folders,'-DvoxyDistant.lodBenchmark=true','-DvoxyDistant.benchmarkOutput='+str(output)]
    if kind=='client':command+=['--username','LodBench','--width','960','--height','540']
    log=(output/'console.log').open('w',encoding='utf-8');logs.append(log)
    env=os.environ.copy();env['MOD_CLASSES']=folders
    p=subprocess.Popen(command,cwd=home,env=env,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True,encoding='utf-8');processes.append(p);return p

def state():
    p=out/'client/states.jsonl'
    return json.loads(p.read_text(encoding='utf-8').splitlines()[-1]) if p.exists() and p.stat().st_size else {}

def wait(predicate,seconds=120):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if any(p.poll() is not None for p in processes):raise RuntimeError('Game exited; inspect '+str(out))
        if predicate():return
        time.sleep(.25)
    raise TimeoutError(str(state()))

def client(command,**fields):
    identity=uuid.uuid4().hex;p=out/'client/client-command.json';temp=p.with_suffix('.tmp')
    temp.write_text(json.dumps(dict(id=identity,command=command,**fields)),encoding='utf-8');temp.replace(p)
    ack=out/'client/acks'/f'{identity}.json';wait(ack.exists)
    value=json.loads(ack.read_text(encoding='utf-8'))
    if value['result']!='ok':raise AssertionError(value)
    return value

def server(text):
    service.stdin.write(text+'\n');service.stdin.flush()

def epoch():
    p=out/'server/console.log'
    lines=p.read_text(encoding='utf-8').splitlines()
    import re
    values=[int(m.group(1)) for line in lines if (m:=re.search(r'SERVER request player=.*? epoch=(\d+) radius=[1-9]',line))]
    return max(values,default=0)

try:
    service=launch('server',server_home)
    wait(lambda:'Done (' in (out/'server/console.log').read_text(encoding='utf-8'))
    game=launch('client',client_home);wait(lambda:state().get('ready'));client('connect')
    wait(lambda:state().get('in_world') and state().get('lod_hello'))
    server('gamerule doMobSpawning false\ngamerule keepInventory true\nspawnpoint LodBench 0 80 0\ngamemode creative LodBench')
    if args.case=='respawn':
        server('tp LodBench 1024 100 0')
        client('receive');wait(lambda:epoch()>0);before=epoch()
        server('kill LodBench');wait(lambda:state().get('dead'));client('respawn')
        wait(lambda:not state().get('dead') and epoch()>before)
        print('LIVE_SAME_DIMENSION_RESPAWN_PASS epoch',before,'->',epoch(),flush=True)
        server('execute in minecraft:the_nether run tp LodBench 0 100 0')
        wait(lambda:state().get('dimension')=='minecraft:the_nether' and state().get('hello_dimension')=='minecraft:the_nether')
        before=epoch();server('kill LodBench');wait(lambda:state().get('dead'));client('respawn')
        wait(lambda:state().get('dimension')=='minecraft:overworld' and state().get('hello_dimension')=='minecraft:overworld' and epoch()>before)
        print('LIVE_CROSS_DIMENSION_RESPAWN_PASS',json.dumps(state(),ensure_ascii=False),flush=True)
    else:
        server('tp LodBench 0 100 0\ngamemode spectator LodBench')
        client('receive');wait(lambda:epoch()>0);time.sleep(5)
        before=client('discovery-state');client('rebuild-index')
        wait(lambda:'索引重建完成' in (out/'client/console.log').read_text(encoding='utf-8'))
        time.sleep(3);after=client('discovery-state')
        print('LIVE_INDEX_RECOVERY',before,after,flush=True)
        assert after['regions']>0 and after['generation']>before['generation'], 'Stationary index recovery failed'
        before_epoch=epoch();server('voxydistant import')
        wait(lambda:'import 维护任务已结束' in (out/'server/console.log').read_text(encoding='utf-8'),180)
        wait(lambda:epoch()>before_epoch,15)
        after=client('discovery-state');assert after['regions']>0
        print('LIVE_IMPORT_RECOVERY_PASS',after,flush=True)
    (out/'result.json').write_text(json.dumps(dict(passed=True,case=args.case,state=state()),ensure_ascii=False,indent=2),encoding='utf-8')
finally:
    for p in reversed(processes):
        if p.poll() is None:
            if p is processes[0]:p.stdin.write('stop\n');p.stdin.flush()
            else:p.terminate()
            try:p.wait(timeout=45)
            except subprocess.TimeoutExpired:p.kill();p.wait()
    for log in logs:log.close()
    print('ARTIFACTS',out,flush=True)
