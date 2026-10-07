"""Exercise delayed LOD replies, fast travel and teleports in a disposable Forge scene.

Reuse build/coarse-recovery-input and the launch specs from
build/benchmarks/lod-square256-cache128. All writes stay under build/.
"""
import argparse
import csv
import json
import math
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import uuid

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--mode', choices=['request', 'travel', 'extreme', 'performance'], required=True)
parser.add_argument('--variant', default='fixed')
parser.add_argument('--speed', type=float, default=320)
parser.add_argument('--generate-missing', action='store_true')
parser.add_argument('--uncached-target', type=int, default=128)
parser.add_argument('--debug', action='store_true')
parser.add_argument('--launch-build', type=Path, help='Use an isolated revision build for the A/B performance comparison')
args = parser.parse_args()
run = root / 'build/coarse-recovery-travel'
run.mkdir(exist_ok=True)
base = root / 'build/benchmarks/lod-square256-cache128'
source = root / 'build/coarse-recovery-input'
server_home = run / 'server-game'
client_home = run / 'game'
for kind, home in [('server', server_home), ('client', client_home)]:
    if not home.exists():
        shutil.copytree(source / kind, home)
if args.mode == 'extreme':
    target=args.uncached_target
    assert not (server_home/f'world/region/r.{target//32}.0.mca').exists(), 'Use an unvisited teleport target'
(server_home / 'config/voxy_distant.toml').write_text('''[server]
radius = 24
generateMissingChunks = false
distanceBands = ["8:0", "16:1", "24:2"]
totalBandwidthMbps = 30.0
[server.generation]
preset = "CUSTOM"
playerConcurrency = 8
[server.generation.custom]
conversionThreads = 4
generationConcurrency = 32
submissionsPerSecond = 8192
snapshotMillisPerTick = 2.0
conversionDutyCycle = 1.0
''', encoding='utf-8')
if args.generate_missing or args.mode=='performance':
    path=server_home/'config/voxy_distant.toml'
    path.write_text(path.read_text(encoding='utf-8').replace('generateMissingChunks = false','generateMissingChunks = true'),encoding='utf-8')
(client_home / 'config/voxy_distant.toml').write_text('''[client]
receiveServerLods = false
radius = 24
receiveMemoryMiB = 128
requestWindowColumns = 512
indexMemoryMiB = 256
processingDutyCycle = 0.9
[client.localGeneration]
enabled = false
''', encoding='utf-8')
if args.debug:
    for home in (client_home,server_home):
        with (home/'config/voxy_distant.toml').open('a',encoding='utf-8') as config:
            config.write('\n[debug]\nenabled = true\nverbose = true\nmeshDetails = false\nintervalSeconds = 2\n')
voxy = json.loads((client_home / 'config/voxy-config.json').read_text(encoding='utf-8'))
voxy.update(ingest_enabled=False, section_render_distance=.75, sub_division_size=64, service_threads=8)
(client_home / 'config/voxy-config.json').write_text(json.dumps(voxy), encoding='utf-8')
if args.variant.endswith('-cold'):
    disposable_cache = (client_home / '.voxy').resolve()
    assert disposable_cache.is_relative_to((root / 'build/coarse-recovery-travel').resolve())
    if disposable_cache.exists():
        shutil.rmtree(disposable_cache)
(client_home / 'options.txt').write_text('lang:zh_cn\nguiScale:2\nmaxFps:120\nrenderDistance:10\npauseOnLostFocus:false\ntutorialStep:none\nenableVsync:false\n', encoding='utf-8')
out = (run / args.variant).resolve()
assert out.is_relative_to(run.resolve())
out.mkdir(exist_ok=False)
processes = []
logs = []
report = {'mode': args.mode, 'variant': args.variant}

