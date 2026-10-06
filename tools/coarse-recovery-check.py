"""Exercise delayed LOD replies and a 160 block/s route in a disposable Forge scene.

Reuse build/coarse-recovery-input and the launch specs from
build/benchmarks/lod-square256-cache128. All writes stay under build/.
"""
import argparse
import csv
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import uuid

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--mode', choices=['request', 'travel'], required=True)
parser.add_argument('--variant', default='fixed')
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
voxy = json.loads((client_home / 'config/voxy-config.json').read_text(encoding='utf-8'))
voxy.update(ingest_enabled=False, section_render_distance=.75, sub_division_size=64, service_threads=8)
(client_home / 'config/voxy-config.json').write_text(json.dumps(voxy), encoding='utf-8')
if args.variant.endswith('-cold'):
    disposable_cache = (client_home / '.voxy').resolve()
    assert disposable_cache.is_relative_to((root / 'build/coarse-recovery-travel').resolve())
    if disposable_cache.exists():
        shutil.rmtree(disposable_cache)
(client_home / 'options.txt').write_text('lang:zh_cn\nguiScale:2\nmaxFps:120\nrenderDistance:10\npauseOnLostFocus:false\ntutorialStep:none\n', encoding='utf-8')
out = (run / args.variant).resolve()
assert out.is_relative_to(run.resolve())
out.mkdir(exist_ok=False)
processes = []
logs = []
report = {'mode': args.mode, 'variant': args.variant}

def launch(kind, home):
    spec = json.loads((base / f'{kind}-launch.json').read_text(encoding='utf-8'))
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
        jargs += ['--username', 'LodBench', '--width', '1280', '--height', '720']
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

with socket.socket() as probe:
    probe.bind(('127.0.0.1', 25576))
client = None
server = launch('server', server_home)
try:
    wait(lambda: 'Done (' in (out / 'server/console.log').read_text(encoding='utf-8'), 150)
    time.sleep(5)
    if args.mode == 'travel':
        if not (run/'import-complete').exists():
            server_command('voxydistant import')
            wait(lambda: 'import 维护任务已结束' in (out / 'server/console.log').read_text(encoding='utf-8'), 240)
            (run/'import-complete').touch()
    client = launch('client', client_home)
    wait(lambda: state().get('ready'))
    command('connect')
    wait(lambda: state().get('in_world') and state().get('lod_hello'))
    server_command('gamemode spectator LodBench\ntime set noon\ngamerule doDaylightCycle false\nweather clear\ngamerule doWeatherCycle false\ntp LodBench 0 180 0')
    time.sleep(3)
    server_command('gamerule disableElytraMovementCheck true\nexecute as LodBench run data merge entity @s {FallFlying:1b}')
    command('receive')
    if args.mode == 'request':
        command('receive-disable')
        time.sleep(1)
        report['request_check'] = command('request-check')
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
