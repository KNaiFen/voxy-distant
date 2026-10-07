"""Alternate two Forge revision builds with identical disposable worlds and cold clients.

Prepare the small input and both development launches as in the performance report.
Keep only the compact per-window results; game directories and raw metrics are disposable.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import shutil
import subprocess

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--baseline-build',type=Path,required=True)
parser.add_argument('--order',default='old,new,new,old,old,new')
args=parser.parse_args()
builds={'old':args.baseline_build.resolve(),'new':root/'build'}
run=root/'build/coarse-recovery-travel'
evidence=root/'build/lod-performance-evidence'
evidence.mkdir(exist_ok=True)

def remove(path):
    path=path.resolve()
    assert path.is_relative_to((root/'build').resolve())
    if path.is_dir():shutil.rmtree(path)
    elif path.exists():path.unlink()

def rows(path,start,end):
    with path.open(encoding='utf-8') as file:
        return [row for row in csv.DictReader(file) if start<=int(row['time_ns'])<=end]

def distribution(values):
    values=sorted(values)
    return {'n':len(values),'mean':sum(values)/len(values),
            'p95':values[math.ceil(len(values)*.95)-1],'p99':values[math.ceil(len(values)*.99)-1],
            'over_33ms':sum(v>33.333 for v in values),'over_50ms':sum(v>50 for v in values)}

def cpu(samples):
    return (int(samples[-1]['process_cpu_ns'])-int(samples[0]['process_cpu_ns']))/(int(samples[-1]['time_ns'])-int(samples[0]['time_ns']))

def summarize(output):
    result=json.loads((output/'result.json').read_text(encoding='utf-8'))
    assert result['passed'] and result['exits']==[0,0]
    result['coverage_complete']=all(result[key]['insufficient']==0 for key in ('cold_coverage','first_travel_coverage','hot_coverage'))
    assert not (output/'client/gpu-visibility.csv').exists()
    assert not (output/'client/node-samples.jsonl').exists()
    metrics={}
    for label,window in result['windows'].items():
        start,end=window['start'],window['end']
        frames=rows(output/'client/frames.csv',start['client'],end['client'])
        ticks=rows(output/'server/server-ticks.csv',start['server'],end['server'])
        client=rows(output/'client/client-samples.csv',start['client'],end['client'])
        server=rows(output/'server/server-samples.csv',start['server'],end['server'])
        states=[json.loads(line) for line in (output/'client/states.jsonl').read_text(encoding='utf-8').splitlines()]
        states=[s for s in states if start['client']<=s['nano_time']<=end['client']]
        assert all(s['vsync'] is False and s['max_fps']==260 for s in states)
        thread_cpu={}
        for side,bounds,name in [('client','client','threads.csv'),('server','server','server-threads.csv')]:
            by_thread={}
            for row in rows(output/side/name,start[bounds],end[bounds]):
                by_thread.setdefault((row['id'],row['name']),[]).append(row)
            totals={}
            for (_,thread),samples in by_thread.items():
                if len(samples)<2:continue
                cores=(int(samples[-1]['cpu_ns'])-int(samples[0]['cpu_ns']))/(int(samples[-1]['time_ns'])-int(samples[0]['time_ns']))
                totals[thread]=totals.get(thread,0)+cores
            thread_cpu[side]=totals
        metrics[label]={
            'seconds':(end['client']-start['client'])/1e9,
            'frames_ms':distribution([int(f['frame_ns'])/1e6 for f in frames if f['in_world']=='true']),
            'render_ms':distribution([int(f['render_ns'])/1e6 for f in frames if f['in_world']=='true']),
            'ticks_ms':distribution([int(t['tick_ns'])/1e6 for t in ticks]),
            'client_cpu_cores':cpu(client),'server_cpu_cores':cpu(server),'thread_cpu_cores':thread_cpu,
            'receive_memory_peak_mib':max(int(s['memory']) for s in client)/2**20,
            'queue_peak':max(int(s['queue']) for s in client),'pending_peak':max(int(s['pending']) for s in client),
            'mesh_queue_peak':max(int(s['mesh_queue']) for s in client),
            'client_heap_peak_mib':max(int(s['heap_bytes']) for s in client)/2**20,
            'geometry_sections_end':int(client[-1]['geometry_sections']),
            'applied_delta':int(client[-1]['applied'])-int(client[0]['applied']),
            'received_bytes_delta':int(client[-1]['received'])-int(client[0]['received']),
            'hits_delta':int(server[-1]['hits'])-int(server[0]['hits']),
            'misses_delta':int(server[-1]['misses'])-int(server[0]['misses']),
            'completed_delta':int(server[-1]['completed'])-int(server[0]['completed']),
            'peak_speed':max(s['prediction_speed'] for s in states),
        }
    result['metrics']=metrics
    return result

results=[]
for index,revision in enumerate(args.order.split(','),1):
    assert revision in builds
    for name in ('server-game','game','import-complete'):
        remove(run/name)
    variant=f'perf-{index}-{revision}-cold'
    output=run/variant
    assert not output.exists()
    print(f'START {variant}',flush=True)
    command=['python',str(root/'tools/coarse-recovery-check.py'),'--mode','performance','--variant',variant,
             '--launch-build',str(builds[revision])]
    completed=subprocess.run(command,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8')
    print(completed.stdout,flush=True)
    if completed.returncode:
        raise RuntimeError(f'{variant} failed; retained minimal scene for diagnosis at {run}')
    result=summarize(output)
    if revision=='new':
        assert all(result[key]['insufficient']==0 for key in ('cold_coverage','first_travel_coverage','hot_coverage')),result
    result['revision']=revision
    result['command']=command
    result['main_class_hashes']={str(p.relative_to(builds[revision])):hashlib.sha256(p.read_bytes()).hexdigest()
                                 for p in (builds[revision]/'classes/java/main').rglob('Remote*.class')}
    results.append(result)
    (evidence/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    size=sum(p.stat().st_size for p in output.rglob('*') if p.is_file())
    for name in ('server-game','game','import-complete'):
        path=run/name
        if path.is_dir():size+=sum(p.stat().st_size for p in path.rglob('*') if p.is_file())
        remove(path)
    remove(output)
    print(f'CLEAN {variant} bytes={size}',flush=True)
print(f'PERFORMANCE_PASS rounds={len(results)} results={evidence}',flush=True)