def launch(kind, home):
    spec_dir = args.launch_build.resolve() if args.launch_build else base
    spec_name = 'smoke' if args.launch_build and kind == 'server' else kind
    spec = json.loads((spec_dir / f'{spec_name}-launch.json').read_text(encoding='utf-8'))
    folders = spec['mod_classes'].split(';distant_smoke')[0]
    jargs = [a for a in spec['args'] if not a.startswith(('-Dfml.modFolders=', '-Xmx', '-Xms'))]
    folders += f';distant_smoke%%{root / "build/classes/java/smoke"};distant_smoke%%{root / "build/resources/smoke"}'
    output = out / kind
    output.mkdir()
    jargs[1:1] = ['-Xmx4G', '-Xms1G', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                 '-Dfml.modFolders=' + folders, '-DvoxyDistant.lodBenchmark=true', '-DvoxyDistant.benchmarkOutput=' + str(output)]
    jargs += ['--mixin.config', 'distant_benchmark.mixins.json']
    if kind == 'client':
        jargs.insert(1, '-DvoxyDistant.fixedCamera=true')
        if args.mode == 'extreme':
            jargs[1:1]=['-Dvoxy.verifyNodeManager=true','-DvoxyDistant.nodeDiagnostic=true','-DvoxyDistant.visibilityDiagnostic=true']
        jargs += ['--username', 'LodBench', '--width', '1280', '--height', '720']
    elif args.mode == 'extreme':
        jargs.insert(1, '-DvoxyDistant.extremeMovement=true')
    env = os.environ.copy()
    env['MOD_CLASSES'] = folders
    log = (output / 'console.log').open('w', encoding='utf-8')
    process = subprocess.Popen(jargs, cwd=home, env=env, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True, encoding='utf-8')
    processes.append(process)
    logs.append(log)
    return process

def state():
    file = out / 'client/states.jsonl'
    return json.loads(file.read_text(encoding='utf-8').splitlines()[-1]) if file.exists() and file.stat().st_size else {}

def wait(predicate, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if any(p.poll() is not None for p in processes):
            raise RuntimeError('Game exited before completion')
        if predicate():
            return
        time.sleep(.2)
    raise TimeoutError('Scene did not converge')

def command(command, **fields):
    identity = command + '-' + uuid.uuid4().hex
    control = out / 'client/client-command.json'
    temp = control.with_suffix('.tmp')
    temp.write_text(json.dumps({'id': identity, 'command': command, **fields}), encoding='utf-8')
    temp.replace(control)
    result = out / 'client/acks' / (identity + '.json')
    wait(result.exists)
    ack = json.loads(result.read_text(encoding='utf-8'))
    if ack['result'] != 'ok':
        raise AssertionError(ack)
    return ack

def server_command(text):
    server.stdin.write(text + '\n')
    server.stdin.flush()

def sample():
    file = out / 'client/client-samples.csv'
    with file.open(encoding='utf-8') as f:
        return list(csv.DictReader(f))[-1]

def audit(label, seconds=180, complete=True):
    deadline=time.monotonic()+seconds
    while True:
        (out/'client/coverage.json').unlink(missing_ok=True)
        command('audit')
        wait(lambda:(out/'client/coverage.json').exists(),90)
        coverage=json.loads((out/'client/coverage.json').read_text(encoding='utf-8'))
        last=sample()
        nodes=json.loads((out/'client/node-samples.jsonl').read_text(encoding='utf-8').splitlines()[-1])
        idle=all(int(last[key])==0 for key in ('pending','queue','active','mesh_queue')) and not any(nodes['requests']) and nodes['collapse_wait']==0 and nodes['mesh_rebuild']==0
        enough=coverage['insufficient']==0 if complete else coverage['insufficient']==coverage['level_counts'][5]
        if time.time()*1000-nodes['epoch_ms']<3000 and idle and enough:
            report[label]={'coverage':coverage,'sample':last,'epoch_ms':state()['epoch_ms']}
            report[label]['nodes']=nodes
            report[label]['screenshot']=command('screenshot')
            camera=report[label]['screenshot']
            with (out/'client/gpu-visibility.csv').open(encoding='utf-8') as file:
                rows=[row for row in csv.DictReader(file) if row['visible']=='true' and abs(int(row['epoch_ms'])-camera['epoch_ms'])<1500]
            counts=[0]*5;near_large=[]
            for row in rows:
                level=int(row['level']);counts[level]+=1
                dx=max(float(row['min_x'])-camera['x'],camera['x']-float(row['max_x']),0)
                dz=max(float(row['min_z'])-camera['z'],camera['z']-float(row['max_z']),0)
                if level>=3 and math.hypot(dx,dz)<=128:near_large.append(row)
            report[label]['gpu']={'visible_levels':counts,'near_large':near_large}
            if complete:assert not near_large, f'{label}: large visible nodes remain near the camera'
            return
        if time.monotonic()>=deadline:
            report[label]={'coverage':coverage,'sample':last}
            raise AssertionError(f'{label}: coverage or queues did not recover')
        time.sleep(5)

def teleport(cx,cz):
    server_command(f'tp LodBench {cx*16} 180 {cz*16} 0 15')
    command('move-camera',x=cx*16,z=cz*16)
    wait(lambda:state().get('x')==cx*16 and state().get('z')==cz*16)

def performance_mark():
    with (out/'server/server-samples.csv').open(encoding='utf-8') as file:
        server_sample=list(csv.DictReader(file))[-1]
    return {'client':state()['nano_time'],'server':int(server_sample['time_ns'])}

def performance_record(label,start):
    end=performance_mark()
    report.setdefault('windows',{})[label]={'start':start,'end':end}
    print(json.dumps({'variant':args.variant,'phase':label,'seconds':(end['client']-start['client'])/1e9}),flush=True)
    (out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')

def performance_settle():
    stable=0
    def idle():
        nonlocal stable
        last=sample()
        clear=all(int(last[key])==0 for key in ('pending','queue','active','mesh_queue','full_retry'))
        stable=stable+1 if clear and int(last['geometry_sections'])>0 else 0
        return stable>=15
    wait(idle,240)

def performance_audit(label):
    deadline=time.monotonic()+30
    while True:
        (out/'client/coverage.json').unlink(missing_ok=True)
        command('audit')
        wait(lambda:(out/'client/coverage.json').exists())
        report[label]=json.loads((out/'client/coverage.json').read_text(encoding='utf-8'))
        if report[label]['insufficient']==0 or time.monotonic()>=deadline:return
        time.sleep(5)

with socket.socket() as probe:
    probe.bind(('127.0.0.1', 25576))
client = None
server = launch('server', server_home)
try:
    wait(lambda: 'Done (' in (out / 'server/console.log').read_text(encoding='utf-8'), 150)
    time.sleep(5)
    if args.mode == 'performance':
        server_command('gamerule randomTickSpeed 0\ngamerule doMobSpawning false\nkill @e[type=!player]\ntime set noon\ngamerule doDaylightCycle false\nweather clear\ngamerule doWeatherCycle false')
        time.sleep(15)
    if args.mode in ('travel','extreme','performance'):
        if not (run/'import-complete').exists():
            server_command('voxydistant import')
            wait(lambda: 'import 维护任务已结束' in (out / 'server/console.log').read_text(encoding='utf-8'), 240)
            (run/'import-complete').touch()
    client = launch('client', client_home)
    wait(lambda: state().get('ready'))
    command('connect')
    wait(lambda: state().get('in_world') and state().get('lod_hello'))
    server_command('gamemode spectator LodBench\ntime set noon\ngamerule doDaylightCycle false\nweather clear\ngamerule doWeatherCycle false\ngamerule randomTickSpeed 0\ngamerule doMobSpawning false\ntp LodBench 0 180 0')
    time.sleep(3)
    if args.mode=='performance':
        command('cpu-view')
        time.sleep(10)
        receive_start=performance_mark()
    command('receive')
    if args.mode=='performance':
        performance_settle()
        performance_record('cold_receive',receive_start)
        performance_audit('cold_coverage')
        # Keep radius 24 inside the 64 x 64 saved corpus while crossing chunks repeatedly.
        time.sleep(10)
        start=performance_mark()
        command('cruise',waypoints=[[7,0],[-7,0],[0,0]]*14,speed=160)
        wait(lambda:state().get('cruise_done') is False)
        wait(lambda:state().get('cruise_done'),180)
        performance_record('first_travel',start)
        performance_settle()
        performance_audit('first_travel_coverage')
        time.sleep(15)
        start=performance_mark();time.sleep(45);performance_record('stationary',start)
        start=performance_mark()
        command('cruise',waypoints=[[7,0],[-7,0],[0,0]]*20,speed=160)
        wait(lambda:state().get('cruise_done') is False)
        wait(lambda:state().get('cruise_done'),180)
        performance_record('hot_travel',start)
        performance_settle()
        performance_audit('hot_coverage')
        report['coverage_complete']=all(report[key]['insufficient']==0 for key in ('cold_coverage','first_travel_coverage','hot_coverage'))
        report['sample']=sample()
        report['auto_ingest']=state()['auto_ingest']
        assert report['auto_ingest'] is False
        with (out/'server/player-positions.csv').open(encoding='utf-8') as file:
            positions=list(csv.DictReader(file))
        assert min(float(row['x']) for row in positions)<-80 and max(float(row['x']) for row in positions)>80
        report['server_x_bounds']=[min(float(row['x']) for row in positions),max(float(row['x']) for row in positions)]
    elif args.mode == 'request':
        command('receive-disable')
        time.sleep(1)
        report['request_check'] = command('request-check')
    elif args.mode=='extreme':
        wait(lambda:int(sample()['geometry_sections'])>0,180)
        command('cruise',waypoints=([[8,0],[-8,0],[8,8],[-8,-8],[-8,8],[8,-8],[0,0]]*8),speed=args.speed)
        wait(lambda:state().get('cruise_done'),240)
        report['peak_speed']=max(json.loads(line).get('prediction_speed',0) for line in (out/'client/states.jsonl').read_text(encoding='utf-8').splitlines())
        server_command('kill @e[type=!player]')
        time.sleep(15)
        audit('cold_fast_route',complete=args.generate_missing)
        command('renderer');time.sleep(5);audit('renderer_recovery',complete=args.generate_missing)
        # Cached origin is interrupted by two long teleports, without waiting for transfers to finish.
        for cx,cz in [(16,16),(-16,-16),(target,0),(0,0),(target,0)]:
            teleport(cx,cz);time.sleep(2)
        # This target has no server LOD cache and no saved terrain in the isolated corpus.
        command('receive-radius',radius=12)
        time.sleep(20)
        audit('uncached_teleport',seconds=240,complete=args.generate_missing)
        command('cruise',waypoints=[[target+4,4],[target-4,-4],[target,0]],speed=args.speed)
        wait(lambda:state().get('cruise_done'),120)
        time.sleep(10)
        audit('uncached_fast_route',seconds=240,complete=args.generate_missing)
        if not args.generate_missing:
            time.sleep(20);audit('uncached_stationary',seconds=240,complete=False)
        teleport(0,0);command('receive-radius',radius=24);time.sleep(10)
        server_command('kill @e[type=!player]')
        audit('return_cached',complete=args.generate_missing)
        report['auto_ingest']=state()['auto_ingest']
        assert report['auto_ingest'] is False
    else:
        wait(lambda: int(sample()['geometry_sections']) > 0 and int(sample()['pending']) == 0 and int(sample()['queue']) == 0, 180)
        command('cruise', waypoints=[[24,0],[24,24],[-24,24],[-24,-24],[24,-24],[0,0]], speed=160)
        wait(lambda: state().get('cruise_done'), 180)
        report['peak_speed'] = max(json.loads(line).get('prediction_speed', 0) for line in (out/'client/states.jsonl').read_text(encoding='utf-8').splitlines())
        server_command('tp LodBench 0 180 0')
        time.sleep(90)
        deadline = time.monotonic() + 120
        while True:
            (out/'client/coverage.json').unlink(missing_ok=True)
            command('audit')
            wait(lambda: (out/'client/coverage.json').exists(), 90)
            if json.loads((out/'client/coverage.json').read_text(encoding='utf-8'))['insufficient'] == 0:
                break
            if time.monotonic() >= deadline:
                raise AssertionError('Coverage remains incomplete after stopping')
            time.sleep(10)
        report['coverage'] = json.loads((out/'client/coverage.json').read_text(encoding='utf-8'))
        report['sample'] = sample()
        report['settled'] = command('screenshot')
        command('renderer')
        time.sleep(10)
        report['reopened'] = command('screenshot')
        report['auto_ingest'] = state()['auto_ingest']
        assert report['auto_ingest'] is False
        assert report['coverage']['insufficient'] == 0, report
        assert all(int(report['sample'][key]) == 0 for key in ('pending','mesh_queue','retries')), report
        if args.variant == 'regression':
            command('auto-ingest', enabled=True)
            time.sleep(3)
            assert state()['auto_ingest'] is True and int(sample()['applied']) > 0
            command('auto-ingest', enabled=False)
            command('receive-disable')
            wait(lambda: state().get('status') == '服务端远景接收已关闭')
            command('receive')
            wait(lambda: int(sample()['geometry_sections']) > 0 and int(sample()['pending']) == 0 and int(sample()['queue']) == 0)
            (out/'client/coverage.json').unlink()
            command('audit')
            wait(lambda: (out/'client/coverage.json').exists())
            report['toggle_coverage'] = json.loads((out/'client/coverage.json').read_text(encoding='utf-8'))
            assert report['toggle_coverage']['insufficient'] == 0, report
    if args.mode in ('extreme','performance'):
        console=(out/'client/console.log').read_text(encoding='utf-8')
        assert 'Critical error occurred in async processor' not in console
        assert 'Voxy Distant receive failed' not in console
    report['passed'] = True
finally:
    if client is not None and client.poll() is None:
        command('quit')
        client.wait(timeout=90)
    if server.poll() is None:
        server_command('stop')
        server.wait(timeout=90)
    report['exits'] = [p.returncode for p in processes]
    for log in logs:
        log.close()
    (out/'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False), flush=True)
